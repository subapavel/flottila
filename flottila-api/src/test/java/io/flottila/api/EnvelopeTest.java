package io.flottila.api;

import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvelopeTest {

    static final Key<Integer> SPLIT_INDEX = new Key<>("splitIndex");

    final InMemoryBlobStore store = new InMemoryBlobStore(1_000);
    final Body body0 = store.write(BlobKey.body("m-1", 0), Buffer.buffer("<a/>"), 100).await();
    final Body body1 = store.write(BlobKey.body("m-1", 1), Buffer.buffer("<b/>"), 100).await();
    final Envelope ingress = Envelope.ingress("m-1", "invoice", Headers.empty().with("content-type", "text/xml"), body0);

    @Test
    void ingressIsStepZeroWithEmptyPropsKeysAndAttachments() {
        assertEquals("m-1", ingress.id());
        assertEquals("invoice", ingress.flow());
        assertEquals(0, ingress.step());
        assertEquals(Optional.of("text/xml"), ingress.header(Envelope.H_CONTENT_TYPE));
        assertEquals(Props.empty(), ingress.props());
        assertEquals(Keys.empty(), ingress.keys());
        assertEquals(Map.of(), ingress.attachments());
        assertSame(body0, ingress.body());
    }

    @Test
    void everyWithReturnsANewEnvelopeAndLeavesTheOriginalUntouched() {
        Envelope e2 = ingress.withHeader("x-trace", "t1").with(SPLIT_INDEX, 3).withKey("invoiceId", "4711");
        assertNotSame(ingress, e2);
        assertEquals(Optional.empty(), ingress.header("x-trace"));
        assertEquals(Optional.empty(), ingress.get(SPLIT_INDEX));
        assertEquals(Optional.empty(), ingress.key("invoiceId"));
        assertEquals(Optional.of("t1"), e2.header("x-trace"));
        assertEquals(Optional.of(3), e2.get(SPLIT_INDEX));
        assertEquals(Optional.of("4711"), e2.key("invoiceId"));
        assertEquals(0, e2.step(), "with* never changes the step");
    }

    @Test
    void nextMovesToTheNextStepWithANewBodyAndFreshViews() {
        AtomicInteger parsed = new AtomicInteger();
        String v0 = ingress.view(String.class, b -> "parsed#" + parsed.incrementAndGet());
        Envelope next = ingress.next(body1);
        assertEquals(1, next.step());
        assertSame(body1, next.body());
        assertEquals(ingress.headers(), next.headers());
        assertEquals(ingress.props(), next.props());
        assertEquals(ingress.keys(), next.keys());
        String v1 = next.view(String.class, b -> "parsed#" + parsed.incrementAndGet());
        assertEquals("parsed#1", v0);
        assertEquals("parsed#2", v1, "a new body means the memoized view is gone");
    }

    @Test
    void viewsAreMemoizedAndSharedByReferenceAcrossWith() {
        AtomicInteger parsed = new AtomicInteger();
        Envelope e2 = ingress.withHeader("x", "y");
        String a = ingress.view(String.class, b -> "parsed#" + parsed.incrementAndGet());
        String b = e2.view(String.class, bb -> "parsed#" + parsed.incrementAndGet());
        assertSame(a, b, "same body, same view instance");
        assertEquals(1, parsed.get(), "the parser ran once");
        assertSame(ingress.views(), e2.views());
        assertSame(ingress.body(), e2.body());
    }

    @Test
    void attachmentsAreKeptSeparateFromTheBodyAndImmutable() {
        Part pdf = new Part("invoice.pdf", "application/pdf", body1);
        Envelope e2 = ingress.withAttachment(pdf);
        assertEquals(Map.of("invoice.pdf", pdf), e2.attachments());
        assertEquals(Map.of(), ingress.attachments());
        assertThrows(UnsupportedOperationException.class, () -> e2.attachments().put("x", pdf));
        assertSame(body0, e2.body());
    }

    @Test
    void replayCarriesTheOriginalIdAsAProp() {
        Envelope replay = ingress.withId("m-2").with(Envelope.REPLAY_OF, "m-1");
        assertEquals("m-2", replay.id());
        assertEquals(Optional.of("m-1"), replay.get(Envelope.REPLAY_OF));
        assertEquals(Optional.empty(), ingress.get(Envelope.REPLAY_OF));
    }

    @Test
    void typedPropsRoundTripThroughTheirKey() {
        Key<Long> bytes = new Key<>("bytes");
        Envelope e2 = ingress.with(bytes, 42L).withHeaders(Map.of("a", "1", "b", "2"));
        assertEquals(Optional.of(42L), e2.get(bytes));
        assertEquals(Optional.of("1"), e2.header("a"));
        assertEquals(Optional.of("2"), e2.header("b"));
        assertTrue(e2.props().asMap().containsKey("bytes"));
    }

    @Test
    void nullBodyOrIdIsRejected() {
        assertThrows(NullPointerException.class, () -> Envelope.ingress(null, "f", Headers.empty(), body0));
        assertThrows(NullPointerException.class, () -> Envelope.ingress("m", "f", Headers.empty(), null));
        assertThrows(NullPointerException.class, () -> ingress.next(null));
    }

    @Test
    void advanceMovesToTheNextStepWithTheSameBodyAndEverythingElse() {
        Envelope e = Envelope.ingress("m-1", "invoice", Headers.empty().with("a", "b"), body0).withKey("k", "v");
        Envelope next = e.advance();
        assertEquals(1, next.step());
        assertEquals(0, e.step(), "the original is untouched");
        assertSame(e.body(), next.body(), "the body is shared by reference");
        assertSame(e.views(), next.views(), "and so are the parsed views");
        assertEquals(e.headers(), next.headers());
        assertEquals(e.keys(), next.keys());
        assertEquals(e.id(), next.id());
        assertEquals(2, next.advance().step());
    }
}
