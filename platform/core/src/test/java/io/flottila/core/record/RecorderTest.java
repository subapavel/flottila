package io.flottila.core.record;

import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Pipeline;
import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.api.StepFactory;
import io.flottila.api.StepResources;
import io.flottila.core.flow.FlowBuilder;
import io.flottila.core.flow.FlowDefinition;
import io.flottila.core.flow.Replay;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recording (ARCHITECTURE §6, §8): with a MessageStore given to the builder, every step leaves a snapshot — ingress as step 0 —
 * with the step's name, outcome, wall and CPU time, headers, props, keys, the body by reference and the versions the message
 * ran with; the level decides which steps, the payload setting whether the body stays reachable; the write is never awaited
 * on the message path.
 */
@Timeout(30)
class RecorderTest {

    final FakeRuntime rt = FakeRuntime.create();
    final InMemoryMessageStore store = new InMemoryMessageStore();
    final AtomicLong cpu = new AtomicLong();
    /** Every reading is 1 µs later than the one before: a step's CPU time is the readings around it. */
    final Meter meter = () -> cpu.addAndGet(1_000);

    static StepFactory factory(String type, Function<StepConfig, Stage> make) {
        return new StepFactory() {
            @Override public String type() { return type; }
            @Override public String configSchema() { return "{}"; }
            @Override public Stage create(StepConfig step, StepResources res) { return make.apply(step); }
        };
    }

    final List<StepFactory> factories = List.of(
            factory("mark", s -> Stage.of(e -> { rt.clock().advance(Duration.ofMillis(5)); return e.withHeader("seen-" + s.name(), "yes").withKey("k-" + s.name(), "v"); })),
            factory("drop", s -> (e, rest) -> e),
            factory("boom", s -> Stage.of(e -> { throw new IllegalStateException("boom at " + s.name()); })),
            factory("rebody", s -> Stage.of(e -> e.next(rt.store(e, Buffer.buffer("<new/>"))))));

    static Map<String, Object> flow(Map<String, Object> recording, List<Map<String, Object>> steps) {
        Map<String, Object> doc = new java.util.HashMap<>(Map.of("schemaVersion", 1, "kind", "flow", "id", "f", "version", "1.0.0", "from", Map.of("endpoint", "in"),
                "delivery", Map.of("mode", "sync"), "limits", Map.of("maxBodyBytes", 4096), "steps", steps));
        if (recording != null) doc.put("recording", recording);
        return doc;
    }

    Pipeline build(Map<String, Object> recording, Map<String, Object>... steps) {
        return new FlowBuilder(rt.runtime(), factories, List.of(), Map.<String, EndpointConfig>of()).recording(store, meter)
                .build(FlowDefinition.from(flow(recording, List.of(steps))), java.nio.file.Path.of(""));
    }

    static Map<String, Object> step(String type, String name) { return Map.of("type", type, "name", name); }

    Envelope in() { return rt.ingress("f", Headers.empty().with("x", "1"), Buffer.buffer("<in/>")); }

    @Test
    void everyStepLeavesASnapshotWithIngressAsStepZero() {
        Envelope in = in();
        Envelope out = build(Map.of("level", "all"), step("mark", "a"), step("rebody", "b")).apply(in);
        List<Snapshot> t = store.timeline(in.id()).await();
        assertEquals(List.of(0, 1, 2), t.stream().map(Snapshot::step).toList());
        assertEquals(List.of("ingress", "a", "b"), t.stream().map(Snapshot::stepName).toList());
        assertTrue(t.stream().allMatch(s -> s.outcome() == Snapshot.Outcome.OK && s.error().isEmpty() && s.flow().equals("f")));
        Snapshot ingress = t.get(0);
        assertEquals(0, ingress.wallNanos(), "ingress is identity: nothing to time");
        assertEquals("1", ingress.headers().get("x"));
        assertEquals(in.body().hash(), ingress.bodyHash());
        assertEquals(in.body().size(), ingress.bodySize());
        assertEquals(Optional.of(in.body().key()), ingress.bodyKey(), "keep: the body stays reachable by its key");
        assertEquals(Map.of("flow", "f@1.0.0"), ingress.versions(), "the versions the message ran with");
        Snapshot a = t.get(1);
        assertEquals(Duration.ofMillis(5).toNanos(), a.wallNanos(), "the step advanced the fake clock by 5 ms");
        assertEquals(1_000, a.cpuNanos(), "one meter tick between the readings around the step");
        assertEquals("yes", a.headers().get("seen-a"));
        assertEquals("v", a.keys().get("k-a"));
        assertEquals(in.body().hash(), a.bodyHash(), "headers only: the same body");
        assertEquals(FakeRuntime.T0, a.startedAt());
        Snapshot b = t.get(2);
        assertEquals(out.body().hash(), b.bodyHash());
        assertEquals(Optional.of(out.body().key()), b.bodyKey());
        assertEquals(2, b.bodyKey().orElseThrow().step());
        assertEquals(FakeRuntime.T0.plusMillis(5), b.startedAt());
        assertEquals(List.of(in.id()), store.findByKey("k-a", "v", Optional.empty(), Page.MAX_LIMIT).await().items());
    }

