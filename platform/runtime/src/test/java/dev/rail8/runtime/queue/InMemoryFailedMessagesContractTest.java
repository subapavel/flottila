package dev.rail8.runtime.queue;

import dev.rail8.core.dlq.Dlq;
import dev.rail8.core.dlq.FailedMessages;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.queue.InMemoryFailedMessages;
import dev.rail8.core.queue.InMemoryWorkQueue;
import dev.rail8.core.queue.WorkQueue;
import dev.rail8.testkit.FakeClock;

import java.time.Duration;
import java.time.Instant;

/** Core's in-memory acts over its in-memory queue and DLQ pass the contract; leases run on a fake clock. */
class InMemoryFailedMessagesContractTest extends FailedMessagesContract {

    private FakeClock clock;
    private InMemoryDlq dlq;
    private FailedMessages failed;

    @Override
    protected WorkQueue emptyQueue() {
        clock = FakeClock.at(Instant.parse("2026-11-10T10:00:00Z"));
        dlq = new InMemoryDlq();
        InMemoryWorkQueue rows = new InMemoryWorkQueue(clock, dlq);
        failed = new InMemoryFailedMessages(rows);
        return rows;
    }

    @Override
    protected FailedMessages failed() {
        return failed;
    }

    @Override
    protected Dlq dlq() {
        return dlq;
    }

    @Override
    protected void kept(String messageId) {
        // the in-memory queue keeps no bodies
    }

    @Override
    protected void expireLeases() {
        clock.advance(LEASE.plus(Duration.ofSeconds(1)));
    }
}
