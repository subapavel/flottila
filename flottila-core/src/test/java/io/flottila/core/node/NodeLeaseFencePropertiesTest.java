package io.flottila.core.node;

import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.queue.WorkQueue;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.statistics.Statistics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-15a acceptance 2 and 3 (D-101 (1), (2)) as a property: node A holds work items, node B is a healthy peer that claims
 * whatever the queue hands it; A's link to the membership renews fine, loses the request, loses the answer, delays the
 * request or delays the answer, A pauses, time advances (up to 20 s, or a pause of A's up to 45 s, in one-second steps), A sends and completes
 * when its fence is open. Over every interleaving: A's fence is never open while B holds an item of A's; A never sends at or
 * after {@code fenceUntil} — computed here from the spec's formula over the renews whose renewed answer reached A ({@code s +
 * L/2}, {@code s} the instant the renew was sent, the join counted); a superseded A is never open; a takeover comes at least
 * L/2 after A's last possible send start; A's complete with its fence open always removes the row; and once the links heal,
 * every item ends completed (the model holds nothing).
 *
 * <p>The API assumed: as {@link NodeLeaseTest}; {@code InMemoryWorkQueue(Clock, InMemoryMembership)} — a claim takes another
 * node's item only when that node has no live row in the membership (P2-15a acceptance 3). jqwik's statistics require a
 * share of the tries to reach a takeover and a send, so the property is never vacuously green.
 */
class NodeLeaseFencePropertiesTest {

    static final Instant T0 = Instant.parse("2026-11-05T08:00:00Z");
    static final Duration L = Duration.ofSeconds(30);
    static final Duration R = Duration.ofSeconds(5);
    static final Duration ITEM_LEASE = Duration.ofSeconds(10);
    static final String FLOW = "f";

    enum Kind { ADVANCE, PAUSE, MODE, DELIVER, SEND, COMPLETE, B_CLAIM }

    /** How A's link to the membership treats its renews from now on. */
    enum Link { OK, LOST_REQUEST, LOST_ANSWER, DELAY_REQUEST, DELAY_ANSWER }

    record Act(Kind kind, long millis, int arg) {
        @Override public String toString() {
            return switch (kind) {
                case ADVANCE, PAUSE -> kind + "(" + millis + " ms)";
                case MODE -> "MODE(" + Link.values()[arg] + ")";
                case SEND, COMPLETE -> kind + "(" + arg + ")";
                default -> kind.toString();
            };
        }
    }

    @Provide
    Arbitrary<List<Act>> acts() {
        Arbitrary<Act> advance = Arbitraries.longs().between(1, 20_000).map(ms -> new Act(Kind.ADVANCE, ms, 0));
        Arbitrary<Act> pause = Arbitraries.longs().between(1_000, 45_000).map(ms -> new Act(Kind.PAUSE, ms, 0));
        Arbitrary<Act> mode = Arbitraries.integers().between(0, Link.values().length - 1).map(i -> new Act(Kind.MODE, 0, i));
        Arbitrary<Act> deliver = Arbitraries.just(new Act(Kind.DELIVER, 0, 0));
        Arbitrary<Act> send = Arbitraries.integers().between(0, 3).map(i -> new Act(Kind.SEND, 0, i));
        Arbitrary<Act> complete = Arbitraries.integers().between(0, 3).map(i -> new Act(Kind.COMPLETE, 0, i));
        Arbitrary<Act> claim = Arbitraries.just(new Act(Kind.B_CLAIM, 0, 0));
        return Arbitraries.frequencyOf(Tuple.of(6, advance), Tuple.of(1, pause), Tuple.of(3, mode), Tuple.of(1, deliver), Tuple.of(4, send),
                Tuple.of(1, complete), Tuple.of(3, claim)).list().ofMinSize(1).ofMaxSize(60);
    }

    @Property(tries = 400)
    void aNodesFenceIsNeverOpenWhileAnotherNodeHoldsItsWork(@ForAll("acts") List<Act> acts, @ForAll @IntRange(min = 1, max = 4) int items) {
        World w = new World(items);
        try {
            for (Act act : acts) {
                w.run(act);
                w.check(act);
            }
            w.drain();
            Statistics.label("takeover").collect(!w.tookFromA.isEmpty());
            Statistics.label("A sent").collect(w.sends > 0);
            Statistics.label("takeover").coverage(c -> c.check(true).percentage(p -> p >= 3.0));
            Statistics.label("A sent").coverage(c -> c.check(true).percentage(p -> p >= 10.0));
        } finally {
            w.a.close();
            w.b.close();
        }
    }

