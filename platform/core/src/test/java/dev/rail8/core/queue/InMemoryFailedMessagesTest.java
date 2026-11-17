package dev.rail8.core.queue;

import dev.rail8.core.dlq.FailedMessages;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.testkit.FakeClock;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The in-memory acts refuse a null message id, flow or actor at the call and write nothing; the rules they share with the
 * Postgres ones are {@code FailedMessagesContract}'s (runtime).
 */
class InMemoryFailedMessagesTest {

    final InMemoryWorkQueue queue = new InMemoryWorkQueue(FakeClock.at(Instant.EPOCH), new InMemoryDlq());
    final FailedMessages failed = new InMemoryFailedMessages(queue);
    final FailedMessages.Actor by = new FailedMessages.Actor("ops", "127.0.0.1");

    @Test
    void aNullArgumentIsRefusedAtTheCall() {
        assertEquals("messageId", assertThrows(NullPointerException.class, () -> failed.retry(null, "t", null, 1, by)).getMessage());
        assertEquals("flow", assertThrows(NullPointerException.class, () -> failed.retry("m-1", null, null, 1, by)).getMessage());
        assertEquals("by", assertThrows(NullPointerException.class, () -> failed.retry("m-1", "t", null, 1, null)).getMessage());
        assertEquals("messageId", assertThrows(NullPointerException.class, () -> failed.cancel(null, by, Optional.empty())).getMessage());
        assertEquals("by", assertThrows(NullPointerException.class, () -> failed.cancel("m-1", null, Optional.empty())).getMessage());
        assertEquals("reason", assertThrows(NullPointerException.class, () -> failed.cancel("m-1", by, null)).getMessage());
        assertEquals(0, queue.size(), "nothing written");
    }
}
