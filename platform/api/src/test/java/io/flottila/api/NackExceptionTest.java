package io.flottila.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A NACK fails the send with its reason and whether a retry may help. */
class NackExceptionTest {

    @Test
    void carriesTheReasonAndTheRetryableFlag() {
        NackException retry = new NackException("busy", true);
        assertEquals("busy", retry.reason());
        assertTrue(retry.retryable());
        assertEquals("not accepted (retryable): busy", retry.getMessage());
        NackException last = new NackException("malformed", false);
        assertFalse(last.retryable());
        assertEquals("not accepted (final): malformed", last.getMessage());
        assertThrows(NullPointerException.class, () -> new NackException(null, true));
    }
}
