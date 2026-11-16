package tn.supcom.coldtrace.alert;

import org.junit.jupiter.api.Test;
import tn.supcom.coldtrace.alert.AlertRules.Action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EarlyWarningTest {

    // ---- decision -------------------------------------------------------------------------------------------

    @Test
    void opensWhenTheModelWarnsBeforeTheRealAlert() {
        assertEquals(Action.OPEN, AlertRules.decideEarlyWarning(false, true, false, 0));
        assertEquals(Action.NONE, AlertRules.decideEarlyWarning(false, false, false, 0));
    }

    @Test
    void neverOpensOnceTheTemperatureIncidentIsOpen() {
        assertEquals(Action.NONE, AlertRules.decideEarlyWarning(false, true, true, 0));
    }

    @Test
    void closesAtOnceWhenTheTemperatureIncidentOpens() {
        assertEquals(Action.CLOSE, AlertRules.decideEarlyWarning(true, true, true, 0));
    }

    @Test
    void closesOnlyAfterThreeCalmForecastsInARow() {
        assertEquals(Action.RECOVERING, AlertRules.decideEarlyWarning(true, false, false, 0));
        assertEquals(Action.RECOVERING, AlertRules.decideEarlyWarning(true, false, false, 1));
        assertEquals(Action.CLOSE, AlertRules.decideEarlyWarning(true, false, false, 2));
        // a new warning while recovering resets the count
        assertEquals(Action.STILL_BREACHED, AlertRules.decideEarlyWarning(true, true, false, 2));
    }

    @Test
    void noNewWarningWhileTheUnitCoolsBackAfterARealAlert() {
        // live test: S03's temperature incident closed, then the model still warned at 3.7 °C and falling
        assertEquals(Action.NONE, AlertRules.decideEarlyWarning(false, true, false, true, 0));
        // a warning already open keeps its normal rhythm
        assertEquals(Action.STILL_BREACHED, AlertRules.decideEarlyWarning(true, true, false, true, 0));
        assertEquals(Action.OPEN, AlertRules.decideEarlyWarning(false, true, false, false, 0));
    }

    @Test
    void warmingAgainIsJudgedOnRawReadingsAgainstTheLowestSinceTheAlert() {
        // live test, case A: S03 cooling back after a peak at 6.0 °C, latest reading = lowest so far
        assertFalse(AlertRules.isWarmingAgain(2.9, 2.9));
        // sensor noise around the setpoint is not warming
        assertFalse(AlertRules.isWarmingAgain(2.55, 2.45));
        // live test, 12:18: settled at 2.4 °C after the alert, then a new failure up to 3.5 °C
        assertTrue(AlertRules.isWarmingAgain(3.5, 2.4));
        // no readings since the alert closed: not warming, the hold applies
        assertFalse(AlertRules.isWarmingAgain(null, null));
    }

    @Test
    void theHoldAfterARecoveryLastsTenMinutes() {
        assertTrue(AlertRules.isJustRecovered(0));
        assertTrue(AlertRules.isJustRecovered(599));
        assertFalse(AlertRules.isJustRecovered(600));
        assertFalse(AlertRules.isJustRecovered(-5));   // clock skew: never block on a date in the future
    }

    @Test
    void aWarningWithoutForecastForThreeMinutesIsStale() {
        assertFalse(AlertRules.isForecastStale(30));   // one normal poll interval
        assertFalse(AlertRules.isForecastStale(180));
        assertTrue(AlertRules.isForecastStale(181));
    }

    // ---- parsing the model API's answer ---------------------------------------------------------------------

    /** The example answer documented in mlops/README.md. */
    private static final String ANSWER = """
            {"sensorId": "S03", "horizonMin": 10, "modelVersion": "1", "minute": "2026-11-15T20:47:00+00:00",
             "temperature": 3.15, "predictedMax": 6.21, "limitMax": 4.0, "margin": 0.25,
             "warning": true, "doorOpen": false, "profile": "cold"}
            """;

    @Test
    void parsesTheModelApiAnswer() {
        Forecast f = Forecast.parse(ANSWER);
        assertEquals("S03", f.sensorId());
        assertEquals("1", f.modelVersion());
        assertEquals(10, f.horizonMin());
        assertEquals(3.15, f.temperature());
        assertEquals(6.21, f.predictedMax());
        assertEquals(4.0, f.limitMax());
        assertTrue(f.warning());
    }

    @Test
    void acceptsIntegersAndANullModelVersion() {
        Forecast f = Forecast.parse("""
                {"sensorId": "S01", "horizonMin": 10, "modelVersion": null,
                 "temperature": 3, "predictedMax": 4, "limitMax": 4, "warning": false}
                """);
        assertNull(f.modelVersion());
        assertEquals(3.0, f.temperature());
        assertFalse(f.warning());
    }

    @Test
    void rejectsAnAnswerWithoutTheForecast() {
        assertThrows(IllegalArgumentException.class, () -> Forecast.parse("""
                {"sensorId": "S01", "horizonMin": 10, "modelVersion": "1", "temperature": 3.0,
                 "limitMax": 4.0, "warning": false}
                """));
        assertThrows(IllegalArgumentException.class, () -> Forecast.parse("""
                {"sensorId": "S01", "horizonMin": 10, "modelVersion": "1", "temperature": "3.0",
                 "predictedMax": 4.0, "limitMax": 4.0, "warning": false}
                """));
        assertThrows(IllegalArgumentException.class, () -> Forecast.parse("not json"));
    }
}
