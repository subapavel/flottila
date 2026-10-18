package io.flottila.core.queue;

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
 * In-memory {@link WorkQueue} with the exact eligibility rule of the Postgres claim (oldest row of
 * its key, no live lease on the key), leases on an injected {@link Clock}. Single process only;
 * state whose loss loses messages belongs to Postgres (CLAUDE.md) — this is for tests.
 *
 * @since 0.1
 */
public final class InMemoryWorkQueue implements WorkQueue {

    private static final class Row {
        final Item item;
        String lockedBy;
        Instant lockedUntil;
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
        rows.put(seq, new Row(new Item(seq, messageId, flow, step, partitionKey)));
        return Future.succeededFuture();
    }

    @Override public synchronized Future<List<Item>> claim(String workerId, String flow, int max, Duration lease) {
        Instant now = clock.instant();
        List<Item> taken = new ArrayList<>();
        for (Row r : rows.values()) {
            if (taken.size() >= max) break;
            if (!r.item.flow().equals(flow) || live(r, now) || !eligible(r)) continue;
            take(r, workerId, now, lease);
            taken.add(r.item);
        }
        return Future.succeededFuture(taken);
    }

    @Override public synchronized Future<Void> complete(long seq) {
        rows.remove(seq);
        return Future.succeededFuture();
    }

    @Override public synchronized Future<Optional<Item>> completeAndTakeNext(long seq, String workerId, Duration lease) {
        Row done = rows.remove(seq);
        if (done == null || done.item.partitionKey() == null) return Future.succeededFuture(Optional.empty());
        Instant now = clock.instant();
        for (Row r : rows.values()) {   // seq order: the first row of the key is its oldest, and only the oldest is ever leased
            if (!r.item.flow().equals(done.item.flow()) || !done.item.partitionKey().equals(r.item.partitionKey())) continue;
            take(r, workerId, now, lease);
            return Future.succeededFuture(Optional.of(r.item));
        }
        return Future.succeededFuture(Optional.empty());
    }

    /** Rows currently queued (leased or not). */
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
     * condition ("no live lease on the key") is implied here. The one divergence from the SQL is on
     * misuse: a fast path on a seq that was never handed out would lease the key's oldest row even
     * while it is live; the contract (complete only what you were handed; Q-1) excludes that.
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

    private static void take(Row r, String workerId, Instant now, Duration lease) {
        r.lockedBy = Objects.requireNonNull(workerId, "workerId");
        r.lockedUntil = now.plus(lease);
    }
}
