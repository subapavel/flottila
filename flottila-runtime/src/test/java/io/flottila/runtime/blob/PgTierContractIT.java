package io.flottila.runtime.blob;

import io.flottila.api.BlobStore;
import io.flottila.runtime.db.Migrations;
import io.flottila.testkit.DurableBlobStoreContract;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;

/**
 * The tiered store with its Postgres tier (D-061) passes the durable contract — and the plain one — whether a body starts in
 * memory or in the encrypted spool before it is persisted.
 */
class PgTierContractIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final long MIB = 1L << 20;
    static Vertx vertx;
    static Pool pool;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        pool = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()))
                .with(new PoolOptions().setMaxSize(4)).using(vertx).build();
        PgBodiesIT.neverDeleted(pool);   // no contract case deletes a durable body
    }

    @AfterAll
    static void stop() {
        pool.close().await();
        vertx.close().await();
        pg.stop();
    }

    /** Every case starts without a durable body: the first durable bytes win, so a row left by another case would answer instead (truncate is not a DELETE). */
    @BeforeEach
    void noBodies() {
        pool.query("truncate bodies").execute().await();
    }

    /** Bodies start in memory (tier and budget above every body in the suite) and move to Postgres on persist. */
    @Nested
    class MemoryAndPg extends DurableBlobStoreContract {
        @TempDir Path spool;

        @Override protected BlobStore store() {
            return new TieredBlobStore(new TieredBlobStore.Config(TREE_LIMIT, 8 * MIB, 64 * MIB, spool, TieredBlobStoreTest.key(), null, false), hasher(),
                    new PgBodies(pool, MIB));
        }
    }

    /** No memory tier: bodies start in the encrypted spool and move to Postgres on persist. */
    @Nested
    class SpoolAndPg extends DurableBlobStoreContract {
        @TempDir Path spool;

        @Override protected BlobStore store() {
            return new TieredBlobStore(new TieredBlobStore.Config(TREE_LIMIT, 0, MIB, spool, TieredBlobStoreTest.key(), null, false), hasher(),
                    new PgBodies(pool, MIB));
        }

        @Override protected long bigSize() { return 12 * MIB; }
    }
}
