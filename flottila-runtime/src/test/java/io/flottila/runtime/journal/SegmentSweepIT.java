package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobNotFoundException;
import io.flottila.api.Envelope;
import io.flottila.api.Timer;
import io.flottila.core.dlq.Parked;
import io.flottila.runtime.blob.TieredBlobStore;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.flottila.runtime.journal.JournalWorld.bodyRef;
import static io.flottila.runtime.journal.JournalWorld.bytes;
import static io.flottila.runtime.journal.JournalWorld.causes;
import static io.flottila.runtime.journal.JournalWorld.count;
import static io.flottila.runtime.journal.JournalWorld.settled;
import static io.flottila.runtime.journal.JournalWorld.undecided;
import static io.flottila.runtime.journal.JournalWorld.workRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CORE-4d packet item 1 and 2 (CORE-3 items 10 and 14): the segment sweeper of a node. A candidate is one of this node's
 * segments sealed at least {@code FLOTTILA_JOURNAL_SWEEP_GRACE} ago by its trailer's sealed ms (after a restart: since the
 * start). A pass reads the open-save set first, then asks the pins in a later snapshot; a segment of an open save is skipped.
 * pinned = a work row's ref with {@code lost_at} null (held, awaiting a retry or leased included) ∪ a DLQ row ⋈ its
 * {@code body_refs} row not archived, {@code lost_at} null ∪ a {@code body_refs} row with {@code archived_at} null,
 * {@code lost_at} null and {@code created_at} > clock − {@code FLOTTILA_JOURNAL_KEEP_MAX_AGE}. An unpinned row is locked
 * {@code for update skip locked}, its pins asked again, deleted; its file unlinked off the hot path, its cached read channel
 * closed. A save whose commit reply was lost stays open until {@code pg_xact_status} of its xid is known. Every expected set is
 * the exact set a pass answers; every case has a segment the same pass sweeps (the sweep is no no-op) or keeps.
 *
 * <p>Seams: the journal's fake clock seals a segment ({@code FLOTTILA_JOURNAL_SEGMENT_AGE} PT1S) and ages it; the sweeper's
 * own timer here never fires ({@link StillTimer}) — a pass runs when a case asks ({@code sweep()}), except in
 * {@link #passesRunEveryIntervalOnTheInjectedTimer()}; the intake's and the store's statements cross {@link PgWire}, which can
 * hold, cut or let through a COMMIT or an insert; the journal's rows and the sweeper's queries do not.
 *
 * <p>The API assumed (CORE-4d, test-author): {@code io.flottila.runtime.journal.SweepConfig(Duration interval, Duration grace,
 * Duration keepMaxAge)}; {@code io.flottila.runtime.journal.SegmentSweeper(Journal journal, SegmentRows rows,
 * Supplier<? extends Collection<JournalRef>> openSaves, SweepConfig config, Clock clock, Timer timer) implements AutoCloseable}
 * — {@code void start()} (the first pass {@code interval} later on {@code timer}, each pass scheduling the next),
 * {@code Future<Set<Long>> sweep()} (one pass now, on a virtual thread: the segment numbers whose row it deleted),
 * {@code close()}; {@code PgIntake.openSaves()} (exists) answers a save whose commit outcome is unknown until
 * {@code pg_xact_status} of the xid it took inside its transaction says committed or aborted.
 */
@Timeout(240)
class SegmentSweepIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final Duration INTERVAL = Duration.ofSeconds(10);
    static final Duration GRACE = Duration.ofMinutes(1);
    static final Duration KEEP = Duration.ofHours(1);
    static final Duration SEGMENT_AGE = Duration.ofSeconds(1);   // JournalFixture.Settings' default
    static final SweepConfig CONFIG = new SweepConfig(INTERVAL, GRACE, KEEP);
    static final long BOUND_NANOS = Duration.ofSeconds(20).toNanos();
    static Vertx vertx;

    @TempDir Path tmp;
    CaseDatabase db;
    PgWire wire;
    Pool relayed;
    JournalWorld a;
    final List<AutoCloseable> closing = new CopyOnWriteArrayList<>();

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
        db = CaseDatabase.create(pg, vertx, "sweep_").migrated();
        wire = new PgWire(pg.getHost(), pg.getMappedPort(5432));
        relayed = PgBuilder.pool().connectingTo(db.options().setHost("127.0.0.1").setPort(wire.port()))
                .with(new PoolOptions().setMaxSize(4)).using(vertx).build();
        a = world("node-a", relayed);
    }

    @AfterEach
    void close() throws Exception {
        List<AutoCloseable> all = new ArrayList<>(closing);
        java.util.Collections.reverse(all);
        for (AutoCloseable c : all) c.close();
        if (a != null) a.close();
        wire.close();
        relayed.close().await();
        db.close();
    }

    // --- the world

    JournalWorld world(String node, Pool pool) throws Exception {
        return JournalWorld.open(vertx, pool, db.pool(), Files.createDirectories(tmp.resolve(node)), node);
    }

    /** A timer that records what is scheduled and never fires it: a pass runs only when a case asks. */
    static final class StillTimer implements Timer {
        final List<Duration> delays = new CopyOnWriteArrayList<>();

        @Override public Cancellable schedule(Duration delay, Runnable task) {
            delays.add(delay);
            return () -> true;
        }
    }

    SegmentSweeper sweeper(JournalWorld w) {
        return sweeper(w, w.intake::openSaves);
    }

    SegmentSweeper sweeper(JournalWorld w, Supplier<? extends Collection<JournalRef>> openSaves) {
        SegmentSweeper s = new SegmentSweeper(w.journal, new PgSegmentRows(db.pool()), openSaves, CONFIG, w.opened.clock(), new StillTimer());
        closing.add(s);
        return s;
    }

    /** One pass, waited for: the segments it swept. */
    static Set<Long> sweep(SegmentSweeper s) {
        Future<Set<Long>> f = settled(s.sweep());
        assertTrue(f.succeeded(), "a pass completes: " + (f.failed() ? causes(f.cause()) : ""));
        return f.result();
    }

    /** A body's key and the segment its record is in, sealed. */
    record Sealed(String id, BlobKey key, long segment) { }

    /** Message {@code id} saved by the intake (its work row; a kept ref with {@code keepInput}), its segment then sealed. */
    Sealed saved(JournalWorld w, String id, boolean keepInput) throws Exception {
        Envelope in = w.arrive(id, bytes(3_000, id.hashCode()));
        Future<String> saved = w.save(in, keepInput, undecided());
        assertTrue(saved.succeeded(), "fixture: saved " + (saved.failed() ? causes(saved.cause()) : ""));
        JournalWorld.Ref ref = workRef(db.pool(), id);
        assertNotNull(ref, "fixture: its work row names its record");
        seal(w, ref.segment());
        return new Sealed(id, in.body().key(), ref.segment());
    }

    /** {@code id}'s step-1 body written and persisted by the store (its kept ref, no work row), its segment then sealed. */
    Sealed persisted(JournalWorld w, String id) throws Exception {
        BlobKey key = BlobKey.body(id, 1);
        assertTrue(settled(w.blobs.write(key, bytes(3_000, id.hashCode()), JournalWorld.MAX)).succeeded(), "fixture: written");
        assertTrue(settled(w.blobs.persist(key)).succeeded(), "fixture: persisted");
        JournalWorld.Ref ref = bodyRef(db.pool(), key);
        assertNotNull(ref, "fixture: its kept ref");
        seal(w, ref.segment());
        return new Sealed(id, key, ref.segment());
    }

    /** Rolls segment {@code n} by age on the journal's fake clock and waits until its footer is written (sealed). */
    static void seal(JournalWorld w, long n) throws Exception {
        w.opened.clock().advance(SEGMENT_AGE);
        until(() -> sealed(w, n), () -> "segment " + n + " sealed after its age");
    }

    static boolean sealed(JournalWorld w, long n) {
        try {
            return OnVirtualThread.call(() -> w.journal.index(n)).isPresent();
        } catch (Exception e) {
            throw new AssertionError("index of " + n, e);
        }
    }

    Path file(JournalWorld w, long n) {
        return w.opened.config().dir().resolve("segments").resolve(n + ".seg");
    }

    long rows(String node, long n) {
        return count(db.pool(), "select count(*) from journal_segments where node = $1 and segment = $2", node, n);
    }

    void kept(JournalWorld w, long n, String what) {
        assertEquals(1, rows(w.node, n), what + ": its segment row stays");
        assertTrue(Files.exists(file(w, n)), what + ": its file stays");
    }

    void gone(JournalWorld w, long n, String what) {
        assertEquals(0, rows(w.node, n), what + ": its segment row is deleted");
        until(() -> !Files.exists(file(w, n)), () -> what + ": its file " + file(w, n) + " is unlinked (in the background)");
    }

    void sql(String statement, Object... params) {
        db.pool().preparedQuery(statement).execute(Tuple.wrap(params)).await();
    }

    static OffsetDateTime at(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    /** {@code key}'s kept ref, created at {@code created} (the server writes now(); the pins compare with the injected clock). */
    void createdAt(BlobKey key, Instant created) {
        sql("update body_refs set created_at = $1 where message_id = $2 and step = $3", at(created), key.messageId(), key.step());
    }

    static void until(BooleanSupplier condition, Supplier<String> what) {
        long deadline = System.nanoTime() + BOUND_NANOS;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("not within 20 s: " + what.get());
            Thread.onSpinWait();
        }
    }

    CompletableFuture<Void> parkOnAVirtualThread(Parked p) {
        CompletableFuture<Void> outcome = new CompletableFuture<>();
        Thread.ofVirtual().name("sweep-it-park").start(() -> {
            try {
                a.dlq.park(p).await();
                outcome.complete(null);
            } catch (Throwable t) {
                outcome.completeExceptionally(t);
            }
        });
        return outcome;
    }

    void parked(Sealed s) throws Exception {
        CompletableFuture<Void> p = parkOnAVirtualThread(PgDlqJournalIT.parked(s.id(), s.key().step() + 1));
        p.get(20, java.util.concurrent.TimeUnit.SECONDS);
    }

    long waiters(String event) {
        return count(db.pool(), "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock' and wait_event = $1", event);
    }

    long lockWaiters() {
        return count(db.pool(), "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'");
    }

    // --- grace and interval

    @Test
    void graceCountsFromTheSeal() throws Exception {
        Sealed s = saved(a, "m-grace", false);
        Instant sealedAt = a.opened.clock().instant();
        sql("delete from work where id = $1", s.id());
        SegmentSweeper sweeper = sweeper(a);

        a.opened.clock().advance(GRACE.minusMillis(1));
        assertEquals(Set.of(), sweep(sweeper), "sealed 1 ms less than the grace ago: no candidate");
        kept(a, s.segment(), "inside the grace");

        a.opened.clock().advance(Duration.ofMillis(1));
        assertEquals(Set.of(s.segment()), sweep(sweeper), "sealed exactly the grace ago: swept");
        gone(a, s.segment(), "past the grace");

        // after a restart the grace counts from the start, whatever the trailer says
        Sealed before = saved(a, "m-before-restart", false);
        sql("delete from work where id = $1", before.id());
        long highestBefore = count(db.pool(), "select max(segment) from journal_segments where node = 'node-a'");
        closing.remove(sweeper);
        sweeper.close();
        a.close();
        Instant restart = sealedAt.plus(Duration.ofMinutes(30));
        a = JournalWorld.open(vertx, relayed, db.pool(), tmp.resolve("node-a"), "node-a", x -> { },
                new io.flottila.runtime.blob.TieredBlobStore.Config(JournalWorld.MAX, JournalWorld.MEMORY_TIER, JournalWorld.MEMORY_BUDGET, JournalWorld.DURABLE_MAX), restart);
        SegmentSweeper restarted = sweeper(a);

        assertEquals(Set.of(), sweep(restarted), "at the start: sealed 30 min ago by its trailer, but the grace counts from the start");
        a.opened.clock().advance(GRACE.minusMillis(1));
        assertEquals(Set.of(), sweep(restarted), "1 ms less than the grace after the start: no candidate");
        kept(a, before.segment(), "inside the grace after the restart");
        a.opened.clock().advance(Duration.ofMillis(1));
        Set<Long> swept = sweep(restarted);
        assertTrue(swept.contains(before.segment()), "the grace after the start: swept " + swept);
        assertTrue(swept.stream().allMatch(n -> n <= highestBefore), "only segments of the journal before the restart (the new ones are open): " + swept);
        gone(a, before.segment(), "the grace after the restart");
    }

    @Test
    void passesRunEveryIntervalOnTheInjectedTimer() throws Exception {
        Sealed s = saved(a, "m-interval", false);
        sql("delete from work where id = $1", s.id());
        a.opened.clock().advance(GRACE);
        FakeTimer timer = new FakeTimer(a.opened.clock());
        SegmentSweeper sweeper = new SegmentSweeper(a.journal, new PgSegmentRows(db.pool()), a.intake::openSaves, CONFIG, a.opened.clock(), timer);
        closing.add(sweeper);

        sweeper.start();
        assertEquals(1, timer.pending(), "start schedules the first pass");
        assertEquals(INTERVAL, timer.nextDelay(), "FLOTTILA_JOURNAL_SWEEP_INTERVAL later");
        kept(a, s.segment(), "no pass before the interval");

        a.opened.clock().advance(INTERVAL);
        until(() -> rows("node-a", s.segment()) == 0, () -> "the pass the timer ran swept segment " + s.segment());
        gone(a, s.segment(), "the timer's pass");
        until(() -> timer.pending() == 1, () -> "the pass scheduled the next: " + timer.pending());
        assertEquals(INTERVAL, timer.nextDelay(), "an interval later");
    }

    // --- the open-save set and in-doubt commits

    @Test
    void aSaveCommittingAfterTheOpenSetReadKeepsItsSegment() throws Exception {
        Sealed free = saved(a, "m-free", false);
        sql("delete from work where id = $1", free.id());
        PgWire.Held commit = wire.holdNext(PgWire.query("COMMIT"));
        Envelope racing = a.arrive("m-racing", bytes(3_000, 77));
        Future<String> save = a.start(racing, false, undecided());
        commit.awaitReached();
        List<JournalRef> open = List.copyOf(a.intake.openSaves());
        assertEquals(1, open.size(), "fixture: the save is open (its COMMIT held): " + open);
        long n = open.get(0).segment();
        seal(a, n);
        a.opened.clock().advance(GRACE);

        AtomicReference<Collection<JournalRef>> read = new AtomicReference<>();
        SegmentSweeper sweeper = sweeper(a, () -> {
            // the save commits while the pass reads the set: the set it reads no longer holds it
            commit.release();
            assertTrue(settled(save).succeeded(), "fixture: committed " + (save.failed() ? causes(save.cause()) : ""));
            Collection<JournalRef> now = List.copyOf(a.intake.openSaves());
            read.set(now);
            return now;
        });

        assertEquals(Set.of(free.segment()), sweep(sweeper), "the pins are asked after the set is read: the committed work row pins");
        assertEquals(List.of(), List.copyOf(read.get()), "fixture: the set read held no open save — only the later snapshot kept it");
        kept(a, n, "a save committed after the open-set read");
        Future<Buffer> body = settled(a.blobs.read(racing.body().key()));
        assertTrue(body.succeeded(), "its body reads: " + (body.failed() ? causes(body.cause()) : ""));
        assertEquals(bytes(3_000, 77), body.result());
        gone(a, free.segment(), "the unpinned segment of the same pass");
    }

    @Test
    void anUnknownCommitOutcomeKeepsItsSegmentUntilItsXactStatusIsKnown() throws Exception {
        Sealed free = saved(a, "m-free", false);
        sql("delete from work where id = $1", free.id());

        PgWire.Held lost = wire.holdNext(PgWire.query("COMMIT"));
        Future<String> aborted = a.start(a.arrive("m-aborted", bytes(3_000, 81)), false, undecided());
        lost.awaitReached();
        lost.cut();   // the COMMIT never reaches the server, the client's connection is gone: the outcome is unknown
        assertTrue(settled(aborted).failed(), "the save whose commit reply was lost fails");
        List<JournalRef> openAfterLoss = List.copyOf(a.intake.openSaves());
        assertEquals(1, openAfterLoss.size(), "it stays in the open-save set: " + openAfterLoss);
        long abortedSegment = openAfterLoss.get(0).segment();
        seal(a, abortedSegment);

        // P2-16a (D-121 A3): the cut save's transaction, open until lost.abort(), holds K-1's lock: the second goes under K-2
        PgWire.Held landed = wire.holdNext(PgWire.query("COMMIT"));
        Future<String> committed = startUnder(a.arrive("m-committed", bytes(3_000, 82)), "K-2");
        landed.awaitReached();
        landed.cut();
        assertTrue(settled(committed).failed(), "fixture: its reply lost too");
        List<JournalRef> bothOpen = List.copyOf(a.intake.openSaves());
        assertEquals(2, bothOpen.size(), "fixture: two saves of unknown outcome: " + bothOpen);
        long committedSegment = bothOpen.stream().mapToLong(JournalRef::segment).filter(x -> x != abortedSegment).findFirst().orElseThrow();
        seal(a, committedSegment);
        a.opened.clock().advance(GRACE);
        SegmentSweeper sweeper = sweeper(a);

        assertEquals(Set.of(free.segment()), sweep(sweeper), "both transactions in progress: their outcome unknown, their segments kept");
        assertEquals(Set.of(), sweep(sweeper), "and kept by a second pass while still unknown");
        kept(a, abortedSegment, "an unknown outcome");
        kept(a, committedSegment, "an unknown outcome");

        lost.abort();          // the server's session closes without the COMMIT: rolled back
        landed.commitAfterCut();   // the COMMIT reaches the server: committed
        until(() -> count(db.pool(), "select count(*) from work where id = 'm-committed'") == 1
                        && count(db.pool(), "select count(*) from pg_stat_activity where datname = current_database() and state like 'idle in transaction%'") == 0,
                () -> "the committed save visible and the aborted session gone");

        assertEquals(Set.of(abortedSegment), sweep(sweeper), "aborted: nothing pins its record; committed: its work row pins");
        gone(a, abortedSegment, "an aborted outcome, known");
        kept(a, committedSegment, "a committed outcome, known");
        assertEquals(0, count(db.pool(), "select count(*) from work where id = 'm-aborted'"), "fixture: the aborted save left no row");
        assertEquals(1, count(db.pool(), "select count(*) from work where id = 'm-committed'"), "fixture: the committed one landed");
        assertEquals(List.of(), List.copyOf(a.intake.openSaves()), "both outcomes known: no open save left");
    }

    // --- a status lookup that fails (FIX-145): it pins only its own save, the sweep goes on

    /** A statement asking {@code pg_xact_status}, as executed (extended protocol) or as a simple query. */
    static final java.util.function.Predicate<PgWire.Sent> XACT_LOOKUP = s -> (s.type() == 'E' || s.type() == 'Q')
            && s.sql() != null && s.sql().contains("pg_xact_status");

    /** A save whose COMMIT reply was cut: failed, open, its body local, its segment sealed; {@code held} decides its outcome. */
    record InDoubtSave(PgWire.Held held, BlobKey key, JournalRef ref) { }

    /**
     * {@code id}'s save under partition key {@code partition}, its COMMIT reply cut. P2-16a (D-121 A3): its transaction, open until
     * the case decides it, holds the key's lock, so a second in-doubt save of the same case goes under another key.
     */
    InDoubtSave inDoubtSave(String id, int seed, String partition) throws Exception {
        Set<JournalRef> before = Set.copyOf(a.blobs.openSaves());
        PgWire.Held held = wire.holdNext(PgWire.query("COMMIT"));
        Envelope in = a.arrive(id, bytes(3_000, seed));
        Future<String> save = startUnder(in, partition);
        held.awaitReached();
        held.cut();
        assertTrue(settled(save).failed(), "fixture: the save whose commit reply was lost fails");
        List<JournalRef> added = a.blobs.openSaves().stream().filter(r -> !before.contains(r)).toList();
        assertEquals(1, added.size(), "fixture: it stays open, by its xid: " + added);
        seal(a, added.get(0).segment());
        return new InDoubtSave(held, in.body().key(), added.get(0));
    }

    /** {@code e}'s save on node a under partition key {@code partition}, no dedupe key, not waited for. */
    Future<String> startUnder(Envelope e, String partition) {
        return a.intake.save(e, JournalWorld.ingress(e), JournalWorld.FLOW, partition, null, false, undecided());
    }

    /** A transaction id this server has not assigned yet: {@code pg_xact_status} refuses it (SQLSTATE 22023), checked here. */
    String futureXid() {
        String xid = db.pool().query("select (pg_current_xact_id()::text::bigint + 1000000)::text").execute().await()
                .iterator().next().getString(0);
        Future<?> asked = settled(db.pool().preparedQuery("select pg_xact_status($1::text::xid8)").execute(Tuple.of(xid)));
        assertTrue(asked.failed(), "fixture: Postgres refuses the status of the future xid " + xid);
        assertEquals("22023", assertInstanceOf(io.vertx.pgclient.PgException.class, asked.cause()).getSqlState(),
                "fixture: refused as a future transaction id: " + asked.cause());
        return xid;
    }

    /** A save forced and open, its segment sealed, registered in doubt under {@code xid} (the {@code PgIntake.inDoubt} seam). */
    InDoubtSave refusedSave(String id, int seed, String xid) throws Exception {
        Envelope in = a.arrive(id, bytes(3_000, seed));
        Future<TieredBlobStore.Durable> forced = settled(a.blobs.forced(in.body().key()));
        assertTrue(forced.succeeded(), "fixture: forced " + (forced.failed() ? causes(forced.cause()) : ""));
        JournalRef ref = forced.result().ref();
        assertNotNull(ref, "fixture: a local body forced has a ref of its own");
        seal(a, ref.segment());
        io.flottila.runtime.store.InDoubtSeam.register(a.intake, xid, List.of(forced.result()));
        return new InDoubtSave(null, in.body().key(), ref);
    }

    /** The resolved sessions gone and {@code committed}'s row visible: the outcomes are known to Postgres. */
    void outcomesKnown(String committed) {
        until(() -> count(db.pool(), "select count(*) from work where id = $1", committed) == 1
                        && count(db.pool(), "select count(*) from pg_stat_activity where datname = current_database() and state like 'idle in transaction%'") == 0,
                () -> "the landed COMMIT of " + committed + " visible and every cut session gone");
    }

    /** The {@code pg_xact_status} lookups the intake executed since {@code mark}. */
    long lookupsSince(int mark) {
        return wire.since(mark).stream().filter(XACT_LOOKUP).count();
    }

    /** The WARNING records of every Flottila logger, each as its formatted message and its causes' messages. */
    static final class Warnings implements AutoCloseable {
        private final java.util.logging.Logger logger = java.util.logging.Logger.getLogger("io.flottila");   // strong: JUL holds it weakly
        private final List<String> texts = new CopyOnWriteArrayList<>();
        private final java.util.logging.Handler handler = new java.util.logging.Handler() {
            private final java.util.logging.SimpleFormatter format = new java.util.logging.SimpleFormatter();

            @Override public void publish(java.util.logging.LogRecord r) {
                if (!r.getLevel().equals(java.util.logging.Level.WARNING)) return;
                StringBuilder text = new StringBuilder(r.getLoggerName()).append(": ").append(format.formatMessage(r));
                for (Throwable t = r.getThrown(); t != null; t = t.getCause()) text.append(" | ").append(t);
                texts.add(text.toString());
            }
            @Override public void flush() { }
            @Override public void close() { }
        };

        Warnings() {
            handler.setLevel(java.util.logging.Level.ALL);
            logger.addHandler(handler);
        }

        List<String> all() { return List.copyOf(texts); }

        List<String> naming(String xid) {
            Pattern whole = Pattern.compile("(?<![0-9])" + Pattern.quote(xid) + "(?![0-9])");
            return texts.stream().filter(t -> whole.matcher(t).find()).toList();
        }

        @Override public void close() { logger.removeHandler(handler); }
    }

    @Test
    void futureXidDoesNotBlockUnrelatedSegmentCollection() throws Exception {
        Sealed free = saved(a, "m-free", false);
        sql("delete from work where id = $1", free.id());
        String xid = futureXid();
        InDoubtSave refused = refusedSave("m-refused", 91, xid);
        assertFalse(refused.ref().segment() == free.segment(), "fixture: the refused save and the unrelated body in segments of their own");
        a.opened.clock().advance(GRACE);
        SegmentSweeper sweeper = sweeper(a);

        try (Warnings warnings = new Warnings()) {
            assertEquals(Set.of(free.segment()), sweep(sweeper), "a refused xid pins only its own save: the unrelated segment is swept");
            gone(a, free.segment(), "the unrelated segment");
            kept(a, refused.ref().segment(), "the save whose xid Postgres refuses");
            assertEquals(List.of(refused.ref()), a.blobs.openSaves(), "neither committed nor rolled back: the refused save is still open");
            assertTrue(a.blobs.location(refused.key()).isPresent(), "not committed: its body stays local");
            List<String> first = warnings.naming(xid);
            assertEquals(1, first.size(), "one WARNING names the xid the first time its lookup fails: " + warnings.all());
            assertTrue(first.get(0).contains("22023") || first.get(0).contains("in the future"),
                    "the WARNING carries Postgres's refusal (SQLSTATE or message): " + first.get(0));

            int mark = wire.mark();
            assertEquals(Set.of(), sweep(sweeper), "a second pass: still pinned, nothing else to sweep");
            assertTrue(lookupsSince(mark) >= 1, "fixture: the second pass asked the xid's status again: " + wire.since(mark));
            kept(a, refused.ref().segment(), "the refused save, a second pass");
            assertEquals(List.of(refused.ref()), a.blobs.openSaves(), "still open after the second pass");
            assertEquals(1, warnings.naming(xid).size(), "a second failing lookup logs no second WARNING for the xid: " + warnings.all());
        }
    }

    @Test
    void aFailingStatusQueryLeavesEveryInDoubtSaveOpenAndTheSweepRuns() throws Exception {
        Sealed free = saved(a, "m-free", false);
        sql("delete from work where id = $1", free.id());
        InDoubtSave landed = inDoubtSave("m-landed", 92, "K-1");
        InDoubtSave lost = inDoubtSave("m-lost", 93, "K-2");   // landed's open transaction holds K-1's lock
        landed.held().commitAfterCut();   // committed: a lookup that answered would settle it as committed
        lost.held().abort();              // rolled back: a lookup that answered would let its segment go
        outcomesKnown("m-landed");
        Set<JournalRef> both = Set.of(landed.ref(), lost.ref());
        assertEquals(3, Set.of(free.segment(), landed.ref().segment(), lost.ref().segment()).size(), "fixture: three segments of their own");
        a.opened.clock().advance(GRACE);
        SegmentSweeper sweeper = sweeper(a);

        try (Warnings warnings = new Warnings()) {
            List<PgWire.FatalReply> asked = List.of(wire.fatalReplyTo(XACT_LOOKUP), wire.fatalReplyTo(XACT_LOOKUP), wire.fatalReplyTo(XACT_LOOKUP));
            Collection<JournalRef> open = OnVirtualThread.call(() -> a.intake.openSaves());   // throws today: the batched error escapes
            for (PgWire.FatalReply f : asked) f.awaitDone();   // the batched lookup, then each xid's own: every one failed
            assertEquals(both, Set.copyOf(open), "every lookup failed: openSaves answers every in-doubt save, none settled");
            assertEquals(2, warnings.all().size(), "one WARNING per in-doubt xid whose lookup failed: " + warnings.all());

            List<PgWire.FatalReply> again = List.of(wire.fatalReplyTo(XACT_LOOKUP), wire.fatalReplyTo(XACT_LOOKUP), wire.fatalReplyTo(XACT_LOOKUP));
            assertEquals(Set.of(free.segment()), sweep(sweeper), "every lookup failing: the pass sweeps the unrelated segment");
            for (PgWire.FatalReply f : again) f.awaitDone();
            gone(a, free.segment(), "the unrelated segment");
            kept(a, lost.ref().segment(), "an in-doubt save whose lookup failed (only its open save pins it)");
            assertEquals(both, Set.copyOf(a.blobs.openSaves()), "every in-doubt save still open after the pass");
            assertTrue(a.blobs.location(landed.key()).isPresent(), "not settled as committed: its body stays local");
            assertEquals(2, warnings.all().size(), "a second failing pass: no second WARNING for either xid: " + warnings.all());
            int warned = warnings.all().size();

            int mark = wire.mark();
            assertEquals(Set.of(lost.ref().segment()), sweep(sweeper), "the lookups answer again: the rolled-back save's segment goes");
            assertEquals(1, lookupsSince(mark), "no lookup failed: one batched lookup, no per-xid one: " + wire.since(mark));
            assertEquals(List.of(), a.blobs.openSaves(), "both outcomes known: no open save left");
            assertTrue(a.blobs.location(landed.key()).isEmpty(), "committed: its local copy is gone");
            assertTrue(a.blobs.location(lost.key()).isPresent(), "rolled back: its body stays local");
            assertEquals(warned, warnings.all().size(), "a later success settles silently: " + warnings.all());
        }
    }

    @Test
    void oneBadXidDoesNotHoldBackAResolvableOne() throws Exception {
        InDoubtSave landed = inDoubtSave("m-landed", 94, "K-1");
        landed.held().commitAfterCut();
        outcomesKnown("m-landed");
        InDoubtSave refused = refusedSave("m-refused", 95, futureXid());
        assertFalse(refused.ref().segment() == landed.ref().segment(), "fixture: the two saves in segments of their own");
        a.opened.clock().advance(GRACE);
        SegmentSweeper sweeper = sweeper(a);

        assertEquals(Set.of(), sweep(sweeper), "the committed save's work row and the refused save's open save pin their segments");
        assertEquals(List.of(refused.ref()), a.blobs.openSaves(), "the same pass settled the resolvable save; the refused one stays open");
        assertTrue(a.blobs.location(landed.key()).isEmpty(), "settled as committed (not rolled back): its local copy is gone");
        assertTrue(a.blobs.location(refused.key()).isPresent(), "the refused save neither committed nor rolled back: its body stays local");
        kept(a, refused.ref().segment(), "the refused save");
        kept(a, landed.ref().segment(), "the committed save");
    }

    // --- what pins

    @Test
    void aWorkRowAlonePins() throws Exception {
        Sealed work = saved(a, "m-work", false);
        Sealed done = saved(a, "m-done", false);
        Sealed lost = saved(a, "m-lost", false);
        assertNull(bodyRef(db.pool(), work.key()), "fixture: level none: no kept ref, the work row alone");
        sql("delete from work where id = $1", done.id());
        sql("update work set lost_at = now() where id = $1", lost.id());
        a.opened.clock().advance(GRACE);

        assertEquals(Set.of(done.segment(), lost.segment()), sweep(sweeper(a)), "a work row pins; a completed or a lost one does not");
        kept(a, work.segment(), "a work row's ref");
        gone(a, done.segment(), "no row");
        gone(a, lost.segment(), "a lost work row");
    }

    @Test
    void aHeldWorkRowAlonePins() throws Exception {
        Sealed held = saved(a, "m-held", false);
        Sealed retrying = saved(a, "m-retrying", false);
        Sealed leased = saved(a, "m-leased", false);
        Sealed done = saved(a, "m-done", false);
        sql("update work set held_at = now() where id = $1", held.id());
        sql("update work set attempts = 3, available_at = now() + interval '1 day' where id = $1", retrying.id());
        sql("update work set locked_by = 'node-a', locked_until = now() + interval '1 hour' where id = $1", leased.id());
        sql("delete from work where id = $1", done.id());
        a.opened.clock().advance(GRACE);

        assertEquals(Set.of(done.segment()), sweep(sweeper(a)), "held, awaiting a retry and leased rows pin");
        kept(a, held.segment(), "a held work row");
        kept(a, retrying.segment(), "a work row awaiting its retry");
        kept(a, leased.segment(), "a leased work row");
    }

    /**
     * P2-15a (D-101): a work row in doubt — handed out again after its holder's node was released ({@code doubts} > 0, V14) — and
     * one a restarted node reruns only after its old fence ({@code available_at} ahead) pin their segments alone, as any work row.
     */
    @Test
    void anInDoubtWorkRowAlonePins() throws Exception {
        Sealed doubt = saved(a, "m-doubt", false);
        Sealed rerun = saved(a, "m-rerun", false);
        Sealed done = saved(a, "m-done", false);
        sql("insert into nodes(node, epoch, lease_until) values ('node-gone', 3, null)");   // a released node
        sql("update work set attempts = 2, doubts = 1, locked_by = 'node-gone', locked_until = now() - interval '1 minute' where id = $1", doubt.id());
        sql("update work set attempts = 1, locked_by = 'node-a', locked_until = now() - interval '1 minute', available_at = now() + interval '15 seconds'"
                + " where id = $1", rerun.id());
        sql("delete from work where id = $1", done.id());
        a.opened.clock().advance(GRACE);

        assertEquals(Set.of(done.segment()), sweep(sweeper(a)), "an in-doubt row and a row awaiting its old fence pin; a completed one does not");
        kept(a, doubt.segment(), "an in-doubt work row");
        kept(a, rerun.segment(), "a work row awaiting its node's old fence");
        gone(a, done.segment(), "no row");
    }

    @Test
    void aLargeStepBodyPersistedPastTheGraceKeepsItsSegment() throws Exception {
        Sealed free = saved(a, "m-free", false);
        sql("delete from work where id = $1", free.id());
        BlobKey large = BlobKey.body("m-large", 2);
        Buffer content = bytes(300_000, 5);   // above RECORD_MAX (256 KiB): a spill, its own segment
        assertTrue(settled(a.blobs.write(large, content, JournalWorld.MAX)).succeeded(), "fixture: written");
        a.opened.clock().advance(GRACE.plusMinutes(1));   // the spill was started more than the grace ago
        Set<Long> rowsBefore = segmentRows("node-a");
        PgWire.Held ref = wire.holdNext(s -> (s.type() == 'Q' || s.type() == 'P') && s.sql() != null
                && s.sql().toLowerCase(java.util.Locale.ROOT).contains("insert into body_refs"));

        Future<Void> persisted = a.blobs.persist(large);
        ref.awaitReached();   // moved, its row in, sealed now; its kept ref not yet committed
        Set<Long> added = new HashSet<>(segmentRows("node-a"));
        added.removeAll(rowsBefore);
        assertEquals(1, added.size(), "fixture: the persist inserted its segment's row: " + added);
        long n = added.iterator().next();
        assertTrue(sealed(a, n), "fixture: the persisted large segment is sealed");

        assertEquals(Set.of(free.segment()), sweep(sweeper(a)), "sealed at its persist, not at its spill's start: inside the grace");
        kept(a, n, "a large body persisted after the grace, its ref not yet committed");

        ref.release();
        assertTrue(settled(persisted).succeeded(), "the persist completes: " + (persisted.failed() ? causes(persisted.cause()) : ""));
        assertEquals(n, bodyRef(db.pool(), large).segment(), "its kept ref names that segment");
        Future<Buffer> read = settled(a.restartedStore().read(large));
        assertTrue(read.succeeded(), "its body reads from the journal: " + (read.failed() ? causes(read.cause()) : ""));
        assertEquals(content, read.result());
    }

    Set<Long> segmentRows(String node) {
        Set<Long> out = new HashSet<>();
        db.pool().preparedQuery("select segment from journal_segments where node = $1").execute(Tuple.of(node)).await().forEach(r -> out.add(r.getLong(0)));
        return out;
    }

    @Test
    void aDlqRowAlonePins() throws Exception {
        Sealed parked = persisted(a, "m-parked");
        Sealed unparked = persisted(a, "m-unparked");
        Sealed archived = persisted(a, "m-archived");
        Sealed lost = persisted(a, "m-lost");
        parked(parked);
        parked(archived);
        parked(lost);
        a.opened.clock().advance(GRACE);
        Instant old = a.opened.clock().instant().minus(KEEP).minus(Duration.ofMinutes(1));
        for (Sealed s : List.of(parked, unparked, archived, lost)) createdAt(s.key(), old);   // the kept refs alone pin no more
        sql("update body_refs set archived_at = now() where message_id = $1", archived.id());
        sql("update body_refs set lost_at = now() where message_id = $1", lost.id());

        assertEquals(Set.of(unparked.segment(), archived.segment(), lost.segment()), sweep(sweeper(a)),
                "a DLQ row pins through its unarchived, not lost ref, whatever its age");
        kept(a, parked.segment(), "a DLQ row's body");
        gone(a, unparked.segment(), "an old ref without a DLQ row");
        gone(a, archived.segment(), "a DLQ row whose ref is archived");
        gone(a, lost.segment(), "a DLQ row whose ref is lost");
    }

    @Test
    void anUnarchivedRefAlonePins() throws Exception {
        Sealed kept = persisted(a, "m-kept");
        Sealed unreferenced = persisted(a, "m-unreferenced");
        sql("delete from body_refs where message_id = $1", unreferenced.id());
        a.opened.clock().advance(GRACE);
        createdAt(kept.key(), a.opened.clock().instant().minus(Duration.ofMinutes(1)));

        assertEquals(Set.of(unreferenced.segment()), sweep(sweeper(a)), "a kept ref younger than KEEP_MAX_AGE pins its segment alone");
        kept(a, kept.segment(), "an unarchived kept ref");
        assertEquals(0, count(db.pool(), "select count(*) from work where id = $1", kept.id()), "fixture: no work row");
    }

    @Test
    void anArchivedRefNoLongerPins() throws Exception {
        Sealed archived = persisted(a, "m-archived");
        Sealed unarchived = persisted(a, "m-unarchived");
        a.opened.clock().advance(GRACE);
        Instant recent = a.opened.clock().instant().minus(Duration.ofMinutes(1));
        createdAt(archived.key(), recent);
        createdAt(unarchived.key(), recent);
        sql("update body_refs set archived_at = now() where message_id = $1", archived.id());

        assertEquals(Set.of(archived.segment()), sweep(sweeper(a)), "an archived ref pins nothing; its unarchived twin pins");
        gone(a, archived.segment(), "an archived ref");
        kept(a, unarchived.segment(), "its unarchived twin");
        assertEquals(1, count(db.pool(), "select count(*) from body_refs where message_id = $1", archived.id()), "the ref's row stays");
    }

    @Test
    void aRefOlderThanKeepMaxAgeNoLongerPins() throws Exception {
        Sealed atTheAge = persisted(a, "m-at-the-age");
        Sealed younger = persisted(a, "m-younger");
        a.opened.clock().advance(GRACE);
        Instant now = a.opened.clock().instant();
        createdAt(atTheAge.key(), now.minus(KEEP));                       // created_at = clock − KEEP_MAX_AGE: not after it
        createdAt(younger.key(), now.minus(KEEP).plusMillis(1));          // 1 ms after it: pins

        assertEquals(Set.of(atTheAge.segment()), sweep(sweeper(a)), "a ref pins while created_at > clock − KEEP_MAX_AGE (the injected clock)");
        gone(a, atTheAge.segment(), "a ref KEEP_MAX_AGE old");
        kept(a, younger.segment(), "a ref 1 ms younger");
        assertEquals(1, count(db.pool(), "select count(*) from body_refs where message_id = $1", atTheAge.id()), "its rows stay (D-096)");
    }

    // --- the pins of a restart agree with the sweep's (FIX-140, F-29)

    static final Pattern ACCEPT_VALUE = Pattern.compile("FLOTTILA_JOURNAL_ACCEPT_LOSS=([0-9a-f-]{36}):([0-9a-f]{16})");

    /** {@code s}'s kept ref, created when it was persisted on the injected clock, then that clock past KEEP_MAX_AGE and the grace. */
    void expired(JournalWorld w, Sealed s, Instant persistedAt) {
        createdAt(s.key(), persistedAt);   // the server wrote its own now(); the pins compare with the injected clock
        w.opened.clock().advance(KEEP.plus(GRACE));
        assertTrue(w.opened.clock().instant().minus(KEEP).isAfter(persistedAt), "fixture: the injected clock is past KEEP_MAX_AGE since the persist");
    }

    /** Closes node {@code w} (its sweepers first) and starts it again on the same directory and database, its clock where it was. */
    JournalWorld restarted(JournalWorld w) throws Exception {
        for (AutoCloseable c : closing) if (c instanceof SegmentSweeper s) s.close();
        Instant at = w.opened.clock().instant();
        if (w == a) {   // still open: close it; a world a case closed already is not closed again
            a = null;
            w.close();
        }
        a = JournalWorld.open(vertx, relayed, db.pool(), tmp.resolve(w.node), w.node, s -> { }, w.config, at);
        return a;
    }

    UUID journalId(String node) {
        return db.pool().preparedQuery("select journal_id from journal_nodes where node = $1").execute(Tuple.of(node)).await().iterator().next().getUUID(0);
    }

    /** {@code id}'s kept ref is neither archived nor lost, and no loss was recorded. */
    void refUntouched(String id, String what) {
        assertEquals(1, count(db.pool(), "select count(*) from body_refs where message_id = $1 and archived_at is null and lost_at is null", id),
                what + ": its body_refs row stays, unarchived, not lost");
        assertEquals(0, count(db.pool(), "select count(*) from journal_losses"), what + ": no loss recorded");
    }

    /** A restart of {@code w} is refused naming {@code s}'s kept ref and printing {@code <journal id>:<digest>}; nothing changes. */
    void restartRefusedNaming(JournalWorld w, Sealed s, String what) {
        UUID journal = journalId(w.node);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> restarted(w), what + ": the restart is refused");
        String message = String.valueOf(e.getMessage());
        assertTrue(message.contains("does not hold every record"), what + ": refused as a pin beyond the held records: " + message);
        assertTrue(message.contains("ref " + s.id() + "/" + s.key().step() + "/") && message.contains(" at segment " + s.segment() + " "),
                what + ": it names the kept ref into segment " + s.segment() + ": " + message);
        Matcher m = ACCEPT_VALUE.matcher(message);
        assertTrue(m.find(), what + ": it prints FLOTTILA_JOURNAL_ACCEPT_LOSS=<journal id>:<digest>: " + message);
        assertEquals(journal.toString(), m.group(1), what + ": the value names the registered journal");
        assertEquals(1, rows(w.node, s.segment()), what + ": its segment row stays (a refusal changes nothing)");
        refUntouched(s.id(), what);
    }

    /**
     * F-29: an expired kept ref (created_at ≤ clock − KEEP_MAX_AGE, no DLQ row) is swept — its segment row and file go, its
     * body_refs row lives on until retention (D-096) — and the next start goes on: the ref reads as nothing stored (retired by
     * the sweep), never as a loss to accept.
     */
    @Test
    void aNormallySweptKeptRefDoesNotRefuseRestart() throws Exception {
        Instant persistedAt = a.opened.clock().instant();
        Sealed s = persisted(a, "m-expired");
        expired(a, s, persistedAt);

        assertEquals(Set.of(s.segment()), sweep(sweeper(a)), "fixture: the expired kept ref pins nothing; its segment is swept");
        gone(a, s.segment(), "the expired ref's segment");
        refUntouched(s.id(), "fixture: after the sweep");

        JournalWorld b;
        try {
            b = restarted(a);
        } catch (IllegalStateException refused) {
            throw new AssertionError("a routine sweep of an expired kept ref must not refuse the next start, but: " + refused.getMessage(), refused);
        }
        assertEquals(0, rows(b.node, s.segment()), "the swept segment stays unregistered");
        refUntouched(s.id(), "after the restart: nothing given up");
        JournalReadIT.readsAsNeverWritten(b.blobs, s.key(), "an expired kept ref into a swept segment, after the restart");
    }

    /** Control: the same expired kept ref, its segment row still registered but its file gone — a loss: the restart refuses. */
    @Test
    void aRegisteredSegmentWhoseFileVanishedStillRefuses() throws Exception {
        Instant persistedAt = a.opened.clock().instant();
        Sealed s = persisted(a, "m-vanished");
        expired(a, s, persistedAt);
        a.close();
        JournalWorld closed = a;
        a = null;
        assertEquals(1, rows(closed.node, s.segment()), "fixture: its segment row is registered (never swept)");
        Files.delete(file(closed, s.segment()));

        restartRefusedNaming(closed, s, "a registered segment whose file vanished");
    }

    /**
     * FIX-140: a work row pins at restart whether or not its segment row is registered — unlike a kept ref, a work row is never
     * retired by the sweep. Its segment row and file gone (no sweep deletes them under a live work row), the restart refuses
     * naming the work item; nothing changes.
     */
    @Test
    void aWorkRowWhoseSegmentRowIsGoneStillRefuses() throws Exception {
        Sealed s = saved(a, "m-work", false);
        assertNull(bodyRef(db.pool(), s.key()), "fixture: level none: no kept ref, the work row alone");
        a.close();
        JournalWorld closed = a;
        a = null;
        sql("delete from journal_segments where node = $1 and segment = $2", closed.node, s.segment());
        Files.delete(file(closed, s.segment()));
        assertEquals(0, rows(closed.node, s.segment()), "fixture: its segment row is gone");
        UUID journal = journalId(closed.node);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> restarted(closed), "a work row's pin refuses the restart");
        String message = String.valueOf(e.getMessage());
        assertTrue(message.contains("does not hold every record"), "refused as a pin beyond the held records: " + message);
        assertTrue(message.contains("work item ") && message.contains(" of message " + s.id() + " at segment " + s.segment() + " "),
                "it names the work item into segment " + s.segment() + ": " + message);
        Matcher m = ACCEPT_VALUE.matcher(message);
        assertTrue(m.find(), "it prints FLOTTILA_JOURNAL_ACCEPT_LOSS=<journal id>:<digest>: " + message);
        assertEquals(journal.toString(), m.group(1), "the value names the registered journal");
        assertEquals(1, count(db.pool(), "select count(*) from work where id = $1 and lost_at is null", s.id()), "its work row stays, not lost");
        assertEquals(0, count(db.pool(), "select count(*) from journal_losses"), "no loss recorded");
    }

    /**
     * Control: an expired kept ref a DLQ row holds keeps its segment through the sweep (an unreferenced twin goes, the pass is
     * no no-op); a restart with its file there goes on and reads it; with the file deleted it refuses naming it.
     */
    @Test
    void aDlqHeldRefStillPinsItsSegment() throws Exception {
        Instant persistedAt = a.opened.clock().instant();
        Sealed held = persisted(a, "m-held");
        Sealed unreferenced = persisted(a, "m-unreferenced");
        sql("delete from body_refs where message_id = $1", unreferenced.id());
        parked(held);
        expired(a, held, persistedAt);

        assertEquals(Set.of(unreferenced.segment()), sweep(sweeper(a)), "a DLQ row pins its expired ref's segment; the unreferenced twin goes");
        kept(a, held.segment(), "the DLQ-held ref's segment");
        gone(a, unreferenced.segment(), "the unreferenced twin's segment");

        JournalWorld b;
        try {
            b = restarted(a);
        } catch (IllegalStateException refused) {
            throw new AssertionError("a DLQ-held ref whose segment is held must not refuse the start, but: " + refused.getMessage(), refused);
        }
        Future<Buffer> read = settled(b.blobs.read(held.key()));
        assertTrue(read.succeeded(), "after the restart the DLQ-held body reads by its rows: " + (read.failed() ? causes(read.cause()) : ""));
        assertEquals(bytes(3_000, held.id().hashCode()), read.result(), "the DLQ-held body, after the restart");
        refUntouched(held.id(), "after the restart");

        b.close();
        a = null;
        Files.delete(file(b, held.segment()));
        restartRefusedNaming(b, held, "a DLQ-held ref whose segment file vanished");
    }

    // --- whose segments

    @Test
    void anotherNodesSegmentIsUntouched() throws Exception {
        JournalWorld b = world("node-b", db.pool());
        closing.add(b);
        Sealed mine = saved(a, "m-a", false);
        Sealed theirs = saved(b, "m-b", false);
        sql("delete from work where id in ('m-a', 'm-b')");
        a.opened.clock().advance(GRACE);
        b.opened.clock().advance(GRACE);
        Set<Long> theirRows = segmentRows("node-b");
        assertTrue(theirRows.contains(mine.segment()), "fixture: node-b has a segment of node-a's swept number: " + theirRows);

        assertEquals(Set.of(mine.segment()), sweep(sweeper(a)), "node-a sweeps its own");
        gone(a, mine.segment(), "node-a's segment");
        assertEquals(theirRows, segmentRows("node-b"), "node-b's rows untouched, its segment of the same number included");
        assertTrue(Files.exists(file(b, theirs.segment())), "node-b's file untouched");

        assertEquals(Set.of(theirs.segment()), sweep(sweeper(b)), "witness: node-b's segment was sweepable, by node-b");
    }

    /**
     * FIX-140: a restart asks whether a kept ref's segment row is registered for the ref's own node. node-b registers the
     * number of node-a's swept segment in the same database; node-a's expired ref into it still pins nothing at node-a's start.
     */
    @Test
    void aSweptKeptRefDoesNotRefuseRestartWhileAnotherNodeRegistersThatNumber() throws Exception {
        JournalWorld b = world("node-b", db.pool());
        closing.add(b);
        Instant persistedAt = a.opened.clock().instant();
        Sealed mine = persisted(a, "m-a");
        Sealed theirs = persisted(b, "m-b");
        assertEquals(mine.segment(), theirs.segment(), "fixture: node-b's kept ref lies in a segment of node-a's number");
        expired(a, mine, persistedAt);

        assertEquals(Set.of(mine.segment()), sweep(sweeper(a)), "fixture: node-a's expired kept ref pins nothing; its segment is swept");
        gone(a, mine.segment(), "node-a's segment");
        assertEquals(1, rows(b.node, mine.segment()), "fixture: node-b's segment of the same number stays registered");

        JournalWorld restarted;
        try {
            restarted = restarted(a);
        } catch (IllegalStateException refused) {
            throw new AssertionError("node-b's segment row of the same number must not pin node-a's swept ref, but: " + refused.getMessage(), refused);
        }
        assertEquals(0, rows(restarted.node, mine.segment()), "node-a's swept segment stays unregistered");
        refUntouched(mine.id(), "node-a's ref after the restart: nothing given up");
        assertEquals(1, count(db.pool(), "select count(*) from body_refs where message_id = $1 and archived_at is null and lost_at is null", theirs.id()),
                "node-b's ref untouched");
        assertTrue(Files.exists(file(b, theirs.segment())), "node-b's file untouched");
    }

    // --- races and reads

    @Test
    void aReferenceInsertRacingTheSweepHoldsOrFindsNoBody() throws Exception {
        Sealed raced = persisted(a, "m-raced");      // swept first, its park racing
        Sealed holding = persisted(a, "m-holding");  // parked first, its park's lock held across a pass
        a.opened.clock().advance(GRACE);
        Instant old = a.opened.clock().instant().minus(KEEP).minus(Duration.ofMinutes(1));
        createdAt(raced.key(), old);
        createdAt(holding.key(), old);
        SegmentSweeper sweeper = sweeper(a);

        // the reference insert first: its CTE holds the segment row FOR KEY SHARE until it commits
        sql("create function hold_park() returns trigger language plpgsql as $$ begin perform pg_advisory_xact_lock(4343); return new; end $$");
        sql("create trigger hold_park after insert on dlq for each row execute function hold_park()");
        PgConnection gate = PgConnection.connect(vertx, db.options()).await();
        closing.add(() -> gate.close().await());
        gate.query("select pg_advisory_lock(4343)").execute().await();
        CompletableFuture<Void> first = parkOnAVirtualThread(PgDlqJournalIT.parked(holding.id(), 2));
        until(() -> waiters("advisory") == 1, () -> "the park inserted its DLQ row and holds its tx open");
        assertEquals(Set.of(raced.segment()), sweep(sweeper), "a segment row held FOR KEY SHARE is skipped (FOR UPDATE SKIP LOCKED); the other goes");
        kept(a, holding.segment(), "the segment a park holds");
        gone(a, raced.segment(), "fixture: the other segment, swept by this pass");
        gate.query("select pg_advisory_unlock(4343)").execute().await();
        first.get(20, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(Set.of(), sweep(sweeper), "once parked, its DLQ row pins");
        kept(a, holding.segment(), "a parked body");
        sql("drop trigger hold_park on dlq");

        // the sweep first: its delete holds the segment row; the park waits, then finds no source row and inserts nothing
        Sealed late = persisted(a, "m-late");
        a.opened.clock().advance(GRACE);
        createdAt(late.key(), a.opened.clock().instant().minus(KEEP).minus(Duration.ofMinutes(1)));
        sql("create function hold_sweep() returns trigger language plpgsql as $$ begin perform pg_advisory_xact_lock(4242); return old; end $$");
        sql("create trigger hold_sweep after delete on journal_segments for each row execute function hold_sweep()");
        gate.query("select pg_advisory_lock(4242)").execute().await();
        Future<Set<Long>> pass = sweeper.sweep();
        until(() -> waiters("advisory") == 1, () -> "the pass deleted the row and holds its tx open");
        CompletableFuture<Void> second = parkOnAVirtualThread(PgDlqJournalIT.parked(late.id(), 2));
        until(() -> lockWaiters() == 2, () -> "the park waits on the deleted row");
        gate.query("select pg_advisory_unlock(4242)").execute().await();
        Future<Set<Long>> passed = settled(pass);
        assertTrue(passed.succeeded(), "the pass completes: " + (passed.failed() ? causes(passed.cause()) : ""));
        assertEquals(Set.of(late.segment()), passed.result(), "the pass swept it");
        try {
            second.get(20, java.util.concurrent.TimeUnit.SECONDS);
            fail("the park after the sweep's delete must find no body");
        } catch (java.util.concurrent.ExecutionException e) {
            BlobNotFoundException nf = assertInstanceOf(BlobNotFoundException.class, e.getCause(), "no source row: BlobNotFound " + causes(e.getCause()));
            assertEquals(late.key(), nf.key(), "naming the body key");
        }
        assertEquals(0, count(db.pool(), "select count(*) from dlq where id = $1", late.id()), "it inserted nothing");
        gone(a, late.segment(), "the swept segment");
    }

    @Test
    void aSweptBodyReadsAsRemovedEvenFromTheIndex() throws Exception {
        Sealed s = saved(a, "m-swept", false);
        Future<Buffer> before = settled(a.blobs.read(s.key()));
        assertTrue(before.succeeded(), "fixture: it reads from the journal by its index entry first: " + before.cause());
        sql("delete from work where id = $1", s.id());
        a.opened.clock().advance(GRACE);

        assertEquals(Set.of(s.segment()), sweep(sweeper(a)));
        gone(a, s.segment(), "the swept segment");

        JournalReadIT.readsAsNeverWritten(a.blobs, s.key(), "the store whose location index still names the swept record");
        JournalReadIT.readsAsNeverWritten(a.restartedStore(), s.key(), "a store without the index entry");
    }

    /** Message {@code id}'s kept step body (its ref found by the rows) in a segment the journal holds, and its ref. */
    JournalRef keptInAHeldSegment(String id) throws Exception {
        Sealed s = persisted(a, id);
        JournalRef ref = null;
        for (io.vertx.sqlclient.Row r : db.pool().preparedQuery("select segment, position, length from body_refs where message_id = $1").execute(Tuple.of(id)).await()) {
            ref = new JournalRef(r.getLong(0), r.getLong(1), r.getLong(2));
        }
        assertTrue(ref != null && ref.segment() == s.segment(), "fixture: its ref names its segment " + ref);
        assertTrue(a.journal.holds(ref.segment()), "fixture: the journal holds the segment (not swept)");
        Future<Buffer> witness = settled(a.restartedStore().read(s.key()));
        assertTrue(witness.succeeded(), "witness: a store without the index entry reads it by its rows: " + (witness.failed() ? causes(witness.cause()) : ""));
        return ref;
    }

    /**
     * CORE-4d review r2 (CORE-3 item 9): a store without the index entry reads by the rows; a record with one byte flipped in a
     * segment the journal still holds fails {@link JournalCorruptException} — corrupt, never a {@link BlobNotFoundException}.
     */
    @Test
    void aCorruptRecordInAHeldSegmentReadByTheRowsIsCorruptNeverNotFound() throws Exception {
        JournalRef ref = keptInAHeldSegment("m-corrupt");
        BlobKey key = BlobKey.body("m-corrupt", 1);
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(file(a, ref.segment()), java.nio.file.StandardOpenOption.READ,
                java.nio.file.StandardOpenOption.WRITE)) {
            long at = ref.position() + ref.length() / 2;
            java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(1);
            ch.read(b, at);
            b.put(0, (byte) (b.get(0) ^ 0x01)).position(0);
            ch.write(b, at);
        }

        Future<Buffer> read = settled(a.restartedStore().read(key));
        assertTrue(read.failed(), "a flipped byte reads no body");
        assertInstanceOf(JournalCorruptException.class, read.cause(), "corrupt, never not found: " + causes(read.cause()));
        Future<Buffer> open = JournalWorld.opened(a.restartedStore(), key);
        assertInstanceOf(JournalCorruptException.class, open.cause(), "open too: " + (open.failed() ? causes(open.cause()) : "succeeded"));
    }

    /**
     * CORE-4d review r2, the twin: the record's tenant key deleted (shredded), a store without the index entry reading by the
     * rows fails {@link JournalKeyException}, never a {@link BlobNotFoundException}.
     */
    @Test
    void aRecordWhoseTenantKeyIsDeletedReadByTheRowsFailsJournalKeyNeverNotFound() throws Exception {
        keptInAHeldSegment("m-shredded");
        BlobKey key = BlobKey.body("m-shredded", 1);
        a.opened.deleteKey(a.opened.tenant());

        Future<Buffer> read = settled(a.restartedStore().read(key));
        assertTrue(read.failed(), "no key reads no body");
        assertInstanceOf(JournalKeyException.class, read.cause(), "a key failure, never not found: " + causes(read.cause()));
        Future<Buffer> open = JournalWorld.opened(a.restartedStore(), key);
        assertInstanceOf(JournalKeyException.class, open.cause(), "open too: " + (open.failed() ? causes(open.cause()) : "succeeded"));
    }

    /**
     * CORE-4d r1 (2): a read whose FIND answered before a sweep pass removed the segment — its row and its file — reads as
     * removed: a {@link BlobNotFoundException} naming the key, never an {@link java.io.IOException}. The FIND's reply (it ran,
     * saw the segment row: {@code live}) is held at the relay across the pass. {@code read} and {@code open} each get their own
     * held FIND. Witness: the same FIND unheld, before the pass, reads the body.
     */
    @Test
    void aSweepBetweenTheFindAndTheReadReadsAsRemoved() throws Exception {
        Sealed s = persisted(a, "m-find");
        a.opened.clock().advance(GRACE);
        sql("update body_refs set archived_at = now() where message_id = $1", s.id());   // found by FIND, pins nothing
        Future<Buffer> witness = settled(a.restartedStore().read(s.key()));
        assertTrue(witness.succeeded(), "witness: before the pass a store without the index entry reads it by its FIND: " + witness.cause());

        // its Execute: a Parse's reply (the prepare round trip of an uncached statement) comes before the FIND runs
        java.util.function.Predicate<PgWire.Sent> find = m -> m.type() == 'E' && m.sql() != null && m.sql().contains("select 0 as o, r.node");
        TieredBlobStore reading = a.restartedStore(), opening = a.restartedStore();
        PgWire.HeldReply readFind = wire.holdReplyTo(find);
        CompletableFuture<Future<Buffer>> read = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> read.complete(settled(reading.read(s.key()))));   // a read may await its FIND in the call
        readFind.awaitReached();
        PgWire.HeldReply openFind = wire.holdReplyTo(find);
        CompletableFuture<Future<Buffer>> open = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> open.complete(JournalWorld.opened(opening, s.key())));
        openFind.awaitReached();

        assertEquals(Set.of(s.segment()), sweep(sweeper(a)), "fixture: the pass sweeps the segment both FINDs saw");
        gone(a, s.segment(), "the swept segment");
        assertFalse(read.isDone() || open.isDone(), "fixture: both wait for their FIND's reply");
        readFind.release();
        openFind.release();

        JournalReadIT.refusedNotFound(read.get(30, java.util.concurrent.TimeUnit.SECONDS), s.key(), "a read whose FIND saw the segment before the pass (read)");
        JournalReadIT.refusedNotFound(open.get(30, java.util.concurrent.TimeUnit.SECONDS), s.key(), "a read whose FIND saw the segment before the pass (open)");
    }

    @Test
    void aSweptSegmentsChannelIsClosed() throws Exception {
        Sealed s = saved(a, "m-channel", false);
        Sealed other = saved(a, "m-other", false);
        assertTrue(settled(a.blobs.read(s.key())).succeeded(), "fixture: read once: its segment's channel is cached");
        assertTrue(settled(a.blobs.read(other.key())).succeeded(), "fixture: the other's too");
        Path f = file(a, s.segment());
        Path o = file(a, other.segment());
        assertEquals(1, a.files.readOpensOf(f) - a.files.readClosesOf(f), "fixture: one read channel open on " + f);
        assertEquals(1, a.files.readOpensOf(o) - a.files.readClosesOf(o), "fixture: one on " + o);
        sql("delete from work where id = $1", s.id());
        a.opened.clock().advance(GRACE);

        assertEquals(Set.of(s.segment()), sweep(sweeper(a)));
        until(() -> a.files.readOpensOf(f) == a.files.readClosesOf(f), () -> "the swept segment's cached channel is closed: opens "
                + a.files.readOpensOf(f) + ", closes " + a.files.readClosesOf(f));
        gone(a, s.segment(), "the swept segment");
        assertEquals(1, a.files.readOpensOf(o) - a.files.readClosesOf(o), "the kept segment's channel stays cached");
    }

    /**
     * CORE-4d carry (KEEP's conflict branch): a persist of a key whose kept ref exists already meets that row ({@code on conflict})
     * and, its segment row there, keeps it as it is; that branch holds the live row's segment row {@code for key share}, so a pass
     * deleting that segment row either goes first — the persist waits, then finds the row gone and puts its own record in place —
     * or skips it while the persist's transaction holds it. Never a ref left naming a swept segment.
     */
    @Test
    void aKeptRefConflictRacingTheSweepWaitsOrFindsNoBody() throws Exception {
        PgConnection gate = PgConnection.connect(vertx, db.options()).await();
        closing.add(() -> gate.close().await());
        SegmentSweeper sweeper = sweeper(a);

        // the sweep first: its delete of the live row's segment row held; the second persist of the key then meets the row
        Sealed first = persisted(a, "m-keep-swept-first");
        a.opened.clock().advance(GRACE);
        createdAt(first.key(), a.opened.clock().instant().minus(KEEP).minus(Duration.ofMinutes(1)));   // its ref alone pins no more
        Buffer firstBytes = bytes(3_000, first.id().hashCode());
        assertTrue(settled(a.blobs.write(first.key(), firstBytes, JournalWorld.MAX)).succeeded(), "fixture: written again (a local copy)");
        sql("create function hold_sweep() returns trigger language plpgsql as $$ begin perform pg_advisory_xact_lock(4242); return old; end $$");
        sql("create trigger hold_sweep after delete on journal_segments for each row execute function hold_sweep()");
        gate.query("select pg_advisory_lock(4242)").execute().await();
        Future<Set<Long>> pass = sweeper.sweep();
        until(() -> waiters("advisory") == 1, () -> "the pass deleted the segment row and holds its tx open");
        Future<Void> again = a.blobs.persist(first.key());
        until(() -> again.isComplete() || lockWaiters() == 2, () -> "the persist's KEEP waits on the deleted segment row, or completed");
        gate.query("select pg_advisory_unlock(4242)").execute().await();
        assertEquals(Set.of(first.segment()), settled(pass).result(), "fixture: the pass swept the live row's segment");
        settled(again);
        refNamesALiveSegment(first.key(), again, firstBytes, "the sweep first");
        sql("drop trigger hold_sweep on journal_segments");

        // the persist first: its KEEP's conflict branch held inside its transaction; a pass then meets the segment row it holds
        Sealed second = persisted(a, "m-keep-persisted-first");
        a.opened.clock().advance(GRACE);
        createdAt(second.key(), a.opened.clock().instant().minus(KEEP).minus(Duration.ofMinutes(1)));
        Buffer secondBytes = bytes(3_000, second.id().hashCode());
        assertTrue(settled(a.blobs.write(second.key(), secondBytes, JournalWorld.MAX)).succeeded(), "fixture: written again");
        sql("create function hold_keep() returns trigger language plpgsql as $$ begin perform pg_advisory_xact_lock(4343); return new; end $$");
        sql("create trigger hold_keep after update on body_refs for each row execute function hold_keep()");
        gate.query("select pg_advisory_lock(4343)").execute().await();
        Future<Void> keeping = a.blobs.persist(second.key());
        until(() -> waiters("advisory") == 1, () -> "the persist's KEEP met the row and holds its tx open");
        Set<Long> swept = sweep(sweeper);
        assertTrue(!swept.contains(second.segment()), "a segment row held FOR KEY SHARE by a KEEP is skipped: " + swept);
        kept(a, second.segment(), "the segment a KEEP holds");
        gate.query("select pg_advisory_unlock(4343)").execute().await();
        settled(keeping);
        sql("drop trigger hold_keep on body_refs");
        refNamesALiveSegment(second.key(), keeping, secondBytes, "the persist first");
    }

    /** {@code key}'s ref, if any, names a segment whose row is there; a persist that succeeded left its body readable. */
    void refNamesALiveSegment(BlobKey key, Future<Void> persist, Buffer content, String what) {
        JournalWorld.Ref ref = bodyRef(db.pool(), key);
        if (ref != null) assertEquals(1, rows(ref.node(), ref.segment()), what + ": the kept ref names a segment that is there: " + ref);
        if (persist.succeeded()) {
            Future<Buffer> read = settled(a.restartedStore().read(key));
            assertTrue(read.succeeded(), what + ": a persisted body reads: " + (read.failed() ? causes(read.cause()) : ""));
            assertEquals(content, read.result(), what);
        } else {
            assertInstanceOf(BlobNotFoundException.class, persist.cause(), what + ": or the persist finds no body: " + causes(persist.cause()));
        }
    }
}
