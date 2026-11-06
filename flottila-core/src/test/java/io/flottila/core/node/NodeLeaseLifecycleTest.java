package io.flottila.core.node;

import io.flottila.core.node.ScriptedMembership.Mode;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-15a (D-101 (2)): the node lease's lifecycle around {@link NodeLeaseTest}'s cases — a superseded node sends no renew again;
 * an answer that lands after {@code close} changes nothing, and neither does a tick its close could not cancel; a join that fails leaves the fence closed and schedules nothing, a
 * close before the join answers schedules nothing either; renews that keep failing are one WARNING until one succeeds; a release
 * that fails is a WARNING and the next renew asks again, one that succeeds a WARNING naming the peers; the supersession is one
 * ERROR; a lease or a renew period that is not positive is refused.
 */
class NodeLeaseLifecycleTest {

    static final Instant T0 = Instant.parse("2026-11-05T08:00:00Z");
    static final Duration L = Duration.ofSeconds(30);
    static final Duration R = Duration.ofSeconds(5);

    final FakeClock clock = FakeClock.at(T0);
    final FakeTimer timer = new FakeTimer(clock);
    final LongSupplier nanos = () -> Duration.between(T0, clock.instant()).toNanos();
    final ScriptedMembership scripted = new ScriptedMembership(clock);
    final AtomicInteger halts = new AtomicInteger();
    final List<NodeLease> leases = new ArrayList<>();

    @AfterEach
    void closeLeases() {
        leases.forEach(NodeLease::close);
    }

    NodeLease lease(Membership membership) {
        NodeLease lease = new NodeLease("a", membership, L, R, nanos, timer, halts::incrementAndGet);
        leases.add(lease);
        return lease;
    }

    /** A membership whose join answers {@code join}; renews and releases are never sent in these cases. */
    static Membership joining(Future<Long> join) {
        return new Membership() {
            @Override public Future<Long> join(String node, Duration lease) { return join; }
            @Override public Future<Renewal> renew(String node, long epoch, Duration lease) { throw new AssertionError("no renew expected"); }
            @Override public Future<List<String>> release(List<String> peers) { throw new AssertionError("no release expected"); }
        };
    }

    /** Moves the clock in one-second steps, so each renew fires at its own instant. */
    void advance(Duration total) {
        for (long s = 0; s < total.toSeconds(); s++) clock.advance(Duration.ofSeconds(1));
    }

    @Test
    void aSupersededNodeSendsNoRenewAgain() {
        lease(scripted).start().await();
        scripted.mode(Mode.SUPERSEDED);
        advance(R);
        assertEquals(1, halts.get(), "fixture: superseded at the first renew");
        advance(L.multipliedBy(2));
        assertEquals(1, scripted.renews().size(), "no renew after the supersession: " + scripted.renews());
        assertEquals(0, timer.pending(), "and none scheduled");
    }

    @Test
    void anAnswerAfterTheCloseChangesNothing() {
        NodeLease lease = lease(scripted);
        lease.start().await();
        scripted.mode(Mode.HANG);
        advance(R);   // the renew at T0+5 s hangs
        lease.close();
        scripted.answer(0, false);   // a superseded answer, after the close
        assertEquals(0, halts.get(), "a closed lease halts nothing");
        assertEquals(0, timer.pending(), "and renews no more");
        assertTrue(lease.getAsBoolean(), "its fence counts on from the join (T0+15 s) as without renewals");
        advance(Duration.ofSeconds(10));
        assertFalse(lease.getAsBoolean(), "closed at T0+15 s");
    }

    @Test
    void aTickThatFiresAfterTheCloseSendsNothing() {
        // a timer whose cancel comes too late (the task already firing, as a Vert.x timer may): the tick runs after the close
        io.flottila.api.Timer late = (delay, task) -> {
            timer.schedule(delay, task);
            return () -> false;
        };
        NodeLease lease = new NodeLease("a", scripted, L, R, nanos, late, halts::incrementAndGet);
        leases.add(lease);
        lease.start().await();
        lease.close();
        advance(R.multipliedBy(3));
        assertEquals(List.of(), scripted.renews(), "a closed lease sends no renew, even from a tick its close could not cancel");
        assertEquals(0, timer.pending(), "and schedules none");
    }

    @Test
    void aJoinThatFailsLeavesTheFenceClosedAndRenewsNothing() {
        Membership down = joining(Future.failedFuture(new IllegalStateException("the database is unreachable")));
        NodeLease lease = lease(down);
        Future<Void> started = lease.start();
        assertTrue(started.failed(), "the start fails as the join failed");
        assertFalse(lease.getAsBoolean());
        assertEquals(0, timer.pending(), "no renew scheduled");
    }

