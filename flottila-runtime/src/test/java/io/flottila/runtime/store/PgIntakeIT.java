package io.flottila.runtime.store;

import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.Envelope;
import io.flottila.api.Hasher;
import io.flottila.api.Headers;
import io.flottila.core.batch.BatchingWriter;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.core.record.Recorder;
import io.flottila.core.record.RecordingPolicy;
import io.flottila.core.record.Snapshot;
import io.flottila.runtime.blob.PgBodies;
import io.flottila.runtime.blob.TieredBlobStore;
import io.flottila.runtime.db.Migrations;
import io.flottila.runtime.queue.PgWorkQueue;
import io.flottila.runtime.time.VertxTimer;
import io.flottila.testkit.InMemoryBlobStore;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P2-08a (D-082): {@code PgIntake} commits the ingress body, the ingress record and the work item in one Postgres transaction,
 * body → record → item, the item last; {@code abandoned} is asked just before the commit — true rolls everything back. A session
 * that dies between the record and the enqueue, or an enqueue that fails, leaves neither row nor body behind, and the body
 * stays local and readable (its release comes only after the commit); the sender's retry then saves. A failed save is no
 * snapshot write failure ({@link PgMessageStore#failed()}). A body already durable is not inserted again; a body of another
 * store is persisted there first.
 *
 * <p>Witnesses that survive a rollback: sequences ({@code nextval} is not transactional) bumped by triggers on {@code work};
 * the transaction of each insert is logged by triggers into {@code txlog} ({@code txid_current()}). A trigger kills its own
 * backend with {@code pg_terminate_backend(pg_backend_pid())}: the session dies inside the item's insert, before any commit.
 * Every case runs on a database of its own (FIX-5).
 *
 * <p>The shape assumed: {@code io.flottila.runtime.store.PgIntake(Pool, TieredBlobStore, PgMessageStore, PgWorkQueue) implements Intake}.
 */
@Timeout(60)
class PgIntakeIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final AtomicInteger cases = new AtomicInteger();
    static final long BOUND = 1000;
    static final long FLOW_MAX = 4 * BOUND;
    static final String FLOW = "orders";
    static Vertx vertx;

    @TempDir Path spool;
    Pool pool;    // the intake's pool
    Pool other;   // another session's view of the case database
    TieredBlobStore blobs;
    PgMessageStore messages;
    PgIntake intake;

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

    // --- shared with PgIntakeContractIT

    /** Creates database {@code name} on {@code container} and migrates it; returns the name. */
    static String caseDatabase(PostgreSQLContainer container, String name) {
        try (Connection admin = DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
             Statement create = admin.createStatement()) {
            create.execute("CREATE DATABASE \"" + name + "\"");
        } catch (SQLException e) {
            throw new IllegalStateException("fixture: database " + name + " not created", e);
        }
        // getJdbcUrl() is jdbc:postgresql://host:port/<db> followed by the container's query string (?loggerLevel=OFF)
        String base = container.getJdbcUrl();
        int query = base.indexOf('?');
        String path = query < 0 ? base : base.substring(0, query);
        String suffix = "/" + container.getDatabaseName();
        assertTrue(path.endsWith(suffix), "the container URL ends with its database: " + base);
        Migrations.migrate(path.substring(0, path.length() - suffix.length()) + "/" + name + (query < 0 ? "" : base.substring(query)),
                container.getUsername(), container.getPassword());
        return name;
    }

    /**
     * The intake's pool: ONE connection, so a statement of the save that goes to the pool instead of the transaction waits for
     * the connection its own transaction holds; the wait is bounded ({@link #POOL_WAIT_SECONDS}), so such a save fails with the
     * pool's timeout instead of hanging (every statement on {@code tx}, never the pool).
     */
    static Pool pool(Vertx vertx, PostgreSQLContainer container, String database) {
        return pool(vertx, container, database, 1);
    }

    /** A connection wait above this fails the statement: well inside {@code settled}'s 20 s. */
    static final int POOL_WAIT_SECONDS = 5;

    static Pool pool(Vertx vertx, PostgreSQLContainer container, String database, int maxSize) {
        return PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(container.getHost()).setPort(container.getMappedPort(5432)).setDatabase(database)
                .setUser(container.getUsername()).setPassword(container.getPassword()))
                .with(new PoolOptions().setMaxSize(maxSize).setConnectionTimeout(POOL_WAIT_SECONDS).setConnectionTimeoutUnit(TimeUnit.SECONDS)).using(vertx).build();
    }

    /** The node's body store over {@code spool}: a 1 MiB memory tier and budget, the Postgres tier over {@code pool} bounded by {@link #BOUND}. */
    static TieredBlobStore blobs(Path spool, Pool pool) {
        return new TieredBlobStore(new TieredBlobStore.Config(FLOW_MAX, 1L << 20, 1L << 20, spool, spoolKey(), null, false), Hasher.sha256(),
                new PgBodies(pool, BOUND));
    }

    static SecretKey spoolKey() {
        try {
            KeyGenerator g = KeyGenerator.getInstance("AES");
            g.init(256);
            return g.generateKey();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- fixture

    @BeforeEach
    void openTheStores() {
        String database = caseDatabase(pg, "intake_" + cases.incrementAndGet());
        pool = pool(vertx, pg, database);
        other = pool(vertx, pg, database, 2);
        blobs = blobs(spool, pool);
        messages = new PgMessageStore(pool, new VertxTimer(vertx), BatchingWriter.Options.defaults());
        intake = new PgIntake(pool, blobs, messages, new PgWorkQueue(pool));
        // only the retention job deletes from bodies (P2-07): a save path that deletes fails its case
        sql("create function bodies_never_deleted() returns trigger language plpgsql as "
                + "$$ begin raise exception 'a DELETE on bodies: only the retention job deletes (P2-07)'; end $$");
        sql("create trigger bodies_never_deleted before delete on bodies for each statement execute function bodies_never_deleted()");
    }

    @AfterEach
    void closeTheStores() {
        if (messages != null) messages.close();
        if (pool != null) pool.close().await();
        if (other != null) other.close().await();
    }

    void sql(String statement) {
        other.query(statement).execute().await();
    }

    long count(String table, String id) {
        String column = table.equals("bodies") ? "message_id" : "id";
        return other.preparedQuery("select count(*) from " + table + " where " + column + " = $1").execute(Tuple.of(id)).await()
                .iterator().next().getLong(0);
    }

    long count(String table) {
        return other.query("select count(*) from " + table).execute().await().iterator().next().getLong(0);
    }

    /** Whether {@code nextval} was ever called on {@code sequence} — in any transaction, committed or not. */
    boolean bumped(String sequence) {
        return other.query("select is_called from " + sequence).execute().await().iterator().next().getBoolean(0);
    }

    Optional<Buffer> row(BlobKey k) {
        for (Row r : other.preparedQuery("select bytes from bodies where message_id = $1 and step = $2 and part = $3")
                .execute(Tuple.of(k.messageId(), k.step(), k.part())).await()) {
            return Optional.of(r.getBuffer(0));
        }
        return Optional.empty();
    }

    /** The work rows as {@code id/partition_key}, in seq order. */
    List<String> work() {
        List<String> out = new ArrayList<>();
        for (Row r : other.query("select id, partition_key from work order by seq").execute().await()) out.add(r.getString(0) + "/" + r.getString(1));
        return out;
    }

    /** Every insert into bodies, messages and work from now on is logged with its table and its transaction, in insert order. */
    void logTransactions() {
        sql("create table txlog(n bigserial primary key, tbl text not null, txid bigint not null)");
        sql("create function log_tx() returns trigger language plpgsql as "
                + "$$ begin insert into txlog(tbl, txid) values (tg_table_name, txid_current()); return null; end $$");
        for (String t : List.of("bodies", "messages", "work")) sql("create trigger log_tx after insert on " + t + " for each row execute function log_tx()");
    }

    record Logged(String table, long txid) {}

    List<Logged> logged() {
        List<Logged> out = new ArrayList<>();
        for (Row r : other.query("select tbl, txid from txlog order by n").execute().await()) out.add(new Logged(r.getString(0), r.getLong(1)));
        return out;
    }

    static Buffer bytes(int size, int seed) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) b[i] = (byte) (i * 31 + seed);
        return Buffer.buffer(b);
    }

    Envelope arrive(String id, Buffer bytes) {
        Future<Body> written = settled(blobs.write(BlobKey.body(id, 0), bytes, FLOW_MAX));
        assertTrue(written.succeeded(), "fixture: written " + written.cause());
        assertEquals(Optional.of(TieredBlobStore.Location.MEMORY), blobs.location(BlobKey.body(id, 0)), "fixture: the body is local, in memory");
        return Envelope.ingress(id, FLOW, Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), written.result());
    }

    static Snapshot ingress(Envelope e) {
        return new Recorder(new InMemoryMessageStore(), Clock.fixed(Instant.parse("2026-10-29T10:00:00.123456Z"), ZoneOffset.UTC), Meter.system(), Map.of())
                .startSnapshot(e, Recorder.INGRESS, RecordingPolicy.DEFAULT);
    }

    Future<Void> save(Envelope e, java.util.function.BooleanSupplier abandoned) {
        return settled(intake.save(e, ingress(e), FLOW, "K-1", abandoned));
    }

    static <T> Future<T> settled(Future<T> f) {
        try {
            f.otherwiseEmpty().await(20, TimeUnit.SECONDS);
        } catch (TimeoutException pending) {
            fail("still pending after 20 s: " + f);
        }
        return f;
    }

    static List<String> causes(Throwable failure) {
        List<String> chain = new ArrayList<>();
        for (Throwable t = failure; t != null && chain.size() < 20; t = t.getCause()) chain.add(String.valueOf(t));
        return chain;
    }

    /** Nothing of message {@code id} in Postgres: no ingress row, no payload row, no body row, no item. */
    void nothingCommitted(String id) {
        assertEquals(0, count("messages", id), "no ingress row");
        assertEquals(0, count("payloads"), "no payload row");
        assertEquals(0, count("bodies", id), "no body row");
        assertEquals(0, count("work", id), "no item");
    }

    /** The body of {@code in} is still local and reads as it arrived: nothing released it. */
    void stillLocal(Envelope in, Buffer bytes) {
        BlobKey key = in.body().key();
        assertEquals(Optional.of(TieredBlobStore.Location.MEMORY), blobs.location(key), "the body is still local: its commit never came, so no release");
        assertEquals(bytes.length(), blobs.memoryInUse(), "with its memory budget");
        assertEquals(bytes, settled(blobs.read(key)).result(), "and readable");
    }

    // --- cases

    @Test
    void aSessionKilledBetweenRecordAndEnqueueLeavesNeither() {
        sql("create sequence work_inserts");
        sql("create sequence saw_body");
        sql("create sequence saw_record");
        sql("create function kill_at_enqueue() returns trigger language plpgsql as $$ begin "
                + "perform nextval('work_inserts'); "
                + "if exists (select 1 from bodies where message_id = new.id) then perform nextval('saw_body'); end if; "
                + "if exists (select 1 from messages where id = new.id) then perform nextval('saw_record'); end if; "
                + "perform pg_terminate_backend(pg_backend_pid()); "
                + "return new; end $$");
        sql("create trigger kill_at_enqueue before insert on work for each row execute function kill_at_enqueue()");
        Buffer bytes = bytes(300, 1);
        Envelope in = arrive("m-killed", bytes);

        Future<Void> saved = save(in, () -> false);
        assertTrue(saved.failed(), "the session died before its commit: the save fails");
        assertTrue(bumped("work_inserts"), "fixture: the session reached the item's insert, and died there");
        assertTrue(bumped("saw_body"), "the body was inserted before the item, in the item's transaction");
        assertTrue(bumped("saw_record"), "so was the ingress record");
        nothingCommitted("m-killed");
        stillLocal(in, bytes);
        assertEquals(0, messages.failed(), "a failed intake is no snapshot write failure: no acknowledged row is missing");
    }

    @Test
    void aFailingEnqueueRollsBackRecordAndBody() {
        sql("create function refuse_work() returns trigger language plpgsql as $$ begin raise exception 'work refused by the test'; end $$");
        sql("create trigger refuse_work before insert on work for each row execute function refuse_work()");
        Buffer bytes = bytes(300, 2);
        Envelope in = arrive("m-refused", bytes);

        Future<Void> saved = save(in, () -> false);
        assertTrue(saved.failed(), "the enqueue failed: no save");
        assertTrue(causes(saved.cause()).stream().anyMatch(c -> c.contains("work refused by the test")), "with the enqueue's failure: " + causes(saved.cause()));
        nothingCommitted("m-refused");
        stillLocal(in, bytes);
        assertEquals(0, messages.failed(), "a failed intake is no snapshot write failure");

        sql("drop trigger refuse_work on work");
        Future<Void> retry = save(in, () -> false);
        assertTrue(retry.succeeded(), "the sender's retry saves once the enqueue succeeds: " + retry.cause());
        assertEquals(Optional.of(bytes), row(in.body().key()), "the body is durable");
        assertEquals(1, count("messages", "m-refused"), "the ingress row is there");
        assertEquals(List.of("m-refused/K-1"), work(), "and the item");
        assertEquals(Optional.empty(), blobs.location(in.body().key()), "the local copy went once the commit proved the body durable");
        assertEquals(0, blobs.memoryInUse(), "with its budget");
    }

    @Test
    void bodyRecordAndItemShareOneTransaction() {
        logTransactions();
        Envelope first = arrive("m-one", bytes(300, 3));
        Envelope second = arrive("m-two", bytes(400, 4));

        Future<Optional<TieredBlobStore.Location>> whereAtCompletion = settled(intake.save(first, ingress(first), FLOW, "K-1", () -> false)
                .map(v -> blobs.location(first.body().key())));
        assertTrue(whereAtCompletion.succeeded(), "saved: " + whereAtCompletion.cause());
        assertEquals(Optional.empty(), whereAtCompletion.result(), "the local copy is released before the save's future completes");
        assertTrue(save(second, () -> false).succeeded());

        List<Logged> log = logged();
        assertEquals(List.of("bodies", "messages", "work", "bodies", "messages", "work"), log.stream().map(Logged::table).toList(),
                "per save: body → record → item, the item last (D-082)");
        assertEquals(1, log.subList(0, 3).stream().map(Logged::txid).distinct().count(), "the first save's three inserts share one transaction: " + log);
        assertEquals(1, log.subList(3, 6).stream().map(Logged::txid).distinct().count(), "so do the second's: " + log);
        assertNotEquals(log.get(0).txid(), log.get(3).txid(), "one transaction per save: " + log);
        assertEquals(List.of("m-one/K-1", "m-two/K-1"), work());
    }

    @Test
    void aCommitThatFailsLeavesTheBodyLocal() {
        // a deferred constraint trigger runs at COMMIT: every statement of the save succeeds, the commit itself fails
        sql("create function refuse_at_commit() returns trigger language plpgsql as $$ begin raise exception 'commit refused by the test'; end $$");
        sql("create constraint trigger refuse_at_commit after insert on work deferrable initially deferred for each row execute function refuse_at_commit()");
        Buffer bytes = bytes(300, 10);
        Envelope in = arrive("m-commit-refused", bytes);
        AtomicInteger asked = new AtomicInteger();

        Future<Void> saved = save(in, () -> {
            asked.incrementAndGet();
            return false;
        });
        assertEquals(1, asked.get(), "fixture: the save reached its commit (abandoned is asked just before it)");
        assertTrue(saved.failed(), "the commit failed: no save");
        assertTrue(causes(saved.cause()).stream().anyMatch(c -> c.contains("commit refused by the test")), "with the commit's failure: " + causes(saved.cause()));
        nothingCommitted("m-commit-refused");
        stillLocal(in, bytes);   // the release comes after the commit, never before it
        assertEquals(0, messages.failed(), "a failed intake is no snapshot write failure");
    }

    @Test
    void anAbandonedSaveIsRolledBack() {
        sql("create sequence work_inserts");
        sql("create function count_work() returns trigger language plpgsql as $$ begin perform nextval('work_inserts'); return new; end $$");
        sql("create trigger count_work before insert on work for each row execute function count_work()");
        Buffer bytes = bytes(300, 5);
        Envelope in = arrive("m-abandoned", bytes);
        AtomicInteger asked = new AtomicInteger();

        Future<Void> saved = save(in, () -> {
            asked.incrementAndGet();
            return true;
        });
        assertTrue(saved.failed(), "an abandoned save fails");
        assertEquals(1, asked.get(), "abandoned is asked once");
        assertTrue(bumped("work_inserts"), "asked just before the commit: the item was inserted first, then rolled back");
        nothingCommitted("m-abandoned");
        stillLocal(in, bytes);
    }

    @Test
    void aBodyAlreadyDurableIsNotInsertedAgain() {
        Buffer bytes = bytes(300, 6);
        Envelope in = arrive("m-durable", bytes);
        assertTrue(settled(blobs.persist(in.body().key())).succeeded(), "fixture: the body is durable already");
        assertEquals(Optional.empty(), blobs.location(in.body().key()), "fixture: and no longer local");
        logTransactions();

        Future<Void> saved = save(in, () -> false);
        assertTrue(saved.succeeded(), "saved: " + saved.cause());
        assertEquals(List.of("messages", "work"), logged().stream().map(Logged::table).toList(), "a body already durable inserts nothing");
        assertEquals(1, count("bodies", "m-durable"), "one body row");
        assertEquals(Optional.of(bytes), row(in.body().key()));
        assertEquals(List.of("m-durable/K-1"), work());
    }

    @Test
    void aBodyFromAnotherStoreIsPersistedThereFirst() {
        InMemoryBlobStore elsewhere = new InMemoryBlobStore(Hasher.sha256(), FLOW_MAX);
        BlobKey key = BlobKey.body("m-elsewhere", 0);
        Body body = settled(elsewhere.write(key, bytes(300, 7), FLOW_MAX)).result();
        Envelope in = Envelope.ingress("m-elsewhere", FLOW, Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), body);

        Future<Void> saved = save(in, () -> false);
        assertTrue(saved.succeeded(), "saved: " + saved.cause());
        assertTrue(elsewhere.persisted(key), "the body is persisted in its own store");
        assertEquals(0, count("bodies", "m-elsewhere"), "not in the Postgres tier: it is another store's body");
        assertEquals(1, count("messages", "m-elsewhere"));
        assertEquals(List.of("m-elsewhere/K-1"), work());

        // that store refuses: nothing of the message is saved
        BlobKey refusedKey = BlobKey.body("m-refused-elsewhere", 0);
        settled(elsewhere.write(refusedKey, bytes(300, 8), FLOW_MAX));
        assertTrue(settled(elsewhere.persist(refusedKey)).succeeded(), "fixture: durable with the first bytes");
        Body rewritten = settled(elsewhere.write(refusedKey, bytes(300, 9), FLOW_MAX)).result();   // other bytes under a durable key
        Envelope refused = Envelope.ingress("m-refused-elsewhere", FLOW, Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), rewritten);
        Future<Void> failed = save(refused, () -> false);
        assertTrue(failed.failed(), "the other store's persist failed: no save");
        assertEquals(0, count("messages", "m-refused-elsewhere"), "no ingress row");
        assertFalse(work().contains("m-refused-elsewhere/K-1"), "no item: " + work());
    }
}
