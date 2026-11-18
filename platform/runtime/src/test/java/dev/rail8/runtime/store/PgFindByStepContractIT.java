package dev.rail8.runtime.store;

import dev.rail8.core.batch.BatchingWriter;
import dev.rail8.core.record.MessageStore;
import dev.rail8.runtime.db.Migrations;
import dev.rail8.runtime.time.VertxTimer;
import dev.rail8.testkit.SharedPostgres;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;

import java.time.Duration;

/**
 * The Postgres message store answers the search by step as every store does: over a migrated database, its tables emptied
 * before each case, rows written through the store's own batcher.
 */
class PgFindByStepContractIT extends FindByStepContract {

    static final SharedPostgres pg = SharedPostgres.database();
    static Vertx vertx;
    static Pool pool;
    PgMessageStore opened;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        PgConnectOptions connect = new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()).setCachePreparedStatements(true);
        pool = PgBuilder.pool().connectingTo(connect).with(new PoolOptions().setMaxSize(4)).using(vertx).build();
        Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    @AfterAll
    static void stop() {
        pool.close().await();
        vertx.close().await();
        pg.stop();
    }

    @AfterEach
    void close() {
        if (opened != null) opened.close();
    }

    @Override
    protected MessageStore emptyStore() {
        pool.query("truncate message_keys, messages").execute().await();
        opened = new PgMessageStore(pool, new VertxTimer(vertx), new BatchingWriter.Options(100, Duration.ofMillis(10), 2, 1000));
        return opened;
    }
}
