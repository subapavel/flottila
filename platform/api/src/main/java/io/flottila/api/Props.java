package io.flottila.api;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Flow state: router decisions, split index, replayOf — anything a later stage decides on.
 * Values must be JSON-serialisable (string, number, boolean, small object). Never reaches producers.
 *
 * @since 0.1
 */
public record Props(Map<String, Object> asMap) {
    public Props { asMap = Map.copyOf(asMap); }

    public static Props empty() { return new Props(Map.of()); }

    public <T> Props with(Key<T> key, T value) { var m = new HashMap<>(asMap); m.put(key.name(), value); return new Props(m); }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(Key<T> key) { return Optional.ofNullable((T) asMap.get(key.name())); }
}
