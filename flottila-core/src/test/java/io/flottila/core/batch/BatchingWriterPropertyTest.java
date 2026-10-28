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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CLAUDE.md: property test for the batcher — no loss, no duplicate under any demand pattern.
 * A pattern interleaves saves, commit completions (ok or failed) and clock ticks; afterwards every
 * open commit is completed. Invariants: every accepted save completes exactly once with the
 * outcome of its own batch; every accepted item is in exactly one batch, in save order; rejected
 * items are in none; no batch exceeds batchSize; commits in flight never exceed the concurrency. FIX-66: the same with
 * {@code saveWaiting} in the mix.
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

    // --- FIX-66 (F-13, D-071): saveWaiting beside save

    enum Move { SAVE, SAVE_WAITING, COMPLETE_OK, COMPLETE_FAIL, TICK_1MS, TICK_MAX_WAIT }

    @Provide
    Arbitrary<List<Move>> moves() {
        return Arbitraries.of(Move.class).list().ofMinSize(1).ofMaxSize(80);
    }

    /**
     * Any mix of save, saveWaiting, commit success or failure and clock advance (1 ms steps and whole maxWaits): each item
     * reaches the sink at most once; a save never waits and is refused whenever anyone waits (it never overtakes a waiter);
     * a waiting save that finds a waiter queues behind it; a waiter fails Overloaded only once maxWait has passed since its
     * call and is never handed on after; waiters reach the sink in arrival order, saves in call order; after every move
     * {@code waiting() > 0 ⇒ backlog() == maxBacklog}. Drained, every item is in the sink exactly once or failed Overloaded,
     * and every handed-on item carries its own batch's outcome.
     */
    @Property(tries = 300)
    void noLossNoDuplicateNoOvertakeWithWaitingSaves(@ForAll("moves") List<Move> moves,
                                                     @ForAll @IntRange(min = 1, max = 4) int batchSize,
                                                     @ForAll @IntRange(min = 1, max = 3) int concurrency,
                                                     @ForAll @IntRange(min = 1, max = 3) int backlogBatches,
                                                     @ForAll @IntRange(min = 0, max = 5) int windowMs,
                                                     @ForAll @IntRange(min = 1, max = 8) int maxWaitMs) {
        FakeClock clock = FakeClock.at(Instant.EPOCH);
        FakeTimer timer = new FakeTimer(clock);
        FakeSink sink = new FakeSink();
        sink.manual = true;
        int maxBacklog = batchSize * backlogBatches;
        BatchingWriter<String> w = new BatchingWriter<>(sink, timer,
                new BatchingWriter.Options(batchSize, Duration.ofMillis(windowMs), concurrency, maxBacklog, Duration.ofMillis(maxWaitMs)));

        Map<String, Future<Void>> futures = new HashMap<>();
        Map<String, Instant> calledAt = new HashMap<>();
        List<String> waiterOrder = new ArrayList<>();       // every saveWaiting, in call order
        List<String> saveOrder = new ArrayList<>();         // every save accepted, in call order
        Set<String> refused = new HashSet<>();               // failed Overloaded: never to be handed on
        List<Boolean> batchOutcomes = new ArrayList<>();
        int n = 0;
        for (Move m : moves) {
            int waitingBefore = w.waiting();
            int backlogBefore = w.backlog();
            switch (m) {
                case SAVE -> {
                    String item = "s" + (n++);
                    Future<Void> f = w.save(item);
                    futures.put(item, f);
                    calledAt.put(item, clock.instant());
                    if (waitingBefore > 0) {
                        assertTrue(f.failed(), item + ": a save while " + waitingBefore + " wait must fail at once, never overtake them");
                    }
                    if (f.failed()) {
                        assertInstanceOf(OverloadedException.class, f.cause(), item + ": " + f.cause());
                        assertTrue(waitingBefore > 0 || backlogBefore == maxBacklog, item + " refused with room and nobody waiting");
                        refused.add(item);
                    } else {
                        saveOrder.add(item);
                    }
                    assertEquals(waitingBefore, w.waiting(), "a save never waits");
                }
                case SAVE_WAITING -> {
                    String item = "w" + (n++);
                    Future<Void> f = w.saveWaiting(item);
                    futures.put(item, f);
                    calledAt.put(item, clock.instant());
                    waiterOrder.add(item);
                    assertFalse(f.failed() && f.cause() instanceof OverloadedException, item + " refused at its call: " + f.cause());
                    if (waitingBefore > 0) {
                        assertEquals(waitingBefore + 1, w.waiting(), item + ": queues behind the " + waitingBefore + " already waiting");
                    } else if (backlogBefore < maxBacklog) {
                        assertEquals(0, w.waiting(), item + ": with room and nobody waiting it enters at once");
                    } else {
                        assertEquals(1, w.waiting(), item + ": a full backlog makes it wait");
                    }
                }
                case COMPLETE_OK -> { if (sink.open() > 0) { sink.gate().complete(); batchOutcomes.add(true); } }
                case COMPLETE_FAIL -> { if (sink.open() > 0) { sink.gate().fail(new RuntimeException("failed batch")); batchOutcomes.add(false); } }
                case TICK_1MS -> clock.advance(Duration.ofMillis(1));
                case TICK_MAX_WAIT -> clock.advance(Duration.ofMillis(maxWaitMs));
            }
            checkAfterMove(w, sink, futures, calledAt, refused, clock.instant(), maxWaitMs, maxBacklog, concurrency);
        }
        // drain: complete what is open and let the clock run until nothing waits, queues or flies
        for (int guard = 0; guard < 10_000 && (w.backlog() > 0 || sink.open() > 0 || w.waiting() > 0); guard++) {
            clock.advance(Duration.ofMillis(1));
            if (sink.open() > 0) { sink.gate().complete(); batchOutcomes.add(true); }
            checkAfterMove(w, sink, futures, calledAt, refused, clock.instant(), maxWaitMs, maxBacklog, concurrency);
        }
        assertEquals(0, w.waiting(), "nobody waits after the drain");
        assertEquals(0, w.backlog());
        assertEquals(0, w.inFlight());

        List<String> flushed = sink.batches.stream().flatMap(List::stream).toList();
        assertEquals(flushed.size(), new HashSet<>(flushed).size(), "no item reaches the sink twice: " + flushed);
        assertEquals(saveOrder, flushed.stream().filter(i -> i.startsWith("s")).toList(), "accepted saves reach the sink once each, in call order");
        List<String> waitersHandedOn = waiterOrder.stream().filter(i -> !refused.contains(i)).toList();
        assertEquals(waitersHandedOn, flushed.stream().filter(i -> i.startsWith("w")).toList(),
                "every waiter not failed at its deadline reaches the sink once, in arrival order");
        for (String item : futures.keySet()) {
            assertTrue(futures.get(item).isComplete(), item + " completed");
            assertTrue(refused.contains(item) != flushed.contains(item), item + ": handed on exactly when it was not refused");
        }
        assertEquals(sink.batches.size(), batchOutcomes.size(), "every batch was completed exactly once");
        for (int i = 0; i < sink.batches.size(); i++) {
            for (String item : sink.batches.get(i)) {
                assertEquals(batchOutcomes.get(i), futures.get(item).succeeded(), item + " carries the outcome of its own batch #" + i);
            }
        }
    }

    /** What holds after every move: limits, the waiting invariant, and every Overloaded failure seen so far. */
    private static void checkAfterMove(BatchingWriter<String> w, FakeSink sink, Map<String, Future<Void>> futures, Map<String, Instant> calledAt,
                                       Set<String> refused, Instant now, int maxWaitMs, int maxBacklog, int concurrency) {
        assertTrue(w.inFlight() <= concurrency, "in flight " + w.inFlight() + " > " + concurrency);
        assertTrue(sink.open() <= concurrency, "the sink sees " + sink.open() + " open commits > " + concurrency);
        assertTrue(w.backlog() <= maxBacklog, "backlog " + w.backlog() + " > " + maxBacklog);
        assertTrue(w.waiting() == 0 || w.backlog() == maxBacklog, "waiting " + w.waiting() + " with backlog " + w.backlog() + " < " + maxBacklog);
        List<String> flushed = sink.batches.stream().flatMap(List::stream).toList();
        for (Map.Entry<String, Future<Void>> e : futures.entrySet()) {
            String item = e.getKey();
            Future<Void> f = e.getValue();
            if (!(f.failed() && f.cause() instanceof OverloadedException)) continue;
            assertFalse(flushed.contains(item), item + " failed Overloaded yet was handed to the sink");
            if (item.startsWith("w") && refused.add(item)) {
                Duration waited = Duration.between(calledAt.get(item), now);
                assertTrue(waited.compareTo(Duration.ofMillis(maxWaitMs)) >= 0, item + " failed after " + waited + " < maxWait " + maxWaitMs + " ms");
            }
        }
    }
}
