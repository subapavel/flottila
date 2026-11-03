package io.flottila.core.admission;

import io.flottila.api.NackException;
import io.flottila.api.Timer;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * TargetLimit (D-099): a bound on the sends in flight to one target system. {@code call} waits on the message's virtual
 * thread, runs the send there and holds the permit until the send's future settles; an interrupt while waiting withdraws the
 * request, and a grant that raced the interrupt is given back. The FIFO order and the maxWait refusal over random arrivals are
 * {@link TargetLimitPropertyTest}'s.
 */
class TargetLimitTest {

    static final String T = "http://partner.example:80";
    static final Duration WAIT = Duration.ofSeconds(5);

    final FakeClock clock = FakeClock.at(Instant.parse("2026-01-01T00:00:00Z"));
    final FakeTimer timer = new FakeTimer(clock);
    /** Every send promise a case leaves open is completed in {@link #settleLeftovers()}: no virtual thread outlives its case. */
    final List<Promise<String>> sends = new ArrayList<>();

    @AfterEach
    void settleLeftovers() {
        sends.forEach(p -> p.tryComplete("cleanup"));
    }

    /** A send whose future the test settles; the promise is registered for cleanup before the supplier hands it out. */
    Promise<String> heldSend() {
        Promise<String> p = Promise.promise();
        sends.add(p);
        return p;
    }

