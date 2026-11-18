package dev.rail8.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Services#NONE}, what resources built without a node hold: {@link Services#secret} refuses naming the step and the key,
 * and its cache keeps nothing, so every {@code get} loads. That cache still keeps the rules of {@link Cache}: a key it would
 * refuse is refused, it runs only on a virtual thread, and a loader's failure leaves as thrown.
 */
class ServicesNoneTest {

    static final StepConfig STEP = new StepConfig(1, "hmac-sign", "sign", false, Map.of("key", "secret://partner-hmac"));

    static <T> T onVirtualThread(Callable<T> call) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            return executor.submit(call).get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception x) throw x;
            throw e;
        }
    }

    @Test
    void theOldConstructorsHoldNone() {
        StepResources r = new StepResources(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), (delay, task) -> () -> false, () -> "id",
                new InMemoryBlobStore(1024), Hasher.sha256(), 1024, Path.of("."));
        assertSame(Services.NONE, r.services());
    }

    @Test
    void secretRefusesNamingTheStepAndTheKey() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Services.NONE.secret(STEP, "key"));
        assertTrue(e.getMessage().contains("step 'sign'") && e.getMessage().contains("'key'"), e.getMessage());
    }

    @Test
    void theCacheLoadsOnEveryCall() throws Exception {
        Cache cache = Services.NONE.cache();
        AtomicInteger loads = new AtomicInteger();
        Cache.Loader loader = () -> new Cache.Entry("v" + loads.incrementAndGet(), Duration.ofHours(1));
        assertEquals("v1", onVirtualThread(() -> cache.get("k", loader)));
        assertEquals("v2", onVirtualThread(() -> cache.get("k", loader)));
        onVirtualThread(() -> { cache.remove("k"); return null; });
    }

    @Test
    void theCacheKeepsTheRulesOfACache() throws Exception {
        Cache cache = Services.NONE.cache();
        Cache.Loader loader = () -> new Cache.Entry("v", Duration.ofHours(1));
        assertThrows(IllegalStateException.class, () -> cache.get("k", loader), "off a virtual thread");
        assertThrows(IllegalStateException.class, () -> cache.remove("k"), "off a virtual thread");
        assertThrows(IllegalArgumentException.class, () -> onVirtualThread(() -> cache.get("", loader)));
        assertThrows(IllegalArgumentException.class, () -> onVirtualThread(() -> cache.get("a\nb", loader)));
        assertThrows(IllegalArgumentException.class, () -> onVirtualThread(() -> cache.get("k".repeat((int) Cache.MAX_KEY_CHARS + 1), loader)));
        assertEquals("v", onVirtualThread(() -> cache.get("k".repeat((int) Cache.MAX_KEY_CHARS), loader)));
        IOException checked = new IOException("no route");
        assertSame(checked, assertThrows(IOException.class, () -> onVirtualThread(() -> cache.get("k", () -> { throw checked; }))),
                "a checked failure leaves as thrown");
        assertInstanceOf(IllegalStateException.class, assertThrows(Exception.class, () -> onVirtualThread(() -> cache.get("k", () -> null))));
    }
}
