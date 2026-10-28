package io.flottila.core.queue;

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
 * In-memory {@link WorkQueue} with the exact eligibility rule of the Postgres claim (not held, oldest
 * row of its key, no live lease on the key), the same attempts and fencing (D-055, D-074), leases on an
 * injected {@link Clock}. Single process only; state whose loss loses messages belongs to Postgres
 * (CLAUDE.md) — this is for tests.
 *
 * @since 0.1
 */
public final class InMemoryWorkQueue implements WorkQueue {

    private static final class Row {
        Item item;            // as last handed out: its attempts are the current holder's
        String lockedBy;
        Instant lockedUntil;
        Instant heldAt;       // null: not held
        Row(Item item) { this.item = item; }
    }

    private final Clock clock;
    private final TreeMap<Long, Row> rows = new TreeMap<>();
    private long nextSeq = 1;

    public InMemoryWorkQueue(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public synchronized Future<Void> enqueue(String messageId, String flow, int step, String partitionKey) {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(flow, "flow");
        long seq = nextSeq++;
        rows.put(seq, new Row(new Item(seq, messageId, flow, step, partitionKey, 0)));
        return Future.succeededFuture();
    }

    @Override public synchronized Future<List<Item>> claim(String workerId, String flow, int max, Duration lease) {
        Instant now = clock.instant();
        List<Item> taken = new ArrayList<>();
        for (Row r : rows.values()) {
            if (taken.size() >= max) break;
            if (!r.item.flow().equals(flow) || r.heldAt != null || live(r, now) || !eligible(r)) continue;
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
            return Future.succeededFuture(Completion.done(Optional.of(take(r, workerId, now, lease))));
        }
        return Future.succeededFuture(Completion.done(Optional.empty()));
    }

    @Override public synchronized Future<Boolean> renew(long seq, int attempts, Duration lease) {
        Row r = rows.get(seq);
        if (!current(r, attempts)) return Future.succeededFuture(false);
        r.lockedUntil = clock.instant().plus(lease);
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
        Row r = rows.get(seq);
        if (r == null || r.heldAt == null) return Future.succeededFuture(false);
        r.heldAt = null;
        r.lockedUntil = null;   // the lease cleared (lockedBy is read only under a live lease, and the next take sets it)
        return Future.succeededFuture(true);
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

    /** Leases {@code r} to {@code workerId} and counts the attempt; the item as handed out. */
    private static Item take(Row r, String workerId, Instant now, Duration lease) {
        r.lockedBy = Objects.requireNonNull(workerId, "workerId");
        r.lockedUntil = now.plus(lease);
        Item i = r.item;
        r.item = new Item(i.seq(), i.messageId(), i.flow(), i.step(), i.partitionKey(), i.attempts() + 1);
        return r.item;
    }
}
