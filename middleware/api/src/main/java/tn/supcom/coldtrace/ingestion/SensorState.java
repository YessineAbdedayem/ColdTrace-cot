package tn.supcom.coldtrace.ingestion;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;
import tn.supcom.coldtrace.business.Catalog;
import java.time.Instant;
import java.util.Date;

@ApplicationScoped
public class SensorState {
    @Inject MongoDatabase db;
    @Inject Catalog catalog;
    /** Legacy records are read by event time, except explicitly replayed records. */
    public static Bson eligible() {
        return Filters.or(Filters.and(Filters.eq("liveEligible", true), Filters.eq("ingestionStatus", "complete")),
                Filters.and(Filters.exists("liveEligible", false), Filters.ne("replayed", true)));
    }
    public Document latest(Document sensor) {
        return db.getCollection("measurements").find(Filters.and(eligible(),
                Filters.eq("siteId", sensor.getString("siteId")), Filters.eq("sensorId", sensor.getString("_id")),
                sensor.getDate("assignedAt") == null ? Filters.empty() : Filters.gte("ts", sensor.getDate("assignedAt")),
                Filters.lte("ts", Date.from(Instant.now().plusSeconds(MeasurementPolicy.FUTURE_SECONDS)))))
                .sort(Sorts.descending("ts")).first();
    }
    public String status(Document sensor, Document latest) {
        return MeasurementPolicy.status(catalog.enabled(sensor), latest == null ? null : latest.getDate("ts"), Instant.now());
    }
}
