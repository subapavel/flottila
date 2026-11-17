package dev.rail8.runtime.queue;

import dev.rail8.core.dlq.Dlq;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.queue.InMemoryWorkQueue;
import dev.rail8.core.queue.WorkQueue;
import dev.rail8.testkit.FakeClock;

import java.time.Duration;
import java.time.Instant;

/** P2-16a (D-121 A2): core's in-memory queue passes the hand-off contract; its leases run on a fake clock. */
class InMemoryWorkQueueContractTest extends WorkQueueContract {

    private FakeClock clock;
    private InMemoryDlq dlq;

    @Override
    protected WorkQueue emptyQueue() {
        clock = FakeClock.at(Instant.parse("2026-11-10T10:00:00Z"));
        dlq = new InMemoryDlq();
        return new InMemoryWorkQueue(clock, dlq);
    }

    @Override
    protected void expireLeases() {
        clock.advance(LEASE.plus(Duration.ofSeconds(1)));
    }

    @Override
    protected void passTime(Duration d) {
        clock.advance(d);
    }

    @Override
    protected void kept(String messageId) {
        // the in-memory queue keeps no bodies: a park needs no ref
    }

    @Override
    protected Dlq dlq() {
        return dlq;
    }
}
