package io.flottila.runtime;

import io.flottila.core.Envelope;
import io.smallrye.mutiny.Uni;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Store-and-forward + per-step payload snapshots. Postgres later; interface stays. */
public interface MessageStore {
    Uni<Void> save(Envelope e);
    Uni<Optional<Envelope>> load(String id, int step);

    final class InMemory implements MessageStore {
        private final Map<String, Envelope> map = new ConcurrentHashMap<>();
        public Uni<Void> save(Envelope e) { map.put(e.id() + "/" + e.step(), e); return Uni.createFrom().voidItem(); }
        public Uni<Optional<Envelope>> load(String id, int step) {
            return Uni.createFrom().item(Optional.ofNullable(map.get(id + "/" + step)));
        }
    }
}
