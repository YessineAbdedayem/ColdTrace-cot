package tn.supcom.coldtrace.alert;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;
import tn.supcom.coldtrace.model.Incident;
import tn.supcom.coldtrace.model.Measurement;
import tn.supcom.coldtrace.model.Sensor;
import tn.supcom.coldtrace.model.Threshold;

import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Compares each new reading with the limits of its sensor's profile, then opens or closes incidents.
 * The incident state lives in MongoDB, so an API restart continues where it stopped.
 */
@ApplicationScoped
public class AlertEngine {

    private static final Logger LOG = Logger.getLogger(AlertEngine.class.getName());

    @Inject
    MongoDatabase db;

    @Inject
    AlertBroadcaster broadcaster;

    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public void process(Measurement m) {
        Document sensorDoc = db.getCollection("sensors").find(Filters.eq("_id", m.getSensorId())).first();
        if (sensorDoc == null) {
            warnOnce("sensor:" + m.getSensorId(), "Unknown sensor " + m.getSensorId() + ": no alerts for its readings");
            return;
        }
        Sensor sensor = Sensor.fromDocument(sensorDoc);
        Document thresholdDoc = db.getCollection("thresholds").find(Filters.eq("_id", sensor.getProfile())).first();
        if (thresholdDoc == null) {
            warnOnce("profile:" + sensor.getProfile(),
                    "No thresholds for profile '" + sensor.getProfile() + "' of sensor " + m.getSensorId());
            return;
        }
        Threshold t = Threshold.fromDocument(thresholdDoc);
        evaluate(m, "temperature", m.getTemperature(), t.getTemperatureMin(), t.getTemperatureMax());
        evaluate(m, "humidity", m.getHumidity(), t.getHumidityMin(), t.getHumidityMax());
    }

    private void evaluate(Measurement m, String metric, Double value, Double min, Double max) {
        if (value == null) {
            return;
        }
        MongoCollection<Document> incidents = db.getCollection("incidents");
        Bson openIncident = Filters.and(
                Filters.eq("sensorId", m.getSensorId()),
                Filters.eq("metric", metric),
                Filters.eq("status", Incident.OPEN));
        Document current = incidents.find(openIncident).first();
        boolean breached = AlertRules.isBreached(value, min, max);
        int inRange = current == null ? 0 : current.getInteger("inRangeReadings", 0);
        Date readingTime = Date.from(Instant.parse(m.getTs()));

        switch (AlertRules.decide(current != null, breached, inRange)) {
            case NONE -> { }
            case OPEN -> open(m, metric, value, min, max, readingTime);
            case STILL_BREACHED -> incidents.updateOne(openIncident, Updates.combine(
                    Updates.set("inRangeReadings", 0),
                    AlertRules.isAbove(value, max) ? Updates.max("peakValue", value) : Updates.min("peakValue", value)));
            case RECOVERING -> incidents.updateOne(openIncident, Updates.inc("inRangeReadings", 1));
            case CLOSE -> close(openIncident, readingTime);
        }
    }

    private void open(Measurement m, String metric, double value, Double min, Double max, Date readingTime) {
        Document doc = Incident.newOpenDocument(m.getSiteId(), m.getSensorId(), metric, value, min, max, readingTime);
        try {
            db.getCollection("incidents").insertOne(doc);
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
                throw e;
            }
            return; // the partial unique index already holds an open incident for this sensor and metric
        }
        LOG.info("Incident opened: " + m.getSensorId() + " " + metric + "=" + value);
        broadcaster.broadcast(new AlertEvent(AlertEvent.OPENED, Incident.fromDocument(doc)));
    }

    private void close(Bson openIncident, Date readingTime) {
        Document closed = db.getCollection("incidents").findOneAndUpdate(
                openIncident,
                Updates.combine(Updates.set("status", Incident.CLOSED), Updates.set("closedAt", readingTime)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (closed != null) {
            LOG.info("Incident closed: " + closed.getString("sensorId") + " " + closed.getString("metric"));
            broadcaster.broadcast(new AlertEvent(AlertEvent.CLOSED, Incident.fromDocument(closed)));
        }
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) {
            LOG.warning(message);
        }
    }
}
