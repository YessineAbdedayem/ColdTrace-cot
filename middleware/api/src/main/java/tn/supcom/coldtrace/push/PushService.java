package tn.supcom.coldtrace.push;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Updates;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import org.bson.Document;
import tn.supcom.coldtrace.business.ApiProblem;
import tn.supcom.coldtrace.model.Incident;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Web Push notifications of new incidents to the phones and PCs of the people concerned (EF-04), even when the
 * front-office is closed. The browser subscribes (front-office, "Enable alerts on this device"); the subscription is
 * kept in coldtrace.push_subscriptions with the user's name and roles; {@link PushRules} picks the recipients.
 *
 * Settings: PUSH_ENABLED (default true), PUSH_SUBJECT (VAPID contact, default mailto:admin@coldtrace.test),
 * PUSH_KEYS_DIR (default KEYS_DIR, then the server data folder), PUSH_ALLOWED_HOSTS (comma-separated push services).
 */
@ApplicationScoped
public class PushService {

    private static final Logger LOG = Logger.getLogger(PushService.class.getName());
    static final String COLLECTION = "push_subscriptions";
    private static final int MAX_PER_USER = 10;

    @Inject MongoDatabase db;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1).build();
    private KeyPair vapid;
    private String subject;
    private List<String> hosts;
    private String unavailable;

    @PostConstruct
    void init() {
        subject = env("PUSH_SUBJECT", "mailto:admin@coldtrace.test");
        hosts = PushRules.hosts(System.getenv("PUSH_ALLOWED_HOSTS"));
        if ("false".equalsIgnoreCase(env("PUSH_ENABLED", "true"))) {
            unavailable = "push notifications are turned off (PUSH_ENABLED=false)";
            return;
        }
        String dir = env("PUSH_KEYS_DIR", env("KEYS_DIR",
                System.getProperty("jboss.server.data.dir", System.getProperty("java.io.tmpdir")) + "/coldtrace-push"));
        try {
            vapid = VapidKeys.loadOrCreate(Path.of(dir));
            LOG.info("Web Push ready (VAPID keys in " + dir + ")");
        } catch (Exception e) {
            unavailable = "push notifications are unavailable: no VAPID keys";
            LOG.log(Level.WARNING, "Web Push disabled: cannot read or create the VAPID keys in " + dir, e);
        }
    }

    public boolean enabled() { return vapid != null; }

    /** The key browsers need to subscribe (applicationServerKey), base64url. */
    public String publicKey() {
        if (vapid == null) throw new ApiProblem(503, "push_unavailable", unavailable);
        return WebPush.base64Url(WebPush.raw((java.security.interfaces.ECPublicKey) vapid.getPublic()));
    }

    public void subscribe(String username, Collection<String> roles, String endpoint, String p256dh, String auth,
                          String userAgent) {
        if (vapid == null) throw new ApiProblem(503, "push_unavailable", unavailable);
        if (!PushRules.allowedEndpoint(endpoint, hosts)) throw ApiProblem.invalid("endpoint is not a known browser push service");
        byte[] key, secret;
        try {
            key = WebPush.fromBase64Url(p256dh);
            secret = WebPush.fromBase64Url(auth);
            WebPush.publicKey(key);
        } catch (Exception e) {
            throw ApiProblem.invalid("keys.p256dh must be a P-256 public key and keys.auth 16 bytes (base64url)");
        }
        if (secret.length != 16) throw ApiProblem.invalid("keys.auth must be 16 bytes");
        String id = id(endpoint);
        var collection = db.getCollection(COLLECTION);
        if (collection.countDocuments(Filters.and(Filters.eq("username", username), Filters.ne("_id", id))) >= MAX_PER_USER) {
            throw ApiProblem.conflict("at most " + MAX_PER_USER + " devices per user: disable one first");
        }
        Document doc = new Document("_id", id).append("endpoint", endpoint).append("p256dh", p256dh).append("auth", auth)
                .append("username", username).append("roles", new ArrayList<>(roles))
                .append("userAgent", userAgent == null ? null : userAgent.substring(0, Math.min(200, userAgent.length())))
                .append("createdAt", new Date()).append("failures", 0);
        collection.replaceOne(Filters.eq("_id", id), doc, new ReplaceOptions().upsert(true));
        LOG.info("Push subscription saved for '" + username + "' " + roles);
    }

    public boolean unsubscribe(String username, String endpoint) {
        return db.getCollection(COLLECTION).deleteOne(Filters.and(Filters.eq("_id", id(endpoint)),
                Filters.eq("username", username))).getDeletedCount() > 0;
    }

    public long devices(String username) {
        return db.getCollection(COLLECTION).countDocuments(Filters.eq("username", username));
    }

    /** Called for each new incident. Never throws: notifications must not disturb the alert engine. */
    public void incidentOpened(Incident incident) {
        if (vapid == null || incident == null) return;
        try {
            Document site = incident.getSiteId() == null ? null
                    : db.getCollection("sites").find(Filters.eq("_id", incident.getSiteId())).first();
            List<String> operators = site == null ? List.of() : site.getList("operators", String.class, List.of());
            String payload = payload(incident);
            for (Document s : db.getCollection(COLLECTION).find()) {
                if (PushRules.recipient(s.getList("roles", String.class, List.of()), s.getString("username"), operators)) {
                    send(s, payload);
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Push notifications failed for incident " + incident.getId(), e);
        }
    }

    /** A test notification to the caller's own devices. Returns how many were sent. */
    public int test(String username) {
        if (vapid == null) throw new ApiProblem(503, "push_unavailable", unavailable);
        String payload = Json.createObjectBuilder().add("title", "ColdTrace · notifications actives")
                .add("body", "Les alertes de ColdTrace s'afficheront sur cet appareil.").add("tag", "coldtrace-test")
                .add("url", "/").build().toString();
        int n = 0;
        for (Document s : db.getCollection(COLLECTION).find(Filters.eq("username", username))) {
            send(s, payload);
            n++;
        }
        return n;
    }

    static String payload(Incident i) {
        String body = switch (i.getMetric() == null ? "" : i.getMetric()) {
            case "door" -> "Porte ouverte sur " + i.getSensorId();
            case "silence" -> "Aucune mesure reçue de " + i.getSensorId();
            case "early_warning" -> String.format(Locale.ROOT, "Le modèle prévoit %.1f °C (limite %.1f °C)",
                    nz(i.getPeakValue()), nz(i.getThresholdMax()));
            case "humidity" -> String.format(Locale.ROOT, "Humidité %.0f %%", nz(i.getValue()));
            default -> String.format(Locale.ROOT, "%.1f °C", nz(i.getValue()))
                    + (i.getThresholdMax() != null && nz(i.getValue()) > i.getThresholdMax()
                    ? String.format(Locale.ROOT, " (max %.1f °C)", i.getThresholdMax())
                    : i.getThresholdMin() != null ? String.format(Locale.ROOT, " (min %.1f °C)", i.getThresholdMin()) : "");
        };
        return Json.createObjectBuilder()
                .add("title", PushRules.title(i.getMetric(), i.getSensorId()))
                .add("body", body + (i.getSiteId() == null ? "" : " · " + i.getSiteId()))
                .add("tag", "incident-" + i.getId())
                .add("url", "/#logbook")
                .add("incidentId", i.getId() == null ? "" : i.getId())
                .add("siteId", i.getSiteId() == null ? "" : i.getSiteId())
                .add("sensorId", i.getSensorId() == null ? "" : i.getSensorId())
                .add("metric", i.getMetric() == null ? "" : i.getMetric())
                .add("openedAt", i.getOpenedAt() == null ? "" : i.getOpenedAt())
                .build().toString();
    }

    private void send(Document subscription, String payload) {
        String endpoint = subscription.getString("endpoint");
        if (!PushRules.allowedEndpoint(endpoint, hosts)) return;
        try {
            byte[] body = WebPush.encrypt(payload.getBytes(StandardCharsets.UTF_8),
                    WebPush.fromBase64Url(subscription.getString("p256dh")), WebPush.fromBase64Url(subscription.getString("auth")));
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(10))
                    .header("TTL", "3600").header("Urgency", "high")
                    .header("Content-Encoding", "aes128gcm").header("Content-Type", "application/octet-stream")
                    .header("Authorization", WebPush.vapidAuthorization(endpoint, vapid, subject, Instant.now()))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            Object id = subscription.get("_id");
            http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, error) -> {
                if (error != null) {
                    LOG.fine("Push to " + host(endpoint) + " failed: " + error.getMessage());
                    return;
                }
                int status = response.statusCode();
                if (status == 404 || status == 410) {
                    // the browser unsubscribed or the subscription expired: forget it
                    db.getCollection(COLLECTION).deleteOne(Filters.eq("_id", id));
                } else if (status >= 400) {
                    LOG.warning("Push service " + host(endpoint) + " answered " + status);
                    db.getCollection(COLLECTION).updateOne(Filters.eq("_id", id), Updates.inc("failures", 1));
                } else {
                    db.getCollection(COLLECTION).updateOne(Filters.eq("_id", id),
                            Updates.combine(Updates.set("lastSentAt", new Date()), Updates.set("failures", 0)));
                }
            });
        } catch (Exception e) {
            LOG.log(Level.FINE, "Could not prepare a push message for " + host(endpoint), e);
        }
    }

    static String id(String endpoint) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(endpoint.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String host(String endpoint) {
        try {
            return URI.create(endpoint).getHost();
        } catch (RuntimeException e) {
            return "?";
        }
    }

    private static double nz(Double d) { return d == null ? Double.NaN : d; }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
