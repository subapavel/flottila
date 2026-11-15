package io.flottila.runtime.node;

import io.flottila.core.flow.RetryPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-14a P1 (implementer): an in-process {@link NodeConfig} — built by its canonical constructor, not read from an env — is held
 * to the ranges {@code FLOTTILA_RETRIES_COUNT} (0..100) and {@code FLOTTILA_RETRIES_INTERVAL} (PT1S..PT24H) name, so no limit is
 * unbounded however the node is configured — nor the cap of a growing schedule (at most PT24H); the message names the key. In range, the constructor keeps the schedule it was given.
 */
class NodeConfigRetriesInProcessTest {

    /** {@code base} with {@code retries}, every other component its own. */
    static NodeConfig with(NodeConfig base, RetryPolicy retries) {
        return new NodeConfig(base.nodeId(), base.env(), base.admin(), base.dataDir(), base.stopDeadline(), base.maxPackageBytes(), base.pg(),
                base.blobs(), base.archives(), base.s3(), base.secrets(), base.leases(), base.retention(), base.cpuSampling(), base.targets(),
                base.journal(), base.journalKey(), base.journalSweep(), base.intakeGroup(), base.nodeLease(), base.nodeLeaseRenew(), retries);
    }

    static RetryPolicy every(int count, Duration interval) {
        return new RetryPolicy(count, interval, interval, 1.0);
    }

    final NodeConfig base = NodeConfig.from(NodeConfigRetriesTest.env());

    @Test
    void inRangeTheScheduleIsKeptAtItsEdges() {
        assertEquals(every(100, Duration.ofSeconds(1)), with(base, every(100, Duration.ofSeconds(1))).retries(), "the upper count, the lower interval");
        assertEquals(every(0, Duration.ofHours(24)), with(base, every(0, Duration.ofHours(24))).retries(), "no retry, the upper interval");
    }

    @Test
    void aCountAboveTheRangeIsRefusedNamingItsKey() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> with(base, every(101, Duration.ofMinutes(5))));
        assertTrue(e.getMessage().contains("FLOTTILA_RETRIES_COUNT") && e.getMessage().contains("101"), e.getMessage());
    }

    @Test
    void anIntervalOutsideTheRangeIsRefusedNamingItsKey() {
        for (Duration interval : new Duration[] {Duration.ofMillis(999), Duration.ofHours(24).plusMillis(1)}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> with(base, every(3, interval)), interval.toString());
            assertTrue(e.getMessage().contains("FLOTTILA_RETRIES_INTERVAL") && e.getMessage().contains(interval.toString()), e.getMessage());
        }
    }

    @Test
    void aGrowingScheduleIsKeptUpToTheRangesCapAndRefusedPastIt() {
        RetryPolicy growing = new RetryPolicy(5, Duration.ofSeconds(1), Duration.ofHours(24), 2.0);
        assertEquals(growing, with(base, growing).retries(), "a cap at the range's end");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> with(base, new RetryPolicy(5, Duration.ofSeconds(1), Duration.ofHours(24).plusMillis(1), 2.0)));
        assertTrue(e.getMessage().contains("FLOTTILA_RETRIES_INTERVAL") && e.getMessage().contains("PT24H0.001S"), e.getMessage());
    }

    @Test
    void noScheduleIsRefused() {
        NullPointerException e = assertThrows(NullPointerException.class, () -> with(base, null));
        assertEquals("retries", e.getMessage());
    }
}
