package io.flottila.core.dlq;

import io.vertx.core.Future;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The DLQ in memory: tests and dry runs only — a deployed at-least-once flow parks in Postgres.
 *
 * @since 0.1
 */
public final class InMemoryDlq implements Dlq {

    private final Map<String, Parked> rows = new LinkedHashMap<>();   // guarded by this

    @Override
    public synchronized Future<Void> park(Parked parked) {
        Objects.requireNonNull(parked, "parked");
        rows.putIfAbsent(parked.messageId(), parked);
        return Future.succeededFuture();
    }

    @Override
    public synchronized Future<List<Parked>> list() {
        return Future.succeededFuture(List.copyOf(rows.values()));
    }

    @Override
    public synchronized Future<Optional<Parked>> get(String messageId) {
        return Future.succeededFuture(Optional.ofNullable(rows.get(messageId)));
    }

    @Override
    public synchronized Future<Void> remove(String messageId) {
        rows.remove(messageId);
        return Future.succeededFuture();
    }

    public synchronized int size() { return rows.size(); }
}
