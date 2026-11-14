package io.flottila.runtime.queue;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobNotFoundException;
import io.flottila.api.BodyLostException;
import io.flottila.api.Envelope;
import io.flottila.api.Props;
import io.flottila.core.dlq.Parked;
import io.flottila.runtime.db.Migrations;
import io.flottila.runtime.store.PgDlq;
import io.flottila.testkit.PostgresImage;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CORE-14b P8 (implementer's cases) on a real Postgres, beyond {@code DlqRestartIT}: {@code PgWorkQueue.restart} of a DLQ row whose
 * ingress body's kept ref is gone fails {@link BlobNotFoundException} naming {@code <id>/0/body} — {@link BodyLostException} when an
 * accepted loss gave the ref up — and changes nothing; a DLQ row of another flow is not restarted (false, kept); a keyed restart
 * inserts under its key's transaction advisory lock, as every keyed insert does (D-121 A3): while another transaction holds that
 * lock the restart waits on it, and completes once it is released; a removal of the DLQ row in flight when the restart comes is
 * waited for, and the restart then answers false.
 */
@Timeout(120)
class PgWorkQueueRestartIT {

    static final String NODE = "node-a";
    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static Vertx vertx;
    static Pool pool;
    PgWorkQueue rows;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        pool = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()))
                .with(new PoolOptions().setMaxSize(4)).using(vertx).build();
    }

    @AfterAll
    static void stop() {
        pool.close().await();
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void clean() {
        pool.query("truncate work, dlq, body_refs, journal_segments").execute().await();
        rows = new PgWorkQueue(pool, NODE);
    }

    Parked parked(String id, String flow) {
        Parked p = ParkedRows.byTheWorker(id, flow, 2);
        ParkedRows.park(pool, p, NODE);
        return p;
    }

    void unchanged(Parked p) {
        assertEquals(Optional.of(p), new PgDlq(pool).get(p.messageId()).await(), "the DLQ row stays as it was");
        assertEquals(0, ParkedRows.count(pool, "select count(*) from work"), "no work row");
    }

    @Test
    void aRowWhoseKeptRefIsGoneFailsBlobNotFoundAndChangesNothing() {
        Parked p = parked("m-1", "t");
        pool.preparedQuery("delete from body_refs where message_id = $1").execute(Tuple.of("m-1")).await();
        Throwable failed = assertThrows(Throwable.class, () -> rows.restart("m-1", "t", null, 2).await(20, TimeUnit.SECONDS));
        assertEquals(BlobKey.body("m-1", 0), assertInstanceOf(BlobNotFoundException.class, failed).key(), "names the ingress body");
        unchanged(p);
    }

    @Test
    void aRowWhoseKeptRefWasGivenUpFailsBodyLostAndChangesNothing() {
        Parked p = parked("m-1", "t");
        pool.preparedQuery("update body_refs set lost_at = now() where message_id = $1").execute(Tuple.of("m-1")).await();
        Throwable failed = assertThrows(Throwable.class, () -> rows.restart("m-1", "t", null, 2).await(20, TimeUnit.SECONDS));
        assertInstanceOf(BodyLostException.class, failed);
        unchanged(p);
    }

    @Test
    void anotherFlowsRowIsNotRestarted() throws Exception {
        Parked p = parked("m-1", "t");
        assertFalse(rows.restart("m-1", "other", null, 2).await(20, TimeUnit.SECONDS), "no DLQ row of m-1 in flow other");
        unchanged(p);
        assertTrue(rows.restart("m-1", "t", null, 2).await(20, TimeUnit.SECONDS), "witness: in its own flow it restarts");
    }

    /** R1 (review 14b r1): the route read attempt 2; the row there now was parked at k = 3 — another row than the one read. */
    @Test
    void aRowParkedAtAnotherAttemptThanTheOneReadIsNotRestarted() throws Exception {
        Parked p = ParkedRows.byTheWorker("m-1", "t", 3);
        ParkedRows.park(pool, p, NODE);
        assertFalse(rows.restart("m-1", "t", null, 2).await(20, TimeUnit.SECONDS), "the row read at attempt 2 is no longer there");
        unchanged(p);
        assertTrue(rows.restart("m-1", "t", null, 3).await(20, TimeUnit.SECONDS), "witness: at the attempt it was parked at it restarts");
    }

    /** R1: a DLQ row parked at a step other than 1 is not a worker's park; it is not restarted. */
    @Test
    void aRowAtAStepOtherThanOneIsNotRestarted() throws Exception {
        Parked p = ParkedRows.row("m-1", "t", 2, Props.empty().with(Envelope.ATTEMPT, 2));
        ParkedRows.park(pool, p, NODE);
        assertFalse(rows.restart("m-1", "t", null, 2).await(20, TimeUnit.SECONDS), "a step-2 row is not restarted");
        unchanged(p);
        parked("m-2", "t");
        assertTrue(rows.restart("m-2", "t", null, 2).await(20, TimeUnit.SECONDS), "witness: its step-1 twin restarts");
    }

    /** R1: a DLQ row of a replay (prop {@code replayOf}) is not restarted. */
    @Test
    void aRowOfAReplayIsNotRestarted() throws Exception {
        Parked p = ParkedRows.row("m-1", "t", 1, Props.empty().with(Envelope.ATTEMPT, 2).with(Envelope.REPLAY_OF, "m-0"));
        ParkedRows.park(pool, p, NODE);
        assertFalse(rows.restart("m-1", "t", null, 2).await(20, TimeUnit.SECONDS), "a replay's row is not restarted");
        unchanged(p);
        parked("m-2", "t");
        assertTrue(rows.restart("m-2", "t", null, 2).await(20, TimeUnit.SECONDS), "witness: its twin without replayOf restarts");
    }

    @Test
    void aKeyedRestartWaitsForItsKeysLock() throws Exception {
        parked("m-1", "t");
        SqlConnection holder = pool.getConnection().await();
        try {
            Transaction tx = holder.begin().await();
            holder.preparedQuery("select pg_advisory_xact_lock($1, hashtext($2))").execute(Tuple.of(PgWorkQueue.KEY_LOCK_CLASS, "t\u001FK")).await();
            Future<Boolean> restarted = rows.restart("m-1", "t", "K", 2);
            until(() -> ParkedRows.count(pool, "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'"
                    + " and wait_event = 'advisory'") == 1, "the restart waits on the key's lock");
            assertFalse(restarted.isComplete(), "not before the lock is released");
            assertEquals(0, ParkedRows.count(pool, "select count(*) from work"), "nothing inserted while it waits");
            tx.commit().await();
            assertTrue(restarted.await(20, TimeUnit.SECONDS), "restarted once the lock is released");
        } finally {
            holder.close().await();
        }
        assertEquals(1, ParkedRows.count(pool, "select count(*) from work where id = 'm-1' and partition_key = 'K'"), "under its key");
    }

    /**
     * A removal of the DLQ row in flight (a {@code /retry}'s, uncommitted) when the restart comes: the restart waits on the row's lock
     * and, once the removal committed, finds no row — false, not a failure, nothing written.
     */
    @Test
    void aRowRemovedWhileTheRestartWaitsOnItIsNotRestarted() throws Exception {
        parked("m-1", "t");
        SqlConnection remover = pool.getConnection().await();
        try {
            Transaction tx = remover.begin().await();
            remover.preparedQuery("delete from dlq where id = $1").execute(Tuple.of("m-1")).await();
            Future<Boolean> restarted = rows.restart("m-1", "t", null, 2);
            until(() -> ParkedRows.count(pool, "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'") == 1,
                    "the restart waits on the removal's row lock");
            tx.commit().await();
            assertFalse(restarted.await(20, TimeUnit.SECONDS), "the row went meanwhile: not restarted");
        } finally {
            remover.close().await();
        }
        assertEquals(0, ParkedRows.count(pool, "select count(*) from work"), "no work row");
        assertEquals(Optional.empty(), new PgDlq(pool).get("m-1").await(), "the removal stands");
    }

    static void until(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("not within 20 s: " + what);
            Thread.onSpinWait();
        }
    }
}
