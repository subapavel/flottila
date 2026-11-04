package io.flottila.runtime.journal;

import io.vertx.core.Future;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The journal's rows in Postgres (CORE-3 items 5 and 9): its {@code journal_nodes} row and its {@code journal_segments}
 * rows. Every segment — the one an open starts, each one the writer pre-opens, a large body's at its
 * {@link Journal.Spill#persist() persist} — has its row inserted before its file appears in {@code segments/}, so no segment
 * file lies there without its row. At start the journal reads its node's journal id and segment rows through here. The
 * Postgres implementation is {@link PgSegmentRows}; a journal without Postgres (CORE-4a/4b's, their tests) keeps no rows:
 * only {@link #insert} is its own, every other method answers as no row were kept. The start also reads the committed
 * {@link Pin pins} into the journal through here and, once an operator accepted a loss ({@code FLOTTILA_JOURNAL_ACCEPT_LOSS}),
 * {@link #giveUp gives} them up.
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

    /**
     * The sweep of {@code node}'s {@code candidates} (CORE-3 item 10): of them, the rows no pin holds — a work row's ref not lost,
     * a DLQ row's kept ref not archived and not lost, a kept ref not archived, not lost and created after {@code keptSince} (the
     * clock − {@code FLOTTILA_JOURNAL_KEEP_MAX_AGE}) — locked, passing a row another transaction holds, their pins asked again
     * under a later snapshot, then deleted; answers the segments whose row it deleted (their files are the caller's to unlink).
     * Without rows kept: none.
     */
    default Future<Set<Long>> sweep(String node, Set<Long> candidates, Instant keptSince) {
        return Future.succeededFuture(Set.of());
    }

    /** Deletes the rows of {@code segments} of {@code node}: segments whose file is not there. */
    default Future<Void> drop(String node, Set<Long> segments) {
        return Future.succeededFuture();
    }

    /**
     * A committed pin of a node's journal (CORE-3 item 9): a work row ({@code seq} at least 1, its message's ingress body) or a
     * kept ref ({@code seq} {@value #REF}, its key) that is not lost — a ref also not archived — naming the record at
     * {@code segment, position, length}.
     *
     * @since 0.1
     */
    record Pin(long seq, String messageId, int step, String part, long segment, long position, long length) {

        /** The {@code seq} of a kept ref's pin: no work row. */
        public static final long REF = -1;

        /** A kept ref's pin, not a work row's. */
        public boolean kept() { return seq == REF; }

        /** What is given up, as the refusal names it and the digest covers it: the row, the key and the record. */
        public String item() {
            return (kept() ? "ref " + messageId + "/" + step + "/" + part : "work item " + seq + " of message " + messageId)
                    + " at segment " + segment + " " + position + "+" + length;
        }
    }

    /**
     * The replacement of a journal given up whole: {@code registered} (null: {@code node} has no {@code journal_nodes} row) is
     * replaced by {@code now}, and every segment row of the node goes.
     *
     * @since 0.1
     */
    record Replacement(UUID registered, UUID now) { }

    /**
     * The pins of {@code node}'s journal that no record held now holds: a pin is held when {@code ends} names its segment and it
     * ends at or before the end of that segment's records; an empty {@code ends} holds none (every pin). In no order.
     */
    default Future<List<Pin>> pinsBeyond(String node, Map<Long, Long> ends) {
        return Future.succeededFuture(List.of());
    }

    /** Whether {@code value} is a {@code <journal id>:<digest>} of {@code node} accepted already ({@code journal_losses}). */
    default Future<Boolean> accepted(String node, String value) {
        return Future.succeededFuture(Boolean.FALSE);
    }

    /**
     * Gives {@code pins} up in one transaction: every work row and kept ref of {@code node} whose ref lies in a given-up extent — in
     * a pin's segment, from its cut in {@code ends} (the records a start's scan holds there; CORE-4d r1: the bytes from it are gone
     * whether or not a row pins them), from 0 for a segment not in {@code ends} (its file gone, or the whole journal given up) —
     * gets {@code lost_at} (one instant, the record's), its rows read after the transaction locked them {@code for update}, so a
     * reference insert copying one of them meanwhile is waited for and its copy given up too, or waits and finds no source;
     * {@code <journal>:<digest>} is recorded as accepted at that instant, and with a {@code replacement} the node's journal is
     * replaced as it says. Nothing is deleted but the replaced journal's segment rows.
     */
    default Future<Void> giveUp(String node, UUID journal, String digest, List<Pin> pins, Map<Long, Long> ends, Replacement replacement) {
        return Future.succeededFuture();
    }
}
