package tn.supcom.coldtrace.report;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;
import tn.supcom.coldtrace.business.Catalog;
import tn.supcom.coldtrace.model.Incident;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Reading history, indicators (KPI) and the compliance report (EF-06, EF-07). Reads only: nothing is changed.
 *
 * Every stored reading counts, replayed ones included: they are part of the history even if they arrived late.
 * Compliance uses the limits configured now for the sensor's profile.
 */
@ApplicationScoped
public class ReportService {

    public static final int MAX_POINTS = 2000;
    public static final int MAX_EXPORT_ROWS = 200_000;

    @Inject MongoDatabase db;
    @Inject Catalog catalog;

    public record Limits(Double temperatureMin, Double temperatureMax, Double humidityMin, Double humidityMax) { }

    /** One time bucket of the history chart. */
    public record Point(String t, Double temperature, Double temperatureMin, Double temperatureMax, Double humidity,
                        Boolean doorOpen, int readings) { }

    public record History(String sensorId, String siteId, String name, String profile, Limits limits, String from,
                          String to, long bucketSeconds, List<Point> points, List<Incident> incidents) { }

    public record SensorKpi(String siteId, String sensorId, String name, String profile, Limits limits, long readings,
                            Double temperatureMin, Double temperatureAvg, Double temperatureMax, Double humidityAvg,
                            Double compliancePercent, int incidents, Map<String, Integer> incidentsByMetric,
                            double alarmMinutes, Double meanAckMinutes, int unacknowledged, int open) { }

    public record Summary(String from, String to, String siteId, String generatedAt, int sensors, long readings,
                          Double compliancePercent, int incidents, Map<String, Integer> incidentsByMetric,
                          double alarmMinutes, Double meanAckMinutes, int unacknowledged, int open,
                          List<SensorKpi> perSensor) { }

    // ---------- history ----------

    public History history(String sensorId, ReportWindow window, int points) {
        if (points < 10 || points > MAX_POINTS) throw tn.supcom.coldtrace.business.ApiProblem.invalid("points must be 10.." + MAX_POINTS);
        Document sensor = catalog.require("sensors", sensorId);
        long bucket = window.bucketMillis(points);
        Document bucketStart = new Document("$subtract", List.of(new Document("$toLong", "$ts"),
                new Document("$mod", List.of(new Document("$toLong", "$ts"), bucket))));
        List<Document> pipeline = List.of(
                new Document("$match", new Document("sensorId", sensorId)
                        .append("ts", new Document("$gte", window.fromDate()).append("$lt", window.toDate()))),
                new Document("$group", new Document("_id", bucketStart)
                        .append("t", new Document("$avg", "$temperature"))
                        .append("tMin", new Document("$min", "$temperature"))
                        .append("tMax", new Document("$max", "$temperature"))
                        .append("h", new Document("$avg", "$humidity"))
                        .append("door", new Document("$max", "$doorOpen"))
                        .append("n", new Document("$sum", 1))),
                new Document("$sort", new Document("_id", 1)));
        List<Point> result = new ArrayList<>();
        db.getCollection("measurements").aggregate(pipeline).allowDiskUse(true).forEach(d -> result.add(new Point(
                Instant.ofEpochMilli(((Number) d.get("_id")).longValue()).toString(),
                round(d.get("t")), round(d.get("tMin")), round(d.get("tMax")), round(d.get("h")),
                d.get("door") instanceof Boolean b ? b : null, ((Number) d.get("n")).intValue())));
        List<Incident> incidents = new ArrayList<>();
        db.getCollection("incidents").find(Filters.and(Filters.eq("sensorId", sensorId), overlaps(window)))
                .sort(Sorts.ascending("openedAt")).limit(500).forEach(d -> incidents.add(Incident.fromDocument(d)));
        return new History(sensorId, sensor.getString("siteId"), sensor.getString("name"), catalog.profile(sensor),
                limits(sensor), window.from().toString(), window.to().toString(), bucket / 1000, result, incidents);
    }

    // ---------- indicators ----------

    public Summary summary(ReportWindow window, String siteId) {
        if (siteId != null) catalog.require("sites", siteId);
        List<SensorKpi> perSensor = new ArrayList<>();
        IncidentStats all = new IncidentStats();
        long readings = 0, inRange = 0;
        for (Document sensor : sensors(siteId)) {
            String id = sensor.getString("_id");
            Limits limits = limits(sensor);
            Document stats = readingStats(id, window, limits);
            long n = stats == null ? 0 : ((Number) stats.get("n")).longValue();
            long ok = stats == null ? 0 : ((Number) stats.get("ok")).longValue();
            IncidentStats incidents = new IncidentStats();
            db.getCollection("incidents").find(Filters.and(Filters.eq("sensorId", id), overlaps(window)))
                    .forEach(d -> incidents.add(d, window));
            readings += n;
            inRange += ok;
            all.addAll(incidents);
            perSensor.add(new SensorKpi(sensor.getString("siteId"), id, sensor.getString("name"), catalog.profile(sensor),
                    limits, n, stats == null ? null : round(stats.get("tMin")), stats == null ? null : round(stats.get("tAvg")),
                    stats == null ? null : round(stats.get("tMax")), stats == null ? null : round(stats.get("hAvg")),
                    IncidentStats.compliance(ok, n), incidents.count(), incidents.byMetric(), incidents.alarmMinutes(),
                    incidents.meanAckMinutes(), incidents.unacknowledged(), incidents.open()));
        }
        return new Summary(window.from().toString(), window.to().toString(), siteId, Instant.now().toString(),
                perSensor.size(), readings, IncidentStats.compliance(inRange, readings), all.count(), all.byMetric(),
                all.alarmMinutes(), all.meanAckMinutes(), all.unacknowledged(), all.open(), perSensor);
    }

