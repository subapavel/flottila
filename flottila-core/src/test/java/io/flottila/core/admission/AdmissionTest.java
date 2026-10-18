package io.flottila.core.admission;

import io.flottila.testkit.NoBlockedPlatformThreads;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-flow admission: a budget of requests AND bytes (CLAUDE.md, ARCHITECTURE §4). Over the limit the
 * non-blocking path answers "no" (HTTP 503), the blocking path waits in FIFO order (pollers pause).
 */
@ExtendWith(NoBlockedPlatformThreads.class)
@Timeout(60)   // turns a lost wake-up into a failure instead of a hang; no assertion depends on elapsed time
class AdmissionTest {

    @Test
    void budgetsAreFiniteAndPositive() {
        assertThrows(IllegalArgumentException.class, () -> new Admission(0, 1024));
        assertThrows(IllegalArgumentException.class, () -> new Admission(4, 0));
        assertThrows(IllegalArgumentException.class, () -> new Admission(-1, 1024));
        assertThrows(IllegalArgumentException.class, () -> new Admission(4, -1));
        Admission a = new Admission(4, 1024);
        assertEquals(4, a.maxRequests());
        assertEquals(1024, a.maxBytes());
    }

    @Test
    void tryAcquireCountsRequestsAndBytesAndRefusesOverEitherBudget() {
        Admission a = new Admission(2, 1000);
        Optional<Admission.Permit> p1 = a.tryAcquire(600);
        assertTrue(p1.isPresent());
        assertEquals(1, a.inFlightRequests());
        assertEquals(600, a.inFlightBytes());
        assertTrue(a.tryAcquire(500).isEmpty(), "1100 bytes would exceed the byte budget");
        Optional<Admission.Permit> p2 = a.tryAcquire(400);
        assertTrue(p2.isPresent(), "exactly the budget is admitted");
        assertEquals(2, a.inFlightRequests());
        assertEquals(1000, a.inFlightBytes());
        assertTrue(a.tryAcquire(0).isEmpty(), "two requests in flight: the request budget refuses even an empty body");
        p1.get().close();
        assertEquals(1, a.inFlightRequests());
        assertEquals(400, a.inFlightBytes());
        assertTrue(a.tryAcquire(600).isPresent());
    }

    @Test
    void aPermitReleasesOnceHoweverOftenItIsClosed() {
        Admission a = new Admission(2, 100);
        Admission.Permit p = a.tryAcquire(40).orElseThrow();
        assertEquals(40, p.bytes());
        assertFalse(p.released());
        p.close();
        p.close();
        assertTrue(p.released());
        assertEquals(0, a.inFlightRequests());
        assertEquals(0, a.inFlightBytes());
    }

    @Test
    void aBodyLargerThanTheWholeByteBudgetCanNeverBeAdmittedAndSaysSo() {
        Admission a = new Admission(2, 100);
        assertThrows(IllegalArgumentException.class, () -> a.tryAcquire(101));
        assertThrows(IllegalArgumentException.class, () -> a.tryAcquire(-1));
        assertThrows(IllegalArgumentException.class, () -> a.acquire(101), "a blocking acquire must not wait forever for the impossible");
        assertEquals(0, a.inFlightRequests());
    }

    @Test
    void blockingAcquireWaitsAndIsAdmittedInFifoOrderEvenWhenALaterSmallRequestWouldFit() throws Exception {
        Admission a = new Admission(10, 100);
        Admission.Permit holder = a.tryAcquire(80).orElseThrow();
        CountDownLatch bigAdmitted = new CountDownLatch(1);
        CountDownLatch smallAdmitted = new CountDownLatch(1);
        Thread big = Thread.ofVirtual().start(() -> {
            try (Admission.Permit p = a.acquire(50)) { bigAdmitted.countDown(); } catch (InterruptedException e) { throw new AssertionError(e); }
        });
        waitUntil(() -> a.waiting() == 1);
        Thread small = Thread.ofVirtual().start(() -> {
            try (Admission.Permit p = a.acquire(10)) { smallAdmitted.countDown(); } catch (InterruptedException e) { throw new AssertionError(e); }
        });
        waitUntil(() -> a.waiting() == 2);
        // the FIFO fact: the small request fits right now and is still not admitted, because the 50-byte request is ahead of it
        assertEquals(80, a.inFlightBytes(), "10 bytes would fit, but the 50-byte request is ahead in the queue");
        assertEquals(1, a.inFlightRequests());
        assertEquals(1, smallAdmitted.getCount());
        holder.close();
        assertTrue(bigAdmitted.await(5, TimeUnit.SECONDS));
        assertTrue(smallAdmitted.await(5, TimeUnit.SECONDS));
        big.join(); small.join();
        assertEquals(0, a.waiting());
        assertEquals(0, a.inFlightBytes());
    }

