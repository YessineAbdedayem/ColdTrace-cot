package tn.supcom.coldtrace.model;

import org.bson.Document;

import java.time.Instant;
import java.util.Date;
import java.util.Set;

/**
 * One sensor reading. In the MQTT payload {@code ts} is an ISO-8601 string;
 * in MongoDB it is stored as a BSON date.
 */
public class Measurement {

    public static final Set<String> TYPES = Set.of("temperature", "humidity", "door");

    private String sensorId;
    private String siteId;
    private String type;
    private Double value;
    private String unit;
    private String ts;

    public Measurement() {
    }

    public Measurement(String sensorId, String siteId, String type, Double value, String unit, String ts) {
        this.sensorId = sensorId;
        this.siteId = siteId;
        this.type = type;
        this.value = value;
        this.unit = unit;
        this.ts = ts;
    }

    /** @throws java.time.format.DateTimeParseException if ts is not ISO-8601 */
    public Document toDocument() {
        return new Document("sensorId", sensorId)
                .append("siteId", siteId)
                .append("type", type)
                .append("value", value)
                .append("unit", unit)
                .append("ts", Date.from(Instant.parse(ts)));
    }

    public static Measurement fromDocument(Document doc) {
        Date ts = doc.getDate("ts");
        Object value = doc.get("value");
        return new Measurement(
                doc.getString("sensorId"),
                doc.getString("siteId"),
                doc.getString("type"),
                value instanceof Number n ? n.doubleValue() : null,
                doc.getString("unit"),
                ts == null ? null : ts.toInstant().toString());
    }

    public String getSensorId() { return sensorId; }
    public void setSensorId(String sensorId) { this.sensorId = sensorId; }
    public String getSiteId() { return siteId; }
    public void setSiteId(String siteId) { this.siteId = siteId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Double getValue() { return value; }
    public void setValue(Double value) { this.value = value; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public String getTs() { return ts; }
    public void setTs(String ts) { this.ts = ts; }
}
