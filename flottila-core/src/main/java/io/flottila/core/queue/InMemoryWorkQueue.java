package io.flottila.core.queue;

import io.flottila.core.node.InMemoryMembership;
import io.flottila.core.record.Page;
import io.vertx.core.Future;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * In-memory {@link WorkQueue} with the exact eligibility rule of the Postgres claim (not held, due, oldest
 * row of its key, no live lease on the key; another node's row only once its holder has no live row in the
 * {@link InMemoryMembership}, a restarted node's own rows only after its old lease's end, D-101, D-112), the same attempts, doubts and
 * fencing (D-055, D-074), leases on an injected {@link Clock}. Single process only; state whose loss loses messages belongs
 * to Postgres (CLAUDE.md) — this is for tests.
 *
 * @since 0.1
 */
public final class InMemoryWorkQueue implements WorkQueue {

    private static final class Row {
        Item item;            // as last handed out: its attempts are the current holder's
        String lockedBy;      // null: never handed out, or released by an operator
        long lockedEpoch;     // the holder's epoch in the membership at the hand-out
        Instant lockedUntil;
        Instant heldAt;       // null: not held
        Instant availableAt;  // null: due; else not claimed before it (a defer, CORE-14a)
        Row(Item item) { this.item = item; }
    }

    private final Clock clock;
    private final InMemoryMembership members;
    private final TreeMap<Long, Row> rows = new TreeMap<>();
    private long nextSeq = 1;

    /** A queue whose holders are members of none: every holder counts as gone once its row's lease ran out. */
    public InMemoryWorkQueue(Clock clock) {
        this(clock, new InMemoryMembership(clock));
    }

