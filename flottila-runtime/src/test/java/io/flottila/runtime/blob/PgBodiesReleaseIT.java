package io.flottila.runtime.blob;

import io.flottila.api.BlobKey;
import io.flottila.runtime.db.Migrations;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static io.flottila.testkit.BlobStoreContract.await;
import static io.flottila.testkit.BlobStoreContract.collect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The local copy of a persisted body goes once (D-061): a memory body's before the persist completes, a spool body's after
 * it, on a virtual thread of its own. Two persists of one key release it once. A stream opened on the spool copy before the
 * persist and read after the unlink reads the durable bytes from Postgres; an unlink that fails is a WARNING — the body is
 * durable, the persist has succeeded, and the stale file costs disk until the next start clears the spool.
 */
class PgBodiesReleaseIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static Vertx vertx;
    static Pool pool;

    @TempDir Path dir;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        pool = PgBuilder.pool().connectingTo(options()).with(new PoolOptions().setMaxSize(4)).using(vertx).build();
        PgBodiesIT.neverDeleted(pool);
    }

    @AfterAll
    static void stop() {
        pool.close().await();
        vertx.close().await();
        pg.stop();
    }

    static PgConnectOptions options() {
        return new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432)).setDatabase(pg.getDatabaseName())
                .setUser(pg.getUsername()).setPassword(pg.getPassword());
    }

    @BeforeEach
    void noBodies() {
        pool.query("truncate bodies").execute().await();   // truncate is not a DELETE
    }

    /** A store with no memory tier: every body is spooled. */
    TieredBlobStore spooling() {
        return PgBodiesIT.store(dir, 0, pool);
    }

    Path spoolFile(BlobKey key) {
        List<Path> files = PgBodiesIT.files(dir);
        assertEquals(1, files.size(), "fixture: one spool file for " + key + ": " + files);
        return files.get(0);
    }

    @Test
    void aMemoryBodyIsReleasedBeforeItsPersistCompletes() {
        TieredBlobStore s = PgBodiesIT.store(dir, 1L << 16, pool);
        BlobKey key = BlobKey.body("m-memory", 0);
        await(s.write(key, PgBodiesIT.bytes(300, 13), PgBodiesIT.FLOW_MAX));
        List<String> released = new CopyOnWriteArrayList<>();
        s.afterRelease(k -> released.add(k + " with " + s.memoryInUse() + " bytes in memory"));
        Future<Void> persisted = s.persist(key);
        await(persisted);
        assertEquals(List.of(key + " with 0 bytes in memory"), released, "released once, its budget back, before the persist completed");
    }

    @Test
    void twoPersistsOfOneKeyReleaseItsLocalCopyOnce() {
        TieredBlobStore s = PgBodiesIT.store(dir, 1L << 16, pool);
        BlobKey key = BlobKey.body("m-twice", 0);
        Buffer bytes = PgBodiesIT.bytes(400, 14);
        await(s.write(key, bytes, PgBodiesIT.FLOW_MAX));
        List<BlobKey> released = new CopyOnWriteArrayList<>();
        s.afterRelease(released::add);
        PgConnection holder = PgConnection.connect(vertx, options()).await();
        Future<Void> first;
        Future<Void> second;
        try {
            Transaction held = holder.begin().await();
            try {
                // another session's uncommitted row under the key: both persists read the local copy, and both inserts wait
                holder.preparedQuery("insert into bodies(message_id, step, part, bytes) values ($1, $2, $3, $4)")
                        .execute(Tuple.of(key.messageId(), key.step(), key.part(), Buffer.buffer("rolled back"))).await();
                first = s.persist(key);
                second = s.persist(key);
                PgBodiesIT.until(() -> lockWaiters() == 2, "both inserts wait on the other session's row");
            } finally {
                held.rollback().await();
            }
        } finally {
            holder.close().await();
        }
        await(first);
        await(second);
        assertEquals(List.of(key), released, "the local copy is released by one of them");
        assertEquals(0, s.memoryInUse(), "its budget released once");
        assertEquals(Optional.empty(), s.location(key));
        assertEquals(bytes, await(s.read(key)));
    }

    /** The release is the persisted copy's only: a write of the key that lands while the insert waits keeps its own copy local. */
    @Test
    void aKeyWrittenAgainWhileItsPersistWaitsKeepsTheNewCopyLocal() {
        TieredBlobStore s = PgBodiesIT.store(dir, 1L << 16, pool);
        BlobKey key = BlobKey.body("m-rewritten", 0);
        Buffer first = PgBodiesIT.bytes(400, 15);
        Buffer second = PgBodiesIT.bytes(500, 16);
        await(s.write(key, first, PgBodiesIT.FLOW_MAX));
        List<BlobKey> released = new CopyOnWriteArrayList<>();
        s.afterRelease(released::add);
        PgConnection holder = PgConnection.connect(vertx, options()).await();
        Future<Void> persisted;
        try {
            Transaction held = holder.begin().await();
            try {
                holder.preparedQuery("insert into bodies(message_id, step, part, bytes) values ($1, $2, $3, $4)")
                        .execute(Tuple.of(key.messageId(), key.step(), key.part(), Buffer.buffer("rolled back"))).await();
                persisted = s.persist(key);
                PgBodiesIT.until(() -> lockWaiters() == 1, "the persist's insert waits on the other session's row");
                await(s.write(key, second, PgBodiesIT.FLOW_MAX));   // a redelivery writes the key again meanwhile
            } finally {
                held.rollback().await();
            }
        } finally {
            holder.close().await();
        }
        await(persisted);
        assertEquals(List.of(), released, "the persist released nothing: the index holds the second write's copy, not the one it persisted");
        assertEquals(Optional.of(TieredBlobStore.Location.MEMORY), s.location(key), "the second write's copy stays local");
        assertEquals(second, await(s.read(key)), "and is what the key reads while it is local");
        await(s.persist(key));
        assertEquals(List.of(key), released, "persisting it releases it");
        assertEquals(Optional.empty(), s.location(key));
        assertEquals(first, await(s.read(key)), "the first durable bytes win (D-061)");
    }

    @Test
    void aSpoolStreamOpenedBeforeThePersistReadsTheDurableBytesOnceItsFileIsGone() throws InterruptedException {
        TieredBlobStore s = spooling();
        BlobKey key = BlobKey.body("m-opened", 0);
        Buffer bytes = PgBodiesIT.bytes(800, 11);
        await(s.write(key, bytes, PgBodiesIT.FLOW_MAX));
        CountDownLatch unlinked = new CountDownLatch(1);
        s.afterRelease(k -> unlinked.countDown());
        ReadStream<Buffer> opened = s.open(key);   // on the spool copy; its file is opened when the stream is first read
        await(s.persist(key));
        assertTrue(unlinked.await(10, TimeUnit.SECONDS), "the spool copy was released");
        assertEquals(List.of(), PgBodiesIT.files(dir), "fixture: the spool file is gone");
        assertEquals(bytes, await(collect(opened)), "the stream reads the durable bytes instead of failing on the missing file");
    }

    @Test
    void aSpoolFileThatCannotBeUnlinkedIsAWarningAndThePersistStands() throws Exception {
        TieredBlobStore s = spooling();
        BlobKey key = BlobKey.body("m-stuck", 0);
        Buffer bytes = PgBodiesIT.bytes(700, 12);
        await(s.write(key, bytes, PgBodiesIT.FLOW_MAX));
        Path file = spoolFile(key);
        List<BlobKey> released = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        s.afterRelease(k -> {
            released.add(k);
            done.countDown();
        });
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Logger jul = Logger.getLogger(TieredBlobStore.class.getName());   // System.Logger delegates to it
        Handler handler = new Handler() {
            @Override public void publish(LogRecord r) {
                if (r.getLevel().intValue() >= Level.WARNING.intValue()) warnings.add(r);
            }

            @Override public void flush() {}

            @Override public void close() {}
        };
        jul.addHandler(handler);
        PgConnection holder = PgConnection.connect(vertx, options()).await();
        try {
            Future<Void> persisted;
            Transaction held = holder.begin().await();
            try {
                // another session's uncommitted row under the key: the persist has read the spool file and its insert waits
                holder.preparedQuery("insert into bodies(message_id, step, part, bytes) values ($1, $2, $3, $4)")
                        .execute(Tuple.of(key.messageId(), key.step(), key.part(), Buffer.buffer("rolled back"))).await();
                persisted = s.persist(key);
                PgBodiesIT.until(() -> lockWaiters() == 1, "the persist's insert waits on the other session's row");
                // meanwhile the spool file becomes a directory with something in it: no unlink can remove that
                Files.delete(file);
                Files.createDirectories(file.resolve("in-the-way"));
            } finally {
                held.rollback().await();
            }
            await(persisted);
            assertTrue(done.await(10, TimeUnit.SECONDS), "the release ran to its end although the unlink failed");
            assertEquals(List.of(key), released);
            assertEquals(Optional.empty(), s.location(key), "the index entry is gone");
            assertTrue(Files.isDirectory(file), "the unlink did not remove what was in the way");
            assertEquals(1, warnings.size(), "one WARNING: " + warnings.stream().map(LogRecord::getMessage).toList());
            assertTrue(warnings.get(0).getMessage().contains(key.toString()), "names the body: " + warnings.get(0).getMessage());
            assertNotNull(warnings.get(0).getThrown(), "with the unlink's failure");
            assertEquals(bytes, await(s.read(key)), "the body reads from Postgres");
        } finally {
            jul.removeHandler(handler);
            holder.close().await();
        }
    }

    static long lockWaiters() {
        return pool.preparedQuery("select count(*) from pg_stat_activity where datname = $1 and wait_event_type = 'Lock'")
                .execute(Tuple.of(pg.getDatabaseName())).await().iterator().next().getLong(0);
    }
}
