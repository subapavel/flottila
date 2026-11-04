package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.Envelope;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.flottila.runtime.journal.JournalWorld.bodyRef;
import static io.flottila.runtime.journal.JournalWorld.bytes;
import static io.flottila.runtime.journal.JournalWorld.causes;
import static io.flottila.runtime.journal.JournalWorld.count;
import static io.flottila.runtime.journal.JournalWorld.settled;
import static io.flottila.runtime.journal.JournalWorld.workRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-4c2 (D-082 restated by D-105, CORE-3 item 6): the intake over the journal. Append → forced → one transaction [dedupe] +
 * ingress record + work item with the ref (+ {@code body_refs} only when the flow keeps its input) → abandoned check → commit →
 * index the ref, release the memory copy. No row naming a body commits before its record is forced, and no statement of the
 * save's transaction is sent before it; a force that fails saves nothing and never reaches the commit. Level none is the
 * record and the work item only: counted at the client, on the wire ({@link PgWire}). A save that rolls back leaves only bytes
 * in the journal no row references, and its body local.
 *
 * <p>Witnesses that survive a rollback: a sequence bumped by BEFORE INSERT triggers on {@code messages}, {@code work},
 * {@code body_refs} and {@code processed_ids}; the transaction of each insert logged by AFTER INSERT triggers. The journal's
 * files are {@link HeldFiles}: a force is held or failed by the case (the record's message id is plain in its header).
 */
@Timeout(120)
class PgIntakeJournalIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static Vertx vertx;

    @TempDir Path tmp;
    CaseDatabase db;
    final List<AutoCloseable> closing = new ArrayList<>();

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
    void caseDatabase() throws Exception {
        db = CaseDatabase.create(pg, vertx, "intake_journal_").migrated();
        sql("create sequence save_inserts");
        sql("create function count_insert() returns trigger language plpgsql as $$ begin perform nextval('save_inserts'); return new; end $$");
        for (String table : List.of("messages", "work", "body_refs", "processed_ids")) {
            sql("create trigger count_" + table + " before insert on " + table + " for each row execute function count_insert()");
        }
    }

    @AfterEach
    void closeAll() throws Exception {
        for (AutoCloseable c : closing.reversed()) c.close();
        if (db != null) db.close();
    }

    void sql(String statement) {
        db.pool().query(statement).execute().await();
    }

    /** {@code nextval} calls on {@code save_inserts} so far, in any transaction, committed or not: every insert the saves sent. */
    long inserts() {
        Row r = db.pool().query("select last_value, is_called from save_inserts").execute().await().iterator().next();
        return r.getBoolean(1) ? r.getLong(0) : 0;
    }

    /**
     * The save of {@code in} with {@code answer}, decided while its transaction is open: a session holds {@code work} in SHARE
     * mode so the item's insert waits; once it waits, {@code atTheDecision} runs, {@code answer} completes and the session lets go.
     */
    Future<String> decidedInItsTransaction(JournalWorld a, Envelope in, Promise<Void> answer, Runnable atTheDecision) {
        io.vertx.pgclient.PgConnection holder = io.vertx.pgclient.PgConnection.connect(vertx, db.options()).await();
        try {
            io.vertx.sqlclient.Transaction tx = holder.begin().await();
            holder.query("lock table work in share mode").execute().await();
            Future<String> pending = a.start(in, true, answer.future());
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
            while (count(db.pool(), "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'") == 0) {
                if (System.nanoTime() > deadline) throw new AssertionError("the save's item insert never waited on the held table");
                Thread.onSpinWait();
            }
            atTheDecision.run();
            answer.complete();
            tx.rollback().await();
            return settled(pending);
        } finally {
            holder.close().await();
        }
    }

    JournalWorld world() throws Exception {
        JournalWorld w = JournalWorld.open(vertx, db.pool(), db.pool(), Files.createDirectories(tmp.resolve("journal")), "node-a");
        closing.add(w);
        return w;
    }

    /** Nothing of message {@code id} committed: no ingress row, no item, no kept ref, no bodies row. */
    void nothingCommitted(String id) {
        assertEquals(0, count(db.pool(), "select count(*) from messages where id = $1", id), id + ": no ingress row");
        assertEquals(0, count(db.pool(), "select count(*) from work where id = $1", id), id + ": no item");
        assertEquals(0, count(db.pool(), "select count(*) from body_refs where message_id = $1", id), id + ": no body ref");
        assertEquals(0, count(db.pool(), "select count(*) from bodies where message_id = $1", id), id + ": no bodies row");
    }

    /**
     * Review r1 (2): counted on the wire ({@link PgWire}), not by insert triggers — no statement of the save's transaction, its
     * {@code BEGIN} included, is sent while the record's force is held; the commit comes after the force. A force that fails
     * (EIO) sends nothing at all.
     */
    @Test
    void noCommitBeforeTheForce() throws Exception {
        PgWire wire = new PgWire(pg.getHost(), pg.getMappedPort(5432));
        closing.add(wire);
        Pool counted = PgBuilder.pool().connectingTo(db.options().setHost("127.0.0.1").setPort(wire.port()))
                .with(new PoolOptions().setMaxSize(1)).using(vertx).build();
        closing.add(() -> counted.close().await());
        // the journal's own rows on the case pool: only the intake's statements cross the counted wire
        JournalWorld a = JournalWorld.open(vertx, counted, db.pool(), Files.createDirectories(tmp.resolve("journal")), "node-a");
        closing.add(a);
        counted.query("select 1").execute().await();   // the one connection is open before the count starts
        Buffer bytes = bytes(3_000, 1);
        Envelope in = a.arrive("m-held", bytes);
        HeldFiles.Hold force = a.files.holdForceAfter("m-held");
        PgWire.Held commit = wire.holdNext(PgWire.query("COMMIT"));

        int mark = wire.mark();
        Future<String> saved = a.start(in, true, JournalWorld.undecided());
        force.awaitReached();
        assertFalse(saved.isComplete(), "the save waits for its record's force");
        assertEquals(List.of(), wire.since(mark), "no message, BEGIN included, was sent while the record is not forced");
        assertEquals(0, inserts(), "and no insert");
        nothingCommitted("m-held");

        force.release();
        commit.awaitReached();
        assertTrue(a.files.forcedPast("m-held"), "the commit came after the record's force (D-082 restated, CORE-3 item 6)");
        commit.release();
        assertTrue(settled(saved).succeeded(), "saved once the force returned: " + saved.cause());
        List<String> sent = wire.statementsSince(mark).stream().map(PgIntakeJournalIT::head).toList();
        assertEquals("BEGIN", sent.getFirst(), "witness: the wire counts the save's statements once the force returned: " + sent);
        assertEquals("COMMIT", sent.getLast(), sent.toString());
        assertEquals(1, count(db.pool(), "select count(*) from messages where id = 'm-held'"), "the ingress row committed");
        JournalWorld.Ref ref = workRef(db.pool(), "m-held");
        assertNotNull(ref, "and the item");
        assertEquals(bytes, a.record(ref), "naming the forced record");

        // a force that fails (EIO): the journal fails (fail-stop), the save never reaches its transaction
        long before = inserts();
        Envelope lost = a.arrive("m-unforced", bytes(3_000, 2));
        HeldFiles.Hold failing = a.files.holdForceAfter("m-unforced");
        int failedMark = wire.mark();
        Future<String> refused = a.start(lost, true, JournalWorld.undecided());
        failing.awaitReached();
        failing.fail(new IOException("EIO by the test"));
        assertTrue(settled(refused).failed(), "a record never forced is no save");
        assertTrue(causes(refused.cause()).stream().anyMatch(c -> c.contains(JournalFailedException.class.getSimpleName())),
                "failed retryable, with the journal's failure (503 for the sender): " + causes(refused.cause()));
        assertEquals(List.of(), wire.since(failedMark), "it never reached its transaction: it sent no message");
        assertEquals(before, inserts(), "no insert");
        nothingCommitted("m-unforced");
    }

    /** The statement a SQL text is, as {@code BEGIN}, {@code COMMIT} or {@code insert into <table>}. */
    static String head(String sql) {
        String s = sql.toLowerCase(Locale.ROOT);
        if (s.startsWith("insert into ")) {
            String rest = s.substring("insert into ".length());
            int end = 0;
            while (end < rest.length() && (Character.isLetterOrDigit(rest.charAt(end)) || rest.charAt(end) == '_')) end++;
            return "insert into " + rest.substring(0, end);
        }
        if (s.startsWith("with ")) return "with … " + s;   // a CTE statement: shown whole, so the failure names it
        return s.toUpperCase(Locale.ROOT);
    }

    @Test
    void levelNoneSavesRecordAndWorkItemOnly() throws Exception {
        try (PgWire wire = new PgWire(pg.getHost(), pg.getMappedPort(5432))) {
            Pool counted = PgBuilder.pool().connectingTo(db.options().setHost("127.0.0.1").setPort(wire.port()))
                    .with(new PoolOptions().setMaxSize(1)).using(vertx).build();
            closing.add(() -> counted.close().await());
            // the journal's own rows on the case pool: only the intake's statements cross the counted wire
            JournalWorld a = JournalWorld.open(vertx, counted, db.pool(), Files.createDirectories(tmp.resolve("journal")), "node-a");
            closing.add(a);
            counted.query("select 1").execute().await();   // the one connection is open before the count starts
            Buffer bytes = bytes(3_000, 3);
            Envelope in = a.arrive("m-none", bytes);

            int mark = wire.mark();
            Future<String> saved = a.save(in, false, JournalWorld.undecided());
            assertTrue(saved.succeeded(), "saved: " + saved.cause());
            List<String> sent = wire.statementsSince(mark).stream().map(PgIntakeJournalIT::head).toList();
            long roundTrips = wire.roundTripsSince(mark);

            // the record is the message store's two statements (messages, then message_keys: PgBatchSink), then the item
            assertEquals(List.of("BEGIN", "insert into messages", "insert into message_keys", "insert into work", "COMMIT"), sent,
                    "level none: the ingress record and the work item, nothing else in or around the transaction (CORE-3 item 6)");
            // this client sends BEGIN and COMMIT as one simple query each and a prepared statement as two round trips
            // (Parse/Describe/Sync, then Bind/Execute/Sync), as observed for today's save on the same wire
            assertEquals(1 + 3 * 2 + 1, roundTrips, "8 round trips: no extra statement or round trip for the body: " + wire.since(mark));
        }
        assertEquals(1, count(db.pool(), "select count(*) from messages where id = 'm-none'"), "the ingress row");
        JournalWorld.Ref ref = workRef(db.pool(), "m-none");
        assertNotNull(ref, "the item");
        assertEquals("node-a", ref.node(), "its body on this node");
        assertEquals(0, count(db.pool(), "select count(*) from body_refs"), "no body_refs row at level none");
        assertEquals(0, count(db.pool(), "select count(*) from bodies"), "no bodies row");
    }

    @Test
    void keepInputWritesABodyRef() throws Exception {
        JournalWorld a = world();
        sql("create table txlog(n bigserial primary key, tbl text not null, txid bigint not null)");
        sql("create function log_tx() returns trigger language plpgsql as $$ begin insert into txlog(tbl, txid) values (tg_table_name, txid_current()); return null; end $$");
        for (String t : List.of("messages", "work", "body_refs", "bodies")) sql("create trigger log_tx after insert on " + t + " for each row execute function log_tx()");
        Buffer bytes = bytes(3_000, 4);
        Envelope in = a.arrive("m-kept", bytes);

        Future<String> saved = a.save(in, true, JournalWorld.undecided());
        assertTrue(saved.succeeded(), "saved: " + saved.cause());
        JournalWorld.Ref kept = bodyRef(db.pool(), BlobKey.body("m-kept", 0));
        assertNotNull(kept, "keepInput: the ingress body's ref is kept in body_refs, under (id, 0, body)");
        assertEquals(workRef(db.pool(), "m-kept"), kept, "the same ref as the work item's: one record, two references");
        assertEquals("node-a", kept.node());
        assertEquals(bytes, a.record(kept), "the record of the arrived bytes");
        List<String> tables = new ArrayList<>();
        Set<Long> txids = new java.util.HashSet<>();
        for (Row r : db.pool().query("select tbl, txid from txlog order by n").execute().await()) {
            tables.add(r.getString(0));
            txids.add(r.getLong(1));
        }
        assertEquals("messages", tables.get(0), "the ingress record first: " + tables);
        assertEquals(Set.of("work", "body_refs"), Set.copyOf(tables.subList(1, tables.size())), "then the item and the kept ref, no bodies row: " + tables);
        assertEquals(3, tables.size(), "one row each: " + tables);
        assertEquals(1, txids.size(), "in one transaction: " + txids);
        assertEquals(0, count(db.pool(), "select count(*) from bodies"), "no bodies row");
    }

    @Test
    void aRolledBackSaveLeavesOnlyUnreferencedBytes() throws Exception {
        JournalWorld a = world();
        Buffer bytes = bytes(3_000, 5);
        Envelope in = a.arrive("m-rolled", bytes);
        Promise<Void> answer = Promise.promise();

        Future<String> abandoned = decidedInItsTransaction(a, in, answer, () -> { });   // the answer was decided meanwhile: roll back
        assertTrue(abandoned.failed(), "an abandoned save fails");
        assertTrue(inserts() > 0, "fixture: the transaction ran its inserts, then rolled back");
        nothingCommitted("m-rolled");
        assertTrue(a.files.forcedPast("m-rolled"), "the record was appended and forced before the transaction: its bytes are in the journal");
        assertEquals(0, count(db.pool(), "select count(*) from work where body_node is not null")
                + count(db.pool(), "select count(*) from body_refs"), "and no row references them (the sweeper's, CORE-4d)");
        assertEquals(bytes.length(), a.blobs.memoryInUse(), "the body stays local with its budget: no commit, no release");
        assertEquals(bytes, settled(a.blobs.read(in.body().key())).result(), "and readable");

        Future<String> retry = a.save(in, true, JournalWorld.undecided());
        assertTrue(retry.succeeded(), "the sender's retry saves: " + retry.cause());
        JournalWorld.Ref ref = workRef(db.pool(), "m-rolled");
        assertNotNull(ref);
        assertEquals(bytes, a.record(ref), "its item names a record of the bytes");
        assertEquals(ref, bodyRef(db.pool(), in.body().key()), "and so does its kept ref");
        assertEquals(0, a.blobs.memoryInUse(), "released once committed");
    }

    // --- the open-save set (CORE-3 item 6; its consumer is the CORE-4d sweeper, which skips the segments it names)

    /** The refs the intake's open-save set names now, as a list: {@code PgIntake.openSaves()} is assumed to be a {@code Collection<JournalRef>}. */
    static List<JournalRef> openSaves(JournalWorld w) {
        Collection<JournalRef> open = w.intake.openSaves();
        return List.copyOf(open);
    }

    /**
     * Holds every commit of a transaction that inserted a work row: a deferred constraint trigger takes the transaction-level
     * advisory lock {@code 4242} at COMMIT, which {@code gate} holds at session level until the case lets go.
     */
    PgConnection commitGate() {
        sql("create function wait_for_the_gate() returns trigger language plpgsql as $$ begin perform pg_advisory_xact_lock(4242); return null; end $$");
        sql("create constraint trigger gate_commit after insert on work deferrable initially deferred for each row execute function wait_for_the_gate()");
        PgConnection gate = PgConnection.connect(vertx, db.options()).await();
        closing.add(() -> gate.close().await());
        gate.query("select pg_advisory_lock(4242)").execute().await();
        return gate;
    }

    /** The backends of the case database blocked by {@code gate}'s session at a COMMIT. */
    long commitsHeldBy(PgConnection gate) {
        int pid = gate.query("select pg_backend_pid()").execute().await().iterator().next().getInteger(0);
        return count(db.pool(), "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'"
                + " and upper(query) like 'COMMIT%' and $1 = any(pg_blocking_pids(pid))", pid);
    }

    @Test
    void aSaveIsAnOpenSaveFromItsReservationUntilItsCommitIsAcknowledged() throws Exception {
        JournalWorld a = world();
        assertEquals(List.of(), openSaves(a), "fixture: no save yet");
        Buffer bytes = bytes(3_000, 7);
        Envelope in = a.arrive("m-open", bytes);
        HeldFiles.Hold force = a.files.holdForceAfter("m-open");
        PgConnection gate = commitGate();

        Future<String> saved = a.start(in, true, JournalWorld.undecided());
        force.awaitReached();
        List<JournalRef> whileForcing = openSaves(a);   // appended, not yet forced: reserved and written
        force.release();
        JournalFixture.until(() -> commitsHeldBy(gate) == 1, () -> "the save's COMMIT was not seen held at the gate: " + saved);
        List<JournalRef> whileCommitting = openSaves(a);   // the COMMIT is sent, its acknowledgement held back
        assertFalse(saved.isComplete(), "the save waits for its commit's acknowledgement");

        gate.query("select pg_advisory_unlock(4242)").execute().await();
        assertTrue(settled(saved).succeeded(), "saved once the commit was acknowledged: " + saved.cause());
        JournalRef ref = workRef(db.pool(), "m-open").journalRef();
        assertEquals(List.of(ref), whileForcing, "in the open-save set while its record waits for the force, naming that record");
        assertEquals(List.of(ref), whileCommitting, "and while its commit is not acknowledged: a held commit keeps it there");
        assertEquals(List.of(), openSaves(a), "out of the set once the commit is acknowledged (before the save completes)");
    }

    @Test
    void aSaveLeavesTheOpenSavesOnceItsRollbackIsAcknowledgedOrItsForceFailed() throws Exception {
        JournalWorld a = world();
        Envelope rolled = a.arrive("m-rolled-open", bytes(3_000, 8));
        AtomicReference<List<JournalRef>> atTheDecision = new AtomicReference<>();
        Future<String> abandoned = decidedInItsTransaction(a, rolled, Promise.promise(), () -> atTheDecision.set(openSaves(a)));
        assertTrue(abandoned.failed(), "fixture: an abandoned save fails");
        assertEquals(1, atTheDecision.get().size(), "an open save while its transaction runs: " + atTheDecision.get());
        assertEquals(List.of(), openSaves(a), "out of the set once its rollback is acknowledged");

        Envelope lost = a.arrive("m-unforced-open", bytes(3_000, 9));
        HeldFiles.Hold failing = a.files.holdForceAfter("m-unforced-open");
        Future<String> refused = a.start(lost, true, JournalWorld.undecided());
        failing.awaitReached();
        assertEquals(1, openSaves(a).size(), "an open save while its record waits for the force");
        failing.fail(new IOException("EIO by the test"));
        assertTrue(settled(refused).failed(), "fixture: a record never forced is no save");
        assertEquals(List.of(), openSaves(a), "a save that never reached its transaction leaves the set when it fails");
    }

    /**
     * Review r1 (3): a body above RECORD_MAX (256 KiB here) is a spill; its persist inserts the spill's segment row, and that
     * insert refused (a trigger) makes the save fail — and leave the open-save set, which it entered at its reservation.
     * Nothing commits; the spill stays local.
     */
    @Test
    void aSpilledBodyWhosePersistFailsIsAFailedSaveAndLeavesTheOpenSaves() throws Exception {
        JournalWorld a = world();
        Envelope in = a.arrive("m-spilled", bytes(300_000, 10));
        assertEquals(java.util.Optional.of(io.flottila.runtime.blob.TieredBlobStore.Location.SPILL), a.blobs.location(in.body().key()),
                "fixture: above RECORD_MAX, a spill");
        sql("create function refuse_segment() returns trigger language plpgsql as $$ begin raise exception 'segment row refused by the test'; end $$");
        sql("create trigger refuse_segment before insert on journal_segments for each row execute function refuse_segment()");
        Future<String> refused = a.save(in, true, JournalWorld.undecided());
        assertTrue(refused.failed(), "a spill whose segment row is refused is no save");
        assertTrue(causes(refused.cause()).stream().anyMatch(c -> c.contains("segment row refused by the test")),
                "failed by the refused insert: " + causes(refused.cause()));
        assertEquals(List.of(), openSaves(a), "and out of the open saves again");
        nothingCommitted("m-spilled");
        assertEquals(java.util.Optional.of(io.flottila.runtime.blob.TieredBlobStore.Location.SPILL), a.blobs.location(in.body().key()),
                "its body stays local");
    }
}
