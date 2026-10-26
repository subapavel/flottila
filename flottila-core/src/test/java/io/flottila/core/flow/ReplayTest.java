package io.flottila.core.flow;

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
}
