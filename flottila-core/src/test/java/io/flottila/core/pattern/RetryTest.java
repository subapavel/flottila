package io.flottila.core.pattern;

import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Step;
import io.flottila.core.flow.RetryPolicy;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.FakeTimer;
import io.flottila.testkit.NoBlockedPlatformThreads;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Retry per step (ARCHITECTURE §4): exponential backoff on retryable steps, driven by the injected timer, never a sleep. */
@ExtendWith(NoBlockedPlatformThreads.class)
@Timeout(60)
class RetryTest {

    final FakeRuntime rt = FakeRuntime.create();
    final FakeClock clock = rt.clock();
    final FakeTimer timer = rt.timer();
    final Envelope in = rt.ingress("f", Headers.empty(), Buffer.buffer("<a/>"));
    final RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(100), Duration.ofMillis(250), 2.0);

    /** Runs the step on a virtual thread (it parks between attempts) and drives the fake clock until it ends. */
    Envelope runWithClockAdvancing(Step step, List<Duration> waitsSeen) throws Exception {
        AtomicReference<Object> outcome = new AtomicReference<>();
        Thread t = Thread.ofVirtual().start(() -> { try { outcome.set(step.apply(in)); } catch (Throwable e) { outcome.set(e); } });
        while (t.isAlive()) {
            if (timer.pending() > 0) {
                Duration next = timer.nextDelay();
                waitsSeen.add(next);
                clock.advance(next);
            }
            Thread.onSpinWait();
            Thread.yield();
        }
        if (outcome.get() instanceof Exception e) throw e;
        if (outcome.get() instanceof Error e) throw e;
        return (Envelope) outcome.get();
    }

    @Test
    void failuresAreRetriedWithGrowingDelaysUntilOneAttemptSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Step flaky = e -> { if (calls.incrementAndGet() < 3) throw new IllegalStateException("try " + calls.get()); return e.withHeader("done", "yes"); };
        List<Duration> waits = new ArrayList<>();
        Envelope out = runWithClockAdvancing(Retry.step(flaky, policy, timer), waits);
        assertEquals("yes", out.header("done").orElseThrow());
        assertEquals(3, calls.get());
        assertEquals(List.of(Duration.ofMillis(100), Duration.ofMillis(200)), waits, "100 ms, then 200 ms, driven by the fake clock");
        assertEquals(0, timer.pending());
    }

    @Test
    void afterTheLastAttemptTheLastExceptionIsThrownWithTheEarlierOnesSuppressed() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Step broken = e -> { throw new IllegalStateException("try " + calls.incrementAndGet()); };
        List<Duration> waits = new ArrayList<>();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> runWithClockAdvancing(Retry.step(broken, policy, timer), waits));
        assertEquals("try 4", e.getMessage(), "attempts = 3 retries after the first try: 4 calls");
        assertEquals(3, e.getSuppressed().length);
        assertEquals("try 1", e.getSuppressed()[0].getMessage());
        assertEquals(List.of(Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofMillis(250)), waits, "capped at max");
        assertEquals(4, calls.get());
    }

    @Test
    void aStepThatSucceedsFirstTimeNeverTouchesTheTimerAndNoRetriesMeansNoWrapping() {
        AtomicInteger calls = new AtomicInteger();
        Step fine = e -> { calls.incrementAndGet(); return e; };
        Envelope out = Retry.step(fine, policy, timer).apply(in);
        assertSame(in, out);
        assertEquals(1, calls.get());
        assertEquals(0, timer.pending());
        Step unwrapped = Retry.step(fine, RetryPolicy.NONE, timer);
        assertSame(fine, unwrapped, "a policy of zero attempts adds nothing");
    }

    @Test
    void anInterruptWhileWaitingStopsRetryingAndKeepsTheInterrupt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Step broken = e -> { calls.incrementAndGet(); throw new IllegalStateException("always"); };
        Step retried = Retry.step(broken, policy, timer);
        AtomicReference<Throwable> seen = new AtomicReference<>();
        AtomicReference<Boolean> flag = new AtomicReference<>();
        Thread t = Thread.ofVirtual().start(() -> { try { retried.apply(in); } catch (Throwable x) { seen.set(x); flag.set(Thread.currentThread().isInterrupted()); } });
        while (timer.pending() == 0) { Thread.onSpinWait(); Thread.yield(); }
        t.interrupt();
        t.join();
        assertTrue(seen.get() instanceof IllegalStateException, String.valueOf(seen.get()));
        assertEquals("always", seen.get().getMessage());
        assertTrue(flag.get(), "the interrupt is re-set for the caller (a cancelled scope)");
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), clock.instant(), "no time passed: the wait was cut short");
        assertEquals(1, calls.get(), "no further attempt after the interrupt");
        assertEquals(0, seen.get().getSuppressed().length);
        assertEquals(0, timer.pending(), "the cut-short wait is cancelled");
    }

    /** {@code failure} thrown as it is, a checked one included, from a lambda that declares none (as {@code Future.await()} does). */
    @SuppressWarnings("unchecked")
    static <T extends Throwable> RuntimeException sneaky(Throwable failure) throws T {
        throw (T) failure;
    }

    /**
     * FIX-107: a step's {@link InterruptedException} is a cancelled run, not the step's failure: it is not retried — no backoff
     * scheduled, the step not called again — it propagates as is, and the interrupt flag, cleared by whatever threw it, is set
     * again on the thread that ran the step. The step throws with the flag clear, as an interrupted JDK wait does; the run's
     * thread reads (and clears) its flag after the call, so it never leaks; the fake clock is driven as for a real retry.
     */
    @Test
    void anInterruptedExceptionFromTheStepIsNeitherRetriedNorSwallowedAndTheFlagIsRestored() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<Boolean> flagWhenThrown = new java.util.concurrent.CopyOnWriteArrayList<>();
        InterruptedException cancelled = new InterruptedException("cancelled");
        Step interrupted = e -> {
            calls.incrementAndGet();
            flagWhenThrown.add(Thread.currentThread().isInterrupted());
            throw sneaky(cancelled);
        };
        Step retried = Retry.step(interrupted, policy, timer);
        AtomicReference<Object> outcome = new AtomicReference<>();
        AtomicReference<Boolean> flagAfter = new AtomicReference<>();
        Thread t = Thread.ofVirtual().start(() -> {
            try {
                outcome.set(retried.apply(in));
            } catch (Throwable x) {
                outcome.set(x);
            } finally {
                flagAfter.set(Thread.interrupted());   // read and cleared on the thread that ran the step
            }
        });
        List<Duration> waits = new ArrayList<>();
        while (t.isAlive()) {
            if (timer.pending() > 0) {   // a retry's backoff: driven, so a wrong retry runs to its end instead of hanging
                Duration next = timer.nextDelay();
                waits.add(next);
                clock.advance(next);
            }
            Thread.onSpinWait();
            Thread.yield();
        }
        assertEquals(List.of(), waits, "no backoff: an interrupt is never retried");
        assertEquals(1, calls.get(), "the step ran once");
        assertEquals(List.of(false), flagWhenThrown, "fixture: the step threw with the flag clear");
        assertSame(cancelled, outcome.get(), "the InterruptedException propagates as is");
        assertEquals(0, cancelled.getSuppressed().length);
        assertTrue(flagAfter.get(), "the interrupt flag is set again for the caller");
        assertEquals(0, timer.pending());
    }
}
