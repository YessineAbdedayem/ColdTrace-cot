package tn.supcom.coldtrace.push;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WebPushTest {

    /** Decrypts like a browser does (RFC 8291 receiving side), independently of the sender's code path. */
    static byte[] decrypt(byte[] message, KeyPair receiver, byte[] auth) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(message);
        byte[] salt = new byte[16];
        b.get(salt);
        int rs = b.getInt();
        assertEquals(WebPush.RECORD_SIZE, rs);
        byte[] senderKey = new byte[b.get() & 0xff];
        b.get(senderKey);
        byte[] ciphertext = new byte[b.remaining()];
        b.get(ciphertext);

        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(receiver.getPrivate());
        ka.doPhase(WebPush.publicKey(senderKey), true);
        byte[] uaPublic = WebPush.raw((ECPublicKey) receiver.getPublic());
        byte[] prkKey = WebPush.hmac(auth, ka.generateSecret());
        byte[] ikm = WebPush.hmac(prkKey, WebPush.concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, senderKey, new byte[]{1}));
        byte[] prk = WebPush.hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(WebPush.hmac(prk, "Content-Encoding: aes128gcm\0\1".getBytes(StandardCharsets.US_ASCII)), 16);
        byte[] nonce = Arrays.copyOf(WebPush.hmac(prk, "Content-Encoding: nonce\0\1".getBytes(StandardCharsets.US_ASCII)), 12);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] plain = c.doFinal(ciphertext);
        assertEquals(2, plain[plain.length - 1], "last-record delimiter");
        return Arrays.copyOf(plain, plain.length - 1);
    }

    @Test
    void encryptedMessageDecryptsOnTheReceivingSide() throws Exception {
        KeyPair browser = WebPush.newKeyPair();
        byte[] auth = new byte[16];
        new java.security.SecureRandom().nextBytes(auth);
        String json = "{\"title\":\"ColdTrace · Température hors plage · S01\"}";
        byte[] message = WebPush.encrypt(json.getBytes(StandardCharsets.UTF_8), WebPush.raw((ECPublicKey) browser.getPublic()), auth);
        assertEquals(json, new String(decrypt(message, browser, auth), StandardCharsets.UTF_8));
    }

    @Test
    void wrongAuthSecretCannotDecrypt() throws Exception {
        KeyPair browser = WebPush.newKeyPair();
        byte[] auth = new byte[16], other = new byte[16];
        other[0] = 1;
        byte[] message = WebPush.encrypt("x".getBytes(), WebPush.raw((ECPublicKey) browser.getPublic()), auth);
        assertThrows(javax.crypto.AEADBadTagException.class, () -> decrypt(message, browser, other));
    }

    @Test
    void rejectsBadKeys() {
        assertThrows(Exception.class, () -> WebPush.encrypt("x".getBytes(), new byte[65], new byte[16]));
        assertThrows(Exception.class, () -> WebPush.encrypt("x".getBytes(), WebPush.raw((ECPublicKey) WebPush.newKeyPair().getPublic()), new byte[8]));
    }

    @Test
    void rawPointRoundTrips() throws Exception {
        ECPublicKey key = (ECPublicKey) WebPush.newKeyPair().getPublic();
        byte[] raw = WebPush.raw(key);
        assertEquals(65, raw.length);
        assertEquals(key.getW(), WebPush.publicKey(raw).getW());
        assertArrayEquals(raw, WebPush.fromBase64Url(WebPush.base64Url(raw)));
    }

    @Test
    void vapidHeaderIsAVerifiableEs256TokenForThePushOrigin() throws Exception {
        KeyPair vapid = WebPush.newKeyPair();
        Instant now = Instant.parse("2026-11-23T10:00:00Z");
        String h = WebPush.vapidAuthorization("https://fcm.googleapis.com/fcm/send/abc", vapid, "mailto:a@b.test", now);
        assertTrue(h.startsWith("vapid t="));
        String jwt = h.substring(8, h.indexOf(", k="));
        String[] parts = jwt.split("\\.");
        String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertTrue(claims.contains("\"aud\":\"https://fcm.googleapis.com\""), claims);
        assertTrue(claims.contains("\"exp\":" + now.plusSeconds(43200).getEpochSecond()));
        Signature v = Signature.getInstance("SHA256withECDSAinP1363Format");
        v.initVerify(vapid.getPublic());
        v.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(v.verify(Base64.getUrlDecoder().decode(parts[2])));
        assertArrayEquals(WebPush.raw((ECPublicKey) vapid.getPublic()), WebPush.fromBase64Url(h.substring(h.indexOf("k=") + 2)));
    }

    @Test
    void vapidKeysAreCreatedOnceAndReloaded() throws Exception {
        Path dir = Files.createTempDirectory("vapid");
        KeyPair first = VapidKeys.loadOrCreate(dir);
        KeyPair again = VapidKeys.loadOrCreate(dir);
        assertArrayEquals(first.getPublic().getEncoded(), again.getPublic().getEncoded());
        assertArrayEquals(first.getPrivate().getEncoded(), again.getPrivate().getEncoded());
    }

    @Test
    void recipientsAreQualityAdminsAndTheAssignedDrivers() {
        assertTrue(PushRules.recipient(List.of("quality"), "qualite", List.of("driver1")));
        assertTrue(PushRules.recipient(List.of("admin"), "admin", List.of("driver1")));
        assertTrue(PushRules.recipient(List.of("operator"), "driver1", List.of("driver1")));
        assertFalse(PushRules.recipient(List.of("operator"), "driver2", List.of("driver1")));
        assertTrue(PushRules.recipient(List.of("operator"), "driver2", List.of()), "nobody assigned: every operator");
        assertFalse(PushRules.recipient(List.of(), "x", List.of()));
    }

    @Test
    void onlyHttpsEndpointsOfKnownPushServicesAreCalled() {
        List<String> hosts = PushRules.DEFAULT_HOSTS;
        assertTrue(PushRules.allowedEndpoint("https://fcm.googleapis.com/fcm/send/x", hosts));
        assertTrue(PushRules.allowedEndpoint("https://web.push.apple.com/abc", hosts));
        assertTrue(PushRules.allowedEndpoint("https://wns2-par02p.notify.windows.com/w/?token=x", hosts));
        assertFalse(PushRules.allowedEndpoint("http://fcm.googleapis.com/x", hosts));
        assertFalse(PushRules.allowedEndpoint("https://mongo:27017/x", hosts));
        assertFalse(PushRules.allowedEndpoint("https://evil.test/push.apple.com", hosts));
        assertFalse(PushRules.allowedEndpoint("https://fcm.googleapis.com.evil.test/x", hosts));
        assertFalse(PushRules.allowedEndpoint("https://user@fcm.googleapis.com/x", hosts));
        assertFalse(PushRules.allowedEndpoint(null, hosts));
    }

    @Test
    void payloadIsJsonWithTitleAndBody() {
        var i = new tn.supcom.coldtrace.model.Incident();
        i.setId("0123456789abcdef01234567");
        i.setSiteId("site01");
        i.setSensorId("S01");
        i.setMetric("temperature");
        i.setValue(7.25);
        i.setThresholdMax(4.0);
        String p = PushService.payload(i);
        var json = jakarta.json.Json.createReader(new java.io.StringReader(p)).readObject();
        assertEquals("ColdTrace · Température hors plage · S01", json.getString("title"));
        assertEquals("7.3 °C (max 4.0 °C) · site01", json.getString("body"));
        assertEquals("incident-0123456789abcdef01234567", json.getString("tag"));
    }
}
