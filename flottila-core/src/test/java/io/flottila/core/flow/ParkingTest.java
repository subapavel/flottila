package io.flottila.core.flow;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobStore;
import io.flottila.api.BodyTooLargeException;
import io.flottila.api.EndpointConfig;
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
import io.flottila.core.dlq.Parking;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Page;
import io.flottila.core.record.Snapshot;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.InMemoryBlobStore;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Failure per delivery mode (ARCHITECTURE §4 lifecycle table, §6 retry): a sync flow answers the caller with the error, an
 * at-most-once flow drops, an at-least-once flow parks the message in the DLQ by reference — durably, before the failure
 * surfaces as a {@link ParkedException} the consumer acknowledges on — and never silently.
 */
class ParkingTest {

    final FakeRuntime rt = FakeRuntime.create();
    final InMemoryDlq dlq = new InMemoryDlq();
    final InMemoryMessageStore store = new InMemoryMessageStore();

    static StepFactory factory(String type, Function<StepConfig, Stage> make) {
        return new StepFactory() {
            @Override public String type() { return type; }
            @Override public String configSchema() { return "{}"; }
            @Override public Stage create(StepConfig step, StepResources res) { return make.apply(step); }
        };
    }

    final List<StepFactory> factories = List.of(
            factory("mark", s -> Stage.of(e -> e.withHeader("seen-" + s.name(), "yes").withKey("k", "v"))),
            factory("boom", s -> Stage.of(e -> { throw new IllegalStateException("boom at " + s.name()); })));

    static Map<String, Object> flow(String mode, List<Map<String, Object>> steps) {
        return Map.of("schemaVersion", 1, "kind", "flow", "id", "f", "version", "1.0.0", "from", Map.of("endpoint", "in"),
                "delivery", Map.of("mode", mode), "limits", Map.of("maxBodyBytes", 4096), "steps", steps);
    }

    static final List<Map<String, Object>> MARK_THEN_BOOM = List.of(Map.of("type", "mark", "name", "a"), Map.of("type", "boom", "name", "x"), Map.of("type", "mark", "name", "never"));

    FlowBuilder builder(Dlq dlq) {
        FlowBuilder b = new FlowBuilder(rt.runtime(), factories, List.of(), Map.<String, EndpointConfig>of()).recording(store, () -> 0);
        return dlq == null ? b : b.dlq(dlq);
    }

    Envelope in() { return rt.ingress("f", Headers.empty().with("x", "1"), Buffer.buffer("<in/>")); }

    @Test
    void anAtLeastOnceFlowParksTheFailedStepsInputByReferenceAndThrowsParked() {
        Pipeline p = builder(dlq).build(FlowDefinition.from(flow("at-least-once", MARK_THEN_BOOM)), Path.of(""));
        Envelope in = in();
        ParkedException e = assertThrows(ParkedException.class, () -> p.apply(in));
        Parked parked = e.parked();
        assertEquals(in.id(), parked.messageId());
        assertEquals("f", parked.flow());
        assertEquals(2, parked.step(), "the step that failed");
        assertEquals("x", parked.stepName());
        assertTrue(parked.error().contains("boom at x"), parked.error());
        assertEquals(FakeRuntime.T0, parked.parkedAt());
        assertEquals("yes", parked.headers().get("seen-a"), "the failed step's input: step a's output");
        assertEquals("v", parked.keys().get("k"));
        assertEquals(in.body().hash(), parked.bodyHash());
        assertEquals(in.body().size(), parked.bodySize());
        assertEquals(in.body().key(), parked.bodyKey(), "by reference, whatever the recording payload setting");
        assertEquals(Map.of("flow", "f@1.0.0"), parked.versions());
        assertTrue(e.getCause() instanceof IllegalStateException && e.getCause().getMessage().equals("boom at x"), "the failure travels as the cause");
        assertEquals(List.of(parked), dlq.list(Optional.empty(), Page.MAX_LIMIT).await().items(), "durable before the exception surfaces");
        List<Snapshot> t = store.timeline(in.id()).await();
        assertEquals(List.of("ingress", "a", "x"), t.stream().map(Snapshot::stepName).toList(), "the FAILED snapshot is recorded as well");
        assertEquals(Snapshot.Outcome.FAILED, t.get(2).outcome());
    }

