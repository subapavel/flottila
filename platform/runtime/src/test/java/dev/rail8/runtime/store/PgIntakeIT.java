package dev.rail8.runtime.store;

import dev.rail8.api.BlobKey;
import dev.rail8.api.Body;
import dev.rail8.api.Envelope;
import dev.rail8.api.Hasher;
import dev.rail8.api.Headers;
import dev.rail8.core.batch.BatchingWriter;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.core.record.Meter;
import dev.rail8.core.record.Recorder;
import dev.rail8.core.record.RecordingPolicy;
import dev.rail8.core.record.Snapshot;
import dev.rail8.runtime.blob.TieredBlobStore;
import dev.rail8.runtime.db.Migrations;
import dev.rail8.runtime.journal.JournalRef;
import dev.rail8.runtime.journal.TestJournals;
import dev.rail8.runtime.queue.NotAdmittedException;
import dev.rail8.runtime.queue.PgWorkQueue;
import dev.rail8.runtime.time.VertxTimer;
import dev.rail8.testkit.InMemoryBlobStore;
import dev.rail8.testkit.PostgresImage;
import io.vertx.core.Future;
import io.vertx.core.Promise;
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

import java.nio.file.Path;
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
import java.util.Set;
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
 * <p>A save held open is staged without sleeps: a second session holds {@code lock table work in share mode}, so the save stays
 * open at its item's insert; {@code pg_stat_activity} shows who waits.
 *
 * <p>The shape assumed: {@code dev.rail8.runtime.store.PgIntake(Pool, TieredBlobStore, PgMessageStore, PgWorkQueue) implements Intake};
 * {@code Future<String> save(Envelope, Snapshot, String flow, String partitionKey, boolean keepInput, Future<?> abandoned)}.
 *
 * <p>Expectations changed by CORE-4c2 (D-105, CORE-3 item 6): the body store's durable tier is the node's journal, so a save
 * inserts no {@code bodies} row — the record is forced before the transaction, which commits the ingress row, the item with the
 * ref and, the flow keeping its input (every save here passes {@code keepInput} true, the default policy), the {@code body_refs}
 * row. "The body is durable" reads that row's ref back from the journal ({@link #kept}); "no body row" covers both tables. The
 * shape: {@code TieredBlobStore.Config(treeLimit, memoryTier, memoryBudget, durableMax)}, {@code new TieredBlobStore(Config, Hasher,
 * Journal, int tenantKeyId, Pool)}.
 */
