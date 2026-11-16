package tn.supcom.coldtrace.iam.token;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyFilesTest {

    @Test
    void theKeysAreCreatedOnceThenReused(@TempDir Path dir) throws Exception {
        KeyPair first = KeyFiles.loadOrCreate(dir);
        assertTrue(Files.exists(dir.resolve(KeyFiles.PRIVATE)) && Files.exists(dir.resolve(KeyFiles.PUBLIC)));
        KeyPair again = KeyFiles.loadOrCreate(dir);
        assertArrayEquals(first.getPublic().getEncoded(), again.getPublic().getEncoded());
        assertArrayEquals(first.getPrivate().getEncoded(), again.getPrivate().getEncoded());
        assertEquals(KeyFiles.keyId(first.getPublic()), KeyFiles.keyId(again.getPublic()));
    }

    @Test
    void thePublicPemReadsBack(@TempDir Path dir) throws Exception {
        KeyPair keys = KeyFiles.loadOrCreate(dir);
        String pem = KeyFiles.publicPem(keys.getPublic());
        assertTrue(pem.startsWith("-----BEGIN PUBLIC KEY-----"));
        assertArrayEquals(keys.getPublic().getEncoded(), KeyFiles.readPublic(pem).getEncoded());
    }
}
