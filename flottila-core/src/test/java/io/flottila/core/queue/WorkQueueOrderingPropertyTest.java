package io.flottila.core.queue;

import io.flottila.testkit.FakeClock;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CLAUDE.md: property test for WorkQueue ordering — per-key order holds under any claim
 * interleaving. Workers claim, complete (plainly or through the same-key fast path), renew and hold
 * what they were handed, and leases expire, in any order; whenever a row is handed out it must be
 * the oldest remaining row of its key, its key must hold no live lease, it must not be held, and its
 * attempts count every hand-out of the row, this one included; a renew or a hold takes effect exactly
 * when the row exists, is not held and the attempts are the row's (D-055); null keys carry no
 * ordering; at the end every row has been handed out at least once (at-least-once) and the queue
 * holds only the held rows and the rows of their keys behind them.
 */
class WorkQueueOrderingPropertyTest {

    static final Duration LEASE = Duration.ofSeconds(10);
    static final List<String> KEYS = java.util.Arrays.asList("A", "B", "C", null);
    static final List<String> WORKERS = List.of("w1", "w2", "w3");

    sealed interface Action permits Claim, Complete, FastPath, Expire, Renew, Hold {}
    record Claim(int worker, int max) implements Action {}
    record Complete(int worker) implements Action {}
    record FastPath(int worker) implements Action {}
    record Expire() implements Action {}
    /** The worker renews the last item it was handed, whatever became of it since. */
    record Renew(int worker) implements Action {}
    /** The worker gives up the last item it was handed by holding it (D-055: past the bound and unparkable). */
    record Hold(int worker) implements Action {}

    @Provide
    Arbitrary<List<Action>> actions() {
        Arbitrary<Action> claim = Combinators.combine(Arbitraries.integers().between(0, 2), Arbitraries.integers().between(1, 3)).as(Claim::new);
        Arbitrary<Action> complete = Arbitraries.integers().between(0, 2).map(Complete::new);
        Arbitrary<Action> fast = Arbitraries.integers().between(0, 2).map(FastPath::new);
        Arbitrary<Action> expire = Arbitraries.just(new Expire());
        Arbitrary<Action> renew = Arbitraries.integers().between(0, 2).map(Renew::new);
        Arbitrary<Action> hold = Arbitraries.integers().between(0, 2).map(Hold::new);
        return Arbitraries.frequencyOf(net.jqwik.api.Tuple.of(4, claim), net.jqwik.api.Tuple.of(3, complete),
                net.jqwik.api.Tuple.of(3, fast), net.jqwik.api.Tuple.of(1, expire), net.jqwik.api.Tuple.of(2, renew),
                net.jqwik.api.Tuple.of(1, hold)).list().ofMinSize(1).ofMaxSize(80);
    }

    @Provide
    Arbitrary<List<Integer>> keyIndexes() {
        return Arbitraries.integers().between(0, 3).list().ofMinSize(1).ofMaxSize(15);
    }

