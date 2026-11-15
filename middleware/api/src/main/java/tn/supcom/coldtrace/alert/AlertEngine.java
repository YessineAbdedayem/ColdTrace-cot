package tn.supcom.coldtrace.alert;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.Sorts;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import jakarta.enterprise.concurrent.ManagedScheduledExecutorService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;
import tn.supcom.coldtrace.model.Incident;
import tn.supcom.coldtrace.model.Measurement;
import tn.supcom.coldtrace.model.Sensor;
import tn.supcom.coldtrace.model.Threshold;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Compares each new reading with the limits of its sensor's profile, then opens or closes incidents.
 * The incident state lives in MongoDB, so an API restart continues where it stopped.
 *
 * Metrics: temperature and humidity (limits from the profile), door (open = breach),
 * and silence (no reading from a known sensor for {@link AlertRules#SILENT_AFTER_SECONDS}).
 */
@ApplicationScoped
public class AlertEngine {

    private static final Logger LOG = Logger.getLogger(AlertEngine.class.getName());
    private static final String SILENCE = "silence";
    private static final long SILENCE_CHECK_SECONDS = 15;

    @Inject
    MongoDatabase db;

    @Inject
    AlertBroadcaster broadcaster;

    @Resource
    ManagedScheduledExecutorService scheduler;

    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private ScheduledFuture<?> silenceCheck;

    void onStartup(@Observes Startup event) {
        silenceCheck = scheduler.scheduleWithFixedDelay(this::checkSilentSensors,
                SILENCE_CHECK_SECONDS, SILENCE_CHECK_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        if (silenceCheck != null) {
            silenceCheck.cancel(false);
        }
    }

    public void process(Measurement m) {
        Date readingTime = Date.from(Instant.parse(m.getTs()));
        // Any reading means the sensor is talking again.
        close(openIncident(m.getSensorId(), SILENCE), readingTime);

        Document sensorDoc = db.getCollection("sensors").find(Filters.eq("_id", m.getSensorId())).first();
        if (sensorDoc == null) {
            warnOnce("sensor:" + m.getSensorId(), "Unknown sensor " + m.getSensorId() + ": no alerts for its readings");
            return;
        }
        if (m.getDoorOpen() != null) {
            evaluate(m, "door", AlertRules.doorValue(m.getDoorOpen()), null, AlertRules.DOOR_MAX);
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
        Bson openIncident = openIncident(m.getSensorId(), metric);
        Document current = incidents.find(openIncident).first();
        boolean breached = AlertRules.isBreached(value, min, max);
        int inRange = current == null ? 0 : current.getInteger("inRangeReadings", 0);
        Date readingTime = Date.from(Instant.parse(m.getTs()));

        switch (AlertRules.decide(current != null, breached, inRange)) {
            case NONE -> { }
            case OPEN -> open(m.getSiteId(), m.getSensorId(), metric, value, min, max, readingTime);
            case STILL_BREACHED -> incidents.updateOne(openIncident, Updates.combine(
                    Updates.set("inRangeReadings", 0),
                    AlertRules.isAbove(value, max) ? Updates.max("peakValue", value) : Updates.min("peakValue", value)));
            case RECOVERING -> incidents.updateOne(openIncident, Updates.inc("inRangeReadings", 1));
            case CLOSE -> close(openIncident, readingTime);
        }
    }

    /**
     * Opens a "silence" incident for each known sensor whose last reading is too old.
     * Its value is the number of seconds without a reading, and peakValue keeps growing while it lasts.
     */
    void checkSilentSensors() {
        try {
            Instant now = Instant.now();
            for (Document sensor : db.getCollection("sensors").find()) {
                String sensorId = sensor.getString("_id");
                Document last = db.getCollection("measurements").find(Filters.eq("sensorId", sensorId))
                        .sort(Sorts.descending("ts")).limit(1).first();
                if (last == null || last.getDate("ts") == null) {
                    continue; // never heard from: not installed yet rather than silent
                }
                long silentFor = Duration.between(last.getDate("ts").toInstant(), now).getSeconds();
                if (!AlertRules.isSilent(silentFor)) {
                    continue;
                }
                Bson openIncident = openIncident(sensorId, SILENCE);
                if (db.getCollection("incidents").find(openIncident).first() == null) {
                    open(sensor.getString("siteId"), sensorId, SILENCE, silentFor,
                            null, (double) AlertRules.SILENT_AFTER_SECONDS, Date.from(now));
                } else {
                    db.getCollection("incidents").updateOne(openIncident, Updates.max("peakValue", (double) silentFor));
                }
            }
        } catch (RuntimeException e) {
            // Never let the scheduled task die: it would not be rescheduled.
            LOG.log(Level.WARNING, "Silent-sensor check failed", e);
        }
    }

    private static Bson openIncident(String sensorId, String metric) {
        return Filters.and(
                Filters.eq("sensorId", sensorId),
                Filters.eq("metric", metric),
                Filters.eq("status", Incident.OPEN));
    }

    private void open(String siteId, String sensorId, String metric, double value, Double min, Double max,
                      Date openedAt) {
        Document doc = Incident.newOpenDocument(siteId, sensorId, metric, value, min, max, openedAt);
        try {
            db.getCollection("incidents").insertOne(doc);
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
                throw e;
            }
            return; // the partial unique index already holds an open incident for this sensor and metric
        }
        LOG.info("Incident opened: " + sensorId + " " + metric + "=" + value);
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
