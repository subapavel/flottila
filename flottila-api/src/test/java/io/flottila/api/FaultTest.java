package io.flottila.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The api's failures say whose fault they are (D-049 a): the component that speaks to the sender answers by
 * {@link Fault.Kind} — MALFORMED 400, INVALID 422, DOWNSTREAM 502 / 503, INTERNAL 500 — without knowing the types.
 */
class FaultTest {

    @Test
    void aNackIsADownstreamFaultRetryableOrNot() {
        NackException retryable = new NackException("partner busy", true);
        Fault asFault = assertInstanceOf(Fault.class, retryable);
        assertEquals(Fault.Kind.DOWNSTREAM, asFault.kind(), "the receiver said no: downstream");
        assertTrue(retryable.retryable());
        assertEquals("partner busy", retryable.reason());
        assertTrue(retryable.getMessage().contains("partner busy"), retryable.getMessage());

        NackException last = new NackException("order unknown", false);
        assertEquals(Fault.Kind.DOWNSTREAM, last.kind(), "retryable or not, still the receiver's answer");
        assertFalse(last.retryable());
        assertEquals("order unknown", last.reason());
        assertTrue(last.getMessage().contains("order unknown"), last.getMessage());
    }

    @Test
    void aBodyAboveTheLimitIsAnInvalidFault() {
        BodyTooLargeException e = new BodyTooLargeException(BlobKey.body("m-1", 0), 2048, 1024);
        assertEquals(Fault.Kind.INVALID, assertInstanceOf(Fault.class, e).kind(), "the sender's body broke a size limit");
        assertTrue(e.getMessage().contains("2048") && e.getMessage().contains("1024"), "names the size and the limit: " + e.getMessage());
        assertEquals(2048, e.size());
        assertEquals(1024, e.limit());
    }

    @Test
    void theKindsAreTheFourOfTheStatusTable() {
        assertEquals(List.of(Fault.Kind.MALFORMED, Fault.Kind.INVALID, Fault.Kind.DOWNSTREAM, Fault.Kind.INTERNAL),
                List.of(Fault.Kind.values()), "one kind per row of the http status table, in its order");
        assertEquals(Fault.Kind.INTERNAL, Fault.Kind.valueOf("INTERNAL"));
    }
}
