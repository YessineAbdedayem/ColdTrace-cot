package tn.supcom.coldtrace.security;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtTest {

    static KeyPair keys;
    static KeyPair otherKeys;
    static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        keys = g.generateKeyPair();
        otherKeys = g.generateKeyPair();
    }

    static JsonObject claims(Instant iat, Instant exp, String iss, String aud) {
        return Json.createObjectBuilder().add("iss", iss).add("aud", aud).add("sub", "qualite").add("name", "Q")
                .add("groups", Json.createArrayBuilder().add("quality")).add("iat", iat.getEpochSecond())
                .add("exp", exp.getEpochSecond()).add("jti", "1").build();
    }

    static String valid() {
        return Jwt.sign(claims(NOW, NOW.plusSeconds(3600), "coldtrace-iam", "coldtrace"), "k1", keys.getPrivate());
    }

    static Jwt.Claims check(String token) throws Jwt.InvalidTokenException {
        return Jwt.verify(token, keys.getPublic(), "coldtrace-iam", "coldtrace", NOW.plusSeconds(60));
    }

    @Test
    void aSignedTokenIsAcceptedWithItsSubjectAndRoles() throws Exception {
        Jwt.Claims c = check(valid());
        assertEquals("qualite", c.subject());
        assertEquals(Set.of("quality"), c.groups());
        assertEquals(NOW.plusSeconds(3600), c.expiresAt());
    }

    @Test
    void aChangedPayloadBreaksTheSignature() {
        String[] p = valid().split("\\.");
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
                claims(NOW, NOW.plusSeconds(3600), "coldtrace-iam", "coldtrace").toString()
                        .replace("\"quality\"", "\"admin\"").getBytes(StandardCharsets.UTF_8));
        assertThrows(Jwt.InvalidTokenException.class, () -> check(p[0] + "." + forged + "." + p[2]));
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRefused() {
        String other = Jwt.sign(claims(NOW, NOW.plusSeconds(3600), "coldtrace-iam", "coldtrace"), "k2",
                otherKeys.getPrivate());
        assertThrows(Jwt.InvalidTokenException.class, () -> check(other));
    }

    @Test
    void theNoneAlgorithmIsRefused() {
        String[] p = valid().split("\\.");
        String none = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        assertThrows(Jwt.InvalidTokenException.class, () -> check(none + "." + p[1] + "."));
        assertThrows(Jwt.InvalidTokenException.class, () -> check(none + "." + p[1] + "." + p[2]));
    }

    @Test
    void anExpiredTokenIsRefusedAfterTheLeeway() throws Exception {
        String t = Jwt.sign(claims(NOW.minusSeconds(7200), NOW.minusSeconds(10), "coldtrace-iam", "coldtrace"),
                "k1", keys.getPrivate());
        Jwt.verify(t, keys.getPublic(), "coldtrace-iam", "coldtrace", NOW);   // 10 s late: within the leeway
        assertThrows(Jwt.InvalidTokenException.class,
                () -> Jwt.verify(t, keys.getPublic(), "coldtrace-iam", "coldtrace", NOW.plusSeconds(60)));
    }

    @Test
    void theIssuerAndAudienceMustMatch() {
        String wrongIss = Jwt.sign(claims(NOW, NOW.plusSeconds(3600), "someone-else", "coldtrace"), "k1", keys.getPrivate());
        String wrongAud = Jwt.sign(claims(NOW, NOW.plusSeconds(3600), "coldtrace-iam", "other-app"), "k1", keys.getPrivate());
        assertThrows(Jwt.InvalidTokenException.class, () -> check(wrongIss));
        assertThrows(Jwt.InvalidTokenException.class, () -> check(wrongAud));
    }

    @Test
    void garbageIsRefusedWithoutAnError() {
        for (String t : new String[]{null, "", "abc", "a.b", "a.b.c", "x".repeat(9000)}) {
            assertThrows(Jwt.InvalidTokenException.class, () -> check(t));
        }
    }
}