    @Test
    void aFilteredMessageAndAFailedStepAreRecordedAsSuchAndTheFailureStillSurfaces() {
        Envelope in = in();
        Envelope back = build(Map.of("level", "all"), step("mark", "a"), step("drop", "f"), step("mark", "never")).apply(in);
        List<Snapshot> t = store.timeline(in.id()).await();
        assertEquals(List.of("ingress", "a", "f"), t.stream().map(Snapshot::stepName).toList(), "nothing after the filter");
        assertEquals(Snapshot.Outcome.FILTERED, t.get(2).outcome());
        assertEquals(2, t.get(2).step(), "the filter's own number even though it handed back step 1's envelope");
        assertEquals(1, back.step());
        Envelope in2 = in();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> build(Map.of("level", "all"), step("mark", "a"), step("boom", "x")).apply(in2));
        assertEquals("boom at x", e.getMessage());
        List<Snapshot> t2 = store.timeline(in2.id()).await();
        assertEquals(List.of("ingress", "a", "x"), t2.stream().map(Snapshot::stepName).toList());
        assertEquals(Snapshot.Outcome.FAILED, t2.get(2).outcome());
        assertTrue(t2.get(2).error().orElseThrow().contains("boom at x"));
        assertEquals("yes", t2.get(2).headers().get("seen-a"), "the failed step's snapshot shows what it was given");
    }

    @Test
    void theLevelSelectsTheStepsAndAStepMayOverrideTheFlow() {
        Envelope ends = in();
        build(Map.of("level", "ends"), step("mark", "a"), step("mark", "b"), step("mark", "c")).apply(ends);
        assertEquals(List.of(0, 3), store.timeline(ends.id()).await().stream().map(Snapshot::step).toList());
        Envelope endsFailed = in();
        assertThrows(IllegalStateException.class, () -> build(Map.of("level", "ends"), step("mark", "a"), step("boom", "x"), step("mark", "c")).apply(endsFailed));
        assertEquals(List.of(0, 2), store.timeline(endsFailed.id()).await().stream().map(Snapshot::step).toList(), "the failure is the end");
        Envelope errorsOk = in();
        build(Map.of("level", "errors"), step("mark", "a")).apply(errorsOk);
        assertEquals(List.of(), store.timeline(errorsOk.id()).await());
        Envelope errorsFailed = in();
        assertThrows(IllegalStateException.class, () -> build(Map.of("level", "errors"), step("mark", "a"), step("boom", "x")).apply(errorsFailed));
        assertEquals(List.of(2), store.timeline(errorsFailed.id()).await().stream().map(Snapshot::step).toList());
        Envelope off = in();
        build(Map.of("level", "off"), step("mark", "a")).apply(off);
        assertEquals(List.of(), store.timeline(off.id()).await());
        Envelope override = in();
        build(Map.of("level", "off"), step("mark", "a"), Map.of("type", "mark", "name", "b", "recording", Map.of("level", "all"))).apply(override);
        assertEquals(List.of(2), store.timeline(override.id()).await().stream().map(Snapshot::step).toList(), "step b records itself although the flow is off");
        Envelope defaults = in();
        build(null, step("mark", "a")).apply(defaults);
        assertEquals(List.of(0, 1), store.timeline(defaults.id()).await().stream().map(Snapshot::step).toList(), "no recording section: all, keep");
    }

    @Test
    void aStageThatHandsOnTwiceIsRecordedOnceWithTheFirstAndAFilterUnderErrorsLeavesNoRow() {
        StepFactory twice = factory("twice", s -> (e, rest) -> { rest.apply(e.withHeader("child", "1")); rest.apply(e.withHeader("child", "2")); return e; });
        List<StepFactory> all = new java.util.ArrayList<>(factories);
        all.add(twice);
        Envelope in = in();
        new FlowBuilder(rt.runtime(), all, List.of(), Map.<String, EndpointConfig>of()).recording(store, meter)
                .build(FlowDefinition.from(flow(Map.of("level", "all"), List.of(step("twice", "split")))), java.nio.file.Path.of("")).apply(in);
        List<Snapshot> t = store.timeline(in.id()).await();
        assertEquals(List.of("ingress", "split"), t.stream().map(Snapshot::stepName).toList(), "one row for the step, not one per hand-on");
        assertEquals("1", t.get(1).headers().get("child"), "the first envelope handed on");
        Envelope filtered = in();
        build(Map.of("level", "errors"), step("drop", "f")).apply(filtered);
        assertEquals(List.of(), store.timeline(filtered.id()).await(), "errors: a filtered message leaves no row");
        AtomicLong readings = new AtomicLong();
        Meter firstKnownThenUnknown = () -> readings.incrementAndGet() == 1 ? 5_000 : Snapshot.CPU_UNKNOWN;
        Envelope lost = in();
        new FlowBuilder(rt.runtime(), factories, List.of(), Map.<String, EndpointConfig>of()).recording(store, firstKnownThenUnknown)
                .build(FlowDefinition.from(flow(Map.of("level", "errors"), List.of(step("mark", "a"), step("boom", "x")))), java.nio.file.Path.of(""));
        Pipeline p = new FlowBuilder(rt.runtime(), factories, List.of(), Map.<String, EndpointConfig>of()).recording(store, firstKnownThenUnknown)
                .build(FlowDefinition.from(flow(Map.of("level", "all"), List.of(step("mark", "a")))), java.nio.file.Path.of(""));
        p.apply(lost);
        assertEquals(Snapshot.CPU_UNKNOWN, store.timeline(lost.id()).await().get(1).cpuNanos(), "a reading that turns unknown mid-step records unknown");
    }

    @Test
    void metadataOnlyKeepsHashAndSizeButNoReachableBody() {
        Envelope in = in();
        build(Map.of("payload", "metadata-only"), step("mark", "a"), Map.of("type", "rebody", "name", "b", "recording", Map.of("payload", "keep"))).apply(in);
        List<Snapshot> t = store.timeline(in.id()).await();
        assertEquals(Optional.empty(), t.get(0).bodyKey());
        assertEquals(in.body().hash(), t.get(0).bodyHash());
        assertEquals(in.body().size(), t.get(0).bodySize());
        assertEquals(Optional.empty(), t.get(1).bodyKey());
        assertTrue(t.get(2).bodyKey().isPresent(), "step b overrides: keep");
    }

    @Test
    void theWriteIsNotAwaitedOnTheMessagePathAndWithoutAStoreNothingIsRecorded() {
        MessageStore never = new MessageStore() {
            @Override public Future<Void> record(Snapshot s) { return Promise.<Void>promise().future(); }
            @Override public Future<List<Snapshot>> timeline(String messageId) { return Future.succeededFuture(List.of()); }
            @Override public Future<Page<String>> findByKey(String name, String value, Optional<String> after, int limit) { return Future.succeededFuture(new Page<>(List.of(), Optional.empty())); }
        };
        Pipeline p = new FlowBuilder(rt.runtime(), factories, List.of(), Map.<String, EndpointConfig>of()).recording(never, meter)
                .build(FlowDefinition.from(flow(Map.of("level", "all"), List.of(step("mark", "a")))), java.nio.file.Path.of(""));
        Envelope in = in();
        assertEquals("yes", p.apply(in).header("seen-a").orElseThrow(), "a store that never answers does not hold the message");
        Pipeline unrecorded = new FlowBuilder(rt.runtime(), factories, List.of(), Map.<String, EndpointConfig>of())
                .build(FlowDefinition.from(flow(Map.of("level", "all"), List.of(step("mark", "a")))), java.nio.file.Path.of(""));
        Envelope in2 = in();
        unrecorded.apply(in2);
        assertEquals(List.of(), store.timeline(in2.id()).await());
        Envelope in3 = in();
        new FlowBuilder(rt.runtime(), factories, List.of(), Map.<String, EndpointConfig>of()).recording(store, () -> Snapshot.CPU_UNKNOWN)
                .build(FlowDefinition.from(flow(null, List.of(step("mark", "a")))), java.nio.file.Path.of("")).apply(in3);
        assertEquals(Snapshot.CPU_UNKNOWN, store.timeline(in3.id()).await().get(1).cpuNanos(), "a meter without a reading records unknown, not a difference of -1s");
        assertSame(RecordingPolicy.DEFAULT, FlowDefinition.from(flow(null, List.of(step("mark", "a")))).recording());
    }

    /** A meter that always has a reading: what step 0 records cannot come from a meter without one. */
    static final Meter READING = () -> 5_000;

    static List<String> names(List<Snapshot> t) { return t.stream().map(Snapshot::stepName).toList(); }

    @Test
    void startSnapshotRecordsCpuUnknownEvenWithAReadingMeter() {
        Envelope in = in();
        new FlowBuilder(rt.runtime(), factories, List.of(), Map.<String, EndpointConfig>of()).recording(store, READING)
                .build(FlowDefinition.from(flow(Map.of("level", "all"), List.of(step("mark", "a")))), java.nio.file.Path.of("")).apply(in);
        List<Snapshot> t = store.timeline(in.id()).await();
        assertEquals(List.of("ingress", "a"), names(t));
        assertEquals(Snapshot.CPU_UNKNOWN, t.get(0).cpuNanos(), "step 0 times nothing: its CPU is unknown, never 0 (F-21)");
        assertEquals(0, t.get(0).wallNanos(), "and its wall stays 0");
        assertEquals(0, t.get(1).cpuNanos(), "the same meter still measures step a: two equal readings, a difference of 0, not unknown");

        Envelope arrived = in();
        Snapshot ingress = new Recorder(store, rt.clock(), READING, Map.of()).startSnapshot(arrived, Recorder.INGRESS, RecordingPolicy.DEFAULT);
        assertEquals(Snapshot.CPU_UNKNOWN, ingress.cpuNanos(), "the row an at-least-once delivery saves before its ACK: CPU unknown, never 0");
        assertEquals(0, ingress.wallNanos());
        assertEquals(0, ingress.step());
    }

    @Test
    void replayStartRowRecordsCpuUnknown() {
        FlowDefinition def = FlowDefinition.from(flow(Map.of("level", "all"), List.of(step("mark", "a"), step("mark", "b"))));
        Envelope in = in();
        new FlowBuilder(rt.runtime(), factories, List.of(), Map.<String, EndpointConfig>of()).recording(store, READING)
                .build(def, java.nio.file.Path.of("")).apply(in);
        Envelope again = new FlowBuilder(rt.runtime(), factories, List.of(), Map.<String, EndpointConfig>of()).recording(store, READING)
                .replay(def, java.nio.file.Path.of("")).from(in.id(), 2);
        List<Snapshot> t = store.timeline(again.id()).await();
        assertEquals(List.of(Replay.REPLAY_INPUT, "b"), names(t), "the replay's timeline starts with the input it was given");
        assertEquals(1, t.get(0).step());
        assertEquals(Snapshot.CPU_UNKNOWN, t.get(0).cpuNanos(), "the replay's start row times nothing: its CPU is unknown, never 0 (F-21)");
        assertEquals(0, t.get(0).wallNanos(), "and its wall stays 0");
        assertEquals(0, t.get(1).cpuNanos(), "step b is measured by the same meter: a reading, not unknown");
    }

    /** Throws a checked {@code failure} from code that declares none, as Vert.x {@code Future.await()} does when interrupted. */
    @SuppressWarnings("unchecked")
    static <T extends Throwable> RuntimeException sneaky(Throwable failure) throws T {
        throw (T) failure;
    }

    @Test
    void aCancelledStepRecordsItsFailureSnapshot() {
        Map<String, Exception> thrown = Map.of(
                "awaiting", new InterruptedException("woken by the stop deadline"),   // what an interrupted await throws, sneakily
                "reading", new IOException("connection reset"));
        List<StepFactory> all = new java.util.ArrayList<>(factories);
        thrown.forEach((type, failure) -> all.add(factory(type, s -> Stage.of(e -> { throw sneaky(failure); }))));
        for (Map.Entry<String, Exception> c : thrown.entrySet()) {
            Envelope in = in();
            Pipeline p = new FlowBuilder(rt.runtime(), all, List.of(), Map.<String, EndpointConfig>of()).recording(store, meter)
                    .build(FlowDefinition.from(flow(Map.of("level", "all"), List.of(step("mark", "a"), step(c.getKey(), "x")))), java.nio.file.Path.of(""));
            Exception escaped = assertThrows(Exception.class, () -> p.apply(in));
            assertSame(c.getValue(), escaped, c.getKey() + ": the failure goes on unchanged");
            List<Snapshot> t = store.timeline(in.id()).await();
            assertEquals(List.of("ingress", "a", "x"), t.stream().map(Snapshot::stepName).toList(), c.getKey() + ": a checked failure leaves the step's row like any other");
            assertEquals(Snapshot.Outcome.FAILED, t.get(2).outcome(), c.getKey());
            String error = t.get(2).error().orElse("");
            assertTrue(error.contains(c.getValue().getClass().getSimpleName()) && error.contains(c.getValue().getMessage()), c.getKey() + ": the row says what failed: " + error);
            assertEquals("yes", t.get(2).headers().get("seen-a"), c.getKey() + ": the failed step's snapshot shows what it was given");
        }
    }

    /** The errors a step may throw (D-111): one carrying a message, and one that carries none, so only its class can be named. */
    static Stream<Arguments> errors() {
        return Stream.of(
                Arguments.of(new AssertionError("invariant broken at x"), "java.lang.AssertionError", "invariant broken at x"),
                Arguments.of(new StackOverflowError(), "java.lang.StackOverflowError", null));
    }

    /** {@code factories} plus one step type, {@code type}, whose stage is {@code stage}. */
    List<StepFactory> with(String type, Stage stage) {
        List<StepFactory> all = new java.util.ArrayList<>(factories);
        all.add(factory(type, s -> stage));
        return all;
    }

    Pipeline buildWith(List<StepFactory> all, Map<String, Object> recording, Map<String, Object>... steps) {
        return new FlowBuilder(rt.runtime(), all, List.of(), Map.<String, EndpointConfig>of()).recording(store, meter)
                .build(FlowDefinition.from(flow(recording, List.of(steps))), java.nio.file.Path.of(""));
    }

    @ParameterizedTest
    @MethodSource("errors")
    void aStepThatThrowsAnErrorRecordsItsFailureSnapshot(Error thrown, String className, String message) {
        Pipeline p = buildWith(with("crash", Stage.of(e -> { throw thrown; })), Map.of("level", "all"), step("mark", "a"), step("crash", "x"));
        Envelope in = in();
        Throwable escaped = assertThrows(Throwable.class, () -> p.apply(in));
        assertSame(thrown, escaped, className + ": the error goes on unchanged, not wrapped");
        List<Snapshot> t = store.timeline(in.id()).await();
        assertEquals(List.of("ingress", "a", "x"), names(t), className + ": the step that threw an Error has its row (D-111)");
        assertEquals(Snapshot.Outcome.FAILED, t.get(2).outcome(), className);
        String error = t.get(2).error().orElse("");
        assertTrue(error.contains(className), className + ": the row names what failed: " + error);
        if (message != null) assertTrue(error.contains(message), className + ": and its message: " + error);
        assertEquals("yes", t.get(2).headers().get("seen-a"), className + ": the failed step's snapshot shows what it was given");
        assertEquals(List.of(Snapshot.Outcome.OK, Snapshot.Outcome.OK), List.of(t.get(0).outcome(), t.get(1).outcome()), className + ": the rows before it are untouched");
        assertTrue(t.get(0).error().isEmpty() && t.get(1).error().isEmpty(), className + ": and carry no error");
    }

    /** Neither an {@link Exception} nor an {@link Error}: what only a {@code Throwable} catch records (D-111 AMENDED). */
    static final class DirectThrowable extends Throwable {
        DirectThrowable(String message) { super(message); }
    }

    @Test
    void aStepThatThrowsADirectThrowableSubclassIsRecordedToo() {
        DirectThrowable thrown = new DirectThrowable("neither exception nor error");
        Pipeline p = buildWith(with("odd", Stage.of(e -> { throw sneaky(thrown); })), Map.of("level", "all"), step("mark", "a"), step("odd", "x"));
        Envelope in = in();
        Throwable escaped = assertThrows(Throwable.class, () -> p.apply(in));
        assertSame(thrown, escaped, "the throwable goes on unchanged, not wrapped");
        List<Snapshot> t = store.timeline(in.id()).await();
        assertEquals(List.of("ingress", "a", "x"), names(t), "a direct Throwable subclass leaves the step's row too: the catch is Throwable, not Exception | Error");
        assertEquals(Snapshot.Outcome.FAILED, t.get(2).outcome());
        String error = t.get(2).error().orElse("");
        assertTrue(error.contains("DirectThrowable") && error.contains("neither exception nor error"), "the row says what failed: " + error);
        assertEquals("yes", t.get(2).headers().get("seen-a"), "the failed step's snapshot shows what it was given");
    }

    /**
     * A {@link MessageStore} decorator that logs every {@code record} call before passing it on — what reaches the store,
     * not what survives in it. The delegate keeps only the first row per {@code (id, step)} (F-29), so a second row for a step
     * is invisible to {@code timeline()}; only a count of the calls can see it. The approach of {@code HoldingMessageStore#recorded}
     * (RedeliveredTimelineTest), which is package-private in {@code core.flow}.
     */
    static final class CountingStore implements MessageStore {
        private final MessageStore delegate;
        private final List<Snapshot> handed = new java.util.ArrayList<>();   // guarded by this

        CountingStore(MessageStore delegate) { this.delegate = delegate; }

        /** The outcomes of every row handed to the store for {@code messageId} at {@code stepName}, in call order. */
        synchronized List<Snapshot.Outcome> handed(String messageId, String stepName) {
            return handed.stream().filter(s -> s.messageId().equals(messageId) && s.stepName().equals(stepName)).map(Snapshot::outcome).toList();
        }

        @Override public Future<Void> record(Snapshot s) {
            synchronized (this) { handed.add(s); }
            return delegate.record(s);
        }
        @Override public Future<List<Snapshot>> timeline(String messageId) { return delegate.timeline(messageId); }
        @Override public Future<Page<String>> findByKey(String name, String value, Optional<String> after, int limit) { return delegate.findByKey(name, value, after, limit); }
    }

    @Test
    void anErrorAfterTheStepHandedOnLeavesNoSecondRow() {
        AssertionError after = new AssertionError("after handing on");
        AssertionError before = new AssertionError("before handing on");
        List<StepFactory> all = new java.util.ArrayList<>(factories);
        all.add(factory("late", s -> (e, rest) -> { rest.apply(e.withHeader("handed", "yes")); throw after; }));
        all.add(factory("early", s -> (e, rest) -> { throw before; }));
        CountingStore counting = new CountingStore(store);
        Function<Map<String, Object>, Pipeline> flowWith = x -> new FlowBuilder(rt.runtime(), all, List.of(), Map.<String, EndpointConfig>of()).recording(counting, meter)
                .build(FlowDefinition.from(flow(Map.of("level", "all"), List.of(step("mark", "a"), x, step("mark", "b")))), java.nio.file.Path.of(""));

        Envelope witness = in();
        Throwable early = assertThrows(Throwable.class, () -> flowWith.apply(step("early", "x")).apply(witness));
        assertSame(before, early);
        List<Snapshot> w = store.timeline(witness.id()).await();
        assertEquals(List.of("ingress", "a", "x"), names(w), "witness: the same Error before any hand-on does leave a row, so the absence below is the guard's");
        assertEquals(Snapshot.Outcome.FAILED, w.get(2).outcome());
        assertEquals(List.of(Snapshot.Outcome.FAILED), counting.handed(witness.id(), "x"), "witness: the counter sees the FAILED row the Error before any hand-on writes");

        Envelope in = in();
        Throwable escaped = assertThrows(Throwable.class, () -> flowWith.apply(step("late", "x")).apply(in));
        assertSame(after, escaped, "the error goes on unchanged");
        assertEquals(List.of(Snapshot.Outcome.OK), counting.handed(in.id(), "x"),
                "step x handed the store exactly one row, the OK one its hand-on wrote: a second row was written for step x if FAILED follows it"
                        + " (the store keeps the first per (id, step), so timeline() alone cannot show it, F-29)");
        assertEquals(List.of(Snapshot.Outcome.OK), counting.handed(in.id(), "b"), "the step x handed on to recorded itself once");
        List<Snapshot> t = store.timeline(in.id()).await();
        assertEquals(List.of("ingress", "a", "x", "b"), names(t), "one row for step x, the OK one its hand-on wrote");
        assertEquals(Snapshot.Outcome.OK, t.get(2).outcome());
        assertEquals("yes", t.get(2).headers().get("handed"), "x's row shows what it handed on");
        assertTrue(t.stream().allMatch(s -> s.outcome() == Snapshot.Outcome.OK && s.error().isEmpty()), "no row of this run carries the error");
    }

    @Test
    void aPolicyThatDoesNotRecordFailuresStillLetsAnErrorEscape() {
        AssertionError thrown = new AssertionError("not recorded, still thrown");
        List<StepFactory> all = with("crash", Stage.of(e -> { throw thrown; }));

        Envelope recorded = in();
        Throwable control = assertThrows(Throwable.class, () -> buildWith(all, Map.of("level", "errors"), step("mark", "a"), step("crash", "x")).apply(recorded));
        assertSame(thrown, control);
        List<Snapshot> r = store.timeline(recorded.id()).await();
        assertEquals(List.of(2), r.stream().map(Snapshot::step).toList(), "control: under errors the same Error leaves exactly the failed step's row");
        assertEquals(Snapshot.Outcome.FAILED, r.get(0).outcome());

        Envelope in = in();
        Throwable escaped = assertThrows(Throwable.class, () -> buildWith(all, Map.of("level", "errors"), step("mark", "a"),
                Map.of("type", "crash", "name", "x", "recording", Map.of("level", "off"))).apply(in));
        assertSame(thrown, escaped, "a step that records nothing still lets the Error go on unchanged: handling does not depend on recording");
        assertEquals(List.of(), store.timeline(in.id()).await(), "and records nothing: recording does not depend on handling");
    }

    /** An {@link AssertionError} whose text cannot be built: recording its failure row throws {@code recordingFailure} (FIX-140 trigger a). */
    static final class HostileError extends AssertionError {
        final RuntimeException recordingFailure = new IllegalStateException("toString exploded");
        HostileError() { super("hostile"); }
        @Override public String toString() { throw recordingFailure; }
    }

    /** The same, for an {@link Exception}. */
    static final class HostileException extends RuntimeException {
        final RuntimeException recordingFailure = new IllegalStateException("toString exploded");
        HostileException() { super("hostile"); }
        @Override public String toString() { throw recordingFailure; }
    }

    @Test
    void aRecordingThatThrowsStillLetsTheOriginalErrorEscapeUnchanged() {
        HostileError thrown = new HostileError();
        Pipeline p = buildWith(with("crash", Stage.of(e -> { throw thrown; })), Map.of("level", "all"), step("mark", "a"), step("crash", "x"));
        Envelope in = in();
        Throwable escaped = assertThrows(Throwable.class, () -> p.apply(in));
        assertSame(thrown, escaped, "the Error that left the step is what leaves the recorder, not the failure of recording it (D-111): an Exception here would be parked as transient");
        assertTrue(java.util.Arrays.asList(escaped.getSuppressed()).contains(thrown.recordingFailure),
                "the recording failure is not discarded: it is among the suppressed of the original: " + java.util.Arrays.toString(escaped.getSuppressed()));
    }

    /** This WARNING is also the only report a throwable built with suppression disabled gets: its {@code addSuppressed} is a no-op, so the suppressed-list assertions of the cases around this one do not cover that path. */
    @Test
    void aRecordingThatThrowsIsLoggedOnceWithTheMessageAndStep() {
        java.util.logging.Logger core = java.util.logging.Logger.getLogger("io.flottila.core");   // a strong reference: JUL holds loggers weakly
        List<java.util.logging.LogRecord> logged = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.logging.Handler capture = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord r) { logged.add(r); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        core.addHandler(capture);
        try {
            List<StepFactory> all = new java.util.ArrayList<>(with("fine", Stage.of(e -> { throw new IllegalStateException("readable"); })));
            all.add(factory("hostile", s -> Stage.of(e -> { throw new HostileError(); })));
            Function<String, List<String>> recorderWarnings = id -> logged.stream()
                    .filter(r -> r.getLevel() == java.util.logging.Level.WARNING && Recorder.class.getName().equals(r.getLoggerName()))
                    .map(r -> r.getMessage() + (r.getThrown() == null ? "" : " / " + r.getThrown()))
                    .filter(m -> m.contains(id)).toList();

            Envelope witness = in();
            assertThrows(IllegalStateException.class, () -> buildWith(all, Map.of("level", "all"), step("mark", "a"), step("fine", "readable-step")).apply(witness));
            assertEquals(Snapshot.Outcome.FAILED, store.timeline(witness.id()).await().getLast().outcome(), "witness: this step's failure was recorded");
            assertEquals(List.of(), recorderWarnings.apply(witness.id()), "witness: a failure that is recorded logs nothing from the recorder");

            Envelope in = in();
            assertThrows(Throwable.class, () -> buildWith(all, Map.of("level", "all"), step("mark", "a"), step("hostile", "crashy-step")).apply(in));
            List<String> warned = recorderWarnings.apply(in.id());
            assertEquals(1, warned.size(), "a FAILED row that could not be recorded is logged exactly once at WARNING, naming the message: " + warned);
            assertTrue(warned.getFirst().contains("crashy-step"), "the WARNING names the step: " + warned);
        } finally {
            core.removeHandler(capture);
        }
    }

    @Test
    void aFailureWhoseToStringThrowsStillLeavesARowOrNoneButNeverADifferentThrowable() {
        HostileException thrown = new HostileException();
        CountingStore counting = new CountingStore(store);
        List<StepFactory> all = with("odd", Stage.of(e -> { throw thrown; }));
        all.add(factory("plain", s -> Stage.of(e -> { throw new IllegalStateException("plain failure"); })));
        Function<Map<String, Object>, Pipeline> flowWith = x -> new FlowBuilder(rt.runtime(), all, List.of(), Map.<String, EndpointConfig>of()).recording(counting, meter)
                .build(FlowDefinition.from(flow(Map.of("level", "all"), List.of(step("mark", "a"), x))), java.nio.file.Path.of(""));

        Envelope witness = in();
        assertThrows(IllegalStateException.class, () -> flowWith.apply(step("plain", "x")).apply(witness));
        assertEquals(List.of(Snapshot.Outcome.FAILED), counting.handed(witness.id(), "x"), "witness: a failure whose text can be built is handed to the store as FAILED");

        Envelope in = in();
        Throwable escaped = assertThrows(Throwable.class, () -> flowWith.apply(step("odd", "x")).apply(in));
        assertSame(thrown, escaped, "the exception that left the step leaves the recorder, not the failure of building its text");
        assertTrue(java.util.Arrays.asList(escaped.getSuppressed()).contains(thrown.recordingFailure), "the failure of building the text is suppressed on it: " + java.util.Arrays.toString(escaped.getSuppressed()));
        assertEquals(List.of(), counting.handed(in.id(), "x"), "no row for the step whose failure has no text: none is invented (no placeholder)");
        assertEquals(List.of("ingress", "a"), names(store.timeline(in.id()).await()), "the rows before it stand, and the step's is absent");
    }

    /** Its text cannot be built, and the failure of building it is itself: the recording failure and the original are one object. */
    static final class SelfThrowing extends AssertionError {
        @Override public String toString() { throw this; }
    }

    @Test
    void aFailureWhoseToStringThrowsItselfStillEscapesUnchanged() {
        SelfThrowing thrown = new SelfThrowing();
        Pipeline p = buildWith(with("self", Stage.of(e -> { throw thrown; })), Map.of("level", "all"), step("mark", "a"), step("self", "x"));
        Envelope in = in();
        Throwable escaped = assertThrows(Throwable.class, () -> p.apply(in));
        assertTrue(!(escaped instanceof IllegalArgumentException), "the recorder must not leave with the IllegalArgumentException of suppressing a throwable into itself, but with the original: " + escaped.getClass().getName());
        assertTrue(escaped.getMessage() == null || !escaped.getMessage().contains("Self-suppression"), "no self-suppression failure replaces the original: " + escaped.getClass().getName());
        assertSame(thrown, escaped, "the original escapes by identity: " + escaped.getClass().getName());
        assertEquals(0, escaped.getSuppressed().length, "nothing is attached: the recording failure is the original itself, and attaching it to itself is what throws");
    }

    /** An {@link AssertionError} whose text cannot be built: {@code toString} throws {@code recordingFailure}, checked or not, as an interrupted {@code await} does (D-056). */
    static final class UnprintableError extends AssertionError {
        final Throwable recordingFailure;
        UnprintableError(Throwable recordingFailure) { super("unprintable"); this.recordingFailure = recordingFailure; }
        @Override public String toString() { throw sneaky(recordingFailure); }
    }

    @Test
    void aRecordingInterruptedByACheckedExceptionStillLetsTheOriginalEscapeWithItSuppressed() {
        InterruptedException interrupted = new InterruptedException("stop deadline");
        UnprintableError thrown = new UnprintableError(interrupted);
        Pipeline p = buildWith(with("crash", Stage.of(e -> { throw thrown; })), Map.of("level", "all"), step("mark", "a"), step("crash", "x"));
        Envelope in = in();
        Throwable escaped = assertThrows(Throwable.class, () -> p.apply(in));
        assertSame(thrown, escaped, "the original leaves, not the checked InterruptedException thrown sneakily while recording: " + escaped.getClass().getName());
        assertTrue(java.util.Arrays.asList(escaped.getSuppressed()).contains(interrupted),
                "the InterruptedException is among the suppressed of the original (a catch narrower than Throwable lets it through): " + java.util.Arrays.toString(escaped.getSuppressed()));
    }

    @Test
    void aRecordingThatFailsWithAnErrorStillLetsTheOriginalEscapeWithItSuppressed() {
        StackOverflowError overflow = new StackOverflowError("while recording");
        UnprintableError thrown = new UnprintableError(overflow);
        Pipeline p = buildWith(with("crash", Stage.of(e -> { throw thrown; })), Map.of("level", "all"), step("mark", "a"), step("crash", "x"));
        Envelope in = in();
        Throwable escaped = assertThrows(Throwable.class, () -> p.apply(in));
        assertSame(thrown, escaped, "the original leaves, not the Error thrown while recording: " + escaped.getClass().getName());
        assertTrue(java.util.Arrays.asList(escaped.getSuppressed()).contains(overflow),
                "the Error is among the suppressed of the original (a catch of Exception only lets it through): " + java.util.Arrays.toString(escaped.getSuppressed()));
    }
}
