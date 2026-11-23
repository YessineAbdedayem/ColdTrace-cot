package tn.supcom.coldtrace.ingestion;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tn.supcom.coldtrace.model.Measurement;
import tn.supcom.coldtrace.business.Catalog;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;

class ReliabilityTest {
    @TempDir Path directory;
    @Test void durableEventsSurviveReopeningAndQuarantineKeepsRawData() throws Exception {
        DurableInbox q = new DurableInbox(directory, 10, 10000);
        q.append("coldtrace/site01/S01/telemetry", "{broken", Instant.parse("2026-11-23T10:00:00Z"));
        q = new DurableInbox(directory, 10, 10000);
        assertEquals(1, q.pending(10).size());
        var entry = q.read(q.pending(10).getFirst());
        assertEquals("{broken", entry.payload());
        q.quarantine(entry, "invalid_json");
        assertEquals(0, q.pending(10).size());
        assertEquals("invalid_json", q.rejected(10).getFirst().get("reason"));
        assertTrue(java.nio.file.Files.readString(directory.resolve("quarantine").resolve(entry.file().getFileName())).contains("broken"));
    }
    @Test void capacityRejectsBeforeAcknowledgementWithoutRemovingExistingEvents() throws Exception {
        DurableInbox q = new DurableInbox(directory, 1, 10000);
        q.append("a", "first", Instant.now());
        assertThrows(java.io.IOException.class, () -> q.append("b", "second", Instant.now()));
        assertEquals("first", q.read(q.pending(10).getFirst()).payload());
    }
    @Test void byteCapacityIsEnforced() throws Exception {
        assertThrows(java.io.IOException.class, () -> new DurableInbox(directory, 10, 4).append("a", "payload", Instant.now()));
    }
    private Measurement reading(Instant ts) {
        Measurement m = new Measurement(); m.setTs(ts.toString()); m.setMsgId("event-1"); m.setSiteId("site01"); m.setSensorId("S01"); m.setTemperature(2.5); return m;
    }
    @Test void liveAdmissionUsesEventTimeNotIngestionTime() {
        Instant now = Instant.now();
        assertFalse(MeasurementPolicy.live(reading(now.minusSeconds(300)), now, null));
        assertTrue(MeasurementPolicy.live(reading(now), now, null));
        assertFalse(MeasurementPolicy.live(reading(now.minusSeconds(1)), now, now));
        Measurement replay = reading(now); replay.setReplayed(true);
        assertFalse(MeasurementPolicy.live(replay, now, null));
    }
    @Test void futureAndImpossibleValuesCannotBeLive() {
        Instant now = Instant.now();
        assertFalse(MeasurementPolicy.live(reading(now.plusSeconds(1000)), now, null));
        Measurement bad = reading(now); bad.setTemperature(Double.NaN);
        assertNotNull(MeasurementPolicy.physicalError(bad));
        bad.setTemperature(2.0); bad.setHumidity(101.0);
        assertNotNull(MeasurementPolicy.physicalError(bad));
    }
    @Test void statusTransitionsAndDisabledDoNotDependOnReceipt() {
        Instant now = Instant.now();
        assertEquals("online", MeasurementPolicy.status(true, Date.from(now), now));
        assertEquals("stale", MeasurementPolicy.status(true, Date.from(now.minusSeconds(MeasurementPolicy.FRESH_SECONDS + 1)), now));
        assertEquals("offline", MeasurementPolicy.status(true, Date.from(now.minusSeconds(MeasurementPolicy.OFFLINE_SECONDS + 1)), now));
        assertEquals("disabled", MeasurementPolicy.status(false, Date.from(now), now));
        assertEquals("unknown", MeasurementPolicy.status(true, null, now));
    }
    @Test void thresholdValidationIsStructuredAndDeterministic() {
        assertThrows(tn.supcom.coldtrace.business.ApiProblem.class, () -> Catalog.validateThreshold(new Document("temperatureMin", 5.0).append("temperatureMax", 4.0)));
        assertThrows(tn.supcom.coldtrace.business.ApiProblem.class, () -> Catalog.thresholdBody("{\"temperatureMax\":\"hot\"}"));
        assertThrows(tn.supcom.coldtrace.business.ApiProblem.class, () -> Catalog.body("[]", "id"));
        assertDoesNotThrow(() -> Catalog.validateThreshold(new Document("temperatureMin", null).append("temperatureMax", -18.0)));
    }
}
