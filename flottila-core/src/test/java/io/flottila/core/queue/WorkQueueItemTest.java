package io.flottila.core.queue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * P2-15a: an item names a hand-out — seq, message, flow, step, key and attempts; its doubts, read off the row's history, are
 * not part of what it names, and the six-argument item is in no doubt.
 */
class WorkQueueItemTest {

    static final WorkQueue.Item HANDED = new WorkQueue.Item(7, "m-1", "f", 1, "K", 2, 1);

    @Test
    void theSameHandOutIsEqualWhateverItsDoubts() {
        WorkQueue.Item built = new WorkQueue.Item(7, "m-1", "f", 1, "K", 2);
        assertEquals(0, built.doubts(), "the six-argument item is in no doubt");
        assertEquals(1, HANDED.doubts());
        assertEquals(HANDED, built);
        assertEquals(HANDED.hashCode(), built.hashCode());
    }

    @Test
    void anotherHandOutIsNotEqual() {
        assertNotEquals(HANDED, new WorkQueue.Item(7, "m-1", "f", 1, "K", 3, 1), "another attempt");
        assertNotEquals(HANDED, new WorkQueue.Item(8, "m-1", "f", 1, "K", 2, 1), "another row");
        assertNotEquals(HANDED, new WorkQueue.Item(7, "m-1", "f", 1, null, 2, 1), "another key");
        assertNotEquals(HANDED, (Object) "m-1", "not an item");
    }
}
