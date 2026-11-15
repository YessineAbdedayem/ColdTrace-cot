package tn.supcom.coldtrace.model;

/** Normalized, validated demo acknowledgement input. The operator name is not an authenticated identity. */
public record IncidentAcknowledgement(String operator, String comment) {

    public static IncidentAcknowledgement validate(Object operator, Object comment) {
        if (!(operator instanceof String rawOperator)) {
            throw new IllegalArgumentException("operator is required and must be a string");
        }
        String normalizedOperator = rawOperator.trim();
        if (normalizedOperator.isEmpty() || normalizedOperator.length() > 64) {
            throw new IllegalArgumentException("operator must contain 1 to 64 characters");
        }
        if (comment != null && !(comment instanceof String)) {
            throw new IllegalArgumentException("comment must be a string");
        }

        String normalizedComment = comment == null ? null : ((String) comment).trim();
        if (normalizedComment != null && normalizedComment.length() > 500) {
            throw new IllegalArgumentException("comment must be at most 500 characters");
        }
        return new IncidentAcknowledgement(normalizedOperator, normalizedComment);
    }
}
