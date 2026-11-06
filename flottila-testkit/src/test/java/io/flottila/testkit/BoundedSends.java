package io.flottila.testkit;

import io.flottila.api.Deadline;
import io.flottila.api.Envelope;
import io.flottila.api.NackException;
import io.flottila.api.Timer;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * P2-15b: what the testkit's own test components do to pass the contract's message-id and deadline cases — the shape a real
 * producer has, written once for the fixtures ({@link LoopbackComponent}, {@link EphemeralAddressComponent}, the self-test's
 * variants). {@link #carryingItsId} sets {@link Envelope#H_MESSAGE_ID} through {@link Envelope#withOutboundId()}, dropping any
 * value forwarded under that name in any case; {@link #within} starts nothing with no time left and cuts a send still
 * unanswered when the deadline, re-read on the timer, has run out.
 */
final class BoundedSends {

    private BoundedSends() {}

    /** {@code e} with exactly one {@link Envelope#H_MESSAGE_ID}: {@link Envelope#withOutboundId()}, as every real producer does. */
    static Envelope carryingItsId(Envelope e) {
        return e.withOutboundId();
    }

    /**
     * No time left: a retryable Nack, {@code send} never called. Else {@code send}'s answer, unless {@code d} — re-read each time
     * {@code timer} fires, re-armed while time is left — runs out first: then a retryable Nack. A {@code null} timer cuts nothing.
     */
    static Future<Envelope> within(Deadline d, Timer timer, Supplier<Future<Envelope>> send) {
        return within(d, timer, send, true, () -> new NackException("deadline passed: the send was cut", true));
    }

    /**
     * {@link #within(Deadline, Timer, Supplier)} with the self-test's defects: {@code reread} false cuts when the timer first
     * fires, at the deadline the send started with, whatever the deadline says then; a cut fails with {@code cut}.
     */
    static Future<Envelope> within(Deadline d, Timer timer, Supplier<Future<Envelope>> send, boolean reread, Supplier<Throwable> cut) {
        Duration left = d.remaining();
        if (!left.isPositive()) return Future.failedFuture(new NackException("deadline passed: not sent", true));
        Future<Envelope> sent = send.get();
        if (timer == null) return sent;
        Promise<Envelope> out = Promise.promise();
        AtomicReference<Timer.Cancellable> watch = new AtomicReference<>();
        Runnable[] check = new Runnable[1];
        check[0] = () -> {
            if (out.future().isComplete()) return;
            Duration now = reread ? d.remaining() : Duration.ZERO;
            if (now.isPositive()) watch.set(timer.schedule(now, check[0]));
            else out.tryFail(cut.get());
        };
        watch.set(timer.schedule(left, check[0]));
        sent.onComplete(r -> {
            watch.get().cancel();
            if (r.succeeded()) out.tryComplete(r.result());
            else out.tryFail(r.cause());
        });
        return out.future();
    }
}
