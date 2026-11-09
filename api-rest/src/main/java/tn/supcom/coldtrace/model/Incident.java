package tn.supcom.coldtrace.model;

import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.Date;

/**
 * A period during which one metric of one sensor is outside its limits.
 * Dates are ISO-8601 strings here and BSON dates in MongoDB.
 */
public class Incident {

    public static final String OPEN = "open";
    public static final String CLOSED = "closed";

    private String id;
    private String siteId;
    private String sensorId;
    private String metric;
    private String status;
    private Double value;
    private Double peakValue;
    private Double thresholdMin;
    private Double thresholdMax;
    private String openedAt;
    private String closedAt;

    public Incident() {
    }

    /** Document for a newly opened incident. {@code openedAt} is the time of the reading that breached the limit. */
    public static Document newOpenDocument(String siteId, String sensorId, String metric, double value,
                                           Double thresholdMin, Double thresholdMax, Date openedAt) {
        return new Document("siteId", siteId)
                .append("sensorId", sensorId)
                .append("metric", metric)
                .append("status", OPEN)
                .append("value", value)
                .append("peakValue", value)
                .append("thresholdMin", thresholdMin)
                .append("thresholdMax", thresholdMax)
                .append("openedAt", openedAt)
                .append("inRangeReadings", 0);
    }

    public static Incident fromDocument(Document doc) {
        Incident i = new Incident();
        ObjectId objectId = doc.getObjectId("_id");
        i.id = objectId == null ? null : objectId.toHexString();
        i.siteId = doc.getString("siteId");
        i.sensorId = doc.getString("sensorId");
        i.metric = doc.getString("metric");
        i.status = doc.getString("status");
        i.value = toDouble(doc.get("value"));
        i.peakValue = toDouble(doc.get("peakValue"));
        i.thresholdMin = toDouble(doc.get("thresholdMin"));
        i.thresholdMax = toDouble(doc.get("thresholdMax"));
        i.openedAt = toIso(doc.getDate("openedAt"));
        i.closedAt = toIso(doc.getDate("closedAt"));
        return i;
    }

    private static Double toDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    private static String toIso(Date date) {
        return date == null ? null : date.toInstant().toString();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSiteId() { return siteId; }
    public void setSiteId(String siteId) { this.siteId = siteId; }
    public String getSensorId() { return sensorId; }
    public void setSensorId(String sensorId) { this.sensorId = sensorId; }
    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Double getValue() { return value; }
    public void setValue(Double value) { this.value = value; }
    public Double getPeakValue() { return peakValue; }
    public void setPeakValue(Double peakValue) { this.peakValue = peakValue; }
    public Double getThresholdMin() { return thresholdMin; }
    public void setThresholdMin(Double thresholdMin) { this.thresholdMin = thresholdMin; }
    public Double getThresholdMax() { return thresholdMax; }
    public void setThresholdMax(Double thresholdMax) { this.thresholdMax = thresholdMax; }
    public String getOpenedAt() { return openedAt; }
    public void setOpenedAt(String openedAt) { this.openedAt = openedAt; }
    public String getClosedAt() { return closedAt; }
    public void setClosedAt(String closedAt) { this.closedAt = closedAt; }
}
