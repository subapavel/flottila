package dev.rail8.core.flow;

import dev.rail8.api.Producer;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The producers {@link FlowBuilder} created for one built flow (FIX-30): a {@link Delivery}'s or a {@link FlowWorker}'s, closed
 * together, once. A producer whose close fails — its future, or anything {@code close()} itself throws, an {@link Error} and a
 * sneaky checked exception included (FIX-141, F-30) — is logged at WARNING and stops no other; the close completes, never fails,
 * once every producer's close has settled.
 *
 * <p>When a {@code close()} throws an {@code InterruptedException}, the flag it swallowed is restored mid-loop, so every
 * producer after the interrupting one is asked to close on a thread whose interrupt flag is now set and a later blocking
 * {@code close()} may fail fast because of it — a consequence of honouring the flag, not a defect; the loop still asks each of
 * them (pinned by ProducersCloseTest.anInterruptFromAProducersCloseRestoresTheFlagAndStillClosesTheOthers). A producer whose
 * returned future instead <em>fails</em> with an {@code InterruptedException} leaves the flag alone: the thread that was
 * interrupted is in general not the one that asked for the close — the failure is observed on whichever thread completed the
 * future — and setting the flag here would invent an interrupt nobody raised on the closing thread. The price is a producer that
 * caught the interrupt itself and failed its future on the closing thread: that thread's cancellation is then genuinely lost,
 * and nothing in the failure lets this loop tell that case from the common one (D-098 (2), pinned by
 * ProducersCloseTest.aCloseWhoseFutureFailsWithAnInterruptedExceptionLeavesTheFlagClear).
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
            } catch (Throwable thrown) {   // a producer that throws instead of failing its future, an Error or a sneaky checked exception too (FIX-141, F-30): the same failed close; a narrower catch abandons the loop and strands every later producer, pinned by ProducersCloseTest.theLoopFinishesTheThirdProducerIsAskedWhenTheMiddleOnesCloseThrows
                // Swallowed, an interrupt leaves the caller's next blocking call unaware that its thread was cancelled.
                // An InterruptedException reaches this catch at all because the enclosing clause is wide enough to see a sneaky
                // checked throwable (FIX-141, F-30); the rule this line implements is D-098 (1): a swallowed interrupt is
                // restored for the thread that was interrupted, so the cancelling caller still sees it.
                // Pinned by ProducersCloseTest.anInterruptFromAProducersCloseRestoresTheFlagAndStillClosesTheOthers
                if (thrown instanceof InterruptedException) Thread.currentThread().interrupt();
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
