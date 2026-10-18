package io.flottila.core.pattern;

import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Key;
import io.flottila.api.Pipeline;
import io.flottila.api.Stage;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The built-in patterns (ARCHITECTURE §4): route sets a decision, filter ends a message, split fans out children. */
class RouteFilterSplitTest {

    final FakeRuntime rt = FakeRuntime.create();
    final Envelope czk = rt.ingress("f", Headers.empty().with("currency", "CZK"), Buffer.buffer("<czk/>"));
    final Envelope eur = rt.ingress("f", Headers.empty().with("currency", "EUR"), Buffer.buffer("<eur/>"));
    final Key<String> PARTNER = new Key<>("PARTNER");

    Body part(Envelope parent, int i, String xml) {
        return rt.blobs().write(BlobKey.attachment(parent.id(), parent.step() + 1, "split-" + i), Buffer.buffer(xml), rt.blobs().treeLimit()).await();
    }

    static List<Envelope> run(Stage stage, Envelope e) {
        List<Envelope> downstream = new ArrayList<>();
        stage.apply(e, x -> { downstream.add(x); return x; });
        return downstream;
    }

    @Test
    void routeSetsThePropOfTheFirstMatchingBranchOrTheOtherwiseValue() {
        Route route = new Route(PARTNER, List.of(
                new Route.Branch("domestic", e -> "CZK".equals(e.headers().get("currency"))),
                new Route.Branch("also-domestic", e -> true)), Optional.of("foreign"));
        assertEquals(Optional.of("domestic"), run(route, czk).get(0).get(PARTNER), "first match wins");
        assertEquals(Optional.of("also-domestic"), run(route, eur).get(0).get(PARTNER));
        Route noCatchAll = new Route(PARTNER, List.of(new Route.Branch("domestic", e -> "CZK".equals(e.headers().get("currency")))), Optional.of("foreign"));
        assertEquals(Optional.of("foreign"), run(noCatchAll, eur).get(0).get(PARTNER));
        Route strict = new Route(PARTNER, List.of(new Route.Branch("domestic", e -> false)), Optional.empty());
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> run(strict, eur), "no route and no otherwise: never dropped silently (ARCHITECTURE: unknown route → error or DLQ)");
        assertTrue(ex.getMessage().contains("PARTNER"), ex.getMessage());
        assertThrows(IllegalArgumentException.class, () -> new Route(PARTNER, List.of(), Optional.of("x")), "a route needs at least one branch");
    }

    @Test
    void filterLetsMatchingMessagesThroughAndEndsTheOthersWithoutCallingTheRest() {
        Filter onlyCzk = new Filter(e -> "CZK".equals(e.headers().get("currency")));
        assertEquals(1, run(onlyCzk, czk).size());
        List<Envelope> downstream = new ArrayList<>();
        Envelope returned = onlyCzk.apply(eur, x -> { downstream.add(x); return x; });
        assertTrue(downstream.isEmpty(), "filtered: the rest of the pipeline never runs");
        assertSame(eur, returned, "the message is handed back as it was; the runtime records it as filtered");
    }

    @Test
    void splitFansOutChildrenWithParentIdIndexAndTotalAndReturnsTheParent() {
        Envelope parent = rt.ingress("f", Headers.empty(), Buffer.buffer("<l><i>1</i><i>2</i><i>3</i></l>"));
        // the parts are keyed by the parent's id and the child's step, one part name each: bytes exist once, the children reference them
        Split split = new Split(e -> List.of(part(e, 0, "<i>1</i>"), part(e, 1, "<i>2</i>"), part(e, 2, "<i>3</i>")), rt.ids());
        List<Envelope> children = new ArrayList<>();
        Envelope returned = split.apply(parent, c -> { children.add(c); return c; });
        assertSame(parent, returned);
        assertEquals(3, children.size());
        for (int i = 0; i < 3; i++) {
            Envelope c = children.get(i);
            assertEquals(Optional.of(parent.id()), c.get(Split.PARENT_ID));
            assertEquals(Optional.of(i), c.get(Split.SPLIT_INDEX));
            assertEquals(Optional.of(3), c.get(Split.SPLIT_TOTAL));
            assertEquals(parent.step() + 1, c.step(), "a child is the next step of its parent");
            assertEquals("f", c.flow());
            assertEquals("<i>" + (i + 1) + "</i>", c.body().whole().toString());
        }
        assertEquals(3, children.stream().map(Envelope::id).distinct().count(), "every child has its own id from the injected generator");
        assertTrue(children.stream().noneMatch(c -> c.id().equals(parent.id())));
        Split empty = new Split(e -> List.of(), rt.ids());
        assertTrue(run(empty, parent).isEmpty(), "zero children: nothing downstream, no error");
    }

    @Test
    void patternsComposeInAPipeline() {
        Pipeline p = Pipeline.of(List.of(
                new Route(PARTNER, List.of(new Route.Branch("domestic", e -> "CZK".equals(e.headers().get("currency")))), Optional.of("foreign")),
                new Filter(e -> e.get(PARTNER).orElse("").equals("domestic")),
                Stage.of(e -> e.withHeader("sent", "yes"))));
        assertEquals("yes", p.apply(czk).header("sent").orElseThrow());
        assertTrue(p.apply(eur).header("sent").isEmpty(), "foreign is filtered before the send");
    }
}
