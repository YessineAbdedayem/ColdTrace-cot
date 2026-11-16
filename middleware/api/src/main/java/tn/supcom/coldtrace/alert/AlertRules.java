package tn.supcom.coldtrace.alert;

/** The alert decisions, kept free of database and network code so they can be unit tested. */
final class AlertRules {

    /** Consecutive in-range readings needed to close an incident, so a value hovering at a limit does not flap. */
    static final int IN_RANGE_READINGS_TO_CLOSE = 3;

    /** A known sensor with no reading for this long gets a "silence" incident. */
    static final long SILENT_AFTER_SECONDS = 60;

    /**
     * An open early warning with no forecast for this long is closed: the model API is down, or the sensor is silent
     * (then its "silence" incident takes over). Several poll rounds, so one slow answer does not close it.
     */
    static final long EARLY_WARNING_STALE_SECONDS = 180;

    /**
     * After a temperature incident closes, no early warning opens for this long (the model's horizon, 10 min), unless
     * the unit is warming again (see {@link #isWarmingAgain}).
     */
    static final long EARLY_WARNING_HOLD_AFTER_RECOVERY_SECONDS = 600;

    /** Above the lowest reading since the alert closed by more than this, the unit is warming again (noise: ±0.05). */
    static final double WARMING_AGAIN_DELTA = 0.3;

    /** The door is a metric like the others: 1 when open, 0 when closed, with 0 as its maximum. */
    static final double DOOR_OPEN = 1.0;
    static final double DOOR_CLOSED = 0.0;
    static final double DOOR_MAX = 0.0;

    enum Action { NONE, OPEN, STILL_BREACHED, RECOVERING, CLOSE }

    static double doorValue(boolean doorOpen) {
        return doorOpen ? DOOR_OPEN : DOOR_CLOSED;
    }

    static boolean isSilent(long secondsSinceLastReading) {
        return secondsSinceLastReading > SILENT_AFTER_SECONDS;
    }

    static boolean isForecastStale(long secondsSinceLastForecast) {
        return secondsSinceLastForecast > EARLY_WARNING_STALE_SECONDS;
    }

    private AlertRules() {
    }

    static boolean isBreached(double value, Double min, Double max) {
        return (min != null && value < min) || isAbove(value, max);
    }

    static boolean isAbove(double value, Double max) {
        return max != null && value > max;
    }

    /** @param inRangeReadings consecutive in-range readings already counted on the open incident */
    static Action decide(boolean incidentOpen, boolean breached, int inRangeReadings) {
        if (!incidentOpen) {
            return breached ? Action.OPEN : Action.NONE;
        }
        if (breached) {
            return Action.STILL_BREACHED;
        }
        return inRangeReadings + 1 >= IN_RANGE_READINGS_TO_CLOSE ? Action.CLOSE : Action.RECOVERING;
    }

    /**
     * Early warning from the model's forecast. Same open/close rhythm as the other metrics (it closes after
     * {@link #IN_RANGE_READINGS_TO_CLOSE} calm forecasts in a row), except that it is pointless once the real
     * temperature incident is open: then it closes at once, or does not open.
     *
     * @param calmForecasts consecutive forecasts without a warning already counted on the open early warning
     */
    static Action decideEarlyWarning(boolean warningOpen, boolean modelWarns, boolean temperatureIncidentOpen,
                                     int calmForecasts) {
        return decideEarlyWarning(warningOpen, modelWarns, temperatureIncidentOpen, false, calmForecasts);
    }

    /**
     * @param justRecovered the sensor's temperature incident closed less than
     *                      {@link #EARLY_WARNING_HOLD_AFTER_RECOVERY_SECONDS} ago and the unit is not warming again.
     *                      The model's 10-minute window still holds the heat of that incident, so it predicts too warm
     *                      while the unit cools back down: no new early warning opens then (live test: a warning at
     *                      2.9 °C and falling). A unit warming again is a new failure and is not held back (live test:
     *                      a real warning at 2.8 °C and rising, 9 min 53 s after a recovery).
     */
    static Action decideEarlyWarning(boolean warningOpen, boolean modelWarns, boolean temperatureIncidentOpen,
                                     boolean justRecovered, int calmForecasts) {
        if (temperatureIncidentOpen) {
            return warningOpen ? Action.CLOSE : Action.NONE;
        }
        if (justRecovered && !warningOpen) {
            return Action.NONE;
        }
        return decide(warningOpen, modelWarns, calmForecasts);
    }

    /**
     * Raw readings, not minute averages: averages lag behind a fast change, and right after a peak a falling unit
     * can still show a rising 2-minute average (live test: 3.29 → 5.32 → 3.73 °C, really falling from 6.0).
     *
     * @param latest the sensor's latest reading
     * @param lowest its lowest reading since the temperature incident closed
     */
    static boolean isWarmingAgain(Double latest, Double lowest) {
        return latest != null && lowest != null && latest > lowest + WARMING_AGAIN_DELTA;
    }

    static boolean isJustRecovered(long secondsSinceTemperatureIncidentClosed) {
        return secondsSinceTemperatureIncidentClosed >= 0
                && secondsSinceTemperatureIncidentClosed < EARLY_WARNING_HOLD_AFTER_RECOVERY_SECONDS;
    }
}
