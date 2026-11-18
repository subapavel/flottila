package dev.rail8.api;

import java.util.Objects;

/** {@link Services#NONE}: no secrets, and a cache that loads on every call and keeps nothing. */
final class NoServices implements Services {

    private static final Cache NOTHING_KEPT = new Cache() {
        @Override
        public String get(String key, Loader loader) {
            check(key);
            Objects.requireNonNull(loader, "loader");
            Entry entry;
            try {
                entry = loader.load();
            } catch (Exception failure) {
                throw NoServices.<RuntimeException>undeclared(failure);
            }
            if (entry == null) throw new IllegalStateException("the loader of cache key '" + key + "' returned null");
            return entry.value();
        }

        @Override
        public void remove(String key) {
            check(key);
        }

        @Override
        public String toString() {
            return "Cache[keeps nothing]";
        }
    };

    @Override
    public Secret secret(StepConfig step, String key) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(key, "key");
        throw new IllegalStateException(step + ": option '" + key + "' names a secret, but these resources were built without a node and hold none");
    }

    @Override
    public Cache cache() {
        return NOTHING_KEPT;
    }

    @Override
    public String toString() {
        return "Services.NONE";
    }

    /** Refuses a key that breaks the rules of {@link Cache}, and a call off a virtual thread. */
    static void check(String key) {
        Objects.requireNonNull(key, "key");
        if (key.isEmpty() || key.length() > Cache.MAX_KEY_CHARS || key.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("cache key '" + key + "' is not 1 to " + Cache.MAX_KEY_CHARS + " characters without control characters");
        }
        if (!Thread.currentThread().isVirtual()) {
            throw new IllegalStateException("the cache blocks and is used on a virtual thread, not on " + Thread.currentThread());
        }
    }

    /** {@code failure} thrown as it is, a checked one too, as {@code Future.await()} throws a failed future's cause. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T undeclared(Throwable failure) throws T {
        throw (T) failure;
    }
}
