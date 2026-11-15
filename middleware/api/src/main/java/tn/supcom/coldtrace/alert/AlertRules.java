package tn.supcom.coldtrace.alert;

/** The alert decisions, kept free of database and network code so they can be unit tested. */
final class AlertRules {

    /** Consecutive in-range readings needed to close an incident, so a value hovering at a limit does not flap. */
    static final int IN_RANGE_READINGS_TO_CLOSE = 3;

    /** A known sensor with no reading for this long gets a "silence" incident. */
    static final long SILENT_AFTER_SECONDS = 60;

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
}
