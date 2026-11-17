package dev.rail8.core.dlq;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A cancel's reason as it is checked, and a failure's text as an audit row keeps it. */
class FailedMessagesTest {

    @Test
    void aReasonIsStrippedAndTakesOneTo1024Characters() {
        assertEquals(Optional.empty(), FailedMessages.reason(Optional.empty()), "none given: none");
        assertEquals(Optional.of("late"), FailedMessages.reason(Optional.of(" \tlate\n")), "stripped");
        String longest = "x".repeat(FailedMessages.REASON_MAX);
        assertEquals(Optional.of(longest), FailedMessages.reason(Optional.of("  " + longest + "  ")), "1024 after stripping");
        assertThrows(IllegalArgumentException.class, () -> FailedMessages.reason(Optional.of(" \t ")), "blank");
        assertThrows(IllegalArgumentException.class, () -> FailedMessages.reason(Optional.of(longest + "x")), "1025");
        assertThrows(NullPointerException.class, () -> FailedMessages.reason(null));
    }

    @Test
    void aFailureIsCutTo4096UnitsAndNeverSplitsAPair() {
        String whole = "e".repeat(FailedMessages.ERROR_MAX);
        assertEquals(whole, FailedMessages.cut(whole), "4096 units: whole");
        assertEquals(whole, FailedMessages.cut(whole + "tail"), "longer: its first 4096");
        String pair = "e".repeat(FailedMessages.ERROR_MAX - 1) + "😀" + "tail";
        assertEquals("e".repeat(FailedMessages.ERROR_MAX - 1), FailedMessages.cut(pair), "a pair across the cut is left out whole");
    }
}
