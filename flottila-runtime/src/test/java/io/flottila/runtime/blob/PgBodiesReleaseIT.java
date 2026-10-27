package io.flottila.runtime.blob;

import io.flottila.api.BlobKey;
import io.flottila.runtime.db.Migrations;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
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
import static io.flottila.testkit.BlobStoreContract.awaitOutcome;
import static io.flottila.testkit.BlobStoreContract.collect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The local copy of a persisted body goes once (D-061): a memory body's before the persist completes, a spool body's after
 * it, on a virtual thread of its own. Two persists of one key release it once. A stream opened on the spool copy before the
 * persist and read after the unlink reads the durable bytes from Postgres; an unlink that fails is a WARNING — the body is
 * durable, the persist has succeeded, and the stale file costs disk until the next start clears the spool. Only a copy
 * proven durable falls back to the row (FIX-35): a stream over a copy never made durable whose file is gone fails, and so
 * does a persist over it, leaving its entry indexed and the row as it was.
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
        return spoolFile(dir, key);
    }

    /** The only spool file under {@code spool}. */
    static Path spoolFile(Path spool, BlobKey key) {
        List<Path> files = PgBodiesIT.files(spool);
        assertEquals(1, files.size(), "fixture: one spool file for " + key + ": " + files);
        return files.get(0);
    }

    /** The bytes of the key's row, or empty. */
    static Optional<Buffer> row(BlobKey key) {
        for (Row r : pool.preparedQuery("select bytes from bodies where message_id = $1 and step = $2 and part = $3")
                .execute(Tuple.of(key.messageId(), key.step(), key.part())).await()) {
            return Optional.of(r.getBuffer(0));
        }
        return Optional.empty();
    }

    /** Reads {@code stream} to its end or its failure, every chunk it delivers appended to {@code delivered}. */
    static Future<Void> drain(ReadStream<Buffer> stream, List<Buffer> delivered) {
        Promise<Void> ended = Promise.promise();
        stream.exceptionHandler(ended::tryFail);
        stream.endHandler(v -> ended.tryComplete());
        stream.handler(delivered::add);
        return ended.future();
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

    /**
     * The release is the persisted copy's only: a write of the key that lands while the insert waits keeps its own copy local.
     * Persisting that copy — other bytes than the durable ones — is refused and releases nothing (D-061 amended by FIX-35).
     */
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
        long budget = s.memoryInUse();
        Future<Void> refused = awaitOutcome(s.persist(key));
        assertTrue(refused.failed(), "the second write's bytes are not the durable ones: its persist fails (D-061 amended by FIX-35)");
        assertInstanceOf(IllegalStateException.class, refused.cause(), String.valueOf(refused.cause()));
        assertEquals(List.of(), released, "a refused persist releases nothing");
        assertEquals(Optional.of(TieredBlobStore.Location.MEMORY), s.location(key), "the second write's copy stays local");
        assertEquals(budget, s.memoryInUse(), "with its budget: the refused persist released none");
        assertEquals(second, await(s.read(key)), "and is still what the key reads");
        assertEquals(first, pool.preparedQuery("select bytes from bodies where message_id = $1 and step = $2 and part = $3")
                .execute(Tuple.of(key.messageId(), key.step(), key.part())).await().iterator().next().getBuffer(0), "the row keeps the persisted bytes");
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

    /**
     * FIX-35: every spool write has a file of its own. A persist's unlink runs on a virtual thread after the persist completed;
     * a write of the key landing before or after it keeps its own file — the unlink removes only the persisted copy's — so the
     * key reads the new bytes from the spool, and persisting them (other bytes than the durable ones) is refused with the
     * new copy and its file still local. No wait for the first unlink before the second write: either order must hold.
     */
    @Test
    void aKeyWrittenAgainRightAfterItsSpoolPersistKeepsAFileOfItsOwn() {
        TieredBlobStore s = spooling();
        BlobKey key = BlobKey.body("m-respooled", 0);
        Buffer first = PgBodiesIT.bytes(600, 17);
        Buffer second = PgBodiesIT.bytes(600, 18);   // the same size, other bytes
        await(s.write(key, first, PgBodiesIT.FLOW_MAX));
        Path firstFile = spoolFile(key);
        await(s.persist(key));   // its unlink is not awaited
        await(s.write(key, second, PgBodiesIT.FLOW_MAX));
        PgBodiesIT.until(() -> !Files.exists(firstFile), "the persisted copy's file is unlinked");
        Path secondFile = spoolFile(key);
        assertNotEquals(firstFile, secondFile, "the second write's file is its own");
        assertEquals(Optional.of(TieredBlobStore.Location.SPOOL), s.location(key), "the second copy is local");
        assertEquals(second, await(s.read(key)), "the key reads the second write's bytes from its own file");
        assertEquals(second, await(collect(s.open(key))), "streamed too");
        Future<Void> refused = awaitOutcome(s.persist(key));
        assertInstanceOf(IllegalStateException.class, refused.cause(), "other bytes under a durable key: " + refused.cause());
        assertEquals(List.of(secondFile), PgBodiesIT.files(dir), "the refused copy keeps its file");
        assertEquals(second, await(s.read(key)), "and is still what the key reads");
    }

    /**
     * FIX-35: a spool copy replaced by a write of its key goes with its own file, unlinked off the writer's path, and only that
     * file — whether the replacing write spills to a file of its own or stays in memory. The key reads the replacing bytes.
     */
    @Test
    void aSpoolCopyReplacedByAWriteOfItsKeyIsUnlinkedAndNothingElse() {
        TieredBlobStore s = PgBodiesIT.store(dir, 512, pool);   // 600 bytes spool, 300 stay in memory
        BlobKey key = BlobKey.body("m-replaced", 0);
        Buffer first = PgBodiesIT.bytes(600, 19);
        Buffer second = PgBodiesIT.bytes(600, 20);
        Buffer third = PgBodiesIT.bytes(300, 21);
        await(s.write(key, first, PgBodiesIT.FLOW_MAX));
        Path firstFile = spoolFile(key);
        await(s.write(key, second, PgBodiesIT.FLOW_MAX));
        PgBodiesIT.until(() -> !Files.exists(firstFile), "the replaced copy's file is unlinked");
        Path secondFile = spoolFile(key);
        assertNotEquals(firstFile, secondFile, "the replacing write's file is its own and stays");
        assertEquals(second, await(s.read(key)), "the key reads the replacing bytes");
        assertEquals(second, await(collect(s.open(key))), "streamed too");

        await(s.write(key, third, PgBodiesIT.FLOW_MAX));
        assertEquals(Optional.of(TieredBlobStore.Location.MEMORY), s.location(key), "fixture: the third write stays in memory");
        PgBodiesIT.until(() -> PgBodiesIT.files(dir).isEmpty(), "the spool copy a memory write replaced is unlinked");
        assertEquals(third, await(s.read(key)), "the key reads the memory copy");
        assertEquals(0, pool.preparedQuery("select 1 from bodies where message_id = $1").execute(Tuple.of(key.messageId())).await().size(),
                "nothing was persisted");
    }

    /**
     * FIX-35 round 2: a missing spool file reads the durable row only for an entry proven durable. The key is persisted (the
     * row holds A) and written again (B, spooled, other bytes); a stream is opened on B's copy, and a third write of the key
     * (C) replaces B's entry and unlinks B's file before the stream is first read. B was never made durable: the stream fails
     * and yields no byte — never the row's A. Twice: B never persisted, and B's persist refused (other bytes than the row)
     * before the open — a refused persist proves nothing durable.
     */
    @Test
    void aStreamOverASpoolCopyWhoseFileARewriteUnlinkedFailsAndNeverYieldsTheRow() throws InterruptedException {
        Buffer a = PgBodiesIT.bytes(600, 22);
        Buffer b = PgBodiesIT.bytes(600, 23);   // the same size, other bytes
        Buffer c = PgBodiesIT.bytes(600, 24);
        for (boolean refusedFirst : new boolean[] {false, true}) {
            String variant = refusedFirst ? "B's persist refused before the open" : "B never persisted";
            Path spool = dir.resolve(refusedFirst ? "refused" : "never-persisted");
            TieredBlobStore s = PgBodiesIT.store(spool, 0, pool);   // no memory tier: every write spools to a file of its own
            BlobKey key = BlobKey.body(refusedFirst ? "m-unlinked-refused" : "m-unlinked", 0);
            CountDownLatch aReleased = new CountDownLatch(1);
            s.afterRelease(k -> aReleased.countDown());
            await(s.write(key, a, PgBodiesIT.FLOW_MAX));
            await(s.persist(key));
            assertTrue(aReleased.await(10, TimeUnit.SECONDS), "fixture: A's spool copy was released (" + variant + ")");
            assertEquals(Optional.of(a), row(key), "fixture: the row holds A");
            await(s.write(key, b, PgBodiesIT.FLOW_MAX));
            Path bFile = spoolFile(spool, key);
            if (refusedFirst) {
                Future<Void> refused = awaitOutcome(s.persist(key));
                assertInstanceOf(IllegalStateException.class, refused.cause(), "fixture: B is not the row's bytes: " + refused.cause());
                assertTrue(Files.exists(bFile), "fixture: B's copy stays local with its file");
            }
            ReadStream<Buffer> opened = s.open(key);   // on B's copy; its file is opened when the stream is first read
            await(s.write(key, c, PgBodiesIT.FLOW_MAX));   // replaces B's entry: B's file is unlinked on a virtual thread
            PgBodiesIT.until(() -> !Files.exists(bFile), "B's spool file is unlinked (" + variant + ")");
            List<Buffer> delivered = new CopyOnWriteArrayList<>();
            Future<Void> read = awaitOutcome(drain(opened, delivered));
            Buffer got = Buffer.buffer();
            delivered.forEach(got::appendBuffer);
            String yielded = got.length() + " bytes" + (got.equals(a) ? ", the row's A" : "");
            assertTrue(read.failed(), "a stream over B, never durable, whose file is gone fails (" + variant + "); it ended after " + yielded);
            assertEquals(0, got.length(), "and yields no byte (" + variant + "): " + yielded);
            assertEquals(c, await(s.read(key)), "the key reads C, its current copy (" + variant + ")");
            assertEquals(Optional.of(a), row(key), "the row keeps A (" + variant + ")");
        }
    }

    /**
     * FIX-35 round 2: a persist over a spool entry whose file is gone — what a write of the key leaves when it lands between
     * the persist's index read and its lazy open, staged here by deleting the file — fails with an
     * {@link IllegalStateException} naming the key: the local copy is gone and was never proven durable, so a row under the
     * key is no witness of it. The entry stays indexed, the row is as it was, and the key's read fails rather than answering
     * with the row. Twice: the row holds A (the key persisted before B was written), and no row at all.
     */
    @Test
    void aPersistOverASpoolEntryWhoseFileIsGoneFailsAndKeepsTheEntryAndTheRow() throws IOException, InterruptedException {
        Buffer a = PgBodiesIT.bytes(600, 25);
        Buffer b = PgBodiesIT.bytes(600, 26);   // the same size, other bytes
        for (boolean rowFirst : new boolean[] {true, false}) {
            String variant = rowFirst ? "the row holds A" : "no row";
            Path spool = dir.resolve(rowFirst ? "row-first" : "no-row");
            TieredBlobStore s = PgBodiesIT.store(spool, 0, pool);   // no memory tier: every write spools to a file of its own
            BlobKey key = BlobKey.body(rowFirst ? "m-gone" : "m-gone-unpersisted", 0);
            if (rowFirst) {
                CountDownLatch aReleased = new CountDownLatch(1);
                s.afterRelease(k -> aReleased.countDown());
                await(s.write(key, a, PgBodiesIT.FLOW_MAX));
                await(s.persist(key));
                assertTrue(aReleased.await(10, TimeUnit.SECONDS), "fixture: A's spool copy was released");
            }
            await(s.write(key, b, PgBodiesIT.FLOW_MAX));
            Files.delete(spoolFile(spool, key));   // the state the race leaves: B's entry indexed, its file unlinked
            Optional<Buffer> before = row(key);
            assertEquals(rowFirst ? Optional.of(a) : Optional.empty(), before, "fixture: " + variant);
            Future<Void> persisted = awaitOutcome(s.persist(key));
            assertTrue(persisted.failed(), "B's local copy is gone and was never durable: its persist fails (" + variant + ")");
            IllegalStateException e = assertInstanceOf(IllegalStateException.class, persisted.cause(), variant + ": " + persisted.cause());
            assertTrue(e.getMessage().contains(key.toString()), "names the key: " + e.getMessage());
            assertEquals(Optional.of(TieredBlobStore.Location.SPOOL), s.location(key), "B's entry stays indexed (" + variant + ")");
            assertEquals(before, row(key), "the row is as it was (" + variant + ")");
            Future<Buffer> read = awaitOutcome(s.read(key));
            assertTrue(read.failed(), "the key's read fails rather than answering with the row (" + variant + "): "
                    + (read.succeeded() ? read.result().length() + " bytes" + (read.result().equals(a) ? ", the row's A" : "") : ""));
        }
    }

    static long lockWaiters() {
        return pool.preparedQuery("select count(*) from pg_stat_activity where datname = $1 and wait_event_type = 'Lock'")
                .execute(Tuple.of(pg.getDatabaseName())).await().iterator().next().getLong(0);
    }
}
