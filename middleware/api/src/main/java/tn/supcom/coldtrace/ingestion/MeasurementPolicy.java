package tn.supcom.coldtrace.ingestion;

import org.bson.Document;
import tn.supcom.coldtrace.model.Measurement;
import java.time.Instant;
import java.util.Date;

/** Deterministic event-time rules shared by ingestion, status and alerts. */
public final class MeasurementPolicy {
    public static final long FRESH_SECONDS = positiveEnv("SENSOR_FRESH_SECONDS", 30);
    public static final long OFFLINE_SECONDS = Math.max(FRESH_SECONDS, positiveEnv("SENSOR_OFFLINE_SECONDS", 60));
    public static final long FUTURE_SECONDS = positiveEnv("MEASUREMENT_FUTURE_SECONDS", 30);
    private MeasurementPolicy() { }

    public static long positiveEnv(String name, long fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) return fallback;
        long n = Long.parseLong(value);
        if (n < 1) throw new IllegalArgumentException(name + " must be positive");
        return n;
    }

    public static String physicalError(Measurement m) {
        if (!id(m.getSiteId()) || !id(m.getSensorId())) return "invalid siteId or sensorId";
        if (m.getMsgId().length() > 128 || !m.getMsgId().matches("[A-Za-z0-9_.:-]+")) return "invalid msgId";
        if (!range(m.getTemperature(), -40, 85)) return "temperature outside -40..85";
        if (!range(m.getHumidity(), 0, 100)) return "humidity outside 0..100";
        if (m.getSeq() != null && m.getSeq() < 0) return "negative sequence";
        return null;
    }

    public static boolean id(String value) { return value != null && value.matches("[A-Za-z0-9_-]{1,32}"); }
    private static boolean range(Double n, double min, double max) { return n == null || Double.isFinite(n) && n >= min && n <= max; }
    public static boolean active(Document d) { return d == null || !Boolean.FALSE.equals(d.get("active")); }
    public static boolean live(Measurement m, Instant now, Instant watermark) {
        Instant ts = Instant.parse(m.getTs());
        return !Boolean.TRUE.equals(m.getReplayed()) && !ts.isBefore(now.minusSeconds(FRESH_SECONDS))
                && !ts.isAfter(now.plusSeconds(FUTURE_SECONDS)) && (watermark == null || ts.isAfter(watermark));
    }
    public static String status(boolean enabled, Date eventTime, Instant now) {
        if (!enabled) return "disabled";
        if (eventTime == null) return "unknown";
        long age = Math.max(0, now.getEpochSecond() - eventTime.toInstant().getEpochSecond());
        return age <= FRESH_SECONDS ? "online" : age <= OFFLINE_SECONDS ? "stale" : "offline";
    }
}
