package io.flottila.core.batch;

import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CORE-6b (implementer): the weighed writer refuses a weight below zero at the save and queues nothing for it. */
class BatchingWriterWeightTest {

    @Test
    void aNegativeWeightIsRefusedAndNothingQueued() {
        List<List<String>> batches = new ArrayList<>();
        BatchSink<String> sink = batch -> {
            batches.add(List.copyOf(batch));
            return Future.succeededFuture();
        };
        BatchingWriter<String> w = new BatchingWriter<>(sink, new FakeTimer(FakeClock.at(Instant.EPOCH)),
                new BatchingWriter.Options(10, Duration.ZERO, 1, 100), item -> item.startsWith("-") ? -1L : 0L, 10);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> w.save("-minus"));
        assertTrue(e.getMessage().contains("-1"), "names the weight: " + e.getMessage());
        assertEquals(0, w.backlog(), "nothing queued");
        assertEquals(List.of(), batches, "nothing handed to the sink");

        assertTrue(w.save("zero").succeeded(), "a weight of zero is a weight");
        assertEquals(List.of(List.of("zero")), batches);
    }
}
