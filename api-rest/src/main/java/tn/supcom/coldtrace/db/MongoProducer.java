package tn.supcom.coldtrace.db;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOptions;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.Produces;
import org.bson.Document;
import tn.supcom.coldtrace.model.Incident;
import tn.supcom.coldtrace.model.Threshold;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the single MongoClient and exposes the ColdTrace database for injection.
 *
 * MONGO_URI is expected to use the app user, e.g.
 * mongodb://USER:PASSWORD@mongo:27017/coldtrace?authSource=coldtrace
 * The database name comes from the URI path (default "coldtrace").
 */
@ApplicationScoped
public class MongoProducer {

    private static final Logger LOG = Logger.getLogger(MongoProducer.class.getName());
    private static final String DEFAULT_URI = "mongodb://localhost:27017/coldtrace";
    private static final String DEFAULT_DB = "coldtrace";

    private MongoClient client;
    private String dbName;

    @PostConstruct
    void init() {
        String uri = System.getenv("MONGO_URI");
        ConnectionString cs = new ConnectionString(uri == null || uri.isBlank() ? DEFAULT_URI : uri);
        dbName = cs.getDatabase() == null ? DEFAULT_DB : cs.getDatabase();
        client = MongoClients.create(cs);
        // Never log the URI itself: it contains the password.
        LOG.info("MongoDB client for hosts " + cs.getHosts() + ", database '" + dbName + "'"
                + (cs.getUsername() == null ? " (no authentication)" : ", user '" + cs.getUsername() + "'"));
    }

    /** Runs before the MQTT listener (lower priority value = earlier). */
    void onStartup(@Observes @Priority(1) Startup event) {
        try {
            // Same names and keys as gateway-iot/mongo/init-app-user.sh, so both can run on one database.
            MongoCollection<Document> measurements = database().getCollection("measurements");
            measurements.createIndex(Indexes.ascending("msgId"),
                    new IndexOptions().unique(true).name("msgId_unique"));
            measurements.createIndex(
                    Indexes.compoundIndex(Indexes.ascending("siteId", "sensorId"), Indexes.descending("ts")),
                    new IndexOptions().name("site_sensor_ts"));
            LOG.info("MongoDB ready, measurements indexes ensured");

            // At most one open incident per sensor and metric.
            database().getCollection("incidents").createIndex(
                    Indexes.ascending("sensorId", "metric"),
                    new IndexOptions().unique(true).name("one_open_incident")
                            .partialFilterExpression(Filters.eq("status", Incident.OPEN)));
            insertMissingThresholds();
            LOG.info("Alert indexes and default thresholds ensured");
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not prepare MongoDB (is MongoDB reachable?)", e);
        }
    }

    /** Adds the default limits only for profiles that have none, so later edits are kept. */
    private void insertMissingThresholds() {
        MongoCollection<Document> thresholds = database().getCollection("thresholds");
        for (Threshold threshold : Threshold.DEFAULTS) {
            Document fields = threshold.toDocument();
            fields.remove("_id");
            thresholds.updateOne(
                    Filters.eq("_id", threshold.getProfile()),
                    new Document("$setOnInsert", fields),
                    new UpdateOptions().upsert(true));
        }
    }

    @Produces
    public MongoDatabase database() {
        return client.getDatabase(dbName);
    }

    @PreDestroy
    void close() {
        if (client != null) {
            client.close();
        }
    }
}
