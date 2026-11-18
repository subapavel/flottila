package dev.rail8.testkit;

import dev.rail8.api.Cache;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The contract every {@link Cache} must honour: the testkit's fake and every cache a node keeps run the same cases. A
 * subclass supplies a fresh, empty cache per test and moves that cache's clock. Every call runs on its own virtual thread,
 * as a step's would; the cases that need two callers at once park the first one in its loader and wait for the second to
 * block, on the thread's state, never for a while.
 *
 * @since 0.1
 */
public abstract class CacheContract {

    /** How long a case waits for something that must happen at once: a failure, never a pause. */
    private static final long PATIENCE_SECONDS = 10;

    /** A fresh, empty cache on a clock {@link #advance} moves. */
    protected abstract Cache cache();

    /** Moves the clock of the cache {@link #cache()} returned by {@code by}. */
    protected abstract void advance(Duration by);

    protected static Cache.Loader value(String value, Duration ttl) {
        return () -> new Cache.Entry(value, ttl);
    }

    @Test
    public void aMissLoadsAndAHitDoesNot() throws Exception {
        Cache c = cache();
        AtomicInteger loads = new AtomicInteger();
        Cache.Loader loader = () -> new Cache.Entry("token-" + loads.incrementAndGet(), Duration.ofMinutes(5));
        assertEquals("token-1", call(() -> c.get("partner-a", loader)));
        assertEquals("token-1", call(() -> c.get("partner-a", loader)));
        assertEquals(1, loads.get());
    }

    @Test
    public void keysAreSeparate() throws Exception {
        Cache c = cache();
        assertEquals("a", call(() -> c.get("partner-a", value("a", Duration.ofMinutes(5)))));
        assertEquals("b", call(() -> c.get("partner-b", value("b", Duration.ofMinutes(5)))));
        assertEquals("a", call(() -> c.get("partner-a", value("other", Duration.ofMinutes(5)))));
    }

    @Test
    public void aValueIsKeptUntilItsTtlHasPassed() throws Exception {
        Cache c = cache();
        call(() -> c.get("k", value("first", Duration.ofMinutes(5))));
        advance(Duration.ofMinutes(5).minusMillis(1));
        assertEquals("first", call(() -> c.get("k", value("second", Duration.ofMinutes(5)))));
        advance(Duration.ofMillis(1));
        assertEquals("second", call(() -> c.get("k", value("second", Duration.ofMinutes(5)))), "kept for its ttl and no longer");
    }

    @Test
    public void aTtlOfZeroOrLessKeepsNothing() throws Exception {
        Cache c = cache();
        assertEquals("once", call(() -> c.get("k", value("once", Duration.ZERO))));
        assertEquals("again", call(() -> c.get("k", value("again", Duration.ofSeconds(-1)))));
        assertEquals("kept", call(() -> c.get("k", value("kept", Duration.ofSeconds(1)))));
    }

    @Test
    public void aTtlAboveTheLimitIsCutToIt() throws Exception {
        Cache c = cache();
        call(() -> c.get("k", value("first", Duration.ofDays(30))));
        advance(Cache.MAX_TTL.minusMillis(1));
        assertEquals("first", call(() -> c.get("k", value("second", Duration.ofDays(30)))));
        advance(Duration.ofMillis(1));
        assertEquals("second", call(() -> c.get("k", value("second", Duration.ofDays(30)))));
    }

    @Test
    public void removeForgetsTheKey() throws Exception {
        Cache c = cache();
        call(() -> c.get("k", value("first", Duration.ofMinutes(5))));
        call(() -> { c.remove("k"); return null; });
        assertEquals("second", call(() -> c.get("k", value("second", Duration.ofMinutes(5)))));
        call(() -> { c.remove("never-kept"); return null; });
    }