    /** What {@code work} returned or threw, run on a virtual thread of its own (the message's thread). */
    static <T> CompletableFuture<T> onVirtualThread(String name, Callable<T> work) {
        CompletableFuture<T> done = new CompletableFuture<>();
        Thread.ofVirtual().name(name).start(() -> {
            try {
                done.complete(work.call());
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        return done;
    }

    static void until(BooleanSupplier condition, Supplier<String> what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) fail("not within 5 s: " + what.get());
            Thread.onSpinWait();
        }
    }

    static Throwable failureOf(CompletableFuture<?> f) throws Exception {
        try {
            Object value = f.get(5, TimeUnit.SECONDS);
            throw new AssertionError("expected a failure, got " + value);
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    /**
     * The caller's virtual thread leaves {@code call} (interrupted while the send is in flight) but the send has not settled: the
     * permit stays taken until it does. Only then the next waiter is granted, and its send runs on its own thread — the grant
     * completes the waiter's promise, it never runs the send on the thread that settled the first one.
     */
    @Test
    void permitHeldUntilSendSettlesNotUntilCallerLeaves() throws Exception {
        TargetLimit limit = new TargetLimit(T, 1, WAIT, timer);
        Promise<String> first = heldSend();
        AtomicReference<Thread> firstThread = new AtomicReference<>();
        CompletableFuture<String> leaving = onVirtualThread("first", () -> limit.call(() -> {
            firstThread.set(Thread.currentThread());
            return first.future();
        }));
        until(() -> firstThread.get() != null && limit.gauge().inFlight() == 1, () -> "the first send started: " + limit.gauge());

        firstThread.get().interrupt();   // the caller gives up on the answer; the send itself is still out at the partner
        Throwable left = failureOf(leaving);
        assertTrue(firstThread.get().join(Duration.ofSeconds(5)), "the first caller's thread has left");
        assertEquals(1, limit.gauge().inFlight(), "the send has not settled: its permit is still taken although its caller left (" + left + ")");

        AtomicReference<Thread> secondSentOn = new AtomicReference<>();
        AtomicReference<Thread> secondThread = new AtomicReference<>();
        CompletableFuture<String> second = onVirtualThread("second", () -> {
            secondThread.set(Thread.currentThread());
            return limit.call(() -> {
                secondSentOn.set(Thread.currentThread());
                return Future.succeededFuture("second's reply");
            });
        });
        until(() -> limit.gauge().waiting() == 1, () -> "the second caller waits for the taken permit: " + limit.gauge());
        assertEquals(null, secondSentOn.get(), "nothing of the second is sent while the first send is out");

        first.complete("first's late reply");   // the partner answers the first send now, on the test's thread

        assertEquals("second's reply", second.get(5, TimeUnit.SECONDS), "the second was granted once the first send settled");
        assertSame(secondThread.get(), secondSentOn.get(), "the second's send ran on the second caller's own thread, not on the thread that settled the first");
        assertEquals(0, limit.gauge().inFlight(), "both sends settled: nothing in flight");
        assertEquals(0, limit.gauge().waiting());
    }

    /**
     * A caller interrupted while it waits for a permit is withdrawn: its send never runs, it leaves with an
     * {@link InterruptedException} and its interrupt flag set, and the permit freed later is not handed to it — nothing in
     * flight, nothing waiting, and its maxWait passing refuses nothing.
     */
    @Test
    void interruptedWaiterIsWithdrawnAndNothingSent() throws Exception {
        TargetLimit limit = new TargetLimit(T, 1, WAIT, timer);
        Promise<String> holder = heldSend();
        CompletableFuture<String> holding = onVirtualThread("holder", () -> limit.call(holder::future));
        until(() -> limit.gauge().inFlight() == 1, () -> "the holder's send is in flight: " + limit.gauge());

        AtomicInteger sent = new AtomicInteger();
        AtomicReference<Thread> waiter = new AtomicReference<>();
        AtomicBoolean flagOnLeaving = new AtomicBoolean();
        CompletableFuture<String> waiting = onVirtualThread("waiter", () -> {
            waiter.set(Thread.currentThread());
            try {
                return limit.call(() -> {
                    sent.incrementAndGet();
                    return Future.succeededFuture("sent");
                });
            } finally {
                flagOnLeaving.set(Thread.currentThread().isInterrupted());
            }
        });
        until(() -> waiter.get() != null && limit.gauge().waiting() == 1, () -> "the waiter is queued: " + limit.gauge());

        waiter.get().interrupt();

        assertInstanceOf(InterruptedException.class, failureOf(waiting), "the interrupted waiter leaves with an InterruptedException");
        assertTrue(flagOnLeaving.get(), "the interrupt flag is restored on the way out");
        assertEquals(0, limit.gauge().waiting(), "the waiter is withdrawn from the queue");

        holder.complete("holder's reply");
        assertEquals("holder's reply", holding.get(5, TimeUnit.SECONDS));
        assertEquals(0, limit.gauge().inFlight(), "the freed permit went to nobody: the withdrawn waiter is not granted");
        clock.advance(WAIT.plusSeconds(1));
        assertEquals(0, limit.gauge().refused(), "a withdrawn waiter's maxWait refuses nothing");
        assertEquals(0, sent.get(), "the withdrawn waiter's send never ran");
        assertEquals("next", onVirtualThread("next", () -> limit.call(() -> Future.succeededFuture("next"))).get(5, TimeUnit.SECONDS),
                "the permit is free for the next caller");
    }

    /**
     * The waiter is interrupted, and before its thread can run again the permit is freed and granted to it (every carrier of
     * the virtual-thread scheduler is held, so the grant and the interrupt both land while the waiter cannot move). The waiter
     * leaves interrupted without sending, and the permit it was granted is given back: the next caller gets it at once.
     */
    @Test
    void interruptRacingAGrantReleasesThePermit() throws Exception {
        TargetLimit limit = new TargetLimit(T, 1, WAIT, timer);
        Promise<String> holder = heldSend();
        CompletableFuture<String> holding = onVirtualThread("holder", () -> limit.call(holder::future));
        until(() -> limit.gauge().inFlight() == 1, () -> "the holder's send is in flight: " + limit.gauge());

        AtomicInteger sent = new AtomicInteger();
        AtomicReference<Thread> waiter = new AtomicReference<>();
        CompletableFuture<String> waiting = onVirtualThread("waiter", () -> {
            waiter.set(Thread.currentThread());
            return limit.call(() -> {
                sent.incrementAndGet();
                return Future.succeededFuture("sent");
            });
        });
        until(() -> waiter.get() != null && limit.gauge().waiting() == 1 && parked(waiter.get()),
                () -> "the waiter is queued and parked: " + limit.gauge() + ", " + waiter.get().getState());

        try (Carriers held = Carriers.hold()) {
            waiter.get().interrupt();          // the waiter is runnable but no carrier is free to run it
            holder.complete("holder's reply"); // the freed permit is granted to the queued, already interrupted waiter
            assertFalse(waiting.isDone(), "fixture: the waiter has not run between the interrupt and the grant");
        }

        Throwable left = failureOf(waiting);
        assertEquals("holder's reply", holding.get(5, TimeUnit.SECONDS));
        assertInstanceOf(InterruptedException.class, left, "the interrupt was seen: the waiter leaves with an InterruptedException");
        assertEquals(0, sent.get(), "an interrupted waiter sends nothing, granted or not");
        until(() -> limit.gauge().inFlight() == 0, () -> "the raced grant is given back: " + limit.gauge());
        assertEquals(0, limit.gauge().waiting());
        CompletableFuture<String> next = onVirtualThread("next", () -> limit.call(() -> Future.succeededFuture("next")));
        assertEquals("next", next.get(5, TimeUnit.SECONDS), "the next caller is granted at once: the raced permit is not leaked");
    }

    /** A send that throws before it returns a future, or returns a failed one, gives its permit back and fails the call with its own failure. */
    @Test
    void sendThatThrowsReleases() throws Exception {
        TargetLimit limit = new TargetLimit(T, 1, WAIT, timer);
        IllegalStateException broke = new IllegalStateException("the client broke before sending");
        Throwable thrown = failureOf(onVirtualThread("throws", () -> limit.call(() -> { throw broke; })));
        assertSame(broke, thrown, "the send's own exception comes out of call");
        assertEquals(0, limit.gauge().inFlight(), "a send that threw holds no permit");

        IOException refused = new IOException("connection refused");
        Throwable failed = failureOf(onVirtualThread("fails", () -> limit.call(() -> Future.<String>failedFuture(refused))));
        assertSame(refused, failed, "a failed send future's cause comes out of call as is, a checked one too");
        assertEquals(0, limit.gauge().inFlight(), "a failed send holds no permit");

        assertEquals("ok", onVirtualThread("after", () -> limit.call(() -> Future.succeededFuture("ok"))).get(5, TimeUnit.SECONDS),
                "the one permit is free: the next call is granted at once");
        assertEquals(0, limit.gauge().waiting());
        assertEquals(0, limit.gauge().refused());
    }

    /** maxCalls and maxWait must be positive; anything else is refused at construction, never a limit that admits nothing or waits not at all. */
    @Test
    void nonPositiveLimitOrWaitRefused() {
        assertThrows(IllegalArgumentException.class, () -> new TargetLimit(T, 0, WAIT, timer), "maxCalls 0");
        assertThrows(IllegalArgumentException.class, () -> new TargetLimit(T, -1, WAIT, timer), "maxCalls -1");
        assertThrows(IllegalArgumentException.class, () -> new TargetLimit(T, 1, Duration.ZERO, timer), "maxWait 0");
        assertThrows(IllegalArgumentException.class, () -> new TargetLimit(T, 1, Duration.ofMillis(-1), timer), "maxWait negative");
        TargetLimit smallest = new TargetLimit(T, 1, Duration.ofNanos(1), timer);
        assertEquals(1, smallest.gauge().maxCalls(), "the smallest valid limit is accepted as given");
        assertEquals(Duration.ofNanos(1), smallest.gauge().maxWait());
        assertEquals(T, smallest.gauge().target());
    }

    /** A virtual thread is WAITING only while it is parked (unmounted): it waits, it is not between two instructions. */
    static boolean parked(Thread t) {
        return t.getState() == Thread.State.WAITING;
    }

    /**
     * Occupies every carrier of the virtual-thread scheduler with a spinning virtual thread until {@link #close()}: a virtual
     * thread made runnable meanwhile cannot run (virtual threads are not time-sliced). Twice as many spinners as carriers are
     * started, so a spare (compensating) carrier the pool still keeps is taken as well. Held from a platform thread only.
     */
    static final class Carriers implements AutoCloseable {
        private volatile boolean released;
        private final List<Thread> spinners = new ArrayList<>();

        static Carriers hold() throws InterruptedException {
            if (Thread.currentThread().isVirtual()) throw new IllegalStateException("hold the carriers from a platform thread");
            int carriers = Integer.getInteger("jdk.virtualThreadScheduler.parallelism", java.lang.Runtime.getRuntime().availableProcessors());
            Carriers held = new Carriers();
            CountDownLatch spinning = new CountDownLatch(carriers);
            for (int i = 0; i < 2 * carriers; i++) {
                held.spinners.add(Thread.ofVirtual().name("carrier-holder-" + i).start(() -> {
                    spinning.countDown();
                    while (!held.released) Thread.onSpinWait();
                }));
            }
            if (!spinning.await(10, TimeUnit.SECONDS)) {
                held.close();
                throw new IllegalStateException((carriers - spinning.getCount()) + " of " + carriers + " carriers held within 10 s");
            }
            return held;
        }

        @Override
        public void close() throws InterruptedException {
            released = true;
            for (Thread t : spinners) {
                if (!t.join(Duration.ofSeconds(10))) throw new IllegalStateException("carrier holder " + t + " still spinning after 10 s");
            }
        }
    }

    /** The refusal a waiter past maxWait gets, straight from call: a retryable downstream Nack naming the target and the load. */
    @Test
    void aWaiterPastMaxWaitIsRefusedWithARetryableNackAndSendsNothing() throws Exception {
        TargetLimit limit = new TargetLimit(T, 1, WAIT, timer);
        Promise<String> holder = heldSend();
        onVirtualThread("holder", () -> limit.call(holder::future));
        until(() -> limit.gauge().inFlight() == 1, () -> "the holder's send is in flight: " + limit.gauge());
        AtomicInteger sent = new AtomicInteger();
        CompletableFuture<String> waiting = onVirtualThread("waiter", () -> limit.call(() -> {
            sent.incrementAndGet();
            return Future.succeededFuture("sent");
        }));
        until(() -> limit.gauge().waiting() == 1 && timer.pending() == 1, () -> "the waiter is queued, its maxWait armed: " + limit.gauge() + ", pending " + timer.pending());

        clock.advance(WAIT.minusMillis(1));
        // a due refusal runs on this thread, inside advance: checked synchronously, not through the waiter's thread
        assertEquals(0, limit.gauge().refused(), "not refused before maxWait");
        assertEquals(1, limit.gauge().waiting(), "still waiting at maxWait - 1 ms");
        assertEquals(1, timer.pending(), "its maxWait still armed");
        clock.advance(Duration.ofMillis(1));
        assertEquals(1, limit.gauge().refused(), "refused at exactly maxWait");
        assertEquals(0, timer.pending());

        NackException busy = assertInstanceOf(NackException.class, failureOf(waiting), "past maxWait the call fails with a NackException");
        assertTrue(busy.retryable(), "busy is a reason to come back later");
        assertEquals(io.flottila.api.Fault.Kind.DOWNSTREAM, busy.kind());
        assertTrue(busy.reason().startsWith("target " + T + " busy: 1 calls in flight, waited "), busy.reason());
        assertTrue(busy.reason().endsWith("waited PT5S"), busy.reason());
        assertEquals(0, sent.get(), "a refused waiter sends nothing");
        assertEquals(1, limit.gauge().refused());
        assertEquals(0, limit.gauge().waiting());
        assertEquals(1, limit.gauge().inFlight(), "the holder's send is still out");
    }

    // ---- round 2: the maxWait timer after a grant or a withdrawal, a timer that refuses, a permit closed twice -------------

    /** The FakeTimer, but {@code cancel()} does nothing: a waiter's maxWait task still fires after its grant or withdrawal. */
    static Timer cancelIgnored(FakeTimer inner) {
        return (delay, task) -> {
            inner.schedule(delay, task);
            return () -> false;
        };
    }

    /**
     * A waiter granted before its maxWait, whose timer task fires anyway (the cancel did nothing): the late firing refuses
     * nothing — no refusal counted, no exception on the timer's thread, the grant stands.
     */
    @Test
    void aMaxWaitFiringAfterTheGrantRefusesNothing() throws Exception {
        TargetLimit limit = new TargetLimit(T, 1, WAIT, cancelIgnored(timer));
        var first = limit.acquire();
        var second = limit.acquire();
        assertEquals(1, limit.gauge().waiting(), "fixture: the second waits");
        first.result().close();
        assertTrue(second.succeeded(), "the freed permit is granted to the waiter");
        assertEquals(1, timer.pending(), "fixture: the waiter's maxWait task is still armed");

        assertDoesNotThrow(() -> clock.advance(WAIT), "the late maxWait of a granted waiter throws nothing on the timer's thread");
        assertEquals(0, timer.pending(), "fixture: the task fired");
        assertEquals(0, limit.gauge().refused(), "a granted waiter is not refused");
        assertTrue(second.succeeded(), "the grant stands");
        assertEquals(1, limit.gauge().inFlight());
        second.result().close();
        assertEquals(0, limit.gauge().inFlight());
    }

    /** A waiter withdrawn by an interrupt, whose maxWait task fires anyway: no refusal is counted for it. */
    @Test
    void aMaxWaitFiringAfterTheWithdrawalRefusesNothing() throws Exception {
        TargetLimit limit = new TargetLimit(T, 1, WAIT, cancelIgnored(timer));
        var holder = limit.acquire();
        AtomicReference<Thread> waiter = new AtomicReference<>();
        CompletableFuture<String> waiting = onVirtualThread("waiter", () -> {
            waiter.set(Thread.currentThread());
            return limit.call(() -> Future.succeededFuture("sent"));
        });
        until(() -> waiter.get() != null && limit.gauge().waiting() == 1 && timer.pending() == 1, () -> "the waiter is queued: " + limit.gauge());
        waiter.get().interrupt();
        assertInstanceOf(InterruptedException.class, failureOf(waiting));
        assertEquals(1, timer.pending(), "fixture: the withdrawn waiter's maxWait task is still armed");

        assertDoesNotThrow(() -> clock.advance(WAIT));
        assertEquals(0, timer.pending(), "fixture: the task fired");
        assertEquals(0, limit.gauge().refused(), "a withdrawn waiter is not refused");
        assertEquals(0, limit.gauge().waiting());
        holder.result().close();
        assertEquals(0, limit.gauge().inFlight(), "the freed permit went to nobody");
    }

    /**
     * A timer that refuses to schedule the maxWait: the acquire fails with the timer's exception and queues nothing — nothing
     * waits, the permit freed later is not handed to a waiter that cannot be bounded, and the next acquire is granted.
     */
    @Test
    void aTimerThatRefusesToScheduleQueuesNothing() {
        IllegalStateException refusal = new IllegalStateException("timer stopped");
        TargetLimit limit = new TargetLimit(T, 1, WAIT, (delay, task) -> { throw refusal; });
        var holder = limit.acquire();
        assertTrue(holder.succeeded(), "a free permit needs no timer");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, limit::acquire, "no bound, no wait");
        assertSame(refusal, thrown);
        assertEquals(0, limit.gauge().waiting(), "nothing queued without its maxWait");

        holder.result().close();
        assertEquals(0, limit.gauge().inFlight(), "the freed permit went to nobody");
        var next = limit.acquire();
        assertTrue(next.succeeded(), "the next acquire is granted at once");
        assertEquals(1, limit.gauge().inFlight());
    }

    /** A grant cancels the waiter's maxWait task: nothing is left on the timer. */
    @Test
    void aGrantCancelsTheWaitersMaxWait() {
        TargetLimit limit = new TargetLimit(T, 1, WAIT, timer);
        var first = limit.acquire();
        var second = limit.acquire();
        assertEquals(1, timer.pending(), "fixture: the waiter's maxWait is armed");
        first.result().close();
        assertTrue(second.succeeded());
        assertEquals(0, timer.pending(), "the granted waiter's maxWait task is cancelled");
    }

    /** A withdrawal cancels the waiter's maxWait task: nothing is left on the timer. */
    @Test
    void aWithdrawalCancelsTheWaitersMaxWait() throws Exception {
        TargetLimit limit = new TargetLimit(T, 1, WAIT, timer);
        var holder = limit.acquire();
        AtomicReference<Thread> waiter = new AtomicReference<>();
        CompletableFuture<String> waiting = onVirtualThread("waiter", () -> {
            waiter.set(Thread.currentThread());
            return limit.call(() -> Future.succeededFuture("sent"));
        });
        until(() -> waiter.get() != null && limit.gauge().waiting() == 1 && timer.pending() == 1, () -> "the waiter is queued: " + limit.gauge());
        waiter.get().interrupt();
        assertInstanceOf(InterruptedException.class, failureOf(waiting));
        assertEquals(0, timer.pending(), "the withdrawn waiter's maxWait task is cancelled");
        holder.result().close();
    }

    /** A permit closed twice is released once: the second close neither frees another permit nor takes the next holder's. */
    @Test
    void aPermitClosedTwiceIsReleasedOnce() {
        TargetLimit limit = new TargetLimit(T, 2, WAIT, timer);
        var a = limit.acquire().result();
        var b = limit.acquire().result();
        a.close();
        a.close();
        assertEquals(1, limit.gauge().inFlight(), "two permits out, one closed (twice): one still in flight");
        var c = limit.acquire();
        var d = limit.acquire();
        assertTrue(c.succeeded(), "one permit was free");
        assertFalse(d.isComplete(), "only one: the limit of 2 is full again");
        b.close();
        assertTrue(d.succeeded());
    }

    // ---- FIX-115 (D-100): call(within, send) waits at most min(maxWait, within) -------------------------------------------

    /** The FakeTimer, counting every schedule: the witness that a wait armed its bound, or did not. */
    record CountingTimer(FakeTimer inner, AtomicInteger schedules) implements Timer {
        CountingTimer(FakeTimer inner) { this(inner, new AtomicInteger()); }
        @Override public Cancellable schedule(Duration delay, Runnable task) {
            schedules.incrementAndGet();
            return inner.schedule(delay, task);
        }
    }

    /** A send that counts its runs and answers at once. */
    static Supplier<Future<String>> counted(AtomicInteger sent, String reply) {
        return () -> {
            sent.incrementAndGet();
            return Future.succeededFuture(reply);
        };
    }

    /**
     * A waiter whose message has 1.5 s left (maxWait 5 s) is refused at 1.5 s, not at maxWait: a retryable downstream Nack
     * that says how long it waited and that the message's deadline bounded the wait; it sends nothing.
     */
    @Test
    void aDeadlineEarlierThanMaxWaitRefusesTheWaiterAtTheDeadline() throws Exception {
        TargetLimit limit = new TargetLimit(T, 1, WAIT, timer);
        Promise<String> holder = heldSend();
        onVirtualThread("holder", () -> limit.call(holder::future));
        until(() -> limit.gauge().inFlight() == 1, () -> "the holder's send is in flight: " + limit.gauge());
        AtomicInteger sent = new AtomicInteger();
        CompletableFuture<String> waiting = onVirtualThread("waiter", () -> limit.call(Duration.ofMillis(1500), counted(sent, "sent")));
        until(() -> limit.gauge().waiting() == 1 && timer.pending() == 1, () -> "the waiter is queued, its bound armed: " + limit.gauge() + ", pending " + timer.pending());

        clock.advance(Duration.ofMillis(1499));
        assertEquals(0, limit.gauge().refused(), "not refused before the deadline");
        assertEquals(1, limit.gauge().waiting(), "still waiting at the deadline - 1 ms");
        clock.advance(Duration.ofMillis(1));
        assertEquals(1, limit.gauge().refused(), "refused at exactly the deadline, 3.5 s before maxWait");
        assertEquals(0, timer.pending());

        NackException busy = assertInstanceOf(NackException.class, failureOf(waiting), "past the deadline the call fails with a NackException");
        assertTrue(busy.retryable(), "busy is a reason to come back later");
        assertEquals(io.flottila.api.Fault.Kind.DOWNSTREAM, busy.kind());
        assertTrue(busy.reason().startsWith("target " + T + " busy: 1 calls in flight, waited "), busy.reason());
        assertTrue(busy.reason().endsWith("waited PT1.5S until the message's deadline"), busy.reason());
        assertEquals(0, sent.get(), "a refused waiter sends nothing");
        assertEquals(0, limit.gauge().waiting());
        assertEquals(1, limit.gauge().inFlight(), "the holder's send is still out");
    }

    /** A waiter whose message has 60 s left waits maxWait (5 s), not 60 s, and is refused with the maxWait wording. */
    @Test
    void maxWaitEarlierThanTheDeadlineRefusesTheWaiterAtMaxWait() throws Exception {
        TargetLimit limit = new TargetLimit(T, 1, WAIT, timer);
        Promise<String> holder = heldSend();
        onVirtualThread("holder", () -> limit.call(holder::future));
        until(() -> limit.gauge().inFlight() == 1, () -> "the holder's send is in flight: " + limit.gauge());
        AtomicInteger sent = new AtomicInteger();
        CompletableFuture<String> waiting = onVirtualThread("waiter", () -> limit.call(Duration.ofSeconds(60), counted(sent, "sent")));
        until(() -> limit.gauge().waiting() == 1 && timer.pending() == 1, () -> "the waiter is queued, its bound armed: " + limit.gauge() + ", pending " + timer.pending());

        clock.advance(WAIT.minusMillis(1));
        assertEquals(0, limit.gauge().refused(), "not refused before maxWait");
        assertEquals(1, limit.gauge().waiting(), "still waiting at maxWait - 1 ms");
        clock.advance(Duration.ofMillis(1));
        assertEquals(1, limit.gauge().refused(), "refused at exactly maxWait, 55 s before the message's deadline");

        NackException busy = assertInstanceOf(NackException.class, failureOf(waiting));
        assertTrue(busy.retryable(), busy.reason());
        assertTrue(busy.reason().startsWith("target " + T + " busy: 1 calls in flight, waited "), busy.reason());
        assertTrue(busy.reason().endsWith("waited PT5S"), "maxWait bounded the wait: " + busy.reason());
        assertEquals(0, sent.get());
    }

    /**
     * A message whose deadline has passed (nothing left: zero, or less) and that would have to wait is refused at once, on the
     * caller's thread, without the clock moving: a retryable Nack saying the deadline has passed, counted in refused, no bound
     * armed on the timer, nothing queued or sent. The counting timer does see a wait that has time left (its witness).
     */
    @Test
    void aPassedDeadlineRefusesAWaitAtOnceWithoutArmingATimer() throws Exception {
        CountingTimer counting = new CountingTimer(timer);
        TargetLimit limit = new TargetLimit(T, 1, WAIT, counting);
        Promise<String> holder = heldSend();
        onVirtualThread("holder", () -> limit.call(holder::future));
        until(() -> limit.gauge().inFlight() == 1, () -> "the holder's send is in flight: " + limit.gauge());
        AtomicInteger sent = new AtomicInteger();

        int refusals = 0;
        for (Duration within : List.of(Duration.ZERO, Duration.ofMillis(-1))) {
            Throwable failure = failureOf(onVirtualThread("late", () -> limit.call(within, counted(sent, "sent"))));
            refusals++;
            NackException late = assertInstanceOf(NackException.class, failure, within + ": refused with a NackException");
            assertTrue(late.retryable(), within + ": " + late.reason());
            assertEquals(io.flottila.api.Fault.Kind.DOWNSTREAM, late.kind());
            assertTrue(late.reason().startsWith("target " + T + " busy: 1 calls in flight"), late.reason());
            assertTrue(late.reason().endsWith("the message's deadline has passed"), late.reason());
            assertEquals(refusals, limit.gauge().refused(), within + ": counted in refused");
            assertEquals(0, limit.gauge().waiting(), within + ": nothing queued");
            assertEquals(0, counting.schedules().get(), within + ": no bound armed");
            assertEquals(0, timer.pending(), within + ": nothing pending on the timer");
        }
        assertEquals(0, sent.get(), "nothing sent");

        CompletableFuture<String> witness = onVirtualThread("witness", () -> limit.call(Duration.ofSeconds(1), counted(sent, "sent")));
        until(() -> limit.gauge().waiting() == 1 && counting.schedules().get() == 1, () -> "witness: a wait with time left arms its bound: " + counting.schedules());
        clock.advance(Duration.ofSeconds(1));
        assertInstanceOf(NackException.class, failureOf(witness));
        assertEquals(0, sent.get());
    }

    /** A free permit is still granted to a message whose deadline has passed (zero or less left): nothing to wait for, nothing refused. */
    @Test
    void aPassedDeadlineStillTakesAFreePermit() throws Exception {
        CountingTimer counting = new CountingTimer(timer);
        TargetLimit limit = new TargetLimit(T, 1, WAIT, counting);
        for (Duration within : List.of(Duration.ZERO, Duration.ofMillis(-1))) {
            AtomicInteger inFlightDuringSend = new AtomicInteger(-1);
            String reply = onVirtualThread("late", () -> limit.call(within, () -> {
                inFlightDuringSend.set(limit.gauge().inFlight());
                return Future.succeededFuture("sent " + within);
            })).get(5, TimeUnit.SECONDS);
            assertEquals("sent " + within, reply, within + ": the free permit was taken and the send ran");
            assertEquals(1, inFlightDuringSend.get(), within + ": under the permit");
            assertEquals(0, limit.gauge().inFlight(), within + ": given back once the send settled");
        }
        assertEquals(0, limit.gauge().refused(), "nothing refused");
        assertEquals(0, counting.schedules().get(), "nothing armed");
    }
}
