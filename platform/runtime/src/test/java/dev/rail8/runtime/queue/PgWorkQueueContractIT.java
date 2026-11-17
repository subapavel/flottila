package dev.rail8.runtime.queue;

import dev.rail8.api.BlobKey;
import dev.rail8.api.BlobNotFoundException;
import dev.rail8.api.BodyLostException;
import dev.rail8.core.dlq.Dlq;
import dev.rail8.core.queue.WorkQueue;
import dev.rail8.runtime.store.PgDlq;
import dev.rail8.testkit.SharedPostgres;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-16a (D-121 A2): runtime's Postgres queue passes the hand-off contract — its {@code ENQUEUE} with {@code $node, $lease} writes
 * {@code locked_by}, {@code locked_until = now() + lease}, attempts 1 only for the row heading its key. Leases run on the database
 * clock: {@link #expireLeases} moves every {@code locked_until} into the past instead of waiting.
 */
class PgWorkQueueContractIT extends WorkQueueContract {

    static final SharedPostgres pg = SharedPostgres.database();
    static Vertx vertx;
    static Pool pool;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        pool = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()).setCachePreparedStatements(true))
                .with(new PoolOptions().setMaxSize(4)).using(vertx).build();
        dev.rail8.runtime.db.Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    @AfterAll
    static void stop() {
        pool.close().await();
        vertx.close().await();
        pg.stop();
    }

    @Override
    protected WorkQueue emptyQueue() {
        pool.query("truncate work, dlq, body_refs, journal_segments").execute().await();
        return new PgWorkQueue(pool);
    }

    @Override
    protected void expireLeases() {
        pool.query("update work set locked_until = now() - interval '1 second' where locked_until is not null").execute().await();
    }

    @Override
    protected void passTime(java.time.Duration d) {
        pool.preparedQuery("update work set available_at = available_at - make_interval(secs => $1), locked_until = locked_until - make_interval(secs => $1)")
                .execute(io.vertx.sqlclient.Tuple.of(d.toNanos() / 1e9)).await();
    }

    @Override
    protected void kept(String messageId) {
        ParkedRows.keepRef(pool, messageId, NODE);
    }

    @Override
    protected Dlq dlq() {
        return new PgDlq(pool);
    }

    /**
     * A stop without a kept ref of the message's ingress body fails {@link BlobNotFoundException} naming {@code <id>/0/body} and
     * writes neither half: no DLQ row, no stopped row, and the holder still completes its row.
     */
    @Test
    void aStopWithoutAKeptRefFailsBlobNotFoundAndWritesNeitherHalf() {
        await(queue.enqueue("k1", "t", 1, "K"));
        WorkQueue.Item k1 = claimedOne(NODE, "t");

        Throwable failed = assertThrows(Throwable.class, () -> await(queue.stop(k1.seq(), k1.attempts(), failure("k1", "t", k1.attempts(), "K"))));

        assertEquals(BlobKey.body("k1", 0), assertInstanceOf(BlobNotFoundException.class, failed).key(), "it names the ingress body");
        neitherHalf(k1);
    }

    /** A stop whose kept ref an accepted loss gave up fails {@link BodyLostException} and writes neither half. */
    @Test
    void aStopWhoseKeptRefWasGivenUpFailsBodyLostAndWritesNeitherHalf() {
        await(queue.enqueue("k1", "t", 1, "K"));
        WorkQueue.Item k1 = claimedOne(NODE, "t");
        kept("k1");
        pool.preparedQuery("update body_refs set lost_at = now() where message_id = $1").execute(io.vertx.sqlclient.Tuple.of("k1")).await();

        Throwable failed = assertThrows(Throwable.class, () -> await(queue.stop(k1.seq(), k1.attempts(), failure("k1", "t", k1.attempts(), "K"))));

        assertInstanceOf(BodyLostException.class, failed);
        neitherHalf(k1);
    }

    /** No DLQ row of {@code item}'s message, no stopped row, and its holder still completes it. */
    private void neitherHalf(WorkQueue.Item item) {
        assertEquals(Optional.empty(), await(dlq().get(item.messageId())), "no DLQ row");
        assertEquals(List.of(), await(queue.stopped(Optional.empty(), 10)).items(), "no stopped row");
        assertTrue(await(queue.completeRow(item.seq(), item.attempts())).completed(), "the row is still its holder's");
    }
}