    @Test
    public void aKeyThatBreaksTheRulesIsRefusedNamingIt() throws Exception {
        Cache c = cache();
        String longest = "k".repeat((int) Cache.MAX_KEY_CHARS);
        assertEquals("v", call(() -> c.get(longest, value("v", Duration.ofMinutes(1)))));
        for (String bad : new String[]{"", "a\nb", "tab\there", "k".repeat((int) Cache.MAX_KEY_CHARS + 1)}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> call(() -> c.get(bad, value("v", Duration.ofMinutes(1)))), bad);
            assertTrue(e.getMessage().contains("'" + bad + "'"), e.getMessage());
            assertThrows(IllegalArgumentException.class, () -> call(() -> { c.remove(bad); return null; }), bad);
        }
    }

    @Test
    public void offAVirtualThreadBothMethodsAreRefused() {
        Cache c = cache();
        assertFalse(Thread.currentThread().isVirtual(), "the case runs on JUnit's platform thread");
        assertThrows(IllegalStateException.class, () -> c.get("k", value("v", Duration.ofMinutes(1))));
        assertThrows(IllegalStateException.class, () -> c.remove("k"));
    }

    @Test
    public void aLoaderThatThrowsKeepsNothingAndItsFailureLeavesAsThrown() throws Exception {
        Cache c = cache();
        IllegalStateException unchecked = new IllegalStateException("the token endpoint said no");
        assertSame(unchecked, assertThrows(IllegalStateException.class, () -> call(() -> c.get("k", () -> { throw unchecked; }))));
        IOException checked = new IOException("no route to the token endpoint");
        assertSame(checked, assertThrows(IOException.class, () -> call(() -> c.get("k", () -> { throw checked; }))),
                "a checked failure leaves as thrown, undeclared");
        assertEquals("v", call(() -> c.get("k", value("v", Duration.ofMinutes(1)))), "a failed load keeps nothing");
    }

    @Test
    public void aLoaderThatReturnsNullFailsNamingTheKeyAndKeepsNothing() throws Exception {
        Cache c = cache();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> call(() -> c.get("k", () -> null)));
        assertTrue(e.getMessage().contains("'k'"), e.getMessage());
        assertEquals("v", call(() -> c.get("k", value("v", Duration.ofMinutes(1)))));
    }

    @Test
    public void aLoaderThatAsksForItsOwnKeyFails() {
        Cache c = cache();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> call(() -> c.get("k", () -> new Cache.Entry(c.get("k", value("inner", Duration.ofMinutes(1))), Duration.ofMinutes(1)))));
        assertTrue(e.getMessage().contains("'k'"), e.getMessage());
    }

    @Test
    public void callersOfOneKeyShareOneLoad() throws Exception {
        Cache c = cache();
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        Cache.Loader slow = () -> {
            loads.incrementAndGet();
            release.await();
            return new Cache.Entry("shared", Duration.ofMinutes(5));
        };
        Caller first = start(() -> c.get("k", slow));
        first.awaitBlocked();
        Caller second = start(() -> c.get("k", slow));
        second.awaitBlocked();
        release.countDown();
        assertEquals("shared", first.result());
        assertEquals("shared", second.result());
        assertEquals(1, loads.get(), "the second caller waited for the first one's load");
    }

    @Test
    public void aCallerThatWaitedForALoadThatFailedGetsAnIllegalStateWithTheFailureAsCause() throws Exception {
        Cache c = cache();
        CountDownLatch release = new CountDownLatch(1);
        IOException refused = new IOException("the token endpoint refused");
        Caller first = start(() -> c.get("k", () -> { release.await(); throw refused; }));
        first.awaitBlocked();
        Caller second = start(() -> c.get("k", value("unused", Duration.ofMinutes(5))));
        second.awaitBlocked();
        release.countDown();
        assertSame(refused, first.failure(), "the caller whose loader ran gets its failure as thrown");
        IllegalStateException waited = assertInstanceOf(IllegalStateException.class, second.failure());
        assertSame(refused, waited.getCause());
        assertTrue(waited.getMessage().contains("'k'"), waited.getMessage());
    }

    @Test
    public void aLoadOfOneKeyDoesNotHoldUpAnother() throws Exception {
        Cache c = cache();
        CountDownLatch release = new CountDownLatch(1);
        Caller slow = start(() -> c.get("slow", () -> { release.await(); return new Cache.Entry("s", Duration.ofMinutes(5)); }));
        slow.awaitBlocked();
        try {
            assertEquals("fast", call(() -> c.get("fast", value("fast", Duration.ofMinutes(5)))));
        } finally {
            release.countDown();
        }
        assertEquals("s", slow.result());
    }

    @Test
    public void aLoadUnderWayWhenItsKeyIsRemovedIsNotKept() throws Exception {
        Cache c = cache();
        CountDownLatch release = new CountDownLatch(1);
        Caller loading = start(() -> c.get("k", () -> { release.await(); return new Cache.Entry("stale", Duration.ofMinutes(5)); }));
        loading.awaitBlocked();
        call(() -> { c.remove("k"); return null; });
        release.countDown();
        assertEquals("stale", loading.result(), "its own caller still gets the value it loaded");
        assertEquals("fresh", call(() -> c.get("k", value("fresh", Duration.ofMinutes(5)))));
    }

    @Test
    public void theCacheNeverShowsAValue() throws Exception {
        Cache c = cache();
        call(() -> c.get("k", value("token-that-must-not-show", Duration.ofMinutes(5))));
        assertFalse(c.toString().contains("token-that-must-not-show"), c.toString());
    }

    /** {@code task}'s result on a virtual thread of its own; its failure as thrown. */
    protected static <T> T call(Callable<T> task) throws Exception {
        Caller caller = start(task);
        Throwable failure = caller.failure();
        if (failure == null) return caller.result();
        if (failure instanceof Exception x) throw x;
        if (failure instanceof Error x) throw x;
        throw new AssertionError(failure);
    }

    private static Caller start(Callable<?> task) {
        Caller caller = new Caller();
        caller.thread = Thread.ofVirtual().start(() -> {
            try {
                caller.value = task.call();
                caller.done.complete(null);
            } catch (Throwable t) {
                caller.done.complete(t);
            }
        });
        return caller;
    }

    /** One caller on its own virtual thread. */
    private static final class Caller {
        Thread thread;
        volatile Object value;
        final CompletableFuture<Throwable> done = new CompletableFuture<>();

        /** Returns once the caller is parked: in its loader, or waiting for another caller's load. */
        void awaitBlocked() {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(PATIENCE_SECONDS);
            while (!(thread.getState() == Thread.State.WAITING && LockSupport.getBlocker(thread) != null)) {
                if (done.isDone()) fail("the caller finished instead of waiting: " + done.join());
                if (System.nanoTime() > until) fail("the caller did not block within " + PATIENCE_SECONDS + " s");
                Thread.onSpinWait();
            }
        }

        @SuppressWarnings("unchecked")
        <T> T result() throws Exception {
            Throwable failure = failure();
            if (failure != null) throw new AssertionError("the caller failed", failure);
            return (T) value;
        }

        Throwable failure() throws InterruptedException, ExecutionException {
            try {
                return done.get(PATIENCE_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new AssertionError("the caller did not finish within " + PATIENCE_SECONDS + " s", e);
            }
        }
    }
}
