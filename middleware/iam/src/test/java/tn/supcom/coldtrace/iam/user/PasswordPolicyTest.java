package tn.supcom.coldtrace.iam.user;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordPolicyTest {

    @Test
    void passwords() {
        assertNull(PasswordPolicy.problem("cold-chain-2026!", "qualite"));
        assertNotNull(PasswordPolicy.problem("short", "qualite"));
        assertNotNull(PasswordPolicy.problem(null, "qualite"));
        assertNotNull(PasswordPolicy.problem("x".repeat(129), "qualite"));
        assertNotNull(PasswordPolicy.problem("my-qualite-password", "qualite"));
        assertNotNull(PasswordPolicy.problem("change-me-admin", "admin"));   // the .env.example value
    }

    @Test
    void usernames() {
        assertTrue(PasswordPolicy.validUsername("qualite"));
        assertTrue(PasswordPolicy.validUsername("k.hedhili_2"));
        assertFalse(PasswordPolicy.validUsername("ab"));
        assertFalse(PasswordPolicy.validUsername("Admin"));
        assertFalse(PasswordPolicy.validUsername("a b"));
        assertFalse(PasswordPolicy.validUsername("../etc"));
        assertFalse(PasswordPolicy.validUsername(null));
        assertFalse(PasswordPolicy.validUsername("anonymous"));   // WildFly's name for a caller without a token
    }

    @Test
    void roles() {
        assertTrue(Roles.valid(Set.of(Roles.ADMIN)));
        assertTrue(Roles.valid(Set.of(Roles.QUALITY, Roles.OPERATOR)));
        assertFalse(Roles.valid(Set.of()));
        assertFalse(Roles.valid(Set.of("root")));
        assertFalse(Roles.valid(null));
    }
}
