package io.flottila.api;

import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class PipelineTest {

    static final Key<String> TRACE = new Key<>("trace");

    final InMemoryBlobStore store = new InMemoryBlobStore(1_000);
    final Envelope in = Envelope.ingress("m-1", "f", Headers.empty(),
            store.write(BlobKey.body("m-1", 0), Buffer.buffer("x"), 10).await());

    private static Step tag(String name) {
        return e -> e.with(TRACE, e.get(TRACE).map(t -> t + "," + name).orElse(name));
    }

    @Test
    void stagesRunInOrderAndEachStepSeesThePredecessorsResult() {
        Pipeline p = Pipeline.of(List.of(Stage.of(tag("a")), Stage.of(tag("b")), Stage.of(tag("c"))));
        assertEquals("a,b,c", p.apply(in).get(TRACE).orElseThrow());
    }

    @Test
    void emptyPipelineIsIdentity() {
        assertSame(in, Pipeline.of(List.of()).apply(in));
        assertSame(in, Pipeline.END.apply(in));
    }

    @Test
    void aStageThatDoesNotCallRestStopsThePipeline() {
        List<String> ran = new ArrayList<>();
        Stage filter = (e, rest) -> { ran.add("filter"); return e; };
        Pipeline p = Pipeline.of(List.of(filter, Stage.of(e -> { ran.add("after"); return e; })));
        assertSame(in, p.apply(in));
        assertEquals(List.of("filter"), ran);
    }

    @Test
    void aStageMayCallRestOncePerEmission() {
        List<String> seen = new ArrayList<>();
        Stage split = (e, rest) -> {
            Envelope last = e;
            for (int i = 0; i < 3; i++) last = rest.apply(e.with(TRACE, "part" + i));
            return last;
        };
        Pipeline p = Pipeline.of(List.of(split, Stage.of(e -> { seen.add(e.get(TRACE).orElseThrow()); return e; })));
        Envelope out = p.apply(in);
        assertEquals(List.of("part0", "part1", "part2"), seen);
        assertEquals("part2", out.get(TRACE).orElseThrow());
    }

    @Test
    void restOfAStageIsTheRemainingPipelineNotTheWholeOne() {
        List<String> ran = new ArrayList<>();
        Stage twice = (e, rest) -> rest.apply(rest.apply(e));
        Pipeline p = Pipeline.of(List.of(Stage.of(e -> { ran.add("first"); return e; }), twice,
                Stage.of(e -> { ran.add("last"); return e; })));
        p.apply(in);
        assertEquals(List.of("first", "last", "last"), ran);
    }

    @Test
    void stepStageAppliesTheStepThenTheRest() {
        Stage s = Stage.of(tag("s"));
        Envelope out = s.apply(in, e -> e.with(TRACE, e.get(TRACE).orElseThrow() + ",rest"));
        assertEquals("s,rest", out.get(TRACE).orElseThrow());
    }
}
