package tn.supcom.coldtrace.iam.auth;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows down password guessing: after {@link #MAX_FAILURES} wrong passwords for a username within
 * {@link #WINDOW}, that username is locked for {@link #LOCK}. A successful login clears the count.
 *
 * In memory: a restart clears it, which is acceptable for one IAM instance. The username is counted whether or not it
 * exists, so the answer never tells an attacker which usernames are real.
 */
@ApplicationScoped
public class LoginGuard {

    public static final int MAX_FAILURES = 5;
    public static final Duration WINDOW = Duration.ofMinutes(15);
    public static final Duration LOCK = Duration.ofMinutes(5);
    private static final int MAX_TRACKED = 10_000;

    private record Entry(int failures, Instant first, Instant lockedUntil) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /** @return when the lock ends, or null if this username may try now */
    public Instant lockedUntil(String username, Instant now) {
        Entry e = entries.get(key(username));
        return e != null && e.lockedUntil() != null && now.isBefore(e.lockedUntil()) ? e.lockedUntil() : null;
    }

    public void failed(String username, Instant now) {
        if (entries.size() > MAX_TRACKED) {
            entries.clear(); // a flood of random usernames must not fill the memory
        }
        entries.compute(key(username), (k, e) -> {
            if (e == null || now.isAfter(e.first().plus(WINDOW))) {
                e = new Entry(0, now, null);
            }
            int failures = e.failures() + 1;
            return new Entry(failures, e.first(), failures >= MAX_FAILURES ? now.plus(LOCK) : e.lockedUntil());
        });
    }

    public void succeeded(String username) {
        entries.remove(key(username));
    }

    private static String key(String username) {
        return username == null ? "" : username.toLowerCase();
    }
}
