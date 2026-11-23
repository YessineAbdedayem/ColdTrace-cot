package tn.supcom.coldtrace.push;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * The server's VAPID key pair (P-256), created on the first start and kept in a folder that survives restarts
 * (in Docker: the /keys volume, next to the IAM's token keys, never in git or in the image).
 * Changing it invalidates every browser subscription: users then have to enable notifications again.
 */
public final class VapidKeys {

    static final String PRIVATE = "vapid-private.key";
    static final String PUBLIC = "vapid-public.key";

    private VapidKeys() { }

    public static KeyPair loadOrCreate(Path dir) throws IOException, GeneralSecurityException {
        Path priv = dir.resolve(PRIVATE), pub = dir.resolve(PUBLIC);
        KeyFactory ec = KeyFactory.getInstance("EC");
        if (Files.exists(priv) && Files.exists(pub)) {
            return new KeyPair(ec.generatePublic(new X509EncodedKeySpec(read(pub))),
                    ec.generatePrivate(new PKCS8EncodedKeySpec(read(priv))));
        }
        Files.createDirectories(dir);
        KeyPair pair = WebPush.newKeyPair();
        Files.writeString(priv, Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()), StandardCharsets.US_ASCII);
        try {
            Files.setPosixFilePermissions(priv, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Windows (unit tests): no POSIX permissions
        }
        Files.writeString(pub, Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()), StandardCharsets.US_ASCII);
        return pair;
    }

    private static byte[] read(Path p) throws IOException {
        return Base64.getDecoder().decode(Files.readString(p, StandardCharsets.US_ASCII).trim());
    }
}
