package tn.supcom.coldtrace.alert;

import jakarta.json.Json;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

import java.io.StringReader;

/**
 * One answer of the model API's {@code GET /forecast/{sensorId}} (mlops/serving/app.py).
 *
 * @param temperature  the sensor's temperature in the latest complete minute, °C
 * @param predictedMax the predicted highest temperature of the next {@code horizonMin} minutes, °C
 * @param warning      the model's decision: predictedMax above limitMax + margin, with the door closed
 */
public record Forecast(String sensorId, String modelVersion, int horizonMin, double temperature, double predictedMax,
                double limitMax, boolean warning) {

    /** @throws IllegalArgumentException when a required field is missing or has the wrong type */
    static Forecast parse(String json) {
        try (JsonReader reader = Json.createReader(new StringReader(json))) {
            JsonObject o = reader.readObject();
            return new Forecast(
                    o.getString("sensorId"),
                    o.isNull("modelVersion") ? null : o.getString("modelVersion"),
                    o.getInt("horizonMin"),
                    number(o, "temperature"),
                    number(o, "predictedMax"),
                    number(o, "limitMax"),
                    o.getBoolean("warning"));
        } catch (RuntimeException e) { // JsonException, ClassCastException, NullPointerException
            throw new IllegalArgumentException("unexpected forecast answer: " + e.getMessage(), e);
        }
    }

    private static double number(JsonObject o, String key) {
        JsonValue v = o.get(key);
        if (!(v instanceof JsonNumber n)) {
            throw new IllegalArgumentException(key + " is not a number");
        }
        return n.doubleValue();
    }
}
