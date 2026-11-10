package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Runtime;
import io.flottila.api.Timer;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.flow.FlowBuilder;
import io.flottila.core.flow.FlowWorker;
import io.flottila.core.flow.WorkerPolicy;
import io.flottila.core.flow.Workers;
import io.flottila.core.record.Meter;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.runtime.store.PgIntake;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.flottila.runtime.journal.JournalWorld.FLOW;
import static io.flottila.runtime.journal.JournalWorld.bytes;
import static io.flottila.runtime.journal.JournalWorld.causes;
import static io.flottila.runtime.journal.JournalWorld.settled;
import static io.flottila.runtime.journal.JournalWorld.undecided;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-16a review r1 R4 (FIX-45 pattern): the slot calls of a group of two run beside the group's bookkeeping — a slot call that
 * throws never leaves a member's save unanswered, an in-doubt commit unregistered or another slot's room taken. Node {@code node-a}
 * ({@link JournalWorld} on a pool relayed by {@link PgWire}); a grouping {@link PgIntake} (one group in flight, its window on a fake
 * timer of its own) hands off to the worker of flow {@code orders} (two in flight), whose timer refuses one schedule when the case
 * arms it: the lease-end wake of a slot cancelled in doubt ({@code lease}), or the first renewal of a slot's run ({@code lease / 3}).
 * The plug that gathers the group (a save alone, its COMMIT held) is of flow {@code elsewhere}, which has no worker: it reserves
 * nothing and schedules nothing.
 */
