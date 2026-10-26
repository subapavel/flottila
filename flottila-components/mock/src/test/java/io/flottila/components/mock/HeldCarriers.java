package io.flottila.components.mock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Occupies every carrier thread of the virtual-thread scheduler with a spinning virtual thread until {@link #close()}: a
 * virtual thread started meanwhile is queued and cannot run. Virtual threads are not time-sliced, and a spinner that never
 * parks or yields keeps its carrier, so the interleaving "the sender has returned, the handler's thread has not run yet" is
 * constructed, not hoped for. The caller must be a platform thread; nothing it does while holding may need a virtual thread.
 */
final class HeldCarriers implements AutoCloseable {

    private static final Duration BOUND = Duration.ofSeconds(10);

    private volatile boolean released;
    private final List<Thread> spinners = new ArrayList<>();

    private HeldCarriers() {}

    /** Returns once every carrier runs a spinner; the scheduler's parallelism is its property, else the processor count. */
    static HeldCarriers hold() throws InterruptedException {
        if (Thread.currentThread().isVirtual()) throw new IllegalStateException("hold the carriers from a platform thread");
        int carriers = Integer.getInteger("jdk.virtualThreadScheduler.parallelism", Runtime.getRuntime().availableProcessors());
        HeldCarriers held = new HeldCarriers();
        CountDownLatch spinning = new CountDownLatch(carriers);
        try {
            for (int i = 0; i < carriers; i++) {
                held.spinners.add(Thread.ofVirtual().name("carrier-holder-" + i).start(() -> {
                    spinning.countDown();
                    while (!held.released) Thread.onSpinWait();
                }));
            }
            if (!spinning.await(BOUND.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException((carriers - spinning.getCount()) + " of " + carriers + " carriers held within " + BOUND);
            }
        } catch (RuntimeException | InterruptedException | Error failed) {
            held.close();
            throw failed;
        }
        return held;
    }

    /** Lets the spinners end and waits for them, bounded: the carriers are free again when this returns. */
    @Override
    public void close() throws InterruptedException {
        released = true;
        for (Thread t : spinners) {
            if (!t.join(BOUND)) throw new IllegalStateException("carrier holder " + t + " still spinning after " + BOUND);
        }
    }
}
