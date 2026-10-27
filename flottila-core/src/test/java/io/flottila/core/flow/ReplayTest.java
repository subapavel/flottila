package io.flottila.core.flow;

import io.flottila.api.BlobKey;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Pipeline;
import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.api.StepFactory;
import io.flottila.api.StepResources;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.dlq.Parked;
import io.flottila.core.dlq.ParkedException;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Page;
import io.flottila.core.record.Snapshot;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replay from any recorded step (ARCHITECTURE §6 recording, GATE check 7): steps n..end run as a new message marked
 * {@code REPLAY_OF}, on the envelope step n−1 handed on — from its snapshot, or from the DLQ row for a retry; the new
 * message leaves its own snapshots, starting with the input it was given.
 */
class ReplayTest {

    final FakeRuntime rt = FakeRuntime.create();
    final InMemoryMessageStore store = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();
    final AtomicInteger aCalls = new AtomicInteger(), bCalls = new AtomicInteger(), cCalls = new AtomicInteger();
    final AtomicBoolean partnerDown = new AtomicBoolean();

    static StepFactory factory(String type, Function<StepConfig, Stage> make) {
        return new StepFactory() {
            @Override public String type() { return type; }
            @Override public String configSchema() { return "{}"; }
            @Override public Stage create(StepConfig step, StepResources res) { return make.apply(step); }
        };
    }

    final List<StepFactory> factories = List.of(
            factory("a", s -> Stage.of(e -> { aCalls.incrementAndGet(); return e.withHeader("a", "1").withKey("invoiceId", "4711"); })),
            factory("b", s -> Stage.of(e -> { bCalls.incrementAndGet(); return e.next(rt.store(e, Buffer.buffer("<b>" + e.body().whole() + "</b>"))); })),
            factory("c", s -> Stage.of(e -> { cCalls.incrementAndGet(); if (partnerDown.get()) throw new IllegalStateException("partner down"); return e.withHeader("sent", "yes"); })));

    static Map<String, Object> flow(String mode, Map<String, Object> recording) {
        Map<String, Object> doc = new java.util.HashMap<>(Map.of("schemaVersion", 1, "kind", "flow", "id", "f", "version", "1.0.0", "from", Map.of("endpoint", "in"),
                "delivery", Map.of("mode", mode), "limits", Map.of("maxBodyBytes", 4096),
                "steps", List.of(Map.of("type", "a", "name", "a"), Map.of("type", "b", "name", "b"), Map.of("type", "c", "name", "c"))));
        if (recording != null) doc.put("recording", recording);
        return doc;
    }

    FlowBuilder builder() {
        return new FlowBuilder(rt.runtime(), factories, List.of(), Map.<String, EndpointConfig>of()).recording(store, () -> 0).dlq(dlq);
    }

    Envelope in() { return rt.ingress("f", Headers.empty().with("x", "1"), Buffer.buffer("<in/>")); }

    @Test
    void replayFromStepNRunsStepsNToTheEndAsANewMessageOnTheRecordedInput() {
        FlowDefinition def = FlowDefinition.from(flow("sync", null));
        Pipeline p = builder().build(def, Path.of(""));
        Envelope in = in();
        Envelope original = p.apply(in);
        assertEquals(List.of(1, 1, 1), List.of(aCalls.get(), bCalls.get(), cCalls.get()));
        Replay replay = builder().replay(def, Path.of(""));
        Envelope again = replay.from(in.id(), 3);
        assertEquals(List.of(1, 1, 2), List.of(aCalls.get(), bCalls.get(), cCalls.get()), "only step 3 ran again");
        assertNotEquals(in.id(), again.id(), "a new message");
        assertEquals(Optional.of(in.id()), again.get(Envelope.REPLAY_OF));
        assertEquals(3, again.step());
        assertEquals(original.body().hash(), again.body().hash(), "the same step-2 body went in");
        assertEquals("yes", again.header("sent").orElseThrow());
        assertEquals("1", again.header("a").orElseThrow(), "headers of the recorded step-2 envelope");
        assertEquals("4711", again.keys().get("invoiceId"));
        List<Snapshot> t = store.timeline(again.id()).await();
        assertEquals(List.of(2, 3), t.stream().map(Snapshot::step).toList(), "the replay's own timeline starts with the input it was given");
        assertEquals(Replay.REPLAY_INPUT, t.get(0).stepName());
        assertEquals(Optional.of(in.id()), Optional.ofNullable(t.get(0).props().asMap().get("replayOf")));
        assertEquals("c", t.get(1).stepName());
        assertEquals(List.of(in.id(), again.id()), store.findByKey("invoiceId", "4711", Optional.empty(), Page.MAX_LIMIT).await().items(), "found by key like any message");
        Envelope fromTwo = replay.from(in.id(), 2);
        assertEquals(List.of(1, 2, 3), List.of(aCalls.get(), bCalls.get(), cCalls.get()));
        assertEquals("<b><in/></b>", fromTwo.body().whole().toString(), "step b ran on the ingress body again");
        Envelope fromOne = replay.from(in.id(), 1);
        assertEquals(2, aCalls.get(), "from step 1 the whole flow runs on what arrived");
        assertEquals(3, fromOne.step());
    }

