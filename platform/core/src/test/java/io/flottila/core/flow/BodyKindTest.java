package io.flottila.core.flow;

import io.flottila.api.Body;
import io.flottila.api.BodyKind;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Expressions;
import io.flottila.api.Headers;
import io.flottila.api.Pipeline;
import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.api.StepFactory;
import io.flottila.api.StepResources;
import io.flottila.core.pattern.Split;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The body-kind declaration (ARCHITECTURE §5): a step says whether it needs the whole body, a stream, or splits; the
 * build refuses a {@code whole} step on a flow whose max body exceeds the store's tree limit unless a split precedes it —
 * at build time, never on the first large message.
 */
class BodyKindTest {

    final FakeRuntime rt = FakeRuntime.create();   // tree limit 1 MiB

    static StepFactory factory(String type, BodyKind kind) {
        return new StepFactory() {
            @Override public String type() { return type; }
            @Override public String configSchema() { return "{}"; }
            @Override public BodyKind bodyKind() { return kind; }
            @Override public Stage create(StepConfig step, StepResources res) { return Stage.of(e -> e.withHeader("seen-" + step.name(), "yes")); }
        };
    }

    /**
     * A line-oriented language: {@code parts} cuts the body at newlines, reading it through {@link Body#open()} when it
     * says it streams and through {@link Body#whole()} when it says it needs the whole body; its predicate reads nothing.
     */
    static Expressions lines(String name, BodyKind kind) {
        return new Expressions() {
            @Override public String language() { return name; }
            @Override public BodyKind bodyKind() { return kind; }
            @Override public Predicate<Envelope> predicate(String expression, StepResources resources) { return e -> true; }
            @Override public Function<Envelope, Optional<String>> value(String expression, StepResources resources) { return e -> Optional.empty(); }
            @Override public Function<Envelope, List<Body>> parts(String expression, StepResources resources) {
                return e -> {
                    String[] lines = (kind == BodyKind.WHOLE ? e.body().whole() : streamed(e.body())).toString().split("\\n");
                    List<Body> out = new java.util.ArrayList<>();
                    for (int i = 0; i < lines.length; i++) {
                        out.add(resources.blobs().write(io.flottila.api.BlobKey.attachment(e.id(), e.step() + 1, "split-" + i), Buffer.buffer(lines[i]), resources.maxBodyBytes()).await());
                    }
                    return out;
                };
            }
        };
    }

    /** The body read as a stream, never as a tree. */
    static Buffer streamed(Body body) {
        io.vertx.core.Promise<Buffer> done = io.vertx.core.Promise.promise();
        Buffer acc = Buffer.buffer();
        io.vertx.core.streams.ReadStream<Buffer> in = body.open();
        in.exceptionHandler(done::fail);
        in.endHandler(v -> done.complete(acc));
        in.handler(acc::appendBuffer);
        return done.future().await();
    }

    static final Expressions LINES = lines("lines", BodyKind.STREAM);
    static final Expressions LINES_WHOLE = lines("lines-whole", BodyKind.WHOLE);

    static Map<String, Object> flow(long maxBodyBytes, List<Map<String, Object>> steps) {
        return Map.of("schemaVersion", 1, "kind", "flow", "id", "f", "version", "1.0.0", "from", Map.of("endpoint", "in"),
                "delivery", Map.of("mode", "sync"), "limits", Map.of("maxBodyBytes", maxBodyBytes), "steps", steps);
    }

    FlowBuilder builder() {
        return new FlowBuilder(rt.runtime(), List.of(factory("whole", BodyKind.WHOLE), factory("stream", BodyKind.STREAM)), List.of(LINES, LINES_WHOLE), Map.<String, EndpointConfig>of());
    }

