package tn.supcom.coldtrace.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IncidentAcknowledgementTest {

    @Test
    void acceptsValidInput() {
        IncidentAcknowledgement input = IncidentAcknowledgement.validate("Yessine", "Door checked");

        assertEquals("Yessine", input.operator());
        assertEquals("Door checked", input.comment());
    }

    @Test
    void rejectsBlankOperator() {
        assertThrows(IllegalArgumentException.class,
                () -> IncidentAcknowledgement.validate("   ", null));
    }

    @Test
    void rejectsOperatorLongerThan64Characters() {
        assertThrows(IllegalArgumentException.class,
                () -> IncidentAcknowledgement.validate("x".repeat(65), null));
    }

    @Test
    void rejectsCommentLongerThan500Characters() {
        assertThrows(IllegalArgumentException.class,
                () -> IncidentAcknowledgement.validate("Operator", "x".repeat(501)));
    }

    @Test
    void acceptsNullComment() {
        IncidentAcknowledgement input = IncidentAcknowledgement.validate("Operator", null);

        assertNull(input.comment());
    }

    @Test
    void trimsOperatorAndComment() {
        IncidentAcknowledgement input = IncidentAcknowledgement.validate("  Operator  ", "  Checked  ");

        assertEquals("Operator", input.operator());
        assertEquals("Checked", input.comment());
    }
}
