package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnection;
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
import java.util.List;
import java.util.UUID;

import static io.flottila.runtime.journal.JournalWorld.bodyRef;
import static io.flottila.runtime.journal.JournalWorld.bytes;
import static io.flottila.runtime.journal.JournalWorld.count;
import static io.flottila.runtime.journal.JournalWorld.settled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-4d carry (CORE-4c3 r1): an ACCEPT_LOSS give-up ({@link PgSegmentRows#giveUp}) takes {@code for update} on the rows it
 * marks, so a reference insert copying the ref being given up ({@code PgWorkQueue.enqueue(client, id, flow, step, key, body)}:
 * a replay or DLQ-retry enqueue, its CTE holding the ref {@code for key share}) cannot slip past it: whichever goes first, no
 * work row or ref naming the given-up record ends with {@code lost_at} null. Each order is held inside its transaction by an
 * AFTER INSERT trigger waiting on an advisory lock this case holds; the other side is started, then the lock released once it
 * waits or completes. Base behaviour (an UPDATE takes FOR NO KEY UPDATE, which a KEY SHARE does not wait for) leaves a copy.
 */
@Timeout(120)
class GiveUpRaceIT {

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
        db = CaseDatabase.create(pg, vertx, "give_up_").migrated();
        a = JournalWorld.open(vertx, db.pool(), db.pool(), Files.createDirectories(tmp.resolve("journal")), "node-a");
    }

    @AfterEach
    void close() throws Exception {
        if (a != null) a.close();
        db.close();
    }

    void sql(String statement) {
        db.pool().query(statement).execute().await();
    }

    long waiters(String event) {
        return count(db.pool(), "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock' and wait_event = $1", event);
    }

    long lockWaiters() {
        return count(db.pool(), "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'");
    }

    /** A kept ref of {@code id}'s step-1 body, persisted by the store: the pin a give-up names. */
    SegmentRows.Pin keptPin(String id) {
        BlobKey key = BlobKey.body(id, 1);
        assertTrue(settled(a.blobs.write(key, bytes(3_000, id.hashCode()), JournalWorld.MAX)).succeeded(), "fixture: written");
        assertTrue(settled(a.blobs.persist(key)).succeeded(), "fixture: persisted");
        JournalWorld.Ref r = bodyRef(db.pool(), key);
        assertNotNull(r, "fixture: its kept ref");
        return new SegmentRows.Pin(SegmentRows.Pin.REF, id, 1, key.part(), r.segment(), r.position(), r.length());
    }

    Future<Void> giveUp(SegmentRows.Pin pin, String digest) {
        UUID journal = new PgSegmentRows(db.pool()).journalOf("node-a").await();
        return new PgSegmentRows(db.pool()).giveUp("node-a", journal, digest, List.of(pin), null);
    }

    /** The replay of {@code pin}'s body onto a new work item: a reference insert that adds no record. */
    Future<Void> copy(SegmentRows.Pin pin) {
        return a.queue.enqueue(db.pool(), pin.messageId(), JournalWorld.FLOW, 2, "K-1", BlobKey.body(pin.messageId(), 1));
    }

    /** No row names {@code pin}'s record with {@code lost_at} null: no unlost copy. */
    void noUnlostCopy(SegmentRows.Pin pin, String what) {
        assertEquals(0, count(db.pool(), "select count(*) from work where body_node = 'node-a' and body_segment = $1 and body_position = $2 and lost_at is null",
                pin.segment(), pin.position()), what + ": no unlost work row names the given-up record");
        assertEquals(0, count(db.pool(), "select count(*) from body_refs where node = 'node-a' and segment = $1 and position = $2 and lost_at is null",
                pin.segment(), pin.position()), what + ": its ref is lost");
    }

    static void until(java.util.function.BooleanSupplier c, String what) {
        SegmentSweepIT.until(c, () -> what);
    }

    @Test
    void aReferenceInsertRacingAGiveUpLeavesNoUnlostCopy() throws Exception {
        PgConnection gate = PgConnection.connect(vertx, db.options()).await();
        try {
            // the give-up first: held after its marks, at its journal_losses insert; the copy then starts
            SegmentRows.Pin first = keptPin("m-given-up-first");
            sql("create function hold_loss() returns trigger language plpgsql as $$ begin perform pg_advisory_xact_lock(5151); return new; end $$");
            sql("create trigger hold_loss after insert on journal_losses for each row execute function hold_loss()");
            gate.query("select pg_advisory_lock(5151)").execute().await();
            Future<Void> givenUp = giveUp(first, "digest-1");
            until(() -> waiters("advisory") == 1, "the give-up marked its pins and waits, its transaction open");
            Future<Void> copied = copy(first);
            until(() -> copied.isComplete() || lockWaiters() == 2, "the copy waits on the give-up's lock, or completed");
            gate.query("select pg_advisory_unlock(5151)").execute().await();
            assertTrue(settled(givenUp).succeeded(), "fixture: the give-up commits: " + givenUp.cause());
            settled(copied);   // either way: refused, or a copy that is marked lost too
            noUnlostCopy(first, "the give-up first");
            sql("drop trigger hold_loss on journal_losses");

            // the copy first: held after its work insert, its CTE's KEY SHARE on the ref held; the give-up then starts
            SegmentRows.Pin second = keptPin("m-copied-first");
            sql("create function hold_copy() returns trigger language plpgsql as $$ begin perform pg_advisory_xact_lock(5252); return new; end $$");
            sql("create trigger hold_copy after insert on work for each row execute function hold_copy()");
            gate.query("select pg_advisory_lock(5252)").execute().await();
            Future<Void> copiedFirst = copy(second);
            until(() -> waiters("advisory") == 1, "the copy inserted its work row and waits, its transaction open");
            Future<Void> givenUpAfter = giveUp(second, "digest-2");
            until(() -> givenUpAfter.isComplete() || lockWaiters() == 2, "the give-up waits on the copy's KEY SHARE, or completed");
            gate.query("select pg_advisory_unlock(5252)").execute().await();
            assertTrue(settled(copiedFirst).succeeded(), "fixture: the copy commits: " + copiedFirst.cause());
            assertTrue(settled(givenUpAfter).succeeded(), "fixture: the give-up commits: " + givenUpAfter.cause());
            assertEquals(1, count(db.pool(), "select count(*) from work where id = $1", second.messageId()), "fixture: the copy landed");
            noUnlostCopy(second, "the copy first");
        } finally {
            gate.close().await();
        }
    }
}
