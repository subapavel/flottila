package io.flottila.runtime.lease;

import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeRuntime;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-02L (D-083): the in-memory store (a node without Valkey) passes the lease contract on the injected clock — expiry is the
 * clock's, never "always held" — and a lease is held exactly for its ttl from the call: still held a nanosecond before, free then.
 */
class InMemoryPollerLeasesTest extends PollerLeasesContract {

    FakeClock clock;

    @Override
    protected PollerLeases emptyStore() {
        clock = FakeClock.at(FakeRuntime.T0);
        return new InMemoryPollerLeases(clock);
    }

    @Override
    protected Duration shortTtl() { return Duration.ofSeconds(30); }

    @Override
    protected void lapse(String key, Duration ttl) {
        clock.advance(ttl);
    }

    @Test
    void aLeaseIsHeldForExactlyItsTtlFromTheCall() {
        clock.advance(Duration.ofMillis(1234));   // the call is not at the clock's start
        assertTrue(hold(KEY, A, Duration.ofSeconds(30)));

        clock.advance(Duration.ofSeconds(30).minusNanos(1));
        assertFalse(hold(KEY, B, LONG), "held until the last instant before its ttl ran out");
        clock.advance(Duration.ofNanos(1));
        assertTrue(hold(KEY, B, LONG), "free once ttl has passed since the call");
    }
}
