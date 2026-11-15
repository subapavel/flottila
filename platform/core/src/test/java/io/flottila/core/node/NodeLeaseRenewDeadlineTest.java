package io.flottila.core.node;

import io.flottila.core.node.ScriptedMembership.Mode;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.logging.Level;

import static io.flottila.core.node.NodeLeaseLifecycleTest.logged;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-15a, architect decision (4) on the implementer's finding (F-28): the renew is bounded. A renew still unanswered half a
 * lease after its send fails there — the fence closed by then, from the last renewed send — the membership reconnects, and the
 * next period renews again, so a black-holed renew never stops the renewals; the abandoned renew's answer, if it ever comes, is
 * ignored. A renew answered in time and a lease closed meanwhile reconnect nothing; abandoned renews in a row are one WARNING
 * until a renew succeeds.
 */
class NodeLeaseRenewDeadlineTest {

    static final Instant T0 = Instant.parse("2026-11-05T08:00:00Z");
    static final Duration L = Duration.ofSeconds(30);
    static final Duration R = Duration.ofSeconds(7);   // ticks at 7, 14, 21, … never on a deadline (send + 15)

    final FakeClock clock = FakeClock.at(T0);
    final FakeTimer timer = new FakeTimer(clock);
    final LongSupplier nanos = () -> Duration.between(T0, clock.instant()).toNanos();
    final ScriptedMembership scripted = new ScriptedMembership(clock);
    final AtomicInteger reconnects = new AtomicInteger();
    final AtomicInteger halts = new AtomicInteger();
    final List<NodeLease> leases = new ArrayList<>();

    /** The scripted store, its reconnects counted. */
    final Membership membership = new Membership() {
        @Override public Future<Long> join(String node, Duration lease) { return scripted.join(node, lease); }
        @Override public Future<Renewal> renew(String node, long epoch, Duration lease) { return scripted.renew(node, epoch, lease); }
        @Override public Future<List<String>> release(List<String> peers) { return scripted.release(peers); }
        @Override public void reconnect() { reconnects.incrementAndGet(); }
    };

    @AfterEach
    void closeLeases() {
        leases.forEach(NodeLease::close);
    }

    /** A timer whose cancel comes too late (the task already firing, as a Vert.x timer's may): every deadline fires. */
    final io.flottila.api.Timer late = (delay, task) -> {
        timer.schedule(delay, task);
        return () -> false;
    };

    NodeLease started() {
        return started(timer);
    }

    NodeLease started(io.flottila.api.Timer on) {
        NodeLease lease = new NodeLease("a", membership, L, R, nanos, on, halts::incrementAndGet);
        leases.add(lease);
        lease.start().await();
        return lease;
    }

    /** Moves the clock in one-second steps to {@code second} after T0, so each timer task fires at its own instant. */
    void at(long second) {
        while (clock.instant().isBefore(T0.plusSeconds(second))) clock.advance(Duration.ofSeconds(1));
    }

    @Test
    void aBlackHoledRenewFailsAtHalfALeaseAndTheNextRenewAfterTheReconnectReopensTheFence() {
        NodeLease lease = started();
        at(7);
        assertEquals(1, scripted.renews().size(), "fixture: the renew at 7 s renewed (fence until 22 s)");
        scripted.mode(Mode.HANG);
        at(14);
        assertEquals(2, scripted.renews().size(), "the renew at 14 s is black-holed");
        at(21);
        assertTrue(lease.getAsBoolean(), "open until s + L/2 of the last renewed send");
        at(22);
        assertFalse(lease.getAsBoolean(), "closed at 7 s + L/2");
        at(28);
        assertEquals(2, scripted.renews().size(), "at most one renew in flight: the tick at 21 and 28 s sends nothing");
        assertEquals(0, reconnects.get(), "not before the hung renew's deadline");
        at(29);
        assertEquals(1, reconnects.get(), "the renew sent at 14 s fails at 14 s + L/2: the membership reconnects");

        scripted.mode(Mode.OK);   // the new connection reaches the store
        at(34);
        assertFalse(lease.getAsBoolean());
        assertEquals(2, scripted.renews().size());
        at(35);
        assertEquals(3, scripted.renews().size(), "the next period renews again");
        assertTrue(lease.getAsBoolean(), "and its renewed answer reopens the fence");
        assertEquals(1, timer.pending(), "the answered renew's deadline is cancelled: only the next period waits");

        scripted.answer(1, false);   // the abandoned renew's answer arrives after all: ignored
        assertEquals(0, halts.get(), "an abandoned renew's answer supersedes nobody");
        assertTrue(lease.getAsBoolean());
        at(49);
        assertTrue(lease.getAsBoolean(), "renewed at 42 and 49 s");
        assertEquals(1, reconnects.get());
    }

    @Test
    void aRenewAnsweredInTimeReconnectsNothingAtItsDeadline() {
        NodeLease lease = started(late);   // its deadline fires all the same
        scripted.mode(Mode.HANG);
        at(7);
        at(10);
        scripted.mode(Mode.OK);
        scripted.answer(0, true);   // the renew sent at 7 s answers 3 s late, inside its deadline (22 s)
        at(30);
        assertEquals(0, reconnects.get(), "answered in time: nothing dropped");
        assertTrue(lease.getAsBoolean(), "renewed at 14, 21 and 28 s");
        assertEquals(4, scripted.renews().size());
    }

    @Test
    void aLeaseClosedWithARenewOutstandingReconnectsNothingAndLogsNothing() {
        List<String> warnings = logged(Level.WARNING, () -> {
            NodeLease lease = started(late);   // the close cannot cancel the deadline: it fires
            scripted.mode(Mode.HANG);
            at(7);
            lease.close();
            at(30);   // the renew's deadline (22 s) passes after the close
        });
        assertEquals(0, reconnects.get(), "a closed lease drops no connection: its membership is being closed");
        assertEquals(List.of(), warnings);
        assertEquals(1, scripted.renews().size(), "and renews no more");
    }

    @Test
    void abandonedRenewsInARowAreOneWarningUntilARenewSucceeds() {
        List<String> warnings = logged(Level.WARNING, () -> {
            started();
            scripted.mode(Mode.HANG);
            at(44);      // renews sent at 7 and 28 s, abandoned at 22 and 43 s
            assertEquals(2, reconnects.get(), "fixture: two renews abandoned");
            scripted.mode(Mode.OK);
            at(49);      // the renew at 49 s succeeds
            scripted.mode(Mode.HANG);
            at(71);      // the renew at 56 s is abandoned at 71 s
            assertEquals(3, reconnects.get(), "fixture: a third renew abandoned, after a success");
        });
        assertEquals(2, warnings.size(), "one WARNING for the first two, one after the success: " + warnings);
        assertTrue(warnings.get(0).contains("unanswered half a lease after its send"), warnings.get(0));
    }
}
