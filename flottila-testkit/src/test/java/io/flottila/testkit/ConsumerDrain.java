package io.flottila.testkit;

import io.flottila.api.Ack;
import io.flottila.api.Envelope;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.util.function.Function;

/**
 * The drain a test component's consumer owes {@link ComponentContract} (D-056): it counts the handler answers still
 * outstanding, and the future {@link #stop()} returns completes once the last of them has completed, success or failure
 * alike — at once when none is outstanding. A component wraps every handler call in {@link #track(Future)}, or hands it to
 * {@link #deliver}, which also runs it on a virtual thread of its own (D-057).
 */
final class ConsumerDrain {

    private int outstanding;          // guarded by this
    private Promise<Void> drained;    // guarded by this; set by the first stop()

    /** Counts {@code answer} as outstanding until it completes; returns it unchanged. */
    Future<Ack> track(Future<Ack> answer) {
        synchronized (this) { outstanding++; }
        answer.onComplete(done -> answered());
        return answer;
    }

    private void answered() {
        Promise<Void> done;
        synchronized (this) {
            outstanding--;
            done = outstanding == 0 ? drained : null;
        }
        if (done != null) done.tryComplete();
    }

    /**
     * Calls {@code handler} with {@code e} on a virtual thread of its own and counts the answer as outstanding from this
     * moment — before the handler runs, so a stop that begins while the handler is still running waits for it. A handler
     * that throws answers with that failure.
     */
    Future<Ack> deliver(Function<Envelope, Future<Ack>> handler, Envelope e) {
        Promise<Ack> answer = Promise.promise();
        track(answer.future());
        Thread.ofVirtual().name("test-delivery-" + e.id()).start(() -> {
            try {
                handler.apply(e).onComplete(answer);
            } catch (Throwable failed) {
                answer.tryFail(failed);
            }
        });
        return answer.future();
    }

    /** Completes once no answer is outstanding; the same future for every call. */
    Future<Void> stop() {
        Promise<Void> done;
        boolean idle;
        synchronized (this) {
            if (drained == null) drained = Promise.promise();
            done = drained;
            idle = outstanding == 0;
        }
        if (idle) done.tryComplete();
        return done.future();
    }
}