    @Test
    void aCloseBeforeTheJoinAnswersSchedulesNoRenew() {
        Promise<Long> joined = Promise.promise();
        Membership slow = joining(joined.future());
        NodeLease lease = lease(slow);
        Future<Void> started = lease.start();
        lease.close();
        joined.complete(1L);
        assertTrue(started.succeeded());
        assertEquals(0, timer.pending(), "closed before the join answered: no renew scheduled");
    }

    @Test
    void renewsThatKeepFailingAreOneWarningUntilOneSucceeds() {
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord r) { if (r.getLevel() == Level.WARNING) warnings.add(r); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        Logger log = Logger.getLogger(NodeLease.class.getName());
        log.addHandler(capture);
        try {
            lease(scripted).start().await();
            scripted.mode(Mode.FAIL);
            advance(R.multipliedBy(3));
            assertEquals(3, scripted.renews().size(), "fixture: three renews failed");
            assertEquals(1, warnings.size(), "one WARNING for the three: " + warnings.stream().map(LogRecord::getMessage).toList());
            scripted.mode(Mode.OK);
            advance(R);
            scripted.mode(Mode.FAIL);
            advance(R);
            assertEquals(2, warnings.size(), "a failure after a success is a WARNING again");
        } finally {
            log.removeHandler(capture);
        }
    }

    @Test
    void aReleaseThatFailsIsAWarningAndTheNextRenewAsksAgain() {
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord r) { if (r.getLevel() == Level.WARNING) warnings.add(r); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        List<List<String>> asked = new CopyOnWriteArrayList<>();
        Membership failingRelease = new Membership() {
            @Override public Future<Long> join(String node, Duration lease) { return scripted.join(node, lease); }
            @Override public Future<Renewal> renew(String node, long epoch, Duration lease) { return scripted.renew(node, epoch, lease); }
            @Override public Future<List<String>> release(List<String> peers) {
                asked.add(List.copyOf(peers));
                return Future.failedFuture(new IllegalStateException("scripted: the release did not commit"));
            }
        };
        Logger log = Logger.getLogger(NodeLease.class.getName());
        log.addHandler(capture);
        try {
            lease(failingRelease).start().await();
            scripted.expired(List.of("b"));
            advance(L.plus(R));   // past the first lease: this node's fence stayed open for a whole lease
            assertTrue(asked.size() >= 2, "every renewed answer naming b after the first lease asks again: " + asked);
            assertTrue(asked.stream().allMatch(List.of("b")::equals), "only b is asked for: " + asked);
            assertEquals(asked.size(), warnings.stream().filter(w -> w.getMessage().contains("could not release")).count(),
                    "one WARNING per failed release: " + warnings.stream().map(LogRecord::getMessage).toList());
        } finally {
            log.removeHandler(capture);
        }
    }

    /** The records {@link NodeLease} logs at {@code level} while {@code run} runs. */
    static List<String> logged(Level level, Runnable run) {
        List<String> records = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord r) { if (r.getLevel() == level) records.add(r.getMessage()); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        Logger log = Logger.getLogger(NodeLease.class.getName());
        log.addHandler(capture);
        try {
            run.run();
        } finally {
            log.removeHandler(capture);
        }
        return records;
    }

    @Test
    void theSupersessionIsOneErrorSayingWhy() {
        List<String> errors = logged(Level.SEVERE, () -> {
            lease(scripted).start().await();
            scripted.mode(Mode.SUPERSEDED);
            advance(R.multipliedBy(3));
        });
        assertEquals(1, halts.get(), "fixture: halted");
        assertEquals(1, errors.size(), "one ERROR: " + errors);
        assertTrue(errors.get(0).contains("node a (epoch 1) was superseded"), errors.get(0));
    }

    @Test
    void aReleaseIsAWarningNamingThePeersReleased() {
        List<String> warnings = logged(Level.WARNING, () -> {
            lease(scripted).start().await();
            scripted.expired(List.of("b", "c"));
            advance(L);   // the first renew after a whole lease of an open fence releases them
        });
        assertEquals(List.of(List.of("b", "c")), scripted.releases().stream().map(ScriptedMembership.Release::peers).toList(), "fixture: one release");
        assertEquals(1, warnings.size(), "one WARNING: " + warnings);
        assertTrue(warnings.get(0).contains("found the members [b, c] expired and released [b, c]"), warnings.get(0));
    }

    @Test
    void aLeaseOrARenewPeriodThatIsNotPositiveIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new NodeLease("a", scripted, Duration.ZERO, R, nanos, timer, () -> {}));
        assertThrows(IllegalArgumentException.class, () -> new NodeLease("a", scripted, L, Duration.ofSeconds(-1), nanos, timer, () -> {}));
    }
}