@Timeout(240)
class PgIntakeHandoffGroupIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final Duration LEASE = Duration.ofSeconds(30);
    static final String NODE = "node-a";
    static final String ELSEWHERE = "elsewhere";
    static Vertx vertx;

    @TempDir Path tmp;
    CaseDatabase db;
    PgWire wire;
    Pool relayed;
    JournalWorld a;
    final FakeRuntime rt = FakeRuntime.create();
    final RefusingTimer timer = new RefusingTimer(rt.timer());
    final Workers workers = new Workers();
    PgIntake intake;
    PgIntakeHandoffIT.Hold hold;
    FlowWorker worker;
    PgIntakeHandoffIT.Claims claims;

    /** {@code inner}, but the first schedule of the armed delay after {@link #refuseNext} throws {@link #REFUSAL} at the call. */
    static final class RefusingTimer implements Timer {
        static final IllegalStateException REFUSAL = new IllegalStateException("schedule refused by the test");
        final Timer inner;
        final AtomicReference<Duration> armed = new AtomicReference<>();
        final AtomicInteger refused = new AtomicInteger();

        RefusingTimer(Timer inner) { this.inner = inner; }

        void refuseNext(Duration delay) { armed.set(delay); }

        @Override public Cancellable schedule(Duration delay, Runnable task) {
            Duration d = armed.get();
            if (d != null && d.equals(delay) && armed.compareAndSet(d, null)) {
                refused.incrementAndGet();
                throw REFUSAL;
            }
            return inner.schedule(delay, task);
        }
    }

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stop() {
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void open() throws Exception {
        db = CaseDatabase.create(pg, vertx, "handoff_group_").migrated();
        wire = new PgWire(pg.getHost(), pg.getMappedPort(5432));
        relayed = PgBuilder.pool().connectingTo(db.options().setHost("127.0.0.1").setPort(wire.port()))
                .with(new PoolOptions().setMaxSize(2)).using(vertx).build();
        a = JournalWorld.open(vertx, relayed, db.pool(), Files.createDirectories(tmp.resolve(NODE)), NODE);
        FakeTimer window = new FakeTimer(FakeClock.at(Instant.parse("2026-11-10T10:00:00Z")));   // never advanced: members go when the slot frees
        intake = new PgIntake(a.pool, a.blobs, a.messages, a.queue, workers, window, new NodeConfig.IntakeGroup(64, 1L << 20, Duration.ofMillis(50), 1));
        relayed.query("select 1").execute().await();
        hold = new PgIntakeHandoffIT.Hold();
        claims = new PgIntakeHandoffIT.Claims(a.queue);
        Runtime runtime = new Runtime(rt.clock(), timer, rt.ids(), a.blobs, rt.hasher(), Map.of());
        worker = new FlowBuilder(runtime, List.of(hold), List.of(), Map.of()).recording(a.messages, Meter.system()).queue(claims)
                .dlq(new InMemoryDlq()).workers(workers).worker(PgIntakeHandoffIT.flow(), Path.of(""), NODE, new WorkerPolicy(LEASE, 5, Duration.ofMinutes(10)));
        worker.start();
        PgIntakeHandoffIT.until(() -> claims.calls.get() == 1 && claims.answered.get() == 1, () -> "the worker's one start claim answered (A6)");
    }

    @AfterEach
    void close() throws Exception {
        wire.close();   // first: a held reply never hangs the pool's close
        if (worker != null) worker.stop();
        if (hold != null) hold.releaseAll();
        if (a != null) a.close();
        relayed.close().await();
        db.close();
    }

    /** {@code id}'s save of flow {@code flow}, no key, its input kept, called on a virtual thread; returns once the call returned. */
    Future<String> call(String id, String flow) throws Exception {
        Body body = settled(a.blobs.write(BlobKey.body(id, 0), bytes(1_000, id.hashCode()), JournalWorld.MAX)).result();
        Envelope e = Envelope.ingress(id, flow, Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), body);
        return OnVirtualThread.join(OnVirtualThread.start(() -> intake.save(e, JournalWorld.ingress(e), flow, null, null, true, undecided())));
    }

    /** A save of flow {@code elsewhere} alone in the intake's one commit slot, held at its COMMIT: saves called now form one group. */
    record Plug(PgWire.Held commit, Future<String> saved) {}

    Plug plug() throws Exception {
        PgWire.Held commit = wire.holdNext(PgWire.query("COMMIT"));
        Future<String> saved = call("m-plug", ELSEWHERE);
        commit.awaitReached();
        return new Plug(commit, saved);
    }

    void unplug(Plug p) {
        p.commit().release();
        assertTrue(settled(p.saved()).succeeded(), "the plug saved: " + p.saved().cause());
    }

    String lockedBy(String id) {
        return db.pool().preparedQuery("select locked_by from work where id = $1").execute(io.vertx.sqlclient.Tuple.of(id)).await()
                .iterator().next().getString(0);
    }

    /** Both slots of the worker's room (two) reserved, then given back: nothing holds room. */
    void roomIsBack(String why) {
        Optional<FlowWorker.Slot> first = workers.reserve(FLOW);
        Optional<FlowWorker.Slot> second = workers.reserve(FLOW);
        first.ifPresent(s -> s.cancel(false));
        second.ifPresent(s -> s.cancel(false));
        assertTrue(first.isPresent() && second.isPresent(), why + ": room 2 back (slots " + first.isPresent() + ", " + second.isPresent() + ")");
    }

    /**
     * A group commit in doubt (its COMMIT landed, the reply replaced by a FATAL) whose first slot's cancel throws — its lease-end
     * wake refused by the timer — still fails every member with the lost reply, registers the doubt (the store keeps both open;
     * the intake's {@code pg_xact_status} settles both committed) and gives every slot's room back.
     */
    @Test
    void aSlotCancelThatThrowsInAnInDoubtGroupLeavesEveryMemberAnsweredAndTheDoubtRegistered() throws Exception {
        Plug plug = plug();
        Future<String> m1 = call("m-1", FLOW);
        Future<String> m2 = call("m-2", FLOW);
        assertFalse(m1.isComplete() || m2.isComplete(), "fixture: both wait behind the plug");
        PgWire.FatalReply lost = wire.fatalReplyTo(PgWire.query("COMMIT"));   // the plug's COMMIT is past: the next is the group's
        timer.refuseNext(LEASE);   // the first slot cancelled in doubt arms the lease-end wake: refused

        unplug(plug);
        lost.awaitDone();
        for (Future<String> save : List.of(m1, m2)) {
            assertTrue(settled(save).failed(), "a commit in doubt fails every member: " + save);
            assertTrue(causes(save.cause()).stream().anyMatch(c -> c.contains("57P01")), "with the lost reply: " + causes(save.cause()));
        }
        PgIntakeHandoffIT.until(() -> timer.refused.get() == 1, () -> "fixture: a slot's cancel met the refusing timer (the slot calls follow the answers)");
        assertEquals(NODE, lockedBy("m-1"), "fixture: the commit landed, both rows leased to the node");
        assertEquals(NODE, lockedBy("m-2"));
        assertEquals(2, List.copyOf(a.blobs.openSaves()).size(), "in doubt: both members stay open in the store");
        assertEquals(List.of(), List.copyOf(intake.openSaves()), "the doubt was registered: pg_xact_status settles both committed");
        roomIsBack("every slot of the group given back");
    }

    /**
     * A committed group whose first slot's run throws — its renewal refused by the timer — still answers every member's save with
     * its id, and the other member's slot runs its item.
     */
    @Test
    void aHandOffThatThrowsInACommittedGroupStillAnswersEveryMember() throws Exception {
        Plug plug = plug();
        Future<String> m1 = call("m-1", FLOW);
        Future<String> m2 = call("m-2", FLOW);
        assertFalse(m1.isComplete() || m2.isComplete(), "fixture: both wait behind the plug");
        timer.refuseNext(LEASE.dividedBy(3));   // the first slot's run schedules its renewal: refused

        unplug(plug);
        assertTrue(settled(m1).succeeded(), "m-1 answered: " + m1.cause());
        assertTrue(settled(m2).succeeded(), "m-2 answered: " + m2.cause());
        assertEquals("m-1", m1.result());
        assertEquals("m-2", m2.result());
        PgIntakeHandoffIT.until(() -> timer.refused.get() == 1, () -> "fixture: a slot's run met the refusing timer (the slot calls follow the answers)");
        assertEquals(NODE, lockedBy("m-1"), "both written leased to the node");
        assertEquals(NODE, lockedBy("m-2"));
        PgIntakeHandoffIT.until(() -> hold.entries("m-1").size() + hold.entries("m-2").size() == 1,
                () -> "exactly one entry: the slot whose run was not refused runs its item, the refused slot's never ran; events " + hold.events);
    }
}
