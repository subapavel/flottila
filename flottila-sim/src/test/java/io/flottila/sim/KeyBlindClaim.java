package io.flottila.sim;

import io.flottila.core.queue.WorkQueue;
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
 * Broken variant: a claim hands out the due rows in seq order without regard to their key — a row whose key is still leased
 * (its message parked on a Retry backoff after a refused call) is claimed too, so a second message of the key reaches the
 * partner during the first one's retries. Claims stay in seq order, so first receipts keep the order of the sends; everything
 * else is the in-memory queue's (fast path: the oldest due row of the key). A {@link WorkQueue} of its own, built over the
 * public interface only: the in-memory queue's claim always keeps a key serial.
 */
final class KeyBlindClaim implements World {

    static final String NAME = "key-blind-claim";

    @Override public String name() { return NAME; }

    @Override public WorkQueue queue(WorkQueue sim, Clock clock) {
        return new KeyBlindQueue(clock);
    }

    static final class KeyBlindQueue implements WorkQueue {

        private static final class Row {
            Item item;
            Instant lockedUntil;
            Instant heldAt;
            Row(Item item) { this.item = item; }
        }

        private final Clock clock;
        private final TreeMap<Long, Row> rows = new TreeMap<>();
        private long nextSeq = 1;

        KeyBlindQueue(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
        }

        @Override public synchronized Future<Void> enqueue(String messageId, String flow, int step, String partitionKey) {
            long seq = nextSeq++;
            rows.put(seq, new Row(new Item(seq, messageId, flow, step, partitionKey, 0)));
            return Future.succeededFuture();
        }

        @Override public synchronized Future<List<Item>> claim(String workerId, String flow, int max, Duration lease) {
            Instant now = clock.instant();
            List<Item> taken = new ArrayList<>();
            for (Row r : rows.values()) {
                if (taken.size() >= max) break;
                if (r.item.flow().equals(flow) && r.heldAt == null && !live(r, now)) taken.add(take(r, now, lease));   // THE DEFECT: no key rule
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
            for (Row r : rows.values()) {
                if (!r.item.flow().equals(done.item.flow()) || !done.item.partitionKey().equals(r.item.partitionKey())) continue;
                if (r.heldAt != null || live(r, now)) continue;
                return Future.succeededFuture(Completion.done(Optional.of(take(r, now, lease))));
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

        @Override public synchronized Future<Page<Held>> held(Optional<String> after, int limit) {
            Page.requireLimit(limit);
            long from = after.map(c -> Long.parseLong(Page.position(c))).orElse(Long.MIN_VALUE);
            List<Held> page = new ArrayList<>();
            for (Row r : rows.tailMap(from, false).values()) {
                if (r.heldAt == null) continue;
                if (page.size() == limit) {
                    return Future.succeededFuture(new Page<>(page, Optional.of(Page.cursor(Long.toString(page.getLast().item().seq())))));
                }
                page.add(new Held(r.item, r.heldAt));
            }
            return Future.succeededFuture(new Page<>(page, Optional.empty()));
        }

        @Override public synchronized Future<Boolean> release(long seq) {
            Row r = rows.get(seq);
            if (r == null || r.heldAt == null) return Future.succeededFuture(false);
            r.heldAt = null;
            r.lockedUntil = null;
            return Future.succeededFuture(true);
        }

        @Override public synchronized Future<Boolean> hasItems(String flow) {
            return Future.succeededFuture(rows.values().stream().anyMatch(r -> r.item.flow().equals(flow)));
        }

        private static boolean live(Row r, Instant now) {
            return r.lockedUntil != null && r.lockedUntil.isAfter(now);
        }

        private static boolean current(Row r, int attempts) {
            return r != null && r.heldAt == null && attempts > 0 && r.item.attempts() == attempts;
        }

        private static Item take(Row r, Instant now, Duration lease) {
            r.lockedUntil = now.plus(lease);
            Item i = r.item;
            r.item = new Item(i.seq(), i.messageId(), i.flow(), i.step(), i.partitionKey(), i.attempts() + 1);
            return r.item;
        }
    }
}
