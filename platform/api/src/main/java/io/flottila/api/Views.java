package io.flottila.api;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Memoized parsed views (XdmNode, JsonObject, …) of one Body. Shared by reference between
 * envelopes carrying the same body — safe because nothing is ever mutated. A parser must not ask
 * for another view of the same envelope while it runs (ConcurrentHashMap refuses the recursive update).
 *
 * @since 0.1
 */
public final class Views {
    private final ConcurrentHashMap<Class<?>, Object> map = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> type, Supplier<T> parser) {
        return (T) map.computeIfAbsent(type, t -> parser.get());
    }
}
