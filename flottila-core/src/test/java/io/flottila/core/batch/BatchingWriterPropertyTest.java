package io.flottila.core.batch;

import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Future;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CLAUDE.md: property test for the batcher — no loss, no duplicate under any demand pattern.
 * A pattern interleaves saves, commit completions (ok or failed) and clock ticks; afterwards every
 * open commit is completed. Invariants: every accepted save completes exactly once with the
 * outcome of its own batch; every accepted item is in exactly one batch, in save order; rejected
 * items are in none; no batch exceeds batchSize; commits in flight never exceed the concurrency.
 */
class BatchingWriterPropertyTest {

    enum Action { SAVE, COMPLETE_OK, COMPLETE_FAIL, TICK }

    @Provide
    Arbitrary<List<Action>> patterns() {
        return Arbitraries.of(Action.class).list().ofMinSize(1).ofMaxSize(60);
    }

    @Property(tries = 300)
    void noLossNoDuplicateUnderAnyDemandPattern(@ForAll("patterns") List<Action> actions,
                                                @ForAll @IntRange(min = 1, max = 5) int batchSize,
                                                @ForAll @IntRange(min = 1, max = 3) int concurrency,
                                                @ForAll @IntRange(min = 1, max = 4) int backlogBatches,
                                                @ForAll @IntRange(min = 0, max = 20) int windowMs) {
        FakeClock clock = FakeClock.at(Instant.EPOCH);
        FakeTimer timer = new FakeTimer(clock);
        FakeSink sink = new FakeSink();
        sink.manual = true;
        int maxBacklog = batchSize * backlogBatches;
        BatchingWriter<String> w = new BatchingWriter<>(sink, timer,
                new BatchingWriter.Options(batchSize, Duration.ofMillis(windowMs), concurrency, maxBacklog));

        Map<String, Future<Void>> accepted = new HashMap<>();
        List<String> acceptedOrder = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        List<Boolean> batchOutcomes = new ArrayList<>();   // index = batch number in sink.batches
        int maxInFlight = 0;
        int saves = 0;
        for (Action a : actions) {
            switch (a) {
                case SAVE -> {
                    String item = "i" + (saves++);
                    Future<Void> f = w.save(item);
                    if (f.failed() && f.cause() instanceof OverloadedException) rejected.add(item);
                    else { accepted.put(item, f); acceptedOrder.add(item); }
                }
                case COMPLETE_OK -> { if (sink.open() > 0) { sink.gate().complete(); batchOutcomes.add(true); } }
                case COMPLETE_FAIL -> { if (sink.open() > 0) { sink.gate().fail(new RuntimeException("failed batch")); batchOutcomes.add(false); } }
                case TICK -> clock.advance(Duration.ofMillis(Math.max(windowMs, 1)));
            }
            maxInFlight = Math.max(maxInFlight, w.inFlight());
            assertTrue(w.inFlight() <= concurrency, "in flight " + w.inFlight() + " > " + concurrency);
            assertTrue(sink.open() <= concurrency, "the sink sees " + sink.open() + " open commits > " + concurrency);
            assertTrue(w.backlog() <= maxBacklog, "backlog " + w.backlog() + " > " + maxBacklog);
        }
        // drain: tick the window and complete everything that is still open, until nothing moves
        for (int guard = 0; guard < 10_000 && (w.backlog() > 0 || sink.open() > 0); guard++) {
            clock.advance(Duration.ofMillis(Math.max(windowMs, 1)));
            if (sink.open() > 0) { sink.gate().complete(); batchOutcomes.add(true); }
        }
        assertEquals(0, w.backlog(), "every accepted item was flushed");
        assertEquals(0, w.inFlight());

        List<String> flushed = sink.batches.stream().flatMap(List::stream).toList();
        assertEquals(acceptedOrder, flushed, "accepted items are flushed exactly once, in save order");
        for (List<String> b : sink.batches) assertTrue(b.size() <= batchSize, "batch of " + b.size() + " > " + batchSize);
        for (String r : rejected) assertTrue(!flushed.contains(r), "rejected item " + r + " must not be flushed");
        assertEquals(sink.batches.size(), batchOutcomes.size(), "every batch was completed exactly once");
        for (int i = 0; i < sink.batches.size(); i++) {
            boolean ok = batchOutcomes.get(i);
            for (String item : sink.batches.get(i)) {
                Future<Void> f = accepted.get(item);
                assertTrue(f.isComplete(), item + " completed");
                assertEquals(ok, f.succeeded(), item + " carries the outcome of its own batch #" + i);
            }
        }
        assertTrue(maxInFlight <= concurrency);
    }
}
