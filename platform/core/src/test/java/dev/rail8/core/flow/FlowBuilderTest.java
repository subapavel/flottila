package dev.rail8.core.flow;

import dev.rail8.api.Ack;
import dev.rail8.api.Component;
import dev.rail8.api.Consumer;
import dev.rail8.api.EndpointConfig;
import dev.rail8.api.Envelope;
import dev.rail8.api.Health;
import dev.rail8.api.Headers;
import dev.rail8.api.Pipeline;
import dev.rail8.api.Producer;
import dev.rail8.api.Stage;
import dev.rail8.api.StepConfig;
import dev.rail8.api.StepFactory;
import dev.rail8.api.StepResources;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.dlq.Parked;
import dev.rail8.core.dlq.ParkedException;
import dev.rail8.core.queue.InMemoryWorkQueue;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.core.record.Snapshot;
import dev.rail8.testkit.FakeRuntime;
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

    /**
     * FIX-264: a delivery of a mode the builder does not know is refused, naming the flow and the mode — never built as one of the
     * modes it knows: no producer of the flow's {@code to} step is opened for it.
     */
    @Test
    void aDeliveryOfAnUnknownModeIsRefusedNamingTheFlowAndTheModeAndOpensNoProducer() {
        EchoComponent echo = new EchoComponent();
        rt.withComponent(echo);
        FlowBuilder builder = new FlowBuilder(rt.runtime(), List.of(), List.of(), Map.of("partner", new EndpointConfig("partner", URI.create("echo://partner"), Map.of())));
        Map<String, Object> doc = new java.util.HashMap<>(flow(List.of(Map.of("type", "to", "name", "send", "endpoint", "partner"))));
        doc.put("delivery", Map.of("mode", "exactly-once"));
        FlowDefinition def = FlowDefinition.from(doc);
        assertEquals("exactly-once", def.deliveryMode(), "fixture: the definition carries the mode as written");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> builder.delivery(def, NO_FILES));
        String text = refused.getMessage();
        assertTrue(text.contains("flow 'f'") && text.contains("delivery.mode 'exactly-once'"), text);
        assertEquals(List.of(), echo.producersBuilt, "no producer opened for a delivery that does not build");
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
        // the run's time limit is pending throughout: a second task is the retry's wait, the earlier of the two
        while (t.isAlive()) { if (rt.timer().pending() > 1) rt.clock().advance(rt.timer().nextDelay()); Thread.onSpinWait(); Thread.yield(); }
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
        StepFactory toProp = factory("to-prop", s -> Stage.of(e -> e.with(new dev.rail8.api.Key<String>("PARTNER"), e.headers().get("choice"))));
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
     * snapshots (ingress and steps), an at-least-once pipeline's DLQ row when a step fails, the at-least-once
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

        // sync: the ingress and both steps
        FlowDefinition sync = FlowDefinition.from(versionedFlow("sync"));
        Envelope in = rt.ingress("f", Headers.empty(), Buffer.buffer("<x/>"));
        b.build(sync, NO_FILES).apply(in);
        List<Snapshot> synced = store.timeline(in.id()).await();
        assertEquals(List.of(0, 1, 2), steps(synced), "fixture: the ingress and both steps recorded");
        assertVersions(synced, "sync");

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
        FlowWorker worker = b.retries(new RetryPolicy(0, Duration.ofMinutes(1), Duration.ofMinutes(1), 1.0))   // CORE-14a: maxAttempts 1 is count 0
                .worker(atLeastOnce, NO_FILES, "w1", new WorkerPolicy(LEASE, Duration.ofSeconds(1)));
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

    // --- P1-09d2a (D-063): every file a build reads reaches the builder's reads sink, for the flow's effective hash

    /** A step that reads, at build time, the file its {@code file} option names and, relative to that file, its {@code include}. */
    static final StepFactory READS = new StepFactory() {
        @Override public String type() { return "reads"; }
        @Override public String configSchema() { return "{}"; }
        @Override public Stage create(StepConfig step, StepResources res) {
            Path named = res.file((String) step.config().get("file"));
            res.resolve(named.toUri().toString(), (String) step.config().get("include"));
            return Stage.of(e -> e);
        }
    };

    static FlowDefinition reading(String mode) {
        Map<String, Object> doc = new java.util.HashMap<>(flow(List.of(Map.of("type", "reads", "name", "r", "file", "main.xsl", "include", "sub/lib.xsl"))));
        doc.put("delivery", Map.of("mode", mode));
        return FlowDefinition.from(doc);
    }

    /**
     * The sink {@code reads(...)} names gets each path the steps' {@link StepResources} returned while the flow was built — a sync or
     * at-most-once delivery builds the steps, an at-least-once flow's worker does — and a later {@code reads(...)} replaces it, so a
     * deploy can tell the files of one flow from the next one's.
     */
    @Test
    void theReadsSinkGetsEveryFileOfDeliveryAndWorker() {
        FlowBuilder b = new FlowBuilder(rt.runtime(), List.of(READS), List.of(), Map.of())
                .recording(new InMemoryMessageStore(), () -> 0).dlq(new InMemoryDlq()).queue(new InMemoryWorkQueue(rt.clock()));
        Path a = Path.of("flows-a").toAbsolutePath().normalize();
        Path c = Path.of("flows-c").toAbsolutePath().normalize();
        Path w = Path.of("flows-w").toAbsolutePath().normalize();

        List<Path> sync = new ArrayList<>();
        b.reads(sync::add).delivery(reading("sync"), a);
        assertEquals(List.of(a.resolve("main.xsl"), a.resolve("sub/lib.xsl")), sync, "a sync delivery's build: the named file and its include");

        List<Path> atMostOnce = new ArrayList<>();
        b.reads(atMostOnce::add).delivery(reading("at-most-once"), c);
        assertEquals(List.of(c.resolve("main.xsl"), c.resolve("sub/lib.xsl")), atMostOnce, "an at-most-once delivery's build");

        List<Path> worker = new ArrayList<>();
        b.reads(worker::add).worker(reading("at-least-once"), w, "w1");
        assertEquals(List.of(w.resolve("main.xsl"), w.resolve("sub/lib.xsl")), worker, "an at-least-once worker's build");

        assertEquals(2, sync.size(), "a sink replaced by a later reads(...) gets nothing more: " + sync);
        assertEquals(2, atMostOnce.size(), "a sink replaced by a later reads(...) gets nothing more: " + atMostOnce);
    }

    // --- P2-02b (D-087): the package's dependencies reach every step's resources

    /** Records the dependencies its resources carry, then reads the file its {@code file} option names. */
    static final class DependencyProbe implements StepFactory {
        final List<Map<String, Path>> seen = new ArrayList<>();
        @Override public String type() { return "probe-deps"; }
        @Override public String configSchema() { return "{}"; }
        @Override public Stage create(StepConfig step, StepResources res) {
            seen.add(res.dependencies());
            res.file((String) step.config().get("file"));
            return Stage.of(e -> e);
        }
    }

    static FlowDefinition probing(String mode, String file) {
        Map<String, Object> doc = new java.util.HashMap<>(flow(List.of(Map.of("type", "probe-deps", "name", "p", "file", file))));
        doc.put("delivery", Map.of("mode", mode));
        return FlowDefinition.from(doc);
    }

    /**
     * {@code dependencies(map)} hands every flow built from then on resources whose {@code dependencies()} is that map — a sync
     * delivery's build and an at-least-once worker's alike — so {@code file("common:x.xsl")} resolves in common's directory and is
     * reported to the reads sink; without a call the map is empty, and a later call replaces it.
     */
    @Test
    void theDependenciesReachEveryStepsResources() {
        DependencyProbe probe = new DependencyProbe();
        FlowBuilder b = new FlowBuilder(rt.runtime(), List.of(probe), List.of(), Map.of())
                .recording(new InMemoryMessageStore(), () -> 0).dlq(new InMemoryDlq()).queue(new InMemoryWorkQueue(rt.clock()));
        Path pkg = Path.of("pkg-invoice").toAbsolutePath().normalize();
        Path common = Path.of("pkg-common").toAbsolutePath().normalize();

        b.delivery(probing("sync", "x.xsl"), pkg);
        assertEquals(List.of(Map.of()), probe.seen, "without a call: no dependencies");

        List<Path> reads = new ArrayList<>();
        b.dependencies(Map.of("common", common)).reads(reads::add).delivery(probing("sync", "common:x.xsl"), pkg);
        b.worker(probing("at-least-once", "common:xslt/y.xsl"), pkg, "w1");
        assertEquals(List.of(Map.of(), Map.of("common", common), Map.of("common", common)), probe.seen, "the delivery's and the worker's step");
        assertEquals(List.of(common.resolve("x.xsl"), common.resolve("xslt/y.xsl")), reads, "each resolved in common's directory, reported");

        b.dependencies(Map.of());
        assertThrows(IllegalArgumentException.class, () -> b.delivery(probing("sync", "common:x.xsl"), pkg),
                "a later call replaces the map: common is no dependency any more");
        assertThrows(NullPointerException.class, () -> b.dependencies(null));
    }
}