    @Test
    void aWholeStepOnAFlowAboveTheTreeLimitIsABuildErrorNamingTheStepAndBothLimits() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> builder().build(FlowDefinition.from(flow(2 * 1024 * 1024,
                List.of(Map.of("type", "stream", "name", "gz"), Map.of("type", "whole", "name", "map")))), Path.of("")));
        assertTrue(e.getMessage().contains("map") && e.getMessage().contains("2097152") && e.getMessage().contains("1048576") && e.getMessage().contains("whole"), e.getMessage());
        assertTrue(assertThrows(IllegalStateException.class, () -> builder().build(FlowDefinition.from(flow(2 * 1024 * 1024,
                List.of(Map.of("type", "filter", "name", "f", "language", "lines-whole", "when", "x")))), Path.of(""))).getMessage().contains("'f'"),
                "a built-in over a whole-body language is a whole step too");
    }

    @Test
    void streamStepsAndFlowsUnderTheTreeLimitBuildAndUndeclaredMeansWhole() {
        assertEquals("yes", builder().build(FlowDefinition.from(flow(2 * 1024 * 1024, List.of(Map.of("type", "stream", "name", "gz")))), Path.of(""))
                .apply(rt.ingress("f", Headers.empty(), Buffer.buffer("<x/>"))).header("seen-gz").orElseThrow(), "a stream step never needs the whole body");
        assertEquals("yes", builder().build(FlowDefinition.from(flow(1024 * 1024, List.of(Map.of("type", "whole", "name", "map")))), Path.of(""))
                .apply(rt.ingress("f", Headers.empty(), Buffer.buffer("<x/>"))).header("seen-map").orElseThrow(), "at the tree limit a whole step is fine");
        StepFactory silent = new StepFactory() {
            @Override public String type() { return "silent"; }
            @Override public String configSchema() { return "{}"; }
            @Override public Stage create(StepConfig step, StepResources res) { return Stage.of(e -> e); }
        };
        assertEquals(BodyKind.WHOLE, silent.bodyKind(), "a factory that declares nothing is taken to need the whole body — the safe reading");
        Expressions mute = new Expressions() {
            @Override public String language() { return "x"; }
            @Override public Predicate<Envelope> predicate(String expression, StepResources resources) { return e -> true; }
            @Override public Function<Envelope, Optional<String>> value(String expression, StepResources resources) { return e -> Optional.empty(); }
        };
        assertEquals(BodyKind.WHOLE, mute.bodyKind(), "and so is a language");
        assertThrows(UnsupportedOperationException.class, () -> mute.parts("a", null), "a language that cannot split says so");
    }

    @Test
    void aSplitOverAWholeBodyLanguageIsItselfAWholeStepAndDoesNotLiftTheRule() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> builder().build(FlowDefinition.from(flow(2 * 1024 * 1024, List.of(
                Map.of("type", "split", "name", "tree-split", "language", "lines-whole", "xpath", "\\n"),
                Map.of("type", "whole", "name", "map")))), Path.of("")), "the splitter reads the tree: it is refused before anything behind it");
        assertTrue(e.getMessage().contains("tree-split") && e.getMessage().contains("WHOLE"), e.getMessage());
        Pipeline under = builder().build(FlowDefinition.from(flow(1024, List.of(
                Map.of("type", "split", "name", "tree-split", "language", "lines-whole", "xpath", "\\n"),
                Map.of("type", "whole", "name", "map")))), Path.of(""));
        Envelope parent = rt.ingress("f", Headers.empty(), Buffer.buffer("x\ny"));
        assertEquals(parent.id(), under.apply(parent).id(), "under the tree limit a whole-body split builds and runs");
        assertTrue(rt.blobs().contains(io.flottila.api.BlobKey.attachment(parent.id(), 1, "split-1")));
    }

    @Test
    void aSplitBeforeTheWholeStepMakesTheFlowBuildAndTheChildrenRunTheRest() {
        Pipeline p = builder().build(FlowDefinition.from(flow(2 * 1024 * 1024, List.of(
                Map.of("type", "split", "name", "lines", "language", "lines", "xpath", "\\n"),
                Map.of("type", "whole", "name", "map")))), Path.of(""));
        Envelope parent = rt.ingress("f", Headers.empty().with("h", "v"), Buffer.buffer("one\ntwo\nthree"));
        Envelope back = p.apply(parent);
        assertEquals(parent.id(), back.id(), "the parent comes back");
        assertEquals(0, back.step(), "handed back as it was, like filter: the split does not advance the parent");
        List<String> seenIds = new java.util.ArrayList<>();
        assertTrue(rt.blobs().contains(io.flottila.api.BlobKey.attachment(parent.id(), 1, "split-2")), "the parts live under the parent's id and the split's step");
        Pipeline counting = new FlowBuilder(rt.runtime(), List.of(new StepFactory() {
            @Override public String type() { return "collect"; }
            @Override public String configSchema() { return "{}"; }
            @Override public Stage create(StepConfig step, StepResources res) { return Stage.of(e -> { seenIds.add(e.id() + ":" + e.get(Split.SPLIT_INDEX).orElseThrow() + "/" + e.get(Split.SPLIT_TOTAL).orElseThrow() + ":" + e.body().whole()); return e; }); }
        }), List.of(LINES), Map.<String, EndpointConfig>of()).build(FlowDefinition.from(flow(4096, List.of(
                Map.of("type", "split", "name", "lines", "language", "lines", "xpath", "\\n"), Map.of("type", "collect", "name", "c")))), Path.of(""));
        Envelope parent2 = rt.ingress("f", Headers.empty(), Buffer.buffer("a\nb"));
        counting.apply(parent2);
        assertEquals(2, seenIds.size());
        assertTrue(seenIds.get(0).endsWith(":0/2:a") && seenIds.get(1).endsWith(":1/2:b"), seenIds.toString());
        assertTrue(seenIds.stream().noneMatch(s -> s.startsWith(parent2.id() + ":")), "children have their own ids");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> builder().build(FlowDefinition.from(flow(4096, List.of(Map.of("type", "split", "name", "s", "language", "lines")))), Path.of(""))).getMessage().contains("'xpath'"));
    }
}
