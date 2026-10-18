package io.flottila.core.scope;

import io.flottila.api.Timer;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.flottila.testkit.NoBlockedPlatformThreads;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ARCHITECTURE §4: a thin RequestScope (fork / join / cancel on virtual threads) wraps each message so a
 * timeout or cancel stops all of its children. The owner runs on the message's virtual thread; every
 * blocking call in these tests happens on a virtual thread (the detector is on).
 */
@ExtendWith(NoBlockedPlatformThreads.class)
@Timeout(60)   // a scope that never joins is a failure, not a hang; no assertion depends on elapsed time
class RequestScopeTest {

    final FakeClock clock = FakeClock.at(Instant.parse("2026-01-01T00:00:00Z"));
    final FakeTimer timer = new FakeTimer(clock);

    RequestScope open(Duration deadline) { return RequestScope.open(clock, timer, deadline); }

    @Test
    void forkRunsTheTaskOnAVirtualThreadAndJoinWaitsForEveryChild() throws Exception {
        onVirtual(() -> {
            try (RequestScope scope = open(Duration.ofSeconds(5))) {
                CountDownLatch release = new CountDownLatch(1);
                RequestScope.Subtask<Boolean> a = scope.fork(() -> Thread.currentThread().isVirtual());
                RequestScope.Subtask<String> b = scope.fork(() -> { release.await(); return "b"; });
                assertEquals(2, scope.forked());
                assertEquals(RequestScope.Subtask.State.RUNNING, b.state());
                assertThrows(IllegalStateException.class, b::get, "no result before it finished");
                release.countDown();
                scope.join();
                assertEquals(RequestScope.Subtask.State.SUCCESS, a.state());
                assertEquals(Boolean.TRUE, a.get(), "children run on virtual threads");
                assertEquals("b", b.get());
                assertEquals(0, scope.running());
                assertFalse(scope.isCancelled());
            }
            return null;
        });
    }

    @Test
    void aFailingChildIsFailedWithItsExceptionAndDoesNotCancelTheScope() throws Exception {
        onVirtual(() -> {
            try (RequestScope scope = open(Duration.ofSeconds(5))) {
                RequestScope.Subtask<Object> bad = scope.fork(() -> { throw new IllegalArgumentException("boom"); });
                RequestScope.Subtask<Integer> good = scope.fork(() -> 42);
                scope.join();
                assertEquals(RequestScope.Subtask.State.FAILED, bad.state());
                assertInstanceOf(IllegalArgumentException.class, bad.exception());
                assertEquals("boom", bad.exception().getMessage());
                IllegalStateException e = assertThrows(IllegalStateException.class, bad::get);
                assertInstanceOf(IllegalArgumentException.class, e.getCause());
                assertEquals(42, good.get());
                assertNull(good.exception());
                assertFalse(scope.isCancelled(), "one failed child is a result, not a cancellation; the caller decides");
            }
            return null;
        });
    }

    @Test
    void cancelInterruptsAChildParkedInAVertxAwaitAndRejectsFurtherForks() throws Exception {
        onVirtual(() -> {
            Promise<String> never = Promise.promise();
            AtomicReference<Thread> childThread = new AtomicReference<>();
            try (RequestScope scope = open(Duration.ofSeconds(5))) {
                RequestScope.Subtask<String> child = scope.fork(() -> { childThread.set(Thread.currentThread()); return never.future().await(); });
                RequestScope.Subtask<String> finished = scope.fork(() -> "already done");
                waitUntil(() -> childThread.get() != null && childThread.get().getState() == Thread.State.WAITING);
                waitUntil(() -> finished.state() == RequestScope.Subtask.State.SUCCESS);
                scope.cancel();
                scope.cancel();
                scope.join();
                assertTrue(scope.isCancelled());
                assertEquals("already done", finished.get(), "a child that had finished before the cancel keeps its result");
                assertEquals(RequestScope.Subtask.State.CANCELLED, child.state(), "an exception observed after cancel is a cancellation, not a failure");
                assertInstanceOf(InterruptedException.class, child.exception());
                assertEquals(0, scope.running());
                assertThrows(IllegalStateException.class, () -> scope.fork(() -> "late"));
                assertEquals(2, scope.forked());
            }
            return null;
        });
    }

