package io.flottila.runtime.blob;

import io.flottila.api.BlobStore;
import io.flottila.api.Hasher;
import io.flottila.runtime.db.Migrations;
import io.flottila.runtime.journal.TestJournals;
import io.flottila.testkit.DurableBlobStoreContract;
import io.flottila.testkit.PostgresImage;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The tiered store with its Postgres tier (D-061) passes the durable contract — and the plain one — whether a body starts in
 * memory or in the encrypted spool before it is persisted.
 */
class PgTierContractIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
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
    }

    @AfterAll
    static void stop() {
        pool.close().await();
        vertx.close().await();
        pg.stop();
    }

    /** Every case starts without a durable body: the first durable bytes win, so a ref left by another case would answer instead. */
    @BeforeEach
    void noBodies() {
        pool.query("truncate bodies, body_refs, journal_segments, journal_nodes").execute().await();
    }

    /**
     * Bodies start in memory (tier and budget above every body in the suite) and move into the node's journal on persist, their
     * refs in Postgres. CORE-4c2: was "and move to Postgres" (the {@code bodies} tier, D-105); {@code SpoolAndPg} (no memory
     * tier: the encrypted spool, then Postgres) is retired with the spool — its journal twin is
     * {@code JournalBlobStoreIT.LargeBodies}, which spills from the first byte above the tier.
     */
    @Nested
    class MemoryAndJournal extends DurableBlobStoreContract {
        @TempDir Path dir;
        TestJournals.Opened journal;

        @Override protected BlobStore store() {
            try {
                if (journal == null) journal = TestJournals.open(pool, Files.createDirectories(dir.resolve("journal")), "node-1");
            } catch (Exception e) {
                throw new IllegalStateException("fixture: the journal did not open", e);
            }
            return new TieredBlobStore(new TieredBlobStore.Config(TREE_LIMIT, 8 * MIB, 64 * MIB, MIB), Hasher.sha256(), journal.journal(), journal.tenant(), pool);
        }

        @AfterEach
        void closeTheJournal() throws IOException {
            if (journal != null) journal.close();
        }
    }
}
