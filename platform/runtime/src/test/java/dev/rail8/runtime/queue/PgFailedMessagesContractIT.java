package dev.rail8.runtime.queue;

import dev.rail8.core.dlq.Dlq;
import dev.rail8.core.dlq.FailedMessages;
import dev.rail8.core.queue.WorkQueue;
import dev.rail8.runtime.store.PgDlq;
import dev.rail8.testkit.SharedPostgres;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * Runtime's Postgres acts pass the contract over a {@link PgWorkQueue} and its {@code dlq} table. Leases run on the database clock:
 * {@link #expireLeases} moves every {@code locked_until} into the past instead of waiting.
 */
class PgFailedMessagesContractIT extends FailedMessagesContract {

    static final SharedPostgres pg = SharedPostgres.database();
    static Vertx vertx;
    static Pool pool;
    private FailedMessages failed;

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
        pool.query("truncate work, dlq, body_refs, journal_segments, audit, messages").execute().await();
        PgWorkQueue queue = new PgWorkQueue(pool);
        failed = new PgFailedMessages(pool, queue);
        return queue;
    }

    @Override
    protected FailedMessages failed() {
        return failed;
    }

    @Override
    protected Dlq dlq() {
        return new PgDlq(pool);
    }

    @Override
    protected void kept(String messageId) {
        ParkedRows.keepRef(pool, messageId, NODE);
    }

    @Override
    protected void arrived(String messageId) {
        pool.preparedQuery("insert into messages(id, step, flow, body_hash) values ($1, 0, 't', $2)")
                .execute(Tuple.of(messageId, Buffer.buffer(new byte[Long.BYTES]))).await();
    }

    @Override
    protected void expireLeases() {
        pool.query("update work set locked_until = now() - interval '1 second' where locked_until is not null").execute().await();
    }
}
