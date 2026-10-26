package io.flottila.core.flow;

import io.flottila.api.Ack;
import io.flottila.api.Component;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Health;
import io.flottila.api.Headers;
import io.flottila.api.Pipeline;
import io.flottila.api.Producer;
import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.api.StepFactory;
import io.flottila.api.StepResources;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.dlq.Parked;
import io.flottila.core.dlq.ParkedException;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Snapshot;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FlowBuilder (ARCHITECTURE §4, CLAUDE.md): builds a Pipeline from a FlowDefinition and registered step factories;
 * resolves producers from the Runtime's components at build time; every step is numbered; a retryable step is
 * wrapped; the Runtime is used here and nowhere else.
 */
class FlowBuilderTest {

    final FakeRuntime rt = FakeRuntime.create();

    /** A producer component that records what it is sent and echoes a reply. */
    static final class EchoComponent implements Component {
        final List<Envelope> sent = new ArrayList<>();
        final List<String> producersBuilt = new ArrayList<>();
        @Override public String scheme() { return "echo"; }
        @Override public String configSchema() { return "{}"; }
        @Override public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) { throw new UnsupportedOperationException(); }
        @Override public Producer producer(EndpointConfig cfg) {
            producersBuilt.add(cfg.name());
            return new Producer() {
                @Override public Future<Envelope> send(Envelope e) { sent.add(e); return Future.succeededFuture(e.withHeader("replied-by", cfg.name())); }
                @Override public Future<Void> close() { return Future.succeededFuture(); }
            };
        }
        @Override public Future<Health> health() { return Future.succeededFuture(Health.up()); }
    }

    static StepFactory factory(String type, Function<StepConfig, Stage> make) {
        return new StepFactory() {
            @Override public String type() { return type; }
            @Override public String configSchema() { return "{}"; }
            @Override public Stage create(StepConfig step, StepResources res) { return make.apply(step); }
        };
    }

    /** No step of these flows refers to a file. */
    static final Path NO_FILES = Path.of("");

    static Map<String, Object> flow(List<Map<String, Object>> steps) {
        return Map.of("schemaVersion", 1, "kind", "flow", "id", "f", "version", "1.0.0", "from", Map.of("endpoint", "in"),
                "delivery", Map.of("mode", "sync"), "limits", Map.of("maxBodyBytes", 4096),
                "retry", Map.of("attempts", 2, "initial", "10ms", "max", "20ms"), "steps", steps);
    }

    @Test
    void buildsTheStepsInOrderNumbersThemFromOneAndSendsThroughResolvedProducers() {
        EchoComponent echo = new EchoComponent();
        rt.withComponent(echo);
        FlowBuilder builder = new FlowBuilder(rt.runtime(), List.of(factory("mark", s -> Stage.of(e -> e.withHeader("seen-" + s.name(), "step " + e.step())))), List.of(),
                Map.of("partner", new EndpointConfig("partner", URI.create("echo://partner"), Map.of())));
        Map<String, Object> doc = new java.util.HashMap<>(flow(List.of(
                Map.of("type", "mark", "name", "a"),
                Map.of("type", "mark", "name", "b"),
                Map.of("type", "to", "name", "send", "endpoint", "partner"))));
        doc.put("forwardHeaders", List.of("seen-a", "seen-b"));   // D-049 (e): a producer gets only the listed headers, whatever set them
        FlowDefinition def = FlowDefinition.from(doc);
        Pipeline p = builder.build(def, NO_FILES);
        Envelope out = p.apply(rt.ingress("f", Headers.empty(), Buffer.buffer("<x/>")));
        assertEquals("step 0", out.header("seen-a").orElseThrow(), "step a saw the ingress envelope");
        assertEquals("step 1", out.header("seen-b").orElseThrow(), "step b saw step 1's envelope: numbering follows the position");
        assertEquals(3, out.step(), "after three steps the envelope is at step 3");
        assertEquals("partner", out.header("replied-by").orElseThrow());
        assertEquals(1, echo.sent.size());
        assertEquals(2, echo.sent.get(0).step(), "the producer received step 2's envelope");
        assertEquals(List.of("partner"), echo.producersBuilt, "the producer was resolved once, at build time");
    }

    @Test
    void unknownStepTypesMissingEndpointsAndUnknownSchemesAreBuildErrorsNamingTheStep() {
        rt.withComponent(new EchoComponent());
        FlowBuilder builder = new FlowBuilder(rt.runtime(), List.of(), List.of(), Map.of("partner", new EndpointConfig("partner", URI.create("echo://partner"), Map.of()),
                "ftp", new EndpointConfig("ftp", URI.create("sftp://host"), Map.of())));
        IllegalArgumentException unknownType = assertThrows(IllegalArgumentException.class,
                () -> builder.build(FlowDefinition.from(flow(List.of(Map.of("type", "xlst", "name", "map")))), NO_FILES));
        assertTrue(unknownType.getMessage().contains("xlst") && unknownType.getMessage().contains("map"), unknownType.getMessage());
        IllegalArgumentException unknownEndpoint = assertThrows(IllegalArgumentException.class,
                () -> builder.build(FlowDefinition.from(flow(List.of(Map.of("type", "to", "name", "send", "endpoint", "nowhere")))), NO_FILES));
        assertTrue(unknownEndpoint.getMessage().contains("nowhere") && unknownEndpoint.getMessage().contains("send"), unknownEndpoint.getMessage());
        IllegalArgumentException unknownScheme = assertThrows(IllegalArgumentException.class,
                () -> builder.build(FlowDefinition.from(flow(List.of(Map.of("type", "to", "name", "send", "endpoint", "ftp")))), NO_FILES));
        assertTrue(unknownScheme.getMessage().contains("sftp"), unknownScheme.getMessage());
    }

    @Test
    void aRetryableStepIsRetriedWithTheFlowsPolicyAndANonRetryableOneIsNot() throws Exception {
        AtomicInteger flakyCalls = new AtomicInteger();
        AtomicInteger plainCalls = new AtomicInteger();
        FlowBuilder builder = new FlowBuilder(rt.runtime(), List.of(
                factory("flaky", s -> Stage.of(e -> { if (flakyCalls.incrementAndGet() < 2) throw new IllegalStateException("once"); return e; })),
                factory("plain", s -> Stage.of(e -> { plainCalls.incrementAndGet(); throw new IllegalStateException("always"); }))), List.of(), Map.of());
        Pipeline retried = builder.build(FlowDefinition.from(flow(List.of(Map.of("type", "flaky", "name", "io", "retryable", true)))), NO_FILES);
        Envelope in = rt.ingress("f", Headers.empty(), Buffer.buffer("<x/>"));
        Thread t = Thread.ofVirtual().start(() -> retried.apply(in));
        while (t.isAlive()) { if (rt.timer().pending() > 0) rt.clock().advance(rt.timer().nextDelay()); Thread.onSpinWait(); Thread.yield(); }
        assertEquals(2, flakyCalls.get(), "failed once, retried once, succeeded");
        Pipeline plain = builder.build(FlowDefinition.from(flow(List.of(Map.of("type", "plain", "name", "cpu")))), NO_FILES);
        assertThrows(IllegalStateException.class, () -> plain.apply(in));
        assertEquals(1, plainCalls.get(), "not retryable: one call, the failure surfaces");
    }

    @Test
    void byPropSendsToTheEndpointTheRouteChoseAndRefusesAnUnknownChoice() {
        EchoComponent echo = new EchoComponent();
        rt.withComponent(echo);
        FlowBuilder builder = new FlowBuilder(rt.runtime(), List.of(factory("choose", s -> Stage.of(e -> e.withHeader("choice", (String) s.config().get("value"))))), List.of(),
                Map.of("dom", new EndpointConfig("dom", URI.create("echo://dom"), Map.of()), "for", new EndpointConfig("for", URI.create("echo://for"), Map.of())));
        // the route decision travels as a prop; the test's choose step writes a header and a tiny adapter step copies it into the prop
        StepFactory toProp = factory("to-prop", s -> Stage.of(e -> e.with(new io.flottila.api.Key<String>("PARTNER"), e.headers().get("choice"))));
        FlowBuilder b2 = new FlowBuilder(rt.runtime(), List.of(factory("choose", s -> Stage.of(e -> e.withHeader("choice", (String) s.config().get("value")))), toProp), List.of(),
                Map.of("dom", new EndpointConfig("dom", URI.create("echo://dom"), Map.of()), "for", new EndpointConfig("for", URI.create("echo://for"), Map.of())));
        Pipeline p = b2.build(FlowDefinition.from(flow(List.of(
                Map.of("type", "choose", "name", "c", "value", "foreign"), Map.of("type", "to-prop", "name", "p"),
                Map.of("type", "to", "name", "send", "byProp", "PARTNER", "endpoints", Map.of("domestic", "dom", "foreign", "for"))))), NO_FILES);
        assertEquals("for", p.apply(rt.ingress("f", Headers.empty(), Buffer.buffer("<x/>"))).header("replied-by").orElseThrow());
        Pipeline bad = b2.build(FlowDefinition.from(flow(List.of(
                Map.of("type", "choose", "name", "c", "value", "martian"), Map.of("type", "to-prop", "name", "p"),
                Map.of("type", "to", "name", "send", "byProp", "PARTNER", "endpoints", Map.of("domestic", "dom"))))), NO_FILES);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bad.apply(rt.ingress("f", Headers.empty(), Buffer.buffer("<x/>"))));
        assertTrue(e.getMessage().contains("martian"), e.getMessage());
        // Map.of has no fixed iteration order: the first build resolved both endpoints (in either order), the second only 'dom'
        assertEquals(java.util.Set.of("dom", "for"), new java.util.HashSet<>(echo.producersBuilt.subList(0, 2)), "producers are resolved per build, for the endpoints the flow names");
        assertEquals(List.of("dom"), echo.producersBuilt.subList(2, 3));
    }

    // --- versions (P1-09d1a, D-059): the deploy adds package=<name>@<version> and revision to the flow's own versions

    static final Map<String, String> DEPLOYED = Map.of("package", "shop@2.3.0", "revision", "9f8e7d6");
    static final Map<String, String> MERGED = Map.of("flow", "f@1.0.0", "package", "shop@2.3.0", "revision", "9f8e7d6");
    static final Duration LEASE = Duration.ofSeconds(30);

    static Map<String, Object> versionedFlow(String mode) {
        Map<String, Object> doc = new java.util.HashMap<>(flow(List.of(Map.of("type", "mark", "name", "a"), Map.of("type", "partner", "name", "p"))));
        doc.put("delivery", Map.of("mode", mode));
        doc.put("limits", Map.of("maxBodyBytes", 4096, "maxInFlight", 4));
        return doc;
    }

    static List<Integer> steps(List<Snapshot> timeline) {
        return timeline.stream().map(Snapshot::step).toList();
    }

    static void assertVersions(List<Snapshot> timeline, String what) {
        for (Snapshot s : timeline) assertEquals(MERGED, s.versions(), what + ", snapshot of step " + s.step() + " (" + s.stepName() + ")");
    }

    /**
     * Every place the builder hands the flow's versions on carries the extra ones beside {@code flow}: the sync pipeline's
     * snapshots (ingress and steps), a replay's snapshots, an at-least-once pipeline's DLQ row when a step fails, the at-least-once
     * delivery's ingress snapshot, the worker's snapshots and the worker's DLQ row for an item past its attempts.
     */
    @Test
    void versionsReachEverySnapshotAndDlqRow() throws Exception {
        InMemoryMessageStore store = new InMemoryMessageStore();
        InMemoryDlq dlq = new InMemoryDlq();
        InMemoryWorkQueue queue = new InMemoryWorkQueue(rt.clock());
        AtomicBoolean partnerDown = new AtomicBoolean();
        FlowBuilder b = new FlowBuilder(rt.runtime(), List.of(
                factory("mark", s -> Stage.of(e -> e.withHeader("seen-" + s.name(), "yes"))),
                factory("partner", s -> Stage.of(e -> { if (partnerDown.get()) throw new IllegalStateException("partner down"); return e; }))),
                List.of(), Map.of()).recording(store, () -> 0).dlq(dlq).queue(queue).versions(DEPLOYED);

        // sync: the ingress and both steps, then a replay of step 2 as a new message
        FlowDefinition sync = FlowDefinition.from(versionedFlow("sync"));
        Envelope in = rt.ingress("f", Headers.empty(), Buffer.buffer("<x/>"));
        b.build(sync, NO_FILES).apply(in);
        List<Snapshot> synced = store.timeline(in.id()).await();
        assertEquals(List.of(0, 1, 2), steps(synced), "fixture: the ingress and both steps recorded");
        assertVersions(synced, "sync");
        Envelope replayed = b.replay(sync, NO_FILES).from(in.id(), 2);
        List<Snapshot> replay = store.timeline(replayed.id()).await();
        assertEquals(List.of(1, 2), steps(replay), "fixture: the replay's input and step 2 recorded");
        assertVersions(replay, "replay");

        // at-least-once pipeline: a failing step is parked
        FlowDefinition atLeastOnce = FlowDefinition.from(versionedFlow("at-least-once"));
        partnerDown.set(true);
        Envelope failing = rt.ingress("f", Headers.empty(), Buffer.buffer("<x/>"));
        assertThrows(ParkedException.class, () -> b.build(atLeastOnce, NO_FILES).apply(failing));
        Parked parkedByStep = dlq.get(failing.id()).await().orElseThrow(() -> new AssertionError("fixture: the failing step was parked"));
        assertEquals("p", parkedByStep.stepName());
        assertEquals(MERGED, parkedByStep.versions(), "the DLQ row of a failed step");
        assertVersions(store.timeline(failing.id()).await(), "parked message");

        // at-least-once delivery and worker: 'exhausted' had one claim already (attempt 1 of 1), 'runs' none
        Delivery delivery = b.delivery(atLeastOnce, NO_FILES);
        Envelope exhausted = rt.ingress("f", Headers.empty(), Buffer.buffer("<x/>"));
        assertEquals(new Ack.Accepted(exhausted.id()), delivery.apply(exhausted).await());
        assertEquals(1, queue.claim("another-worker", "f", 1, LEASE).await().size(), "fixture: the first attempt is claimed elsewhere");
        rt.clock().advance(LEASE);
        partnerDown.set(false);
        Envelope runs = rt.ingress("f", Headers.empty(), Buffer.buffer("<x/>"));
        assertEquals(new Ack.Accepted(runs.id()), delivery.apply(runs).await());
        FlowWorker worker = b.worker(atLeastOnce, NO_FILES, "w1", new WorkerPolicy(LEASE, 1, Duration.ofSeconds(1)));
        try {
            assertEquals(2, worker.poll().await(5, TimeUnit.SECONDS), "fixture: both items claimed in one round");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (queue.size() > 0 || dlq.size() < 2) {
                if (System.nanoTime() - deadline > 0) throw new AssertionError("not settled within 5 s: queue " + queue.snapshot() + ", DLQ rows " + dlq.size());
                Thread.onSpinWait();
            }
        } finally {
            worker.stop();
        }
        Parked parkedByWorker = dlq.get(exhausted.id()).await().orElseThrow(() -> new AssertionError("fixture: the item past its attempts was parked"));
        assertTrue(parkedByWorker.error().contains("AttemptsExhaustedException"), "fixture: parked by the worker's attempts bound: " + parkedByWorker.error());
        assertEquals(MERGED, parkedByWorker.versions(), "the DLQ row of an item past its attempts");
        List<Snapshot> accepted = store.timeline(exhausted.id()).await();
        assertEquals(List.of(0), steps(accepted), "fixture: the delivery's ingress snapshot, no run");
        assertVersions(accepted, "at-least-once ingress");
        List<Snapshot> ran = store.timeline(runs.id()).await();
        assertEquals(List.of(0, 1, 2), steps(ran), "fixture: the delivery's ingress snapshot and the worker's two steps");
        assertVersions(ran, "worker run");
    }

    @Test
    void versionsRefuseTheFlowKey() {
        FlowBuilder b = new FlowBuilder(rt.runtime(), List.of(), List.of(), Map.of());
        IllegalArgumentException alone = assertThrows(IllegalArgumentException.class, () -> b.versions(Map.of("flow", "other@9.9.9")),
                "the flow's own version is the builder's to write");
        assertTrue(String.valueOf(alone.getMessage()).contains("flow"), "names the key: " + alone.getMessage());
        assertThrows(IllegalArgumentException.class, () -> b.versions(Map.of("package", "shop@2.3.0", "flow", "other@9.9.9")),
                "refused beside other keys too");
    }
}
