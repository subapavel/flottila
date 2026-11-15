package io.flottila.api;

import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * P2-15b (D-101 (3), D-113 draft): the message id a producer sends its partner, computed in one place. {@code outboundId()} is
 * the message's id; {@code withOutboundId()} is the envelope as a producer hands it over — one {@code x-message-id}, that id,
 * every header named so in any case dropped, every other header and field kept.
 */
class EnvelopeOutboundIdTest {

    final InMemoryBlobStore store = new InMemoryBlobStore(1_000);
    final Body body = store.write(BlobKey.body("m-7", 0), Buffer.buffer("<a/>"), 1_000).await();

    @Test
    void theOutboundIdIsTheMessageId() {
        Envelope e = Envelope.ingress("m-7", "orders", Headers.empty(), body);
        assertEquals("m-7", e.outboundId());
        assertEquals("x-message-id", Envelope.H_MESSAGE_ID, "the wire name, lower case (P2-15 decision 4: not renameable now)");
    }

    @Test
    void everyForwardedMessageIdIsReplacedByTheEnvelopesOwn() {
        Envelope e = Envelope.ingress("m-7", "orders", new Headers(Map.of("X-Message-ID", "up-1", "x-message-id", "up-2", "X-MESSAGE-ID", "up-3",
                "x-request-id", "r-1", "content-type", "application/xml")), body).with(Envelope.ATTEMPT, 2).withKey("order", "42").advance();
        Envelope out = e.withOutboundId();
        assertEquals(Map.of("x-message-id", "m-7", "x-request-id", "r-1", "content-type", "application/xml"), out.headers().asMap(),
                "one x-message-id, the envelope's; the three forwarded spellings dropped, the other headers kept");
        assertEquals(e.id(), out.id());
        assertEquals(e.flow(), out.flow());
        assertEquals(1, out.step(), "the step as it was");
        assertEquals(e.props(), out.props());
        assertEquals(e.keys(), out.keys());
        assertSame(e.body(), out.body(), "the body by reference");
        assertSame(e.views(), out.views(), "its views by reference");
        assertEquals(e.attachments(), out.attachments());
    }

    @Test
    void anEnvelopeWithoutOneGetsOne() {
        Envelope plain = Envelope.ingress("m-8", "orders", Headers.empty(), body);
        assertEquals(Map.of("x-message-id", "m-8"), plain.withOutboundId().headers().asMap());
        Envelope near = Envelope.ingress("m-9", "orders", new Headers(Map.of("x-message-ids", "a", "message-id", "b")), body);
        assertEquals(Map.of("x-message-id", "m-9", "x-message-ids", "a", "message-id", "b"), near.withOutboundId().headers().asMap(),
                "only that very name is replaced, not one that merely resembles it");
    }
}