    /** A queue whose holders' liveness is their row in {@code members} (a worker id is its node id). */
    public InMemoryWorkQueue(Clock clock, InMemoryMembership members) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.members = Objects.requireNonNull(members, "members");
    }

    @Override public synchronized Future<Void> enqueue(String messageId, String flow, int step, String partitionKey) {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(flow, "flow");
        long seq = nextSeq++;
        rows.put(seq, new Row(new Item(seq, messageId, flow, step, partitionKey, 0)));
        return Future.succeededFuture();
    }

    /** The row, leased to {@code workerId} under the membership's epoch as a claim leases it, when it heads its key. */
    @Override public synchronized Future<Optional<Item>> enqueueLeased(String messageId, String flow, int step, String partitionKey, String workerId,
                                                                      Duration lease) {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(flow, "flow");
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(lease, "lease");
        long seq = nextSeq++;
        Row row = new Row(new Item(seq, messageId, flow, step, partitionKey, 0));
        boolean heads = eligible(row);   // no row of its key is older: none exists, as it is the newest
        rows.put(seq, row);
        return Future.succeededFuture(heads ? Optional.of(take(row, workerId, clock.instant(), lease)) : Optional.empty());
    }

    @Override public synchronized Future<List<Item>> claim(String workerId, String flow, int max, Duration lease) {
        Instant now = clock.instant();
        List<Item> taken = new ArrayList<>();
        for (Row r : rows.values()) {
            if (taken.size() >= max) break;
            if (!r.item.flow().equals(flow) || r.heldAt != null || notDue(r, now) || live(r, now) || !eligible(r) || !released(r, workerId, now)) continue;
            taken.add(take(r, workerId, now, lease));
        }
        return Future.succeededFuture(taken);
    }

    @Override public synchronized Future<Boolean> complete(long seq, int attempts) {
        if (!current(rows.get(seq), attempts)) return Future.succeededFuture(false);
        rows.remove(seq);
        return Future.succeededFuture(true);
    }

    @Override public synchronized Future<Completion> completeAndTakeNext(long seq, int attempts, String workerId, Duration lease) {
        Row done = rows.get(seq);
        if (!current(done, attempts)) return Future.succeededFuture(Completion.STALE);
        rows.remove(seq);
        if (done.item.partitionKey() == null) return Future.succeededFuture(Completion.done(Optional.empty()));
        Instant now = clock.instant();
        for (Row r : rows.values()) {   // seq order: the first row of the key is its oldest, and only the oldest is ever leased
            if (!r.item.flow().equals(done.item.flow()) || !done.item.partitionKey().equals(r.item.partitionKey())) continue;
            return Future.succeededFuture(Completion.done(Optional.of(take(r, workerId, now, lease))));   // never handed out: the oldest only ever is
        }
        return Future.succeededFuture(Completion.done(Optional.empty()));
    }

    @Override public synchronized Future<Boolean> renew(long seq, int attempts, Duration lease) {
        Row r = rows.get(seq);
        if (!current(r, attempts)) return Future.succeededFuture(false);
        r.lockedUntil = clock.instant().plus(lease);
        return Future.succeededFuture(true);
    }

    /** Not before {@code delay} from now on the injected clock; the holder and the lease cleared, the item as it was. */
    @Override public synchronized Future<Boolean> defer(long seq, int attempts, Duration delay) {
        Objects.requireNonNull(delay, "delay");
        Row r = rows.get(seq);
        if (!current(r, attempts)) return Future.succeededFuture(false);
        r.availableAt = clock.instant().plus(delay);
        r.lockedBy = null;      // no one's: the next hand-out is in no doubt
        r.lockedUntil = null;
        return Future.succeededFuture(true);
    }

    /** Appends the row whatever was parked: this queue has no DLQ (the Postgres one deletes its row in the same transaction). */
    @Override public synchronized Future<Boolean> restart(String messageId, String flow, String partitionKey, int attempt) {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(flow, "flow");
        long seq = nextSeq++;
        rows.put(seq, new Row(new Item(seq, messageId, flow, 1, partitionKey, attempt, 0, attempt + 1)));
        return Future.succeededFuture(true);
    }

    @Override public synchronized Future<Boolean> hold(long seq, int attempts) {
        Row r = rows.get(seq);
        if (!current(r, attempts)) return Future.succeededFuture(false);
        r.heldAt = clock.instant();
        return Future.succeededFuture(true);
    }

    /** Seq order over every flow; the cursor is the last seq handed out, as the Postgres queue's. */
    @Override public synchronized Future<Page<Held>> held(Optional<String> after, int limit) {
        Page.requireLimit(limit);
        long from = after.map(InMemoryWorkQueue::seqOf).orElse(Long.MIN_VALUE);
        List<Held> page = new ArrayList<>();
        for (Row r : rows.tailMap(from, false).values()) {
            if (r.heldAt == null) continue;
            if (page.size() == limit) return Future.succeededFuture(new Page<>(page, Optional.of(cursorOf(page.getLast()))));
            page.add(new Held(r.item, r.heldAt));
        }
        return Future.succeededFuture(new Page<>(page, Optional.empty()));
    }

    @Override public synchronized Future<Boolean> release(long seq) {
        return releaseRow(seq).map(Optional::isPresent);
    }

    /** The released row's flow; no body node: this queue holds no bodies. */
    @Override public synchronized Future<Optional<Released>> releaseRow(long seq) {
        Row r = rows.get(seq);
        if (r == null || r.heldAt == null) return Future.succeededFuture(Optional.empty());
        r.heldAt = null;
        r.lockedBy = null;      // the holder cleared with the lease: the next hand-out is in no doubt
        r.lockedUntil = null;
        return Future.succeededFuture(Optional.of(new Released(Optional.of(r.item.flow()), Optional.empty())));
    }

    @Override public synchronized Future<Boolean> hasItems(String flow) {
        Objects.requireNonNull(flow, "flow");
        return Future.succeededFuture(rows.values().stream().anyMatch(r -> r.item.flow().equals(flow)));
    }

    /** Rows currently queued (leased, held or neither). */
    public synchronized int size() { return rows.size(); }

    /** The worker holding a live lease on {@code seq}, if any. */
    public synchronized Optional<String> leaseHolder(long seq) {
        Row r = rows.get(seq);
        return r != null && live(r, clock.instant()) ? Optional.of(r.lockedBy) : Optional.empty();
    }

    /** All rows in seq order (tests). */
    public synchronized List<Item> snapshot() {
        return rows.values().stream().map(r -> r.item).toList();
    }

    /** Deferred past {@code now}. */
    private static boolean notDue(Row r, Instant now) {
        return r.availableAt != null && r.availableAt.isAfter(now);
    }

    private static boolean live(Row r, Instant now) {
        return r.lockedUntil != null && r.lockedUntil.isAfter(now);
    }

    /**
     * Oldest row of its key (null key: always). Only the oldest row of a key is ever leased, so a key
     * with a live lease has its oldest row live and {@link #claim} already skips it: the SQL's second
     * condition ("no live lease on the key") is implied here.
     */
    private boolean eligible(Row candidate) {
        String key = candidate.item.partitionKey();
        if (key == null) return true;
        for (Row other : rows.values()) {
            if (other == candidate || !other.item.flow().equals(candidate.item.flow()) || !key.equals(other.item.partitionKey())) continue;
            if (other.item.seq() < candidate.item.seq()) return false;
        }
        return true;
    }

    /**
     * The caller's claim is the row's current one (Q-1, D-074): the row exists, is not held and was last handed out with
     * {@code attempts}, at least 1 — a row never handed out matches no token.
     */
    private static boolean current(Row r, int attempts) {
        return r != null && r.heldAt == null && attempts > 0 && r.item.attempts() == attempts;
    }

    /** The cursor after {@code last}: its seq. */
    private static String cursorOf(Held last) {
        return Page.cursor(Long.toString(last.item().seq()));
    }

    /** The seq inside a cursor; {@link IllegalArgumentException} (a {@link NumberFormatException} included) when it holds none. */
    private static long seqOf(String cursor) {
        return Long.parseLong(Page.position(cursor));
    }

    /**
     * The holder of a row whose lease ran out lets it go to {@code workerId} (D-101): no holder, the claimer itself, or a node
     * with no live membership row; and a restarted holder's row only once its previous incarnation's lease ended.
     */
    private boolean released(Row r, String workerId, Instant now) {
        if (r.lockedBy == null) return true;
        if (!r.lockedBy.equals(workerId) && members.live(r.lockedBy)) return false;
        return !members.waits(r.lockedBy, r.lockedEpoch, now);
    }

    /** Leases {@code r} to {@code workerId} and counts the attempt, and a doubt when an earlier holder left it unsettled; the item as handed out. */
    private Item take(Row r, String workerId, Instant now, Duration lease) {
        Item i = r.item;
        int doubt = r.lockedBy == null ? 0 : 1;
        r.lockedBy = Objects.requireNonNull(workerId, "workerId");
        r.lockedEpoch = members.epoch(workerId);
        r.lockedUntil = now.plus(lease);
        r.item = new Item(i.seq(), i.messageId(), i.flow(), i.step(), i.partitionKey(), i.attempts() + 1, i.doubts() + doubt, i.maxAttempts());
        return r.item;
    }
}
