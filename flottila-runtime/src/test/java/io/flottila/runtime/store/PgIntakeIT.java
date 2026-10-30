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
import java.util.concurrent.atomic.AtomicBoolean;
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
 * <p>P2-02D (D-084): a save with a dedupe key first inserts {@code processed_ids(scope, key_hash, message_id, created_at)} — the
 * scope the flow it is given, the key's SHA-256 over its UTF-8 bytes, the arrival's id, the ingress snapshot's start — {@code on
 * conflict do nothing}; a key already taken rolls the save back, never asks {@code abandoned}, evicts the duplicate's local body
 * and answers the first message's id; a key row that is gone by the time it is read is a failed save. A save that fails or is
 * abandoned leaves the key free. A racing duplicate waits on the open first save (READ COMMITTED): its commit makes the duplicate
 * answer the first id, its rollback lets the duplicate save itself. The race is staged without sleeps: a second session holds
 * {@code lock table work in share mode}, so the first save stays open at its item's insert; {@code pg_stat_activity} shows who
 * waits; the second save runs on a second node (its own pool, body store and stores).
 *
 * <p>The shape assumed: {@code io.flottila.runtime.store.PgIntake(Pool, TieredBlobStore, PgMessageStore, PgWorkQueue) implements Intake};
 * P2-02D: {@code Future<String> save(Envelope, Snapshot, String flow, String partitionKey, String dedupeKey, BooleanSupplier abandoned)}.
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
    String database;
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
        database = caseDatabase(pg, "intake_" + cases.incrementAndGet());
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
        if (held != null) held.close();
        if (node2 != null) node2.close();
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

    /** A save without a dedupe key. */
    Future<String> save(Envelope e, java.util.function.BooleanSupplier abandoned) {
        return settled(intake.save(e, ingress(e), FLOW, "K-1", null, abandoned));
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

        Future<String> saved = save(in, () -> false);
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

        Future<String> saved = save(in, () -> false);
        assertTrue(saved.failed(), "the enqueue failed: no save");
        assertTrue(causes(saved.cause()).stream().anyMatch(c -> c.contains("work refused by the test")), "with the enqueue's failure: " + causes(saved.cause()));
        nothingCommitted("m-refused");
        stillLocal(in, bytes);
        assertEquals(0, messages.failed(), "a failed intake is no snapshot write failure");

        sql("drop trigger refuse_work on work");
        Future<String> retry = save(in, () -> false);
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

        Future<Optional<TieredBlobStore.Location>> whereAtCompletion = settled(intake.save(first, ingress(first), FLOW, "K-1", null, () -> false)
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

        Future<String> saved = save(in, () -> {
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

        Future<String> saved = save(in, () -> {
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
    void theIngressRowStoresNullCpu() {
        Envelope in = arrive("m-cpu", bytes(300, 9));
        Meter reading = () -> 5_000L;   // a meter with a reading: the ingress row still measured nothing (F-21)
        Snapshot ingress = new Recorder(new InMemoryMessageStore(), Clock.fixed(Instant.parse("2026-10-29T10:00:00.123456Z"), ZoneOffset.UTC), reading, Map.of())
                .startSnapshot(in, Recorder.INGRESS, RecordingPolicy.DEFAULT);

        Future<String> saved = settled(intake.save(in, ingress, FLOW, "K-1", null, () -> false));
        assertTrue(saved.succeeded(), "saved: " + saved.cause());
        List<String> rows = new ArrayList<>();
        for (Row r : other.preparedQuery("select step, wall_ns, cpu_ns from messages where id = $1").execute(Tuple.of("m-cpu")).await()) {
            rows.add("step " + r.getInteger("step") + " wall_ns " + r.getLong("wall_ns") + " cpu_ns " + r.getLong("cpu_ns"));
        }
        assertEquals(List.of("step 0 wall_ns 0 cpu_ns null"), rows, "the at-least-once ingress row: cpu_ns NULL, no reading and never 0 (F-21)");
        List<Snapshot> timeline = settled(messages.timeline("m-cpu")).result();
        assertEquals(Snapshot.CPU_UNKNOWN, timeline.get(0).cpuNanos(), "and it reads back as unknown");
    }

    @Test
    void aBodyAlreadyDurableIsNotInsertedAgain() {
        Buffer bytes = bytes(300, 6);
        Envelope in = arrive("m-durable", bytes);
        assertTrue(settled(blobs.persist(in.body().key())).succeeded(), "fixture: the body is durable already");
        assertEquals(Optional.empty(), blobs.location(in.body().key()), "fixture: and no longer local");
        logTransactions();

        Future<String> saved = save(in, () -> false);
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

        Future<String> saved = save(in, () -> false);
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
        Future<String> failed = save(refused, () -> false);
        assertTrue(failed.failed(), "the other store's persist failed: no save");
        assertEquals(0, count("messages", "m-refused-elsewhere"), "no ingress row");
        assertFalse(work().contains("m-refused-elsewhere/K-1"), "no item: " + work());
    }

    // --- the dedupe key (P2-02D, D-084)

    /** A qualified flow id: the key's scope. */
    static final String SCOPE = "shop/orders";
    /** An sftp-shaped file key, with characters outside Latin-1 so the digest's charset matters. */
    static final String KEY = "drop/inbox/größe-€-1.xml|1730196000000|300";
    /** When every ingress snapshot of this class started (the fixed clock of {@link #ingress}). */
    static final Instant STARTED = Instant.parse("2026-10-29T10:00:00.123456Z");

    /** A second session's open transaction holding a lock; {@link #release} rolls it back. */
    Held held;
    /** A second node over the case database: its own pool of one connection, body store and stores. */
    Node2 node2;

    record Held(io.vertx.sqlclient.SqlConnection connection, io.vertx.sqlclient.Transaction tx, AtomicBoolean released) implements AutoCloseable {
        /** Rolls back once: tx.completion() is a fresh future completed on the connection's context, so it cannot tell. */
        void release() {
            if (released.compareAndSet(false, true)) tx.rollback().await();
        }

        @Override public void close() {
            release();
            connection.close().await();
        }
    }

    record Node2(Pool pool, TieredBlobStore blobs, PgMessageStore messages, PgIntake intake) implements AutoCloseable {
        @Override public void close() {
            messages.close();
            pool.close().await();
        }
    }

    /** Takes {@code lock table work in share mode} in a transaction of a second session: every insert into work waits for it. */
    Held holdWork() {
        io.vertx.sqlclient.SqlConnection c = other.getConnection().await();
        io.vertx.sqlclient.Transaction tx = c.begin().await();
        c.query("lock table work in share mode").execute().await();
        held = new Held(c, tx, new AtomicBoolean());
        return held;
    }

    Node2 node2() throws java.io.IOException {
        Pool p = pool(vertx, pg, database);
        Path dir = java.nio.file.Files.createDirectories(spool.resolve("node-2"));
        TieredBlobStore b = blobs(dir, p);
        PgMessageStore m = new PgMessageStore(p, new VertxTimer(vertx), BatchingWriter.Options.defaults());
        node2 = new Node2(p, b, m, new PgIntake(p, b, m, new PgWorkQueue(p)));
        return node2;
    }

    /** Like {@link #arrive}, the body written in {@code store}. */
    static Envelope arriveIn(TieredBlobStore store, String id, Buffer bytes) {
        Future<Body> written = settled(store.write(BlobKey.body(id, 0), bytes, FLOW_MAX));
        assertTrue(written.succeeded(), "fixture: written " + written.cause());
        assertEquals(Optional.of(TieredBlobStore.Location.MEMORY), store.location(BlobKey.body(id, 0)), "fixture: the body is local, in memory");
        return Envelope.ingress(id, FLOW, Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), written.result());
    }

    Future<String> saveKeyed(PgIntake through, Envelope e, String scope, String key, java.util.function.BooleanSupplier abandoned) {
        return through.save(e, ingress(e), scope, "K-1", key, abandoned);
    }

    Future<String> saveKeyed(Envelope e, String key, java.util.function.BooleanSupplier abandoned) {
        return settled(saveKeyed(intake, e, SCOPE, key, abandoned));
    }

    /** {@code abandoned} answering {@code answer}, counting its asks. */
    static java.util.function.BooleanSupplier counting(AtomicInteger asks, boolean answer) {
        return () -> {
            asks.incrementAndGet();
            return answer;
        };
    }

    record Processed(String scope, String keyHash, String messageId, Instant createdAt) {}

    /** The processed_ids rows, the hash in hex, by scope then message id. */
    List<Processed> processed() {
        List<Processed> out = new ArrayList<>();
        for (Row r : other.query("select scope, encode(key_hash, 'hex'), message_id, created_at from processed_ids order by scope, message_id").execute().await()) {
            out.add(new Processed(r.getString(0), r.getString(1), r.getString(2), r.getOffsetDateTime(3).toInstant()));
        }
        return out;
    }

    /** The SHA-256 of {@code key}'s UTF-8 bytes, in hex. */
    static String sha256(String key) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Backends of the case database waiting on a lock. */
    long lockWaiters() {
        return other.preparedQuery("select count(*) from pg_stat_activity where datname = $1 and wait_event_type = 'Lock'")
                .execute(Tuple.of(database)).await().iterator().next().getLong(0);
    }

    /** Spins (yielding, never sleeping) until {@code condition} holds; fails after 20 s naming what never happened. */
    static void until(java.util.function.BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("not within 20 s: " + what);
            Thread.onSpinWait();
            Thread.yield();
        }
    }

    /** Nothing of message {@code id} in Postgres: no ingress row, no body row, no item (other messages' rows may be there). */
    void nothingOf(String id) {
        assertEquals(0, count("messages", id), id + ": no ingress row");
        assertEquals(0, count("bodies", id), id + ": no body row");
        assertEquals(0, count("work", id), id + ": no item");
    }

    /** Message {@code id} saved whole: its ingress row, its body row and its item. */
    void savedWhole(String id) {
        assertEquals(1, count("messages", id), id + ": the ingress row");
        assertEquals(1, count("bodies", id), id + ": the body row");
        assertEquals(1, count("work", id), id + ": the item");
    }

    @Test
    void aKeySavedTwiceIsOneMessageAndOneItem() {
        Envelope first = arrive("m-1", bytes(300, 20));
        Envelope again = arrive("m-2", bytes(300, 20));   // the same file delivered again: a new arrival, a new id

        Future<String> saved = saveKeyed(first, KEY, () -> false);
        assertTrue(saved.succeeded(), "saved: " + saved.cause());
        assertEquals("m-1", saved.result(), "a key no one had: the save answers the arrival's own id");
        Future<String> duplicate = saveKeyed(again, KEY, () -> false);
        assertTrue(duplicate.succeeded(), "a duplicate is a successful save: its sender may ACK — " + duplicate.cause());
        assertEquals("m-1", duplicate.result(), "a duplicate answers the first message's id");

        savedWhole("m-1");
        nothingOf("m-2");
        assertEquals(1, count("messages"), "one ingress row");
        assertEquals(1, count("payloads"), "one payload row");
        assertEquals(1, count("bodies"), "one body row");
        assertEquals(List.of("m-1/K-1"), work(), "one item");
        assertEquals(List.of(new Processed(SCOPE, sha256(KEY), "m-1", STARTED)), processed(),
                "one processed_ids row: the flow's scope, the SHA-256 of the key's UTF-8 bytes, the first id, the ingress snapshot's start");
    }

    @Test
    void theSameKeyInAnotherFlowIsAnotherMessage() {
        Envelope orders = arrive("m-1", bytes(300, 21));
        Envelope invoices = arrive("m-2", bytes(300, 21));   // both envelopes name flow "orders": the scope is the flow the intake is given

        assertEquals("m-1", settled(saveKeyed(intake, orders, "shop/orders", KEY, () -> false)).result());
        Future<String> inInvoices = settled(saveKeyed(intake, invoices, "shop/invoices", KEY, () -> false));
        assertTrue(inInvoices.succeeded(), "saved: " + inInvoices.cause());
        assertEquals("m-2", inInvoices.result(), "the same key in another flow is no duplicate");

        savedWhole("m-1");
        savedWhole("m-2");
        assertEquals(List.of(new Processed("shop/invoices", sha256(KEY), "m-2", STARTED), new Processed("shop/orders", sha256(KEY), "m-1", STARTED)),
                processed(), "one row per scope");
    }

    @Test
    void aDuplicateNeverAsksAbandoned() {
        assertEquals("m-1", saveKeyed(arrive("m-1", bytes(300, 22)), KEY, () -> false).result());
        Envelope again = arrive("m-2", bytes(300, 22));
        AtomicInteger asks = new AtomicInteger();

        Future<String> duplicate = saveKeyed(again, KEY, counting(asks, true));   // asked, it would abandon the save
        assertEquals(0, asks.get(), "a duplicate commits nothing: abandoned is never asked");
        assertTrue(duplicate.succeeded(), "so its answer is the first id, never an abandoned save: " + duplicate.cause());
        assertEquals("m-1", duplicate.result());
        nothingOf("m-2");
    }

    @Test
    void anAbandonedFirstSaveLeavesTheKeyFree() {
        Envelope first = arrive("m-1", bytes(300, 23));
        AtomicInteger asks = new AtomicInteger();
        Future<String> abandoned = saveKeyed(first, KEY, counting(asks, true));
        assertTrue(abandoned.failed(), "an abandoned save fails");
        assertEquals(1, asks.get(), "fixture: abandoned was asked, once");
        assertEquals(List.of(), processed(), "its key row went with the rollback");

        Envelope next = arrive("m-2", bytes(300, 23));
        Future<String> saved = saveKeyed(next, KEY, () -> false);
        assertTrue(saved.succeeded(), "the next arrival with the key saves: " + saved.cause());
        assertEquals("m-2", saved.result(), "as itself: the abandoned save left the key free");
        savedWhole("m-2");
        nothingOf("m-1");
        assertEquals(List.of(new Processed(SCOPE, sha256(KEY), "m-2", STARTED)), processed());
    }

    @Test
    void aFailedEnqueueLeavesTheKeyFree() {
        sql("create function refuse_work() returns trigger language plpgsql as $$ begin raise exception 'work refused by the test'; end $$");
        sql("create trigger refuse_work before insert on work for each row execute function refuse_work()");
        Future<String> failed = saveKeyed(arrive("m-1", bytes(300, 24)), KEY, () -> false);
        assertTrue(failed.failed(), "the enqueue failed: no save");
        assertTrue(causes(failed.cause()).stream().anyMatch(c -> c.contains("work refused by the test")), "with the enqueue's failure: " + causes(failed.cause()));
        assertEquals(List.of(), processed(), "its key row went with the rollback");

        sql("drop trigger refuse_work on work");
        Future<String> saved = saveKeyed(arrive("m-2", bytes(300, 24)), KEY, () -> false);
        assertTrue(saved.succeeded(), "saved: " + saved.cause());
        assertEquals("m-2", saved.result(), "the failed save left the key free");
        savedWhole("m-2");
        nothingOf("m-1");
    }

    @Test
    void aDuplicateOfACompletedItemStillAnswersTheFirstId() {
        assertEquals("m-1", saveKeyed(arrive("m-1", bytes(300, 25)), KEY, () -> false).result());
        sql("delete from work where id = 'm-1'");   // the worker completed the message: its item is gone, its rows stay
        assertEquals(List.of(), work(), "fixture: the item is completed");

        Future<String> duplicate = saveKeyed(arrive("m-2", bytes(300, 25)), KEY, () -> false);
        assertTrue(duplicate.succeeded(), "saved: " + duplicate.cause());
        assertEquals("m-1", duplicate.result(), "the key outlives the item: the first id");
        assertEquals(List.of(), work(), "no item queued again");
        nothingOf("m-2");
    }

    @Test
    void aKeyOfEightKilobytesIsSavedAndDeduped() {
        String key = "drop/inbox/" + "€".repeat(2730) + ".xml|1730196000000|300";   // 8 KiB of UTF-8: above a b-tree entry's bound unhashed
        assertTrue(key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8192, "fixture: over 8 KiB");

        Future<String> saved = saveKeyed(arrive("m-1", bytes(300, 26)), key, () -> false);
        assertTrue(saved.succeeded(), "a long key is saved: only its 32-byte digest is indexed — " + saved.cause());
        assertEquals("m-1", saved.result());
        Future<String> duplicate = saveKeyed(arrive("m-2", bytes(300, 26)), key, () -> false);
        assertEquals("m-1", duplicate.result(), "and deduped: " + duplicate.cause());
        assertEquals(List.of(new Processed(SCOPE, sha256(key), "m-1", STARTED)), processed());
        assertEquals(List.of("m-1/K-1"), work());
    }

    @Test
    void aDuplicatesLocalBodyIsEvicted() {
        assertEquals("m-1", saveKeyed(arrive("m-1", bytes(300, 27)), KEY, () -> false).result());
        Envelope again = arrive("m-2", bytes(400, 27));
        assertEquals(400, blobs.memoryInUse(), "fixture: the duplicate's body holds its budget (the first's went with its commit)");

        Future<Optional<TieredBlobStore.Location>> whereAtCompletion = settled(saveKeyed(intake, again, SCOPE, KEY, () -> false)
                .map(id -> blobs.location(again.body().key())));
        assertTrue(whereAtCompletion.succeeded(), "saved: " + whereAtCompletion.cause());
        assertEquals(Optional.empty(), whereAtCompletion.result(), "the duplicate's local body is evicted before its save completes");
        assertEquals(0, blobs.memoryInUse(), "with its budget");
        assertEquals(0, count("bodies", "m-2"), "and never made durable");
    }

    @Test
    void aDuplicateWhoseEvictionFailsStillAnswersTheFirstId() throws java.io.IOException {
        // a store whose memory tier takes nothing: every body spills to its spool file
        Path dir = java.nio.file.Files.createDirectories(spool.resolve("spilling"));
        TieredBlobStore spilling = new TieredBlobStore(new TieredBlobStore.Config(FLOW_MAX, 0, 1L << 20, dir, spoolKey(), null, false), Hasher.sha256(),
                new PgBodies(pool, BOUND));
        PgIntake through = new PgIntake(pool, spilling, messages, new PgWorkQueue(pool));
        assertEquals("m-1", settled(saveKeyed(intake, arrive("m-1", bytes(300, 28)), SCOPE, KEY, () -> false)).result());
        Body body = settled(spilling.write(BlobKey.body("m-2", 0), bytes(300, 28), FLOW_MAX)).result();
        assertEquals(Optional.of(TieredBlobStore.Location.SPOOL), spilling.location(body.key()), "fixture: the duplicate's body is in its spool file");
        List<Path> files;
        try (var walk = java.nio.file.Files.walk(dir)) {
            files = walk.filter(java.nio.file.Files::isRegularFile).toList();
        }
        assertEquals(1, files.size(), "fixture: one spool file: " + files);
        java.nio.file.Files.delete(files.get(0));   // its file is now a non-empty directory: deleting it fails
        java.nio.file.Files.createDirectories(files.get(0).resolve("blocker"));
        assertTrue(settled(spilling.evict(BlobKey.body("m-unknown", 0))).succeeded(), "fixture: an unknown key evicts quietly");
        Envelope again = Envelope.ingress("m-2", FLOW, Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), body);

        Future<String> duplicate = settled(saveKeyed(through, again, SCOPE, KEY, () -> false));
        assertTrue(duplicate.succeeded(), "an eviction that fails is logged, never the save's failure: " + duplicate.cause());
        assertEquals("m-1", duplicate.result());
        assertTrue(java.nio.file.Files.isDirectory(files.get(0)), "fixture: the eviction was asked and failed (the blocker still stands)");
        nothingOf("m-2");
    }

    @Test
    void aDuplicateWhoseFirstRowIsGoneIsAFailedSave() {
        assertEquals("m-1", saveKeyed(arrive("m-1", bytes(300, 29)), KEY, () -> false).result());
        // the retention job's delete, staged inside the duplicate's own transaction: after its insert found the key taken, before
        // it reads the first id (a statement trigger fires for an insert that inserted nothing); the save's rollback undoes it
        sql("create function lose_the_key() returns trigger language plpgsql as $$ begin delete from processed_ids; return null; end $$");
        sql("create trigger lose_the_key after insert on processed_ids for each statement execute function lose_the_key()");
        AtomicInteger asks = new AtomicInteger();

        Future<String> duplicate = saveKeyed(arrive("m-2", bytes(300, 29)), KEY, counting(asks, false));
        assertTrue(duplicate.failed(), "a key taken by a row that is gone names no first id: a failed save, the sender retries — " + duplicate);
        assertTrue(causes(duplicate.cause()).stream().anyMatch(c -> c.contains("gone")),
                "failed because the key's row is gone, not by reading a first id that is not there: " + causes(duplicate.cause()));
        assertEquals(0, asks.get(), "it never reaches the commit: abandoned is not asked");
        nothingOf("m-2");
        assertEquals(List.of(new Processed(SCOPE, sha256(KEY), "m-1", STARTED)), processed(), "fixture: the delete went with the rollback");
        assertEquals(List.of("m-1/K-1"), work());
    }

    @Test
    void aDuplicateAnswersTheIdOfItsOwnScopeAndKey() {
        String other = "drop/inbox/other.xml|1730196000000|300";
        assertEquals("m-1", settled(saveKeyed(intake, arrive("m-1", bytes(300, 32)), "shop/orders", KEY, () -> false)).result());
        assertEquals("m-2", settled(saveKeyed(intake, arrive("m-2", bytes(300, 33)), "shop/orders", other, () -> false)).result());
        assertEquals("m-3", settled(saveKeyed(intake, arrive("m-3", bytes(300, 34)), "shop/invoices", KEY, () -> false)).result());
        assertEquals(3, processed().size(), "fixture: three key rows, two per scope or per key: " + processed());

        Future<String> ordersKey = settled(saveKeyed(intake, arrive("m-4", bytes(300, 32)), "shop/orders", KEY, () -> false));
        Future<String> ordersOther = settled(saveKeyed(intake, arrive("m-5", bytes(300, 33)), "shop/orders", other, () -> false));
        Future<String> invoicesKey = settled(saveKeyed(intake, arrive("m-6", bytes(300, 34)), "shop/invoices", KEY, () -> false));
        assertEquals(List.of("m-1", "m-2", "m-3"), List.of(String.valueOf(ordersKey.result()), String.valueOf(ordersOther.result()),
                String.valueOf(invoicesKey.result())), "each duplicate answers the id saved under its own scope and key — never another row's: "
                + List.of(ordersKey, ordersOther, invoicesKey));
        assertEquals(List.of("m-1/K-1", "m-2/K-1", "m-3/K-1"), work(), "no item for a duplicate");
        for (String duplicate : List.of("m-4", "m-5", "m-6")) nothingOf(duplicate);
    }

    @Test
    void aDuplicateRacingAnOpenSaveAnswersTheFirstIdOnceItCommits() throws java.io.IOException {
        Node2 second = node2();
        Envelope first = arrive("m-1", bytes(300, 30));
        Envelope again = arriveIn(second.blobs(), "m-2", bytes(300, 30));
        AtomicInteger againAsks = new AtomicInteger();

        holdWork();
        Future<String> firstSave = saveKeyed(intake, first, SCOPE, KEY, () -> false);
        until(() -> lockWaiters() == 1, "the first save waits at its item's insert, its key row inserted and not committed");
        Future<String> duplicate = saveKeyed(second.intake(), again, SCOPE, KEY, counting(againAsks, false));
        until(() -> lockWaiters() == 2, "the duplicate waits as well: on the first save's open key row");
        assertFalse(firstSave.isComplete(), "fixture: the first save is open");
        assertFalse(duplicate.isComplete(), "a duplicate of an open save is not answered while the first is open");
        assertEquals(List.of(), processed(), "fixture: nothing committed yet");

        held.release();
        assertEquals("m-1", settled(firstSave).result(), "the first save commits once the lock is gone");
        Future<String> answered = settled(duplicate);
        assertTrue(answered.succeeded(), "the duplicate waited for the first's commit, then found the key taken: " + answered.cause());
        assertEquals("m-1", answered.result(), "and answers the first id");
        assertEquals(0, againAsks.get(), "a duplicate never asks abandoned");
        savedWhole("m-1");
        nothingOf("m-2");
        assertEquals(List.of("m-1/K-1"), work());
        assertEquals(List.of(new Processed(SCOPE, sha256(KEY), "m-1", STARTED)), processed());
        assertEquals(Optional.empty(), second.blobs().location(again.body().key()), "the duplicate's local body on its node is evicted");
    }

    @Test
    void aDuplicateRacingAnOpenSaveSavesItselfOnceTheFirstRollsBack() throws java.io.IOException {
        Node2 second = node2();
        Envelope first = arrive("m-1", bytes(300, 31));
        Envelope again = arriveIn(second.blobs(), "m-2", bytes(300, 31));

        holdWork();
        Future<String> firstSave = saveKeyed(intake, first, SCOPE, KEY, () -> true);   // decided meanwhile: it rolls back at its commit
        until(() -> lockWaiters() == 1, "the first save waits at its item's insert, its key row inserted and not committed");
        Future<String> racing = saveKeyed(second.intake(), again, SCOPE, KEY, () -> false);
        until(() -> lockWaiters() == 2, "the second save waits as well: on the first save's open key row");
        assertFalse(racing.isComplete(), "the second save is not answered while the first is open");

        held.release();
        assertTrue(settled(firstSave).failed(), "the first save is abandoned and rolls back");
        Future<String> answered = settled(racing);
        assertTrue(answered.succeeded(), "the key came free with the rollback: the second save goes on — " + answered.cause());
        assertEquals("m-2", answered.result(), "as itself");
        savedWhole("m-2");
        nothingOf("m-1");
        assertEquals(List.of("m-2/K-1"), work());
        assertEquals(List.of(new Processed(SCOPE, sha256(KEY), "m-2", STARTED)), processed());
    }
}
