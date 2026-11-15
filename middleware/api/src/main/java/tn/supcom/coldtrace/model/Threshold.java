package tn.supcom.coldtrace.model;

import org.bson.Document;

import java.util.List;

/** Allowed ranges for one sensor profile. A null limit means that side is not checked. */
public class Threshold {

    /** Limits from requirement EF-03 of the cahier des charges. */
    public static final List<Threshold> DEFAULTS = List.of(
            new Threshold("cold", 0.0, 4.0, 50.0, 70.0),
            new Threshold("freezer", null, -18.0, 50.0, 70.0));

    private String profile;
    private Double temperatureMin;
    private Double temperatureMax;
    private Double humidityMin;
    private Double humidityMax;

    public Threshold() {
    }

    public Threshold(String profile, Double temperatureMin, Double temperatureMax,
                     Double humidityMin, Double humidityMax) {
        this.profile = profile;
        this.temperatureMin = temperatureMin;
        this.temperatureMax = temperatureMax;
        this.humidityMin = humidityMin;
        this.humidityMax = humidityMax;
    }

    public Document toDocument() {
        return new Document("_id", profile)
                .append("temperatureMin", temperatureMin)
                .append("temperatureMax", temperatureMax)
                .append("humidityMin", humidityMin)
                .append("humidityMax", humidityMax);
    }

    public static Threshold fromDocument(Document doc) {
        return new Threshold(
                doc.getString("_id"),
                toDouble(doc.get("temperatureMin")),
                toDouble(doc.get("temperatureMax")),
                toDouble(doc.get("humidityMin")),
                toDouble(doc.get("humidityMax")));
    }

    private static Double toDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    public String getProfile() { return profile; }
    public void setProfile(String profile) { this.profile = profile; }
    public Double getTemperatureMin() { return temperatureMin; }
    public void setTemperatureMin(Double temperatureMin) { this.temperatureMin = temperatureMin; }
    public Double getTemperatureMax() { return temperatureMax; }
    public void setTemperatureMax(Double temperatureMax) { this.temperatureMax = temperatureMax; }
    public Double getHumidityMin() { return humidityMin; }
    public void setHumidityMin(Double humidityMin) { this.humidityMin = humidityMin; }
    public Double getHumidityMax() { return humidityMax; }
    public void setHumidityMax(Double humidityMax) { this.humidityMax = humidityMax; }
}