    @Property(tries = 300)
    void perKeyOrderHoldsUnderAnyClaimInterleaving(@ForAll("keyIndexes") List<Integer> keyIndexes, @ForAll("actions") List<Action> actions) {
        FakeClock clock = FakeClock.at(Instant.EPOCH);
        InMemoryWorkQueue q = new InMemoryWorkQueue(clock);
        Map<Long, String> keyOf = new HashMap<>();
        List<Long> seqs = new ArrayList<>();
        for (int i = 0; i < keyIndexes.size(); i++) {
            String key = KEYS.get(keyIndexes.get(i));
            q.enqueue("m" + i, "f", 1, key).await();
        }
        for (WorkQueue.Item it : q.snapshot()) { keyOf.put(it.seq(), it.partitionKey()); seqs.add(it.seq()); }
        Set<Long> remaining = new HashSet<>(seqs);                 // enqueued, not completed
        Map<Integer, Deque<WorkQueue.Item>> held = new HashMap<>();  // worker → items it holds
        Map<Long, Instant> leaseUntil = new HashMap<>();
        Set<Long> handedOut = new HashSet<>();
        Map<Long, Integer> attemptsOf = new HashMap<>();             // hand-outs per row: what its attempts must say
        Set<Long> heldRows = new HashSet<>();

        for (Action a : actions) {
            Instant now = clock.instant();
            switch (a) {
                case Claim c -> {
                    List<WorkQueue.Item> got = q.claim(WORKERS.get(c.worker()), "f", c.max(), LEASE).await();
                    assertTrue(got.size() <= c.max());
                    for (WorkQueue.Item it : got) {
                        check(it, remaining, keyOf, leaseUntil, now);
                        counted(it, attemptsOf, heldRows);
                        leaseUntil.put(it.seq(), now.plus(LEASE));
                        held.computeIfAbsent(c.worker(), k -> new ArrayDeque<>()).add(it);
                        handedOut.add(it.seq());
                    }
                }
                case Complete c -> {
                    WorkQueue.Item it = pollLive(held, c.worker(), leaseUntil, now);
                    if (it != null) { q.complete(it.seq()).await(); remaining.remove(it.seq()); leaseUntil.remove(it.seq()); heldRows.remove(it.seq()); }
                }
                case FastPath f -> {
                    WorkQueue.Item it = pollLive(held, f.worker(), leaseUntil, now);
                    if (it != null) {
                        Optional<WorkQueue.Item> next = q.completeAndTakeNext(it.seq(), WORKERS.get(f.worker()), LEASE).await();
                        remaining.remove(it.seq()); leaseUntil.remove(it.seq()); heldRows.remove(it.seq());
                        if (next.isPresent()) {
                            assertEquals(it.partitionKey(), next.get().partitionKey(), "the fast path stays on the same key");
                            assertTrue(it.partitionKey() != null, "no fast path without a key");
                            check(next.get(), remaining, keyOf, leaseUntil, now);
                            counted(next.get(), attemptsOf, heldRows);
                            leaseUntil.put(next.get().seq(), now.plus(LEASE));
                            held.get(f.worker()).add(next.get());
                            handedOut.add(next.get().seq());
                        }
                    }
                }
                case Expire e -> clock.advance(LEASE.plusMillis(1));
                case Renew r -> {
                    Deque<WorkQueue.Item> d = held.get(r.worker());
                    WorkQueue.Item it = d == null ? null : d.peekLast();
                    if (it != null) {
                        boolean expected = remaining.contains(it.seq()) && !heldRows.contains(it.seq()) && attemptsOf.get(it.seq()) == it.attempts();
                        assertEquals(expected, q.renew(it.seq(), it.attempts(), LEASE).await(),
                                "renew of " + it + " (row's attempts " + attemptsOf.get(it.seq()) + ", held " + heldRows.contains(it.seq()) + ")");
                        if (expected) leaseUntil.put(it.seq(), now.plus(LEASE));   // extended, or revived when it had run out
                    }
                }
                case Hold h -> {
                    Deque<WorkQueue.Item> d = held.get(h.worker());
                    WorkQueue.Item it = d == null ? null : d.pollLast();
                    if (it != null) {
                        boolean expected = remaining.contains(it.seq()) && !heldRows.contains(it.seq()) && attemptsOf.get(it.seq()) == it.attempts();
                        assertEquals(expected, q.hold(it.seq(), it.attempts()).await(),
                                "hold of " + it + " (row's attempts " + attemptsOf.get(it.seq()) + ", held " + heldRows.contains(it.seq()) + ")");
                        if (expected) heldRows.add(it.seq());
                    }
                }
            }
        }
        // drain: expire everything, one worker claims and completes until only the held rows and the rows behind them are left
        Set<Long> stuck = new HashSet<>(heldRows);
        for (long s : remaining) {
            String key = keyOf.get(s);
            if (key != null && heldRows.stream().anyMatch(h -> h < s && key.equals(keyOf.get(h)))) stuck.add(s);
        }
        clock.advance(LEASE.plusMillis(1));
        for (int guard = 0; guard < 1_000 && q.size() > stuck.size(); guard++) {
            Instant now = clock.instant();
            for (WorkQueue.Item it : q.claim("drain", "f", 5, LEASE).await()) {
                check(it, remaining, keyOf, leaseUntil, now);
                counted(it, attemptsOf, heldRows);
                handedOut.add(it.seq());
                q.complete(it.seq()).await();
                remaining.remove(it.seq());
            }
        }
        assertEquals(stuck, q.snapshot().stream().map(WorkQueue.Item::seq).collect(java.util.stream.Collectors.toSet()),
                "every row was completed but the held ones and those behind them on their key");
        Set<Long> reachable = new HashSet<>(seqs);
        reachable.removeIf(s -> stuck.contains(s) && !heldRows.contains(s));
        assertEquals(reachable, handedOut, "every row not blocked behind a held one was handed out at least once");
    }

    /** A hand-out: never of a held row, and its attempts count every hand-out of the row, this one included. */
    private static void counted(WorkQueue.Item it, Map<Long, Integer> attemptsOf, Set<Long> heldRows) {
        assertTrue(!heldRows.contains(it.seq()), "a held row was handed out: " + it);
        int n = attemptsOf.merge(it.seq(), 1, Integer::sum);
        assertEquals(n, it.attempts(), "attempts of " + it + " after " + n + " hand-outs");
    }

    /** Oldest remaining row of its key, and no live lease on the key at this moment. */
    private static void check(WorkQueue.Item it, Set<Long> remaining, Map<Long, String> keyOf, Map<Long, Instant> leaseUntil, Instant now) {
        assertTrue(remaining.contains(it.seq()), "a completed row must never be handed out again: " + it);
        String key = it.partitionKey();
        if (key == null) return;
        for (long s : remaining) {
            if (key.equals(keyOf.get(s))) {
                assertTrue(s >= it.seq(), "row " + it.seq() + " of key " + key + " handed out before older row " + s);
                Instant until = leaseUntil.get(s);
                assertTrue(s == it.seq() || until == null || !until.isAfter(now), "key " + key + " already has a live lease on row " + s);
            }
        }
    }

    private static WorkQueue.Item pollLive(Map<Integer, Deque<WorkQueue.Item>> held, int worker, Map<Long, Instant> leaseUntil, Instant now) {
        Deque<WorkQueue.Item> d = held.get(worker);
        while (d != null && !d.isEmpty()) {
            WorkQueue.Item it = d.poll();
            Instant until = leaseUntil.get(it.seq());
            if (until != null && until.isAfter(now)) return it;   // a worker only completes what it still holds
        }
        return null;
    }
}
