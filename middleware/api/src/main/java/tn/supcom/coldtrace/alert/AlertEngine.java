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
 * The early_warning metric comes from the model's forecasts instead of readings: see {@link #applyForecast}.
 */
@ApplicationScoped
public class AlertEngine {

    private static final Logger LOG = Logger.getLogger(AlertEngine.class.getName());
    private static final String SILENCE = "silence";
    /** Opened by {@link EarlyWarningPoller} when the model predicts the temperature limit will be crossed. */
    static final String EARLY_WARNING = "early_warning";
    // closeReason of an early warning
    static final String CLOSED_CALM = "calm";               // the forecasts went back below the limit
    static final String CLOSED_REPLACED = "replaced";       // the real temperature incident opened
    static final String CLOSED_NO_FORECAST = "no_forecast"; // no forecast for EARLY_WARNING_STALE_SECONDS
    private static final long SILENCE_CHECK_SECONDS = 15;

    @Inject
    MongoDatabase db;

    @Inject
    AlertBroadcaster broadcaster;
    @Inject
    tn.supcom.coldtrace.business.Catalog catalog;

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
        Document configured = db.getCollection("sensors").find(Filters.eq("_id", m.getSensorId())).first();
        if (configured == null || !catalog.enabled(configured)) return;
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
        Document thresholdDoc = catalog.threshold(sensorDoc);
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
        if (current != null && m.getMsgId().equals(current.getString("lastEventId"))) return;
        boolean breached = AlertRules.isBreached(value, min, max);
        int inRange = current == null ? 0 : current.getInteger("inRangeReadings", 0);
        Date readingTime = Date.from(Instant.parse(m.getTs()));

