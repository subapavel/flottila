package io.flottila.core.flow;

import io.flottila.api.Ack;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Pipeline;
import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.api.StepFactory;
import io.flottila.api.StepResources;
import io.flottila.core.dlq.Dlq;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.dlq.Parked;
import io.flottila.core.dlq.ParkedException;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.core.record.Page;
import io.flottila.core.record.Snapshot;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import jdk.jfr.Configuration;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;
import jdk.jfr.ValueDescriptor;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * FIX-108 (D-094): only when the builder is asked to, {@code stepEvents(true)} — which the node does only when it samples (owner
 * decision 2026-11-03: sampling off costs nothing) —, every step of a flow it builds is wrapped, outermost, in the JFR event
 * {@code io.flottila.Step} (fields {@code flow}, {@code step}, {@code name}; category Flottila; no stack trace; no message id),
 * disabled unless a recording enables it; by default, or after {@code stepEvents(false)}, no step is wrapped. The event begins when
 * the step's stage is entered and is committed when it returns or throws, on the message's thread: it spans the rest of the pipeline
 * (the events of one message nest) and the step's retry, numbering, recording and parking wrappers. The start stage (ingress, replay)
 * is not marked.
 *
 * <p>Observed with {@link Recording} to a file and {@link RecordingFile}, deterministic on any OS. A test event
 * {@code io.flottila.test.Probe}, committed by the steps' bodies, the meter and the DLQ on the message's thread, places what ran
 * inside or outside a step's event (JFR timestamps of one thread are ordered). Each case's flows have ids of their own
 * ({@code step-events-*}): events of another test's flows are never counted.
 */
class FlowBuilderStepEventTest {

    static final String STEP_EVENT = "io.flottila.Step";
    static final String PROBE_EVENT = "io.flottila.test.Probe";
    static final Path NO_FILES = Path.of("");
    static final Duration BOUND = Duration.ofSeconds(30);
    /** The fields every JFR event has; the step event's own are the rest. */
    static final Set<String> STANDARD_FIELDS = Set.of("startTime", "duration", "eventThread", "stackTrace");

    @TempDir Path tmp;
    final FakeRuntime rt = FakeRuntime.create();
    final AtomicInteger files = new AtomicInteger();

    /** A witness: what committed it ran on its thread between the event's start and end. */
    @Name(PROBE_EVENT)
    @StackTrace(false)
    static class Probe extends Event {
        String label;
    }

    static void probe(String label) {
        Probe p = new Probe();
        p.label = label;
        p.commit();
    }

    /** {@code mark}: a probe {@code body <name>} spanning the step's own work, then a header. */
    static final StepFactory MARK = factory("mark", s -> Stage.of(e -> {
        Probe p = new Probe();
        p.label = "body " + s.name();
        p.begin();
        try {
            return e.withHeader("seen-" + s.name(), "yes");
        } finally {
            p.commit();
        }
    }));

    /** {@code boom}: a probe {@code body <name>}, then an {@link IllegalStateException}. */
    static final StepFactory BOOM = factory("boom", s -> Stage.of(e -> {
        Probe p = new Probe();
        p.label = "body " + s.name();
        p.begin();
        try {
            throw new IllegalStateException("boom in " + s.name());
        } finally {
            p.commit();
        }
    }));

    static StepFactory factory(String type, Function<StepConfig, Stage> make) {
        return new StepFactory() {
            @Override public String type() { return type; }
            @Override public String configSchema() { return "{}"; }
            @Override public Stage create(StepConfig step, StepResources res) { return make.apply(step); }
        };
    }

    static Map<String, Object> step(String type, String name) {
        return Map.of("type", type, "name", name);
    }

