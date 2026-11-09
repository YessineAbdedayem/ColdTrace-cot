package tn.supcom.coldtrace.mqtt;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import tn.supcom.coldtrace.model.GatewayStatus;
import tn.supcom.coldtrace.model.Measurement;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Checks the API against the example messages in gateway-iot/MESSAGE-FORMAT.md. */
class GatewayContractTest {

    private static final String READING = """
            {"v":1,"msgId":"site01-S01-1793632152000","siteId":"site01","sensorId":"S01",
             "gatewayId":"gw-site01","ts":"2026-11-02T15:09:12.000Z","tsSource":"sensor",
             "temperature":4.2,"humidity":61.5,"doorOpen":false,
             "receivedAt":"2026-11-02T15:09:12.185Z","replayed":true}""";

    private static final Jsonb JSONB = JsonbBuilder.create();

    @AfterAll
    static void closeJsonb() throws Exception {
        JSONB.close();
    }

    private static Measurement reading(String json) {
        return JSONB.fromJson(json, Measurement.class);
    }

    @Test
    void gatewayReadingIsAcceptedAndStoredWithAllFields() {
        Measurement m = reading(READING);

        assertNull(MqttListener.validate(m));
        Document doc = m.toDocument();
        assertEquals(1, doc.getInteger("v"));
        assertEquals("site01-S01-1793632152000", doc.getString("msgId"));
        assertEquals("site01", doc.getString("siteId"));
        assertEquals("S01", doc.getString("sensorId"));
        assertEquals("gw-site01", doc.getString("gatewayId"));
        assertEquals(Date.from(Instant.parse("2026-11-02T15:09:12.000Z")), doc.getDate("ts"));
        assertEquals("sensor", doc.getString("tsSource"));
        assertEquals(4.2, doc.getDouble("temperature"));
        assertEquals(61.5, doc.getDouble("humidity"));
        assertFalse(doc.getBoolean("doorOpen"));
        assertEquals(Date.from(Instant.parse("2026-11-02T15:09:12.185Z")), doc.getDate("receivedAt"));
        assertEquals(true, doc.getBoolean("replayed"));
    }

    @Test
    void readingWithOnlyOneMeasurementOmitsTheOthers() {
        Measurement m = reading("""
                {"v":1,"msgId":"a","siteId":"site01","sensorId":"S01",
                 "ts":"2026-11-02T15:09:12.000Z","doorOpen":true}""");

        assertNull(MqttListener.validate(m));
        Document doc = m.toDocument();
        assertFalse(doc.containsKey("temperature"));
        assertFalse(doc.containsKey("humidity"));
        assertFalse(doc.containsKey("replayed"));
    }

    @Test
    void documentRoundTripKeepsTheReading() {
        Measurement back = Measurement.fromDocument(reading(READING).toDocument());

        assertEquals("site01-S01-1793632152000", back.getMsgId());
        assertEquals(4.2, back.getTemperature());
        assertEquals(false, back.getDoorOpen());
        assertEquals("2026-11-02T15:09:12Z", back.getTs());
    }

    @Test
    void invalidReadingsAreRejectedWithAReason() {
        String tail = "\"ts\":\"2026-11-02T15:09:12.000Z\",\"temperature\":4.2}";

        assertEquals("missing v",
                MqttListener.validate(reading("{\"msgId\":\"a\",\"siteId\":\"s\",\"sensorId\":\"S\"," + tail)));
        assertEquals("unsupported format version 2",
                MqttListener.validate(reading("{\"v\":2,\"msgId\":\"a\",\"siteId\":\"s\",\"sensorId\":\"S\"," + tail)));
        assertEquals("missing msgId",
                MqttListener.validate(reading("{\"v\":1,\"siteId\":\"s\",\"sensorId\":\"S\"," + tail)));
        assertEquals("no temperature, humidity or doorOpen",
                MqttListener.validate(reading(
                        "{\"v\":1,\"msgId\":\"a\",\"siteId\":\"s\",\"sensorId\":\"S\",\"ts\":\"2026-11-02T15:09:12Z\"}")));
        assertEquals("ts is not ISO-8601",
                MqttListener.validate(reading(
                        "{\"v\":1,\"msgId\":\"a\",\"siteId\":\"s\",\"sensorId\":\"S\",\"ts\":\"yesterday\",\"humidity\":50}")));
    }

    @Test
    void birthMessageIsAcceptedWithoutCounters() {
        GatewayStatus s = JSONB.fromJson("{\"v\":1,\"status\":\"online\"}", GatewayStatus.class);

        assertNull(MqttListener.validate(s));
        Document doc = s.toDocument();
        assertEquals("online", doc.getString("status"));
        assertNotNull(doc.getDate("updatedAt"));
        assertFalse(doc.containsKey("forwarded"));
    }

    @Test
    void heartbeatKeepsItsCounters() {
        GatewayStatus s = JSONB.fromJson("""
                {"v":1,"gatewayId":"gw-site01","siteId":"site01","status":"online",
                 "ts":"2026-11-02T15:12:02.646Z","buffered":0,"forwarded":125,
                 "replayed":160,"rejected":43,"dropped":0}""", GatewayStatus.class);

        assertNull(MqttListener.validate(s));
        Document doc = s.toDocument();
        assertEquals("gw-site01", doc.getString("gatewayId"));
        assertEquals(125L, doc.getLong("forwarded"));
        assertEquals(43L, doc.getLong("rejected"));
    }

    @Test
    void unknownGatewayStateIsRejected() {
        GatewayStatus s = JSONB.fromJson("{\"status\":\"sleeping\"}", GatewayStatus.class);

        assertEquals("unknown status 'sleeping'", MqttListener.validate(s));
    }
}
