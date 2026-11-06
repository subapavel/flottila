package io.flottila.core.node;

import io.flottila.core.node.ScriptedMembership.Mode;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P2-15a acceptance 2 (D-101 (1), (2)): the node lease and its self-fence on a fake monotonic clock and the fake timer. With
 * lease L and renew period R: on each successful renew {@code fenceUntil = max(fenceUntil, s + L/2)}, {@code s} the clock
 * read before the request; {@code open = !superseded && now < fenceUntil}; a renew updating 0 rows (renewed = false) is
 * superseded and halts the node (fail-stop); a node releases expired peers only if its own fence stayed open for the last L.
 *
 * <p>The API assumed (P2-15a, test-author): {@code io.flottila.core.node.NodeLease implements BooleanSupplier} —
 * {@code new NodeLease(String node, Membership membership, Duration lease, Duration renew, LongSupplier nanos, Timer timer,
 * Runnable halt)}; {@code Future<Void> start()} joins and renews every {@code renew} on {@code timer}; {@code close()} stops
 * the renewals; {@code getAsBoolean()} is the fence. {@code InMemoryMembership(Clock)} measures the leases on that clock. The
 * join counts as the first renew (its send opens the fence until {@code s + L/2}); before it the fence is closed.
 *
 * <p>The monotonic clock starts at a negative reading, as {@link System#nanoTime()} may.
 */
class NodeLeaseTest {

    static final Instant T0 = Instant.parse("2026-11-05T08:00:00Z");
    static final Duration L = Duration.ofSeconds(30);
    static final Duration R = Duration.ofSeconds(5);
    static final long ORIGIN = -9_000_000_000_000L;   // a monotonic reading may be negative

    final FakeClock clock = FakeClock.at(T0);
    final FakeTimer timer = new FakeTimer(clock);
    final LongSupplier nanos = () -> ORIGIN + Duration.between(T0, clock.instant()).toNanos();
    final ScriptedMembership scripted = new ScriptedMembership(clock);
    final List<NodeLease> leases = new ArrayList<>();

    @AfterEach
    void closeLeases() {
        leases.forEach(NodeLease::close);
    }

    NodeLease lease(String node, Membership membership, AtomicInteger halts) {
        NodeLease lease = new NodeLease(node, membership, L, R, nanos, timer, halts::incrementAndGet);
        leases.add(lease);
        return lease;
    }

    static Duration s(long seconds) { return Duration.ofSeconds(seconds); }

    /** Moves the fake clock to {@code T0 + t}, stopping at every timer task due on the way, so each fires at its own instant. */
    void until(Duration t) {
        Instant target = T0.plus(t);
        if (target.isBefore(clock.instant())) fail("the test moves the clock back to " + target + " from " + clock.instant());
        while (timer.pending() > 0 && !clock.instant().plus(timer.nextDelay()).isAfter(target)) clock.advance(timer.nextDelay());
        clock.set(target);
    }

    List<Instant> renewInstants() {
        return scripted.renews().stream().map(ScriptedMembership.Call::at).toList();
    }

    @Test
    void aNodeThatCannotRenewClosesItsFenceAtHalfTheLease() {
        AtomicInteger halts = new AtomicInteger();
        NodeLease lease = lease("a", scripted, halts);
        assertFalse(lease.getAsBoolean(), "closed before the node joined");

        lease.start().await();
        assertEquals(List.of(T0), scripted.joins(), "joined at start");
        assertTrue(lease.getAsBoolean(), "open once joined");
        until(s(10));
        assertEquals(List.of(T0.plusSeconds(5), T0.plusSeconds(10)), renewInstants(), "one renew every R from the join");
        assertTrue(scripted.renews().stream().allMatch(c -> c.node().equals("a") && c.epoch() == 1 && c.lease().equals(L)),
                "each renew names the node, the epoch its join got and the lease: " + scripted.renews());

        scripted.mode(Mode.FAIL);   // from now on the database cannot be reached
        until(s(25).minusMillis(1));
        assertTrue(lease.getAsBoolean(), "open 1 ms before T0+25 s: the last renew that succeeded was sent at T0+10 s, + L/2");
        until(s(25));
        assertFalse(lease.getAsBoolean(), "closed at T0+25 s = s + L/2 of the last successful renew, though its lease runs to T0+40 s");
        assertTrue(renewInstants().containsAll(List.of(T0.plusSeconds(15), T0.plusSeconds(20), T0.plusSeconds(25))),
                "the failed renews were tried every R all the same: " + renewInstants());
        until(s(120));
        assertFalse(lease.getAsBoolean(), "still closed while no renew succeeds");
        assertEquals(0, halts.get(), "a renew that fails supersedes nothing: the node is not halted");
    }

    @Test
    void theFenceCountsFromTheRenewsSendNotItsAnswer() {
        AtomicInteger halts = new AtomicInteger();
        NodeLease lease = lease("a", scripted, halts);
        lease.start().await();   // joined at T0: open until T0+15 s
        scripted.mode(Mode.HANG);
        until(s(5));
        assertEquals(List.of(T0.plusSeconds(5)), renewInstants(), "the renew sent at T0+5 s, unanswered");

        until(s(12));
        scripted.answer(0, true);   // the renew sent at T0+5 s answers at T0+12 s
        assertTrue(lease.getAsBoolean(), "open at T0+12 s");
        until(s(20).minusMillis(1));
        assertTrue(lease.getAsBoolean(), "open until T0+20 s = its send (T0+5 s) + L/2: the answer extended the join's T0+15 s");
        until(s(20));
        assertFalse(lease.getAsBoolean(), "closed at T0+20 s: counted from the send, not from the answer (T0+12 s + L/2 = T0+27 s)");
        until(s(27));
        assertFalse(lease.getAsBoolean(), "still closed at T0+27 s: no later renew was answered");
        assertEquals(0, halts.get());
    }

    @Test
    void aReleasedNodesNextRenewHaltsItForGood() {
        AtomicInteger halts = new AtomicInteger();
        NodeLease lease = lease("a", scripted, halts);
        lease.start().await();
        until(s(5));   // renewed at T0+5 s: open until T0+20 s
        assertTrue(lease.getAsBoolean(), "fixture: open after a renew");

        scripted.mode(Mode.SUPERSEDED);   // a peer released this node's row: the next renew updates 0 rows
        until(s(10));
        assertEquals(T0.plusSeconds(10), renewInstants().getLast(), "fixture: the renew at T0+10 s answered renewed = false");
        assertFalse(lease.getAsBoolean(), "closed at once on the superseded answer, though T0+10 s is before its fenceUntil T0+20 s");
        assertEquals(1, halts.get(), "the node halts (fail-stop)");

        until(s(10).plus(L.multipliedBy(2)));
        assertFalse(lease.getAsBoolean(), "still closed two leases later");
        assertEquals(1, halts.get(), "halted once, not again at every later renew");

        scripted.mode(Mode.OK);   // whatever the membership answers from now on
        until(s(10).plus(L.multipliedBy(4)));
        assertFalse(lease.getAsBoolean(), "for good: a superseded node's fence never opens again, a renewed answer included");
        assertEquals(1, halts.get());
    }

    @Test
    void aNodeWithAGapInItsOwnLeaseReleasesNobodyForOneLease() {
        AtomicInteger halts = new AtomicInteger();
        scripted.expired(List.of("b"));   // every renewed answer names peer b as expired
        NodeLease lease = lease("a", scripted, halts);
        lease.start().await();

        until(s(25));
        assertEquals(List.of(), scripted.releases(), "joined at T0: its fence was not open for the last L before T0+30 s");
        until(s(35));
        assertFalse(scripted.releases().isEmpty(), "open for a whole lease: b is released");
        ScriptedMembership.Release first = scripted.releases().get(0);
        assertEquals(List.of("b"), first.peers(), "the expired peer the renew named");
        assertTrue(!first.at().isBefore(T0.plusSeconds(30)) && !first.at().isAfter(T0.plusSeconds(35)),
                "released at the first renew L after the join, within R: " + first.at());

        // one failed renew that leaves the fence open is no gap: the releases go on
        scripted.mode(Mode.FAIL);
        until(s(40));
        scripted.mode(Mode.OK);
        int beforeBlip = scripted.releases().size();
        until(s(45));
        assertEquals(beforeBlip + 1, scripted.releases().size(), "a failed renew at T0+40 s left the fence open (to T0+50 s): released at T0+45 s");

        // a gap: no renew succeeds from T0+50 s on, the fence (last success T0+45 s) closes at T0+60 s
        scripted.mode(Mode.FAIL);
        until(s(65));
        assertFalse(lease.getAsBoolean(), "fixture: the fence closed at T0+60 s");
        scripted.mode(Mode.OK);
        int beforeReopen = scripted.releases().size();
        until(s(70));
        assertTrue(lease.getAsBoolean(), "fixture: reopened by the renew sent at T0+70 s");
        until(s(95));
        assertEquals(beforeReopen, scripted.releases().size(),
                "reopened at T0+70 s after a gap: nobody released for one lease, up to the renew at T0+95 s: " + scripted.releases());
        until(s(105));
        assertTrue(scripted.releases().size() > beforeReopen, "a lease after the reopening the releases resume (by T0+105 s)");
        Instant resumed = scripted.releases().get(beforeReopen).at();
        assertTrue(!resumed.isBefore(T0.plusSeconds(100)), "not before T0+100 s = reopened + L: " + resumed);
        assertEquals(0, halts.get());
    }

    @Test
    void aDeadPeerIsReleasedWithinLeasePlusRenewNeverBeforeHalfALeaseAfterItsFence() {
        InMemoryMembership members = new InMemoryMembership(clock);
        Recording seenByA = new Recording(members, clock);
        Killable seenByB = new Killable(members);
        AtomicInteger haltsA = new AtomicInteger();
        AtomicInteger haltsB = new AtomicInteger();
        NodeLease a = lease("a", seenByA, haltsA);
        NodeLease b = lease("b", seenByB, haltsB);

        a.start().await();           // a joins at T0, renews at T0+5k s
        until(s(1));
        b.start().await();           // b joins at T0+1 s, renews at T0+6 s and T0+11 s
        until(s(11));
        seenByB.kill();              // b dies right after its renew sent at T0+11 s: its fence ends at T0+26 s, its row at T0+41 s
        Instant fenceOfB = T0.plusSeconds(26);
        until(s(26).minusMillis(1));
        assertTrue(b.getAsBoolean(), "fixture: b's fence open until T0+26 s");
        until(s(26));
        assertFalse(b.getAsBoolean(), "fixture: b's fence closed at T0+26 s");

        Instant released = null;
        for (Duration t = s(26); released == null && t.compareTo(s(120)) <= 0; t = t.plusMillis(100)) {
            until(t);
            released = seenByA.releasedAt("b");
        }
        assertTrue(released != null, "a released the dead b within 120 s; a's releases: " + seenByA.released);
        assertFalse(released.isBefore(fenceOfB.plus(L.dividedBy(2))),
                "never before half a lease after b's fence (T0+41 s): released at " + released);
        assertFalse(released.isAfter(T0.plusSeconds(11).plus(L).plus(R)),
                "within L + R of b's last renew (T0+46 s): released at " + released);
        assertTrue(a.getAsBoolean(), "a's own fence stayed open throughout");

        seenByB.revive();            // b was only paused: its next renew finds its row released
        until(Duration.between(T0, released).plus(R).plus(R));
        assertEquals(1, haltsB.get(), "a released node's next renew halts it");
        assertFalse(b.getAsBoolean());
        assertEquals(0, haltsA.get());
    }

    /** A membership seen through by one node, its releases recorded with the instant each answered and what it released. */
    static final class Recording implements Membership {
        record Released(Instant at, List<String> peers) {}

        final Membership delegate;
        final Clock clock;
        final List<Released> released = new CopyOnWriteArrayList<>();

        Recording(Membership delegate, Clock clock) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.clock = clock;
        }

        Instant releasedAt(String peer) {
            return released.stream().filter(r -> r.peers().contains(peer)).map(Released::at).findFirst().orElse(null);
        }

        @Override public Future<Long> join(String node, Duration lease) { return delegate.join(node, lease); }

        @Override public Future<Renewal> renew(String node, long epoch, Duration lease) { return delegate.renew(node, epoch, lease); }

        @Override public Future<List<String>> release(List<String> peers) {
            return delegate.release(peers).onSuccess(done -> { if (!done.isEmpty()) released.add(new Released(clock.instant(), List.copyOf(done))); });
        }
    }

    /**
     * A membership seen through by one node that can be killed: from then on its calls never answer (a dead or paused process);
     * revived, the calls left unanswered fail as on a reset connection and new ones reach the membership again.
     */
    static final class Killable implements Membership {
        final Membership delegate;
        final List<Promise<?>> unanswered = new CopyOnWriteArrayList<>();
        volatile boolean dead;

        Killable(Membership delegate) { this.delegate = Objects.requireNonNull(delegate, "delegate"); }

        void kill() { dead = true; }

        void revive() {
            dead = false;
            unanswered.forEach(p -> p.tryFail(new IllegalStateException("connection reset while the process was paused")));
        }

        private <T> Future<T> never() {
            Promise<T> p = Promise.promise();
            unanswered.add(p);
            return p.future();
        }

        @Override public Future<Long> join(String node, Duration lease) {
            return dead ? never() : delegate.join(node, lease);
        }

        @Override public Future<Renewal> renew(String node, long epoch, Duration lease) {
            return dead ? never() : delegate.renew(node, epoch, lease);
        }

        @Override public Future<List<String>> release(List<String> peers) {
            return dead ? never() : delegate.release(peers);
        }
    }
}