    @Test
    void theDeadlineCancelsTheScopeThroughTheInjectedTimerAndRemainingClampsAtZero() throws Exception {
        onVirtual(() -> {
            try (RequestScope scope = open(Duration.ofMillis(300))) {
                assertEquals(clock.instant().plusMillis(300), scope.deadline());
                assertEquals(Duration.ofMillis(300), scope.remaining());
                CountDownLatch started = new CountDownLatch(1);
                RequestScope.Subtask<String> child = scope.fork(() -> { started.countDown(); new CountDownLatch(1).await(); return "never"; });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                clock.advance(Duration.ofMillis(299));
                assertFalse(scope.isCancelled());
                assertEquals(Duration.ofMillis(1), scope.remaining());
                clock.advance(Duration.ofMillis(1));
                assertTrue(scope.isCancelled(), "the deadline fired through the fake timer");
                scope.join();
                assertEquals(RequestScope.Subtask.State.CANCELLED, child.state());
                clock.advance(Duration.ofMillis(500));
                assertEquals(Duration.ZERO, scope.remaining());
            }
            return null;
        });
    }

    @Test
    void closeCancelsWhatIsStillRunningWaitsForItAndDropsTheDeadlineTimer() throws Exception {
        onVirtual(() -> {
            AtomicBoolean childEnded = new AtomicBoolean();
            RequestScope.Subtask<String> child;
            RequestScope scope = open(Duration.ofSeconds(5));
            try (scope) {
                CountDownLatch started = new CountDownLatch(1);
                child = scope.fork(() -> {
                    started.countDown();
                    try { new CountDownLatch(1).await(); } finally { childEnded.set(true); }
                    return "never";
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertEquals(1, scope.running());
                assertEquals(1, timer.pending(), "the deadline is armed while the scope is open");
            }
            assertTrue(childEnded.get(), "close returned only after the child ended");
            assertEquals(RequestScope.Subtask.State.CANCELLED, child.state());
            assertTrue(scope.isCancelled());
            assertEquals(0, timer.pending(), "a closed scope leaves no timer behind");
            clock.advance(Duration.ofSeconds(10));
            assertThrows(IllegalStateException.class, () -> scope.fork(() -> "after close"));
            return null;
        });
    }

    @Test
    void closeAfterAJoinedScopeIsQuietAndDoesNotCancel() throws Exception {
        onVirtual(() -> {
            RequestScope scope = open(Duration.ofSeconds(5));
            RequestScope.Subtask<Integer> one = scope.fork(() -> 1);
            scope.join();
            scope.close();
            assertFalse(scope.isCancelled(), "nothing was running: close had nothing to cancel");
            assertEquals(1, one.get());
            assertEquals(0, timer.pending());
            assertThrows(IllegalStateException.class, () -> scope.fork(() -> 2), "closed, though never cancelled: no more forks");
            return null;
        });
    }

    @Test
    void cancellingTheRootStopsAGrandchildInANestedScope() throws Exception {
        onVirtual(() -> {
            AtomicReference<RequestScope.Subtask<String>> grandchild = new AtomicReference<>();
            CountDownLatch grandchildStarted = new CountDownLatch(1);
            try (RequestScope root = open(Duration.ofSeconds(5))) {
                RequestScope.Subtask<String> child = root.fork(() -> {
                    try (RequestScope nested = open(Duration.ofSeconds(5))) {
                        grandchild.set(nested.fork(() -> { grandchildStarted.countDown(); new CountDownLatch(1).await(); return "never"; }));
                        nested.join();
                        return "joined";
                    }
                });
                assertTrue(grandchildStarted.await(5, TimeUnit.SECONDS));
                root.cancel();
                root.join();
                assertEquals(RequestScope.Subtask.State.CANCELLED, child.state());
                assertEquals(RequestScope.Subtask.State.CANCELLED, grandchild.get().state(), "the interrupt unwinds the child through its nested scope's close");
            }
            return null;
        });
    }

    @Test
    void anInterruptedJoinCancelsTheScopeBeforeRethrowing() throws Exception {
        onVirtual(() -> {
            RequestScope scope = open(Duration.ofSeconds(5));
            CountDownLatch started = new CountDownLatch(1);
            RequestScope.Subtask<String> child = scope.fork(() -> { started.countDown(); new CountDownLatch(1).await(); return "never"; });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Thread owner = Thread.currentThread();
            Thread.ofVirtual().start(() -> { waitUntil(() -> owner.getState() == Thread.State.WAITING); owner.interrupt(); });
            assertThrows(InterruptedException.class, scope::join);
            assertTrue(scope.isCancelled());
            scope.close();
            assertEquals(RequestScope.Subtask.State.CANCELLED, child.state());
            return null;
        });
    }

    @Test
    void closeKeepsWaitingForAStubbornChildAndHandsTheOwnersInterruptBack() throws Exception {
        onVirtual(() -> {
            RequestScope scope = open(Duration.ofSeconds(5));
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch letGo = new CountDownLatch(1);
            RequestScope.Subtask<String> child = scope.fork(() -> {
                started.countDown();
                try { new CountDownLatch(1).await(); } catch (InterruptedException first) { letGo.await(); }   // swallows the cancel, ends only when let go
                return "late";
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Thread owner = Thread.currentThread();
            Thread.ofVirtual().start(() -> {
                waitUntil(() -> owner.getState() == Thread.State.WAITING);
                owner.interrupt();                      // the owner is interrupted while close() waits
                waitUntil(() -> owner.getState() == Thread.State.WAITING);
                letGo.countDown();                      // only now may the child end
            });
            scope.close();
            assertTrue(Thread.interrupted(), "close() waited the child out and handed the owner's interrupt back");
            assertEquals(RequestScope.Subtask.State.SUCCESS, child.state(), "a child that ignores the interrupt still ends on its own terms");
            assertEquals("late", child.get());
            assertEquals(0, scope.running());
            return null;
        });
    }

    @Test
    void deadlinesAreFiniteAndPositiveAndTheCollaboratorsAreRequired() {
        assertThrows(IllegalArgumentException.class, () -> RequestScope.open(clock, timer, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> RequestScope.open(clock, timer, Duration.ofSeconds(-1)));
        assertThrows(NullPointerException.class, () -> RequestScope.open(null, timer, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> RequestScope.open(clock, (Timer) null, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> RequestScope.open(clock, timer, null));
        try (RequestScope scope = RequestScope.open(Clock.fixed(Instant.EPOCH, java.time.ZoneOffset.UTC), timer, Duration.ofSeconds(1))) {
            assertThrows(NullPointerException.class, () -> scope.fork(null));
            assertNotNull(scope.deadline());
        }
    }

    @Test
    void tenThousandScopesForkJoinAndCloseWithoutLosingAResultOrLeakingATimer() throws Exception {
        int scopes = 10_000;
        CountDownLatch done = new CountDownLatch(scopes);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int i = 0; i < scopes; i++) {
            Thread.ofVirtual().start(() -> {
                try (RequestScope scope = open(Duration.ofSeconds(30))) {
                    RequestScope.Subtask<Integer> a = scope.fork(() -> 1);
                    RequestScope.Subtask<Integer> b = scope.fork(() -> 2);
                    scope.join();
                    if (a.get() + b.get() != 3) throw new AssertionError("lost a child");
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(60, TimeUnit.SECONDS));
        assertNull(failure.get(), String.valueOf(failure.get()));
        assertEquals(0, timer.pending(), "every scope dropped its deadline timer");
    }

    /** Runs the body on a virtual thread (the scope owner is always the message's virtual thread) and rethrows its failure. */
    static void onVirtual(Callable<Void> body) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread t = Thread.ofVirtual().start(() -> { try { body.call(); } catch (Throwable e) { failure.set(e); } });
        t.join();
        if (failure.get() instanceof Exception e) throw e;
        if (failure.get() instanceof Error e) throw e;
    }

    static void waitUntil(java.util.function.BooleanSupplier state) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!state.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("state not reached");
            Thread.onSpinWait();
            Thread.yield();
        }
    }
}
