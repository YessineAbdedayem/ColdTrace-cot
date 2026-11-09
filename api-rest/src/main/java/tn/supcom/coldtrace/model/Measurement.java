package tn.supcom.coldtrace.model;

import org.bson.Document;

import java.time.Instant;
import java.util.Date;

/**
 * One gateway reading, in the format defined by gateway-iot/MESSAGE-FORMAT.md.
 * In the MQTT payload {@code ts} and {@code receivedAt} are ISO-8601 strings;
 * in MongoDB they are stored as BSON dates. Absent optional fields are not stored.
 */
public class Measurement {

    public static final int FORMAT_VERSION = 1;

    private Integer v;
    private String msgId;
    private String siteId;
    private String sensorId;
    private String gatewayId;
    private String ts;
    private String tsSource;
    private Double temperature;
    private Double humidity;
    private Boolean doorOpen;
    private String receivedAt;
    private Boolean replayed;

    public Measurement() {
    }

    /** @throws java.time.format.DateTimeParseException if ts or receivedAt is not ISO-8601 */
    public Document toDocument() {
        Document doc = new Document("v", v)
                .append("msgId", msgId)
                .append("siteId", siteId)
                .append("sensorId", sensorId)
                .append("ts", Date.from(Instant.parse(ts)));
        appendIfPresent(doc, "gatewayId", gatewayId);
        appendIfPresent(doc, "tsSource", tsSource);
        appendIfPresent(doc, "temperature", temperature);
        appendIfPresent(doc, "humidity", humidity);
        appendIfPresent(doc, "doorOpen", doorOpen);
        if (receivedAt != null) {
            doc.append("receivedAt", Date.from(Instant.parse(receivedAt)));
        }
        appendIfPresent(doc, "replayed", replayed);
        return doc;
    }

    public static Measurement fromDocument(Document doc) {
        Measurement m = new Measurement();
        m.v = doc.getInteger("v");
        m.msgId = doc.getString("msgId");
        m.siteId = doc.getString("siteId");
        m.sensorId = doc.getString("sensorId");
        m.gatewayId = doc.getString("gatewayId");
        m.ts = isoOrNull(doc.getDate("ts"));
        m.tsSource = doc.getString("tsSource");
        m.temperature = doubleOrNull(doc.get("temperature"));
        m.humidity = doubleOrNull(doc.get("humidity"));
        m.doorOpen = doc.getBoolean("doorOpen");
        m.receivedAt = isoOrNull(doc.getDate("receivedAt"));
        m.replayed = doc.getBoolean("replayed");
        return m;
    }

    private static void appendIfPresent(Document doc, String key, Object value) {
        if (value != null) {
            doc.append(key, value);
        }
    }

    private static String isoOrNull(Date date) {
        return date == null ? null : date.toInstant().toString();
    }

    private static Double doubleOrNull(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }

    public Integer getV() { return v; }
    public void setV(Integer v) { this.v = v; }
    public String getMsgId() { return msgId; }
    public void setMsgId(String msgId) { this.msgId = msgId; }
    public String getSiteId() { return siteId; }
    public void setSiteId(String siteId) { this.siteId = siteId; }
    public String getSensorId() { return sensorId; }
    public void setSensorId(String sensorId) { this.sensorId = sensorId; }
    public String getGatewayId() { return gatewayId; }
    public void setGatewayId(String gatewayId) { this.gatewayId = gatewayId; }
    public String getTs() { return ts; }
    public void setTs(String ts) { this.ts = ts; }
    public String getTsSource() { return tsSource; }
    public void setTsSource(String tsSource) { this.tsSource = tsSource; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Double getHumidity() { return humidity; }
    public void setHumidity(Double humidity) { this.humidity = humidity; }
    public Boolean getDoorOpen() { return doorOpen; }
    public void setDoorOpen(Boolean doorOpen) { this.doorOpen = doorOpen; }
    public String getReceivedAt() { return receivedAt; }
    public void setReceivedAt(String receivedAt) { this.receivedAt = receivedAt; }
    public Boolean getReplayed() { return replayed; }
    public void setReplayed(Boolean replayed) { this.replayed = replayed; }
}