    /** One try: the clock, the timer, the membership, the queue and both nodes. */
    static final class World {
        final FakeClock clock = FakeClock.at(T0);
        final FakeTimer timer = new FakeTimer(clock);
        final LongSupplier nanos = () -> Duration.between(T0, clock.instant()).toNanos();
        final InMemoryMembership members = new InMemoryMembership(clock);
        final InMemoryWorkQueue queue = new InMemoryWorkQueue(clock, members);
        final LinkOfA link = new LinkOfA(members, clock);
        boolean haltedA;
        boolean haltedB;
        final NodeLease a = new NodeLease("a", link, L, R, nanos, timer, () -> haltedA = true);
        final NodeLease b = new NodeLease("b", members, L, R, nanos, timer, () -> haltedB = true);
        /** A's items still A's to settle: seq → the attempts A claimed it with. */
        final Map<Long, Integer> heldByA = new LinkedHashMap<>();
        final List<Long> itemsOfA = new ArrayList<>();
        /** B's items: seq → the attempts of B's newest claim of it. */
        final Map<Long, Integer> heldByB = new HashMap<>();
        final Set<Long> tookFromA = new HashSet<>();
        final List<Instant> takeovers = new ArrayList<>();
        int sends;

        World(int items) {
            a.start().await();
            b.start().await();
            for (int i = 0; i < items; i++) queue.enqueue("m-" + i, FLOW, 1, null).await();
            for (WorkQueue.Item item : queue.claim("a", FLOW, items, ITEM_LEASE).await()) {
                heldByA.put(item.seq(), item.attempts());
                itemsOfA.add(item.seq());
            }
            assertEquals(items, heldByA.size(), "fixture: A holds every item");
        }

        boolean aActs() { return !haltedA && a.getAsBoolean(); }

        /** Time passes in steps of at most a second: B, a healthy process, renews on time; A's link decides what its renews do. */
        void advance(long millis) {
            for (long left = millis; left > 0; left -= 1_000) clock.advance(Duration.ofMillis(Math.min(left, 1_000)));
        }

        void run(Act act) {
            switch (act.kind()) {
                case ADVANCE -> advance(act.millis());
                case PAUSE -> {   // A's process is frozen: its renews reach nobody until it thaws, then go out late
                    Link before = link.mode;
                    link.mode = Link.DELAY_REQUEST;
                    advance(act.millis());
                    link.mode = before;
                    link.deliver();
                }
                case MODE -> link.mode = Link.values()[act.arg()];
                case DELIVER -> link.deliver();
                case SEND -> {
                    Long seq = nth(act.arg());
                    if (seq != null && aActs()) {
                        assertTrue(clock.instant().isBefore(link.fenceUntil),
                                "A sends at " + clock.instant() + ", at or after its fenceUntil " + link.fenceUntil);
                        sends++;
                    }
                }
                case COMPLETE -> {
                    Long seq = nth(act.arg());
                    if (seq != null && aActs()) {
                        assertTrue(queue.complete(seq, heldByA.get(seq)).await(),
                                "A's fence is open at " + clock.instant() + ": its item " + seq + " is still A's and its complete removes it");
                        heldByA.remove(seq);
                    }
                }
                case B_CLAIM -> claimByB();
            }
        }

        /** The {@code n}-th item A still holds (modulo their count), or null. */
        Long nth(int n) {
            if (heldByA.isEmpty()) return null;
            return new ArrayList<>(heldByA.keySet()).get(n % heldByA.size());
        }

        void claimByB() {
            for (WorkQueue.Item item : queue.claim("b", FLOW, 10, ITEM_LEASE).await()) {
                heldByB.put(item.seq(), item.attempts());
                if (itemsOfA.contains(item.seq()) && tookFromA.add(item.seq())) {
                    takeovers.add(clock.instant());
                    heldByA.remove(item.seq());   // A's token is stale from now on
                    assertFalse(a.getAsBoolean(), "B took A's item " + item.seq() + " at " + clock.instant() + " while A's fence is open");
                    assertFalse(clock.instant().isBefore(link.fenceUntil.plus(L.dividedBy(2))),
                            "B took A's item " + item.seq() + " at " + clock.instant() + ", less than L/2 after A's fenceUntil " + link.fenceUntil);
                }
            }
        }

