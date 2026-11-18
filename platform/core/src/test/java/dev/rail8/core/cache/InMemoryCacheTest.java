package dev.rail8.core.cache;

import dev.rail8.api.Cache;
import dev.rail8.testkit.CacheContract;
import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeRuntime;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The node's in-memory cache: each scope passes {@link CacheContract}; scopes of another tenant, package or step type never meet
 * a key; the node keeps at most its entries and its bytes, dropping the least recently used entry first.
 */
class InMemoryCacheTest {

    final FakeClock clock = FakeClock.at(FakeRuntime.T0);

    @Nested
    class AScope extends CacheContract {
        @Override protected Cache cache() { return new InMemoryCache(clock, InMemoryCache.MAX_ENTRIES, InMemoryCache.MAX_BYTES).scope("default", "invoice", "hmac-sign"); }

        @Override protected void advance(Duration by) { clock.advance(by); }
    }

    static String get(Cache c, String key, String value) throws Exception {
        return onVirtualThread(() -> c.get(key, () -> new Cache.Entry(value, Duration.ofHours(1))));
    }

    @Test
    void scopesNeverMeetAKey() throws Exception {
        InMemoryCache node = new InMemoryCache(clock, 100, 100_000);
        Cache sign = node.scope("default", "invoice", "hmac-sign");
        assertEquals("a", get(sign, "k", "a"));
        get(sign, "key-zz", "value-zz");
        assertEquals("a", get(node.scope("default", "invoice", "hmac-sign"), "k", "other"), "the same scope again is the same entries");
        assertEquals("b", get(node.scope("default", "invoice", "token"), "k", "b"), "another step type");
        assertEquals("c", get(node.scope("default", "orders", "hmac-sign"), "k", "c"), "another package");
        assertEquals("d", get(node.scope("other", "invoice", "hmac-sign"), "k", "d"), "another tenant");
        assertEquals(5, node.entries());
        assertFalse(sign.toString().contains("zz"), "neither keys nor values are shown: " + sign);
    }

    @Test
    void theLeastRecentlyUsedEntryIsDroppedOverTheEntryLimit() throws Exception {
        InMemoryCache node = new InMemoryCache(clock, 2, 100_000);
        Cache c = node.scope("default", "invoice", "t");
        get(c, "one", "1");
        get(c, "two", "2");
        get(c, "one", "unused");   // one is now the most recently used
        get(c, "three", "3");
        assertEquals(2, node.entries());
        assertEquals("1", get(c, "one", "reloaded"));
        assertEquals("reloaded", get(c, "two", "reloaded"), "two was the least recently used and was dropped");
    }

    @Test
    void theNodeKeepsWithinItsBytes() throws Exception {
        InMemoryCache node = new InMemoryCache(clock, 100, 2L * (1 + 10) * 2);   // two entries of a one-character key and ten characters
        Cache c = node.scope("default", "invoice", "t");
        get(c, "a", "0123456789");
        get(c, "b", "0123456789");
        assertEquals(2, node.entries());
        get(c, "c", "0123456789");
        assertEquals(2, node.entries(), "the third entry pushed out the first");
        AtomicInteger loads = new AtomicInteger();
        onVirtualThread(() -> c.get("a", () -> new Cache.Entry("x" + loads.incrementAndGet(), Duration.ofHours(1))));
        assertEquals(1, loads.get());
    }

    @Test
    void limitsArePositive() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryCache(clock, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new InMemoryCache(clock, 1, 0));
        assertTrue(InMemoryCache.MAX_ENTRIES > 0 && InMemoryCache.MAX_BYTES > 0);
    }

    /** {@code task}'s result on a virtual thread, as a step would call the cache. */
    static <T> T onVirtualThread(Callable<T> task) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            return executor.submit(task).get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception x) throw x;
            throw e;
        }
    }
}
