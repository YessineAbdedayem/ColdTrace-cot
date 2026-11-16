package tn.supcom.coldtrace.iam.token;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Signed tokens in the JWT format (RFC 7519), RS256 only: the IAM signs with its private key, and anyone holding the
 * public key (the API) can check a token without asking the IAM.
 *
 * Kept free of WildFly and MongoDB, so it can be unit tested. The same checks run in the API (step 2).
 */
public final class Jwt {

    public static final String ALG = "RS256";
    /** Clock difference tolerated between the IAM and the services that check its tokens. */
    public static final long LEEWAY_SECONDS = 30;

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private Jwt() {
    }

    /** The checked content of a token. */
    public record Claims(String subject, String name, Set<String> groups, String issuer, String audience,
                         Instant issuedAt, Instant expiresAt, String id) {
    }

    public static class InvalidTokenException extends Exception {
        public InvalidTokenException(String message) {
            super(message);
        }
    }

    public static String sign(JsonObject claims, String keyId, PrivateKey key) {
        JsonObject header = Json.createObjectBuilder()
                .add("alg", ALG).add("typ", "JWT").add("kid", keyId).build();
        String signingInput = b64(header.toString()) + "." + b64(claims.toString());
        try {
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(key);
            s.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + B64.encodeToString(s.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot sign the token", e);
        }
    }

    /**
     * Checks the signature, the algorithm, the dates, the issuer and the audience, in that order.
     *
     * @throws InvalidTokenException with a short reason, safe to log (never contains the token)
     */
    public static Claims verify(String token, PublicKey key, String issuer, String audience, Instant now)
            throws InvalidTokenException {
        if (token == null || token.length() > 8192) {
            throw new InvalidTokenException("missing or oversized token");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw new InvalidTokenException("not a JWT");
        }
        JsonObject header = json(parts[0]);
        // Only RS256: refuses "none" and any attempt to switch to an HMAC algorithm with the public key as secret.
        if (!ALG.equals(string(header, "alg"))) {
            throw new InvalidTokenException("unexpected algorithm");
        }
        try {
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initVerify(key);
            s.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!s.verify(B64D.decode(parts[2]))) {
                throw new InvalidTokenException("bad signature");
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new InvalidTokenException("bad signature");
        }

        JsonObject c = json(parts[1]);
        Instant exp = instant(c, "exp");
        Instant iat = instant(c, "iat");
        if (exp == null || now.isAfter(exp.plusSeconds(LEEWAY_SECONDS))) {
            throw new InvalidTokenException("expired");
        }
        if (iat != null && iat.isAfter(now.plusSeconds(LEEWAY_SECONDS))) {
            throw new InvalidTokenException("issued in the future");
        }
        if (!issuer.equals(string(c, "iss"))) {
            throw new InvalidTokenException("wrong issuer");
        }
        if (!audience.equals(string(c, "aud"))) {
            throw new InvalidTokenException("wrong audience");
        }
        String sub = string(c, "sub");
        if (sub == null || sub.isBlank()) {
            throw new InvalidTokenException("no subject");
        }
        Set<String> groups = new LinkedHashSet<>();
        if (c.get("groups") instanceof JsonArray a) {
            for (JsonValue v : a) {
                if (v instanceof JsonString g) {
                    groups.add(g.getString());
                }
            }
        }
        return new Claims(sub, string(c, "name"), Set.copyOf(groups), string(c, "iss"), string(c, "aud"),
                iat, exp, string(c, "jti"));
    }

    private static String b64(String s) {
        return B64.encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonObject json(String part) throws InvalidTokenException {
        try (JsonReader r = Json.createReader(new StringReader(new String(B64D.decode(part), StandardCharsets.UTF_8)))) {
            return r.readObject();
        } catch (RuntimeException e) {
            throw new InvalidTokenException("malformed token");
        }
    }

    private static String string(JsonObject o, String key) {
        return o.get(key) instanceof JsonString s ? s.getString() : null;
    }

    private static Instant instant(JsonObject o, String key) {
        return o.get(key) instanceof JsonNumber n ? Instant.ofEpochSecond(n.longValue()) : null;
    }
}