    @Test
    void nonBlockingCallersNeverJumpTheQueue() throws Exception {
        Admission a = new Admission(10, 100);
        Admission.Permit holder = a.tryAcquire(80).orElseThrow();
        Thread waiter = Thread.ofVirtual().start(() -> {
            try (Admission.Permit p = a.acquire(50)) { /* admitted after the holder */ } catch (InterruptedException e) { throw new AssertionError(e); }
        });
        waitUntil(() -> a.waiting() == 1);
        assertTrue(a.tryAcquire(10).isEmpty(), "someone is waiting: a non-blocking request that would fit still queues behind, i.e. is refused");
        holder.close();
        waiter.join();
        assertTrue(a.tryAcquire(10).isPresent());
    }

    @Test
    void anAdmittedHeadPassesTheBatonToTheNextWaiterThatFitsWhileItStillHoldsItsPermit() throws Exception {
        Admission a = new Admission(10, 100);
        Admission.Permit holder = a.tryAcquire(80).orElseThrow();
        CountDownLatch smallAdmitted = new CountDownLatch(1);
        Thread big = Thread.ofVirtual().start(() -> {
            try (Admission.Permit p = a.acquire(50)) { smallAdmitted.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
        });
        waitUntil(() -> a.waiting() == 1);
        Thread small = Thread.ofVirtual().start(() -> {
            try (Admission.Permit p = a.acquire(10)) { smallAdmitted.countDown(); } catch (InterruptedException e) { throw new AssertionError(e); }
        });
        waitUntil(() -> a.waiting() == 2);
        holder.close();
        assertTrue(smallAdmitted.await(5, TimeUnit.SECONDS), "the big request, once admitted, woke the small one behind it without releasing anything");
        big.join(); small.join();
        assertEquals(0, a.inFlightRequests());
    }

    @Test
    void anInterruptedHeadWaiterWakesTheNextOneThatFits() throws Exception {
        Admission a = new Admission(10, 100);
        Admission.Permit holder = a.tryAcquire(80).orElseThrow();
        AtomicReference<Throwable> bigOutcome = new AtomicReference<>();
        Thread big = Thread.ofVirtual().start(() -> { try { a.acquire(50); } catch (Throwable t) { bigOutcome.set(t); } });
        waitUntil(() -> a.waiting() == 1);
        CountDownLatch smallAdmitted = new CountDownLatch(1);
        Thread small = Thread.ofVirtual().start(() -> {
            try (Admission.Permit p = a.acquire(10)) { smallAdmitted.countDown(); } catch (InterruptedException e) { throw new AssertionError(e); }
        });
        waitUntil(() -> a.waiting() == 2);
        assertEquals(80, a.inFlightBytes(), "10 bytes fit, but the queue head wants 50");
        big.interrupt();
        assertTrue(smallAdmitted.await(5, TimeUnit.SECONDS), "with the head gone the 10-byte request is admitted at once (holder still present)");
        big.join(); small.join();
        assertTrue(bigOutcome.get() instanceof InterruptedException);
        holder.close();
        assertEquals(0, a.inFlightRequests());
    }

    @Test
    void aReleaseThatStillLeavesTheHeadWaiterTooBigWakesNobody() throws Exception {
        Admission a = new Admission(10, 100);
        Admission.Permit first = a.tryAcquire(40).orElseThrow();
        Admission.Permit second = a.tryAcquire(40).orElseThrow();
        CountDownLatch admitted = new CountDownLatch(1);
        Thread waiter = Thread.ofVirtual().start(() -> {
            try (Admission.Permit p = a.acquire(70)) { admitted.countDown(); } catch (InterruptedException e) { throw new AssertionError(e); }
        });
        waitUntil(() -> a.waiting() == 1);
        CountDownLatch smallAdmitted = new CountDownLatch(1);
        Thread small = Thread.ofVirtual().start(() -> {
            try (Admission.Permit p = a.acquire(5)) { smallAdmitted.countDown(); } catch (InterruptedException e) { throw new AssertionError(e); }
        });
        waitUntil(() -> a.waiting() == 2);
        first.close();                                   // 40 + 70 = 110 > 100: the head still does not fit — and the 5-byte request behind it stays queued too
        assertEquals(2, a.waiting());
        assertEquals(40, a.inFlightBytes());
        assertEquals(1, admitted.getCount(), "not admitted on a release that does not make it fit");
        assertEquals(1, smallAdmitted.getCount(), "strict order: a release wakes the head or nobody, never a fitting waiter behind it");
        second.close();                                  // 0 + 70 fits; the head passes the baton to the 5-byte request
        assertTrue(admitted.await(5, TimeUnit.SECONDS));
        assertTrue(smallAdmitted.await(5, TimeUnit.SECONDS));
        waiter.join(); small.join();
        assertEquals(0, a.inFlightBytes());
    }

    @Test
    void anInterruptedWaiterLeavesTheQueueHoldingNothing() throws Exception {
        Admission a = new Admission(1, 100);
        Admission.Permit holder = a.tryAcquire(10).orElseThrow();
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread waiter = Thread.ofVirtual().start(() -> {
            try { a.acquire(10); } catch (Throwable t) { outcome.set(t); }
        });
        waitUntil(() -> a.waiting() == 1);
        waiter.interrupt();
        waiter.join();
        assertTrue(outcome.get() instanceof InterruptedException, String.valueOf(outcome.get()));
        assertEquals(0, a.waiting());
        assertEquals(1, a.inFlightRequests(), "only the holder");
        holder.close();
        assertTrue(a.tryAcquire(10).isPresent(), "the interrupted waiter left no debris");
    }

    @Test
    void tenThousandVirtualThreadsNeverExceedEitherBudget() throws Exception {
        Admission a = new Admission(64, 64 * 1024);
        int threads = 10_000;
        AtomicLong maxRequests = new AtomicLong();
        AtomicLong maxBytes = new AtomicLong();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            long bytes = 1 + (i % 2048);
            Thread.ofVirtual().start(() -> {
                try (Admission.Permit p = a.acquire(bytes)) {
                    maxRequests.accumulateAndGet(a.inFlightRequests(), Math::max);
                    maxBytes.accumulateAndGet(a.inFlightBytes(), Math::max);
                    Thread.yield();
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(60, TimeUnit.SECONDS), "all 10k admitted and released");
        assertTrue(failures.isEmpty(), String.valueOf(failures.peek()));
        assertTrue(maxRequests.get() <= 64, "max in-flight requests observed: " + maxRequests);
        assertTrue(maxBytes.get() <= 64 * 1024, "max in-flight bytes observed: " + maxBytes);
        assertTrue(maxRequests.get() > 1, "the test actually ran concurrently (observed " + maxRequests + ")");
        assertEquals(0, a.inFlightRequests());
        assertEquals(0, a.inFlightBytes());
        assertEquals(0, a.waiting());
    }

    @Test
    void permitIsNotNullAndCarriesItsBytes() {
        Admission a = new Admission(1, 10);
        Admission.Permit p = a.tryAcquire(7).orElseThrow();
        assertNotNull(p);
        assertEquals(7, p.bytes());
    }

    /** Spins (yielding) until an observable state holds; the bound only turns a hang into a failure, it never decides an outcome. */
    static void waitUntil(java.util.function.BooleanSupplier state) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!state.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("state not reached");
            Thread.onSpinWait();
            Thread.yield();
        }
    }
}
