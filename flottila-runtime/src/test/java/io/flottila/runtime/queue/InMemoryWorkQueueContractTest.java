package io.flottila.runtime.queue;

import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.queue.WorkQueue;
import io.flottila.testkit.FakeClock;

import java.time.Duration;
import java.time.Instant;

/** P2-16a (D-121 A2): core's in-memory queue passes the hand-off contract; its leases run on a fake clock. */
class InMemoryWorkQueueContractTest extends WorkQueueContract {

    private FakeClock clock;

    @Override
    protected WorkQueue emptyQueue() {
        clock = FakeClock.at(Instant.parse("2026-11-10T10:00:00Z"));
        return new InMemoryWorkQueue(clock);
    }

    @Override
    protected void expireLeases() {
        clock.advance(LEASE.plus(Duration.ofSeconds(1)));
    }

    @Override
    protected void passTime(Duration d) {
        clock.advance(d);
    }
}
