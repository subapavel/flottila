package io.flottila.runtime.journal;

import io.vertx.core.Future;

/**
 * Where the journal registers a large body's segment before its file appears in {@code segments/} (CORE-3 item 4): a
 * {@link Journal.Spill#persist() persist} inserts the row, and only once the insert succeeded moves the file in, so no
 * segment file lies in {@code segments/} without its row. The Postgres implementation ({@code journal_segments}) is
 * CORE-4c's; a test double records the calls and fails one.
 *
 * @since 0.1
 */
@FunctionalInterface
public interface SegmentRows {

    /**
     * Inserts the row of segment {@code segment} of node {@code node}. A persist retried after a failed insert or a failed
     * move inserts again: the insert is idempotent. A failed future fails the persist, which leaves the spill in place.
     */
    Future<Void> insert(String node, long segment);
}
