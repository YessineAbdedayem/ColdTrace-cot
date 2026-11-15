package tn.supcom.coldtrace.model;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IncidentTest {

    @Test
    void oldDocumentsWithoutAcknowledgementFieldsStillLoad() {
        Incident incident = Incident.fromDocument(new Document("status", Incident.OPEN));

        assertNull(incident.getAcknowledgedAt());
        assertNull(incident.getAcknowledgedBy());
        assertNull(incident.getComment());
    }

    @Test
    void acknowledgementDatesRoundTripAsBsonDateAndIsoString() {
        String acknowledgedAt = "2026-11-09T12:00:00Z";
        Incident incident = new Incident();
        incident.setId("65f000000000000000000001");
        incident.setStatus(Incident.OPEN);
        incident.setAcknowledgedAt(acknowledgedAt);
        incident.setAcknowledgedBy("Yessine");
        incident.setComment("Checked");

        Document stored = incident.toDocument();
        Incident loaded = Incident.fromDocument(stored);

        assertEquals(new ObjectId("65f000000000000000000001"), stored.getObjectId("_id"));
        assertEquals(Date.from(Instant.parse(acknowledgedAt)), stored.getDate("acknowledgedAt"));
        assertEquals(acknowledgedAt, loaded.getAcknowledgedAt());
        assertEquals("Yessine", loaded.getAcknowledgedBy());
        assertEquals("Checked", loaded.getComment());
    }
}
