package dev.rail8.core.batch;

import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeTimer;
import io.vertx.core.Future;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-6b, FIX-235 decision 3: the weighed writer fails the save of an item weighing below zero — a failed future, never a
 * throw: the weight is computed from the item by code the writer was given, it is no argument of the save — and queues
 * nothing for it. (CORE-6b pinned a throw here; decision 3 made that expectation wrong.)
 */
class BatchingWriterWeightTest {

    /** Both ways in compute the weight before anything is queued. */
    enum Mode {
        SAVE { @Override Future<Void> save(BatchingWriter<String> w, String item) { return w.save(item); } },
        SAVE_WAITING { @Override Future<Void> save(BatchingWriter<String> w, String item) { return w.saveWaiting(item); } };

        abstract Future<Void> save(BatchingWriter<String> w, String item);
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void aNegativeWeightFailsThatSaveAndQueuesNothing(Mode mode) {
        List<List<String>> batches = new ArrayList<>();
        BatchSink<String> sink = batch -> {
            batches.add(List.copyOf(batch));
            return Future.succeededFuture();
        };
        FakeTimer timer = new FakeTimer(FakeClock.at(Instant.EPOCH));
        BatchingWriter<String> w = new BatchingWriter<>(sink, timer,
                new BatchingWriter.Options(10, Duration.ZERO, 1, 100), item -> item.startsWith("-") ? -42L : 0L, 10);

        List<LogRecord> logged = new CopyOnWriteArrayList<>();
        BatchingWriterErrorContainmentTest.capturingEverything(logged, () -> {
            Future<Void> minus = assertDoesNotThrow(() -> mode.save(w, "-minus"), "a negative weight fails that save, it is not thrown at the caller");
            assertTrue(minus.failed(), "the save of a negative weight is failed at once: " + minus);
            IllegalArgumentException e = assertInstanceOf(IllegalArgumentException.class, minus.cause(), "an IllegalArgumentException");
            assertTrue(String.valueOf(e.getMessage()).contains("-42"), "names the weight: " + e.getMessage());
            assertEquals(0, w.backlog(), "nothing queued");
            assertEquals(0, w.waiting(), "nobody waiting");
            assertEquals(0, timer.pending(), "nothing scheduled");
            assertEquals(List.of(), batches, "nothing handed to the sink");
            assertEquals(List.of(), logged.stream().map(LogRecord::getMessage).toList(),
                    "decision 7: a fault that fails an item is not logged, the failed future is its report");
        });

        assertTrue(mode.save(w, "zero").succeeded(), "a weight of zero is a weight");
        assertEquals(List.of(List.of("zero")), batches);
    }
}
