package tn.supcom.coldtrace.iam.user;

import org.bson.Document;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * A user as the API shows it: never with the password hash, which only {@link UserStore} reads.
 * In MongoDB (coldtrace.users): _id = username, name, roles, enabled, passwordHash, createdAt, updatedAt.
 */
public record User(String username, String name, Set<String> roles, boolean enabled, String createdAt,
                   String updatedAt) {

    static User fromDocument(Document d) {
        List<String> roles = d.getList("roles", String.class, List.of());
        return new User(d.getString("_id"), d.getString("name"), new TreeSet<>(roles),
                d.getBoolean("enabled", true), iso(d.getDate("createdAt")), iso(d.getDate("updatedAt")));
    }

    private static String iso(Date date) {
        return date == null ? null : date.toInstant().toString();
    }

    static Date now() {
        return Date.from(Instant.now());
    }
}
