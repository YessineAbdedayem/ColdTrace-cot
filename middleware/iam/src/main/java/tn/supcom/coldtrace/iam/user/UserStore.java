package tn.supcom.coldtrace.iam.user;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.security.enterprise.identitystore.Pbkdf2PasswordHash;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The users in coldtrace.users, with their passwords hashed by Jakarta Security's PBKDF2. */
@ApplicationScoped
public class UserStore {

    @Inject
    MongoDatabase db;

    @Inject
    Pbkdf2PasswordHash hash;

    /** A hash of a random password, checked when the username does not exist, so both cases take the same time. */
    private String dummyHash;

    @PostConstruct
    void init() {
        // OWASP 2023 guidance for PBKDF2-HMAC-SHA256; the defaults (1024 iterations) are far too low.
        hash.initialize(Map.of(
                "Pbkdf2PasswordHash.Algorithm", "PBKDF2WithHmacSHA256",
                "Pbkdf2PasswordHash.Iterations", "210000",
                "Pbkdf2PasswordHash.SaltSizeBytes", "32",
                "Pbkdf2PasswordHash.KeySizeBytes", "32"));
        dummyHash = hash.generate(java.util.UUID.randomUUID().toString().toCharArray());
    }

    private MongoCollection<Document> users() {
        return db.getCollection("users");
    }

    public List<User> list() {
        List<User> out = new ArrayList<>();
        for (Document d : users().find().sort(Sorts.ascending("_id"))) {
            out.add(User.fromDocument(d));
        }
        return out;
    }

    public Optional<User> find(String username) {
        return Optional.ofNullable(users().find(Filters.eq("_id", username)).first()).map(User::fromDocument);
    }

    /** The user if the password is right and the account enabled; otherwise empty, in about the same time. */
    public Optional<User> check(String username, char[] password) {
        Document d = username == null ? null : users().find(Filters.eq("_id", username)).first();
        if (d == null || d.getString("passwordHash") == null) {
            hash.verify(password, dummyHash);
            return Optional.empty();
        }
        boolean ok = hash.verify(password, d.getString("passwordHash"));
        return ok && d.getBoolean("enabled", true) ? Optional.of(User.fromDocument(d)) : Optional.empty();
    }

    /** @return false if the username is taken */
    public boolean create(String username, String name, Set<String> roles, char[] password) {
        Document d = new Document("_id", username)
                .append("name", name == null || name.isBlank() ? username : name.strip())
                .append("roles", List.copyOf(roles))
                .append("enabled", true)
                .append("passwordHash", hash.generate(password))
                .append("createdAt", User.now())
                .append("updatedAt", User.now());
        try {
            users().insertOne(d);
            return true;
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                return false;
            }
            throw e;
        }
    }

    /** Changes only the fields that are not null. @return false if the user does not exist */
    public boolean update(String username, String name, Set<String> roles, Boolean enabled, char[] password) {
        List<Bson> changes = new ArrayList<>();
        changes.add(Updates.set("updatedAt", User.now()));
        if (name != null) {
            changes.add(Updates.set("name", name.strip()));
        }
        if (roles != null) {
            changes.add(Updates.set("roles", List.copyOf(roles)));
        }
        if (enabled != null) {
            changes.add(Updates.set("enabled", enabled));
        }
        if (password != null) {
            changes.add(Updates.set("passwordHash", hash.generate(password)));
        }
        return users().updateOne(Filters.eq("_id", username), Updates.combine(changes)).getMatchedCount() == 1;
    }

    public boolean delete(String username) {
        return users().deleteOne(Filters.eq("_id", username)).getDeletedCount() == 1;
    }

    /** Enabled administrators other than `username`: there must always be one left. */
    public long otherEnabledAdmins(String username) {
        return users().countDocuments(Filters.and(Filters.ne("_id", username), Filters.eq("roles", Roles.ADMIN),
                Filters.ne("enabled", false)));
    }

    public long enabledAdmins() {
        return users().countDocuments(Filters.and(Filters.eq("roles", Roles.ADMIN), Filters.ne("enabled", false)));
    }
}
