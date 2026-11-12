package io.flottila.runtime.queue;

import io.flottila.core.queue.WorkQueue;
import io.flottila.testkit.PostgresImage;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * P2-16a (D-121 A2): runtime's Postgres queue passes the hand-off contract — its {@code ENQUEUE} with {@code $node, $lease} writes
 * {@code locked_by}, {@code locked_until = now() + lease}, attempts 1 only for the row heading its key. Leases run on the database
 * clock: {@link #expireLeases} moves every {@code locked_until} into the past instead of waiting.
 */
class PgWorkQueueContractIT extends WorkQueueContract {

    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static Vertx vertx;
    static Pool pool;

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

    @Override
    protected WorkQueue emptyQueue() {
        pool.query("truncate work").execute().await();
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
}
