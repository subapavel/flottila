package io.flottila.core.queue;

import io.flottila.core.record.Page;
import io.vertx.core.Future;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable work between a consumer's durable save and the pipeline, for at-least-once flows
 * (ARCHITECTURE §7): one row per message, strictly serial per partition key, parallel across
 * keys, leases with expiry for redelivery. Postgres implementation in runtime (SKIP LOCKED),
 * in-memory one here for tests and the flow builder's unit tests.
 *
 * <p>Every hand-out of a row ({@link #claim}, {@link #completeAndTakeNext}) counts one attempt; the
 * {@link Item#attempts()} it was handed out with is the holder's fencing token (D-055):
 * renew, hold, complete and the fast path act only for the current holder (Q-1, D-074) — the row exists, is not held and
 * was last handed out with that token, which is at least 1 (a token of 0, a row never handed out, matches no row). A held
 * row is never handed out again, never completed and stays the oldest row of its key, so it blocks the rows behind it until
 * a human deals with it: {@link #held} lists the held rows and {@link #release} hands one back to the claim (D-067), whose
 * holder then completes it. A lost row (its body given up, D-105) blocks its key the same way and is never handed out:
 * {@link #lost} lists the lost rows and {@link #discard} deletes one with an audit row (D-126).
 *
 * @since 0.1
 */
public interface WorkQueue {

    /**
     * One queued message, as handed out. Two items are equal when they are the same hand-out — seq, message, flow, step, key
     * and attempts, the holder's token; {@code doubts}, read off the row's history, is not compared (P2-15a: an item a test or
     * a caller builds without it names the same hand-out).
     *
     * @param attempts how many times the row has been handed out, this hand-out included (1 on the first claim)
     * @param doubts how many of its hand-outs were of a row an earlier holder had been handed and left unsettled (D-101,
     *               derived, nothing written per send): that holder may have sent before it died or was fenced, so the run is in
     *               doubt; 0 on a first hand-out and on a row an operator {@linkplain #release released}
     * @since 0.1
     */
    record Item(long seq, String messageId, String flow, int step, String partitionKey, int attempts, int doubts) {

        /** An item in no doubt. */
        public Item(long seq, String messageId, String flow, int step, String partitionKey, int attempts) {
            this(seq, messageId, flow, step, partitionKey, attempts, 0);
        }

        @Override public boolean equals(Object o) {
            return o instanceof Item other && handOut().equals(other.handOut());
        }

        @Override public int hashCode() {
            return handOut().hashCode();
        }

        /** What names the hand-out: every component but {@code doubts}. */
        private List<Object> handOut() {
            return Arrays.asList(seq, messageId, flow, step, partitionKey, attempts);
        }
    }

    /**
     * One held row (D-067).
     *
     * @param item the row as last handed out: its attempts are those of the claim that was held
     * @param heldAt the instant of the hold
     * @param blocks the rows of its key in its flow behind it, which wait while it is held (D-126: the barrier made visible); 0 for
     *               a row without a key
     * @since 0.1
     */
    record Held(Item item, Instant heldAt, long blocks) {

        /** A held row whose queue does not count what it blocks (the in-memory one): {@code blocks} 0. */
        public Held(Item item, Instant heldAt) {
            this(item, heldAt, 0);
        }
    }

    /**
     * One lost row (D-105, D-126): its body was given up by an accepted {@code FLOTTILA_JOURNAL_ACCEPT_LOSS}, so it is never claimed,
     * yet it stays the oldest row of its key until an operator {@linkplain #discard discards} it.
     *
     * @param item the row as last handed out (attempts 0 when never)
     * @param lostAt the instant its loss was accepted
     * @param blocks the rows of its key in its flow behind it, which wait until it is discarded; 0 for a row without a key
     * @since 0.1
     */
    record Lost(Item item, Instant lostAt, long blocks) {}

    /**
     * What an operator's discard of a lost row did (D-126): the row is deleted and one audit row written, in one transaction.
     *
     * @param auditId the id of the audit row written
     * @param flow the discarded row's flow, to wake
     * @param nextNode the node whose journal holds the body of its key's next row, if it has a key and that row exists and names its
     *                 node — the node to announce, as a release does (D-121 (b) B4 (ii)); never null
     * @throws NullPointerException {@code flow} or {@code nextNode} is null
     * @since 0.1
     */
    record Discarded(long auditId, String flow, Optional<String> nextNode) {

        public Discarded {
            Objects.requireNonNull(flow, "flow");
            Objects.requireNonNull(nextNode, "nextNode");
        }
    }

    /**
     * What a same-key fast path did (Q-1, D-074): {@link #STALE} — the caller no longer held the row, nothing was completed and
     * nothing taken — or {@link #done done(next)}: the row was completed and {@code next} is the row of its key handed out to the
     * caller, if any.
     *
     * @param completed the row was removed by this call
     * @param next the next row of the key now leased to the caller; never null, and empty when nothing was completed
     * @param nextNode when nothing was taken: the node whose journal holds the key's next row, if that row is not held and lies on
     *                 another node than the caller's queue claims for — the row that node may claim now (D-121 (b) B4 (i)); never
     *                 null, and empty otherwise
     * @throws NullPointerException {@code next} or {@code nextNode} is null
     * @throws IllegalArgumentException a next row without a completion, or a next node beside a next row or without a completion
     * @since 0.1
     */
    record Completion(boolean completed, Optional<Item> next, Optional<String> nextNode) {

        /** Nothing completed, nothing taken: the row was claimed again, held, completed or unknown. */
        public static final Completion STALE = new Completion(false, Optional.empty());

        public Completion {
            Objects.requireNonNull(next, "next");
            Objects.requireNonNull(nextNode, "nextNode");
            if (!completed && next.isPresent()) throw new IllegalArgumentException("a next row without a completion: " + next.get());
            if (nextNode.isPresent() && (!completed || next.isPresent())) {
                throw new IllegalArgumentException("a next node only for a completion that took nothing: " + nextNode.get());
            }
        }

        /** A completion with no next node: {@code next} is the row of its key handed out with it, if any. */
        public Completion(boolean completed, Optional<Item> next) {
            this(completed, next, Optional.empty());
        }

        /** The row was completed; {@code next} is the row of its key handed out with it, if any. */
        public static Completion done(Optional<Item> next) {
            return new Completion(true, next);
        }

        /** The row was completed and nothing taken: the key's next row lies on {@code node}, for that node to claim. */
        public static Completion elsewhere(String node) {
            return new Completion(true, Optional.empty(), Optional.of(node));
        }
    }

    /**
     * What an operator's release handed back to the claim (D-067, D-121 (b) B4 (ii)): the row's flow and the node whose journal
     * holds its body — each empty when the queue cannot tell (a row of any node, a queue that answers only {@link #release}).
     *
     * @param flow the released row's flow; never null
     * @param bodyNode the node whose journal holds the row's body; never null
     * @since 0.1
     */
    record Released(Optional<String> flow, Optional<String> bodyNode) {

        /** A release whose row the queue cannot tell: every flow, any node. */
        public static final Released UNTOLD = new Released(Optional.empty(), Optional.empty());

        public Released {
            Objects.requireNonNull(flow, "flow");
            Objects.requireNonNull(bodyNode, "bodyNode");
        }
    }

    /** Appends a row; {@code partitionKey} null = no ordering. */
    Future<Void> enqueue(String messageId, String flow, int step, String partitionKey);

    /**
     * Appends a row handed out at once (D-121 A2, the local hand-off): when it heads its key — no other row of ({@code flow},
     * {@code partitionKey}) exists, held or not, leased or not; a row without a key always heads it — it is written leased to
     * {@code workerId} for {@code lease}, attempts 1, doubts 0, and answered as that hand-out; otherwise it is a plain row, as
     * {@link #enqueue} appends it, and the answer is empty. A row written leased is the holder's as a claimed one is: renewed,
     * completed, held by its attempts, and claimable again once its lease ran out, in doubt.
     *
     * <p>This default cannot hand off: it appends a plain row and answers empty.
     *
     * @since 0.1
     */
    default Future<Optional<Item>> enqueueLeased(String messageId, String flow, int step, String partitionKey, String workerId, Duration lease) {
        return enqueue(messageId, flow, step, partitionKey).map(Optional.empty());
    }

    /**
     * Leases up to {@code max} rows to {@code workerId}: a row is claimable when it is not held, it is due (not {@linkplain #defer deferred}
     * past now), its lease is free, it is the oldest row of its key (held rows included) and that key holds no live lease (or it has no key).
     * Each row handed out counts one more attempt; the rows come in seq order. A worker id is its node's id (D-101): another
     * node's row is claimable only once that holder has no live membership row (released, or none at all), and a restarted
     * node's own rows only after its previous incarnation's lease end. A row handed out while an earlier holder left it unsettled
     * counts one more {@linkplain Item#doubts() doubt}.
     */
    Future<List<Item>> claim(String workerId, String flow, int max, Duration lease);

    /**
     * Removes a finished row for its current holder: {@code true} when this call removed it; {@code false} when the caller no
     * longer holds it (claimed again, held, completed or unknown) — the row is left to its holder, as it was.
     */
    Future<Boolean> complete(long seq, int attempts);

    /**
     * {@link #complete} answering, once the delete committed, what it did as a {@link Completion}: {@link Completion#STALE} when the
     * caller no longer held {@code seq}; otherwise completed, nothing taken, and — for a keyed row — the node of the key's next row
     * when that row is neither held nor lost and lies in another journal than the one this queue claims for (D-121 (b) B4 (i): the
     * node that may claim it now).
     *
     * <p>This default completes by {@link #complete} and names no node: a queue that holds no bodies has no other node to tell.
     *
     * @since 0.1
     */
    default Future<Completion> completeRow(long seq, int attempts) {
        return complete(seq, attempts).map(removed -> removed ? Completion.done(Optional.empty()) : Completion.STALE);
    }

    /**
     * {@link #completeRow} for a row without a partition key, which a queue may gather with other such completions into one
     * statement (D-122): the answer comes once the delete committed. Each row is fenced by its own token, so a member the caller
     * no longer holds answers {@link Completion#STALE} and its neighbours complete; a batching queue answers STALE for a keyed row
     * too, which it never completes this way.
     *
     * <p>This default completes by {@link #completeRow}, a keyed row included.
     *
     * @since 0.1
     */
    default Future<Completion> completeUnkeyed(long seq, int attempts) {
        return completeRow(seq, attempts);
    }

    /**
     * Same-key fast path, under the same condition as {@link #complete}: completes {@code seq} and, if it had a key, leases the
     * next row of that key to the same worker in the same round-trip — a hot partition never waits for a new claim round.
     * {@link Completion#STALE} when the caller no longer holds {@code seq}: nothing is completed and nothing taken.
     */
    Future<Completion> completeAndTakeNext(long seq, int attempts, String workerId, Duration lease);

    /**
     * Extends the lease of {@code seq} to {@code lease} from now, while the row exists, is not held and was last
     * handed out with {@code attempts}, at least 1 — a lease that ran out and was not claimed again is revived. {@code true} when
     * extended; {@code false} when the caller no longer holds the row (claimed again, held, completed or unknown).
     */
    Future<Boolean> renew(long seq, int attempts, Duration lease);

    /**
     * Hands {@code seq} back to the claim not before {@code delay} from now (CORE-14a P3, D-135 draft: a scheduled retry), under the
     * same condition as {@link #renew}: its holder and its lease are cleared — the row is no one's, so its next hand-out counts one
     * more attempt and no doubt — and it is claimable only once {@code delay} passed on the queue's clock (Postgres: the database's
     * {@code now()}). Its seq, message id, attempts, doubts and body ref are kept, so it still heads its key and blocks the rows
     * behind it while it waits. {@code true} when deferred by this call; {@code false} when the caller no longer holds the row
     * (claimed again, held, completed or unknown): nothing changed.
     *
     * @since 0.1
     */
    Future<Boolean> defer(long seq, int attempts, Duration delay);

    /**
     * Marks {@code seq} held, under the same condition as {@link #renew}: it is never claimed again and blocks its key
     * until a human deals with it. {@code true} when held by this call.
     */
    Future<Boolean> hold(long seq, int attempts);

    /**
     * The held rows of every flow a page at a time, in seq order: up to {@code limit} rows after the cursor {@code after}, and
     * a cursor to the next page when another held row exists. The cursor is a position (a seq), never an offset: a row
     * released or completed meanwhile shifts nothing.
     *
     * @throws IllegalArgumentException {@code limit} is not 1..{@link Page#MAX_LIMIT}, or {@code after} is not a cursor this
     *                                  queue hands out — thrown at the call, not as a failed future
     */
    Future<Page<Held>> held(Optional<String> after, int limit);

    /**
     * The lost rows of every flow a page at a time, in seq order, as {@link #held} pages the held ones (D-126): up to {@code limit}
     * rows after the cursor {@code after}, each with the rows of its key behind it, and a cursor to the next page when another lost
     * row exists.
     *
     * <p>This default lists nothing, after checking the limit as {@link #held} does: a queue that holds no bodies never loses one.
     *
     * @throws IllegalArgumentException {@code limit} is not 1..{@link Page#MAX_LIMIT} — thrown at the call, not as a failed future
     * @since 0.1
     */
    default Future<Page<Lost>> lost(Optional<String> after, int limit) {
        Page.requireLimit(limit);
        return Future.succeededFuture(new Page<>(List.of(), Optional.empty()));
    }

    /**
     * Deletes {@code seq} only while it is lost (D-126 L3) — a keyed row under its key's lock, as a keyed complete takes it — and in
     * the same transaction writes one audit row: action {@code work.discard}, subject {@code work/<seq>}, the self-declared
     * {@code actor}, and a detail naming the row's flow, message id, partition key, lost instant and the caller's {@code remote}
     * address. Empty when {@code seq} is not lost (unknown, queued, leased or held): nothing deleted, nothing audited.
     *
     * <p>This default discards nothing: a queue that holds no bodies has no lost row.
     *
     * @since 0.1
     */
    default Future<Optional<Discarded>> discard(long seq, String actor, String remote) {
        return Future.succeededFuture(Optional.empty());
    }

    /**
     * Hands a held row back to the claim: clears the hold and the lease, keeps the attempts, so the row is claimable at once
     * and, still the oldest of its key, before the rows behind it. {@code true} only when {@code seq} was held; an unknown,
     * leased or queued row is left as it was.
     */
    Future<Boolean> release(long seq);

    /**
     * {@link #release} answering what it released: empty when {@code seq} was not held (nothing changed), else the row's flow and
     * body node as far as this queue knows them — what the release wakes, here and on the row's node (D-121 (b) B4 (ii)).
     *
     * <p>This default releases by {@link #release} and cannot tell the row: a release answers {@link Released#UNTOLD}.
     *
     * @since 0.1
     */
    default Future<Optional<Released>> releaseRow(long seq) {
        return release(seq).map(released -> released ? Optional.of(Released.UNTOLD) : Optional.empty());
    }

    /**
     * {@code true} while any row of {@code flow} exists — due, backing off after a lease ran out, leased or held; a completed row
     * is gone and never counts, nor does another flow's row (D-063: a redeploy that removes an at-least-once flow checks it).
     *
     * @throws NullPointerException {@code flow} is null, thrown at the call, not as a failed future
     */
    Future<Boolean> hasItems(String flow);
}
