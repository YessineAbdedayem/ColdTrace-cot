package tn.supcom.coldtrace.iam.db;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.util.logging.Logger;

/**
 * The IAM's MongoDB database. MONGO_URI is the platform's app user (users live in coldtrace.users), e.g.
 * mongodb://USER:PASSWORD@mongo:27017/coldtrace?authSource=coldtrace
 */
@ApplicationScoped
public class MongoProducer {

    private static final Logger LOG = Logger.getLogger(MongoProducer.class.getName());

    private MongoClient client;
    private String dbName;

    @PostConstruct
    void init() {
        String uri = System.getenv("MONGO_URI");
        ConnectionString cs = new ConnectionString(uri == null || uri.isBlank() ? "mongodb://localhost:27017/coldtrace" : uri);
        dbName = cs.getDatabase() == null ? "coldtrace" : cs.getDatabase();
        client = MongoClients.create(cs);
        // Never log the URI itself: it contains the password.
        LOG.info("MongoDB client for hosts " + cs.getHosts() + ", database '" + dbName + "'");
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