        switch (AlertRules.decide(current != null, breached, inRange)) {
            case NONE -> { }
            case OPEN -> open(m.getSiteId(), m.getSensorId(), metric, value, min, max, readingTime, m.getMsgId());
            case STILL_BREACHED -> incidents.updateOne(openIncident, Updates.combine(
                    Updates.set("lastEventId", m.getMsgId()),
                    Updates.set("inRangeReadings", 0),
                    AlertRules.isAbove(value, max) ? Updates.max("peakValue", value) : Updates.min("peakValue", value)));
            case RECOVERING -> incidents.updateOne(openIncident, Updates.combine(Updates.inc("inRangeReadings", 1), Updates.set("lastEventId", m.getMsgId())));
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
                if (!catalog.enabled(sensor)) continue;
                String sensorId = sensor.getString("_id");
                Document last = db.getCollection("measurements").find(Filters.and(Filters.eq("sensorId", sensorId),
                                Filters.eq("siteId", sensor.getString("siteId")), Filters.ne("liveEligible", false), Filters.ne("replayed", true),
                                Filters.lte("ts", Date.from(now.plusSeconds(tn.supcom.coldtrace.ingestion.MeasurementPolicy.FUTURE_SECONDS)))))
                        .sort(Sorts.descending("ts")).limit(1).first();
                if (last == null || last.getDate("ts") == null) {
                    continue; // never heard from: not installed yet rather than silent
                }
                long silentFor = Duration.between(last.getDate("ts").toInstant(), now).getSeconds();
                if (silentFor <= tn.supcom.coldtrace.ingestion.MeasurementPolicy.OFFLINE_SECONDS) {
                    continue;
                }
                Bson openIncident = openIncident(sensorId, SILENCE);
                if (db.getCollection("incidents").find(openIncident).first() == null) {
                    open(sensor.getString("siteId"), sensorId, SILENCE, silentFor,
                            null, (double) tn.supcom.coldtrace.ingestion.MeasurementPolicy.OFFLINE_SECONDS, Date.from(now));
                } else {
                    db.getCollection("incidents").updateOne(openIncident, Updates.max("peakValue", (double) silentFor));
                }
            }
        } catch (RuntimeException e) {
            // Never let the scheduled task die: it would not be rescheduled.
            LOG.log(Level.WARNING, "Silent-sensor check failed", e);
        }
    }

    /**
     * Applies one forecast of the early-warning model to the sensor's "early_warning" incident.
     * Its value is the temperature when it opened, and peakValue the highest predicted maximum while it lasts.
     */
    public void applyForecast(String siteId, Forecast f) {
        MongoCollection<Document> incidents = db.getCollection("incidents");
        Bson openWarning = openIncident(f.sensorId(), EARLY_WARNING);
        Document current = incidents.find(openWarning).first();
        boolean temperatureOpen = incidents.find(openIncident(f.sensorId(), "temperature")).first() != null;
        int calm = current == null ? 0 : current.getInteger("inRangeReadings", 0);
        Date now = new Date();
        Document lastTemperature = temperatureOpen ? null : incidents.find(Filters.and(
                        Filters.eq("sensorId", f.sensorId()), Filters.eq("metric", "temperature"),
                        Filters.eq("status", Incident.CLOSED)))
                .sort(Sorts.descending("closedAt")).limit(1).first();
        Date closedAt = lastTemperature == null ? null : lastTemperature.getDate("closedAt");
        boolean justRecovered = closedAt != null && AlertRules.isJustRecovered(
                Duration.between(closedAt.toInstant(), now.toInstant()).getSeconds());
        if (justRecovered) {
            // Hold back only while the unit cools down after its alert: warming again is a new failure.
            justRecovered = !AlertRules.isWarmingAgain(
                    readingSince(siteId, f.sensorId(), closedAt, Sorts.descending("ts")),
                    readingSince(siteId, f.sensorId(), closedAt, Sorts.ascending("temperature")));
        }

        switch (AlertRules.decideEarlyWarning(current != null, f.warning(), temperatureOpen, justRecovered, calm)) {
            case NONE -> { }
            case OPEN -> {
                Document doc = Incident.newOpenDocument(siteId, f.sensorId(), EARLY_WARNING, f.temperature(),
                                null, f.limitMax(), now)
                        .append("peakValue", f.predictedMax())
                        .append("modelVersion", f.modelVersion())
                        .append("horizonMin", f.horizonMin())
                        .append("lastForecastAt", now);
                insertAndBroadcast(doc);
            }
            case STILL_BREACHED -> incidents.updateOne(openWarning, Updates.combine(
                    Updates.set("inRangeReadings", 0), Updates.max("peakValue", f.predictedMax()),
                    Updates.set("lastForecastAt", now)));
            case RECOVERING -> incidents.updateOne(openWarning, Updates.combine(
                    Updates.inc("inRangeReadings", 1), Updates.set("lastForecastAt", now)));
            case CLOSE -> close(openWarning, now, temperatureOpen ? CLOSED_REPLACED : CLOSED_CALM);
        }
    }

    /** One temperature reading of the sensor since `since`: the first in `order` (latest, or lowest). */
    private Double readingSince(String siteId, String sensorId, Date since, Bson order) {
        Bson filter = Filters.and(Filters.eq("sensorId", sensorId), Filters.gte("ts", since),
                Filters.ne("temperature", null));
        if (siteId != null) {
            filter = Filters.and(Filters.eq("siteId", siteId), filter);   // uses the site_sensor_ts index
        }
        Document d = db.getCollection("measurements").find(filter).sort(order).limit(1).first();
        return d == null || !(d.get("temperature") instanceof Number n) ? null : n.doubleValue();
    }

    /**
     * Closes the open early warnings that got no forecast for {@link AlertRules#EARLY_WARNING_STALE_SECONDS}.
     * Without this, a warning would stay open for ever when the model API stops answering for its sensor.
     */
    public void closeStaleEarlyWarnings() {
        Instant now = Instant.now();
        for (Document w : db.getCollection("incidents").find(
                Filters.and(Filters.eq("metric", EARLY_WARNING), Filters.eq("status", Incident.OPEN)))) {
            Date last = w.getDate("lastForecastAt") != null ? w.getDate("lastForecastAt") : w.getDate("openedAt");
            if (last == null || AlertRules.isForecastStale(Duration.between(last.toInstant(), now).getSeconds())) {
                close(Filters.and(Filters.eq("_id", w.get("_id")), Filters.eq("status", Incident.OPEN)),
                        Date.from(now), CLOSED_NO_FORECAST);
            }
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
        open(siteId, sensorId, metric, value, min, max, openedAt, null);
    }

    private void open(String siteId, String sensorId, String metric, double value, Double min, Double max,
                      Date openedAt, String eventId) {
        Document incident = Incident.newOpenDocument(siteId, sensorId, metric, value, min, max, openedAt);
        if (eventId != null) incident.put("lastEventId", eventId);
        if (insertAndBroadcast(incident)
                && "temperature".equals(metric)) {
            // The real alert replaces the early warning that announced it.
            close(openIncident(sensorId, EARLY_WARNING), openedAt, CLOSED_REPLACED);
        }
    }

    /** @return false when an open incident for this sensor and metric already existed */
    private boolean insertAndBroadcast(Document doc) {
        try {
            db.getCollection("incidents").insertOne(doc);
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
                throw e;
            }
            return false; // the partial unique index already holds an open incident for this sensor and metric
        }
        LOG.info("Incident opened: " + doc.getString("sensorId") + " " + doc.getString("metric")
                + "=" + doc.get("value"));
        broadcaster.broadcast(new AlertEvent(AlertEvent.OPENED, Incident.fromDocument(doc)));
        return true;
    }

    private void close(Bson openIncident, Date readingTime) {
        close(openIncident, readingTime, null);
    }

    /** @param reason stored as closeReason when not null */
    private void close(Bson openIncident, Date readingTime, String reason) {
        Bson update = Updates.combine(Updates.set("status", Incident.CLOSED), Updates.set("closedAt", readingTime));
        if (reason != null) {
            update = Updates.combine(update, Updates.set("closeReason", reason));
        }
        Document closed = db.getCollection("incidents").findOneAndUpdate(
                openIncident, update, new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
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
