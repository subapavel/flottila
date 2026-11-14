package io.flottila.core.flow;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static io.flottila.core.flow.FlowWorkerRetryTest.REWRITE;
import static io.flottila.core.flow.FlowWorkerRetryTest.flow;
import static io.flottila.core.flow.FlowWorkerRetryTest.retries;
import static io.flottila.core.flow.FlowWorkerTest.TO_PARTNER;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-14a review r1 I2 (implementer): a schedule of retries is held to the D-135 bounds wherever it enters — the node's default
 * through {@link FlowBuilder#retries} and a flow's own through {@link FlowDefinition}'s constructor, not only a flow file's
 * keys: at most 100 retries (a count of {@link Integer#MAX_VALUE} would overflow the worker's bound, count + 1), an interval
 * from 1s to 24h, a cap of at most 24h. Each edge is accepted; one past it is refused naming the entry and the value.
 */
class RetryBoundsTest {

    static final Duration SECOND = Duration.ofSeconds(1);
    static final Duration DAY = Duration.ofHours(24);

    /** Each schedule one past an edge, with the value its refusal names. */
    static final List<Object[]> PAST = List.of(
            new Object[] {new RetryPolicy(101, SECOND, SECOND, 1.0), "101"},
            new Object[] {new RetryPolicy(Integer.MAX_VALUE, SECOND, SECOND, 1.0), String.valueOf(Integer.MAX_VALUE)},
            new Object[] {new RetryPolicy(3, Duration.ofMillis(999), Duration.ofMillis(999), 1.0), "PT0.999S"},
            new Object[] {new RetryPolicy(3, DAY.plusMillis(1), DAY.plusMillis(1), 1.0), "PT24H0.001S"},
            new Object[] {new RetryPolicy(3, SECOND, DAY.plusMillis(1), 2.0), "PT24H0.001S"});

    /** Each schedule at an edge. */
    static final List<RetryPolicy> AT = List.of(
            new RetryPolicy(100, SECOND, SECOND, 1.0), new RetryPolicy(0, DAY, DAY, 1.0), new RetryPolicy(5, SECOND, DAY, 2.0));

    static FlowDefinition withRetries(FlowDefinition f, Optional<RetryPolicy> retries) {
        return new FlowDefinition(f.id(), f.version(), f.fromEndpoint(), f.deliveryMode(), f.saveTimeout(), f.timeout(), f.maxBodyBytes(),
                f.maxInFlight(), f.maxInFlightBytes(), f.partitionBy(), f.onFailure(), f.forwardHeaders(), f.retry(), retries, f.recording(), f.steps());
    }

    @Test
    void theNodesDefaultIsHeldToTheBounds() {
        FlowBuilder b = new FlowWorkerRetryTest.World().builder();
        for (Object[] past : PAST) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> b.retries((RetryPolicy) past[0]), past[0].toString());
            assertTrue(e.getMessage().startsWith("the node's retries") && e.getMessage().contains((String) past[1]), e.getMessage());
        }
        for (RetryPolicy at : AT) assertDoesNotThrow(() -> b.retries(at), at.toString());
    }

    @Test
    void aFlowsOwnScheduleIsHeldToTheBoundsBuiltInProcess() {
        FlowDefinition read = FlowDefinition.from(flow(retries(1, "1m"), List.of(REWRITE, TO_PARTNER)));
        for (Object[] past : PAST) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> withRetries(read, Optional.of((RetryPolicy) past[0])), past[0].toString());
            assertTrue(e.getMessage().startsWith("delivery.retries") && e.getMessage().contains((String) past[1]), e.getMessage());
        }
        for (RetryPolicy at : AT) assertEquals(Optional.of(at), withRetries(read, Optional.of(at)).retries(), at.toString());
        assertEquals(Optional.empty(), withRetries(read, Optional.empty()).retries(), "none: the node's default applies");
    }
}
