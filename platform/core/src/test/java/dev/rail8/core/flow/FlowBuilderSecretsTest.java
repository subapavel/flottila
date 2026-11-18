package dev.rail8.core.flow;

import dev.rail8.api.Cache;
import dev.rail8.api.EndpointConfig;
import dev.rail8.api.Envelope;
import dev.rail8.api.Fault;
import dev.rail8.api.Headers;
import dev.rail8.api.NackException;
import dev.rail8.api.Pipeline;
import dev.rail8.api.Secret;
import dev.rail8.api.Services;
import dev.rail8.api.Stage;
import dev.rail8.api.StepConfig;
import dev.rail8.api.StepFactory;
import dev.rail8.api.StepResources;
import dev.rail8.core.cache.InMemoryCache;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.dlq.ParkedException;
import dev.rail8.core.node.FencedException;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.core.record.Snapshot;
import dev.rail8.core.record.StepLog;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The secrets and the cache {@link FlowBuilder} gives the steps a factory builds. A factory takes the secret an option names
 * through {@link Services#secret}; what a node refuses (an absent option, a literal, a malformed reference, one that is not
 * set) is a build error naming the step and the key, which never shows a literal, and a secret is taken only while the flow
 * builds. Every whole string {@code secret://...} in any step's config is resolved while the flow builds. A failure of a step a
 * factory built, in a flow that took a secret, leaves with each value replaced by its reference: in what the pipeline throws,
 * in the recorded step and in the parked row, its retry answer, kind, causes and stack frames kept; so does each line such a step
 * logged that its recorded step keeps. Steps of one type share
 * the cache their type is given.
 */
class FlowBuilderSecretsTest {

    static final String VALUE = "hmac-key-0123456789";
    static final String REFERENCE = "secret://partner-hmac";

    final FakeRuntime rt = FakeRuntime.create();
    final Map<String, String> env = new HashMap<>(Map.of(REFERENCE, VALUE));
    final Function<String, String> source = reference -> {
        String v = env.get(reference);
        if (v == null) throw new IllegalArgumentException("secret '" + reference + "' is not set (RAIL8_SECRET_" + reference.substring(9).toUpperCase() + ")");
        return v;
    };
    final InMemoryMessageStore store = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();

    /** A factory of {@code type} that takes the secret its option {@code key} names and builds the stage {@code make} gives. */
    static StepFactory taking(String type, Function<Secret, Stage> make) {
        return factory(type, (step, r) -> make.apply(r.services().secret(step, "key")));
    }

    interface Make {
        Stage create(StepConfig step, StepResources resources);
    }

    static StepFactory factory(String type, Make make) {
        return new StepFactory() {
            @Override public String type() { return type; }
            @Override public String configSchema() { return "{}"; }
            @Override public Stage create(StepConfig step, StepResources res) { return make.create(step, res); }
        };
    }

    static Map<String, Object> flow(String mode, List<Map<String, Object>> steps) {
        return Map.of("schemaVersion", 1, "kind", "flow", "id", "f", "version", "1.0.0", "from", Map.of("endpoint", "in"),
                "delivery", Map.of("mode", mode), "limits", Map.of("maxBodyBytes", 4096), "steps", steps);
    }

    static Map<String, Object> step(String type, String name, Object key) {
        return Map.of("type", type, "name", name, "key", key);
    }

    FlowBuilder builder(StepFactory... factories) {
        return new FlowBuilder(rt.runtime(), List.of(factories), List.of(), Map.<String, EndpointConfig>of()).secrets(source).recording(store, () -> 0).dlq(dlq);
    }

    Pipeline build(FlowBuilder builder, String mode, List<Map<String, Object>> steps) {
        return builder.build(FlowDefinition.from(flow(mode, steps)), Path.of(""));
    }

    Envelope in() { return rt.ingress("f", Headers.empty(), Buffer.buffer("<in/>")); }

    /** A factory whose stage throws what {@code failure} makes of the secret's value. */
    static StepFactory leaking(Function<String, Throwable> failure) {
        return taking("leak", secret -> Stage.of(e -> { throw FlowBuilderSecretsTest.<RuntimeException>undeclared(failure.apply(secret.value())); }));
    }

    /** A failure the sender caused. */
    static final class Invalid extends RuntimeException implements Fault {
        Invalid(String message) { super(message); }
        @Override public Kind kind() { return Kind.INVALID; }
    }

    @SuppressWarnings("unchecked")
    static <T extends Throwable> T undeclared(Throwable t) throws T {
        throw (T) t;
    }

    @Test
    void aFactoryTakesTheSecretItsOptionNamesAndReadsTheValueWhenItRuns() {
        List<Secret> taken = new ArrayList<>();
        Pipeline p = build(builder(taking("sign", secret -> {
            taken.add(secret);
            return Stage.of(e -> e.withHeader("signed-with", secret.toString()).withHeader("value-length", String.valueOf(secret.value().length())));
        })), "sync", List.of(step("sign", "sign", REFERENCE)));
        assertEquals(REFERENCE, taken.getFirst().reference());
        env.put(REFERENCE, "rotated");
        Envelope out = p.apply(in());
        assertEquals(REFERENCE, out.header("signed-with").orElseThrow());
        assertEquals("7", out.header("value-length").orElseThrow(), "the value is read when the step needs it");
    }

    @Test
    void whatANodeRefusesIsABuildErrorNamingTheStepAndTheKey() {
        FlowBuilder builder = builder(taking("sign", secret -> Stage.of(e -> e)));
        for (Object option : new Object[]{42, "a-literal-password", "secret://Not-Well-Formed", "secret://not-set"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> build(builder, "sync", List.of(step("sign", "sign", option))), option.toString());
            assertTrue(e.getMessage().contains("step 'sign'") && e.getMessage().contains("'key'"), e.getMessage());
            assertFalse(e.getMessage().contains("a-literal-password"), "a literal is never shown: " + e.getMessage());
        }
        IllegalArgumentException absent = assertThrows(IllegalArgumentException.class, () -> build(builder, "sync", List.of(Map.of("type", "sign", "name", "sign"))));
        assertTrue(absent.getMessage().contains("step 'sign'") && absent.getMessage().contains("'key'"), absent.getMessage());
        IllegalArgumentException unset = assertThrows(IllegalArgumentException.class, () -> build(builder, "sync", List.of(step("sign", "sign", "secret://not-set"))));
        assertTrue(unset.getMessage().contains("RAIL8_SECRET_NOT-SET"), "the source's refusal names where it looked: " + unset.getMessage());
    }

    @Test
    void withoutASourceEveryReferenceIsRefused() {
        FlowBuilder bare = new FlowBuilder(rt.runtime(), List.of(taking("sign", secret -> Stage.of(e -> e))), List.of(), Map.of());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> build(bare, "sync", List.of(step("sign", "sign", REFERENCE))));
        assertTrue(e.getMessage().contains(REFERENCE) && e.getMessage().contains("step 'sign'"), e.getMessage());
    }

    @Test
    void aSecretIsTakenOnlyWhileTheFlowBuilds() {
        AtomicReference<Services> kept = new AtomicReference<>();
        Pipeline p = build(builder(factory("late", (step, r) -> {
            kept.set(r.services());
            return Stage.of(e -> e.withHeader("late", r.services().secret(step, "key").reference()));
        })), "sync", List.of(step("late", "late", REFERENCE)));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> p.apply(in()));
        assertTrue(e.getMessage().contains("step 'late'") && e.getMessage().contains("while the flow builds"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> kept.get().secret(new StepConfig(1, "late", "late", false, Map.of("key", REFERENCE)), "key"));
    }

    @Test
    void everySecretReferenceInAnyStepsConfigIsResolvedWhileTheFlowBuilds() {
        FlowBuilder builder = builder(factory("mark", (step, r) -> Stage.of(e -> e)));
        Map<String, Object> nested = Map.of("type", "mark", "name", "m", "options", Map.of("list", List.of("plain", "secret://not-set")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> build(builder, "sync", List.of(nested)));
        assertTrue(e.getMessage().contains("step 'm'") && e.getMessage().contains("'options.list[1]'") && e.getMessage().contains("secret://not-set"), e.getMessage());
        assertEquals(1, build(builder, "sync", List.of(Map.of("type", "mark", "name", "m", "options", Map.of("list", List.of(REFERENCE)))))
                .apply(in()).step(), "a reference that is set builds");
        assertEquals(1, build(builder, "sync", List.of(Map.of("type", "mark", "name", "m", "note", "see secret://not-set for details"))).apply(in()).step(),
                "a string that merely contains a reference is not one");
    }

    @Test
    void aFailureThatHoldsAValueLeavesWithItsReferenceAndIsRecordedSo() {
        IllegalStateException original = new IllegalStateException("the partner refused key " + VALUE);
        Pipeline p = build(builder(leaking(value -> original)), "sync", List.of(step("leak", "leak", REFERENCE)));
        Envelope in = in();
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> p.apply(in));
        assertEquals("java.lang.IllegalStateException: the partner refused key " + REFERENCE, thrown.toString());
        assertFalse(thrown instanceof Fault, "an unclassified failure stays unclassified");
        assertArrayEquals(original.getStackTrace(), thrown.getStackTrace(), "its stack frames are kept");
        Snapshot failed = store.timeline(in.id()).await().getLast();
        assertEquals(Snapshot.Outcome.FAILED, failed.outcome());
        assertEquals("java.lang.IllegalStateException: the partner refused key " + REFERENCE, failed.error().orElseThrow());
    }

    @Test
    void aLineAStepLoggedIsRecordedWithEachValueReplacedByItsReference() {
        System.Logger log = System.getLogger("partner.client");
        Pipeline p = build(builder(taking("sign", secret -> Stage.of(e -> {
            log.log(System.Logger.Level.WARNING, "signing with " + secret.value(), new IllegalStateException("refused " + secret.value()));
            return e;
        }))), "sync", List.of(step("sign", "sign", REFERENCE)));
        Envelope in = in();
        p.apply(in);
        StepLog.Line line = store.timeline(in.id()).await().getLast().log().lines().getFirst();
        assertEquals("signing with " + REFERENCE, line.text());
        assertEquals(Optional.of("java.lang.IllegalStateException: refused " + REFERENCE), line.thrown());
    }

    @Test
    void aParkedFailureHoldsTheReference() {
        Pipeline p = build(builder(leaking(value -> new IllegalStateException("refused " + value))), "at-least-once", List.of(step("leak", "leak", REFERENCE)));
        ParkedException parked = assertThrows(ParkedException.class, () -> p.apply(in()));
        assertFalse(parked.parked().error().contains(VALUE), parked.parked().error());
        assertTrue(parked.parked().error().contains(REFERENCE), parked.parked().error());
        assertFalse(parked.getCause().getMessage().contains(VALUE));
    }

    @Test
    void theRetryAnswerAndTheKindAreKept() {
        NackException nack = assertInstanceOf(NackException.class, assertThrows(RuntimeException.class,
                () -> build(builder(leaking(value -> new NackException("401 for " + value, true))), "sync", List.of(step("leak", "leak", REFERENCE))).apply(in())));
        assertTrue(nack.retryable());
        assertEquals("401 for " + REFERENCE, nack.reason());
        Fault invalid = assertInstanceOf(Fault.class, assertThrows(RuntimeException.class,
                () -> build(builder(leaking(value -> new Invalid("bad " + value))), "sync", List.of(step("leak", "leak", REFERENCE))).apply(in())));
        assertEquals(Fault.Kind.INVALID, invalid.kind());
        assertEquals(Invalid.class.getName() + ": bad " + REFERENCE, invalid.toString());
    }

    @Test
    void theCausesAndTheSuppressedFailuresAreScrubbedLinkByLink() {
        RuntimeException root = new RuntimeException("root holds " + VALUE);
        IOException checked = new IOException("the call failed", root);
        checked.addSuppressed(new IllegalStateException("cleanup saw " + VALUE));
        Throwable thrown = assertThrows(Throwable.class,
                () -> build(builder(leaking(value -> checked)), "sync", List.of(step("leak", "leak", REFERENCE))).apply(in()));
        assertEquals("java.io.IOException: the call failed", thrown.toString());
        assertEquals("java.lang.RuntimeException: root holds " + REFERENCE, thrown.getCause().toString());
        assertArrayEquals(root.getStackTrace(), thrown.getCause().getStackTrace());
        assertEquals("java.lang.IllegalStateException: cleanup saw " + REFERENCE, thrown.getSuppressed()[0].toString());
    }

    @Test
    void anErrorAnInterruptAFenceAndAStoppedRunLeaveAsThrown() {
        AssertionError error = new AssertionError("error holds " + VALUE);
        assertSame(error, assertThrows(AssertionError.class,
                () -> build(builder(leaking(value -> error)), "sync", List.of(step("leak", "leak", REFERENCE))).apply(in())));
        InterruptedException interrupt = new InterruptedException("interrupt holds " + VALUE);
        assertSame(interrupt, assertThrows(InterruptedException.class,
                () -> build(builder(leaking(value -> interrupt)), "sync", List.of(step("leak", "leak", REFERENCE))).apply(in())));
        FencedException fenced = new FencedException("fenced while holding " + VALUE);
        assertSame(fenced, assertThrows(FencedException.class,
                () -> build(builder(leaking(value -> fenced)), "sync", List.of(step("leak", "leak", REFERENCE))).apply(in())));
        RunStoppedException stopped = new RunStoppedException(new Runs.Cancelled("operator", Optional.of("holds " + VALUE)), null);
        assertSame(stopped, assertThrows(RunStoppedException.class,
                () -> build(builder(leaking(value -> stopped)), "sync", List.of(step("leak", "leak", REFERENCE))).apply(in())));
    }

    @Test
    void aFailureThatHoldsNoValueLeavesAsThrown() {
        IllegalStateException clean = new IllegalStateException("nothing secret here");
        assertSame(clean, assertThrows(IllegalStateException.class,
                () -> build(builder(leaking(value -> clean)), "sync", List.of(step("leak", "leak", REFERENCE))).apply(in())));
    }

    @Test
    void aFlowThatTookNoSecretLeavesFailuresAsThrown() {
        IllegalStateException holds = new IllegalStateException("holds " + VALUE);
        Pipeline p = build(builder(factory("boom", (step, r) -> Stage.of(e -> { throw holds; }))), "sync", List.of(Map.of("type", "boom", "name", "b")));
        assertSame(holds, assertThrows(IllegalStateException.class, () -> p.apply(in())), "no value was handed out, so none is known");
    }

    @Test
    void aFailureFromTheRestOfThePipelineLeavesAsItCame() {
        FlowSecrets secrets = new FlowSecrets(source);
        secrets.services(new StepConfig(1, "sign", "sign", false, Map.of("key", REFERENCE)), Services.NONE.cache())
                .secret(new StepConfig(1, "sign", "sign", false, Map.of("key", REFERENCE)), "key");
        IllegalStateException downstream = new IllegalStateException("downstream holds " + VALUE);
        Stage handsOn = secrets.scrubbing((e, rest) -> rest.apply(e));
        assertSame(downstream, assertThrows(IllegalStateException.class, () -> handsOn.apply(in(), e -> { throw downstream; })),
                "a later step's failure is that step's to scrub");
        Stage wraps = secrets.scrubbing((e, rest) -> {
            try {
                return rest.apply(e);
            } catch (IllegalStateException x) {
                throw new IllegalStateException("wrapped", x);
            }
        });
        Throwable own = assertThrows(RuntimeException.class, () -> wraps.apply(in(), e -> { throw downstream; }));
        assertEquals("java.lang.IllegalStateException: wrapped", own.toString(), "a failure of its own that carries one is a copy");
        assertNotSame(downstream, own.getCause());
        assertEquals("java.lang.IllegalStateException: downstream holds " + REFERENCE, own.getCause().toString());
    }

    @Test
    void stepsOfOneTypeShareTheCacheTheirTypeIsGiven() {
        Map<String, Cache> byType = new HashMap<>();
        Map<String, Cache> seen = new HashMap<>();
        StepFactory a = factory("a", (step, r) -> { seen.put(step.name(), r.services().cache()); return Stage.of(e -> e); });
        StepFactory b = factory("b", (step, r) -> { seen.put(step.name(), r.services().cache()); return Stage.of(e -> e); });
        InMemoryCache node = new InMemoryCache(rt.clock(), 10, 10_000);
        FlowBuilder builder = builder(a, b).caches(type -> byType.computeIfAbsent(type, t -> node.scope("default", "invoice", t)));
        build(builder, "sync", List.of(Map.of("type", "a", "name", "a1"), Map.of("type", "a", "name", "a2"), Map.of("type", "b", "name", "b1")));
        assertEquals(Map.of("a1", byType.get("a"), "a2", byType.get("a"), "b1", byType.get("b")), seen);
        assertNotSame(byType.get("a"), byType.get("b"));
        Map<String, Cache> unset = new HashMap<>();
        build(new FlowBuilder(rt.runtime(), List.of(factory("a", (step, r) -> { unset.put(step.name(), r.services().cache()); return Stage.of(e -> e); })),
                List.of(), Map.of()), "sync", List.of(Map.of("type", "a", "name", "a1")));
        assertSame(Services.NONE.cache(), unset.get("a1"), "without caches a step's cache keeps nothing");
    }
}
