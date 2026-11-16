package tn.supcom.coldtrace.iam.auth;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LoginGuardTest {

    static final Instant T0 = Instant.parse("2026-11-16T12:00:00Z");

    @Test
    void fiveWrongPasswordsLockTheUsernameForFiveMinutes() {
        LoginGuard g = new LoginGuard();
        for (int i = 0; i < LoginGuard.MAX_FAILURES - 1; i++) {
            g.failed("admin", T0);
        }
        assertNull(g.lockedUntil("admin", T0));
        g.failed("admin", T0);
        assertEquals(T0.plus(LoginGuard.LOCK), g.lockedUntil("admin", T0.plusSeconds(1)));
        assertNull(g.lockedUntil("admin", T0.plus(LoginGuard.LOCK).plusSeconds(1)));
    }

    @Test
    void theCountIsPerUsernameAndIgnoresCase() {
        LoginGuard g = new LoginGuard();
        for (int i = 0; i < LoginGuard.MAX_FAILURES; i++) {
            g.failed("Admin", T0);
        }
        assertNotNull(g.lockedUntil("admin", T0));
        assertNull(g.lockedUntil("qualite", T0));
    }

    @Test
    void aSuccessClearsTheCount() {
        LoginGuard g = new LoginGuard();
        for (int i = 0; i < LoginGuard.MAX_FAILURES - 1; i++) {
            g.failed("admin", T0);
        }
        g.succeeded("admin");
        g.failed("admin", T0);
        assertNull(g.lockedUntil("admin", T0));
    }

    @Test
    void failuresOlderThanTheWindowAreForgotten() {
        LoginGuard g = new LoginGuard();
        for (int i = 0; i < LoginGuard.MAX_FAILURES - 1; i++) {
            g.failed("admin", T0);
        }
        Instant later = T0.plus(LoginGuard.WINDOW).plusSeconds(1);
        g.failed("admin", later);
        assertNull(g.lockedUntil("admin", later));
    }
}
