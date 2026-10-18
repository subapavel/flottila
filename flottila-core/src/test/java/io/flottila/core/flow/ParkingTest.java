package io.flottila.core.flow;

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
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Snapshot;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(List.of(parked), dlq.list().await(), "durable before the exception surfaces");
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
        assertEquals(List.of(), dlq.list().await());
        Envelope fine = builder(dlq).build(FlowDefinition.from(flow("at-least-once", List.of(Map.of("type", "mark", "name", "a")))), Path.of("")).apply(in());
        assertEquals("yes", fine.header("seen-a").orElseThrow(), "a message that goes through is never parked");
        assertEquals(List.of(), dlq.list().await());
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
            @Override public Future<List<Parked>> list() { return Future.succeededFuture(List.of()); }
            @Override public Future<Optional<Parked>> get(String id) { return Future.succeededFuture(Optional.empty()); }
            @Override public Future<Void> remove(String id) { return Future.succeededFuture(); }
        };
        Pipeline p = builder(broken).build(FlowDefinition.from(flow("at-least-once", MARK_THEN_BOOM)), Path.of(""));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> p.apply(in()));
        assertEquals("boom at x", e.getMessage(), "not a ParkedException: the message is not parked");
        assertEquals(1, e.getSuppressed().length);
        assertEquals("dlq down", e.getSuppressed()[0].getMessage());
    }
}
