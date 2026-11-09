package tn.supcom.coldtrace.alert;

/** The alert decisions, kept free of database and network code so they can be unit tested. */
final class AlertRules {

    /** Consecutive in-range readings needed to close an incident, so a value hovering at a limit does not flap. */
    static final int IN_RANGE_READINGS_TO_CLOSE = 3;

    enum Action { NONE, OPEN, STILL_BREACHED, RECOVERING, CLOSE }

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
