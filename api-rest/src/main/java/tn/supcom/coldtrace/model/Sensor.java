package tn.supcom.coldtrace.model;

import org.bson.Document;

public class Sensor {

    private String id;
    private String siteId;
    private String type;
    private String name;
    private Double minThreshold;
    private Double maxThreshold;

    public Sensor() {
    }

    public Sensor(String id, String siteId, String type, String name, Double minThreshold, Double maxThreshold) {
        this.id = id;
        this.siteId = siteId;
        this.type = type;
        this.name = name;
        this.minThreshold = minThreshold;
        this.maxThreshold = maxThreshold;
    }

    public Document toDocument() {
        return new Document("_id", id)
                .append("siteId", siteId)
                .append("type", type)
                .append("name", name)
                .append("minThreshold", minThreshold)
                .append("maxThreshold", maxThreshold);
    }

    public static Sensor fromDocument(Document doc) {
        return new Sensor(
                doc.getString("_id"),
                doc.getString("siteId"),
                doc.getString("type"),
                doc.getString("name"),
                toDouble(doc.get("minThreshold")),
                toDouble(doc.get("maxThreshold")));
    }

    private static Double toDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSiteId() { return siteId; }
    public void setSiteId(String siteId) { this.siteId = siteId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Double getMinThreshold() { return minThreshold; }
    public void setMinThreshold(Double minThreshold) { this.minThreshold = minThreshold; }
    public Double getMaxThreshold() { return maxThreshold; }
    public void setMaxThreshold(Double maxThreshold) { this.maxThreshold = maxThreshold; }
}