    @Test
    void aSyncFlowAndAnAtMostOnceFlowLetTheFailureReachTheConsumerAndNeitherParks() {
        Envelope in = in();
        IllegalStateException sync = assertThrows(IllegalStateException.class, () -> builder(dlq).build(FlowDefinition.from(flow("sync", MARK_THEN_BOOM)), Path.of("")).apply(in));
        assertEquals("boom at x", sync.getMessage());
        IllegalStateException atMostOnce = assertThrows(IllegalStateException.class, () -> builder(dlq).build(FlowDefinition.from(flow("at-most-once", MARK_THEN_BOOM)), Path.of("")).apply(in()));
        assertEquals("boom at x", atMostOnce.getMessage());
        assertEquals(List.of(), dlq.list(Optional.empty(), Page.MAX_LIMIT).await().items());
        Envelope fine = builder(dlq).build(FlowDefinition.from(flow("at-least-once", List.of(Map.of("type", "mark", "name", "a")))), Path.of("")).apply(in());
        assertEquals("yes", fine.header("seen-a").orElseThrow(), "a message that goes through is never parked");
        assertEquals(List.of(), dlq.list(Optional.empty(), Page.MAX_LIMIT).await().items());
    }

    @Test
    void anAtLeastOnceFlowWithoutADlqIsABuildErrorNeverASilentDrop() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> builder(null).build(FlowDefinition.from(flow("at-least-once", MARK_THEN_BOOM)), Path.of("")));
        assertTrue(e.getMessage().contains("at-least-once") && e.getMessage().contains("DLQ"), e.getMessage());
        Pipeline sync = builder(null).build(FlowDefinition.from(flow("sync", MARK_THEN_BOOM)), Path.of(""));
        assertEquals("boom at x", assertThrows(IllegalStateException.class, () -> sync.apply(in())).getMessage(), "a sync flow builds and runs without a DLQ");
    }

    @Test
    void aParkThatFailsSurfacesTheOriginalFailureWithTheParkFailureSuppressed() {
        Dlq broken = new Dlq() {
            @Override public Future<Void> park(Parked p) { return Future.failedFuture(new IllegalStateException("dlq down")); }
            @Override public Future<Page<Parked>> list(Optional<String> after, int limit) { return Future.succeededFuture(new Page<>(List.of(), Optional.empty())); }
            @Override public Future<Optional<Parked>> get(String id) { return Future.succeededFuture(Optional.empty()); }
            @Override public Future<Void> remove(String id) { return Future.succeededFuture(); }
        };
        Pipeline p = builder(broken).build(FlowDefinition.from(flow("at-least-once", MARK_THEN_BOOM)), Path.of(""));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> p.apply(in()));
        assertEquals("boom at x", e.getMessage(), "not a ParkedException: the message is not parked");
        assertEquals(1, e.getSuppressed().length);
        assertEquals("dlq down", e.getSuppressed()[0].getMessage());
    }

    // --- P1-09j (D-062, F-7): the parked body is made durable in its own store before the DLQ row is written

    /** The flows' max body size in the P1-09j cases. */
    static final long MAX = 4096;

    static final List<Map<String, Object>> REWRITE_THEN_BOOM = List.of(Map.of("type", "rewrite", "name", "r"), Map.of("type", "boom", "name", "x"));

    /**
     * {@code rewrite}: hands on a new body, {@code <name/>}, written into {@code into} under the key of the step's output
     * (message id, the step's number) — a body of that store ({@code body.store() == into}), whatever the runtime's store is.
     */
    static StepFactory rewrite(BlobStore into) {
        return factory("rewrite", s -> Stage.of(e -> e.next(into.write(BlobKey.body(e.id(), e.step() + 1), Buffer.buffer("<" + s.name() + "/>"), MAX).await())));
    }

    /** A builder whose {@code rewrite} steps write into {@code stepBodies}; the other step types as {@link #factories}. */
    FlowBuilder builder(Dlq dlq, BlobStore stepBodies) {
        List<StepFactory> all = new ArrayList<>(factories);
        all.add(rewrite(stepBodies));
        return new FlowBuilder(rt.runtime(), all, List.of(), Map.<String, EndpointConfig>of()).recording(store, () -> 0).dlq(dlq);
    }

    /** A store over a fresh {@link InMemoryBlobStore}: not the runtime's ({@code rt.blobs()}). */
    HoldingBlobStore bodies() {
        return new HoldingBlobStore(new InMemoryBlobStore(rt.hasher(), FakeRuntime.DEFAULT_TREE_LIMIT));
    }

    /** A stage-0 envelope of flow {@code f} whose body {@code <in/>} lives in {@code bodies}, under a fresh id. */
    Envelope ingressIn(BlobStore bodies) {
        String id = rt.ids().next();
        return Envelope.ingress(id, "f", Headers.empty().with("x", "1"), bodies.write(BlobKey.body(id, 0), Buffer.buffer("<in/>"), MAX).await());
    }

    /** Step 2's input: {@code in} after a step 1 that wrote {@code <step-1/>} into {@code bodies} (key: message id, step 1). */
    static Envelope stepTwoInput(Envelope in, BlobStore bodies) {
        return in.next(bodies.write(BlobKey.body(in.id(), 1), Buffer.buffer("<step-1/>"), MAX).await());
    }

    static final StepConfig STEP_X = new StepConfig(2, "boom", "x", false, Map.of());

    /**
     * {@code doc} recorded metadata-only: no row keeps a body, so the recorder persists nothing (D-064: without a trail it
     * persists every kept body, then records) and a park's persist is the only one these cases see.
     */
    static Map<String, Object> metadataOnly(Map<String, Object> doc) {
        Map<String, Object> m = new java.util.HashMap<>(doc);
        m.put("recording", Map.of("payload", "metadata-only"));
        return m;
    }

    Parking parking(Dlq dlq) {
        return new Parking(dlq, rt.clock(), Map.of("flow", "f@1.0.0"));
    }

    /**
     * An {@link InMemoryDlq} that notes, at every {@code park} call, whether {@code durable} held the parked body's key at that
     * moment: the order "persist completed, then the row" as the DLQ saw it, not as a flag set before the call.
     */
    static final class WatchingDlq implements Dlq {
        final InMemoryDlq rows = new InMemoryDlq();
        final List<Boolean> durableAtPark = new CopyOnWriteArrayList<>();
        private final HoldingBlobStore durable;

        WatchingDlq(HoldingBlobStore durable) { this.durable = durable; }

        @Override public Future<Void> park(Parked p) {
            durableAtPark.add(durable.persisted(p.bodyKey()));
            return rows.park(p);
        }

        @Override public Future<Page<Parked>> list(Optional<String> after, int limit) { return rows.list(after, limit); }
        @Override public Future<Optional<Parked>> get(String id) { return rows.get(id); }
        @Override public Future<Void> remove(String id) { return rows.remove(id); }

        List<Parked> all() { return rows.list(Optional.empty(), Page.MAX_LIMIT).await().items(); }
    }

    @Test
    void aParkPersistsTheParkedBodyBeforeItWritesTheRow() throws Exception {
        HoldingBlobStore bodies = bodies();
        WatchingDlq dlq = new WatchingDlq(bodies);
        Envelope e = stepTwoInput(ingressIn(bodies), bodies);
        BlobKey key = BlobKey.body(e.id(), 1);
        bodies.hold();

        Future<Parked> park = parking(dlq).park(e, STEP_X, new IllegalStateException("boom at x"));
        assertEquals(List.of(key), bodies.persists(), "the park asks the body's own store to persist the parked body, its key");
        assertEquals(1, bodies.held(), "the persist is pending");
        assertFalse(park.isComplete(), "the park waits for the persist: " + park);
        assertEquals(List.of(), dlq.durableAtPark, "no DLQ row is written while the parked body is not durable");
        assertEquals(List.of(), dlq.all());

        bodies.release();
        Parked parked = park.await(5, TimeUnit.SECONDS);   // the persist completed, then the row
        assertEquals(key, parked.bodyKey());
        assertEquals(List.of(true), dlq.durableAtPark, "the row was written once, with the body already durable");
        assertEquals(List.of(parked), dlq.all());
        assertTrue(bodies.persisted(key));
        assertEquals(List.of(key), bodies.persists(), "persisted once");
    }

    @Test
    void aFailedPersistFailsTheParkAndWritesNoRow() {
        HoldingBlobStore bodies = bodies();
        WatchingDlq dlq = new WatchingDlq(bodies);
        Envelope e = stepTwoInput(ingressIn(bodies), bodies);
        IllegalStateException down = new IllegalStateException("pg down");
        bodies.refuse(down);

        Future<Parked> park = parking(dlq).park(e, STEP_X, new IllegalStateException("boom at x"));
        assertEquals(List.of(BlobKey.body(e.id(), 1)), bodies.persists(), "the persist was asked for");
        assertTrue(park.failed(), "a persist that fails fails the park: " + park);
        assertSame(down, park.cause(), "with the persist's failure as its cause");
        assertEquals(List.of(), dlq.durableAtPark, "the DLQ was never asked to park");
        assertEquals(List.of(), dlq.all(), "no row");
    }

    @Test
    void aPersistThatThrowsFailsTheParkAndWritesNoRow() {
        HoldingBlobStore bodies = bodies();
        WatchingDlq dlq = new WatchingDlq(bodies);
        Envelope e = stepTwoInput(ingressIn(bodies), bodies);
        IllegalArgumentException thrown = new IllegalArgumentException("no object stored under " + e.body().key());
        bodies.throwNext(thrown);

        Future<Parked> park = assertDoesNotThrow(() -> parking(dlq).park(e, STEP_X, new IllegalStateException("boom at x")),
                "a persist that throws becomes a failed park, never a throw out of park");
        assertEquals(List.of(BlobKey.body(e.id(), 1)), bodies.persists(), "the persist was asked for");
        assertTrue(park.failed(), "a persist that throws fails the park: " + park);
        assertSame(thrown, park.cause(), "with what the persist threw as its cause");
        assertEquals(List.of(), dlq.durableAtPark, "the DLQ was never asked to park");
        assertEquals(List.of(), dlq.all(), "no row");
    }

    /** One way a store cannot make the parked body durable; {@code arm} sets the store up and returns what the persist fails with. */
    record Refusal(String name, Function<HoldingBlobStore, RuntimeException> arm) {}

    @Test
    void aStepWhoseBodyCannotPersistSurfacesItsFailureWithThePersistFailureSuppressed() {
        List<Refusal> refusals = List.of(
                new Refusal("above the durable bound", b -> { BodyTooLargeException t = new BodyTooLargeException(BlobKey.body("any", 1), MAX, MAX - 1); b.refuse(t); return t; }),
                new Refusal("postgres down", b -> { IllegalStateException t = new IllegalStateException("pg down"); b.refuse(t); return t; }),
                new Refusal("a persist that throws", b -> { IllegalArgumentException t = new IllegalArgumentException("no object stored"); b.throwNext(t); return t; }));
        for (Refusal r : refusals) {
            HoldingBlobStore bodies = bodies();
            WatchingDlq dlq = new WatchingDlq(bodies);
            RuntimeException refused = r.arm().apply(bodies);
            Pipeline p = builder(dlq, bodies).build(FlowDefinition.from(metadataOnly(flow("at-least-once", REWRITE_THEN_BOOM))), Path.of(""));
            Envelope in = in();

            RuntimeException surfaced = assertThrows(RuntimeException.class, () -> p.apply(in), r.name());
            assertFalse(surfaced instanceof ParkedException, r.name() + ": not parked, so not a ParkedException: " + surfaced);
            assertInstanceOf(IllegalStateException.class, surfaced, r.name());
            assertEquals("boom at x", surfaced.getMessage(), r.name() + ": the step's own failure surfaces");
            assertEquals(1, surfaced.getSuppressed().length, r.name() + ": " + List.of(surfaced.getSuppressed()));
            assertSame(refused, surfaced.getSuppressed()[0], r.name() + ": the persist's failure, suppressed");
            assertEquals(List.of(BlobKey.body(in.id(), 1)), bodies.persists(), r.name() + ": step x's input was the body to persist");
            assertEquals(List.of(), dlq.durableAtPark, r.name() + ": the DLQ was never asked to park");
        }
    }

    @Test
    void theParkedBodyIsPersistedInItsOwnStore() {
        HoldingBlobStore own = bodies();   // step r writes here; the runtime's store is rt.blobs()
        WatchingDlq dlq = new WatchingDlq(own);
        Pipeline p = builder(dlq, own).build(FlowDefinition.from(metadataOnly(flow("at-least-once", REWRITE_THEN_BOOM))), Path.of(""));
        Envelope in = in();   // the ingress body lives in the runtime's store
        BlobKey key = BlobKey.body(in.id(), 1);

        ParkedException e = assertThrows(ParkedException.class, () -> p.apply(in));
        assertEquals(key, e.parked().bodyKey(), "fixture: step x's input is step r's output");
        assertEquals(List.of(key), own.persists(), "persisted in the store the parked body lives in");
        assertTrue(own.persisted(key), "and made durable there");
        assertEquals(List.of(true), dlq.durableAtPark, "before the row was written");
        assertFalse(rt.blobs().contains(key), "fixture: the runtime's store never held the parked body");
        assertFalse(rt.blobs().persisted(in.body().key()), "the ingress is not what a park at step 2 persists");
    }

    @Test
    void aParkAtStepThreePersistsStepThreesInputKey() {
        HoldingBlobStore bodies = bodies();
        WatchingDlq dlq = new WatchingDlq(bodies);
        List<Map<String, Object>> steps = List.of(Map.of("type", "rewrite", "name", "a"), Map.of("type", "rewrite", "name", "b"), Map.of("type", "boom", "name", "x"));
        Pipeline p = builder(dlq, bodies).build(FlowDefinition.from(metadataOnly(flow("at-least-once", steps))), Path.of(""));
        Envelope in = ingressIn(bodies);   // every body of the message in one store: only the key tells them apart
        BlobKey stepThreesInput = BlobKey.body(in.id(), 2);

        ParkedException e = assertThrows(ParkedException.class, () -> p.apply(in));
        assertEquals(3, e.parked().step());
        assertEquals(stepThreesInput, e.parked().bodyKey(), "fixture: step 3's input is step b's output");
        assertEquals(List.of(stepThreesInput), bodies.persists(), "the parked input's key, not the ingress's nor step 1's");
        assertTrue(bodies.persisted(stepThreesInput));
        assertFalse(bodies.persisted(in.body().key()), "the ingress is not persisted by this park");
        assertFalse(bodies.persisted(BlobKey.body(in.id(), 1)));
        assertEquals(List.of(true), dlq.durableAtPark);
    }

    @Test
    void aFailingSyncStepPersistsNothingAndParksNothing() {
        HoldingBlobStore bodies = bodies();
        WatchingDlq dlq = new WatchingDlq(bodies);
        Supplier<Envelope> in = () -> ingressIn(bodies);
        Pipeline sync = builder(dlq, bodies).build(FlowDefinition.from(metadataOnly(flow("sync", REWRITE_THEN_BOOM))), Path.of(""));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> sync.apply(in.get()));
        assertEquals("boom at x", e.getMessage(), "the caller gets the step's failure");
        assertEquals(0, e.getSuppressed().length, "nothing was tried on the side: " + List.of(e.getSuppressed()));
        assertEquals(List.of(), bodies.persists(), "a sync flow never persists outside its recording (D-045; D-064: kept bodies persist at a run's end)");
        assertEquals(List.of(), dlq.durableAtPark, "and never parks");

        // witness: the same steps over the same store, at-least-once, persist the parked body through it
        Pipeline atLeastOnce = builder(dlq, bodies).build(FlowDefinition.from(metadataOnly(flow("at-least-once", REWRITE_THEN_BOOM))), Path.of(""));
        Envelope parkedIn = in.get();
        assertThrows(ParkedException.class, () -> atLeastOnce.apply(parkedIn));
        assertEquals(List.of(BlobKey.body(parkedIn.id(), 1)), bodies.persists(), "this store records a park's persist");
    }

    /** {@code failure} thrown as it is, a checked one included, from a lambda that declares none (as {@code Future.await()} does). */
    @SuppressWarnings("unchecked")
    static <T extends Throwable> RuntimeException sneaky(Throwable failure) throws T {
        throw (T) failure;
    }

    /**
     * FIX-107: a step's {@link InterruptedException} is a cancelled run, not the step's failure: the wrapped step does not park
     * it — no row, nothing handed on — it propagates as is, and the interrupt flag, cleared by whatever threw it, is set again
     * on the thread that ran the step (read and cleared right after the call, so it never leaks). Witness: the same wrap over
     * the same DLQ parks a checked {@link java.io.IOException} — the DLQ records what is parked.
     */
    @Test
    void anInterruptedExceptionFromTheStepIsNotParkedAndTheFlagIsRestored() {
        InterruptedException cancelled = new InterruptedException("cancelled");
        List<Boolean> flagWhenThrown = new CopyOnWriteArrayList<>();
        List<Envelope> handedOn = new CopyOnWriteArrayList<>();
        Stage interrupted = parking(dlq).wrap(Stage.of(e -> {
            flagWhenThrown.add(Thread.currentThread().isInterrupted());
            throw sneaky(cancelled);
        }), STEP_X);
        Envelope in = in();
        InterruptedException thrown;
        boolean flagAfter;
        try {
            thrown = assertThrows(InterruptedException.class, () -> interrupted.apply(in, out -> { handedOn.add(out); return out; }));
        } finally {
            flagAfter = Thread.interrupted();   // read and cleared on the thread that ran the step, whatever was thrown
        }
        assertSame(cancelled, thrown, "the InterruptedException propagates as is");
        assertEquals(0, thrown.getSuppressed().length, "no park was tried on the side: " + List.of(thrown.getSuppressed()));
        assertEquals(List.of(false), flagWhenThrown, "fixture: the step threw with the flag clear");
        assertTrue(flagAfter, "the interrupt flag is set again for the caller");
        assertEquals(List.of(), handedOn);
        assertEquals(List.of(), dlq.list(Optional.empty(), Page.MAX_LIMIT).await().items(), "a cancelled run is never parked");

        // witness: a checked failure of the same wrapped step over the same DLQ is parked
        java.io.IOException reset = new java.io.IOException("partner socket reset");
        Stage failing = parking(dlq).wrap(Stage.of(e -> { throw sneaky(reset); }), STEP_X);
        ParkedException parked = assertThrows(ParkedException.class, () -> failing.apply(in, out -> out));
        assertSame(reset, parked.getCause());
        assertFalse(Thread.interrupted(), "a parked failure leaves the flag alone (read and cleared)");
        assertEquals(List.of(parked.parked()), dlq.list(Optional.empty(), Page.MAX_LIMIT).await().items());
    }
}
