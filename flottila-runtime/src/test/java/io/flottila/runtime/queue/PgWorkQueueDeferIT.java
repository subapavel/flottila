package io.flottila.runtime.queue;

import io.flottila.core.queue.WorkQueue;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.flottila.testkit.PostgresImage;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-14a P3 (D-135 draft): {@code PgWorkQueue.defer(seq, attempts, delay)} sets {@code available_at} to the database's
 * {@code now()} plus {@code delay} — never the node's clock, which here is a fake one in 2001 (the queue's timer) — and clears the
 * holder ({@code locked_by}, and the lease with it: the row is claimable at its due even inside the lease it was handed out with),
 * keeping seq, id, attempts, doubts and the body ref ({@code body_*}, which pins its journal segment). Time on the database clock is
 * moved by shifting the row's {@code available_at} back, never by waiting.
 *
 * <p>The API assumed (CORE-14a, test-author): {@code Future<Boolean> WorkQueue.defer(long seq, int attempts, Duration delay)};
 * the queue built as the node builds it, {@code new PgWorkQueue(pool, node, null, timer)}.
 */
@Timeout(120)
class PgWorkQueueDeferIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static final String NODE = "n1";
    static final Duration LEASE = Duration.ofSeconds(30);
    static Vertx vertx;
    static Pool pool;
    PgWorkQueue queue;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        pool = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()).setCachePreparedStatements(true))
                .with(new PoolOptions().setMaxSize(4)).using(vertx).build();
        io.flottila.runtime.db.Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    @AfterAll
    static void stop() {
        pool.close().await();
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void open() {
        pool.query("truncate work").execute().await();
        queue = new PgWorkQueue(pool, NODE, null, new FakeTimer(FakeClock.at(Instant.parse("2001-01-01T00:00:00Z"))));
    }

    @AfterEach
    void close() throws Exception {
        queue.close().await(20, TimeUnit.SECONDS);
    }

    Row row(long seq) {
        return pool.preparedQuery("select id, attempts, doubts, locked_by, locked_until, available_at, held_at, body_node, body_segment, body_position,"
                + " body_length from work where seq = $1").execute(Tuple.of(seq)).await().iterator().next();
    }

    OffsetDateTime databaseNow() {
        return pool.query("select clock_timestamp()").execute().await().iterator().next().getOffsetDateTime(0);
    }

    /** The row's due {@code by} earlier on the database clock: as if that long had passed for it. */
    void dueEarlierBy(long seq, Duration by) {
        pool.preparedQuery("update work set available_at = available_at - make_interval(secs => $2) where seq = $1")
                .execute(Tuple.of(seq, by.toNanos() / 1e9)).await();
    }

    WorkQueue.Item claimedOne(String worker) {
        List<WorkQueue.Item> taken = queue.claim(worker, "t", 1, LEASE).await();
        assertEquals(1, taken.size(), "fixture: one row claimed: " + taken);
        return taken.get(0);
    }

    @Test
    void notBeforeOnTheDatabaseClock() {
        queue.enqueue("u1", "t", 1, null).await();
        WorkQueue.Item u1 = claimedOne("w1");
        Duration delay = Duration.ofMinutes(10).plusMillis(750);   // a fraction of a second: not truncated to whole seconds

        OffsetDateTime before = databaseNow();
        assertTrue(queue.defer(u1.seq(), u1.attempts(), delay).await(), "the current holder defers its row");
        OffsetDateTime after = databaseNow();
        OffsetDateTime due = row(u1.seq()).getOffsetDateTime("available_at");
        assertFalse(due.isBefore(before.plus(delay)), "due no earlier than the database's now() at the call plus the delay: " + due + " vs " + before);
        assertFalse(due.isAfter(after.plus(delay)), "and no later: " + due + " vs " + after);

        assertEquals(List.of(), queue.claim("w2", "t", 10, LEASE).await(), "not due on the database clock");
        dueEarlierBy(u1.seq(), Duration.ofMinutes(9));
        assertEquals(List.of(), queue.claim("w2", "t", 10, LEASE).await(), "still not due a minute before it");
        dueEarlierBy(u1.seq(), Duration.ofMinutes(1).plusMillis(751));
        assertEquals(List.of(new WorkQueue.Item(u1.seq(), "u1", "t", 1, null, 2)), queue.claim("w2", "t", 10, LEASE).await(),
                "due: claimed by another worker inside the lease it was handed out with — the defer cleared the lease with the holder");
    }

    @Test
    void aDeferKeepsTheRowsRefAndCountsAndClearsItsHolder() {
        queue.enqueue("d1", "t", 1, null).await();
        long seq = claimedOne("w1").seq();
        pool.preparedQuery("update work set body_node = $2, body_segment = 7, body_position = 128, body_length = 3000, locked_until = now() - interval '1 second'"
                + " where seq = $1").execute(Tuple.of(seq, NODE)).await();
        WorkQueue.Item again = claimedOne("w2");
        assertEquals(2, again.attempts(), "fixture: handed out again");
        assertEquals(1, again.doubts(), "fixture: in doubt");

        assertTrue(queue.defer(seq, 2, Duration.ofMinutes(1)).await());
        Row r = row(seq);
        assertEquals("d1", r.getString("id"));
        assertEquals(2, r.getInteger("attempts"), "attempts kept: the defer is no hand-out");
        assertEquals(1, r.getInteger("doubts"), "doubts kept");
        assertNull(r.getString("locked_by"), "the holder cleared");
        assertNull(r.getValue("held_at"), "not held");
        assertEquals(NODE, r.getString("body_node"), "the body ref kept");
        assertEquals(7L, r.getLong("body_segment"));
        assertEquals(128L, r.getLong("body_position"));
        assertEquals(3000L, r.getLong("body_length"));

        dueEarlierBy(seq, Duration.ofMinutes(2));
        WorkQueue.Item retried = claimedOne("w3");
        assertEquals(new WorkQueue.Item(seq, "d1", "t", 1, null, 3), retried, "the same row, its next hand-out attempt 3");
        assertEquals(1, retried.doubts(), "no doubt added by the deferred hand-out, the earlier one kept");
    }
}
