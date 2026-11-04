package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobNotFoundException;
import io.flottila.api.BlobStore;
import io.flottila.api.Envelope;
import io.flottila.core.queue.WorkQueue;
import io.flottila.runtime.blob.TieredBlobStore;
import io.flottila.runtime.queue.PgWorkQueue;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Transaction;
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
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.flottila.runtime.journal.JournalWorld.bodyRef;
import static io.flottila.runtime.journal.JournalWorld.bytes;
import static io.flottila.runtime.journal.JournalWorld.count;
import static io.flottila.runtime.journal.JournalWorld.opened;
import static io.flottila.runtime.journal.JournalWorld.settled;
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
 * CORE-4c2 (CORE-3 item 8): how the journal store finds a key. The in-memory location index first (filled at the save's
 * commit): a hit asks Postgres nothing. On a miss: the key's {@code body_refs} row, then the work row by message id, then
 * {@code bodies} (a pre-V12 row), then {@link BlobNotFoundException} (D-096). A ref whose segment row is gone (the sweeper's
 * delete, staged by the case) reads as removed — as never written — although its file is still there. A miss is staged with a
 * second store over the same open journal ({@link JournalWorld#restartedStore()}): its index is empty.
 *
 * <p>"Asks no Postgres" is witnessed by a session holding {@code body_refs}, {@code work}, {@code bodies} and
 * {@code journal_segments} in ACCESS EXCLUSIVE mode: a read that asked any of them would wait for it, past the bound.
 */
@Timeout(120)
class JournalReadIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static Vertx vertx;

    @TempDir Path tmp;
    CaseDatabase db;
    JournalWorld a;

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
        db = CaseDatabase.create(pg, vertx, "journal_read_").migrated();
        a = JournalWorld.open(vertx, db.pool(), db.pool(), Files.createDirectories(tmp.resolve("journal")), "node-a",
                s -> s.segmentRecords(1),   // a segment per record: a case sweeps one message's segment only
                new TieredBlobStore.Config(JournalWorld.MAX, JournalWorld.MEMORY_TIER, JournalWorld.MEMORY_BUDGET, JournalWorld.DURABLE_MAX));
    }

    @AfterEach
    void close() throws Exception {
        if (a != null) a.close();
        db.close();
    }

    /** {@code id} saved through the intake: committed, its memory copy released. */
    Envelope saved(String id, Buffer bytes, boolean keepInput) {
        Envelope in = a.arrive(id, bytes);
        Future<String> saved = a.save(in, keepInput, JournalWorld.undecided());
        assertTrue(saved.succeeded(), "fixture: saved " + saved.cause());
        return in;
    }

    static void refusedNotFound(Future<?> f, BlobKey key, String what) {
        assertTrue(f.failed(), what + ": nothing stored under " + key);
        BlobNotFoundException e = assertInstanceOf(BlobNotFoundException.class, f.cause(), what + ": " + f.cause());
        assertEquals(key, e.key(), what + ": it names the key");
    }

    /** {@code read}, {@code open} and {@code exists} of {@code key} on {@code store} all say "nothing stored". */
    static void readsAsNeverWritten(BlobStore store, BlobKey key, String what) {
        refusedNotFound(settled(store.read(key)), key, what + " (read)");
        refusedNotFound(opened(store, key), key, what + " (open)");
        assertEquals(Boolean.FALSE, settled(store.exists(key)).result(), what + " (exists)");
    }

    @Test
    void indexHitAsksNoPostgres() throws Exception {
        Buffer bytes = bytes(3_000, 1);
        Envelope in = saved("m-hit", bytes, false);
        BlobKey key = in.body().key();
        assertEquals(0, a.blobs.memoryInUse(), "fixture: the memory copy went at the commit: a read must go to the journal");

        PgConnection holder = PgConnection.connect(vertx, db.options()).await();
        try {
            Transaction tx = holder.begin().await();
            int pid = holder.query("select pg_backend_pid()").execute().await().iterator().next().getInteger(0);
            holder.query("lock table body_refs, work, bodies, journal_segments in access exclusive mode").execute().await();
            assertEquals(bytes, bounded(a.blobs.read(key), "read"), "the index names the ref: the record is read, no row asked");
            assertEquals(bytes, bounded(opened(a.blobs, key), "open"), "streamed too");
            // CORE-4c2 review r1 (architect decision): exists() never answers from the location index, it asks Postgres — so on
            // an index hit it waits for the tables the case holds locked (was: true from the index, no row asked)
            Future<Boolean> exists = a.blobs.exists(key);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (count(db.pool(), "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'"
                    + " and $1 = any(pg_blocking_pids(pid))", pid) == 0) {
                if (exists.isComplete()) fail("exists answered while the case holds the tables locked: not from Postgres: " + exists);
                if (System.nanoTime() > deadline) fail("exists was not seen waiting on the tables the case holds locked");
                Thread.onSpinWait();
                Thread.yield();
            }
            assertFalse(exists.isComplete(), "exists waits for Postgres: the index does not answer it");
            tx.rollback().await();
            assertEquals(Boolean.TRUE, exists.await(10, TimeUnit.SECONDS), "and answers true once the tables are released");
        } finally {
            holder.close().await();
        }
    }

    /** {@code f}'s result within 10 s: a read waiting on a locked table fails here, naming {@code what}. */
    static <T> T bounded(Future<T> f, String what) {
        try {
            return f.await(10, TimeUnit.SECONDS);
        } catch (TimeoutException waiting) {
            return fail(what + " asked Postgres: it waits on a table the case holds locked");
        }
    }

    @Test
    void missReadsBodyRefs() {
        Buffer kept = bytes(3_000, 2);
        Buffer unreferenced = bytes(3_000, 3);
        BlobKey keptKey = saved("m-kept", kept, true).body().key();
        BlobKey goneKey = saved("m-unref", unreferenced, true).body().key();
        db.pool().query("delete from work").execute().await();   // both items completed: only their kept refs remain
        db.pool().preparedQuery("delete from body_refs where message_id = $1").execute(Tuple.of("m-unref")).await();
        TieredBlobStore restarted = a.restartedStore();

        assertEquals(kept, settled(restarted.read(keptKey)).result(), "a miss reads the key's body_refs row, then the record it names");
        assertEquals(kept, settled(opened(restarted, keptKey)).result(), "streamed too");
        assertEquals(Boolean.TRUE, settled(restarted.exists(keptKey)).result());
        assertTrue(a.files.forcedPast("m-unref"), "fixture: the other record is in the journal still");
        readsAsNeverWritten(restarted, goneKey, "a record no row references");
    }

    @Test
    void missReadsTheWorkRow() {
        Buffer bytes = bytes(3_000, 4);
        Buffer completed = bytes(3_000, 5);
        BlobKey key = saved("m-none", bytes, false).body().key();
        BlobKey doneKey = saved("m-done", completed, false).body().key();
        assertNull(bodyRef(db.pool(), key), "fixture: level none, no body_refs row");
        db.pool().preparedQuery("delete from work where id = $1").execute(Tuple.of("m-done")).await();   // its item completed
        TieredBlobStore restarted = a.restartedStore();

        assertEquals(bytes, settled(restarted.read(key)).result(), "a miss without a body_refs row reads the work row by id, then its record");
        assertEquals(bytes, settled(opened(restarted, key)).result(), "streamed too");
        assertEquals(Boolean.TRUE, settled(restarted.exists(key)).result());
        readsAsNeverWritten(restarted, doneKey, "a completed item's body, never kept");
        readsAsNeverWritten(restarted, BlobKey.body("m-none", 1), "another step of the message: the work row's ref is its input's only");
    }

    @Test
    void preV12KeyReadsBodies() {
        Buffer bytes = bytes(2_000, 6);
        BlobKey key = BlobKey.body("m-pre", 0);
        db.pool().preparedQuery("insert into bodies(message_id, step, part, bytes) values ($1, $2, $3, $4)")
                .execute(Tuple.of(key.messageId(), key.step(), key.part(), bytes)).await();
        db.pool().query("insert into work(id, flow, step) values ('m-pre', 'orders', 1)").execute().await();   // a pre-V12 item: no body ref
        assertNull(workRef(db.pool(), "m-pre").node(), "fixture: the item names no node");

        assertEquals(bytes, settled(a.blobs.read(key)).result(), "a key no ref names reads its pre-V12 bodies row");
        assertEquals(bytes, settled(opened(a.blobs, key)).result(), "streamed too");
        assertEquals(Boolean.TRUE, settled(a.blobs.exists(key)).result());
    }

    @Test
    void unknownKeyIsBlobNotFound() {
        saved("m-known", bytes(1_000, 7), true);
        for (BlobKey unknown : List.of(BlobKey.body("m-unknown", 0), BlobKey.body("m-known", 3), BlobKey.attachment("m-known", 0, "a.pdf"))) {
            readsAsNeverWritten(a.blobs, unknown, String.valueOf(unknown));
            readsAsNeverWritten(a.restartedStore(), unknown, unknown + " on a miss");
        }
    }

    @Test
    void sweptSegmentReadsAsRemoved() {
        Buffer kept = bytes(3_000, 8);
        Buffer level = bytes(3_000, 9);
        BlobKey keptKey = saved("m-swept-kept", kept, true).body().key();
        BlobKey itemKey = saved("m-swept-item", level, false).body().key();
        JournalWorld.Ref keptRef = bodyRef(db.pool(), keptKey);
        JournalWorld.Ref itemRef = workRef(db.pool(), "m-swept-item");
        assertNotNull(keptRef);
        assertNotNull(itemRef);
        db.pool().preparedQuery("delete from work where id = $1").execute(Tuple.of("m-swept-kept")).await();
        // the sweeper's delete of both segment rows (CORE-4d); the files stay until its background unlink
        for (long segment : List.of(keptRef.segment(), itemRef.segment())) {
            assertEquals(1, count(db.pool(), "with gone as (delete from journal_segments where node = 'node-a' and segment = $1 returning 1) select count(*) from gone", segment),
                    "fixture: segment row " + segment + " deleted");
        }
        assertTrue(Files.exists(a.opened.config().dir().resolve("segments").resolve(keptRef.segment() + ".seg")), "fixture: its file is still there");
        TieredBlobStore restarted = a.restartedStore();

        readsAsNeverWritten(restarted, keptKey, "a kept ref into a swept segment");
        readsAsNeverWritten(restarted, itemKey, "a work row's ref into a swept segment");
    }

    // --- the index's bound and the claim (CORE-3 item 8: at most 262 144 entries, the oldest segment's first out; filled at
    // the commit and by the claim)

    /** A session holding every table a miss reads in ACCESS EXCLUSIVE mode, and its backend pid. */
    static final class Holder {
        final PgConnection session;
        final Transaction tx;
        final int pid;
        boolean gone;

        Holder(PgConnection session, Transaction tx, int pid) {
            this.session = session;
            this.tx = tx;
            this.pid = pid;
        }

        int pid() { return pid; }

        /** Rolls back and closes, once. */
        void letGo() {
            if (gone) return;
            gone = true;
            tx.rollback().await();
            session.close().await();
        }
    }

    Holder lockEveryTableAMissReads() {
        PgConnection session = PgConnection.connect(vertx, db.options()).await();
        Transaction tx = session.begin().await();
        int pid = session.query("select pg_backend_pid()").execute().await().iterator().next().getInteger(0);
        session.query("lock table body_refs, work, bodies, journal_segments in access exclusive mode").execute().await();
        return new Holder(session, tx, pid);
    }

    /** Fails unless {@code read} is seen waiting on {@code holder}'s locks: the read asked Postgres. */
    void waitsOn(Holder holder, Future<?> read, String what) {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (count(db.pool(), "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'"
                + " and $1 = any(pg_blocking_pids(pid))", holder.pid()) == 0) {
            if (read.isComplete()) fail(what + " did not ask Postgres: it ended while every table a miss reads was locked: " + read);
            if (System.nanoTime() > deadline) fail(what + " was not seen waiting on the locked tables");
            Thread.onSpinWait();
            Thread.yield();
        }
        assertFalse(read.isComplete(), what + " waits on the locked tables");
    }

    @Test
    void theIndexBoundIs262144EntriesByDefault() {
        assertEquals(262_144, new TieredBlobStore.Config(JournalWorld.MAX, JournalWorld.MEMORY_TIER, JournalWorld.MEMORY_BUDGET,
                JournalWorld.DURABLE_MAX).indexEntries(), "the design's bound (CORE-3 item 8)");
        assertEquals(4, new TieredBlobStore.Config(JournalWorld.MAX, JournalWorld.MEMORY_TIER, JournalWorld.MEMORY_BUDGET,
                JournalWorld.DURABLE_MAX, 4).indexEntries(), "a bound of the deployment's choosing");
        for (int refused : new int[] {0, -1}) {
            assertThrows(IllegalArgumentException.class, () -> new TieredBlobStore.Config(JournalWorld.MAX, JournalWorld.MEMORY_TIER,
                    JournalWorld.MEMORY_BUDGET, JournalWorld.DURABLE_MAX, refused), "a finite, positive bound: " + refused);
        }
    }

    @Test
    void aFullIndexLetsTheOldestSegmentsEntriesGoFirstAndTheirReadsAskPostgres() throws Exception {
        a.close();   // this case's node has a bound of 4 entries, a segment per record
        a = JournalWorld.open(vertx, db.pool(), db.pool(), Files.createDirectories(tmp.resolve("journal-bound")), "node-c",
                s -> s.segmentRecords(1),
                new TieredBlobStore.Config(JournalWorld.MAX, JournalWorld.MEMORY_TIER, JournalWorld.MEMORY_BUDGET, JournalWorld.DURABLE_MAX, 4));
        List<Buffer> bodies = new java.util.ArrayList<>();
        List<BlobKey> keys = new java.util.ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            Buffer b = bytes(2_000, 40 + i);
            bodies.add(b);
            keys.add(saved("m-" + i, b, true).body().key());
        }
        List<Long> segments = keys.stream().map(k -> bodyRef(db.pool(), k).segment()).toList();
        assertEquals(segments.stream().sorted().distinct().toList(), segments, "fixture: m-1..m-5 lie in five segments, oldest first");

        Holder holder = lockEveryTableAMissReads();
        try {
            for (int i = 1; i < 5; i++) {
                assertEquals(bodies.get(i), bounded(a.blobs.read(keys.get(i)), "read of m-" + (i + 1)),
                        "m-" + (i + 1) + " is one of the 4 newest entries: an index hit");
            }
            Future<Buffer> evicted = a.blobs.read(keys.get(0));
            waitsOn(holder, evicted, "the read of m-1, whose entry left with the oldest segment when m-5 came in,");
            holder.letGo();
            assertEquals(bodies.get(0), settled(evicted).result(), "and reads its body from the ref Postgres names");
        } finally {
            holder.letGo();
        }
    }

    @Test
    void theClaimFillsTheIndex() {
        Buffer bytes = bytes(3_000, 50);
        BlobKey key = saved("m-claimed", bytes, false).body().key();   // level none: the ref on the work row only
        TieredBlobStore claimed = a.restartedStore();
        PgWorkQueue queue = new PgWorkQueue(db.pool(), "node-a", claimed);
        List<WorkQueue.Item> items = queue.claim("w-1", JournalWorld.FLOW, 10, Duration.ofMinutes(1)).await();
        assertEquals(List.of("m-claimed"), items.stream().map(WorkQueue.Item::messageId).toList(), "fixture: claimed");
        TieredBlobStore notClaimed = a.restartedStore();   // the witness: the same miss without a claim

        Holder holder = lockEveryTableAMissReads();
        try {
            assertEquals(bytes, bounded(claimed.read(key), "read after the claim"), "the claim put the item's ref in the store's index: no row asked");
            Future<Buffer> miss = notClaimed.read(key);
            waitsOn(holder, miss, "the read through a store the claim did not tell");
            holder.letGo();
            assertEquals(bytes, settled(miss).result(), "which then reads it through the work row");
        } finally {
            holder.letGo();
        }
    }
}
