package io.flottila.runtime.queue;

import io.flottila.core.queue.WorkQueue;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-7 (D-122), the edges the batched-completion cases leave open (implementer): {@code refused} counts only the members that
 * waited out maxWait — neither a member refused by a closed queue nor one failed by its statement —, and a queue built without a
 * timer keeps the single statement: its {@code completeUnkeyed} is {@code completeRow} (a keyed row completes too), it counts
 * nothing and its {@code close} has nothing to wait for. Two members for one row and token gathered in one batch: the row is
 * deleted once, so one answers done and the other STALE, as the second of two single-row calls would.
 */
@Timeout(120)
class PgWorkQueueCompletionEdgesIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final String FLOW = "orders";
    static final Duration LEASE = Duration.ofMinutes(5);
    static final WorkQueue.Completion DONE = new WorkQueue.Completion(true, Optional.empty(), Optional.empty());
    static Vertx vertx;
    static Pool pool;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        io.flottila.runtime.db.Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        pool = pool();
    }

    @AfterAll
    static void stop() {
        pool.close().await();
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void empty() {
        pool.query("truncate work").execute().await();
    }

    static Pool pool() {
        return PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()))
                .with(new PoolOptions().setMaxSize(2)).using(vertx).build();
    }

    static <T> T await(Future<T> f) throws Exception {
        return f.await(20, TimeUnit.SECONDS);
    }

    static <T> AsyncResult<T> settled(Future<T> f) throws Exception {
        return await(f.transform(Future::succeededFuture));
    }

    PgWorkQueue batched(Pool over) {
        return new PgWorkQueue(over, "n1", null, new FakeTimer(FakeClock.at(Instant.parse("2026-11-11T10:00:00Z"))));
    }

    /** A member handed over after close fails at once, IllegalStateException, and is no refusal: nothing was sent. */
    @Test
    void aMemberAfterCloseFailsAndIsNotCountedRefused() throws Exception {
        PgWorkQueue fixture = new PgWorkQueue(pool);
        await(fixture.enqueue("a", FLOW, 1, null));
        WorkQueue.Item a = await(fixture.claim("n1", FLOW, 1, LEASE)).getFirst();
        PgWorkQueue queue = batched(pool);
        await(queue.close());
        AsyncResult<WorkQueue.Completion> r = settled(queue.completeUnkeyed(a.seq(), a.attempts()));
        assertTrue(r.failed(), "refused by the closed queue: " + r.result());
        assertInstanceOf(IllegalStateException.class, r.cause());
        assertEquals(new PgWorkQueue.Completions(0, 0, 0, 0), queue.completions(), "nothing sent, nothing refused for want of room");
        assertEquals(1L, pool.query("select count(*) from work").execute().await().iterator().next().getLong(0), "the row stays");
    }

    /** A statement that fails (its pool closed) fails its member with that cause: one statement sent, no stale, no refusal. */
    @Test
    void aFailedStatementIsNotCountedRefused() throws Exception {
        Pool closed = pool();
        await(closed.close());
        PgWorkQueue queue = batched(closed);
        AsyncResult<WorkQueue.Completion> r = settled(queue.completeUnkeyed(1, 1));
        assertTrue(r.failed(), "the statement failed: " + r.result());
        assertEquals(new PgWorkQueue.Completions(1, 1, 0, 0), queue.completions(), "sent and failed: neither stale nor refused");
        await(queue.close());
    }

    /** Built without a timer: completeUnkeyed is completeRow (a keyed row completes), nothing is counted, close is done at once. */
    @Test
    void aQueueWithoutATimerCompletesUnkeyedByItsSingleStatement() throws Exception {
        PgWorkQueue queue = new PgWorkQueue(pool, "n1");
        await(queue.enqueue("k", FLOW, 1, "K"));
        WorkQueue.Item k = await(queue.claim("n1", FLOW, 1, LEASE)).getFirst();
        assertEquals(DONE, await(queue.completeUnkeyed(k.seq(), k.attempts())), "the single statement completes a keyed row too");
        assertEquals(0L, pool.query("select count(*) from work").execute().await().iterator().next().getLong(0), "the row is gone");
        assertEquals(new PgWorkQueue.Completions(0, 0, 0, 0), queue.completions(), "no batch: nothing counted");
        Future<Void> closing = queue.close();
        assertTrue(closing.succeeded(), "nothing to wait for: closed at once");
    }

    /** Spins (never sleeping) until a DELETE of work waits on a lock in Postgres; fails after 20 s. */
    static void untilADeleteWaits() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (pool.query("select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock' "
                + "and query ilike 'delete from work%'").execute().await().iterator().next().getInteger(0) != 1) {
            if (System.nanoTime() - deadline > 0) throw new AssertionError("no DELETE waits on the lock within 20 s");
            Thread.onSpinWait();
        }
    }

    /** One row, one token, two members in one batch: the row deleted once — the first member done, the second STALE. */
    @Test
    void twoMembersForOneRowAndTokenInOneBatchCompleteItOnce() throws Exception {
        PgWorkQueue fixture = new PgWorkQueue(pool);
        for (String id : new String[] {"first", "twice"}) await(fixture.enqueue(id, FLOW, 1, null));
        List<WorkQueue.Item> items = await(fixture.claim("n1", FLOW, 2, LEASE));
        Pool own = pool();   // the queue's own connections: the holder and the waiting DELETE leave the fixture pool one for the probe
        PgWorkQueue queue = batched(own);
        SqlConnection holder = await(pool.getConnection());
        Transaction tx = await(holder.begin());
        Future<WorkQueue.Completion> first;
        Future<WorkQueue.Completion> once;
        Future<WorkQueue.Completion> again;
        try {
            await(holder.query("lock table work in share mode").execute());
            first = queue.completeUnkeyed(items.get(0).seq(), 1);
            untilADeleteWaits();
            once = queue.completeUnkeyed(items.get(1).seq(), 1);
            again = queue.completeUnkeyed(items.get(1).seq(), 1);
        } finally {
            await(tx.rollback());
            await(holder.close());
        }
        assertEquals(DONE, await(first));
        assertEquals(DONE, await(once), "the first member of the row: done");
        assertEquals(WorkQueue.Completion.STALE, await(again), "the second: the row is already gone");
        assertEquals(new PgWorkQueue.Completions(2, 3, 1, 0), queue.completions(), "two statements, three members, one stale");
        await(queue.close());
        await(own.close());
    }
}
