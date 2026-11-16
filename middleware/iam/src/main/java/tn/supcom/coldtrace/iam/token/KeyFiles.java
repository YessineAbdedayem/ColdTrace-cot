package tn.supcom.coldtrace.iam.token;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The IAM's RSA key pair, kept as PEM files in a directory (a Docker volume): created on the first start, reused after,
 * so tokens stay valid across restarts. The private key never leaves this directory; the public key is served at
 * GET /auth/public-key for the services that check tokens.
 */
public final class KeyFiles {

    public static final String PRIVATE = "private.pem";
    public static final String PUBLIC = "public.pem";

    private KeyFiles() {
    }

    public static KeyPair loadOrCreate(Path dir) throws IOException, GeneralSecurityException {
        Path priv = dir.resolve(PRIVATE);
        Path pub = dir.resolve(PUBLIC);
        if (Files.exists(priv) && Files.exists(pub)) {
            return new KeyPair(readPublic(Files.readString(pub)), readPrivate(Files.readString(priv)));
        }
        Files.createDirectories(dir);
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair pair = g.generateKeyPair();
        Files.writeString(priv, pem("PRIVATE KEY", pair.getPrivate().getEncoded()), StandardCharsets.US_ASCII);
        try {
            Files.setPosixFilePermissions(priv, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // not a POSIX file system (tests on Windows): nothing to restrict
        }
        Files.writeString(pub, publicPem(pair.getPublic()), StandardCharsets.US_ASCII);
        return pair;
    }

    public static String publicPem(PublicKey key) {
        return pem("PUBLIC KEY", key.getEncoded());
    }

    public static PublicKey readPublic(String pem) throws GeneralSecurityException {
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der(pem)));
    }

    public static PrivateKey readPrivate(String pem) throws GeneralSecurityException {
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der(pem)));
    }

    /** A short id of the public key ("kid"), so a service can tell which key signed a token after a key change. */
    public static String keyId(PublicKey key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    private static byte[] der(String pem) {
        String body = pem.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
