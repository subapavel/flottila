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
import io.flottila.runtime.journal.TestJournals;
import io.flottila.testkit.PostgresImage;
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
import java.util.concurrent.Semaphore;
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
 * {@code bodies} rows written before V12 (D-058, D-061, F-6), read by the journal store (CORE-4c2, CORE-3 item 8): a key with
 * no index entry, no {@code body_refs} row and no work row is read from its {@code bodies} row — by the store of this node and
 * by a restarted one alike; a key whose row the retention job deleted reads as never written (D-096); exists asks for the
 * row, never its bytes. Every case runs on a database of its own (FIX-5); rows are seeded by SQL as V6..V11 wrote them.
 *
 * <p>CORE-4c2 retired the cases of the Postgres tier's writes — no body bytes are inserted into {@code bodies} any more (D-105;
 * the table is read only until CORE-11): {@code aPersistedBodyIsReadByAFreshStoreOverANewSpoolKeyAndPool},
 * {@code persistCompletesOnlyOnceItsInsertCommitted}, {@code aSameSizeSpoolRewriteDuringAPersistKeepsTheRewrite},
 * {@code aBodyOfExactlyTheBoundPersists}, {@code aBodyAboveTheBoundFailsTooLargeWritesNoRowAndStaysReadable},
 * {@code persistReleasesTheBudgetAndTheIndexEntryAndUnlinksTheSpoolFile}, {@code aFailedInsertFailsPersistAndKeepsTheBodyLocal},
 * {@code aKeyWrittenAgainWithOtherBytesAfterPersistFailsIllegalStateAndTheRowKeepsTheFirst},
 * {@code aKeyWrittenAgainWithTheSameBytesAfterPersistPersistsQuietlyAndReleasesTheCopy},
 * {@code aConflictingRowCommittedWhileThePersistWaitsIsCompared}, {@code aRowDeletedBetweenConflictAndCompareIsNoOtherBytes},
 * {@code theSameBytesAgainKeepTheRowsAge}. Their journal twins: JournalBlobStoreIT (persist, FIX-35 read-back, the bound
 * through DurableBlobStoreContract), PgIntakeIT, PgBodyRefsIT. The kept cases persist nothing: a body is a seeded row.
 */
class PgBodiesIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static final AtomicInteger cases = new AtomicInteger();
    static final Hasher HASHER = Hasher.sha256();
    static final long BOUND = 1000;   // the durable bound of every store here
    static final long FLOW_MAX = 4 * BOUND;
    static Vertx vertx;

    @TempDir Path dir;
    String database;
    Pool pool;    // the stores' pool
    Pool other;   // another session's pool on the case database: what "another session sees" reads through
    TestJournals.Opened journal;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stop() {
        vertx.close().await();
        pg.stop();
    }

    static PgConnectOptions options(String database) {
        return new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432)).setDatabase(database)
                .setUser(pg.getUsername()).setPassword(pg.getPassword());
    }

    @BeforeEach
    void createTheCaseDatabase() throws Exception {
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
        journal = TestJournals.open(other, Files.createDirectories(dir.resolve("journal")), "node-1");
    }

    @AfterEach
    void closeThePools() throws Exception {
        if (journal != null) journal.close();
        if (pool != null) pool.close().await();
        if (other != null) other.close().await();
    }

    Pool newPool() {
        return PgBuilder.pool().connectingTo(options(database)).with(new PoolOptions().setMaxSize(4)).using(vertx).build();
    }

    /** A store of node {@code node-1} over its journal, a memory tier of {@code memoryTier} bytes, its rows read through {@code p}; a second one is a restart. */
    TieredBlobStore store(long memoryTier, Pool p) {
        return new TieredBlobStore(new TieredBlobStore.Config(FLOW_MAX, memoryTier, 1L << 20, BOUND), HASHER, journal.journal(), journal.tenant(), p);
    }

    /** {@code size} bytes of a repeating, position-dependent pattern: a shifted or truncated copy does not compare equal. */
    static Buffer bytes(int size, int seed) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) b[i] = (byte) (i * 31 + seed);
        return Buffer.buffer(b);
    }

    /** {@code k}'s pre-V12 row with {@code bytes}, as V6..V11's persist committed it. */
    void seeded(BlobKey k, Buffer bytes) {
        other.preparedQuery("insert into bodies(message_id, step, part, bytes) values ($1, $2, $3, $4)")
                .execute(Tuple.of(k.messageId(), k.step(), k.part(), bytes)).await();
    }

    /** The bytes of the key's row as another session sees them, or empty. */
    Optional<Buffer> row(BlobKey k) {
        for (Row r : other.preparedQuery("select bytes from bodies where message_id = $1 and step = $2 and part = $3")
                .execute(Tuple.of(k.messageId(), k.step(), k.part())).await()) {
            return Optional.of(r.getBuffer(0));
        }
        return Optional.empty();
    }

    @Test
    void aMissingRowFailsReadAndOpen() {
        TieredBlobStore s = store(1L << 16, pool);
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
        seeded(gone, bytes(100, 8));
        assertEquals(bytes(100, 8), await(s.read(gone)), "witness: the row reads");
        other.preparedQuery("delete from bodies where message_id = $1").execute(Tuple.of(gone.messageId())).await();
        Future<Buffer> readGone = awaitOutcome(s.read(gone));
        assertTrue(readGone.failed(), "no local copy answers instead");
        assertInstanceOf(IllegalArgumentException.class, readGone.cause(), String.valueOf(readGone.cause()));
        Future<Buffer> openGone = awaitOutcome(collect(s.open(gone)));
        assertTrue(openGone.failed());
        assertInstanceOf(IllegalArgumentException.class, openGone.cause(), String.valueOf(openGone.cause()));
    }

    @Test
    void aBodyKeyWithNoIndexEntryAndNoRowIs404NoLongerStoredThroughTheAdminRoute() {
        TieredBlobStore s = store(1L << 16, pool);
        BlobKey kept = BlobKey.body("m-admin", 0);
        BlobKey gone = BlobKey.body("m-admin", 1);   // never written: no index entry, no row (retention took it, P2-07)
        Buffer xml = bytes(500, 3);
        seeded(kept, xml);   // the witness: the same route over the same store answers from the pre-V12 row
        Body body = Body.of(s, kept, HASHER.hashOf(xml), xml.length());
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
            assertEquals(200, durable.status(), "the pre-V12 body streams from its row through the same route");
            assertEquals(xml, durable.body(), "every byte of the row");
        } finally {
            client.close().await();
            admin.stop().await();
        }
    }

    /**
     * D-096: a key whose pre-V12 row the retention job deleted reads as never written, for the store that read it and for a
     * restarted one alike: exists is false (an answer), read fails with the miss
     * signal naming the key, open throws it or its stream fails with it before any byte, persist fails with it. Witness: before
     * the delete the key exists and reads from Postgres; a sibling key persisted beside it still does afterwards.
     */
    @Test
    void aKeyWhoseRowWasDeletedIsFalseToExistsAndBlobNotFoundToReadOpenPersist() {
        TieredBlobStore s = store(1L << 16, pool);
        BlobKey k = BlobKey.body("m-retained", 1);
        BlobKey sibling = BlobKey.body("m-retained", 2);
        seeded(k, bytes(400, 5));
        seeded(sibling, bytes(300, 6));
        assertEquals(Optional.empty(), s.location(k), "fixture: no local copy");
        assertEquals(Boolean.TRUE, await(s.exists(k)), "witness: the key exists, from its row");
        assertEquals(bytes(400, 5), await(s.read(k)), "witness: and reads from Postgres");

        // the retention job's delete (P2-07)
        assertEquals(1, other.preparedQuery("delete from bodies where message_id = $1 and step = $2 and part = $3")
                .execute(Tuple.of(k.messageId(), k.step(), k.part())).await().rowCount(), "fixture: the row is deleted");

        TieredBlobStore fresh = store(1L << 16, pool);   // a restart: an empty index over the same journal
        for (TieredBlobStore store : List.of(s, fresh)) {
            String which = store == s ? "the store that read it" : "a restarted store";
            Future<Boolean> exists = awaitOutcome(store.exists(k));
            assertTrue(exists.succeeded(), which + ": a key with no row is an answer, not a failure: " + exists.cause());
            assertEquals(Boolean.FALSE, exists.result(), which);

            Future<Buffer> read = awaitOutcome(store.read(k));
            assertTrue(read.failed(), which + ": the read fails");
            assertEquals(k, assertInstanceOf(io.flottila.api.BlobNotFoundException.class, read.cause(), which + ": " + read.cause()).key(), which);

            assertEquals(k, openFailure(store, k).key(), which + ": open");

            Future<Void> persist = awaitOutcome(store.persist(k));
            assertTrue(persist.failed(), which + ": nothing to make durable");
            assertEquals(k, assertInstanceOf(io.flottila.api.BlobNotFoundException.class, persist.cause(), which + ": " + persist.cause()).key(), which);

            assertEquals(Boolean.TRUE, await(store.exists(sibling)), which + ": witness: the sibling's row stays");
            assertEquals(bytes(300, 6), await(store.read(sibling)), which);
        }
        assertEquals(Optional.empty(), row(k), "nothing wrote the key back");
    }

    /**
     * D-096: exists of a key with no local copy, no ref and a pre-V12 row asks Postgres whether the row is there, never for its bytes:
     * every statement the store sends from the exists on is recorded at the pool (a proxy, the store boundary) and none
     * selects {@code bytes}. Witness: a read of the same key through the same pool does.
     */
    @Test
    void existsAsksForTheRowNotItsBytes() {
        List<String> statements = new CopyOnWriteArrayList<>();
        Pool recording = (Pool) java.lang.reflect.Proxy.newProxyInstance(Pool.class.getClassLoader(), new Class<?>[] {Pool.class}, (proxy, method, args) -> {
            if ((method.getName().equals("preparedQuery") || method.getName().equals("query")) && args != null && args.length > 0 && args[0] instanceof String sql) {
                statements.add(sql);
            }
            try {
                return method.invoke(pool, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        });
        TieredBlobStore s = store(1L << 16, recording);
        BlobKey k = BlobKey.body("m-exists-only", 1);
        seeded(k, bytes(900, 9));
        assertEquals(Optional.empty(), s.location(k), "fixture: no local copy");
        statements.clear();

        assertEquals(Boolean.TRUE, await(s.exists(k)), "the row is there");
        assertEquals(Boolean.FALSE, await(s.exists(BlobKey.body("m-exists-only", 2))), "no row for the next step");
        assertFalse(statements.isEmpty(), "fixture: exists asked Postgres through the pool");
        assertEquals(List.of(), statements.stream().filter(q -> q.toLowerCase(java.util.Locale.ROOT).contains("select bytes")).toList(),
                "exists fetched no bytes: " + statements);

        await(s.read(k));
        assertTrue(statements.stream().anyMatch(q -> q.toLowerCase(java.util.Locale.ROOT).contains("select bytes")), "witness: a read selects the bytes: " + statements);
    }
    /** The miss signal of opening {@code k}: thrown by open, or its stream's failure, with no chunk and no end before it. */
    static io.flottila.api.BlobNotFoundException openFailure(TieredBlobStore s, BlobKey k) {
        ReadStream<Buffer> stream;
        try {
            stream = s.open(k);
        } catch (io.flottila.api.BlobNotFoundException thrown) {
            return thrown;
        }
        io.vertx.core.Promise<Throwable> failed = io.vertx.core.Promise.promise();
        List<String> seen = new CopyOnWriteArrayList<>();
        stream.exceptionHandler(t -> { seen.add("failure"); failed.tryComplete(t); });
        stream.endHandler(v -> { seen.add("end"); failed.tryComplete(null); });
        stream.handler(chunk -> seen.add("chunk"));
        Throwable t = await(failed.future());
        assertEquals(List.of("failure"), seen, "the stream of " + k + " fails before any byte and never ends");
        return assertInstanceOf(io.flottila.api.BlobNotFoundException.class, t, String.valueOf(t));
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
