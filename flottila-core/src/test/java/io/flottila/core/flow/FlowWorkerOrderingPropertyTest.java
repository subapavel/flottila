package io.flottila.core.flow;

import io.flottila.api.Envelope;
import io.flottila.api.Pipeline;
import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.api.StepFactory;
import io.flottila.api.StepResources;
import io.flottila.core.dlq.Parked;
import io.flottila.core.record.Page;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.constraints.IntRange;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P2-01 (D-075), property: two workers over one queue, each with a random {@code maxChain} (0..3), random messages — key A, B or
 * none; outcome pass, park, crash on the first run only — and a random interleaving of polls, runs let through and clock moves of
 * at most half a lease (a running item renews every third). Per key, the runs enter in seq order (a rerun of the same item
 * included: never a later item of the key before an earlier one is done) and never overlap, on one worker or across both; once
 * drained every item is settled — the queue empty, every pass or crash-once message sent exactly once, every park parked
 * exactly once and never sent — and no settle was stale.
 *
 * <p>Every try builds its own {@link FlowWorkerFastPathTest.Line} (jqwik: nothing lives on the instance). A {@code keys} step
 * ({@link KeyProbe}) runs first and wraps the rest of the pipeline: a run is active for its key from its entry until everything
 * after it returned.
 */
class FlowWorkerOrderingPropertyTest {

    static final Duration LEASE = FlowWorkerFastPathTest.LEASE;
    static final Map<String, Object> PROBE = Map.of("type", "keys", "name", "keys");

    /** One message: its key ({@code null}: none) and its outcome. */
    record Msg(String key, String outcome) {}

    sealed interface Move permits Advance, PollBy, Finish {}

    /** The clock moves {@code sixths} × lease / 6 — at most half a lease. */
    record Advance(int sixths) implements Move {}

    record PollBy(int worker) implements Move {}

    /** One run held at the gate is let through and its thread joined. */
    record Finish(int pick) implements Move {}

    /** Records, per key, the order runs entered and every entry while another run of the key was active. */
    static final class KeyProbe implements StepFactory {
        final FlowWorkerFastPathTest.TakeQueue queue;
        final List<String> entries = new CopyOnWriteArrayList<>();      // message ids in entry order
        final List<String> overlaps = new CopyOnWriteArrayList<>();
        private final Map<String, String> active = new HashMap<>();     // guarded by this: key → message id running

        KeyProbe(FlowWorkerFastPathTest.TakeQueue queue) {
            this.queue = queue;
        }

        @Override public String type() { return "keys"; }

        @Override public String configSchema() { return "{}"; }

        @Override public Stage create(StepConfig step, StepResources resources) {
            return this::pass;
        }

        private Envelope pass(Envelope e, Pipeline rest) {
            String key = queue.keyOf(e.id());
            synchronized (this) {
                entries.add(e.id());
                if (key != null) {
                    String other = active.putIfAbsent(key, e.id());
                    if (other != null) overlaps.add(e.id() + " entered while " + other + " of key " + key + " was active");
                }
            }
            try {
                return rest.apply(e);
            } finally {
                if (key != null) {
                    synchronized (this) {
                        active.remove(key, e.id());
                    }
                }
            }
        }
    }

    @Provide
    Arbitrary<List<Msg>> messages() {
        Arbitrary<String> key = Arbitraries.frequency(Tuple.of(2, "A"), Tuple.of(2, "B"), Tuple.of(1, "-"));
        Arbitrary<String> outcome = Arbitraries.frequency(Tuple.of(6, "pass"), Tuple.of(1, "park"), Tuple.of(1, "crash-once"));
        return Combinators.combine(key, outcome).as((k, o) -> new Msg(k.equals("-") ? null : k, o)).list().ofMinSize(1).ofMaxSize(8);
    }

    @Provide
    Arbitrary<List<Move>> moves() {
        Arbitrary<Move> advance = Arbitraries.integers().between(1, 3).map(Advance::new);
        Arbitrary<Move> poll = Arbitraries.integers().between(0, 1).map(PollBy::new);
        Arbitrary<Move> finish = Arbitraries.integers().between(0, 7).map(Finish::new);
        return Arbitraries.frequencyOf(Tuple.of(2, advance), Tuple.of(3, poll), Tuple.of(4, finish)).list().ofMinSize(1).ofMaxSize(30);
    }