@Timeout(60)
class PgIntakeIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static final AtomicInteger cases = new AtomicInteger();
    static final long BOUND = 1000;
    static final long FLOW_MAX = 4 * BOUND;
    static final String FLOW = "orders";
    static Vertx vertx;

    @TempDir Path spool;   // the journal directories of the case's nodes (CORE-4c2: no spool any more)
    String database;
    Pool pool;    // the intake's pool
    Pool other;   // another session's view of the case database
    TieredBlobStore blobs;
    TestJournals.Opened journal;   // node-1's journal, its rows on {@code other}
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

    /**
     * The node's body store over {@code journal} (CORE-4c2: the journal replaces the spool and the bodies tier): a 1 MiB memory tier
     * and budget, durable bound {@link #BOUND}, its rows on {@code pool}.
     */
    static TieredBlobStore blobs(TestJournals.Opened journal, Pool pool) {
        return new TieredBlobStore(new TieredBlobStore.Config(FLOW_MAX, 1L << 20, 1L << 20, BOUND), Hasher.sha256(), journal.journal(),
                journal.tenant(), pool);
    }


    // --- fixture

    @BeforeEach
    void openTheStores() throws Exception {
        database = caseDatabase(pg, "intake_" + cases.incrementAndGet());
        pool = pool(vertx, pg, database);
        other = pool(vertx, pg, database, 2);
        journal = TestJournals.open(other, java.nio.file.Files.createDirectories(spool.resolve("node-1")), "node-1");
        blobs = blobs(journal, pool);
        messages = new PgMessageStore(pool, new VertxTimer(vertx), BatchingWriter.Options.defaults());
        intake = new PgIntake(pool, blobs, messages, new PgWorkQueue(pool, "node-1"));
        // only the retention job deletes from bodies (P2-07): a save path that deletes fails its case
        sql("create function bodies_never_deleted() returns trigger language plpgsql as "
                + "$$ begin raise exception 'a DELETE on bodies: only the retention job deletes (P2-07)'; end $$");
        sql("create trigger bodies_never_deleted before delete on bodies for each statement execute function bodies_never_deleted()");
    }

    @AfterEach
    void closeTheStores() throws Exception {
        if (held != null) held.close();
        if (messages != null) messages.close();
        if (journal != null) journal.close();
        if (pool != null) pool.close().await();
        if (other != null) other.close().await();
    }

    void sql(String statement) {
        other.query(statement).execute().await();
    }

    long count(String table, String id) {
        String column = table.equals("bodies") || table.equals("body_refs") ? "message_id" : "id";
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

    /** The bytes of {@code k}'s kept ref ({@code body_refs}), read back from the journal by that ref; empty without a row. */
    Optional<Buffer> kept(BlobKey k) throws Exception {
        for (Row r : other.preparedQuery("select segment, position, length from body_refs where message_id = $1 and step = $2 and part = $3")
                .execute(Tuple.of(k.messageId(), k.step(), k.part())).await()) {
            JournalRef ref = new JournalRef(r.getLong(0), r.getLong(1), r.getLong(2));
            return Optional.of(Buffer.buffer(dev.rail8.runtime.node.OnVirtualThread.call(() -> journal.journal().read(ref))));
        }
        return Optional.empty();
    }

    /** The work rows as {@code id/partition_key}, in seq order. */
    List<String> work() {
        List<String> out = new ArrayList<>();
        for (Row r : other.query("select id, partition_key from work order by seq").execute().await()) out.add(r.getString(0) + "/" + r.getString(1));
        return out;
    }

    /** Every insert into bodies, messages, work and body_refs from now on is logged with its table and its transaction, in insert order. */
    void logTransactions() {
        sql("create table txlog(n bigserial primary key, tbl text not null, txid bigint not null)");
        sql("create function log_tx() returns trigger language plpgsql as "
                + "$$ begin insert into txlog(tbl, txid) values (tg_table_name, txid_current()); return null; end $$");
        for (String t : List.of("bodies", "messages", "work", "body_refs")) sql("create trigger log_tx after insert on " + t + " for each row execute function log_tx()");
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

    /** A save under the cases' one partition key, its input kept. */
    Future<String> save(Envelope e, Future<?> abandoned) {
        return settled(intake.save(e, ingress(e), FLOW, "K-1", true, abandoned));
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

    /** Nothing of message {@code id} in Postgres: no ingress row (its hash with it: V11 dropped payloads, D-097), no body row, no item. */
    void nothingCommitted(String id) {
        assertEquals(0, count("messages", id), "no ingress row");
        assertEquals(0, count("bodies", id), "no body row");
        assertEquals(0, count("body_refs", id), "no kept ref (CORE-4c2)");
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

    /**
     * An intake over a queue with admission keeps a message only for a flow a record runs at-least-once, a mark retires or the node
     * names: a save for any other flow fails {@link NotAdmittedException}, nothing of it is committed and its body stays local.
     */
    @Test
    void aSaveIsKeptOnlyForAFlowThePoolRunsRetiresOrTheNodeNames() {
        PgIntake admitting = new PgIntake(pool, blobs, messages, new PgWorkQueue(pool, "node-1", null, new VertxTimer(vertx), () -> Set.of("shop/named")));
        sql("insert into deployments (package, version, repository, commit, content, deployed_at, dependencies, at_least_once) values "
                + "('shop', '1.0.0', 'shop', repeat('1', 40), 'sha256:' || repeat('1', 64), now(), '[]'::jsonb, '{shop/recorded}')");
        mark(other, "shop/retired");
        Buffer bytes = bytes(300, 7);
        Envelope gone = arrive("m-gone", bytes);

        Future<String> refused = settled(admitting.save(gone, ingress(gone), "shop/gone", "K-1", true, Promise.promise().future()));

        assertTrue(refused.failed(), "a flow the pool no longer runs: the save fails");
        assertTrue(causes(refused.cause()).stream().anyMatch(c -> c.startsWith(NotAdmittedException.class.getName())), "not admitted: " + causes(refused.cause()));
        nothingCommitted("m-gone");
        stillLocal(gone, bytes);
        for (String flow : List.of("shop/recorded", "shop/retired", "shop/named")) {
            Envelope in = arrive("m-" + flow.substring(5), bytes(300, 8));
            Future<String> kept = settled(admitting.save(in, ingress(in), flow, "K-" + flow, true, Promise.promise().future()));
            assertTrue(kept.succeeded(), flow + " is admitted: " + (kept.failed() ? causes(kept.cause()) : ""));
            assertEquals(1, count("work", in.id()), flow + ": its work row");
        }
    }

    /** Marks {@code flow} retired, as the deploy store marks it. */
    static void mark(Pool on, String flow) {
        on.preparedQuery("insert into retiring (flow, package, version, repository, commit, content, dependencies, deployed_at, marked_at) "
                + "values ($1, 'shop', '1.0.0', 'shop', repeat('1', 40), 'sha256:' || repeat('1', 64), '[]'::jsonb, now(), now())")
                .execute(Tuple.of(flow)).await();
    }

    @Test
    void aSessionKilledBetweenRecordAndEnqueueLeavesNeither() {
        sql("create sequence work_inserts");
        sql("create sequence saw_record");
        sql("create function kill_at_enqueue() returns trigger language plpgsql as $$ begin "
                + "perform nextval('work_inserts'); "
                + "if exists (select 1 from messages where id = new.id) then perform nextval('saw_record'); end if; "
                + "perform pg_terminate_backend(pg_backend_pid()); "
                + "return new; end $$");
        sql("create trigger kill_at_enqueue before insert on work for each row execute function kill_at_enqueue()");
        Buffer bytes = bytes(300, 1);
        Envelope in = arrive("m-killed", bytes);

        Future<String> saved = save(in, Promise.promise().future());
        assertTrue(saved.failed(), "the session died before its commit: the save fails");
        assertTrue(bumped("work_inserts"), "fixture: the session reached the item's insert, and died there");
        // CORE-4c2: no body row precedes the item any more — the record was forced before the transaction (PgIntakeJournalIT)
        assertTrue(bumped("saw_record"), "so was the ingress record");
        nothingCommitted("m-killed");
        stillLocal(in, bytes);
        assertEquals(0, messages.failed(), "a failed intake is no snapshot write failure: no acknowledged row is missing");
    }

    @Test
    void aFailingEnqueueRollsBackRecordAndBody() throws Exception {
        sql("create function refuse_work() returns trigger language plpgsql as $$ begin raise exception 'work refused by the test'; end $$");
        sql("create trigger refuse_work before insert on work for each row execute function refuse_work()");
        Buffer bytes = bytes(300, 2);
        Envelope in = arrive("m-refused", bytes);

        Future<String> saved = save(in, Promise.promise().future());
        assertTrue(saved.failed(), "the enqueue failed: no save");
        assertTrue(causes(saved.cause()).stream().anyMatch(c -> c.contains("work refused by the test")), "with the enqueue's failure: " + causes(saved.cause()));
        nothingCommitted("m-refused");
        stillLocal(in, bytes);
        assertEquals(0, messages.failed(), "a failed intake is no snapshot write failure");

        sql("drop trigger refuse_work on work");
        Future<String> retry = save(in, Promise.promise().future());
        assertTrue(retry.succeeded(), "the sender's retry saves once the enqueue succeeds: " + retry.cause());
        assertEquals(Optional.of(bytes), kept(in.body().key()), "the body is durable: its kept ref reads it back from the journal (CORE-4c2)");
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

        Future<Optional<TieredBlobStore.Location>> whereAtCompletion = settled(intake.save(first, ingress(first), FLOW, "K-1", true, Promise.promise().future())
                .map(v -> blobs.location(first.body().key())));
        assertTrue(whereAtCompletion.succeeded(), "saved: " + whereAtCompletion.cause());
        assertEquals(Optional.empty(), whereAtCompletion.result(), "the local copy is released before the save's future completes");
        assertTrue(save(second, Promise.promise().future()).succeeded());

        // CORE-4c2: the body is no row — its record is forced before the transaction; then the record, then the item with its
        // ref and the kept ref (body_refs) in either order
        List<Logged> log = logged();
        assertEquals(6, log.size(), "three inserts per save, no bodies row: " + log);
        for (int save = 0; save < 2; save++) {
            List<String> tables = log.subList(3 * save, 3 * save + 3).stream().map(Logged::table).toList();
            assertEquals("messages", tables.get(0), "save " + save + ": the record first: " + log);
            assertEquals(java.util.Set.of("work", "body_refs"), java.util.Set.copyOf(tables.subList(1, 3)), "save " + save + ": then item and kept ref: " + log);
        }
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
        Future<String> saved = save(in, Promise.promise().future());
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
        Promise<Void> answer = Promise.promise();

        holdWork();
        Future<String> pending = intake.save(in, ingress(in), FLOW, "K-1", true, answer.future());
        until(() -> lockWaiters() == 1, "the save waits at its item's insert, inside its transaction");
        answer.complete();   // the delivery's answer is decided while the transaction is open
        held.release();
        Future<String> saved = settled(pending);
        assertTrue(saved.failed(), "an abandoned save fails");
        assertTrue(causes(saved.cause()).stream().anyMatch(c -> c.contains("abandoned")), "by its abandonment: " + causes(saved.cause()));
        assertTrue(bumped("work_inserts"), "decided after the item's insert: inserted, then rolled back — nothing commits after the answer");
        nothingCommitted("m-abandoned");
        stillLocal(in, bytes);
    }

    @Test
    void theIngressRowStoresNullCpu() {
        Envelope in = arrive("m-cpu", bytes(300, 9));
        Meter reading = () -> 5_000L;   // a meter with a reading: the ingress row still measured nothing (F-21)
        Snapshot ingress = new Recorder(new InMemoryMessageStore(), Clock.fixed(Instant.parse("2026-10-29T10:00:00.123456Z"), ZoneOffset.UTC), reading, Map.of())
                .startSnapshot(in, Recorder.INGRESS, RecordingPolicy.DEFAULT);

        Future<String> saved = settled(intake.save(in, ingress, FLOW, "K-1", true, Promise.promise().future()));
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
    void aBodyAlreadyDurableIsNotInsertedAgain() throws Exception {
        Buffer bytes = bytes(300, 6);
        Envelope in = arrive("m-durable", bytes);
        assertTrue(settled(blobs.persist(in.body().key())).succeeded(), "fixture: the body is durable already");
        assertEquals(Optional.empty(), blobs.location(in.body().key()), "fixture: and no longer local");
        logTransactions();

        Future<String> saved = save(in, Promise.promise().future());
        assertTrue(saved.succeeded(), "saved: " + saved.cause());
        assertEquals(List.of("messages", "work"), logged().stream().map(Logged::table).toList(), "a body already durable inserts nothing");
        assertEquals(1, count("body_refs", "m-durable"), "one kept ref, the persist's (CORE-4c2: no bodies row)");
        assertEquals(0, count("bodies", "m-durable"));
        assertEquals(Optional.of(bytes), kept(in.body().key()));
        assertEquals(List.of("m-durable/K-1"), work());
    }

    @Test
    void aBodyFromAnotherStoreIsPersistedThereFirst() {
        InMemoryBlobStore elsewhere = new InMemoryBlobStore(Hasher.sha256(), FLOW_MAX);
        BlobKey key = BlobKey.body("m-elsewhere", 0);
        Body body = settled(elsewhere.write(key, bytes(300, 7), FLOW_MAX)).result();
        Envelope in = Envelope.ingress("m-elsewhere", FLOW, Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), body);

        Future<String> saved = save(in, Promise.promise().future());
        assertTrue(saved.succeeded(), "saved: " + saved.cause());
        assertTrue(elsewhere.persisted(key), "the body is persisted in its own store");
        assertEquals(0, count("bodies", "m-elsewhere"), "not in the Postgres tier: it is another store's body");
        assertEquals(0, count("body_refs", "m-elsewhere"), "nor kept in this node's journal (CORE-4c2)");
        assertEquals(1, count("messages", "m-elsewhere"));
        assertEquals(List.of("m-elsewhere/K-1"), work());

        // that store refuses: nothing of the message is saved
        BlobKey refusedKey = BlobKey.body("m-refused-elsewhere", 0);
        settled(elsewhere.write(refusedKey, bytes(300, 8), FLOW_MAX));
        assertTrue(settled(elsewhere.persist(refusedKey)).succeeded(), "fixture: durable with the first bytes");
        Body rewritten = settled(elsewhere.write(refusedKey, bytes(300, 9), FLOW_MAX)).result();   // other bytes under a durable key
        Envelope refused = Envelope.ingress("m-refused-elsewhere", FLOW, Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), rewritten);
        Future<String> failed = save(refused, Promise.promise().future());
        assertTrue(failed.failed(), "the other store's persist failed: no save");
        assertEquals(0, count("messages", "m-refused-elsewhere"), "no ingress row");
        assertFalse(work().contains("m-refused-elsewhere/K-1"), "no item: " + work());
    }

    // --- a save held open at its item's insert

    /** A second session's open transaction holding a lock; {@link #release} rolls it back. */
    Held held;

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

    /** Takes {@code lock table work in share mode} in a transaction of a second session: every insert into work waits for it. */
    Held holdWork() {
        io.vertx.sqlclient.SqlConnection c = other.getConnection().await();
        io.vertx.sqlclient.Transaction tx = c.begin().await();
        c.query("lock table work in share mode").execute().await();
        held = new Held(c, tx, new AtomicBoolean());
        return held;
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
}
