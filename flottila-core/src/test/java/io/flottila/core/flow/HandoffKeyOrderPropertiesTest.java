package io.flottila.core.flow;

import io.flottila.api.Ack;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Pipeline;
import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.api.StepFactory;
import io.flottila.api.StepResources;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.core.record.Page;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.buffer.Buffer;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P2-16a (D-121 A1–A4, D-116), property: one started worker of a keyed flow, every save handed off through core's
 * {@link SequentialIntake} with the node's {@link Workers}, a random in-flight limit (1..3) and a random interleaving of saves — key
 * A, B or none — and runs let through. Whatever the interleaving: never two runs of one key at once; per key the runs enter in
 * commit order (the saves commit one after the other here); once every run is let through, every saved item has run once and its
 * row is gone, nothing parked — a hand-off refused (full, key busy) is found by a chain end or its key's holder, never left to a
 * tick: the clock never moves, so the poll interval never fires.
 *
 * <p>Every try builds its own world (jqwik: nothing lives on the instance; @BeforeEach does not run for a property). The class
 * name ends in {@code Test} so that Surefire runs it (the packet's {@code HandoffKeyOrderProperties}).
 */
class HandoffKeyOrderPropertiesTest {

    static final Path NO_FILES = Path.of("");
    static final String FLOW = "orders";

    sealed interface Move permits Save, Finish {}

    /** A save of a message with this key ({@code null}: none). */
    record Save(String key) implements Move {}

    /** One run held at the gate is let through and its thread joined. */
    record Finish(int pick) implements Move {}

    /** Per key, the order runs entered and every entry while another run of the key was active; wraps the rest of the pipeline. */
    static final class KeyProbe implements StepFactory {
        final List<String> entries = new CopyOnWriteArrayList<>();
        final List<String> overlaps = new CopyOnWriteArrayList<>();
        private final Map<String, String> active = new HashMap<>();   // guarded by this

        @Override public String type() { return "keys"; }

        @Override public String configSchema() { return "{}"; }

        @Override public Stage create(StepConfig step, StepResources resources) {
            return this::pass;
        }

        private Envelope pass(Envelope e, Pipeline rest) {
            String key = e.header("x-customer").orElse(null);
            synchronized (this) {
                if (key != null) {
                    String other = active.putIfAbsent(key, e.id());
                    if (other != null) overlaps.add(e.id() + " of key " + key + " entered while " + other + " ran");
                }
                entries.add(e.id());
            }
            try {
                return rest.apply(e);
            } finally {
                synchronized (this) {
                    if (key != null) active.remove(key, e.id());
                }
            }
        }
    }

    @Provide
    Arbitrary<List<Move>> moves() {
        Arbitrary<Move> save = Arbitraries.of("A", "B", "none").map(k -> new Save(k.equals("none") ? null : k));
        Arbitrary<Move> finish = Arbitraries.integers().between(0, 7).map(Finish::new);
        return Arbitraries.oneOf(save, finish).list().ofMinSize(1).ofMaxSize(16);
    }

    @Property(tries = 60)
    void handedOffRunsKeepKeyOrderAndEverySavedItemRuns(@ForAll @IntRange(min = 1, max = 3) int maxInFlight, @ForAll("moves") List<Move> moves) throws Exception {
        FakeRuntime rt = FakeRuntime.create();
        InMemoryMessageStore store = new InMemoryMessageStore();
        InMemoryWorkQueue rows = new InMemoryWorkQueue(rt.clock());
        RecordingWorkQueue queue = new RecordingWorkQueue(rows, rt.clock());
        InMemoryDlq dlq = new InMemoryDlq();
        RunGate gate = new RunGate();
        KeyProbe probe = new KeyProbe();
        Workers workers = new Workers();
        FlowBuilder b = new FlowBuilder(rt.runtime(), List.of(gate, probe), List.of(BuiltInStepsTest.HEADERS), Map.of())
                .recording(store, Meter.system()).queue(queue).dlq(dlq).workers(workers)
                .intake(new SequentialIntake(store, queue, workers, "w1"));
        List<Map<String, Object>> steps = List.of(
                Map.of("type", "extractKey", "name", "key", "language", "header", "key", "customer", "xpath", "x-customer"),
                Map.of("type", "keys", "name", "keys"), RunGate.STEP);
        Map<String, Object> doc = FlowWorkerTest.flow(maxInFlight, steps);
        doc.put("partitionBy", "customer");
        doc.put("onFailure", "park");
        FlowDefinition def = FlowDefinition.from(doc);
        Delivery delivery = b.delivery(def, NO_FILES);
        FlowWorker worker = b.worker(def, NO_FILES, "w1", new WorkerPolicy(Duration.ofSeconds(30), 5, Duration.ofMinutes(10)));
        Map<String, List<String>> committed = new LinkedHashMap<>();   // key → ids in save order
        List<String> saved = new ArrayList<>();
        List<RunGate.Run> finished = new ArrayList<>();
        try {
            worker.start();
            until(() -> queue.claims().size() == 1 && queue.claimsInFlight() == 0, () -> "the start claim; " + queue.claims());
            for (Move m : moves) {
                if (m instanceof Save s) {
                    Headers h = Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml");
                    if (s.key() != null) h = h.with("x-customer", s.key());
                    Envelope in = rt.ingress(FLOW, h, Buffer.buffer("<order/>"));
                    assertEquals(new Ack.Accepted(in.id()), delivery.apply(in).await(5, TimeUnit.SECONDS), "fixture: accepted");
                    saved.add(in.id());
                    committed.computeIfAbsent(String.valueOf(s.key()), k -> new ArrayList<>()).add(in.id());
                } else if (m instanceof Finish f) {
                    List<RunGate.Run> held = gate.runs().stream().filter(r -> !r.released()).toList();
                    if (held.isEmpty()) continue;
                    RunGate.Run run = held.get(f.pick() % held.size());
                    run.release();
                    run.joined();   // its chain end — a fast path's next run, a claim — happened on its thread
                    finished.add(run);
                }
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (rows.size() > 0 || worker.unsettled() > 0) {   // let every run through, one at a time, its chain end joined
                if (System.nanoTime() - deadline > 0) {
                    fail("not drained without a tick: rows " + rows.snapshot() + ", running " + worker.unsettled() + ", runs " + gate.runs()
                            + ", claims " + queue.claims() + ", leased enqueues " + queue.enqueueLeasedCalls());
                }
                Optional<RunGate.Run> next = gate.runs().stream().filter(r -> !r.released()).findFirst();
                if (next.isPresent()) {
                    next.get().release();
                    next.get().joined();
                } else {
                    Thread.onSpinWait();
                }
            }
        } finally {
            worker.stop();
            gate.open();
            queue.releaseHeld();
        }

        assertEquals(List.of(), probe.overlaps, "never two runs of one key at once");
        for (Map.Entry<String, List<String>> key : committed.entrySet()) {
            if (key.getKey().equals("null")) continue;
            List<String> ran = probe.entries.stream().filter(key.getValue()::contains).toList();
            assertEquals(key.getValue(), ran, "key " + key.getKey() + ": runs in commit order");
        }
        assertEquals(saved.size(), probe.entries.size(), "every saved item ran once: saved " + saved + ", ran " + probe.entries);
        assertEquals(java.util.Set.copyOf(saved), java.util.Set.copyOf(probe.entries), "every saved item ran");
        assertEquals(List.of(), dlq.list(Optional.empty(), Page.MAX_LIMIT).await().items(), "nothing parked");
    }

    static void until(java.util.function.BooleanSupplier condition, java.util.function.Supplier<String> what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) fail("not within 5 s: " + what.get());
            Thread.onSpinWait();
        }
    }
}