    @Property(tries = 150)
    void perKeyRunsInSeqOrderNeverOverlappingAndEveryItemSettled(@ForAll("messages") List<Msg> messages, @ForAll("moves") List<Move> moves,
                                                                 @ForAll @IntRange(min = 0, max = 3) int chain1, @ForAll @IntRange(min = 0, max = 3) int chain2,
                                                                 @ForAll @IntRange(min = 1, max = 3) int maxInFlight) throws Exception {
        FlowWorkerFastPathTest.Line line = new FlowWorkerFastPathTest.Line();
        KeyProbe probe = new KeyProbe(line.queue);
        line.extra.add(probe);
        try {
            FlowDefinition def = FlowWorkerFastPathTest.Line.def(maxInFlight, List.of(PROBE, RunGate.STEP, FlowWorkerFastPathTest.OUTCOME, FlowWorkerFastPathTest.TO_PARTNER));
            FlowBuilder b = line.builder();
            Delivery delivery = b.delivery(def, FlowWorkerFastPathTest.NO_FILES);
            List<String> ids = new ArrayList<>();
            for (Msg m : messages) ids.add(line.accept(delivery, m.key(), m.outcome()).id());
            List<FlowWorker> workers = List.of(
                    line.worker(b, def, "w1", FlowWorkerFastPathTest.policy(1_000, chain1)),
                    line.worker(b, def, "w2", FlowWorkerFastPathTest.policy(1_000, chain2)));

            for (Move m : moves) {
                switch (m) {
                    case Advance a -> line.rt.clock().advance(LEASE.multipliedBy(a.sixths()).dividedBy(6));
                    case PollBy p -> workers.get(p.worker()).poll();
                    case Finish f -> {
                        List<RunGate.Run> held = line.gate.held();
                        if (!held.isEmpty()) {
                            RunGate.Run run = held.get(f.pick() % held.size());
                            run.release();
                            run.joined();
                        }
                    }
                }
                line.settle();
                assertEquals(List.of(), probe.overlaps, "two runs of one key at once after " + m + "; " + line.queue.events());
            }

            line.gate.open();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (line.rows.size() > 0) {   // a crashed item waits for its lease: a third of a lease at a time, so a running one renews
                if (System.nanoTime() - deadline > 0) fail("not drained within 10 s: " + line.rows.snapshot() + "; " + line.queue.events());
                for (FlowWorker w : workers) w.poll().otherwiseEmpty().await(5, SECONDS);
                line.gate.joinPassed();
                if (line.rows.size() > 0) line.rt.clock().advance(LEASE.dividedBy(3));
            }
            line.gate.joinPassed();

            assertEquals(List.of(), probe.overlaps, "two runs of one key at once; " + line.queue.events());
            for (String key : List.of("A", "B")) {
                List<String> ofKey = new ArrayList<>();
                for (int i = 0; i < messages.size(); i++) if (key.equals(messages.get(i).key())) ofKey.add(ids.get(i));
                int last = -1;
                for (String entered : probe.entries) {
                    int at = ofKey.indexOf(entered);
                    if (at < 0) continue;
                    assertTrue(at >= last, "key " + key + ": " + entered + " (#" + (at + 1) + ") entered after #" + (last + 1) + " of " + ofKey
                            + "; entries " + probe.entries + "; " + line.queue.events());
                    last = at;
                }
            }
            assertEquals(0, line.rows.size(), "every item settled: " + line.rows.snapshot());
            List<String> sent = line.sentIds();
            List<String> parked = line.dlq.list(Optional.empty(), Page.MAX_LIMIT).await().items().stream().map(Parked::messageId).toList();
            for (int i = 0; i < messages.size(); i++) {
                String id = ids.get(i);
                boolean parks = messages.get(i).outcome().equals("park");
                assertEquals(parks ? 0 : 1, sent.stream().filter(id::equals).count(), id + " (" + messages.get(i) + ") sent: " + sent);
                assertEquals(parks ? 1 : 0, parked.stream().filter(id::equals).count(), id + " (" + messages.get(i) + ") parked: " + parked);
            }
            for (FlowWorker w : workers) assertEquals(0, w.staleSettles(), "no settle was stale: " + line.queue.events());
        } finally {
            line.close();
        }
    }
}
