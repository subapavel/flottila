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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * CORE-7 C2 (D-122): runtime's Postgres queue built with the completion batcher — {@code new PgWorkQueue(pool, node, store, timer)}
 * — passes the same {@link WorkQueueContract} as the single-statement queue ({@link PgWorkQueueContractIT}) and core's in-memory one:
 * its {@code completeUnkeyed} goes through the batch statement, everything else as before. Each case's queue is closed after it.
 *
 * <p>The API assumed (CORE-7 packet, test-author's choices for approval): {@code PgWorkQueue(Pool, String node, TieredBlobStore store,
 * Timer timer)} with {@code store} null (no index to tell, as the three-argument form allows); {@code Future<Void> close()}.
 */
class PgBatchedWorkQueueContractIT extends WorkQueueContract {

    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static Vertx vertx;
    static Pool pool;
    PgWorkQueue batched;

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

    @AfterEach
    void closeTheQueue() throws Exception {
        if (batched != null) batched.close().await(20, TimeUnit.SECONDS);
    }

    @Override
    protected WorkQueue emptyQueue() {
        pool.query("truncate work").execute().await();
        batched = new PgWorkQueue(pool, NODE, null, new FakeTimer(FakeClock.at(Instant.parse("2026-11-11T10:00:00Z"))));
        return batched;
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
