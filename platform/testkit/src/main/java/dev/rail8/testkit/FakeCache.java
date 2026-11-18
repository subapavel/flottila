package dev.rail8.testkit;

import dev.rail8.api.Cache;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The cache of {@link FakeServices}: in memory, on the clock it is given, at most {@link #MAX_ENTRIES} entries with the least
 * recently used one dropped first. One load per key at a time; a load of one key does not hold up another.
 */
final class FakeCache implements Cache {

    static final int MAX_ENTRIES = 10_000;

    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final LinkedHashMap<String, Kept> kept = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, Load> loading = new HashMap<>();

    FakeCache(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    private record Kept(String value, Instant until) {}

    /** A load under way: the thread running it, and its value or failure for the callers waiting. */
    private static final class Load {
        final Thread runner = Thread.currentThread();
        final CompletableFuture<String> result = new CompletableFuture<>();
    }

    @Override
    public String get(String key, Loader loader) {
        check(key);
        Objects.requireNonNull(loader, "loader");
        Load load;
        boolean runs;
        lock.lock();
        try {
            Kept k = kept.get(key);
            if (k != null && clock.instant().isBefore(k.until())) return k.value();
            if (k != null) kept.remove(key);
            load = loading.get(key);
            runs = load == null;
            if (runs) {
                load = new Load();
                loading.put(key, load);
            } else if (load.runner == Thread.currentThread()) {
                throw new IllegalStateException("the loader of cache key '" + key + "' asks for its own key");
            }
        } finally {
            lock.unlock();
        }
        return runs ? run(key, loader, load) : await(key, load);
    }

    private String run(String key, Loader loader, Load load) {
        Entry entry;
        try {
            entry = loader.load();
            if (entry == null) throw new IllegalStateException("the loader of cache key '" + key + "' returned null");
        } catch (Throwable failure) {
            finish(key, load, null);
            load.result.completeExceptionally(failure);
            throw FakeCache.<RuntimeException>undeclared(failure);
        }
        finish(key, load, entry);
        load.result.complete(entry.value());
        return entry.value();
    }

    /** Ends {@code load}; keeps its entry unless the key was removed meanwhile or the ttl keeps nothing. */
    private void finish(String key, Load load, Entry entry) {
        lock.lock();
        try {
            if (loading.get(key) != load) return;
            loading.remove(key);
            if (entry == null || !entry.ttl().isPositive()) return;
            kept.put(key, new Kept(entry.value(), clock.instant().plus(entry.ttl())));
            if (kept.size() > MAX_ENTRIES) kept.pollFirstEntry();
        } finally {
            lock.unlock();
        }
    }

    private static String await(String key, Load load) {
        try {
            return load.result.get();
        } catch (ExecutionException failed) {
            throw new IllegalStateException("the load of cache key '" + key + "' this call waited for failed", failed.getCause());
        } catch (InterruptedException interrupted) {
            throw FakeCache.<RuntimeException>undeclared(interrupted);
        }
    }

    @Override
    public void remove(String key) {
        check(key);
        lock.lock();
        try {
            kept.remove(key);
            loading.remove(key);
        } finally {
            lock.unlock();
        }
    }

    /** How many entries are kept, never a value. */
    @Override
    public String toString() {
        lock.lock();
        try {
            return "FakeCache[" + kept.size() + " kept]";
        } finally {
            lock.unlock();
        }
    }

    private static void check(String key) {
        Objects.requireNonNull(key, "key");
        if (key.isEmpty() || key.length() > MAX_KEY_CHARS || key.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("cache key '" + key + "' is not 1 to " + MAX_KEY_CHARS + " characters without control characters");
        }
        if (!Thread.currentThread().isVirtual()) {
            throw new IllegalStateException("the cache blocks and is used on a virtual thread, not on " + Thread.currentThread());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T undeclared(Throwable failure) throws T {
        throw (T) failure;
    }
}
