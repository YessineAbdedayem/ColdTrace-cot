package tn.supcom.coldtrace.security;

import jakarta.enterprise.context.ApplicationScoped;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.logging.Logger;

/**
 * The IAM's public key, which checks the tokens. Fetched from {IAM_URL}/auth/public-key (only the public key ever
 * leaves the IAM), kept in memory, fetched again every {@link #REFRESH}, and retried at most every {@link #RETRY}
 * while the IAM is not reachable (the API may start first).
 */
@ApplicationScoped
public class IamPublicKey {

    static final Duration REFRESH = Duration.ofMinutes(10);
    static final Duration RETRY = Duration.ofSeconds(10);
    private static final Logger LOG = Logger.getLogger(IamPublicKey.class.getName());

    private final String iamUrl = trimSlash(System.getenv("IAM_URL"));
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();

    private volatile PublicKey key;
    private volatile Instant fetchedAt = Instant.EPOCH;
    private volatile Instant lastAttempt = Instant.EPOCH;

    /** True when IAM_URL is set: without it, the protected endpoints refuse every request (503). */
    public boolean configured() {
        return iamUrl != null;
    }

    /** @return the key, or null if it was never fetched and the IAM can't be reached now */
    public PublicKey get() {
        Instant now = Instant.now();
        if (iamUrl != null && (key == null || now.isAfter(fetchedAt.plus(REFRESH)))
                && now.isAfter(lastAttempt.plus(RETRY))) {
            fetch(now);
        }
        return key;
    }

    private synchronized void fetch(Instant now) {
        if (now.isBefore(lastAttempt.plus(RETRY))) {
            return; // another thread just tried
        }
        lastAttempt = now;
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(iamUrl + "/auth/public-key"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + r.statusCode());
            }
            PublicKey fresh = parse(r.body());
            boolean changed = key == null || !java.util.Arrays.equals(key.getEncoded(), fresh.getEncoded());
            key = fresh;
            fetchedAt = now;
            if (changed) {
                LOG.info("Token signing key loaded from " + iamUrl + " (key " + r.headers().firstValue("X-Key-Id").orElse("?") + ")");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // keep the key we have: tokens it signed stay valid while the IAM is down
            LOG.warning("Cannot fetch the token key from " + iamUrl + " (" + e.getClass().getSimpleName() + ")"
                    + (key == null ? ": logins refused until it answers" : ": keeping the current key"));
        }
    }

    static PublicKey parse(String pem) throws Exception {
        String body = pem.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(body)));
    }

    private static String trimSlash(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String u = url.trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }
}
