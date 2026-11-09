package tn.supcom.coldtrace.rest;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.bson.Document;
import tn.supcom.coldtrace.model.Measurement;
import tn.supcom.coldtrace.model.Sensor;
import tn.supcom.coldtrace.model.Threshold;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /api/sensors: every known sensor with its profile limits, latest reading and recent temperatures.
 * One call gives a dashboard everything it needs to draw the sensors.
 */
@Path("/sensors")
@RequestScoped
public class SensorResource {

    /** Number of recent readings returned per sensor (2 minutes at one reading every 5 s). */
    private static final int RECENT = 24;

    @Inject
    MongoDatabase db;

    public record Limits(Double temperatureMin, Double temperatureMax, Double humidityMin, Double humidityMax) {
    }

    public record Reading(String ts, Double temperature, Double humidity, Boolean doorOpen, Boolean replayed) {
    }

    public record SensorView(String id, String siteId, String name, String profile, Limits limits,
                             Reading latest, List<Double> recentTemperatures) {
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<SensorView> list() {
        Map<String, Limits> limitsByProfile = new HashMap<>();
        for (Document doc : db.getCollection("thresholds").find()) {
            Threshold t = Threshold.fromDocument(doc);
            limitsByProfile.put(t.getProfile(),
                    new Limits(t.getTemperatureMin(), t.getTemperatureMax(), t.getHumidityMin(), t.getHumidityMax()));
        }

        List<SensorView> result = new ArrayList<>();
        for (Document doc : db.getCollection("sensors").find().sort(Sorts.ascending("_id"))) {
            Sensor s = Sensor.fromDocument(doc);
            // Filter on siteId and sensorId so the query uses the {siteId, sensorId, ts} index.
            List<Measurement> recent = new ArrayList<>();
            db.getCollection("measurements")
                    .find(Filters.and(Filters.eq("siteId", s.getSiteId()), Filters.eq("sensorId", s.getId())))
                    .sort(Sorts.descending("ts")).limit(RECENT)
                    .forEach(m -> recent.add(Measurement.fromDocument(m)));

            Reading latest = recent.isEmpty() ? null : toReading(recent.get(0));
            List<Double> temperatures = new ArrayList<>();
            for (Measurement m : recent) {
                if (m.getTemperature() != null) temperatures.add(m.getTemperature());
            }
            Collections.reverse(temperatures); // oldest first, ready to draw
            result.add(new SensorView(s.getId(), s.getSiteId(), s.getName(), s.getProfile(),
                    limitsByProfile.get(s.getProfile()), latest, temperatures));
        }
        return result;
    }

    private static Reading toReading(Measurement m) {
        return new Reading(m.getTs(), m.getTemperature(), m.getHumidity(), m.getDoorOpen(), m.getReplayed());
    }
}
