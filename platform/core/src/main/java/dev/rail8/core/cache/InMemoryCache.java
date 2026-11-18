package dev.rail8.core.cache;

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
 * The node's cache for custom code, in memory: one store for the whole node, kept within a number of entries and a number of
 * bytes (two per character of each key and value), the least recently used entry dropped first. {@link #scope} is the
 * {@link Cache} of one tenant, package and step type; scopes never share a key. Expiry is on the injected clock. On the node
 * a key of a scope is loaded once at a time, and a load of one key never holds up another.
 *
 * @since 0.1
 */
public final class InMemoryCache {

    /** The node's limit on entries. */
    public static final long MAX_ENTRIES = 10_000;
    /** The node's limit on bytes. */
    public static final long MAX_BYTES = 64L * 1024 * 1024;

    private final Clock clock;
    private final long maxEntries;
    private final long maxBytes;
    private final ReentrantLock lock = new ReentrantLock();
    private final LinkedHashMap<Slot, Kept> kept = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<Slot, Load> loading = new HashMap<>();
    private long bytes;

    /** An empty store on {@code clock}, within {@code maxEntries} entries and {@code maxBytes} bytes. */
    public InMemoryCache(Clock clock, long maxEntries, long maxBytes) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxEntries <= 0 || maxBytes <= 0) throw new IllegalArgumentException("limits must be positive, were " + maxEntries + " entries, " + maxBytes + " bytes");
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
    }

    /** The cache of the steps of {@code stepType} in package {@code packageName} of {@code tenant}. */
    public Cache scope(String tenant, String packageName, String stepType) {
        return new Scoped(new Scope(Objects.requireNonNull(tenant, "tenant"), Objects.requireNonNull(packageName, "packageName"),
                Objects.requireNonNull(stepType, "stepType")));
    }

    /** How many entries are kept now. */
    long entries() {
        lock.lock();
        try {
            return kept.size();
        } finally {
            lock.unlock();
        }
    }

    private record Scope(String tenant, String packageName, String stepType) {}

    private record Slot(Scope scope, String key) {}

    private record Kept(String value, Instant until, long bytes) {}

    /** A load under way: the thread running it, and its value or failure for the callers waiting. */
    private static final class Load {
        final Thread runner = Thread.currentThread();
        final CompletableFuture<String> result = new CompletableFuture<>();
    }

    private final class Scoped implements Cache {
        private final Scope scope;

        Scoped(Scope scope) {
            this.scope = scope;
        }

        @Override
        public String get(String key, Loader loader) {
            check(key);
            Objects.requireNonNull(loader, "loader");
            Slot slot = new Slot(scope, key);
            Load load;
            boolean runs;
            lock.lock();
            try {
                Kept k = kept.get(slot);
                if (k != null && clock.instant().isBefore(k.until())) return k.value();
                if (k != null) drop(slot);
                load = loading.get(slot);
                runs = load == null;
                if (runs) {
                    load = new Load();
                    loading.put(slot, load);
                } else if (load.runner == Thread.currentThread()) {
                    throw new IllegalStateException("the loader of cache key '" + key + "' asks for its own key");
                }
            } finally {
                lock.unlock();
            }
            return runs ? run(slot, loader, load) : await(key, load);
        }

        @Override
        public void remove(String key) {
            check(key);
            Slot slot = new Slot(scope, key);
            lock.lock();
            try {
                drop(slot);
                loading.remove(slot);
            } finally {
                lock.unlock();
            }
        }

        /** The scope's package and step type, never a key or a value. */
        @Override
        public String toString() {
            return "InMemoryCache[" + scope.packageName() + ", " + scope.stepType() + "]";
        }
    }

    private String run(Slot slot, Cache.Loader loader, Load load) {
        Cache.Entry entry;
        try {
            entry = loader.load();
            if (entry == null) throw new IllegalStateException("the loader of cache key '" + slot.key() + "' returned null");
        } catch (Throwable failure) {
            finish(slot, load, null);
            load.result.completeExceptionally(failure);
            throw InMemoryCache.<RuntimeException>undeclared(failure);
        }
        finish(slot, load, entry);
        load.result.complete(entry.value());
        return entry.value();
    }

    /** Ends {@code load}; keeps its entry unless the key was removed meanwhile or its ttl keeps nothing, then holds the limits. */
    private void finish(Slot slot, Load load, Cache.Entry entry) {
        lock.lock();
        try {
            if (loading.get(slot) != load) return;
            loading.remove(slot);
            if (entry == null || !entry.ttl().isPositive()) return;
            drop(slot);
            long size = 2L * (slot.key().length() + entry.value().length());
            kept.put(slot, new Kept(entry.value(), clock.instant().plus(entry.ttl()), size));
            bytes += size;
            while (kept.size() > maxEntries || bytes > maxBytes) {
                bytes -= kept.pollFirstEntry().getValue().bytes();
            }
        } finally {
            lock.unlock();
        }
    }

    /** Forgets what {@code slot} keeps; the lock is held. */
    private void drop(Slot slot) {
        Kept gone = kept.remove(slot);
        if (gone != null) bytes -= gone.bytes();
    }

    private static String await(String key, Load load) {
        try {
            return load.result.get();
        } catch (ExecutionException failed) {
            throw new IllegalStateException("the load of cache key '" + key + "' this call waited for failed", failed.getCause());
        } catch (InterruptedException interrupted) {   // thrown as await() throws it: undeclared, the flag cleared
            throw InMemoryCache.<RuntimeException>undeclared(interrupted);
        }
    }

    private static void check(String key) {
        Objects.requireNonNull(key, "key");
        if (key.isEmpty() || key.length() > Cache.MAX_KEY_CHARS || key.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("cache key '" + key + "' is not 1 to " + Cache.MAX_KEY_CHARS + " characters without control characters");
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
