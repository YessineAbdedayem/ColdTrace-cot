package tn.supcom.coldtrace.ingestion;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.*;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.annotation.Resource;
import jakarta.enterprise.concurrent.ManagedScheduledExecutorService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import org.bson.Document;
import tn.supcom.coldtrace.alert.AlertEngine;
import tn.supcom.coldtrace.business.Catalog;
import tn.supcom.coldtrace.db.MongoProducer;
import tn.supcom.coldtrace.model.GatewayStatus;
import tn.supcom.coldtrace.model.Measurement;
import tn.supcom.coldtrace.mqtt.MqttListener;
import java.nio.file.Path;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

@ApplicationScoped
public class IngestionService {
    private static final Logger LOG = Logger.getLogger(IngestionService.class.getName());
    @Inject MongoDatabase db;
    @Inject MongoProducer mongo;
    @Inject Catalog catalog;
    @Inject SensorState states;
    @Inject AlertEngine alerts;
    @Resource ManagedScheduledExecutorService scheduler;
    private final Jsonb json = JsonbBuilder.create();
    private DurableInbox inbox;
    private ScheduledFuture<?> task;
    private volatile String lastFailure;
    private volatile boolean accepting = true;
    private long nextAttempt;
    private volatile long backoff = 1000, persisted, duplicates;
    private boolean databaseReady;

