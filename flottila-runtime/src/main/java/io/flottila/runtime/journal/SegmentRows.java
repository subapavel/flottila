package io.flottila.runtime.journal;

import io.vertx.core.Future;

import java.util.Set;
import java.util.UUID;

/**
 * The journal's rows in Postgres (CORE-3 items 5 and 9): its {@code journal_nodes} row and its {@code journal_segments}
 * rows. Every segment — the one an open starts, each one the writer pre-opens, a large body's at its
 * {@link Journal.Spill#persist() persist} — has its row inserted before its file appears in {@code segments/}, so no segment
 * file lies there without its row. At start the journal reads its node's journal id and segment rows through here. The
 * Postgres implementation is {@link PgSegmentRows}; a journal without Postgres (CORE-4a/4b's, their tests) keeps no rows:
 * only {@link #insert} is its own, every other method answers as no row were kept.
 *
 * @since 0.1
 */
@FunctionalInterface
public interface SegmentRows {

    /**
     * Inserts the row of segment {@code segment} of node {@code node}. A persist retried after a failed insert or a failed
     * move inserts again, and so does a pre-open: the insert is idempotent. A failed future creates no file of that segment;
     * it fails the persist (which leaves the spill in place) and the open; a pre-open tries it again on the timer with a
     * bounded backoff, the journal DEGRADED meanwhile (its appends wait for that segment), never failed by it.
     */
    Future<Void> insert(String node, long segment);

    /** The journal id registered for {@code node}, or null when none is (or no rows are kept). */
    default Future<UUID> journalOf(String node) {
        return Future.succeededFuture();
    }

    /** Registers {@code journal} as {@code node}'s journal: a first start, or a {@code NODE} file found without its row. */
    default Future<Void> register(String node, UUID journal) {
        return Future.succeededFuture();
    }

    /**
     * The segment numbers of {@code node}'s rows: at start a segment file without its row is unlinked and a row without its
     * file dropped. Null when no rows are kept, and a start then reconciles nothing.
     */
    default Future<Set<Long>> segments(String node) {
        return Future.succeededFuture();
    }

    /** Deletes the rows of {@code segments} of {@code node}: segments whose file is not there. */
    default Future<Void> drop(String node, Set<Long> segments) {
        return Future.succeededFuture();
    }
}