    static FlowDefinition flow(String id, String mode, List<Map<String, Object>> steps) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("schemaVersion", 1);
        doc.put("kind", "flow");
        doc.put("id", id);
        doc.put("version", "1.0.0");
        doc.put("from", Map.of("endpoint", "in"));
        doc.put("delivery", Map.of("mode", mode));
        doc.put("limits", Map.of("maxBodyBytes", 4096, "maxInFlight", 4));
        doc.put("retry", Map.of("attempts", 2, "initial", "10ms", "max", "20ms"));
        doc.put("steps", steps);
        return FlowDefinition.from(doc);
    }

    Envelope ingress(String flow) {
        return rt.ingress(flow, Headers.empty(), Buffer.buffer("<x/>"));
    }

    /** What ran on a virtual thread of its own: the thread's id and what the call returned or threw. */
    record Ran(long thread, Object outcome) {}

    /** {@code call} on a fresh virtual thread, as a consumer runs a message; waits for it, at most {@link #BOUND}. */
    static Ran onVirtualThread(Supplier<Object> call) throws InterruptedException {
        AtomicReference<Object> outcome = new AtomicReference<>();
        Thread t = Thread.ofVirtual().name("step-events-message").start(() -> {
            try {
                outcome.set(call.get());
            } catch (Throwable x) {
                outcome.set(x);
            }
        });
        if (!t.join(BOUND)) fail("the message's thread did not end within " + BOUND + ", at " + Arrays.toString(t.getStackTrace()));
        return new Ran(t.threadId(), outcome.get());
    }

    @FunctionalInterface
    interface Body {
        void run() throws Exception;
    }

    /** The events a recording saw while {@code body} ran: one that enables the step and probe events, or one of {@code configuration}. */
    List<RecordedEvent> recorded(Optional<Configuration> configuration, Body body) throws Exception {
        Path file = tmp.resolve("recording-" + files.incrementAndGet() + ".jfr");
        try (Recording r = configuration.map(Recording::new).orElseGet(Recording::new)) {
            if (configuration.isEmpty()) {
                r.enable(STEP_EVENT);
                r.enable(PROBE_EVENT);
            }
            r.start();
            body.run();
            r.stop();
            r.dump(file);
        }
        return RecordingFile.readAllEvents(file);
    }

    List<RecordedEvent> recorded(Body body) throws Exception {
        return recorded(Optional.empty(), body);
    }

    /** The step events of {@code flow}, by start. */
    static List<RecordedEvent> stepEvents(List<RecordedEvent> all, String flow) {
        return all.stream().filter(e -> e.getEventType().getName().equals(STEP_EVENT) && flow.equals(e.getString("flow")))
                .sorted(Comparator.comparing(RecordedEvent::getStartTime)).toList();
    }

    /** The step events of {@code flow} on {@code thread}, by start. */
    static List<RecordedEvent> stepEvents(List<RecordedEvent> all, String flow, long thread) {
        return stepEvents(all, flow).stream().filter(e -> e.getThread().getJavaThreadId() == thread).toList();
    }

    /** The probes on {@code thread} whose label starts with {@code prefix}, by start. */
    static List<RecordedEvent> probes(List<RecordedEvent> all, String prefix, long thread) {
        return all.stream().filter(e -> e.getEventType().getName().equals(PROBE_EVENT) && e.getString("label").startsWith(prefix)
                && e.getThread().getJavaThreadId() == thread).sorted(Comparator.comparing(RecordedEvent::getStartTime)).toList();
    }

    /** {@code (step, name)} of each event, in order. */
    static List<String> stepsOf(List<RecordedEvent> events) {
        return events.stream().map(e -> e.getInt("step") + " " + e.getString("name")).toList();
    }

    static String shown(RecordedEvent e) {
        String what = e.getEventType().getName().equals(STEP_EVENT) ? "step " + e.getInt("step") + " " + e.getString("name") : "probe " + e.getString("label");
        return what + " [" + e.getStartTime() + ", " + e.getEndTime() + "]";
    }

    /** {@code inner} ran within {@code outer}: started no earlier, ended no later. */
    static void assertWithin(RecordedEvent outer, RecordedEvent inner, String what) {
        assertTrue(!inner.getStartTime().isBefore(outer.getStartTime()) && !inner.getEndTime().isAfter(outer.getEndTime()),
                what + ": " + shown(inner) + " within " + shown(outer));
    }

    static boolean notAfter(Instant a, Instant b) {
        return !a.isAfter(b);
    }

    @Test
    void everyStepEmitsOneEventOnTheMessagesVirtualThread() throws Exception {
        String id = "step-events-every";
        Pipeline p = new FlowBuilder(rt.runtime(), List.of(MARK), List.of(), Map.of()).stepEvents(true)
                .build(flow(id, "sync", List.of(step("mark", "a"), step("mark", "b"), step("mark", "c"))), NO_FILES);
        List<Ran> ran = new ArrayList<>();
        List<RecordedEvent> all = recorded(() -> {
            ran.add(onVirtualThread(() -> p.apply(ingress(id))));
            ran.add(onVirtualThread(() -> p.apply(ingress(id))));
        });
        for (Ran r : ran) assertInstanceOf(Envelope.class, r.outcome(), "fixture: the message went through");

        List<RecordedEvent> events = stepEvents(all, id);
        assertEquals(6, events.size(), "three steps, two messages: one event per step per message, " + events.stream().map(FlowBuilderStepEventTest::shown).toList());
        for (Ran r : ran) {
            List<RecordedEvent> mine = stepEvents(all, id, r.thread());
            assertEquals(List.of("1 a", "2 b", "3 c"), stepsOf(mine), "the message's thread carries its three step events, numbered and named");
            for (RecordedEvent e : mine) {
                assertTrue(e.getThread().isVirtual(), "on the message's virtual thread: " + shown(e));
                assertNull(e.getStackTrace(), "no stack trace is recorded: " + shown(e));
            }
        }
        assertEquals(ran.stream().map(Ran::thread).collect(Collectors.toSet()),
                events.stream().map(e -> e.getThread().getJavaThreadId()).collect(Collectors.toSet()), "no step event on another thread");

        EventType type = events.get(0).getEventType();
        Map<String, String> fields = type.getFields().stream().filter(f -> !STANDARD_FIELDS.contains(f.getName()))
                .collect(Collectors.toMap(ValueDescriptor::getName, ValueDescriptor::getTypeName));
        assertEquals(Map.of("flow", "java.lang.String", "step", "int", "name", "java.lang.String"), fields,
                "the event carries flow, step and name and nothing else — no message id");
        assertEquals(List.of("Flottila"), type.getCategoryNames());
    }

    /**
     * A step's stage hands its result to the rest of the pipeline, so the events of one message nest: step 1's spans step 2's, which
     * spans step 3's. A step that throws still commits its event, and so do the steps the failure passes through.
     */
    @Test
    void stepEventsNestAndAFailedStepEmits() throws Exception {
        String id = "step-events-nest";
        Pipeline p = new FlowBuilder(rt.runtime(), List.of(MARK, BOOM), List.of(), Map.of()).stepEvents(true)
                .build(flow(id, "sync", List.of(step("mark", "outer"), step("mark", "middle"), step("boom", "fails"))), NO_FILES);
        AtomicReference<Ran> ran = new AtomicReference<>();
        List<RecordedEvent> all = recorded(() -> ran.set(onVirtualThread(() -> p.apply(ingress(id)))));
        IllegalStateException failure = assertInstanceOf(IllegalStateException.class, ran.get().outcome(), "fixture: step 3 threw");
        assertTrue(failure.getMessage().contains("boom in fails"), "fixture: " + failure);

        long thread = ran.get().thread();
        List<RecordedEvent> events = stepEvents(all, id, thread);
        assertEquals(List.of("1 outer", "2 middle", "3 fails"), stepsOf(events), "every step emitted, the failed one too");
        assertEquals(3, stepEvents(all, id).size(), "and nothing else of the flow");
        RecordedEvent e1 = events.get(0);
        RecordedEvent e2 = events.get(1);
        RecordedEvent e3 = events.get(2);
        assertWithin(e1, e2, "step 1's event spans step 2's");
        assertWithin(e2, e3, "step 2's event spans step 3's");

        List<RecordedEvent> bodies = probes(all, "body ", thread);
        assertEquals(List.of("body outer", "body middle", "body fails"), bodies.stream().map(e -> e.getString("label")).toList(), "fixture: each step's body ran once");
        for (int i = 0; i < 3; i++) assertWithin(events.get(i), bodies.get(i), "step " + (i + 1) + "'s own work lies within its event");
        assertTrue(notAfter(bodies.get(0).getEndTime(), e2.getStartTime()), "step 1's work is done before step 2 begins: " + shown(bodies.get(0)) + ", " + shown(e2));
        assertTrue(notAfter(bodies.get(1).getEndTime(), e3.getStartTime()), "step 2's work is done before step 3 begins: " + shown(bodies.get(1)) + ", " + shown(e3));
    }

    /**
     * A builder configured as a node's is (store, DLQ, queue) with {@code stepEvents(true)} marks the steps of the sync delivery and of
     * the at-least-once worker, and a recording that enables the event sees them — no sampler runs anywhere in this JVM.
     */
    @Test
    void stepEventsOnMarksTheSyncDeliveryAndTheWorkersSteps() throws Exception {
        InMemoryWorkQueue queue = new InMemoryWorkQueue(rt.clock());
        FlowBuilder b = new FlowBuilder(rt.runtime(), List.of(MARK), List.of(), Map.of()).stepEvents(true)
                .recording(new InMemoryMessageStore(), () -> Snapshot.CPU_UNKNOWN).dlq(new InMemoryDlq()).queue(queue);
        String syncId = "step-events-sync";
        String queuedId = "step-events-worker";
        Delivery sync = b.delivery(flow(syncId, "sync", List.of(step("mark", "a"), step("mark", "b"))), NO_FILES);
        FlowDefinition atLeastOnce = flow(queuedId, "at-least-once", List.of(step("mark", "a"), step("mark", "b")));
        Delivery accepting = b.delivery(atLeastOnce, NO_FILES);
        FlowWorker worker = b.worker(atLeastOnce, NO_FILES, "w1", new WorkerPolicy(Duration.ofSeconds(30), 3, Duration.ofSeconds(1)));
        AtomicReference<Ran> synced = new AtomicReference<>();
        List<RecordedEvent> all;
        try {
            all = recorded(() -> {
                synced.set(onVirtualThread(() -> sync.apply(ingress(syncId)).await()));
                Envelope queued = ingress(queuedId);
                assertEquals(new Ack.Accepted(queued.id()), accepting.apply(queued).await(10, TimeUnit.SECONDS), "fixture: saved and queued");
                assertEquals(1, worker.poll().await(10, TimeUnit.SECONDS), "fixture: the worker claimed the item");
                long deadline = System.nanoTime() + BOUND.toNanos();
                while (queue.size() > 0) {
                    if (System.nanoTime() - deadline > 0) fail("fixture: the worker's run did not complete within " + BOUND + ": " + queue.snapshot());
                    Thread.onSpinWait();
                }
            });
        } finally {
            worker.stop();
        }
        assertInstanceOf(Ack.Reply.class, synced.get().outcome(), "fixture: the sync delivery answered");

        assertEquals(List.of("1 a", "2 b"), stepsOf(stepEvents(all, syncId, synced.get().thread())), "the sync delivery's steps, on the caller's thread");
        List<RecordedEvent> ranByWorker = stepEvents(all, queuedId);
        assertEquals(List.of("1 a", "2 b"), stepsOf(ranByWorker), "the worker's steps");
        assertEquals(1, ranByWorker.stream().map(e -> e.getThread().getJavaThreadId()).distinct().count(), "on one thread, the item's run");
        assertTrue(ranByWorker.get(0).getThread().isVirtual(), "a virtual thread: " + ranByWorker.get(0).getThread().getJavaName());
    }

    /**
     * By default the builder marks no step (owner decision 2026-11-03): built as a node's is (store, DLQ, queue) without
     * {@code stepEvents}, its pipeline, sync delivery, at-least-once worker and replay emit no {@code io.flottila.Step} under a recording
     * that enables it — while that recording sees each step's body probe on the same threads, and the step events of a flow built
     * with {@code stepEvents(true)} run in the same window.
     */
    @Test
    void aDefaultBuilderMarksNoStep() throws Exception {
        InMemoryWorkQueue queue = new InMemoryWorkQueue(rt.clock());
        FlowBuilder b = new FlowBuilder(rt.runtime(), List.of(MARK), List.of(), Map.of())
                .recording(new InMemoryMessageStore(), () -> Snapshot.CPU_UNKNOWN).dlq(new InMemoryDlq()).queue(queue);
        String plainId = "step-events-off-pipeline";
        String syncId = "step-events-off-sync";
        String queuedId = "step-events-off-worker";
        String witnessId = "step-events-off-witness";
        FlowDefinition plainDef = flow(plainId, "sync", List.of(step("mark", "a"), step("mark", "b")));
        Pipeline plain = b.build(plainDef, NO_FILES);
        Replay replay = b.replay(plainDef, NO_FILES);
        Delivery sync = b.delivery(flow(syncId, "sync", List.of(step("mark", "a"), step("mark", "b"))), NO_FILES);
        FlowDefinition atLeastOnce = flow(queuedId, "at-least-once", List.of(step("mark", "a"), step("mark", "b")));
        Delivery accepting = b.delivery(atLeastOnce, NO_FILES);
        FlowWorker worker = b.worker(atLeastOnce, NO_FILES, "w1", new WorkerPolicy(Duration.ofSeconds(30), 3, Duration.ofSeconds(1)));
        Pipeline witness = new FlowBuilder(rt.runtime(), List.of(MARK), List.of(), Map.of()).stepEvents(true)
                .build(flow(witnessId, "sync", List.of(step("mark", "a"))), NO_FILES);
        Envelope in = ingress(plainId);
        AtomicReference<Ran> ran = new AtomicReference<>();
        AtomicReference<Ran> replayed = new AtomicReference<>();
        AtomicReference<Ran> synced = new AtomicReference<>();
        AtomicReference<Ran> witnessed = new AtomicReference<>();
        List<RecordedEvent> all;
        try {
            all = recorded(() -> {
                ran.set(onVirtualThread(() -> plain.apply(in)));
                replayed.set(onVirtualThread(() -> replay.from(in.id(), 2)));
                synced.set(onVirtualThread(() -> sync.apply(ingress(syncId)).await()));
                Envelope queued = ingress(queuedId);
                assertEquals(new Ack.Accepted(queued.id()), accepting.apply(queued).await(10, TimeUnit.SECONDS), "fixture: saved and queued");
                assertEquals(1, worker.poll().await(10, TimeUnit.SECONDS), "fixture: the worker claimed the item");
                long deadline = System.nanoTime() + BOUND.toNanos();
                while (queue.size() > 0) {
                    if (System.nanoTime() - deadline > 0) fail("fixture: the worker's run did not complete within " + BOUND + ": " + queue.snapshot());
                    Thread.onSpinWait();
                }
                witnessed.set(onVirtualThread(() -> witness.apply(ingress(witnessId))));
            });
        } finally {
            worker.stop();
        }
        assertInstanceOf(Envelope.class, ran.get().outcome(), "fixture: the message went through");
        assertInstanceOf(Envelope.class, replayed.get().outcome(), "fixture: the replay went through");
        assertInstanceOf(Ack.Reply.class, synced.get().outcome(), "fixture: the sync delivery answered");
        assertInstanceOf(Envelope.class, witnessed.get().outcome(), "fixture: the witness flow's message went through");

        assertEquals(List.of("body a", "body b"), probes(all, "body ", ran.get().thread()).stream().map(e -> e.getString("label")).toList(),
                "witness: the recording saw the pipeline's step bodies on its thread");
        assertEquals(List.of("body b"), probes(all, "body ", replayed.get().thread()).stream().map(e -> e.getString("label")).toList(),
                "witness: the recording saw the replay's step body on its thread");
        assertEquals(List.of("body a", "body b"), probes(all, "body ", synced.get().thread()).stream().map(e -> e.getString("label")).toList(),
                "witness: the recording saw the sync delivery's step bodies on its thread");
        assertEquals(List.of("1 a"), stepsOf(stepEvents(all, witnessId)), "witness: the same recording sees the step events of a flow built with stepEvents(true)");
        for (String id : List.of(plainId, syncId, queuedId)) {
            assertEquals(List.of(), stepEvents(all, id).stream().map(FlowBuilderStepEventTest::shown).toList(),
                    "a default builder marks no step of " + id + " (pipeline, replay, sync delivery, worker)");
        }
    }

    /**
     * The flag is the builder's state when it builds, both ways: one builder, {@code stepEvents(true)}, flow A built, then
     * {@code stepEvents(false)}, flow B built — under a recording enabling {@code io.flottila.Step}, A's steps are marked and B's are
     * not (B's step bodies witness that it ran in the recording).
     */
    @Test
    void stepEventsFalseAfterTrueBuildsUnmarkedFlows() throws Exception {
        String markedId = "step-events-toggle-on";
        String unmarkedId = "step-events-toggle-off";
        FlowBuilder b = new FlowBuilder(rt.runtime(), List.of(MARK), List.of(), Map.of());
        Pipeline marked = b.stepEvents(true).build(flow(markedId, "sync", List.of(step("mark", "a"), step("mark", "b"))), NO_FILES);
        Pipeline unmarked = b.stepEvents(false).build(flow(unmarkedId, "sync", List.of(step("mark", "a"), step("mark", "b"))), NO_FILES);
        AtomicReference<Ran> ranMarked = new AtomicReference<>();
        AtomicReference<Ran> ranUnmarked = new AtomicReference<>();
        List<RecordedEvent> all = recorded(() -> {
            ranMarked.set(onVirtualThread(() -> marked.apply(ingress(markedId))));
            ranUnmarked.set(onVirtualThread(() -> unmarked.apply(ingress(unmarkedId))));
        });
        assertInstanceOf(Envelope.class, ranMarked.get().outcome(), "fixture: flow A's message went through");
        assertInstanceOf(Envelope.class, ranUnmarked.get().outcome(), "fixture: flow B's message went through");
        assertEquals(List.of("body a", "body b"), probes(all, "body ", ranUnmarked.get().thread()).stream().map(e -> e.getString("label")).toList(),
                "witness: the recording saw flow B's step bodies on its thread");
        assertEquals(List.of("1 a", "2 b"), stepsOf(stepEvents(all, markedId, ranMarked.get().thread())), "flow A, built after stepEvents(true): its steps are marked");
        assertEquals(List.of(), stepEvents(all, unmarkedId).stream().map(FlowBuilderStepEventTest::shown).toList(),
                "flow B, built after stepEvents(false) on the same builder: no step is marked");
    }

    /**
     * The event is disabled unless a recording enables it: a recording with the JDK's {@code default} or {@code profile} settings
     * sees none — while it does see the probe the step's body commits on the same thread, so it was recording.
     */
    @Test
    void aDefaultRecordingSeesNoStepEvent() throws Exception {
        String id = "step-events-default";
        Pipeline p = new FlowBuilder(rt.runtime(), List.of(MARK), List.of(), Map.of()).stepEvents(true).build(flow(id, "sync", List.of(step("mark", "a"))), NO_FILES);
        for (String settings : List.of("default", "profile")) {
            AtomicReference<Ran> ran = new AtomicReference<>();
            List<RecordedEvent> all = recorded(Optional.of(Configuration.getConfiguration(settings)), () -> ran.set(onVirtualThread(() -> p.apply(ingress(id)))));
            assertInstanceOf(Envelope.class, ran.get().outcome(), "fixture: the message went through");
            assertEquals(1, probes(all, "body a", ran.get().thread()).size(), "witness: the " + settings + " recording saw the step's body probe");
            assertEquals(List.of(), stepEvents(all, id).stream().map(FlowBuilderStepEventTest::shown).toList(), "no step event under the " + settings + " settings");
        }
    }

    /**
     * The stages a pipeline starts with are not steps: a recorded flow's run emits events for steps 1..3 only, not for the ingress
     * snapshot's stage; a replay from step 2 emits events for steps 2 and 3 only, not for its replay-input stage.
     */
    @Test
    void theStartStageIsNotMarked() throws Exception {
        String id = "step-events-start";
        FlowDefinition def = flow(id, "sync", List.of(step("mark", "a"), step("mark", "b"), step("mark", "c")));
        FlowBuilder b = new FlowBuilder(rt.runtime(), List.of(MARK), List.of(), Map.of()).stepEvents(true).recording(new InMemoryMessageStore(), () -> Snapshot.CPU_UNKNOWN);
        Pipeline p = b.build(def, NO_FILES);
        Replay replay = b.replay(def, NO_FILES);
        Envelope in = ingress(id);
        AtomicReference<Ran> run = new AtomicReference<>();
        AtomicReference<Ran> replayed = new AtomicReference<>();
        List<RecordedEvent> all = recorded(() -> {
            run.set(onVirtualThread(() -> p.apply(in)));
            replayed.set(onVirtualThread(() -> replay.from(in.id(), 2)));
        });
        assertInstanceOf(Envelope.class, run.get().outcome(), "fixture: the message went through");
        assertInstanceOf(Envelope.class, replayed.get().outcome(), "fixture: the replay went through");

        assertEquals(List.of("1 a", "2 b", "3 c"), stepsOf(stepEvents(all, id, run.get().thread())), "the recorded run: its steps, not its ingress stage");
        assertEquals(List.of("2 b", "3 c"), stepsOf(stepEvents(all, id, replayed.get().thread())), "the replay from step 2: steps 2 and 3, not its input stage");
        assertEquals(5, stepEvents(all, id).size(), "and nothing else of the flow");
    }

    /**
     * The step event is the outermost wrapper: the recording wrapper's meter readings (at its entry and when it records) and the
     * parking of a failed at-least-once step in the DLQ all happen within the event of the step they belong to.
     */
    @Test
    void aStepsEventSpansItsRecordingAndParking() throws Exception {
        String id = "step-events-outermost";
        Meter meter = () -> {
            probe("meter");
            return Snapshot.CPU_UNKNOWN;
        };
        ProbedDlq dlq = new ProbedDlq();
        Pipeline p = new FlowBuilder(rt.runtime(), List.of(MARK, BOOM), List.of(), Map.of()).stepEvents(true).recording(new InMemoryMessageStore(), meter).dlq(dlq)
                .build(flow(id, "at-least-once", List.of(step("mark", "first"), step("boom", "fails"))), NO_FILES);
        AtomicReference<Ran> ran = new AtomicReference<>();
        List<RecordedEvent> all = recorded(() -> ran.set(onVirtualThread(() -> p.apply(ingress(id)))));
        ParkedException parked = assertInstanceOf(ParkedException.class, ran.get().outcome(), "fixture: step 2 failed and was parked");

        long thread = ran.get().thread();
        List<RecordedEvent> events = stepEvents(all, id, thread);
        assertEquals(List.of("1 first", "2 fails"), stepsOf(events));
        RecordedEvent e1 = events.get(0);
        RecordedEvent e2 = events.get(1);

        List<RecordedEvent> readings = probes(all, "meter", thread);
        assertEquals(4, readings.size(), "fixture: each step's recorder reads the meter at its entry and when it records: "
                + readings.stream().map(FlowBuilderStepEventTest::shown).toList());
        assertWithin(e1, readings.get(0), "step 1's recording wrapper is entered within step 1's event");
        assertWithin(e1, readings.get(1), "step 1 is recorded within its event");
        assertTrue(notAfter(readings.get(1).getEndTime(), e2.getStartTime()), "fixture: step 1 recorded before step 2 began");
        assertWithin(e2, readings.get(2), "step 2's recording wrapper is entered within step 2's event");
        assertWithin(e2, readings.get(3), "step 2's failure is recorded within its event");

        List<RecordedEvent> parks = all.stream().filter(e -> e.getEventType().getName().equals(PROBE_EVENT) && "park".equals(e.getString("label"))).toList();
        assertEquals(1, parks.size(), "fixture: one park");
        assertEquals(thread, parks.get(0).getThread().getJavaThreadId(), "fixture: the DLQ was called on the message's thread");
        assertWithin(e2, parks.get(0), "the failed step is parked within its event");
        assertTrue(dlq.delegate.get(parked.parked().messageId()).await().isPresent(), "fixture: the row is in the DLQ");
    }

    /** An {@link InMemoryDlq} whose park commits a probe {@code park} spanning the call, on the calling thread. */
    static final class ProbedDlq implements Dlq {
        final InMemoryDlq delegate = new InMemoryDlq();

        @Override
        public Future<Void> park(Parked parked) {
            Probe p = new Probe();
            p.label = "park";
            p.begin();
            try {
                return delegate.park(parked);
            } finally {
                p.commit();
            }
        }

        @Override public Future<Page<Parked>> list(Optional<String> after, int limit) { return delegate.list(after, limit); }

        @Override public Future<Optional<Parked>> get(String messageId) { return delegate.get(messageId); }

        @Override public Future<Void> remove(String messageId) { return delegate.remove(messageId); }
    }
}
