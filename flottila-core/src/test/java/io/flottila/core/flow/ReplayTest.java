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
}
