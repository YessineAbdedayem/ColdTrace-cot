package tn.supcom.coldtrace.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessTest {

    @Test
    void everythingUnderApiAndTheAlertSocketNeedsALogin() {
        assertTrue(Access.needsLogin("/api/sensors"));
        assertTrue(Access.needsLogin("/api/incidents/65f000000000000000000001/ack"));
        assertTrue(Access.needsLogin("/api/dev/faults"));
        assertTrue(Access.needsLogin("/api"));
        assertTrue(Access.needsLogin("/ws/alerts"));
    }

    @Test
    void theHealthCheckAndTheLandingPageStayPublic() {
        assertFalse(Access.needsLogin("/api/health"));
        assertFalse(Access.needsLogin("/api/health/"));
        assertFalse(Access.needsLogin("/"));
        assertFalse(Access.needsLogin("/index.html"));
        // a look-alike prefix is not the API
        assertFalse(Access.needsLogin("/apix/sensors"));
    }

    @Test
    void theTokenComesFromTheHeaderOrForTheSocketFromTheQuery() {
        assertEquals("abc", Access.token("/api/sensors", "Bearer abc", null));
        assertEquals("abc", Access.token("/api/sensors", "bearer   abc ", null));
        assertNull(Access.token("/api/sensors", "Basic dXNlcjpwYXNz", null));
        assertNull(Access.token("/api/sensors", "Bearer ", null));
        // the query parameter only counts on the WebSocket
        assertNull(Access.token("/api/sensors", null, "abc"));
        assertEquals("abc", Access.token("/ws/alerts", null, "abc"));
        assertEquals("hdr", Access.token("/ws/alerts", "Bearer hdr", "abc"));
        assertNull(Access.token("/ws/alerts", null, " "));
    }
}