        void check(Object after) {
            if (a.getAsBoolean()) {
                assertFalse(link.superseded, "after " + after + ": A's fence is open though A was superseded");
                assertTrue(clock.instant().isBefore(link.fenceUntil),
                        "after " + after + ": A's fence is open at " + clock.instant() + ", at or after fenceUntil " + link.fenceUntil);
                assertTrue(tookFromA.isEmpty(), "after " + after + ": A's fence is open while B holds A's items " + tookFromA);
            }
        }

        /** The links heal; A settles what it can, B takes and completes the rest; every item ends completed. */
        void drain() {
            link.mode = Link.OK;
            link.deliver();
            for (int round = 0; round < 120 && queue.size() > 0; round++) {
                clock.advance(R);
                check("drain round " + round);
                for (Long seq : new ArrayList<>(heldByA.keySet())) {
                    if (!aActs()) break;
                    assertTrue(queue.complete(seq, heldByA.get(seq)).await(), "drain: A's fence is open, its item " + seq + " is still A's");
                    heldByA.remove(seq);
                }
                claimByB();
                for (Map.Entry<Long, Integer> mine : new ArrayList<>(heldByB.entrySet())) {
                    assertTrue(queue.complete(mine.getKey(), mine.getValue()).await(), "drain: B completes its item " + mine.getKey());
                    heldByB.remove(mine.getKey());
                }
            }
            assertEquals(0, queue.size(), "every item ends completed: " + queue.snapshot() + " (A halted " + haltedA + ", A open " + a.getAsBoolean() + ")");
            for (Instant t : takeovers) {
                assertFalse(t.isBefore(link.fenceUntil.plus(L.dividedBy(2))),
                        "a takeover at " + t + " came less than L/2 after A's final fenceUntil " + link.fenceUntil);
            }
            assertFalse(haltedB, "B, the healthy peer, is never halted");
        }
    }

    /**
     * A's link to the membership, scripted by {@link Link}, and the spec's model of A's fence over what reached A:
     * {@code fenceUntil = max(s + L/2)} over the joins and renews answered renewed, {@code superseded} once one answered not.
     */
    static final class LinkOfA implements Membership {
        final Membership delegate;
        final Clock clock;
        final List<Runnable> delayed = new ArrayList<>();
        Link mode = Link.OK;
        Instant fenceUntil = Instant.MIN;
        boolean superseded;

        LinkOfA(Membership delegate, Clock clock) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.clock = clock;
        }

        void deliver() {
            List<Runnable> now = new ArrayList<>(delayed);
            delayed.clear();
            now.forEach(Runnable::run);
        }

        private Renewal seen(Instant sent, Renewal answer) {
            if (answer.renewed()) {
                Instant until = sent.plus(L.dividedBy(2));
                if (until.isAfter(fenceUntil)) fenceUntil = until;
            } else {
                superseded = true;
            }
            return answer;
        }

        @Override public Future<Long> join(String node, Duration lease) {
            Instant sent = clock.instant();
            return delegate.join(node, lease).onSuccess(epoch -> { if (sent.plus(L.dividedBy(2)).isAfter(fenceUntil)) fenceUntil = sent.plus(L.dividedBy(2)); });
        }

        @Override public Future<Renewal> renew(String node, long epoch, Duration lease) {
            Instant sent = clock.instant();
            return switch (mode) {
                case OK -> delegate.renew(node, epoch, lease).map(r -> seen(sent, r));
                case LOST_REQUEST -> Future.failedFuture(new IllegalStateException("the renew never reached the database"));
                case LOST_ANSWER -> {
                    delegate.renew(node, epoch, lease);
                    yield Future.failedFuture(new IllegalStateException("the renew's answer was lost"));
                }
                case DELAY_REQUEST -> {
                    Promise<Renewal> late = Promise.promise();
                    delayed.add(() -> delegate.renew(node, epoch, lease).map(r -> seen(sent, r)).onComplete(late));
                    yield late.future();
                }
                case DELAY_ANSWER -> {
                    Future<Renewal> applied = delegate.renew(node, epoch, lease);
                    Promise<Renewal> late = Promise.promise();
                    delayed.add(() -> applied.map(r -> seen(sent, r)).onComplete(late));
                    yield late.future();
                }
            };
        }

        @Override public Future<List<String>> release(List<String> peers) {
            return delegate.release(peers);
        }
    }
}
