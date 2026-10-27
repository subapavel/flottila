package io.flottila.runtime.blob;

import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.BodyTooLargeException;
import io.flottila.api.Hasher;
import io.flottila.api.Headers;
import io.flottila.api.Health;
import io.flottila.api.Keys;
import io.flottila.api.Props;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Snapshot;
import io.flottila.runtime.admin.AdminServer;
import io.flottila.runtime.db.Migrations;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static io.flottila.testkit.BlobStoreContract.await;
import static io.flottila.testkit.BlobStoreContract.awaitOutcome;
import static io.flottila.testkit.BlobStoreContract.collect;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The Postgres tier of {@link TieredBlobStore} (D-058, D-061, F-6): {@code persist} moves a memory or spool body into
 * {@code bodies(message_id, step, part, bytes)} and completes only once that insert committed; then the local copy is gone —
 * the index entry and the memory budget before the future completes, the spool file afterwards off the caller's path — and
 * every read of the key goes to Postgres, by a store of this node or a fresh one (a new spool directory, key and pool). The
 * first durable bytes win for a key; above the durable bound nothing is written and the body stays local; a failed insert
 * keeps the body local too. Every case runs on a database of its own (FIX-5).
 *
 * <p>The test seam {@code afterRelease(Consumer<BlobKey>)} as these cases read it: called once per persisted local body,
 * with its key, once its local copy is gone — for a spool body after the unlink (attempted) on the unlinking virtual thread.
 */
class PgBodiesIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final AtomicInteger cases = new AtomicInteger();
    static final Hasher HASHER = Hasher.sha256();
    static final long BOUND = 1000;   // the durable bound of every store here
    static final long FLOW_MAX = 4 * BOUND;   // the flow's max body size: writes above the durable bound are accepted
    static Vertx vertx;
    static Pool observer;   // on the container's own database: never a session of the case database

    @TempDir Path dir;
    String database;
    Pool pool;    // the stores' pool
    Pool other;   // another session's pool on the case database: what "another session sees" reads through

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        observer = PgBuilder.pool().connectingTo(options(pg.getDatabaseName())).with(new PoolOptions().setMaxSize(2)).using(vertx).build();
    }

    @AfterAll
    static void stop() {
        observer.close().await();
        vertx.close().await();
        pg.stop();
    }

    static PgConnectOptions options(String database) {
        return new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432)).setDatabase(database)
                .setUser(pg.getUsername()).setPassword(pg.getPassword());
    }

    @BeforeEach
    void createTheCaseDatabase() throws SQLException {
        database = "bodies_" + cases.incrementAndGet();
        try (Connection admin = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement create = admin.createStatement()) {
            create.execute("CREATE DATABASE \"" + database + "\"");
        }
        // getJdbcUrl() is jdbc:postgresql://host:port/<db> followed by the container's query string (?loggerLevel=OFF)
        String base = pg.getJdbcUrl();
        int query = base.indexOf('?');
        String path = query < 0 ? base : base.substring(0, query);
        String suffix = "/" + pg.getDatabaseName();
        assertTrue(path.endsWith(suffix), "the container URL ends with its database: " + base);
        Migrations.migrate(path.substring(0, path.length() - suffix.length()) + "/" + database + (query < 0 ? "" : base.substring(query)),
                pg.getUsername(), pg.getPassword());
        pool = newPool();
        other = newPool();
        neverDeleted(other);
    }

    /**
     * Only the retention job deletes from {@code bodies} (P2-07): any DELETE statement on the table fails while this trigger
     * stands, so a store path that deletes fails its case. A case that plays the retention job disables it first.
     */
    static void neverDeleted(Pool p) {
        p.query("create function bodies_never_deleted() returns trigger language plpgsql as "
                + "$$ begin raise exception 'a DELETE on bodies: only the retention job deletes (P2-07)'; end $$").execute().await();
        p.query("create trigger bodies_never_deleted before delete on bodies for each statement execute function bodies_never_deleted()").execute().await();
    }

    @AfterEach
    void closeThePools() {
        if (pool != null) pool.close().await();
        if (other != null) other.close().await();
    }

    Pool newPool() {
        return PgBuilder.pool().connectingTo(options(database)).with(new PoolOptions().setMaxSize(4)).using(vertx).build();
    }

    /** A store over {@code spool} with a fresh spool key, a memory tier of {@code memoryTier} bytes and the Postgres tier over {@code p}. */
    static TieredBlobStore store(Path spool, long memoryTier, Pool p) {
        return new TieredBlobStore(new TieredBlobStore.Config(FLOW_MAX, memoryTier, 1L << 20, spool, TieredBlobStoreTest.key(), null, false), HASHER,
                new PgBodies(p, BOUND));
    }

    /** {@code size} bytes of a repeating, position-dependent pattern: a shifted or truncated copy does not compare equal. */
    static Buffer bytes(int size, int seed) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) b[i] = (byte) (i * 31 + seed);
        return Buffer.buffer(b);
    }

    /** The bytes of the key's row as another session sees them, or empty. */
    Optional<Buffer> row(BlobKey k) {
        for (Row r : other.preparedQuery("select bytes from bodies where message_id = $1 and step = $2 and part = $3")
                .execute(Tuple.of(k.messageId(), k.step(), k.part())).await()) {
            return Optional.of(r.getBuffer(0));
        }
        return Optional.empty();
    }

    long rows() {
        return other.query("select count(*) from bodies").execute().await().iterator().next().getLong(0);
    }

    /** Backends of the case database waiting on a lock. */
    long lockWaiters() {
        return observer.preparedQuery("select count(*) from pg_stat_activity where datname = $1 and wait_event_type = 'Lock'")
                .execute(Tuple.of(database)).await().iterator().next().getLong(0);
    }

    static List<Path> files(Path spool) {
        return Files.exists(spool) ? TieredBlobStoreTest.spoolFiles(spool) : List.of();
    }

    @Test
    void aPersistedBodyIsReadByAFreshStoreOverANewSpoolKeyAndPool() {
        BlobKey k = BlobKey.body("m-restart", 0);
        Buffer original = bytes(900, 7);
        TieredBlobStore before = store(dir.resolve("before"), 0, pool);   // no memory tier: the body is spooled under this start's key
        Body body = await(before.write(k, original, FLOW_MAX));
        assertEquals(Optional.of(TieredBlobStore.Location.SPOOL), before.location(k), "fixture: spooled");
        await(before.persist(k));
        pool.close().await();   // the first store's pool is gone with its process
        pool = null;

        Pool fresh = newPool();
        try {
            TieredBlobStore after = store(dir.resolve("after"), 1L << 16, fresh);   // a new spool directory and key, a new pool
            assertEquals(Optional.empty(), after.location(k), "the fresh store holds nothing locally");
            assertEquals(original, await(after.read(k)), "read by key from Postgres");
            assertEquals(original, await(collect(after.open(k))), "streamed by key from Postgres");
            assertEquals(original, Body.of(after, k, body.hash(), body.size()).whole(), "a body rebuilt from its key reads whole");
            assertEquals(Optional.empty(), after.location(k), "reading caches nothing locally");
        } finally {
            fresh.close().await();
        }
    }

    @Test
    void persistCompletesOnlyOnceItsInsertCommitted() {
        BlobKey k = BlobKey.body("m-held", 0);
        Buffer original = bytes(600, 1);
        TieredBlobStore s = store(dir, 1L << 16, pool);
        await(s.write(k, original, FLOW_MAX));
        Future<Void> persisted;
        PgConnection holder = PgConnection.connect(vertx, options(database)).await();
        try {
            Transaction held = holder.begin().await();
            // another session's uncommitted row under the same key: the persist's insert waits for that transaction to end
            holder.preparedQuery("insert into bodies(message_id, step, part, bytes) values ($1, $2, $3, $4)")
                    .execute(Tuple.of(k.messageId(), k.step(), k.part(), Buffer.buffer("the other session's, rolled back"))).await();
            persisted = s.persist(k);
            until(() -> lockWaiters() == 1, "the persist's insert waits on the other session's uncommitted row");
            assertFalse(persisted.isComplete(), "not complete while its insert is not committed: " + persisted);
            assertEquals(Optional.of(TieredBlobStore.Location.MEMORY), s.location(k), "the local copy stays until the insert committed");
            assertEquals(600, s.memoryInUse(), "and so does its budget");
            assertEquals(Optional.empty(), row(k), "another session sees no row yet");
            held.rollback().await();
        } finally {
            holder.close().await();
        }
        await(persisted);
        assertEquals(Optional.of(original), row(k), "once persist completed, another session sees the row with the body's bytes");
        assertEquals(Optional.empty(), s.location(k), "and the local copy is gone");
        assertEquals(0, s.memoryInUse());
    }

    @Test
    void aBodyOfExactlyTheBoundPersists() {
        BlobKey k = BlobKey.body("m-exact", 0);
        Buffer exact = bytes((int) BOUND, 2);
        TieredBlobStore s = store(dir, 1L << 16, pool);
        await(s.write(k, exact, FLOW_MAX));
        await(s.persist(k));
        assertEquals(Optional.of(exact), row(k), "a body of exactly the bound is durable");
        assertEquals(exact, await(s.read(k)));
    }

    @Test
    void aBodyAboveTheBoundFailsTooLargeWritesNoRowAndStaysReadable() {
        TieredBlobStore s = store(dir, 2 * BOUND, pool);
        BlobKey inMemory = BlobKey.body("m-above", 0);
        Buffer above = bytes((int) BOUND + 1, 3);
        await(s.write(inMemory, above, FLOW_MAX));
        TieredBlobStore spooling = store(dir.resolve("spool-only"), 0, pool);
        BlobKey spooled = BlobKey.body("m-above-spooled", 0);
        await(spooling.write(spooled, above, FLOW_MAX));
        assertEquals(Optional.of(TieredBlobStore.Location.MEMORY), s.location(inMemory), "fixture");
        assertEquals(Optional.of(TieredBlobStore.Location.SPOOL), spooling.location(spooled), "fixture");

        for (Object[] c : new Object[][] {{s, inMemory}, {spooling, spooled}}) {
            TieredBlobStore store = (TieredBlobStore) c[0];
            BlobKey k = (BlobKey) c[1];
            Future<Void> f = awaitOutcome(store.persist(k));
            assertTrue(f.failed(), "above the durable bound: " + k);
            BodyTooLargeException e = assertInstanceOf(BodyTooLargeException.class, f.cause(), String.valueOf(f.cause()));
            assertEquals(k, e.key());
            assertEquals(BOUND + 1, e.size());
            assertEquals(BOUND, e.limit(), "the limit is the durable bound, not the flow's max");
            assertEquals(above, await(store.read(k)), "the body stays readable");
        }
        assertEquals(0, rows(), "nothing written to bodies");
        assertEquals(Optional.of(TieredBlobStore.Location.MEMORY), s.location(inMemory), "the body stays local");
        assertEquals(BOUND + 1, s.memoryInUse(), "its budget stays reserved");
        assertEquals(Optional.of(TieredBlobStore.Location.SPOOL), spooling.location(spooled));
        assertEquals(1, files(dir.resolve("spool-only")).size(), "the spool file stays");
    }

    @Test
    void persistReleasesTheBudgetAndTheIndexEntryAndUnlinksTheSpoolFile() throws InterruptedException {
        TieredBlobStore s = store(dir, 512, pool);   // 300 bytes stay in memory, 800 go to the spool
        BlobKey small = BlobKey.body("m-small", 0);
        BlobKey large = BlobKey.body("m-large", 0);
        Buffer smallBytes = bytes(300, 4);
        Buffer largeBytes = bytes(800, 5);
        List<BlobKey> released = new CopyOnWriteArrayList<>();
        CountDownLatch largeReleased = new CountDownLatch(1);
        s.afterRelease(k -> {
            released.add(k);
            if (k.equals(large)) largeReleased.countDown();
        });
        await(s.write(small, smallBytes, FLOW_MAX));
        await(s.write(large, largeBytes, FLOW_MAX));
        assertEquals(Optional.of(TieredBlobStore.Location.MEMORY), s.location(small), "fixture");
        assertEquals(Optional.of(TieredBlobStore.Location.SPOOL), s.location(large), "fixture");
        assertEquals(300, s.memoryInUse(), "fixture");
        assertEquals(1, files(dir).size(), "fixture: the large body's spool file");

        await(s.persist(small));
        assertEquals(0, s.memoryInUse(), "the budget is released before persist completes");
        assertEquals(Optional.empty(), s.location(small), "and the index entry removed");
        await(s.persist(large));
        assertEquals(Optional.empty(), s.location(large), "the spool body's index entry is removed before persist completes");

        assertTrue(largeReleased.await(10, TimeUnit.SECONDS), "afterRelease saw the spool body's key: " + released);
        until(() -> files(dir).isEmpty(), "the spool file is unlinked after the persist: " + files(dir));
        assertEquals(1, released.stream().filter(large::equals).count(), "released once: " + released);
        assertEquals(Optional.of(smallBytes), row(small));
        assertEquals(Optional.of(largeBytes), row(large));
        assertEquals(smallBytes, await(s.read(small)), "read back from Postgres");
        assertEquals(largeBytes, await(collect(s.open(large))), "streamed back from Postgres");

        await(s.evict(small));   // forgetting a persisted body frees nothing durable: only the retention job deletes rows
        await(s.evict(large));
        assertEquals(Optional.of(smallBytes), row(small), "evict deleted no row");
        assertEquals(Optional.of(largeBytes), row(large));
        assertEquals(largeBytes, await(s.read(large)), "and the key still reads");
    }

    @Test
    void aFailedInsertFailsPersistAndKeepsTheBodyLocal() {
        other.query("create function refuse_bodies() returns trigger language plpgsql as $$ begin raise exception 'bodies refused by the test'; end $$")
                .execute().await();
        other.query("create trigger refuse_bodies before insert on bodies for each row execute function refuse_bodies()").execute().await();
        TieredBlobStore s = store(dir, 512, pool);
        BlobKey inMemory = BlobKey.body("m-refused", 0);
        BlobKey spooled = BlobKey.body("m-refused-spooled", 0);
        Buffer smallBytes = bytes(300, 6);
        Buffer largeBytes = bytes(800, 7);
        await(s.write(inMemory, smallBytes, FLOW_MAX));
        await(s.write(spooled, largeBytes, FLOW_MAX));
        assertEquals(Optional.of(TieredBlobStore.Location.SPOOL), s.location(spooled), "fixture");

        for (BlobKey k : List.of(inMemory, spooled)) {
            Future<Void> f = awaitOutcome(s.persist(k));
            assertTrue(f.failed(), "the insert failed, so the persist fails: " + k);
            assertTrue(causes(f.cause()).stream().anyMatch(m -> m.contains("bodies refused by the test")), "with the insert's failure: " + causes(f.cause()));
        }
        assertEquals(0, rows(), "no row");
        assertEquals(Optional.of(TieredBlobStore.Location.MEMORY), s.location(inMemory), "the memory body stays local");
        assertEquals(300, s.memoryInUse(), "with its budget");
        assertEquals(Optional.of(TieredBlobStore.Location.SPOOL), s.location(spooled), "the spool body stays local");
        assertEquals(1, files(dir).size(), "with its spool file");
        assertEquals(smallBytes, await(s.read(inMemory)));
        assertEquals(largeBytes, await(s.read(spooled)));

        other.query("drop trigger refuse_bodies on bodies").execute().await();
        await(s.persist(inMemory));
        await(s.persist(spooled));
        assertEquals(Optional.of(smallBytes), row(inMemory), "the kept bodies persist once the insert succeeds");
        assertEquals(Optional.of(largeBytes), row(spooled));
    }

    @Test
    void aMissingRowFailsReadAndOpen() {
        TieredBlobStore s = store(dir, 1L << 16, pool);
        BlobKey never = BlobKey.body("m-never", 0);
        Future<Buffer> read = awaitOutcome(s.read(never));
        assertTrue(read.failed(), "no local copy, no row");
        assertInstanceOf(IllegalArgumentException.class, read.cause(), String.valueOf(read.cause()));
        ReadStream<Buffer> lazy = assertDoesNotThrow(() -> s.open(never), "open is lazy: it asks Postgres when the stream is read");
        Future<Buffer> streamed = awaitOutcome(collect(lazy));
        assertTrue(streamed.failed(), "the stream fails");
        assertInstanceOf(IllegalArgumentException.class, streamed.cause(), String.valueOf(streamed.cause()));

        // a persisted body whose row is gone (the retention job's delete, P2-07): no local copy is left to answer instead
        BlobKey gone = BlobKey.body("m-gone", 0);
        await(s.write(gone, bytes(100, 8), FLOW_MAX));
        await(s.persist(gone));
        other.query("alter table bodies disable trigger bodies_never_deleted").execute().await();   // the case plays the retention job
        other.preparedQuery("delete from bodies where message_id = $1").execute(Tuple.of(gone.messageId())).await();
        Future<Buffer> readGone = awaitOutcome(s.read(gone));
        assertTrue(readGone.failed(), "no local cache after persist");
        assertInstanceOf(IllegalArgumentException.class, readGone.cause(), String.valueOf(readGone.cause()));
        Future<Buffer> openGone = awaitOutcome(collect(s.open(gone)));
        assertTrue(openGone.failed());
        assertInstanceOf(IllegalArgumentException.class, openGone.cause(), String.valueOf(openGone.cause()));
    }

    @Test
    void aKeyWrittenAgainAfterPersistPersistsQuietlyAndTheFirstBytesWin() {
        TieredBlobStore s = store(dir, 1L << 16, pool);
        BlobKey k = BlobKey.body("m-again", 0);
        Buffer first = bytes(400, 9);
        Buffer second = bytes(500, 10);
        await(s.write(k, first, FLOW_MAX));
        await(s.persist(k));
        await(s.write(k, second, FLOW_MAX));   // a redelivery writes the key again
        assertEquals(Optional.of(TieredBlobStore.Location.MEMORY), s.location(k), "fixture: the second bytes are local");
        await(s.persist(k));
        assertEquals(Optional.of(first), row(k), "the first durable bytes win (as snapshots do, D-044)");
        assertEquals(first, await(s.read(k)), "and are what the key reads: no local copy of the second stays");
        assertEquals(Optional.empty(), s.location(k));
        assertEquals(0, s.memoryInUse(), "the second copy's budget is released too");
    }

    @Test
    void aBodyKeyWithNoIndexEntryAndNoRowIs404NoLongerStoredThroughTheAdminRoute() {
        TieredBlobStore s = store(dir, 1L << 16, pool);
        BlobKey kept = BlobKey.body("m-admin", 0);
        BlobKey gone = BlobKey.body("m-admin", 1);   // never written: no index entry, no row (retention took it, P2-07)
        Buffer xml = bytes(500, 3);
        Body body = await(s.write(kept, xml, FLOW_MAX));
        await(s.persist(kept));   // the witness: the same route over the same store answers from Postgres
        assertEquals(Optional.empty(), s.location(gone), "fixture: no index entry for the gone key");
        assertEquals(Optional.empty(), row(gone), "fixture: no row for the gone key");
        InMemoryMessageStore messages = new InMemoryMessageStore();
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        messages.record(new Snapshot("m-admin", "invoice", 0, "ingress", Snapshot.Outcome.OK, Optional.empty(), at, 1L, 1L, Headers.empty(), Props.empty(),
                Keys.empty(), body.hash(), body.size(), Optional.of(kept), Map.of())).await();
        messages.record(new Snapshot("m-admin", "invoice", 1, "map", Snapshot.Outcome.OK, Optional.empty(), at, 1L, 1L, Headers.empty(), Props.empty(),
                Keys.empty(), body.hash(), body.size(), Optional.of(gone), Map.of())).await();
        String token = "pg-admin-token";
        AdminServer admin = new AdminServer(new AdminServer.Config("127.0.0.1", 0, Optional.of(token)), messages, new InMemoryDlq(), s,
                new AdminServer.HealthProbe(Map.of("postgres", () -> Future.succeededFuture(Health.up())), () -> 0L));
        int port = admin.start(vertx).await();
        HttpClientAgent client = vertx.createHttpClient();
        try {
            Answer missing = get(client, port, token, "/admin/messages/m-admin/steps/1/body");
            assertEquals(404, missing.status(), "a key with neither an index entry nor a row is a 404 (D-057), not a 500: " + missing.body());
            assertTrue(String.valueOf(missing.contentType()).startsWith("application/json"), "a json {error}: " + missing.contentType());
            String says = new JsonObject(missing.body()).getString("error");
            assertTrue(says != null && says.contains("no longer stored"), "says the bytes are no longer stored: " + missing.body());
            Answer durable = get(client, port, token, "/admin/messages/m-admin/steps/0/body");
            assertEquals(200, durable.status(), "the persisted body streams from Postgres through the same route");
            assertEquals(xml, durable.body(), "every byte of the persisted body");
        } finally {
            client.close().await();
            admin.stop().await();
        }
    }

    record Answer(int status, String contentType, Buffer body) {}

    static Answer get(HttpClientAgent client, int port, String token, String path) {
        return client.request(new RequestOptions().setMethod(HttpMethod.GET).setAbsoluteURI("http://127.0.0.1:" + port + path))
                .compose(req -> req.putHeader("Authorization", "Bearer " + token).send())
                .compose(res -> res.body().map(b -> new Answer(res.statusCode(), res.getHeader("content-type"), b)))
                .await();
    }

    /** The cause chain as text. */
    static List<String> causes(Throwable failure) {
        List<String> chain = new java.util.ArrayList<>();
        for (Throwable t = failure; t != null; t = t.getCause()) chain.add(String.valueOf(t));
        return chain;
    }

    /** Spins (yielding, never sleeping) until {@code condition} holds; fails after 20 s naming what never happened. */
    static void until(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("not within 20 s: " + what);
            Thread.onSpinWait();
            Thread.yield();
        }
    }
}
