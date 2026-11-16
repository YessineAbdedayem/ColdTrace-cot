package tn.supcom.coldtrace.iam.token;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;

import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Issues and checks the access tokens. The key pair lives in KEYS_DIR (a Docker volume), created on the first start.
 *
 * Settings: KEYS_DIR (default /keys), TOKEN_TTL_MINUTES (default 480: one work shift).
 */
@ApplicationScoped
public class TokenService {

    public static final String ISSUER = "coldtrace-iam";
    public static final String AUDIENCE = "coldtrace";
    private static final Logger LOG = Logger.getLogger(TokenService.class.getName());

    private KeyPair keys;
    private String keyId;
    private long ttlSeconds;

    @PostConstruct
    void init() {
        Path dir = Path.of(env("KEYS_DIR", "/keys"));
        try {
            keys = KeyFiles.loadOrCreate(dir);
        } catch (Exception e) {
            throw new IllegalStateException("cannot load or create the signing keys in " + dir, e);
        }
        keyId = KeyFiles.keyId(keys.getPublic());
        ttlSeconds = Math.max(5, Long.parseLong(env("TOKEN_TTL_MINUTES", "480"))) * 60;
        LOG.info("Signing key " + keyId + " from " + dir + ", tokens valid " + ttlSeconds / 60 + " min");
    }

    public record Issued(String token, long expiresIn, Instant expiresAt) {
    }

    public Issued issue(String username, String name, Set<String> roles) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(ttlSeconds);
        JsonArrayBuilder groups = Json.createArrayBuilder();
        new TreeSet<>(roles).forEach(groups::add);
        String token = Jwt.sign(Json.createObjectBuilder()
                .add("iss", ISSUER).add("aud", AUDIENCE).add("sub", username).add("name", name == null ? username : name)
                .add("groups", groups).add("iat", now.getEpochSecond()).add("exp", exp.getEpochSecond())
                .add("jti", UUID.randomUUID().toString()).build(), keyId, keys.getPrivate());
        return new Issued(token, ttlSeconds, exp);
    }

    public Jwt.Claims verify(String token) throws Jwt.InvalidTokenException {
        return Jwt.verify(token, keys.getPublic(), ISSUER, AUDIENCE, Instant.now());
    }

    public String publicKeyPem() {
        return KeyFiles.publicPem(keys.getPublic());
    }

    public String keyId() {
        return keyId;
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v.trim();
    }
}