    @Test
    void replayNeedsTheRecordedInputAndNamesWhatIsMissing() {
        FlowDefinition def = FlowDefinition.from(flow("sync", null));
        Replay replay = builder().replay(def, Path.of(""));
        Envelope in = in();
        builder().build(def, Path.of("")).apply(in);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> replay.from(in.id(), 0)).getMessage().contains("1"), "steps start at 1");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> replay.from(in.id(), 4)).getMessage().contains("3"), "the flow has 3 steps");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> replay.from("nobody", 2)).getMessage().contains("nobody"));
        FlowDefinition ends = FlowDefinition.from(flow("sync", Map.of("level", "ends")));
        Envelope in2 = in();
        builder().build(ends, Path.of("")).apply(in2);
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () -> builder().replay(ends, Path.of("")).from(in2.id(), 3));
        assertTrue(missing.getMessage().contains("step 2") && missing.getMessage().contains(in2.id()), "step 2 was not recorded: " + missing.getMessage());
        assertEquals(3, builder().replay(ends, Path.of("")).from(in2.id(), 1).step(), "from step 1 the ingress snapshot is there under 'ends'");
        FlowDefinition metadata = FlowDefinition.from(flow("sync", Map.of("payload", "metadata-only")));
        Envelope in3 = in();
        builder().build(metadata, Path.of("")).apply(in3);
        IllegalArgumentException noBody = assertThrows(IllegalArgumentException.class, () -> builder().replay(metadata, Path.of("")).from(in3.id(), 2));
        assertTrue(noBody.getMessage().contains("metadata") && noBody.getMessage().contains("step 1"), noBody.getMessage());
        Envelope in4 = in();
        FlowDefinition atLeastOnce = FlowDefinition.from(flow("at-least-once", null));
        partnerDown.set(true);
        assertThrows(ParkedException.class, () -> builder().build(atLeastOnce, Path.of("")).apply(in4));
        IllegalArgumentException pastTheEnd = assertThrows(IllegalArgumentException.class, () -> builder().replay(atLeastOnce, Path.of("")).from(in4.id(), 4));
        assertTrue(pastTheEnd.getMessage().contains("3"), "a parked message is replayed from its parked step at most: " + pastTheEnd.getMessage());
        assertThrows(IllegalArgumentException.class, () -> builder().replay(def, Path.of("")).retry("nobody"), "no such DLQ row");
        FlowBuilder unrecorded = new FlowBuilder(rt.runtime(), factories, List.of(), Map.<String, EndpointConfig>of());
        assertTrue(assertThrows(IllegalStateException.class, () -> unrecorded.replay(def, Path.of(""))).getMessage().contains("recording"), "replay needs the snapshots");
        FlowBuilder noDlq = new FlowBuilder(rt.runtime(), factories, List.of(), Map.<String, EndpointConfig>of()).recording(store, () -> 0);
        assertTrue(assertThrows(IllegalStateException.class, () -> noDlq.replay(def, Path.of("")).retry(in.id())).getMessage().contains("DLQ"), "a retry needs the DLQ");
    }

    @Test
    void aReplayIsBuiltOnceAndHoldsNothingOfTheBuilderOrTheRuntime() {
        FlowBuilderTest.EchoComponent echo = new FlowBuilderTest.EchoComponent();
        rt.withComponent(echo);
        Map<String, Object> doc = new java.util.HashMap<>(flow("at-least-once", null));
        doc.put("steps", List.of(Map.of("type", "a", "name", "a"), Map.of("type", "to", "name", "send", "endpoint", "partner")));
        FlowDefinition def = FlowDefinition.from(doc);
        Map<String, EndpointConfig> endpoints = Map.of("partner", new EndpointConfig("partner", java.net.URI.create("echo://partner"), Map.of()));
        FlowBuilder builder = new FlowBuilder(rt.runtime(), factories, List.of(), endpoints).recording(store, () -> 0).dlq(dlq);
        Envelope in = in();
        builder.build(def, Path.of("")).apply(in);
        assertEquals(1, echo.producersBuilt.size(), "one producer per build");
        Replay replay = builder.replay(def, Path.of(""));
        assertEquals(2, echo.producersBuilt.size(), "the replay is its own build, done once");
        replay.from(in.id(), 2);
        replay.from(in.id(), 1);
        assertEquals(2, echo.producersBuilt.size(), "no producer is resolved at replay time");
        InMemoryDlq other = new InMemoryDlq();
        builder.dlq(other).recording(new InMemoryMessageStore(), () -> 0);
        Envelope third = replay.from(in.id(), 2);
        assertEquals(2, echo.producersBuilt.size());
        assertEquals(2, third.step(), "reconfiguring the builder afterwards changes nothing in a replay built before");
        assertEquals(2, store.timeline(third.id()).await().size(), "its snapshots (input and the send) still go to the store it was built with");
        assertEquals(List.of(), other.list(Optional.empty(), Page.MAX_LIMIT).await().items());
    }

    @Test
    void aDlqRetryReplaysTheParkedStepAsANewMessageAndRemovesTheRowOnSuccess() {
        FlowDefinition def = FlowDefinition.from(flow("at-least-once", Map.of("level", "ends")));
        Pipeline p = builder().build(def, Path.of(""));
        Envelope in = in();
        partnerDown.set(true);
        ParkedException parked = assertThrows(ParkedException.class, () -> p.apply(in));
        assertEquals(3, parked.parked().step());
        Replay replay = builder().replay(def, Path.of(""));
        ParkedException still = assertThrows(ParkedException.class, () -> replay.retry(in.id()));
        assertNotEquals(in.id(), still.parked().messageId(), "the retry is its own message");
        assertEquals(Optional.of(in.id()), Optional.ofNullable(still.parked().props().asMap().get("replayOf")));
        assertEquals(2, dlq.list(Optional.empty(), Page.MAX_LIMIT).await().items().size(), "the retry parked itself; the original row stays");
        assertEquals(2, cCalls.get());
        partnerDown.set(false);
        Envelope delivered = replay.retry(in.id());
        assertEquals("yes", delivered.header("sent").orElseThrow());
        assertEquals(Optional.of(in.id()), delivered.get(Envelope.REPLAY_OF));
        assertEquals(3, cCalls.get(), "only the parked step ran");
        assertEquals(1, bCalls.get(), "step b never ran again");
        List<Parked> left = dlq.list(Optional.empty(), Page.MAX_LIMIT).await().items();
        assertEquals(1, left.size(), "the original row is gone; the failed retry's row remains for the operator");
        assertFalse(left.get(0).messageId().equals(in.id()));
        List<Snapshot> t = store.timeline(delivered.id()).await();
        assertEquals(List.of(2, 3), t.stream().map(Snapshot::step).toList(), "'ends': the replay input and the last step");
        assertEquals(Optional.of(parked.parked().bodyKey()), t.get(0).bodyKey(), "the parked body reference (step 2's output), not a new copy");
        Envelope retried = replay.retry(left.get(0).messageId());
        assertEquals("yes", retried.header("sent").orElseThrow());
        assertEquals(List.of(), dlq.list(Optional.empty(), Page.MAX_LIMIT).await().items(), "the failed retry's own row is retried and removed too");
    }

    /**
     * D-066: a replay is a new message's first run. The snapshot it starts from may carry the attempt of the run that recorded
     * it (a worker's second claim); the replay drops it, so its steps write their bodies under the plain keys.
     */
    @Test
    void aReplayDropsTheAttempt() {
        FlowDefinition def = FlowDefinition.from(flow("at-least-once", null));
        Envelope in = in();
        Envelope original = builder().build(def, Path.of("")).apply(in.with(Envelope.ATTEMPT, 2));   // the run of a second attempt, as the worker starts it
        Snapshot input = store.timeline(in.id()).await().stream().filter(s -> s.step() == 1).findFirst().orElseThrow();
        assertEquals(Optional.of(2), input.props().get(Envelope.ATTEMPT), "fixture: the step-1 row of the second attempt carries it");
        Envelope again = builder().replay(def, Path.of("")).from(in.id(), 2);
        assertEquals(Optional.empty(), again.get(Envelope.ATTEMPT), "the replay is no attempt of the original's");
        assertEquals(Optional.of(in.id()), again.get(Envelope.REPLAY_OF), "and still a replay of it");
        List<Snapshot> t = store.timeline(again.id()).await();
        assertEquals(Optional.empty(), t.get(0).props().get(Envelope.ATTEMPT), "its input row carries no attempt: " + t.get(0).props());
        assertEquals(new BlobKey(in.id(), 2, "body~2"), original.body().key(), "fixture: the original's step b wrote under its attempt");
        assertEquals(BlobKey.body(again.id(), 2), again.body().key(), "the replay's step b writes under the plain key");
    }

    /** D-066: a DLQ retry is a new message's first run too: the attempt the parked run carried is dropped. */
    @Test
    void aDlqRetryDropsTheAttempt() {
        FlowDefinition def = FlowDefinition.from(flow("at-least-once", null));
        Envelope in = in();
        partnerDown.set(true);
        ParkedException parked = assertThrows(ParkedException.class, () -> builder().build(def, Path.of("")).apply(in.with(Envelope.ATTEMPT, 2)));
        assertEquals(Optional.of(2), parked.parked().props().get(Envelope.ATTEMPT), "fixture: the second attempt parked with its attempt");
        partnerDown.set(false);
        Envelope delivered = builder().replay(def, Path.of("")).retry(in.id());
        assertEquals(Optional.empty(), delivered.get(Envelope.ATTEMPT), "the retry is no attempt of the parked run's");
        assertEquals(Optional.of(in.id()), delivered.get(Envelope.REPLAY_OF), "and still a replay of it");
        List<Snapshot> t = store.timeline(delivered.id()).await();
        assertEquals(Optional.empty(), t.get(0).props().get(Envelope.ATTEMPT), "its input row carries no attempt: " + t.get(0).props());
    }

    // --- P1-09k2 (D-064, D-066, F-10)

    /**
     * A replay runs under a trail of its own and returns only once it closed: its step body persisted once (step b's row and
     * step c's show the same key), then its rows written, each with its body already durable. Its input — the recorded body
     * the replay started from, durable since its row was written — is not persisted again.
     */
    @Test
    void aReplayPersistsItsStepBodiesBeforeItReturns() throws Exception {
        HoldingBlobStore held = new HoldingBlobStore(new io.flottila.testkit.InMemoryBlobStore(rt.hasher(), FakeRuntime.DEFAULT_TREE_LIMIT));
        List<String> early = new java.util.concurrent.CopyOnWriteArrayList<>();
        io.flottila.core.record.MessageStore watching = new io.flottila.core.record.MessageStore() {
            @Override public io.vertx.core.Future<Void> record(Snapshot s) {
                if (s.bodyKey().isPresent() && !held.persisted(s.bodyKey().get())) early.add(s.messageId() + " step " + s.step() + " before " + s.bodyKey().get() + " was durable");
                return store.record(s);
            }
            @Override public io.vertx.core.Future<List<Snapshot>> timeline(String messageId) { return store.timeline(messageId); }
            @Override public io.vertx.core.Future<Page<String>> findByKey(String name, String value, Optional<String> after, int limit) {
                return store.findByKey(name, value, after, limit);
            }
        };
        List<StepFactory> all = new java.util.ArrayList<>(factories);
        all.add(factory("bh", s -> Stage.of(e -> e.next(held.write(e.nextKey(), Buffer.buffer("<bh>" + e.body().whole() + "</bh>"), 4096).await()))));
        Map<String, Object> doc = new java.util.HashMap<>(flow("sync", null));
        doc.put("steps", List.of(Map.of("type", "a", "name", "a"), Map.of("type", "bh", "name", "b"), Map.of("type", "c", "name", "c")));
        FlowDefinition def = FlowDefinition.from(doc);
        io.flottila.api.Runtime fake = rt.runtime();   // over the held store: a replay reads its input by key from the runtime's store
        FlowBuilder b = new FlowBuilder(new io.flottila.api.Runtime(fake.clock(), fake.timer(), fake.ids(), held, fake.hasher(), fake.components()), all, List.of(),
                Map.<String, EndpointConfig>of()).recording(watching, () -> 0).dlq(dlq);
        String id = rt.ids().next();
        Envelope in = Envelope.ingress(id, "f", Headers.empty().with("x", "1"), held.write(BlobKey.body(id, 0), Buffer.buffer("<in/>"), 4096).await());
        b.build(def, Path.of("")).apply(in);   // the original run, no trail bound: its rows follow their persists
        DeliveryTest.until(() -> store.timeline(id).await().size() == 4, () -> "fixture: the original run's rows");
        int before = held.persists().size();
        Replay replay = b.replay(def, Path.of(""));
        held.hold();

        java.util.concurrent.atomic.AtomicReference<Envelope> out = new java.util.concurrent.atomic.AtomicReference<>();
        Thread caller = Thread.ofVirtual().start(() -> out.set(replay.from(id, 2)));
        DeliveryTest.until(() -> held.held() == 1 && DeliveryTest.endedOrWaiting(caller),
                () -> "the replay's step body persist asked: held " + held.held() + ", caller " + caller.getState());
        assertNotEquals(Thread.State.TERMINATED, caller.getState(), "the replay has not returned while its step body's persist is held");
        assertEquals(List.of(), early, "no row ahead of its body");

        held.release();
        if (!caller.join(java.time.Duration.ofSeconds(5))) throw new AssertionError("the replay did not return within 5 s: " + List.of(caller.getStackTrace()));
        Envelope again = out.get();
        assertTrue(again != null, "the replay returned its result");
        List<Snapshot> t = store.timeline(again.id()).await();
        assertEquals(List.of(Replay.REPLAY_INPUT, "b", "c"), t.stream().map(Snapshot::stepName).toList(), "the replay's rows were written before it returned");
        assertTrue(t.stream().allMatch(r -> r.bodyKey().isPresent()), "each keeps its key: " + t);
        assertEquals(List.of(), early, "every row was written with its body already durable");
        assertEquals(List.of(BlobKey.body(again.id(), 2)), held.persists().subList(before, held.persists().size()),
                "step b's body once; the replay's input (the original's ingress body) never again");
    }

    /**
     * A DLQ retry runs under a trail of its own, seeded with the parked body (P1-09k2 r1b): it returns — and removes the DLQ
     * row — only once that trail closed, its step body persisted once, then its rows written; the parked input is never
     * persisted again.
     */
    @Test
    void aRetryPersistsItsStepBodiesBeforeItReturnsAndRemovesTheRow() throws Exception {
        HoldingBlobStore held = new HoldingBlobStore(new io.flottila.testkit.InMemoryBlobStore(rt.hasher(), FakeRuntime.DEFAULT_TREE_LIMIT));
        AtomicBoolean bDown = new AtomicBoolean(true);
        List<StepFactory> all = new java.util.ArrayList<>(factories);
        all.add(factory("bh", s -> Stage.of(e -> {
            if (bDown.get()) throw new IllegalStateException("b down");
            return e.next(held.write(e.nextKey(), Buffer.buffer("<bh>" + e.body().whole() + "</bh>"), 4096).await());
        })));
        Map<String, Object> doc = new java.util.HashMap<>(flow("at-least-once", null));
        doc.put("steps", List.of(Map.of("type", "a", "name", "a"), Map.of("type", "bh", "name", "b"), Map.of("type", "c", "name", "c")));
        FlowDefinition def = FlowDefinition.from(doc);
        io.flottila.api.Runtime fake = rt.runtime();   // over the held store: a retry reads the parked body by key from the runtime's store
        FlowBuilder b = new FlowBuilder(new io.flottila.api.Runtime(fake.clock(), fake.timer(), fake.ids(), held, fake.hasher(), fake.components()), all, List.of(),
                Map.<String, EndpointConfig>of()).recording(store, () -> 0).dlq(dlq);
        String id = rt.ids().next();
        Envelope in = Envelope.ingress(id, "f", Headers.empty().with("x", "1"), held.write(BlobKey.body(id, 0), Buffer.buffer("<in/>"), 4096).await());
        assertThrows(ParkedException.class, () -> b.build(def, Path.of("")).apply(in), "fixture: step b failed and the message was parked there");
        BlobKey parkedInput = dlq.get(id).await().orElseThrow().bodyKey();
        assertEquals(in.body().key(), parkedInput, "fixture: step b's input is step a's output, the ingress body");
        assertTrue(held.persisted(parkedInput), "fixture: the park made it durable");
        bDown.set(false);
        Replay replay = b.replay(def, Path.of(""));
        int before = held.persists().size();
        held.hold();

        java.util.concurrent.atomic.AtomicReference<Envelope> out = new java.util.concurrent.atomic.AtomicReference<>();
        Thread caller = Thread.ofVirtual().start(() -> out.set(replay.retry(id)));
        DeliveryTest.until(() -> held.held() >= 1 && DeliveryTest.endedOrWaiting(caller),
                () -> "the retry's step body persist asked: held " + held.held() + ", caller " + caller.getState());
        assertNotEquals(Thread.State.TERMINATED, caller.getState(), "the retry has not returned while its step body's persist is held");
        assertTrue(dlq.get(id).await().isPresent(), "the DLQ row stays until the retry went through, its trail included");

        held.release();
        if (!caller.join(java.time.Duration.ofSeconds(5))) throw new AssertionError("the retry did not return within 5 s: " + List.of(caller.getStackTrace()));
        Envelope again = out.get();
        assertTrue(again != null, "the retry returned its result");
        assertEquals(Optional.empty(), dlq.get(id).await(), "the row is removed once the retry went through");
        List<Snapshot> t = store.timeline(again.id()).await();
        assertEquals(List.of(Replay.REPLAY_INPUT, "b", "c"), t.stream().map(Snapshot::stepName).toList(), "the retry's rows were written before it returned");
        assertTrue(t.stream().allMatch(r -> r.bodyKey().isPresent() && held.persisted(r.bodyKey().get())), "each keeps a durable key: " + t);
        assertEquals(List.of(BlobKey.body(again.id(), 2)), held.persists().subList(before, held.persists().size()),
                "step b's body once; the parked input (the retry's seed) never again");
    }

    // --- P1-09c2 (D-067): a given id, the flow's permit until the trail closed, refusals by reason, close

    static final Path NO_FILES = Path.of("");

    /** Holds every run at its entry until the test releases that run (core flow test fixture). */
    final RunGate gate = new RunGate();
    final AtomicInteger rejects = new AtomicInteger();

    /** a, b, c — plus {@code gate} (RunGate) and {@code reject} (throws its own IllegalArgumentException: a run's failure, never a refusal). */
    List<StepFactory> withGate() {
        List<StepFactory> all = new java.util.ArrayList<>(factories);
        all.add(gate);
        all.add(factory("reject", s -> Stage.of(e -> {
            rejects.incrementAndGet();
            throw new IllegalArgumentException("rejected by the step itself");
        })));
        return all;
    }

    FlowBuilder gated() {
        return new FlowBuilder(rt.runtime(), withGate(), List.of(), Map.<String, EndpointConfig>of()).recording(store, () -> 0).dlq(dlq);
    }

    static Map<String, Object> step(String type, String name) {
        return Map.of("type", type, "name", name);
    }

    /** {@code doc} with these steps and limits ({@code maxInFlightBytes} omitted when null). */
    static Map<String, Object> shaped(Map<String, Object> doc, List<Map<String, Object>> steps, long maxBodyBytes, long maxInFlight, Long maxInFlightBytes) {
        Map<String, Object> shaped = new java.util.HashMap<>(doc);
        shaped.put("steps", steps);
        Map<String, Object> limits = new java.util.HashMap<>(Map.of("maxBodyBytes", maxBodyBytes, "maxInFlight", maxInFlight));
        if (maxInFlightBytes != null) limits.put("maxInFlightBytes", maxInFlightBytes);
        shaped.put("limits", limits);
        return shaped;
    }

    Envelope in(String body) { return rt.ingress("f", Headers.empty().with("x", "1"), Buffer.buffer(body)); }

    /** The original run of {@code in} through a, b, c (sync, recorded): its step-2 body is {@code <b>} + the ingress body + {@code </b>}. */
    Envelope recorded(Envelope in) {
        return builder().build(FlowDefinition.from(flow("sync", null)), NO_FILES).apply(in);
    }

    /** The message parked at step 3 (c) of an at-least-once a, b, c run: its parked input is step b's output. */
    Envelope parkedAtC(Envelope in) {
        partnerDown.set(true);
        try {
            assertEquals(3, assertThrows(ParkedException.class, () -> builder().build(FlowDefinition.from(flow("at-least-once", null)), NO_FILES).apply(in))
                    .parked().step(), "fixture: parked at c");
        } finally {
            partnerDown.set(false);
        }
        return in;
    }

    /** Runs {@code call} on a virtual thread of its own (a replay blocks); its result or failure lands in the returned future. */
    static java.util.concurrent.CompletableFuture<Envelope> onVirtualThread(java.util.concurrent.Callable<Envelope> call) {
        java.util.concurrent.CompletableFuture<Envelope> out = new java.util.concurrent.CompletableFuture<>();
        Thread.ofVirtual().name("replay-caller").start(() -> {
            try {
                out.complete(call.call());
            } catch (Throwable failed) {
                out.completeExceptionally(failed);
            }
        });
        return out;
    }

    /** The outcome of {@code f} within 5 s; a refusal or failure is rethrown unwrapped. */
    static Envelope joined(java.util.concurrent.CompletableFuture<Envelope> f) throws Exception {
        try {
            return f.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof Exception x) throw x;
            throw e;
        }
    }

    /** The one run of {@code messageId} at the gate, once it entered. */
    RunGate.Run atGate(String messageId) {
        DeliveryTest.until(() -> gate.runsOf(messageId).size() == 1, () -> "the run of " + messageId + " reached the gate: " + gate.runs());
        return gate.runsOf(messageId).get(0);
    }

    static ReplayRefusedException refusal(org.junit.jupiter.api.function.Executable call) {
        return assertThrows(ReplayRefusedException.class, call);
    }

    List<Integer> stepsOf(String messageId) {
        return store.timeline(messageId).await().stream().map(Snapshot::step).toList();
    }

    /**
     * The overloads with a given id: {@code from(id, n, replayId)} and {@code retry(id, replayId)} run as {@code replayId} — the
     * envelope, its rows, and a retry that parks again parks under it (the id a 502 names) — and mint nothing; the old forms take
     * exactly one id from the runtime's generator.
     */
    @Test
    void fromWithAGivenIdRunsAsThatId() {
        FlowDefinition def = FlowDefinition.from(flow("sync", null));
        Envelope in = in();
        recorded(in);
        Replay replay = builder().replay(def, NO_FILES);
        long issued = rt.ids().issued();

        Envelope given = replay.from(in.id(), 3, "given-1");

        assertEquals("given-1", given.id(), "the run is the given id");
        assertEquals(Optional.of(in.id()), given.get(Envelope.REPLAY_OF));
        assertEquals(List.of(2, 3), stepsOf("given-1"), "its rows are written under the given id");
        assertEquals(issued, rt.ids().issued(), "a run that was given its id mints none");
        Envelope minted = replay.from(in.id(), 3);
        assertEquals(issued + 1, rt.ids().issued(), "the old form takes one id from the generator");
        assertEquals("m-" + (issued + 1), minted.id(), "and runs as that id");

        FlowDefinition atLeastOnce = FlowDefinition.from(flow("at-least-once", null));
        Envelope parked = parkedAtC(in());
        Replay retries = builder().replay(atLeastOnce, NO_FILES);
        partnerDown.set(true);
        ParkedException again = assertThrows(ParkedException.class, () -> retries.retry(parked.id(), "given-2"));
        assertEquals("given-2", again.parked().messageId(), "a retry that parks again parks under the given id");
        assertTrue(dlq.get("given-2").await().isPresent(), "its row is the given id's");
        assertTrue(dlq.get(parked.id()).await().isPresent(), "the original row stays");
        partnerDown.set(false);
        long before = rt.ids().issued();

        Envelope delivered = retries.retry(parked.id(), "given-3");

        assertEquals("given-3", delivered.id());
        assertEquals(Optional.of(parked.id()), delivered.get(Envelope.REPLAY_OF));
        assertEquals(List.of(Replay.REPLAY_INPUT, "c"), store.timeline("given-3").await().stream().map(Snapshot::stepName).toList());
        assertEquals(Optional.empty(), dlq.get(parked.id()).await(), "the original row is removed once the given id went through");
        assertEquals(before, rt.ids().issued(), "a retry that was given its id mints none");
        Envelope parkedAgain = parkedAtC(in());
        Envelope oldForm = retries.retry(parkedAgain.id());
        assertEquals("m-" + (before + 2), oldForm.id(), "the old retry form takes one id from the generator (the fixture's ingress took the one before)");
    }

    /**
     * Every pre-run refusal is a {@link ReplayRefusedException} — an {@link IllegalArgumentException}, so the asserts above still
     * hold — naming its {@link ReplayRefusedException.Reason}; every reason has a case here (exact set). The order of the checks is
     * the admin API's status order: a step out of range beats an unknown message (400 before 404), and while the replay's only
     * permit is taken the refusals that are no capacity question — unknown, no DLQ row, not recorded, metadata only, beyond the
     * flow, too large — still name their own reason, never BUSY (404/422 before 429). Nothing runs for a refusal: no step, no row
     * under the given id. What the run itself throws — an IllegalArgumentException of a step included — is its outcome, never a
     * refusal. After close, CLOSED.
     */
    @Test
    void everyPreRunRefusalNamesItsReason() throws Exception {
        Envelope in = in("<in/>");                       // step 2 body: <b><in/></b>, 12 bytes
        recorded(in);
        Envelope big = in("<in-big/>");                  // step 2 body: <b><in-big/></b>, 16 bytes
        recorded(big);
        Envelope ends = in();
        builder().build(FlowDefinition.from(flow("sync", Map.of("level", "ends"))), NO_FILES).apply(ends);
        Envelope meta = in();
        builder().build(FlowDefinition.from(flow("sync", Map.of("payload", "metadata-only"))), NO_FILES).apply(meta);
        Envelope parked = parkedAtC(in("<in/>"));        // parked input: 12 bytes, at step 3
        Envelope parkedBig = parkedAtC(in("<in-big/>")); // parked input: 16 bytes, at step 3
        Map<String, Object> five = new java.util.HashMap<>(flow("at-least-once", null));
        five.put("steps", List.of(step("a", "a"), step("a", "a2"), step("a", "a3"), step("a", "a4"), step("c", "c")));   // the body stays the 5-byte ingress
        Envelope parkedAtFive = in();
        partnerDown.set(true);
        assertEquals(5, assertThrows(ParkedException.class, () -> builder().build(FlowDefinition.from(five), NO_FILES).apply(parkedAtFive)).parked().step(),
                "fixture: parked at step 5");
        partnerDown.set(false);
        Envelope rejected = in();
        recorded(rejected);

        // the replay: a, b, gate, c — one request and 12 bytes in flight at most
        FlowDefinition def = FlowDefinition.from(shaped(flow("sync", null), List.of(step("a", "a"), step("b", "b"), RunGate.STEP, step("c", "c")), 12, 1, 12L));
        Replay replay = gated().replay(def, NO_FILES);
        Map<ReplayRefusedException.Reason, ReplayRefusedException> seen = new java.util.EnumMap<>(ReplayRefusedException.Reason.class);
        List<Integer> callsBefore = List.of(aCalls.get(), bCalls.get(), cCalls.get());

        seen.put(ReplayRefusedException.Reason.STEP_OUT_OF_RANGE, refusal(() -> replay.from(in.id(), 0, "refused-0")));
        assertEquals(ReplayRefusedException.Reason.STEP_OUT_OF_RANGE, refusal(() -> replay.from(in.id(), 5, "refused-5")).reason(), "the flow has 4 steps");
        assertEquals(ReplayRefusedException.Reason.STEP_OUT_OF_RANGE, refusal(() -> replay.from("nobody", 0, "refused-nobody-0")).reason(),
                "a step out of range is refused before the message is looked up (400 before 404)");

        java.util.concurrent.CompletableFuture<Envelope> holding = onVirtualThread(() -> replay.from(in.id(), 3, "holding"));
        RunGate.Run held = atGate("holding");             // the only permit: 1 request, 12 bytes
        try {
            seen.put(ReplayRefusedException.Reason.UNKNOWN_MESSAGE, refusal(() -> replay.from("nobody", 2, "refused-unknown")));
            seen.put(ReplayRefusedException.Reason.NOT_RECORDED, refusal(() -> replay.from(ends.id(), 3, "refused-ends")));
            seen.put(ReplayRefusedException.Reason.METADATA_ONLY, refusal(() -> replay.from(meta.id(), 2, "refused-meta")));
            seen.put(ReplayRefusedException.Reason.TOO_LARGE, refusal(() -> replay.from(big.id(), 3, "refused-big")));
            seen.put(ReplayRefusedException.Reason.NO_DLQ_ROW, refusal(() -> replay.retry("nobody", "refused-no-row")));
            seen.put(ReplayRefusedException.Reason.STEP_BEYOND_FLOW, refusal(() -> replay.retry(parkedAtFive.id(), "refused-beyond")));
            assertEquals(ReplayRefusedException.Reason.TOO_LARGE, refusal(() -> replay.retry(parkedBig.id(), "refused-big-retry")).reason(),
                    "a parked body above the byte budget");
            seen.put(ReplayRefusedException.Reason.BUSY, refusal(() -> replay.from(big.id(), 1, "refused-busy")));   // 9 bytes: it fits, no permit is free
            assertEquals(ReplayRefusedException.Reason.BUSY, refusal(() -> replay.retry(parked.id(), "refused-busy-retry")).reason(), "a retry takes a permit too");
        } finally {
            held.release();
        }
        assertEquals("holding", joined(holding).id(), "fixture: the holding run went through");

        Replay rejecting = gated().replay(FlowDefinition.from(shaped(flow("sync", null), List.of(step("a", "a"), step("b", "b"), step("reject", "r")), 4096, 1, null)), NO_FILES);
        IllegalArgumentException runFailure = assertThrows(IllegalArgumentException.class, () -> rejecting.from(rejected.id(), 3, "rejected-run"));
        assertFalse(runFailure instanceof ReplayRefusedException, "a step's own IllegalArgumentException is the run's outcome, never a refusal: " + runFailure);
        assertEquals("rejected by the step itself", runFailure.getMessage());
        assertEquals(1, rejects.get(), "the run ran");

        replay.close().await(5, java.util.concurrent.TimeUnit.SECONDS);
        seen.put(ReplayRefusedException.Reason.CLOSED, refusal(() -> replay.from(in.id(), 3, "refused-closed")));
        assertEquals(ReplayRefusedException.Reason.CLOSED, refusal(() -> replay.retry(parked.id(), "refused-closed-retry")).reason(), "a closed replay retries nothing");

        seen.forEach((reason, refused) -> {
            assertEquals(reason, refused.reason(), "refused for " + reason + ": " + refused.getMessage());
            IllegalArgumentException asIae = refused;   // the existing IllegalArgumentException asserts hold
            assertTrue(asIae.getMessage() != null && !asIae.getMessage().isBlank(), reason + " says why: " + asIae);
        });
        assertEquals(java.util.EnumSet.allOf(ReplayRefusedException.Reason.class), seen.keySet(), "every reason has its case");
        assertEquals(List.of(callsBefore.get(0), callsBefore.get(1), callsBefore.get(2) + 1), List.of(aCalls.get(), bCalls.get(), cCalls.get()),
                "no step ran for a refusal: only the holding run's step c");
        assertEquals(List.of("holding"), gate.runs().stream().map(r -> r.messageId).toList(), "only the holding run reached the gate");
        for (String id : List.of("refused-0", "refused-5", "refused-nobody-0", "refused-unknown", "refused-ends", "refused-meta", "refused-big", "refused-no-row",
                "refused-beyond", "refused-big-retry", "refused-busy", "refused-busy-retry", "refused-closed", "refused-closed-retry")) {
            assertEquals(List.of(), stepsOf(id), id + ": no row for a refused run");
            assertEquals(Optional.empty(), dlq.get(id).await(), id + ": no DLQ row for a refused run");
        }
        assertTrue(dlq.get(parked.id()).await().isPresent() && dlq.get(parkedBig.id()).await().isPresent() && dlq.get(parkedAtFive.id()).await().isPresent(),
                "a refused retry keeps its row");
    }

    /**
     * A replay takes a permit of the flow's admission after its pre-checks and gives it back only once its trail closed — not when
     * its pipeline returned: while the run's step body persist is held (the pipeline has ended, step c ran) a second replay of a
     * one-permit flow is BUSY; once the persist is released the first returns and the next is admitted. A run that throws holds its
     * permit until its trail closed too, then gives it back.
     */
    @Test
    void aReplayHoldsAFlowPermitUntilItsTrailClosed() throws Exception {
        HoldingBlobStore held = new HoldingBlobStore(new io.flottila.testkit.InMemoryBlobStore(rt.hasher(), FakeRuntime.DEFAULT_TREE_LIMIT));
        List<StepFactory> all = new java.util.ArrayList<>(factories);
        all.add(factory("bh", s -> Stage.of(e -> { bCalls.incrementAndGet(); return e.next(held.write(e.nextKey(), Buffer.buffer("<bh>" + e.body().whole() + "</bh>"), 4096).await()); })));
        FlowDefinition def = FlowDefinition.from(shaped(flow("sync", null), List.of(step("a", "a"), step("bh", "b"), step("c", "c")), 4096, 1, null));
        io.flottila.api.Runtime fake = rt.runtime();
        FlowBuilder b = new FlowBuilder(new io.flottila.api.Runtime(fake.clock(), fake.timer(), fake.ids(), held, fake.hasher(), fake.components()), all, List.of(),
                Map.<String, EndpointConfig>of()).recording(store, () -> 0).dlq(dlq);
        String id = rt.ids().next();
        Envelope in = Envelope.ingress(id, "f", Headers.empty(), held.write(BlobKey.body(id, 0), Buffer.buffer("<in/>"), 4096).await());
        b.build(def, NO_FILES).apply(in);
        DeliveryTest.until(() -> store.timeline(id).await().size() == 4, () -> "fixture: the original run's rows");
        Replay replay = b.replay(def, NO_FILES);
        int cBefore = cCalls.get();
        held.hold();

        java.util.concurrent.CompletableFuture<Envelope> first = onVirtualThread(() -> replay.from(id, 2, "first"));
        DeliveryTest.until(() -> held.held() == 1 && cCalls.get() == cBefore + 1, () -> "the first run's pipeline ended and its trail waits for its persist: held " + held.held());

        assertFalse(first.isDone(), "the first replay returns only once its trail closed");
        assertEquals(ReplayRefusedException.Reason.BUSY, refusal(() -> replay.from(id, 3, "second")).reason(),
                "the pipeline returned, the trail did not close: the permit is still taken");
        assertEquals(List.of(), stepsOf("second"));

        held.release();
        assertEquals("first", joined(first).id());
        assertEquals("third", replay.from(id, 3, "third").id(), "the trail closed: the permit is back");

        partnerDown.set(true);
        held.hold();
        java.util.concurrent.CompletableFuture<Envelope> failing = onVirtualThread(() -> replay.from(id, 2, "failing"));
        DeliveryTest.until(() -> held.held() == 1, () -> "the failed run's trail waits for its step body's persist");
        partnerDown.set(false);
        assertEquals(ReplayRefusedException.Reason.BUSY, refusal(() -> replay.from(id, 3, "behind-the-failure")).reason(),
                "a failed run holds its permit until its trail closed too");
        held.release();
        IllegalStateException down = assertThrows(IllegalStateException.class, () -> joined(failing));
        assertEquals("partner down", down.getMessage(), "the run's own failure is its outcome");
        assertEquals("after-the-failure", replay.from(id, 3, "after-the-failure").id(), "the failed run gave its permit back");
    }

    /**
     * No room: a replay whose permit the flow's admission cannot give now is BUSY and runs nothing — no step, no row under its id —
     * counted in requests and in bytes, the input body's size: with 12 of 20 bytes taken by a held run, a second 12-byte input is
     * BUSY with a request still free, a 5-byte one fits; with both requests taken, a 1-byte input is BUSY. Once the held runs
     * end, the refused replay runs.
     */
    @Test
    void noRoomIsBusyAndRunsNothing() throws Exception {
        Envelope in = in("<in/>");     // ingress 5 bytes; step-2 body <b><in/></b>, 12 bytes
        recorded(in);
        Envelope tiny = in("x");       // ingress 1 byte
        recorded(tiny);
        FlowDefinition def = FlowDefinition.from(shaped(flow("sync", null), List.of(step("a", "a"), step("b", "b"), RunGate.STEP, step("c", "c")), 16, 2, 20L));
        Replay replay = gated().replay(def, NO_FILES);

        java.util.concurrent.CompletableFuture<Envelope> twelve = onVirtualThread(() -> replay.from(in.id(), 3, "twelve"));
        RunGate.Run first = atGate("twelve");
        List<Integer> calls = List.of(aCalls.get(), bCalls.get(), cCalls.get());

        assertEquals(ReplayRefusedException.Reason.BUSY, refusal(() -> replay.from(in.id(), 3, "bytes")).reason(),
                "12 + 12 bytes exceed the 20-byte budget though a request is free");
        assertEquals(List.of(aCalls.get(), bCalls.get(), cCalls.get()), calls, "the refused replay ran no step");
        assertEquals(List.of(), stepsOf("bytes"), "and wrote no row");

        java.util.concurrent.CompletableFuture<Envelope> five = onVirtualThread(() -> replay.from(in.id(), 1, "five"));
        RunGate.Run second = atGate("five");   // 12 + 5 bytes, both requests
        List<Integer> callsTwo = List.of(aCalls.get(), bCalls.get(), cCalls.get());

        assertEquals(ReplayRefusedException.Reason.BUSY, refusal(() -> replay.from(tiny.id(), 1, "requests")).reason(),
                "12 + 5 + 1 bytes fit, but both requests are taken");
        assertEquals(List.of(aCalls.get(), bCalls.get(), cCalls.get()), callsTwo, "the refused replay ran no step");
        assertEquals(List.of(), stepsOf("requests"));
        assertEquals(2, gate.entered(), "only the admitted runs reached the gate");

        first.release();
        second.release();
        assertEquals("twelve", joined(twelve).id());
        assertEquals("five", joined(five).id());
        gate.open();
        assertEquals("bytes", replay.from(in.id(), 3, "bytes").id(), "the permits are back: the refused replay runs");
        assertEquals(List.of(2, 3, 4), stepsOf("bytes"));
    }

    /**
     * {@code close()} refuses new runs at once (from and retry: CLOSED) and closes the replay's own producers once the runs in
     * flight ended — after the last of them, never under one (a closed producer refuses the send behind the gate) — each once; a
     * producer of another build (a delivery of the same builder) stays open. A second close closes nothing again; an idle replay
     * closes its producers at once.
     */
    @Test
    void closeRefusesNewRunsAndClosesProducersAfterTheLastRun() throws Exception {
        ClosingPartner partner = new ClosingPartner();
        rt.withComponent(partner);
        Map<String, EndpointConfig> endpoints = Map.of("partner", new EndpointConfig("partner", java.net.URI.create("mock://partner"), Map.of()));
        FlowBuilder b = new FlowBuilder(rt.runtime(), withGate(), List.of(), endpoints).recording(store, () -> 0).dlq(dlq);
        Envelope in = in();
        recorded(in);
        Envelope parked = parkedAtC(in());
        FlowDefinition def = FlowDefinition.from(shaped(flow("sync", null), List.of(step("a", "a"), RunGate.STEP, Map.of("type", "to", "name", "send", "endpoint", "partner")),
                4096, 4, null));
        Replay replay = b.replay(def, NO_FILES);
        assertEquals(1, partner.built().size(), "fixture: the replay's own producer");
        ClosingPartner.Out own = partner.built().get(0);
        b.delivery(def, NO_FILES);
        assertEquals(2, partner.built().size(), "fixture: the delivery's producer");
        ClosingPartner.Out other = partner.built().get(1);

        java.util.concurrent.CompletableFuture<Envelope> one = onVirtualThread(() -> replay.from(in.id(), 2, "one"));
        java.util.concurrent.CompletableFuture<Envelope> two = onVirtualThread(() -> replay.from(in.id(), 2, "two"));
        RunGate.Run runOne = atGate("one");
        RunGate.Run runTwo = atGate("two");

        io.vertx.core.Future<Void> closed = replay.close();

        assertEquals(ReplayRefusedException.Reason.CLOSED, refusal(() -> replay.from(in.id(), 2, "late")).reason(), "no new run once close was called");
        assertEquals(ReplayRefusedException.Reason.CLOSED, refusal(() -> replay.retry(parked.id(), "late-retry")).reason(), "no new retry either");
        assertEquals(2, gate.entered(), "the refused runs never started");
        assertEquals(List.of(), stepsOf("late"));
        assertFalse(closed.isComplete(), "two runs are in flight");
        assertEquals(0, own.closes(), "the producer stays open under the runs in flight");

        runOne.release();
        assertEquals("one", joined(one).id(), "the first run sent through the open producer");
        assertFalse(closed.isComplete(), "one run is still in flight");
        assertEquals(0, own.closes(), "not closed after the first run: the second is still in flight");

        runTwo.release();
        assertEquals("two", joined(two).id(), "the last run sent through the open producer");
        closed.await(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(1, own.closes(), "closed once the last run ended");
        assertEquals(List.of("one", "two"), partner.partner().sent().stream().map(Envelope::id).toList(), "both runs reached the partner");
        assertEquals(0, other.closes(), "the delivery's producer is not the replay's to close");

        replay.close().await(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(1, own.closes(), "a second close closes nothing again");
        assertEquals(List.of(), partner.closedTwice());

        Replay idle = b.replay(def, NO_FILES);
        ClosingPartner.Out idleOwn = partner.built().get(2);
        idle.close().await(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(1, idleOwn.closes(), "an idle replay closes its producers at once");
        assertEquals(0, other.closes());
    }
}
