package tn.supcom.coldtrace.db;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Indexes;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.Produces;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Owns the single MongoClient and exposes the "coldtrace" database for injection. */
@ApplicationScoped
public class MongoProducer {

    private static final Logger LOG = Logger.getLogger(MongoProducer.class.getName());
    private static final String DB_NAME = "coldtrace";

    private MongoClient client;

    @PostConstruct
    void init() {
        String uri = System.getenv("MONGO_URI");
        client = MongoClients.create(uri == null || uri.isBlank() ? "mongodb://localhost:27017" : uri);
    }

    /** Runs before the MQTT listener (lower priority value = earlier). */
    void onStartup(@Observes @Priority(1) Startup event) {
        try {
            database().getCollection("measurements")
                    .createIndex(Indexes.compoundIndex(Indexes.ascending("sensorId"), Indexes.descending("ts")));
            LOG.info("MongoDB ready, index measurements(sensorId asc, ts desc) ensured");
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not create MongoDB index (is MongoDB reachable?)", e);
        }
    }

    @Produces
    public MongoDatabase database() {
        return client.getDatabase(DB_NAME);
    }

    @PreDestroy
    void close() {
        if (client != null) {
            client.close();
        }
    }
}
