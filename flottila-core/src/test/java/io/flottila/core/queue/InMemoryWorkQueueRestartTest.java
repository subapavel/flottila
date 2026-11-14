package io.flottila.core.queue;

import io.flottila.testkit.FakeClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * CORE-14b P8 (implementer's cases): the in-memory restart refuses a null message id or flow at the call, appending nothing — a
 * row without either could never be claimed or completed —, as its enqueue does; the rest of its contract is
 * {@code WorkQueueContract} (runtime).
 */
class InMemoryWorkQueueRestartTest {

    final InMemoryWorkQueue queue = new InMemoryWorkQueue(FakeClock.at(Instant.EPOCH));

    @Test
    void aNullIdOrFlowIsRefusedAndAppendsNothing() {
        assertEquals("messageId", assertThrows(NullPointerException.class, () -> queue.restart(null, "t", null, 1)).getMessage());
        assertEquals("flow", assertThrows(NullPointerException.class, () -> queue.restart("m-1", null, null, 1)).getMessage());
        assertEquals(0, queue.size(), "nothing appended");
        assertEquals(List.of(), queue.claim("n1", "t", 10, Duration.ofSeconds(30)).await());
    }

    /** A restart takes a seq of its own: rows enqueued after it neither replace it nor head before it (PIT r1: seq counted down). */
    @Test
    void rowsEnqueuedAfterARestartQueueBehindIt() {
        queue.restart("m-1", "t", null, 2).await();
        queue.enqueue("m-2", "t", 1, null).await();
        queue.enqueue("m-3", "t", 1, null).await();
        List<WorkQueue.Item> claimed = queue.claim("n1", "t", 10, Duration.ofSeconds(30)).await();
        assertEquals(List.of("m-1", "m-2", "m-3"), claimed.stream().map(WorkQueue.Item::messageId).toList(), "in the order they were added");
        assertEquals(3, claimed.stream().mapToLong(WorkQueue.Item::seq).distinct().count(), "three seqs");
    }
}
