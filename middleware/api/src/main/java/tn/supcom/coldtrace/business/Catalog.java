package tn.supcom.coldtrace.business;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import tn.supcom.coldtrace.ingestion.MeasurementPolicy;
import java.util.*;
import static tn.supcom.coldtrace.business.ApiProblem.*;

/** Business configuration only. Historical measurements and incidents are never deleted. */
@ApplicationScoped
public class Catalog {
    @Inject MongoDatabase db;
    public static Document body(String json, String... allowed) {
        Document d;
        try { d = Document.parse(json == null ? "" : json); } catch (RuntimeException e) { throw invalid("body must be a JSON object"); }
        if (d == null || !Set.of(allowed).containsAll(d.keySet())) throw invalid("unsupported field in request");
        return d;
    }
    public static String id(String value) { if (!MeasurementPolicy.id(value)) throw invalid("id: 1..32 letters, digits, '-' or '_'"); return value; }
    public static String text(Document d, String field, int max, boolean required) {
        Object value = d.get(field);
        if (value == null && !required) return null;
        if (!(value instanceof String s) || s.trim().isEmpty() || s.trim().length() > max) throw invalid(field + ": nonempty text, maximum " + max);
        return s.trim();
    }
    public Document require(String collection, String identifier) {
        Document found = db.getCollection(collection).find(Filters.eq("_id", id(identifier))).first();
        if (found == null) throw missing(collection + "/" + identifier);
        return found;
    }
    public static Map<String, Object> view(Document source) {
        Map<String, Object> result = new LinkedHashMap<>(source);
        result.put("id", result.remove("_id"));
        result.replaceAll((k,v) -> v instanceof Date date ? date.toInstant().toString() : v);
        result.putIfAbsent("active", true);
        return result;
    }
    public List<Map<String, Object>> list(String collection, int offset, int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        db.getCollection(collection).find().sort(Sorts.ascending("_id")).skip(offset(offset)).limit(limit(limit)).forEach(d -> result.add(view(d)));
        return result;
    }
    public static int offset(int offset) { if (offset < 0) throw invalid("offset must be nonnegative"); return offset; }
    public static int limit(int limit) { if (limit < 1 || limit > 500) throw invalid("limit must be 1..500"); return limit; }
    public boolean enabled(Document sensor) {
        return MeasurementPolicy.active(sensor) && MeasurementPolicy.active(db.getCollection("sites").find(Filters.eq("_id", sensor.getString("siteId"))).first());
    }
    public String profile(Document sensor) {
        String profile = sensor.getString("profile");
        if (profile != null) return profile;
        Document site = db.getCollection("sites").find(Filters.eq("_id", sensor.getString("siteId"))).first();
        return site == null ? null : site.getString("defaultProfile");
    }
    public Document threshold(Document sensor) {
        String profile = profile(sensor);
        return profile == null ? null : db.getCollection("thresholds").find(Filters.eq("_id", profile)).first();
    }
    private void checkProfile(String profile) { if (profile != null) require("thresholds", id(profile)); }
    private void insert(String collection, Document value) {
        try { db.getCollection(collection).insertOne(value); }
        catch (MongoWriteException e) {
            if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) throw conflict("id already exists");
            throw e;
        }
    }
    private void save(String collection, Document value, boolean create) {
        long version = value.get("version") instanceof Number n ? n.longValue() : 0;
        value.put("version", version + 1);
        if (create) { insert(collection, value); return; }
        var expected = version == 0 ? Filters.or(Filters.eq("version", 0), Filters.exists("version", false)) : Filters.eq("version", version);
        if (db.getCollection(collection).replaceOne(Filters.and(Filters.eq("_id", value.get("_id")), expected), value).getMatchedCount() != 1)
            throw conflict("configuration changed concurrently; reload and retry");
    }
    public Document site(String identifier, String json, boolean create) {
        Document req = body(json, "id", "name", "location", "defaultProfile", "active", "operators");
        String key = create ? id(text(req, "id", 32, true)) : id(identifier);
        Document value = create ? new Document("_id", key).append("active", true).append("createdAt", new Date()) : require("sites", key);
        if (req.containsKey("id") && !key.equals(req.get("id"))) throw invalid("id is immutable");
        if (create || req.containsKey("name")) value.put("name", text(req, "name", 120, true));
        if (req.containsKey("location")) value.put("location", text(req, "location", 240, false));
        if (req.containsKey("defaultProfile")) { String p = text(req, "defaultProfile", 32, false); checkProfile(p); value.put("defaultProfile", p); }
        if (req.containsKey("operators")) value.put("operators", usernames(req.get("operators")));
        applyActive(req, value);
        value.put("updatedAt", new Date());
        save("sites", value, create);
        return value;
    }
    public Document sensor(String identifier, String json, boolean create) {
        Document req = body(json, "id", "siteId", "name", "type", "profile", "active");
        String key = create ? id(text(req, "id", 32, true)) : id(identifier);
        Document value = create ? new Document("_id", key).append("active", true).append("createdAt", new Date()) : require("sensors", key);
        if (req.containsKey("id") && !key.equals(req.get("id"))) throw invalid("id is immutable");
        if (create || req.containsKey("siteId")) {
            String siteId = id(text(req, "siteId", 32, true));
            if (!MeasurementPolicy.active(require("sites", siteId))) throw conflict("site is inactive");
            String old = value.getString("siteId");
            if (old != null && !old.equals(siteId)) {
                if (db.getCollection("incidents").countDocuments(Filters.and(Filters.eq("sensorId", key), Filters.eq("status", "open"))) > 0)
                    throw conflict("resolve open incidents before reassigning a sensor");
                List<String> prior = new ArrayList<>(value.getList("previousSiteIds", String.class, List.of()));
                if (!prior.contains(old)) prior.add(old);
                value.put("previousSiteIds", prior);
                value.put("assignedAt", new Date());
            }
            value.put("siteId", siteId);
        }
        if (create || req.containsKey("name")) value.put("name", text(req, "name", 120, true));
        if (req.containsKey("type")) value.put("type", text(req, "type", 80, false));
        if (req.containsKey("profile")) { String p = text(req, "profile", 32, false); checkProfile(p); value.put("profile", p); }
        applyActive(req, value);
        value.put("updatedAt", new Date());
        save("sensors", value, create);
        return value;
    }
    /** The drivers / operators assigned to a site (IAM usernames): they receive its alerts on their phone. */
    public static List<String> usernames(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list) || list.size() > 50) throw invalid("operators: a list of at most 50 usernames");
        Set<String> result = new TreeSet<>();
        for (Object o : list) {
            if (!(o instanceof String s) || !s.matches("[a-z0-9._-]{3,32}")) throw invalid("operators: usernames of 3..32 characters among a-z 0-9 . - _");
            result.add(s);
        }
        return new ArrayList<>(result);
    }
    private static void applyActive(Document request, Document target) {
        if (!request.containsKey("active")) return;
        if (!(request.get("active") instanceof Boolean b)) throw invalid("active must be boolean");
        target.put("active", b);
    }
    public Document deactivate(String collection, String key) {
        Document value = require(collection, key);
        value.put("active", false);
        value.put("updatedAt", new Date());
        save(collection, value, false);
        return value;
    }
    public static Document thresholdBody(String json) {
        Document req = body(json, "temperatureMin", "temperatureMax", "humidityMin", "humidityMax");
        if (req.isEmpty()) throw invalid("at least one limit field is required");
        for (String key : req.keySet()) {
            Object v = req.get(key);
            if (v != null && (!(v instanceof Number n) || !Double.isFinite(n.doubleValue()))) throw invalid(key + " must be finite or null");
            if (v != null) req.put(key, ((Number)v).doubleValue());
        }
        return req;
    }
    public static void validateThreshold(Document d) {
        for (String metric : List.of("temperature", "humidity")) {
            Double min = number(d.get(metric + "Min")), max = number(d.get(metric + "Max"));
            double lo = metric.equals("temperature") ? -40 : 0, hi = metric.equals("temperature") ? 85 : 100;
            if (min != null && (min < lo || min > hi) || max != null && (max < lo || max > hi)) throw invalid(metric + " limit outside physical range");
            if (min != null && max != null && min > max) throw invalid(metric + "Min must be <= " + metric + "Max");
        }
    }
    private static Double number(Object value) { return value == null ? null : ((Number)value).doubleValue(); }
    public Document threshold(String key, String json) {
        id(key);
        Document value = db.getCollection("thresholds").find(Filters.eq("_id", key)).first();
        boolean create = value == null;
        if (create) value = new Document("_id", key);
        value.putAll(thresholdBody(json));
        validateThreshold(value);
        value.put("updatedAt", new Date());
        save("thresholds", value, create);
        return value;
    }
}