    /** The incidents of the window, oldest first (for the report's incident list). */
    public List<Document> incidents(ReportWindow window, String siteId, int limit) {
        Bson filter = siteId == null ? overlaps(window) : Filters.and(Filters.eq("siteId", siteId), overlaps(window));
        List<Document> result = new ArrayList<>();
        db.getCollection("incidents").find(filter).sort(Sorts.ascending("openedAt")).limit(limit).forEach(result::add);
        return result;
    }

    /** Streams raw readings, oldest first, at most {@link #MAX_EXPORT_ROWS}. */
    public void readings(ReportWindow window, String siteId, String sensorId, Consumer<Document> sink) {
        List<Bson> filters = new ArrayList<>(List.of(Filters.gte("ts", window.fromDate()), Filters.lt("ts", window.toDate())));
        if (siteId != null) filters.add(Filters.eq("siteId", Catalog.id(siteId)));
        if (sensorId != null) filters.add(Filters.eq("sensorId", Catalog.id(sensorId)));
        db.getCollection("measurements").find(Filters.and(filters)).sort(Sorts.ascending("ts"))
                .limit(MAX_EXPORT_ROWS).forEach(sink::accept);
    }

    // ---------- helpers ----------

    private List<Document> sensors(String siteId) {
        List<Document> result = new ArrayList<>();
        db.getCollection("sensors").find(siteId == null ? Filters.empty() : Filters.eq("siteId", siteId))
                .sort(Sorts.ascending("siteId", "_id")).forEach(result::add);
        return result;
    }

    private Document readingStats(String sensorId, ReportWindow window, Limits l) {
        List<Object> checks = new ArrayList<>();
        addRange(checks, "$temperature", l == null ? null : l.temperatureMin(), l == null ? null : l.temperatureMax());
        addRange(checks, "$humidity", l == null ? null : l.humidityMin(), l == null ? null : l.humidityMax());
        Object inRange = checks.isEmpty() ? 1 : new Document("$cond", List.of(new Document("$and", checks), 1, 0));
        List<Document> pipeline = List.of(
                new Document("$match", new Document("sensorId", sensorId)
                        .append("ts", new Document("$gte", window.fromDate()).append("$lt", window.toDate()))),
                new Document("$group", new Document("_id", null)
                        .append("n", new Document("$sum", 1))
                        .append("ok", new Document("$sum", inRange))
                        .append("tMin", new Document("$min", "$temperature"))
                        .append("tAvg", new Document("$avg", "$temperature"))
                        .append("tMax", new Document("$max", "$temperature"))
                        .append("hAvg", new Document("$avg", "$humidity"))));
        return db.getCollection("measurements").aggregate(pipeline).first();
    }

    /** A value is in range when it is absent, or within the limits that are set. */
    private static void addRange(List<Object> checks, String field, Double min, Double max) {
        if (min == null && max == null) return;
        List<Object> within = new ArrayList<>();
        if (min != null) within.add(new Document("$gte", List.of(field, min)));
        if (max != null) within.add(new Document("$lte", List.of(field, max)));
        checks.add(new Document("$or", List.of(
                new Document("$not", List.of(new Document("$isNumber", field))),
                new Document("$and", within))));
    }

    private Limits limits(Document sensor) {
        Document t = catalog.threshold(sensor);
        return t == null ? null : new Limits(num(t.get("temperatureMin")), num(t.get("temperatureMax")),
                num(t.get("humidityMin")), num(t.get("humidityMax")));
    }

    /** Incidents that were open at some point of the window. */
    static Bson overlaps(ReportWindow window) {
        return Filters.and(Filters.lt("openedAt", window.toDate()),
                Filters.or(Filters.ne("status", Incident.CLOSED), Filters.gte("closedAt", window.fromDate())));
    }

    private static Double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    private static Double round(Object o) {
        return o instanceof Number n ? Math.round(n.doubleValue() * 10) / 10.0 : null;
    }

    static String iso(Date d) {
        return d == null ? "" : d.toInstant().toString();
    }
}
