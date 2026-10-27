package io.flottila.core.flow;

import io.flottila.api.Producer;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The producers {@link FlowBuilder} created for one built flow (FIX-30): a {@link Delivery}'s or a {@link FlowWorker}'s, closed
 * together, once. A producer whose close fails — its future, or a RuntimeException from {@code close()} itself — is logged at WARNING
 * and stops no other; the close completes, never fails, once every producer's close has settled. An {@link Error} thrown by a
 * producer's {@code close()} is not caught: it propagates to the caller, the producers after it are not closed and the close
 * never completes.
 */
final class Producers {

    private static final System.Logger LOG = System.getLogger(Producers.class.getName());

    /** None: an at-least-once delivery only saves. */
    static final Producers NONE = new Producers("-", List.of());

    private final String flow;
    private final List<Producer> all;
    private final AtomicBoolean asked = new AtomicBoolean();
    private final Promise<Void> closed = Promise.promise();

    Producers(String flow, List<Producer> all) {
        this.flow = Objects.requireNonNull(flow, "flow");
        this.all = List.copyOf(all);
    }

    /** Closes every producer on the first call (see the class comment); every call returns that one close. */
    Future<Void> close() {
        if (asked.compareAndSet(false, true)) closeAll(flow, all).onComplete(closed);
        return closed.future();
    }

    /** Closes each of {@code producers}, a failure logged and the others closed all the same; completes once every close settled. */
    static Future<Void> closeAll(String flow, List<Producer> producers) {
        List<Future<Void>> settled = new ArrayList<>(producers.size());
        for (Producer p : producers) {
            Future<Void> closing;
            try {
                closing = p.close();
            } catch (RuntimeException thrown) {   // a producer that throws instead of failing its future: the same failed close
                closing = Future.failedFuture(thrown);
            }
            settled.add(closing.recover(failure -> {
                LOG.log(System.Logger.Level.WARNING, "flow " + flow + ": a producer did not close (" + p + "): " + failure, failure);
                return Future.succeededFuture();
            }));
        }
        return Future.all(settled).mapEmpty();
    }
}
