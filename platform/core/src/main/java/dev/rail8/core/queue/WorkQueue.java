package dev.rail8.core.queue;

import dev.rail8.core.dlq.Parked;
import dev.rail8.core.record.Page;
import io.vertx.core.Future;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable work between a consumer's durable save and the pipeline, for at-least-once flows: one row per message,
 * strictly serial per partition key, parallel across keys, and leases that expire so a row is handed out again when its
 * holder dies. The Postgres implementation lives in runtime (SKIP LOCKED); the in-memory one here serves tests.
 *
 * <p>Every hand-out of a row ({@link #claim}, {@link #completeAndTakeNext}) counts one attempt; the
 * {@link Item#attempts()} it was handed out with is the holder's fencing token: renew, hold, complete and the fast path
 * act only for the current holder, that is when the row exists, is not held and was last handed out with that token,
 * which is at least 1 (a token of 0, a row never handed out, matches no row). A held row is never handed out again,
 * never completed and stays the oldest row of its key, so it blocks the rows behind it until a person deals with it:
 * {@link #held} lists the held rows and {@link #release} hands one back to the claim, whose holder then completes it.
 * A lost row (one whose body was given up) blocks its key the same way and is never handed out: {@link #lost} lists
 * the lost rows and {@link #discard} deletes one and writes an audit row. A keyed row whose message failed for good is
 * {@linkplain #stop stopped}: parked and held in one step, so its key keeps its order and waits until an operator retries or
 * cancels the message ({@link dev.rail8.core.dlq.FailedMessages}); {@link #stopped} lists those rows, and neither
 * {@link #held} nor {@link #release} sees them.
 *
 * <p>An operator's cancel of a message that has not ended marks its row ({@link Cancel}). A marked row is handed out as any
 * row is and carries its mark ({@link Item#cancel()}), and {@link #renewRow} tells its holder of a mark written while it
 * runs. A marked row is never held or stopped: {@link #hold} and {@link #stop} answer false for it, and {@link #defer} makes it
 * due at once, so its next hand-out ends it.
 *
 * @since 0.1
 */
public interface WorkQueue {

    /**
     * An operator's cancel of a row's message, as the row is marked with it.
     *
     * @param auditId the cancel's audit row
     * @param actor who cancelled it, by name
     * @param reason the reason given, when one was
     * @since 0.1
     */
    record Cancel(long auditId, String actor, Optional<String> reason) {

        public Cancel {
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * A renewal's answer.
     *
     * @param renewed whether the lease was extended: false when the caller no longer holds the row
     * @param cancel the row's cancel, when it is marked with one
     * @since 0.1
     */
    record Renewal(boolean renewed, Optional<Cancel> cancel) {

        /** The answer for a row the caller no longer holds. */
        public static final Renewal LOST = new Renewal(false, Optional.empty());

        /** The answer for a lease extended on a row that is not marked. */
        public static final Renewal RENEWED = new Renewal(true, Optional.empty());

        public Renewal {
            Objects.requireNonNull(cancel, "cancel");
        }
    }

    /**
     * One queued message, as handed out. Two items are equal when they are the same hand-out — seq, message, flow, step, key
     * and attempts, the holder's token; {@code doubts}, read off the row's history, is not compared, so an item a test or a
     * caller builds without it names the same hand-out.
     *
     * @param attempts how many times the row has been handed out, this hand-out included (1 on the first claim)
     * @param doubts how many of its hand-outs were of a row an earlier holder had been handed and left unsettled, derived from
     *               the row's history rather than written per send: that holder may have sent before it died or was fenced, so
     *               the run is in doubt; 0 on a first hand-out and on a row an operator {@linkplain #release released}
     * @param maxAttempts the row's own bound on its hand-outs, which an operator's retry writes; 0 means the
     *                    flow's bound. Not compared either: it belongs to the row and is the same for each of its hand-outs
     * @param cancel the operator's cancel the row is marked with, when it is; not compared either
     * @since 0.1
     */
    record Item(long seq, String messageId, String flow, int step, String partitionKey, int attempts, int doubts, int maxAttempts,
                Optional<Cancel> cancel) {

        public Item {
            Objects.requireNonNull(cancel, "cancel");
        }

        /** An item not marked by a cancel. */
        public Item(long seq, String messageId, String flow, int step, String partitionKey, int attempts, int doubts, int maxAttempts) {
            this(seq, messageId, flow, step, partitionKey, attempts, doubts, maxAttempts, Optional.empty());
        }

        /** An item in no doubt, bound by its flow. */
        public Item(long seq, String messageId, String flow, int step, String partitionKey, int attempts) {
            this(seq, messageId, flow, step, partitionKey, attempts, 0);
        }

        /** An item bound by its flow ({@code maxAttempts} 0). */
        public Item(long seq, String messageId, String flow, int step, String partitionKey, int attempts, int doubts) {
            this(seq, messageId, flow, step, partitionKey, attempts, doubts, 0);
        }

        @Override public boolean equals(Object o) {
            return o instanceof Item other && handOut().equals(other.handOut());
        }

        @Override public int hashCode() {
            return handOut().hashCode();
        }

        /** What names the hand-out: every component but {@code doubts}, {@code maxAttempts} and {@code cancel}. */
        private List<Object> handOut() {
            return Arrays.asList(seq, messageId, flow, step, partitionKey, attempts);
        }
    }

    /**
     * One held row.
     *
     * @param item the row as last handed out: its attempts are those of the claim that was held
     * @param heldAt the instant of the hold
     * @param blocks the number of rows of its key in its flow behind it, which wait while it is held; 0 for a row without
     *               a key
     * @since 0.1
     */
    record Held(Item item, Instant heldAt, long blocks) {

        /** A held row whose queue does not count what it blocks (the in-memory one): {@code blocks} 0. */
        public Held(Item item, Instant heldAt) {
            this(item, heldAt, 0);
        }
    }

    /**
     * One row that stops its key: its message was parked after it failed for good, and the row stays held, the oldest of its
     * key, until an operator retries or cancels the message.
     *
     * @param item the row as last handed out: its attempts are those of the run that failed
     * @param stoppedAt the instant of the stop
     * @param blocks the number of rows of its key in its flow behind it, which wait while it stops the key
     * @param error the parked failure's text; empty when the queue cannot read it (its parked row is gone)
     * @since 0.1
     */
    record Stopped(Item item, Instant stoppedAt, long blocks, Optional<String> error) {

        public Stopped {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(stoppedAt, "stoppedAt");
            Objects.requireNonNull(error, "error");
        }
    }

    /**
     * One lost row: its body was given up by an accepted {@code RAIL8_JOURNAL_ACCEPT_LOSS}, so it is never claimed, yet it
     * stays the oldest row of its key until an operator {@linkplain #discard discards} it.
     *
     * @param item the row as last handed out (attempts 0 when never)
     * @param lostAt the instant its loss was accepted
     * @param blocks the number of rows of its key in its flow behind it, which wait until it is discarded; 0 for a row without a key
     * @since 0.1
     */
    record Lost(Item item, Instant lostAt, long blocks) {}

    /**
     * What an operator's discard of a lost row did: the row is deleted and one audit row written, in one transaction.
     *
     * @param auditId the id of the audit row written
     * @param flow the discarded row's flow, to wake
     * @param nextNode the node whose journal holds the body of its key's next row, if it has a key and that row exists and names its
     *                 node: the node to notify, as a release does; never null
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
     * What a same-key fast path did: {@link #STALE} when the caller no longer held the row, so nothing was completed and
     * nothing taken; {@link #done done(next)} when the row was completed and {@code next} is the row of its key handed out
     * to the caller, if any; {@link #elsewhere elsewhere(node)} when the row was completed and the key's next row is
     * another node's to claim.
     *
     * @param completed the row was removed by this call
     * @param next the next row of the key now leased to the caller; never null, and empty when nothing was completed
     * @param nextNode when nothing was taken: the node whose journal holds the key's next row, if that row is not held and lies on
     *                 another node than the caller's queue claims for, so that node may claim it now; never null, and empty
     *                 otherwise
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
     * What an operator's release handed back to the claim: the row's flow and the node whose journal holds its body, each
     * empty when the queue cannot tell (a row of any node, or a queue that implements only {@link #release}).
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

    /** Appends a row; a null {@code partitionKey} means no ordering. */
    Future<Void> enqueue(String messageId, String flow, int step, String partitionKey);

    /**
     * Appends a row and hands it out at once to the local worker. When it heads its key (no other row of ({@code flow},
     * {@code partitionKey}) exists, held or not, leased or not; a row without a key always heads it), it is written leased to
     * {@code workerId} for {@code lease}, with attempts 1 and doubts 0, and answered as that hand-out; otherwise it is a
     * plain row, as {@link #enqueue} appends it, and the answer is empty. A row written leased belongs to its holder as a
     * claimed one does: renewed, completed or held by its attempts, and claimable again, in doubt, once its lease ran out.
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
     * Each row handed out counts one more attempt; the rows come in seq order. A worker id is its node's id: another
     * node's row is claimable only once that holder has no live membership row (released, or none at all), and a restarted
     * node's own rows only after its previous incarnation's lease end. A row handed out while an earlier holder left it unsettled
     * counts one more {@linkplain Item#doubts() doubt}.
     */
    Future<List<Item>> claim(String workerId, String flow, int max, Duration lease);

    /**
     * Removes a finished row for its current holder: {@code true} when this call removed it; {@code false} when the caller no
     * longer holds it (claimed again, held, completed or unknown), and the row is left as it was.
     */
    Future<Boolean> complete(long seq, int attempts);

    /**
     * {@link #complete} answering, once the delete committed, what it did as a {@link Completion}: {@link Completion#STALE} when the
     * caller no longer held {@code seq}; otherwise completed with nothing taken and, for a keyed row, the node of the key's
     * next row when that row is neither held nor lost and lies in another journal than the one this queue claims for: the
     * node that may claim it now.
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
     * statement: the answer comes once the delete committed. Each row is fenced by its own token, so a member the caller
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
     * next row of that key to the same worker in the same round-trip, so a busy partition never waits for a new claim round.
     * {@link Completion#STALE} when the caller no longer holds {@code seq}: nothing is completed and nothing taken.
     */
    Future<Completion> completeAndTakeNext(long seq, int attempts, String workerId, Duration lease);

    /**
     * Extends the lease of {@code seq} to {@code lease} from now, while the row exists, is not held and was last
     * handed out with {@code attempts}, at least 1; a lease that ran out and was not claimed again is revived. {@code true} when
     * extended; {@code false} when the caller no longer holds the row (claimed again, held, completed or unknown).
     */
    Future<Boolean> renew(long seq, int attempts, Duration lease);

    /**
     * {@link #renew}, answering with the row's {@link Cancel} when it is marked with one.
     *
     * <p>This default renews by {@link #renew} and tells no mark: a queue that keeps none has none to tell.
     *
     * @since 0.1
     */
    default Future<Renewal> renewRow(long seq, int attempts, Duration lease) {
        return renew(seq, attempts, lease).map(renewed -> renewed ? Renewal.RENEWED : Renewal.LOST);
    }

    /**
     * Hands {@code seq} back to the claim not before {@code delay} from now, for a scheduled retry, under the same condition
     * as {@link #renew}: its holder and its lease are cleared (the row is no one's, so its next hand-out counts one more
     * attempt and no doubt) and it is claimable only once {@code delay} passed on the queue's clock (Postgres: the database's
     * {@code now()}). Its seq, message id, attempts, doubts and body ref are kept, so it still heads its key and blocks the rows
     * behind it while it waits. {@code true} when deferred by this call; {@code false} when the caller no longer holds the row
     * (claimed again, held, completed or unknown): nothing changed.
     *
     * @since 0.1
     */
    Future<Boolean> defer(long seq, int attempts, Duration delay);

    /**
     * Marks {@code seq} held, under the same condition as {@link #renew}: it is never claimed again and blocks its key
     * until a person deals with it. {@code true} when held by this call.
     */
    Future<Boolean> hold(long seq, int attempts);

    /**
     * Parks the message of the keyed row {@code seq} and stops its key, for the current holder, in one step: writes
     * {@code failure}'s parked row — unless the message is parked already: the first park wins — and holds the row, marked stopped,
     * under the same condition as {@link #renew}; both or neither. A stopped row is never claimed, renewed, deferred or completed,
     * stays the oldest row of its key, so every later row of the key waits behind it, and is not {@linkplain #release released}:
     * only an operator's retry or cancel of its message moves the key on. {@code true} when stopped by this call; {@code false},
     * nothing written, when the caller no longer holds the row (claimed again, held, completed or unknown), when it has no key, or
     * when {@code failure} names another message or flow. A queue whose store keeps bodies (Postgres) writes nothing either when no
     * kept ref of {@code failure}'s body exists: the call fails with {@code BlobNotFoundException} naming its key
     * ({@code BodyLostException} when an accepted loss gave that ref up).
     *
     * @since 0.1
     */
    Future<Boolean> stop(long seq, int attempts, Parked failure);

    /**
     * The stopped rows of every flow a page at a time, in seq order, as {@link #held} pages the held ones: up to {@code limit}
     * rows after the cursor {@code after}, each with the rows of its key behind it and the parked failure's text, and a cursor to
     * the next page when another stopped row exists.
     *
     * @throws IllegalArgumentException {@code limit} is not 1..{@link Page#MAX_LIMIT}, or {@code after} is not a cursor this
     *                                  queue hands out — thrown at the call, not as a failed future
     * @since 0.1
     */
    Future<Page<Stopped>> stopped(Optional<String> after, int limit);

    /**
     * The held rows of every flow a page at a time, in seq order: up to {@code limit} rows after the cursor {@code after}, and
     * a cursor to the next page when another held row exists. A {@linkplain #stop stopped} row is not among them. The cursor is a position (a seq), never an offset: a row
     * released or completed meanwhile shifts nothing.
     *
     * @throws IllegalArgumentException {@code limit} is not 1..{@link Page#MAX_LIMIT}, or {@code after} is not a cursor this
     *                                  queue hands out — thrown at the call, not as a failed future
     */
    Future<Page<Held>> held(Optional<String> after, int limit);

    /**
     * The lost rows of every flow a page at a time, in seq order, as {@link #held} pages the held ones: up to {@code limit}
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
     * Deletes {@code seq} only while it is lost (a keyed row under its key's lock, as a keyed complete takes it) and in
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
     * leased, queued or {@linkplain #stop stopped} row is left as it was.
     */
    Future<Boolean> release(long seq);

    /**
     * {@link #release} answering what it released: empty when {@code seq} was not held or is stopped (nothing changed), else the row's flow and
     * body node as far as this queue knows them: what the release must wake, here and on the row's node.
     *
     * <p>This default releases by {@link #release} and cannot tell the row: a release answers {@link Released#UNTOLD}.
     *
     * @since 0.1
     */
    default Future<Optional<Released>> releaseRow(long seq) {
        return release(seq).map(released -> released ? Optional.of(Released.UNTOLD) : Optional.empty());
    }

    /**
     * {@code true} while any row of {@code flow} exists: due, backing off after a lease ran out, leased, held or stopped; a completed
     * row is gone and never counts, nor does another flow's row. A redeploy that removes an at-least-once flow checks it.
     *
     * @throws NullPointerException {@code flow} is null, thrown at the call, not as a failed future
     */
    Future<Boolean> hasItems(String flow);
}
