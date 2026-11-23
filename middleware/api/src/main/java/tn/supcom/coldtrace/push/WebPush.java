package tn.supcom.coldtrace.push;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

/**
 * The cryptography of the Web Push protocol, with the JDK only (no extra library in the WAR):
 * <ul>
 *   <li>message encryption, RFC 8291 ("aes128gcm" content coding of RFC 8188), one record;</li>
 *   <li>VAPID authentication, RFC 8292: a JWT signed with ES256 (P-256 key of the server).</li>
 * </ul>
 * Kept free of CDI and I/O so it can be unit tested against the receiving side.
 */
public final class WebPush {

    /** Record size advertised in the header: the whole message fits in one record. */
    static final int RECORD_SIZE = 4096;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private WebPush() { }

    public static KeyPair newKeyPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("P-256 is not available", e);
        }
    }

    /** Encrypts {@code payload} for a browser subscription (its p256dh key and auth secret). */
    public static byte[] encrypt(byte[] payload, byte[] uaPublic, byte[] authSecret) throws GeneralSecurityException {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encrypt(payload, uaPublic, authSecret, newKeyPair(), salt);
    }

    static byte[] encrypt(byte[] payload, byte[] uaPublic, byte[] authSecret, KeyPair ephemeral, byte[] salt)
            throws GeneralSecurityException {
        if (authSecret == null || authSecret.length != 16) throw new GeneralSecurityException("auth secret must be 16 bytes");
        if (payload.length > RECORD_SIZE - 17 - 86) throw new GeneralSecurityException("payload too large");
        ECPublicKey receiver = publicKey(uaPublic);
        byte[] asPublic = raw((ECPublicKey) ephemeral.getPublic());

        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(ephemeral.getPrivate());
        agreement.doPhase(receiver, true);
        byte[] ecdhSecret = agreement.generateSecret();

        // RFC 8291 section 3.3 and 3.4
        byte[] prkKey = hmac(authSecret, ecdhSecret);
        byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic, new byte[]{1});
        byte[] ikm = hmac(prkKey, keyInfo);
        byte[] prk = hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(hmac(prk, concat("Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), new byte[]{1})), 16);
        byte[] nonce = Arrays.copyOf(hmac(prk, concat("Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), new byte[]{1})), 12);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ciphertext = cipher.doFinal(concat(payload, new byte[]{2}));   // 0x02: last record, no padding

        ByteBuffer header = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length);
        header.put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic);
        return concat(header.array(), ciphertext);
    }

    /** "Authorization: vapid t=..., k=..." for a push service endpoint. */
    public static String vapidAuthorization(String endpoint, KeyPair vapid, String subject, Instant now)
            throws GeneralSecurityException {
        URI uri = URI.create(endpoint);
        String audience = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        String header = B64.encodeToString("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = B64.encodeToString(("{\"aud\":\"" + audience + "\",\"exp\":" + now.plusSeconds(12 * 3600).getEpochSecond()
                + ",\"sub\":\"" + subject.replace("\"", "") + "\"}").getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + claims;
        Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
        signer.initSign(vapid.getPrivate());
        signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        String jwt = signingInput + "." + B64.encodeToString(signer.sign());
        return "vapid t=" + jwt + ", k=" + B64.encodeToString(raw((ECPublicKey) vapid.getPublic()));
    }

    /** The uncompressed point 0x04 || X || Y (65 bytes), as browsers and push services exchange keys. */
    public static byte[] raw(ECPublicKey key) {
        byte[] out = new byte[65];
        out[0] = 4;
        copyUnsigned(key.getW().getAffineX(), out, 1);
        copyUnsigned(key.getW().getAffineY(), out, 33);
        return out;
    }

    public static ECPublicKey publicKey(byte[] raw) throws GeneralSecurityException {
        if (raw == null || raw.length != 65 || raw[0] != 4) throw new GeneralSecurityException("not an uncompressed P-256 point");
        ECParameterSpec params = ((ECPublicKey) newKeyPair().getPublic()).getParams();
        ECPoint point = new ECPoint(new BigInteger(1, Arrays.copyOfRange(raw, 1, 33)), new BigInteger(1, Arrays.copyOfRange(raw, 33, 65)));
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, params));
    }

    public static String base64Url(byte[] bytes) { return B64.encodeToString(bytes); }

    public static byte[] fromBase64Url(String s) {
        return B64D.decode(s.replace('+', '-').replace('/', '_').replace("=", ""));
    }

    static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }

    private static void copyUnsigned(BigInteger v, byte[] out, int offset) {
        byte[] b = v.toByteArray();
        int start = b.length > 32 ? b.length - 32 : 0;
        int len = b.length - start;
        System.arraycopy(b, start, out, offset + 32 - len, len);
    }
}
