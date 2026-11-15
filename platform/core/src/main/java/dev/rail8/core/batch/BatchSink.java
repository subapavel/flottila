package dev.rail8.core.batch;

import io.vertx.core.Future;

import java.util.List;

/**
 * Where a {@link BatchingWriter} commits: one batch = one commit, completed per batch. Implemented by
 * the Postgres unnest-insert in runtime and by in-memory sinks in tests.
 *
 * @param <T> the item type (envelopes, snapshots, keys …)
 * @since 0.1
 */
@FunctionalInterface
public interface BatchSink<T> {

    /**
     * Commits the whole batch or fails it as a whole. It answers a {@code Future}, never {@code null}; a null answer fails the
     * batch.
     */
    Future<Void> write(List<T> batch);
}
