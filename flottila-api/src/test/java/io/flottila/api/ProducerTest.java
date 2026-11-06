package io.flottila.api;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-15b (D-113 draft, amends D-100): a producer sees the time its send has left through a {@link Deadline}. The default
 * {@code send(e, d)} of a producer that implements only {@code send(e)}: no time left ({@code d.remaining() <= 0}) fails with a
 * retryable {@link NackException} and starts nothing; time left is {@code send(e)} itself.
 *
 * <p>The API assumed (packet P2-15b): {@code @FunctionalInterface io.flottila.api.Deadline { Duration remaining(); }};
 * {@code default Future<Envelope> Producer.send(Envelope e, Deadline d)} — the refusal is a failed future, not a throw.
 */
class ProducerTest {

    final InMemoryBlobStore blobs = new InMemoryBlobStore(1024);
    final Envelope e = Envelope.ingress("m-1", "orders", Headers.empty(),
            blobs.write(BlobKey.body("m-1", 0), Buffer.buffer("<order/>"), 1024).await());

    /** A producer that implements only {@code send(e)}: each call recorded, answered with {@code answer}. */
    static final class OnlySend implements Producer {
        final List<Envelope> sends = new ArrayList<>();
        final Future<Envelope> answer;

        OnlySend(Future<Envelope> answer) { this.answer = answer; }

        @Override public Future<Envelope> send(Envelope e) {
            sends.add(e);
            return answer;
        }

        @Override public Future<Void> close() { return Future.succeededFuture(); }
    }

    @Test
    void theDefaultSendWithNoTimeLeftFailsWithoutCallingSend() {
        for (Duration left : List.of(Duration.ZERO, Duration.ofNanos(-1), Duration.ofSeconds(-30))) {
            OnlySend p = new OnlySend(Future.succeededFuture(e.withHeader("replied", "yes")));
            Future<Envelope> out = p.send(e, () -> left);
            assertTrue(out.failed(), left + " left: the send fails at once, nothing to wait for: " + out);
            NackException nack = assertInstanceOf(NackException.class, out.cause(), left + " left: a NackException: " + out.cause());
            assertTrue(nack.retryable(), left + " left: retryable — the message may be tried again with fresh time: " + nack);
            assertEquals(List.of(), p.sends, left + " left: send(e) was never called, nothing started");
        }
    }

    @Test
    void theDefaultSendWithTimeLeftIsTheSendItself() {
        Envelope reply = e.withHeader("replied", "yes");
        Future<Envelope> answer = Future.succeededFuture(reply);
        for (Duration left : List.of(Duration.ofNanos(1), Duration.ofSeconds(30))) {
            OnlySend p = new OnlySend(answer);
            Future<Envelope> out = p.send(e, () -> left);
            assertEquals(List.of(e), p.sends, left + " left: send(e) called once, with the envelope as given");
            assertSame(reply, out.await(), left + " left: the answer is send(e)'s");
        }
        Future<Envelope> failed = Future.failedFuture(new IllegalStateException("partner down"));
        OnlySend p = new OnlySend(failed);
        Future<Envelope> out = p.send(e, () -> Duration.ofSeconds(1));
        assertTrue(out.failed());
        assertEquals("partner down", out.cause().getMessage(), "send(e)'s failure as it is");
        assertEquals(1, p.sends.size());
    }
}
