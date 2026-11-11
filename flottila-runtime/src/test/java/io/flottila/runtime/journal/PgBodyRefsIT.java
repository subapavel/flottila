package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobNotFoundException;
import io.flottila.api.Envelope;
import io.flottila.runtime.blob.TieredBlobStore;
import io.flottila.testkit.PostgresImage;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Transaction;
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

import static io.flottila.runtime.journal.JournalWorld.bodyRef;
import static io.flottila.runtime.journal.JournalWorld.bytes;
import static io.flottila.runtime.journal.JournalWorld.count;
import static io.flottila.runtime.journal.JournalWorld.settled;
import static io.flottila.runtime.journal.JournalWorld.workRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CORE-4c2 (CORE-3 item 10, last sentence): a reference insert that adds no record — here the persist of a key already durable
 * through its work row only (a level-none ingress body that a park or a replay then keeps) — is one statement whose CTE holds
 * its source row (the work row) and that row's segment row for key share and inserts the {@code body_refs} row from them. No
 * segment row (the sweeper deleted it): {@link BlobNotFoundException} and nothing inserted — through the store that saved the
 * body (its index still names the ref) and through one with an empty index alike. A session holding the segment row for update
 * makes the insert wait; its delete, once committed, leaves the insert with no row.
 */
@Timeout(120)
class PgBodyRefsIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
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
        db = CaseDatabase.create(pg, vertx, "body_refs_").migrated();
        a = JournalWorld.open(vertx, db.pool(), db.pool(), Files.createDirectories(tmp.resolve("journal")), "node-a",
                s -> s.segmentRecords(1),   // a segment per record: sweeping one message's segment sweeps nothing else
                new TieredBlobStore.Config(JournalWorld.MAX, JournalWorld.MEMORY_TIER, JournalWorld.MEMORY_BUDGET, JournalWorld.DURABLE_MAX));
    }

    @AfterEach
    void close() throws Exception {
        if (a != null) a.close();
        db.close();
    }

    /** {@code id} saved at level none: its ref on its work row only. */
    BlobKey levelNone(String id, Buffer bytes) {
        Envelope in = a.arrive(id, bytes);
        assertTrue(a.save(in, false, JournalWorld.undecided()).succeeded(), "fixture: saved");
        assertNull(bodyRef(db.pool(), in.body().key()), "fixture: no body_refs row at level none");
        return in.body().key();
    }

    long deleteSegmentRow(long segment) {
        return count(db.pool(), "with gone as (delete from journal_segments where node = 'node-a' and segment = $1 returning 1) select count(*) from gone", segment);
    }

    static void refusedNotFound(Future<Void> f, BlobKey key, String what) {
        assertTrue(f.failed(), what + ": the body is gone with its segment row");
        BlobNotFoundException e = assertInstanceOf(BlobNotFoundException.class, f.cause(), what + ": " + f.cause());
        assertEquals(key, e.key(), what);
    }

    @Test
    void aReferenceIntoASweptSegmentFailsBlobNotFoundAndInsertsNothing() {
        BlobKey live = levelNone("m-live", bytes(2_000, 1));
        assertTrue(settled(a.blobs.persist(live)).succeeded(), "fixture: a work row's body with its segment row is kept by a persist");
        assertEquals(workRef(db.pool(), "m-live"), bodyRef(db.pool(), live), "copied from the work row: the same ref, no record added");

        BlobKey swept = levelNone("m-swept", bytes(2_000, 2));
        assertEquals(1, deleteSegmentRow(workRef(db.pool(), "m-swept").segment()), "fixture: the sweeper deleted its segment row");

        refusedNotFound(settled(a.blobs.persist(swept)), swept, "the store that saved it (its index still names the ref)");
        assertNull(bodyRef(db.pool(), swept), "and no body_refs row was inserted");
        refusedNotFound(settled(a.restartedStore().persist(swept)), swept, "a store with an empty index");
        assertNull(bodyRef(db.pool(), swept), "still no body_refs row");
        assertEquals(1, count(db.pool(), "select count(*) from body_refs"), "only the live body's ref");
    }

    @Test
    void aReferenceInsertWaitsForItsLockedSegmentRowAndFindsNoRowOnceItIsDeleted() {
        BlobKey key = levelNone("m-raced", bytes(2_000, 3));
        long segment = workRef(db.pool(), "m-raced").segment();
        TieredBlobStore restarted = a.restartedStore();
        PgConnection sweeper = PgConnection.connect(vertx, db.options()).await();
        try {
            Transaction tx = sweeper.begin().await();
            int pid = sweeper.query("select pg_backend_pid()").execute().await().iterator().next().getInteger(0);
            assertEquals(1, sweeper.query("select 1 from journal_segments where node = 'node-a' and segment = " + segment + " for update")
                    .execute().await().size(), "fixture: the segment row is there to lock");

            Future<Void> persist = restarted.persist(key);
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (count(db.pool(), "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'"
                    + " and $1 = any(pg_blocking_pids(pid))", pid) == 0) {
                if (persist.isComplete()) fail("the reference insert did not wait for its segment row held for update: " + persist);
                if (System.nanoTime() > deadline) fail("the reference insert was not seen waiting on the segment row");
                Thread.onSpinWait();
                Thread.yield();
            }
            assertFalse(persist.isComplete(), "it waits for the sweeper's batch");
            sweeper.query("delete from journal_segments where node = 'node-a' and segment = " + segment).execute().await();
            tx.commit().await();

            refusedNotFound(settled(persist), key, "the insert behind the segment row's delete");
            assertNull(bodyRef(db.pool(), key), "no body_refs row names the swept body");
        } finally {
            sweeper.close().await();
        }
    }

    /**
     * {@code id} saved keeping its input ({@code body_refs} row), then its item completed — its work row deleted, as the
     * worker's COMPLETE does — so its kept ref is the key's only source. Answers the key; its local copy went at the commit.
     */
    BlobKey keptOnly(String id, Buffer bytes) {
        Envelope in = a.arrive(id, bytes);
        assertTrue(a.save(in, true, JournalWorld.undecided()).succeeded(), "fixture: saved, keeping its input");
        assertNotNull(bodyRef(db.pool(), in.body().key()), "fixture: its body_refs row");
        assertEquals(1, count(db.pool(), "with gone as (delete from work where id = $1 returning 1) select count(*) from gone", id), "fixture: its item completed");
        return in.body().key();
    }

    /**
     * Review r1 (4), the {@code kept} source of the reference statement: a key held nowhere locally whose own {@code body_refs}
     * row names a swept segment (its segment row deleted) is no body — {@link BlobNotFoundException} through a store with an
     * empty index, and the row is left as it was, nothing inserted. Witness: the same with its segment row there persists.
     */
    @Test
    void aKeptRefIntoASweptSegmentFailsBlobNotFoundAndInsertsNothing() {
        BlobKey live = keptOnly("m-kept-live", bytes(2_000, 11));
        assertTrue(settled(a.restartedStore().persist(live)).succeeded(), "witness: a kept ref with its segment row is a body");

        BlobKey swept = keptOnly("m-kept-swept", bytes(2_000, 12));
        JournalWorld.Ref before = bodyRef(db.pool(), swept);
        assertEquals(1, deleteSegmentRow(before.segment()), "fixture: the sweeper deleted its segment row");

        refusedNotFound(settled(a.restartedStore().persist(swept)), swept, "a kept ref into a swept segment");
        assertEquals(before, bodyRef(db.pool(), swept), "its row is left as it was");
        assertEquals(2, count(db.pool(), "select count(*) from body_refs"), "nothing inserted: the two kept refs only");
    }

    /**
     * Review r1 (4), mirroring {@code ReferenceEnqueueIT}'s locked-source case: a session holding the key's own
     * {@code body_refs} row for update (retention removing the message's refs) makes the persist wait — the statement holds
     * its source for key share — and its delete, once committed, leaves the persist with no source: {@link BlobNotFoundException},
     * no row inserted.
     */
    @Test
    void aPersistWaitsForItsLockedKeptRefAndFindsNoRowOnceItIsDeleted() {
        BlobKey key = keptOnly("m-kept-raced", bytes(2_000, 13));
        TieredBlobStore restarted = a.restartedStore();
        PgConnection retention = PgConnection.connect(vertx, db.options()).await();
        try {
            Transaction tx = retention.begin().await();
            int pid = retention.query("select pg_backend_pid()").execute().await().iterator().next().getInteger(0);
            assertEquals(1, retention.query("select 1 from body_refs where message_id = 'm-kept-raced' for update").execute().await().size(),
                    "fixture: the kept ref is there to lock");

            Future<Void> persist = restarted.persist(key);
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (count(db.pool(), "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'"
                    + " and $1 = any(pg_blocking_pids(pid))", pid) == 0) {
                if (persist.isComplete()) fail("the persist did not wait for its kept ref held for update: " + persist);
                if (System.nanoTime() > deadline) fail("the persist was not seen waiting on the kept ref");
                Thread.onSpinWait();
                Thread.yield();
            }
            assertFalse(persist.isComplete(), "it waits for retention's batch");
            retention.query("delete from body_refs where message_id = 'm-kept-raced'").execute().await();
            tx.commit().await();

            refusedNotFound(settled(persist), key, "the persist behind its kept ref's delete");
            assertNull(bodyRef(db.pool(), key), "no body_refs row was inserted for it");
        } finally {
            retention.close().await();
        }
    }
}
