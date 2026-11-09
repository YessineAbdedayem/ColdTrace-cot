package tn.supcom.coldtrace.alert;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import tn.supcom.coldtrace.alert.AlertRules.Action;
import tn.supcom.coldtrace.model.Incident;
import tn.supcom.coldtrace.model.Threshold;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertRulesTest {

    private static final Threshold COLD = Threshold.DEFAULTS.get(0);
    private static final Threshold FREEZER = Threshold.DEFAULTS.get(1);

    @Test
    void coldProfileAllowsZeroToFourDegreesInclusive() {
        assertFalse(AlertRules.isBreached(0.0, COLD.getTemperatureMin(), COLD.getTemperatureMax()));
        assertFalse(AlertRules.isBreached(4.0, COLD.getTemperatureMin(), COLD.getTemperatureMax()));
        assertTrue(AlertRules.isBreached(4.1, COLD.getTemperatureMin(), COLD.getTemperatureMax()));
        assertTrue(AlertRules.isBreached(-0.1, COLD.getTemperatureMin(), COLD.getTemperatureMax()));
    }

    @Test
    void freezerProfileHasNoLowerLimit() {
        assertFalse(AlertRules.isBreached(-40.0, FREEZER.getTemperatureMin(), FREEZER.getTemperatureMax()));
        assertFalse(AlertRules.isBreached(-18.0, FREEZER.getTemperatureMin(), FREEZER.getTemperatureMax()));
        assertTrue(AlertRules.isBreached(-17.9, FREEZER.getTemperatureMin(), FREEZER.getTemperatureMax()));
    }

    @Test
    void humidityLimitsAreFiftyToSeventyPercent() {
        assertTrue(AlertRules.isBreached(49.9, COLD.getHumidityMin(), COLD.getHumidityMax()));
        assertFalse(AlertRules.isBreached(50.0, COLD.getHumidityMin(), COLD.getHumidityMax()));
        assertFalse(AlertRules.isBreached(70.0, COLD.getHumidityMin(), COLD.getHumidityMax()));
        assertTrue(AlertRules.isBreached(70.1, COLD.getHumidityMin(), COLD.getHumidityMax()));
    }

    @Test
    void openDoorIsABreachAndClosedDoorIsNot() {
        assertTrue(AlertRules.isBreached(AlertRules.doorValue(true), null, AlertRules.DOOR_MAX));
        assertFalse(AlertRules.isBreached(AlertRules.doorValue(false), null, AlertRules.DOOR_MAX));
    }

    @Test
    void sensorIsSilentOnlyAfterSixtySecondsWithoutReading() {
        assertFalse(AlertRules.isSilent(5));
        assertFalse(AlertRules.isSilent(60));
        assertTrue(AlertRules.isSilent(61));
    }

    @Test
    void firstBreachOpensAnIncidentAndNormalReadingsDoNothing() {
        assertEquals(Action.OPEN, AlertRules.decide(false, true, 0));
        assertEquals(Action.NONE, AlertRules.decide(false, false, 0));
    }

    @Test
    void incidentClosesOnlyAfterEnoughConsecutiveInRangeReadings() {
        assertEquals(Action.RECOVERING, AlertRules.decide(true, false, 0));
        assertEquals(Action.RECOVERING, AlertRules.decide(true, false, 1));
        assertEquals(Action.CLOSE, AlertRules.decide(true, false, 2));
    }

    @Test
    void newBreachWhileRecoveringKeepsTheIncidentOpen() {
        assertEquals(Action.STILL_BREACHED, AlertRules.decide(true, true, 2));
    }

    @Test
    void alertEventSerializesWithIsoDatesAndNoInternalCounter() throws Exception {
        Date openedAt = Date.from(Instant.parse("2026-11-09T12:00:00Z"));
        Document doc = Incident.newOpenDocument("site01", "S01", "temperature", 4.3, 0.0, 4.0, openedAt)
                .append("_id", new ObjectId("65f000000000000000000001"));

        try (Jsonb jsonb = JsonbBuilder.create()) {
            String json = jsonb.toJson(new AlertEvent(AlertEvent.OPENED, Incident.fromDocument(doc)));

            assertTrue(json.contains("\"event\":\"incident.opened\""), json);
            assertTrue(json.contains("\"id\":\"65f000000000000000000001\""), json);
            assertTrue(json.contains("\"openedAt\":\"2026-11-09T12:00:00Z\""), json);
            assertTrue(json.contains("\"status\":\"open\""), json);
            assertFalse(json.contains("inRangeReadings"), json);
            assertFalse(json.contains("closedAt"), json);
        }
    }

    @Test
    void acknowledgedAlertEventIncludesAcknowledgementDetails() throws Exception {
        Document doc = Incident.newOpenDocument(
                        "site01", "S01", "door", 1.0, null, 0.0, Date.from(Instant.parse("2026-11-09T12:00:00Z")))
                .append("_id", new ObjectId("65f000000000000000000001"))
                .append("acknowledgedAt", Date.from(Instant.parse("2026-11-09T12:01:00Z")))
                .append("acknowledgedBy", "Yessine")
                .append("comment", "Door checked");

        try (Jsonb jsonb = JsonbBuilder.create()) {
            String json = jsonb.toJson(new AlertEvent(AlertEvent.ACKNOWLEDGED, Incident.fromDocument(doc)));

            assertTrue(json.contains("\"event\":\"incident.acknowledged\""), json);
            assertTrue(json.contains("\"acknowledgedAt\":\"2026-11-09T12:01:00Z\""), json);
            assertTrue(json.contains("\"acknowledgedBy\":\"Yessine\""), json);
            assertTrue(json.contains("\"comment\":\"Door checked\""), json);
            assertTrue(json.contains("\"status\":\"open\""), json);
        }
    }
}
