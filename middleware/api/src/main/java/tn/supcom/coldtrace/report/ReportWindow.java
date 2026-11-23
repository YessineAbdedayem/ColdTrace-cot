package tn.supcom.coldtrace.report;

import tn.supcom.coldtrace.business.ApiProblem;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Date;

/**
 * The period a report covers, [from, to). Both ISO-8601 instants (e.g. 2026-11-23T00:00:00Z).
 * Without "to": now. Without "from": 24 hours before "to". At most {@link #MAX_DAYS} days.
 */
public record ReportWindow(Instant from, Instant to) {

    public static final long MAX_DAYS = 366;

    public static ReportWindow parse(String from, String to, Instant now) {
        Instant end = to == null || to.isBlank() ? now : instant("to", to);
        Instant start = from == null || from.isBlank() ? end.minus(Duration.ofHours(24)) : instant("from", from);
        if (!start.isBefore(end)) throw ApiProblem.invalid("from must be before to");
        if (Duration.between(start, end).toDays() > MAX_DAYS) throw ApiProblem.invalid("at most " + MAX_DAYS + " days per report");
        return new ReportWindow(start, end);
    }

    private static Instant instant(String name, String value) {
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw ApiProblem.invalid(name + " must be an ISO-8601 instant, e.g. 2026-11-23T00:00:00Z");
        }
    }

    public Date fromDate() { return Date.from(from); }
    public Date toDate() { return Date.from(to); }
    public long millis() { return Duration.between(from, to).toMillis(); }

    /** The part of [start, end) inside this window, in milliseconds. A missing end means "still open". */
    public long overlapMillis(Instant start, Instant end) {
        if (start == null) return 0;
        Instant a = start.isAfter(from) ? start : from;
        Instant e = end == null || end.isAfter(to) ? to : end;
        return Math.max(0, Duration.between(a, e).toMillis());
    }

    /** Bucket size so that the window gives at most {@code points} points, never below 5 s (one reading). */
    public long bucketMillis(int points) {
        return Math.max(5000, (millis() + points - 1) / points);
    }
}
