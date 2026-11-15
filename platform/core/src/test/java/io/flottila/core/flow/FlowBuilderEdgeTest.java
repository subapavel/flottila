package io.flottila.core.flow;

import io.flottila.api.Ack;
import io.flottila.api.Component;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Health;
import io.flottila.api.Pipeline;
import io.flottila.api.Producer;
import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.api.StepFactory;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The builder's refusals and the numbering wrapper's edges. */
class FlowBuilderEdgeTest {

    final FakeRuntime rt = FakeRuntime.create();

    static final class NullComponent implements Component {
        @Override public String scheme() { return "null"; }
        @Override public String configSchema() { return "{}"; }
        @Override public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) { throw new UnsupportedOperationException(); }
        @Override public Producer producer(EndpointConfig cfg) {
            return new Producer() {
                @Override public Future<Envelope> send(Envelope e) { return Future.succeededFuture(e); }
                @Override public Future<Void> close() { return Future.succeededFuture(); }
            };
        }
        @Override public Future<Health> health() { return Future.succeededFuture(Health.up()); }
    }

    static StepConfig step(int index, String type, Map<String, Object> config) { return new StepConfig(index, type, "s" + index, false, config); }

    @Test
    void twoFactoriesForOneTypeAreRefused() {
        StepFactory a = FlowBuilderTest.factory("x", s -> Stage.of(e -> e));
        StepFactory b = FlowBuilderTest.factory("x", s -> Stage.of(e -> e));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new FlowBuilder(rt.runtime(), List.of(a, b), List.of(), Map.of()));
        assertTrue(e.getMessage().contains("'x'"), e.getMessage());
    }

    @Test
    void theNumberingWrapperAdvancesAnUnchangedStepLeavesANumberedOneAndRefusesOneThatWentPast() {
        Envelope in = rt.ingress("f", Headers.empty(), Buffer.buffer("<x/>"));
        StepConfig two = step(2, "t", Map.of());
        Envelope atOne = in.advance();
        assertEquals(2, FlowBuilder.numbered(Stage.of(e -> e.withHeader("h", "v")), two).apply(atOne, e -> e).step(), "headers only: advanced to 2");
        assertEquals(2, FlowBuilder.numbered(Stage.of(e -> e.next(rt.store(e, Buffer.buffer("<y/>")))), two).apply(atOne, e -> e).step(), "a new body is already at 2");
        assertEquals(2, FlowBuilder.numbered(Stage.of(e -> e), two).apply(in, e -> e).step(), "two steps behind: advanced twice");
        Stage past = Stage.of(e -> e.advance().advance().advance());
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> FlowBuilder.numbered(past, two).apply(atOne, e -> e));
        assertTrue(ex.getMessage().contains("s2") && ex.getMessage().contains("step 4"), ex.getMessage());
        Envelope kept = in.withHeader("a", "b");
        Envelope filtered = FlowBuilder.numbered((e, rest) -> kept, two).apply(in, e -> { throw new AssertionError("rest must not run"); });
        assertSame(kept, filtered, "a stage that does not hand on is not numbered: the rest never runs");
    }

    @Test
    void toStepRefusesABadEndpointsMapAndAMissingPropAtRunTime() {
        rt.withComponent(new NullComponent());
        FlowBuilder builder = new FlowBuilder(rt.runtime(), List.of(), List.of(), Map.of("n", new EndpointConfig("n", URI.create("null://x"), Map.of())));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> builder.build(FlowDefinition.from(FlowBuilderTest.flow(List.of(
                Map.of("type", "to", "name", "send", "byProp", "P", "endpoints", Map.of())))), FlowBuilderTest.NO_FILES)).getMessage().contains("endpoints"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> builder.build(FlowDefinition.from(FlowBuilderTest.flow(List.of(
                Map.of("type", "to", "name", "send", "byProp", "P")))), FlowBuilderTest.NO_FILES)).getMessage().contains("endpoints"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> builder.build(FlowDefinition.from(FlowBuilderTest.flow(List.of(
                Map.of("type", "to", "name", "send", "endpoint", "")))), FlowBuilderTest.NO_FILES)).getMessage().contains("endpoint"), "an empty endpoint name");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> builder.build(FlowDefinition.from(FlowBuilderTest.flow(List.of(
                Map.of("type", "to", "name", "send")))), FlowBuilderTest.NO_FILES)).getMessage().contains("byProp"), "neither endpoint nor byProp");
        Pipeline p = builder.build(FlowDefinition.from(FlowBuilderTest.flow(List.of(
                Map.of("type", "to", "name", "send", "byProp", "P", "endpoints", Map.of("a", "n"))))), FlowBuilderTest.NO_FILES);
        IllegalStateException unset = assertThrows(IllegalStateException.class, () -> p.apply(rt.ingress("f", Headers.empty(), Buffer.buffer("<x/>"))));
        assertTrue(unset.getMessage().contains("P") && unset.getMessage().contains("send"), unset.getMessage());
        Pipeline direct = builder.build(FlowDefinition.from(FlowBuilderTest.flow(List.of(Map.of("type", "to", "name", "send", "endpoint", "n")))), FlowBuilderTest.NO_FILES);
        assertEquals(1, direct.apply(rt.ingress("f", Headers.empty(), Buffer.buffer("<x/>"))).step());
    }

    @Test
    void theFlowsRetryPolicyIsReadFromTheDocument() {
        assertEquals(Duration.ofMillis(10), FlowDefinition.from(FlowBuilderTest.flow(List.of(Map.of("type", "t", "name", "n")))).retry().initial());
    }
}
