package tn.supcom.coldtrace.report;

import org.bson.Document;

import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Incident indicators of one sensor (or of all of them) over a report window. Pure arithmetic, unit tested.
 *
 * <ul>
 *   <li>alarm time: how long temperature or humidity was out of range, counted inside the window only;</li>
 *   <li>mean time to acknowledge (MTTA): from opening to acknowledgement, over the acknowledged incidents;</li>
 *   <li>unacknowledged: incidents nobody acknowledged (open or closed).</li>
 * </ul>
 */
public final class IncidentStats {

    /** The metrics whose duration counts as "out of range" time. */
    static final Set<String> ALARM_METRICS = Set.of("temperature", "humidity");

    private int count;
    private int open;
    private int unacknowledged;
    private long alarmMillis;
    private long ackMillis;
    private int acknowledged;
    private final Map<String, Integer> byMetric = new TreeMap<>();

    public void add(Document incident, ReportWindow window) {
        count++;
        String metric = incident.getString("metric") == null ? "unknown" : incident.getString("metric");
        byMetric.merge(metric, 1, Integer::sum);
        Instant opened = instant(incident.getDate("openedAt"));
        Instant closed = instant(incident.getDate("closedAt"));
        Instant acked = instant(incident.getDate("acknowledgedAt"));
        if (!"closed".equals(incident.getString("status"))) open++;
        if (ALARM_METRICS.contains(metric)) alarmMillis += window.overlapMillis(opened, closed);
        if (acked == null) {
            unacknowledged++;
        } else if (opened != null && !acked.isBefore(opened)) {
            ackMillis += acked.toEpochMilli() - opened.toEpochMilli();
            acknowledged++;
        }
    }

    public void addAll(IncidentStats other) {
        count += other.count;
        open += other.open;
        unacknowledged += other.unacknowledged;
        alarmMillis += other.alarmMillis;
        ackMillis += other.ackMillis;
        acknowledged += other.acknowledged;
        other.byMetric.forEach((k, v) -> byMetric.merge(k, v, Integer::sum));
    }

    public int count() { return count; }
    public int open() { return open; }
    public int unacknowledged() { return unacknowledged; }
    public Map<String, Integer> byMetric() { return Map.copyOf(byMetric); }
    public double alarmMinutes() { return round1(alarmMillis / 60000.0); }
    public Double meanAckMinutes() { return acknowledged == 0 ? null : round1(ackMillis / 60000.0 / acknowledged); }

    /** In-range readings as a percentage of all readings, or null without readings. */
    public static Double compliance(long inRange, long readings) {
        return readings <= 0 ? null : round1(100.0 * inRange / readings);
    }

    static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static Instant instant(Date d) {
        return d == null ? null : d.toInstant();
    }
}