    void start(@Observes @Priority(5) Startup event) throws IOException {
        String directory = System.getenv("INGESTION_DIR");
        if (directory == null || directory.isBlank()) directory = System.getProperty("jboss.server.data.dir", System.getProperty("java.io.tmpdir")) + "/coldtrace-inbox";
        inbox = new DurableInbox(Path.of(directory), (int)MeasurementPolicy.positiveEnv("INGESTION_MAX_FILES", 20000),
                MeasurementPolicy.positiveEnv("INGESTION_MAX_BYTES", 268435456));
        task = scheduler.scheduleWithFixedDelay(this::drain, 1, 1, TimeUnit.SECONDS);
    }
    /** Returning means fsync+atomic rename succeeded; only then may MQTT acknowledge. */
    public void accept(String topic, String payload) throws IOException {
        try { inbox.append(topic, payload, Instant.now()); accepting = true; }
        catch (IOException e) { accepting = false; lastFailure = "inbox_unwritable_or_full"; throw e; }
    }
    public Map<String,Object> status() throws IOException {
        Map<String,Object> result = new LinkedHashMap<>(inbox.status());
        result.put("accepting", accepting); result.put("persistedThisRun", persisted); result.put("duplicatesThisRun", duplicates);
        result.put("retryBackoffMs", backoff); result.put("lastFailure", lastFailure);
        result.put("durabilityBoundary", "backend_fsync");
        return result;
    }
    public List<Map<String,Object>> quarantined(int limit) throws IOException { return inbox.rejected(limit); }
    private void drain() {
        if (System.currentTimeMillis() < nextAttempt) return;
        try {
            if (!databaseReady) { mongo.ensureReady(); databaseReady = true; }
            for (Path file : inbox.pending(50)) {
                DurableInbox.Entry entry = inbox.read(file);
                String rejection = process(entry);
                if (rejection == null) inbox.complete(file);
                else { inbox.quarantine(entry, rejection); LOG.warning("Quarantined " + entry.topic() + ": " + rejection); }
            }
            backoff = 1000;
            if (accepting) lastFailure = null;
        } catch (Exception e) {
            databaseReady = false;
            lastFailure = "retry_pending_" + e.getClass().getSimpleName();
            LOG.warning("Ingestion paused; durable events retained (" + e.getClass().getSimpleName() + "), retry in " + backoff + "ms");
            nextAttempt = System.currentTimeMillis() + backoff;
            backoff = Math.min(30000, backoff * 2);
        }
    }
    private String process(DurableInbox.Entry entry) {
        String[] topic = entry.topic().split("/", -1);
        if (topic.length == 4 && topic[0].equals("coldtrace") && topic[2].equals("gateway") && topic[3].equals("status")) {
            GatewayStatus s;
            try { s = json.fromJson(entry.payload(), GatewayStatus.class); } catch (RuntimeException e) { return "invalid_json"; }
            String error = MqttListener.validate(s);
            if (error != null) return error;
            db.getCollection("gateways").updateOne(Filters.eq("_id", topic[1]), new Document("$set", s.toDocument()), new UpdateOptions().upsert(true));
            return null;
        }
        if (topic.length != 4 || !topic[0].equals("coldtrace") || !topic[3].equals("telemetry")) return "invalid_topic";
        Measurement m;
        try { m = json.fromJson(entry.payload(), Measurement.class); } catch (RuntimeException e) { return "invalid_json"; }
        if (m == null) return "empty_payload";
        m.setSiteId(topic[1]); m.setSensorId(topic[2]);
        String error = MqttListener.validate(m);
        if (error != null) return error;
        error = MeasurementPolicy.physicalError(m);
        if (error != null) return error;
        if (Instant.parse(m.getTs()).isAfter(entry.ingestedAt().plusSeconds(MeasurementPolicy.FUTURE_SECONDS))) return "future_timestamp";
        Document sensor = db.getCollection("sensors").find(Filters.eq("_id", m.getSensorId())).first();
        if (sensor == null) return "unknown_sensor";
        boolean assigned = m.getSiteId().equals(sensor.getString("siteId"));
        if (!assigned && !sensor.getList("previousSiteIds", String.class, List.of()).contains(m.getSiteId())) return "site_mismatch";

        Document stored = db.getCollection("measurements").find(Filters.eq("msgId", m.getMsgId())).first();
        Document state = db.getCollection("ingestion_state").find(Filters.eq("_id", m.getSensorId())).first();
        if (stored == null) {
            Document previous = states.latest(sensor);
            Date watermark = state != null ? state.getDate("lastAppliedTs") : previous == null ? null : previous.getDate("ts");
            boolean live = assigned && catalog.enabled(sensor) && MeasurementPolicy.live(m, Instant.now(), watermark == null ? null : watermark.toInstant());
            Date assignedAt = sensor.getDate("assignedAt");
            if (assignedAt != null && Instant.parse(m.getTs()).isBefore(assignedAt.toInstant())) live = false;
            stored = m.toDocument().append("ingestedAt", Date.from(entry.ingestedAt()))
                    .append("liveEligible", live).append("ingestionStatus", "pending");
            try { db.getCollection("measurements").insertOne(stored); persisted++; }
            catch (MongoWriteException e) {
                if (e.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) throw e;
                stored = db.getCollection("measurements").find(Filters.eq("msgId", m.getMsgId())).first();
            }
        } else duplicates++;
        for (String field : List.of("v", "siteId", "sensorId", "ts", "temperature", "humidity", "doorOpen", "seq")) {
            if (!Objects.equals(stored.get(field), m.toDocument().get(field))) return "message_identity_collision";
        }
        if ("complete".equals(stored.getString("ingestionStatus")) || !stored.containsKey("ingestionStatus")) return null;
        boolean apply = Boolean.TRUE.equals(stored.getBoolean("liveEligible")) && assigned && catalog.enabled(sensor)
                && !Instant.parse(m.getTs()).isBefore(Instant.now().minusSeconds(MeasurementPolicy.FRESH_SECONDS));
        if (apply && (state == null || !m.getMsgId().equals(state.getString("lastAppliedMsgId")))) {
            if (state == null || state.getDate("lastAppliedTs") == null || Instant.parse(m.getTs()).isAfter(state.getDate("lastAppliedTs").toInstant())) {
                alerts.process(m); // Per-incident event markers make partial retries idempotent.
                db.getCollection("ingestion_state").updateOne(Filters.eq("_id", m.getSensorId()),
                        new Document("$set", new Document("lastAppliedTs", Date.from(Instant.parse(m.getTs())))
                                .append("lastAppliedMsgId", m.getMsgId())), new UpdateOptions().upsert(true));
            } else apply = false;
        }
        db.getCollection("measurements").updateOne(Filters.eq("msgId", m.getMsgId()),
                Updates.combine(Updates.set("ingestionStatus", "complete"), Updates.set("liveEligible", apply)));
        return null;
    }
    @PreDestroy void stop() throws Exception { if (task != null) task.cancel(false); json.close(); }
}
