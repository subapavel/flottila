package dev.rail8.runtime.queue;

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
}
