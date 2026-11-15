package io.flottila.core.admission;

import io.flottila.core.scope.MessageContext;
import io.flottila.core.scope.RequestScope;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.flottila.testkit.NoBlockedPlatformThreads;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The message path end to end (ARCHITECTURE §4 threading): admit — requests and bytes — then run the body
 * inside its context and its scope, release whatever happens. Over the budget the caller is told at once
 * (the HTTP component turns that into 503 + Retry-After).
 */
@ExtendWith(NoBlockedPlatformThreads.class)
@Timeout(60)
class AdmittedTest {

    final FakeClock clock = FakeClock.at(Instant.parse("2026-01-01T00:00:00Z"));
    final FakeTimer timer = new FakeTimer(clock);
    final Admission admission = new Admission(2, 1000);

    MessageContext ctx(String id) { return new MessageContext(id, "f", "t", "tr-" + id, clock.instant().plusSeconds(5)); }

    @Test
    void aSuccessfulRunHoldsThePermitAndTheContextForItsDurationAndReturnsTheResult() throws Exception {
        String result = Admitted.run(admission, ctx("m-1"), 600, clock, timer, scope -> {
            assertEquals(1, admission.inFlightRequests());
            assertEquals(600, admission.inFlightBytes());
            assertEquals("m-1", MessageContext.current().messageId());
            assertEquals(Instant.parse("2026-01-01T00:00:05Z"), scope.deadline(), "the scope's deadline is the context's");
            RequestScope.Subtask<String> child = scope.fork(() -> MessageContext.current().traceId());
            scope.join();
            return child.get();
        });
        assertEquals("tr-m-1", result);
        assertEquals(0, admission.inFlightRequests());
        assertEquals(0, admission.inFlightBytes());
        assertFalse(MessageContext.isBound());
        assertEquals(0, timer.pending(), "the scope's deadline timer is gone");
    }

    @Test
    void aFailingBodyReleasesThePermitAndRethrowsTheSameException() {
        IllegalStateException boom = new IllegalStateException("step failed");
        IllegalStateException seen = assertThrows(IllegalStateException.class, () -> Admitted.run(admission, ctx("m-1"), 10, clock, timer, scope -> { throw boom; }));
        assertSame(boom, seen);
        assertEquals(0, admission.inFlightRequests());
        assertEquals(0, admission.inFlightBytes());
        assertEquals(0, timer.pending());
    }

    @Test
    void aBodyWhoseScopeIsCancelledByTheDeadlineStillReleasesEverything() throws Exception {
        CountDownLatch childStarted = new CountDownLatch(1);
        AtomicReference<RequestScope.Subtask<String>> child = new AtomicReference<>();
        Thread.ofVirtual().start(() -> { try { childStarted.await(); } catch (InterruptedException e) { return; } clock.advance(Duration.ofSeconds(6)); });
        Object outcome = Admitted.run(admission, ctx("m-1"), 10, clock, timer, scope -> {
            child.set(scope.fork(() -> { childStarted.countDown(); new CountDownLatch(1).await(); return "never"; }));
            scope.join();
            return scope.isCancelled() ? "cancelled" : "ran";
        });
        assertEquals("cancelled", outcome);
        assertEquals(RequestScope.Subtask.State.CANCELLED, child.get().state());
        assertEquals(0, admission.inFlightRequests());
    }

    @Test
    void overTheBudgetTheCallerIsRefusedAtOnceWithTheNumbersAndNothingRuns() throws Exception {
        Admission.Permit a = admission.tryAcquire(700).orElseThrow();
        AtomicInteger ran = new AtomicInteger();
        AdmissionRefusedException e = assertThrows(AdmissionRefusedException.class,
                () -> Admitted.run(admission, ctx("m-2"), 400, clock, timer, scope -> ran.incrementAndGet()));
        assertEquals(0, ran.get());
        assertEquals(400, e.requestedBytes());
        assertEquals(1, e.inFlightRequests());
        assertEquals(700, e.inFlightBytes());
        assertEquals(2, e.maxRequests());
        assertEquals(1000, e.maxBytes());
        assertTrue(e.getMessage().contains("400") && e.getMessage().contains("1000"), e.getMessage());
        assertEquals(1, admission.inFlightRequests(), "the refused run took nothing");
        assertFalse(MessageContext.isBound());
        a.close();
        Integer afterRelease = Admitted.run(admission, ctx("m-3"), 400, clock, timer, scope -> ran.incrementAndGet());
        assertEquals(1, afterRelease, "admitted once there is room");
        assertThrows(IllegalArgumentException.class, () -> Admitted.run(admission, ctx("m-4"), 1001, clock, timer, scope -> 0), "larger than the whole budget: a caller error, not backpressure");
    }

    @Test
    void aContextWhoseDeadlineHasPassedIsRefusedBeforeItTakesAPermit() {
        MessageContext late = new MessageContext("m-late", "f", "t", "tr", clock.instant().minusSeconds(1));
        assertThrows(IllegalArgumentException.class, () -> Admitted.run(admission, late, 10, clock, timer, scope -> 0));
        MessageContext now = new MessageContext("m-now", "f", "t", "tr", clock.instant());
        assertThrows(IllegalArgumentException.class, () -> Admitted.run(admission, now, 10, clock, timer, scope -> 0), "a deadline of right now leaves no time to run");
        assertEquals(0, admission.inFlightRequests());
        Admission.Permit a = admission.tryAcquire(1).orElseThrow();
        Admission.Permit b = admission.tryAcquire(1).orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> Admitted.run(admission, late, 10, clock, timer, scope -> 0), "with the budget full the deadline is still checked first: an IAE, not a refusal");
        a.close(); b.close();
    }

    @Test
    void tenThousandAdmittedRunsNeverExceedTheBudgetAndAllComplete() throws Exception {
        Admission a = new Admission(64, 64 * 1024);
        int n = 10_000;
        CountDownLatch done = new CountDownLatch(n);
        AtomicLong maxRequests = new AtomicLong();
        AtomicLong maxBytes = new AtomicLong();
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int i = 0; i < n; i++) {
            long bytes = 1 + (i % 2048);
            String id = "m-" + i;
            Thread.ofVirtual().start(() -> {
                try {
                    Admitted.run(a, ctx(id), bytes, clock, timer, scope -> {
                        maxRequests.accumulateAndGet(a.inFlightRequests(), Math::max);
                        maxBytes.accumulateAndGet(a.inFlightBytes(), Math::max);
                        if (!MessageContext.current().messageId().equals(id)) throw new AssertionError("wrong context");
                        Thread.yield();
                        return null;
                    });
                    completed.incrementAndGet();
                } catch (AdmissionRefusedException e) {
                    refused.incrementAndGet();   // the non-blocking path says no when full: that is the contract, not a failure
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(60, TimeUnit.SECONDS));
        assertNull(failure.get(), String.valueOf(failure.get()));
        assertEquals(n, completed.get() + refused.get());
        assertTrue(completed.get() > 0);
        assertTrue(maxRequests.get() <= 64, "observed " + maxRequests);
        assertTrue(maxBytes.get() <= 64 * 1024, "observed " + maxBytes);
        assertEquals(0, a.inFlightRequests());
        assertEquals(0, a.inFlightBytes());
        assertEquals(0, timer.pending());
    }
}
