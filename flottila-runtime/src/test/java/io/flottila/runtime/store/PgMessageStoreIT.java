package io.flottila.runtime.store;

import io.flottila.api.BlobKey;
import io.flottila.api.Hash;
import io.flottila.api.Headers;
import io.flottila.api.Key;
import io.flottila.api.Keys;
import io.flottila.api.Props;
import io.flottila.core.batch.BatchingWriter;
import io.flottila.core.record.Page;
import io.flottila.core.record.Snapshot;
import io.flottila.runtime.db.Migrations;
import io.flottila.runtime.time.VertxTimer;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The Postgres MessageStore (ARCHITECTURE §5, §7, §8): snapshots through the P0-06 batcher into `messages` / `payloads` /
 * `message_keys` (V1 + V2), the timeline back by message id, messages by business key — every field round-trips.
 */
class PgMessageStoreIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static Vertx vertx;
    static Pool pool;
    PgMessageStore store;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        PgConnectOptions connect = new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()).setCachePreparedStatements(true);
        pool = PgBuilder.pool().connectingTo(connect).with(new PoolOptions().setMaxSize(8)).using(vertx).build();
        Migrations.Result r = Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        assertEquals("5", r.schemaVersion(), "V2 adds the snapshot columns, V3 the DLQ columns, V4 the work attempts, V5 the key sequence (P1-09a)");
    }

    @AfterAll
    static void stop() {
        pool.close().await();
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void open() {
        pool.query("truncate message_keys, messages, payloads").execute().await();
        store = new PgMessageStore(pool, new VertxTimer(vertx), new BatchingWriter.Options(100, Duration.ofMillis(10), 2, 1000));
    }

    @AfterEach
    void close() { store.close(); }

    static Snapshot snap(String id, int step, String name, Snapshot.Outcome outcome, Optional<String> error, Keys keys, Optional<BlobKey> bodyKey) {
        return new Snapshot(id, "invoice-sync", step, name, outcome, error, Instant.parse("2026-01-01T00:00:00.123456Z").plusSeconds(step), 1_500_000L * step,
                step == 2 ? Snapshot.CPU_UNKNOWN : 20_000L * step, Headers.empty().with("content-type", "application/xml").with("n", String.valueOf(step)),
                Props.empty().with(new Key<String>("PARTNER"), "domestic").with(new Key<Integer>("splitIndex"), step).with(new Key<Boolean>("flag"), true),
                keys, new Hash("sha256-64", -42L + step), 187 + step, bodyKey, Map.of("flow", "invoice-sync@0.1.0"));
    }

    @Test
    void everyFieldRoundTripsAndTheTimelineIsOrderedByStep() {
        Keys keys = Keys.empty().with("invoiceId", "4711");
        store.record(snap("m-1", 2, "route", Snapshot.Outcome.OK, Optional.empty(), keys, Optional.of(BlobKey.body("m-1", 0)))).await();
        store.record(snap("m-1", 0, "ingress", Snapshot.Outcome.OK, Optional.empty(), Keys.empty(), Optional.of(BlobKey.body("m-1", 0)))).await();
        store.record(snap("m-1", 1, "validate", Snapshot.Outcome.FAILED, Optional.of("validation failed: /Invoice/Id"), keys, Optional.empty())).await();
        List<Snapshot> t = store.timeline("m-1").await();
        assertEquals(List.of(0, 1, 2), t.stream().map(Snapshot::step).toList());
        assertEquals(snap("m-1", 0, "ingress", Snapshot.Outcome.OK, Optional.empty(), Keys.empty(), Optional.of(BlobKey.body("m-1", 0))), t.get(0));
        assertEquals(snap("m-1", 1, "validate", Snapshot.Outcome.FAILED, Optional.of("validation failed: /Invoice/Id"), keys, Optional.empty()), t.get(1), "metadata-only: no body key, hash and size kept; cpu unknown stays unknown");
        assertEquals(snap("m-1", 2, "route", Snapshot.Outcome.OK, Optional.empty(), keys, Optional.of(BlobKey.body("m-1", 0))), t.get(2));
        assertEquals(List.of(), store.timeline("nobody").await());
        long payloads = pool.query("select count(*) from payloads").execute().await().iterator().next().getLong(0);
        assertEquals(3, payloads, "one payloads row per distinct hash, no bytes in it");
        assertEquals(List.of("m-1"), store.findByKey("invoiceId", "4711", Optional.empty(), Page.MAX_LIMIT).await().items(), "once, although two steps carry the key");
        Snapshot odd = new Snapshot("m-odd", "f", 0, "ingress", Snapshot.Outcome.OK, Optional.empty(), Instant.EPOCH, 0, 0, Headers.empty(),
                Props.empty().with(new Key<Duration>("deadline"), Duration.ofSeconds(3)), Keys.empty(), new Hash("sha256-64", 1), 1, Optional.empty(), Map.of());
        store.record(odd).await();
        assertEquals("PT3S", store.timeline("m-odd").await().get(0).props().asMap().get("deadline"), "a prop that is not JSON-native is kept by its string form");
    }

    @Test
    void aBatchCarriesManyMessagesAndDuplicatesAreIgnored() {
        List<io.vertx.core.Future<Void>> saves = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            saves.add(store.record(snap("m-" + i, 0, "ingress", Snapshot.Outcome.OK, Optional.empty(), Keys.empty().with("invoiceId", "4711").with("orderNo", "o-" + i), Optional.of(BlobKey.body("m-" + i, 0)))));
            saves.add(store.record(snap("m-" + i, 1, "to", Snapshot.Outcome.FILTERED, Optional.empty(), Keys.empty(), Optional.empty())));
        }
        store.flush().await();   // batches commit concurrently (D-044): only a committed row is deterministically "first"
        saves.add(store.record(snap("m-7", 1, "to-again", Snapshot.Outcome.OK, Optional.empty(), Keys.empty(), Optional.empty())));
        store.flush().await();
        assertTrue(saves.stream().allMatch(io.vertx.core.Future::succeeded));
        assertEquals("to", store.timeline("m-7").await().get(1).stepName(), "a duplicate (id, step) after the committed row is ignored: ON CONFLICT DO NOTHING");
        assertEquals(50, store.findByKey("invoiceId", "4711", Optional.empty(), Page.MAX_LIMIT).await().items().size());
        assertEquals(List.of("m-9"), store.findByKey("orderNo", "o-9", Optional.empty(), Page.MAX_LIMIT).await().items());
        long rows = pool.query("select count(*) from messages").execute().await().iterator().next().getLong(0);
        assertEquals(100, rows);
        assertEquals(0, store.failed(), "no commit failed");
    }

    @Test
    void closeDrainsWhatIsQueuedAndAFailedCommitIsCounted() {
        io.vertx.core.Future<Void> queued = store.record(snap("m-x", 0, "ingress", Snapshot.Outcome.OK, Optional.empty(), Keys.empty(), Optional.empty()));
        store.close();
        assertTrue(queued.succeeded(), "close flushes");
        assertEquals(1, store.timeline("m-x").await().size());
        store = new PgMessageStore(pool, new VertxTimer(vertx), new BatchingWriter.Options(100, Duration.ofMillis(10), 2, 1000));
        pool.query("alter table messages add constraint no_m_bad check (id <> 'm-bad')").execute().await();
        try {
            io.vertx.core.Future<Void> bad = store.record(snap("m-bad", 0, "ingress", Snapshot.Outcome.OK, Optional.empty(), Keys.empty(), Optional.empty()));
            store.flush().otherwiseEmpty().await();   // the flush reports the failure too; the snapshot's own future is what the test checks
            assertTrue(bad.failed());
            assertTrue(bad.cause().getMessage().contains("no_m_bad"), bad.cause().getMessage());
            assertEquals(1, store.failed(), "the message path does not await the write; the counter is what monitoring sees");
        } finally {
            pool.query("alter table messages drop constraint no_m_bad").execute().await();
        }
    }

    // --- D-057 (a): key search paged by the V5 key sequence

    static Snapshot keyed(String id, int step, String value) {
        return snap(id, step, "s" + step, Snapshot.Outcome.OK, Optional.empty(), Keys.empty().with("invoiceId", value), Optional.empty());
    }

    Page<String> page(String value, Optional<String> after, int limit) {
        return store.findByKey("invoiceId", value, after, limit).await();
    }

    @Test
    void aMessageRecordedAtManyStepsIsFoundOnce() {
        for (int step = 0; step < 5; step++) store.record(keyed("m-1", step, "4711")).await();   // one transaction per step: a key row each before V5
        store.record(keyed("m-2", 1, "4711")).await();
        store.record(keyed("m-1", 7, "4711")).await();
        assertEquals(List.of("m-1", "m-2"), page("4711", Optional.empty(), Page.MAX_LIMIT).items(), "once each, in the order each first carried the key");
        Page<String> first = page("4711", Optional.empty(), 1);
        assertEquals(List.of("m-1"), first.items());
        Page<String> second = page("4711", first.next(), 1);
        assertEquals(List.of("m-2"), second.items(), "m-1's later steps do not come back on a later page");
        assertEquals(Optional.empty(), second.next());
        long rows = pool.query("select count(*) from message_keys where name = 'invoiceId' and value = '4711'").execute().await().iterator().next().getLong(0);
        assertEquals(2, rows, "one key row per (name, value, message): later steps insert nothing (unique, on conflict do nothing)");
    }

    @Test
    void theLastPageHasNoNextCursor() {
        for (String id : List.of("m-1", "m-2", "m-3")) store.record(keyed(id, 0, "4711")).await();
        Page<String> exact = page("4711", Optional.empty(), 3);
        assertEquals(List.of("m-1", "m-2", "m-3"), exact.items());
        assertEquals(Optional.empty(), exact.next(), "exactly limit items left: limit + 1 fetch, no empty page after");
        assertEquals(Optional.empty(), page("4711", Optional.empty(), 4).next());
        Page<String> first = page("4711", Optional.empty(), 2);
        assertEquals(List.of("m-1", "m-2"), first.items());
        assertTrue(first.next().isPresent(), "a third item exists");
        Page<String> last = page("4711", first.next(), 2);
        assertEquals(List.of("m-3"), last.items());
        assertEquals(Optional.empty(), last.next());
        Page<String> none = page("0", Optional.empty(), 2);
        assertEquals(List.of(), none.items());
        assertEquals(Optional.empty(), none.next());
    }

    @Test
    void aGarbledCursorOrLimitOutsideOneToFiveHundredIsRefused() {
        store.record(keyed("m-1", 0, "4711")).await();
        store.record(keyed("m-2", 0, "4711")).await();
        for (int limit : new int[] {0, -1, Page.MAX_LIMIT + 1, Integer.MIN_VALUE}) refused(() -> page("4711", Optional.empty(), limit), "limit " + limit);
        String notACursor = Base64.getUrlEncoder().withoutPadding().encodeToString("x".getBytes(StandardCharsets.UTF_8));
        for (String cursor : List.of("!!!", notACursor)) refused(() -> page("4711", Optional.of(cursor), 1), "cursor " + cursor);
        assertEquals(List.of("m-1"), page("4711", Optional.empty(), 1).items(), "1 is a limit");
        assertEquals(List.of("m-1", "m-2"), page("4711", Optional.empty(), Page.MAX_LIMIT).items(), "500 is a limit");
    }

    /** Refused with IllegalArgumentException (the admin API's 400), thrown at the call or as the failed future's cause. */
    static void refused(org.junit.jupiter.api.function.Executable call, String what) {
        Throwable t = assertThrows(Throwable.class, call, what + " is refused");
        assertInstanceOf(IllegalArgumentException.class, t, what + ": " + t);
    }

    /**
     * D-044's deadlock rule for V5's unique (name, value, message_id): two batches carrying the same keys in opposite orders.
     * A held transaction stages the interleaving that deadlocks unsorted inserts: it holds the plug batch (so the next batches
     * form whole) and one key row in the middle of both batches; batch A waits there, batch B behind it. The holder rolls back;
     * with every batch's keys inserted in one order, A finishes and B's rows are no-ops — unsorted, A and B wait on each other.
     */
    @Test
    void concurrentBatchesSharingKeysDoNotDeadlock() throws Exception {
        int n = 200;
        store.close();
        store = new PgMessageStore(pool, new VertxTimer(vertx), new BatchingWriter.Options(n, Duration.ofHours(1), 3, 4 * n));
        Buffer plugHash = Buffer.buffer(ByteBuffer.allocate(Long.BYTES).putLong(999L).array());
        SqlConnection holder = pool.getConnection().await();
        Transaction held = holder.begin().await();
        List<Future<Void>> saves = new ArrayList<>();
        try {
            holder.preparedQuery("insert into payloads(hash, size, algorithm) values ($1, 1, 'sha256-64')").execute(Tuple.of(plugHash)).await();
            holder.query("insert into message_keys(name, value, message_id, step) values ('invoiceId', '4711', '" + id(n / 2) + "', 9)").execute().await();
            Snapshot plug = new Snapshot("plug", "invoice-sync", 0, "ingress", Snapshot.Outcome.OK, Optional.empty(), Instant.EPOCH, 0, 0, Headers.empty(), Props.empty(),
                    Keys.empty(), new Hash("sha256-64", 999L), 1, Optional.empty(), Map.of());
            saves.add(store.record(plug));
            until(() -> lockWaiters() == 1, "the plug batch waits on the held payload");
            for (int i = 1; i <= n; i++) saves.add(store.record(keyed(id(i), 0, "4711")));
            until(() -> lockWaiters() == 2, "batch A (" + n + " snapshots, keys ascending) waits on the held key row " + id(n / 2));
            for (int i = n; i >= 1; i--) saves.add(store.record(keyed(id(i), 1, "4711")));
            until(() -> lockWaiters() == 3, "batch B (" + n + " snapshots, keys descending) waits too");
        } finally {
            held.rollback().await();
            holder.close().await();
        }
        Future.join(new ArrayList<>(saves)).otherwiseEmpty().await(60, TimeUnit.SECONDS);
        for (Future<Void> save : saves) {
            if (save.failed()) fail("a batch failed: " + save.cause());
        }
        assertEquals(0, store.failed(), "no commit failed");
        List<String> expected = new ArrayList<>();
        for (int i = 1; i <= n; i++) expected.add(id(i));
        List<String> found = page("4711", Optional.empty(), Page.MAX_LIMIT).items();
        assertEquals(expected, found, "each message once");
        assertEquals(n, new HashSet<>(found).size());
        long rows = pool.query("select count(*) from message_keys").execute().await().iterator().next().getLong(0);
        assertEquals(n, rows, "B's rows were no-ops");
    }

    /**
     * Within one batch a message carries the same key at several steps: the one key row keeps the lowest step, as if the
     * steps had been written one by one (the first wins). A held plug batch makes the two snapshots form one batch, the
     * later step first; the row's step is what the table shows.
     */
    @Test
    void aBatchCarryingAKeyAtSeveralStepsKeepsTheFirstStepsRow() throws Exception {
        store.close();
        store = new PgMessageStore(pool, new VertxTimer(vertx), new BatchingWriter.Options(2, Duration.ofHours(1), 2, 10));
        Buffer plugHash = Buffer.buffer(ByteBuffer.allocate(Long.BYTES).putLong(998L).array());
        SqlConnection holder = pool.getConnection().await();
        Transaction held = holder.begin().await();
        Future<Void> plugged;
        try {
            holder.preparedQuery("insert into payloads(hash, size, algorithm) values ($1, 1, 'sha256-64')").execute(Tuple.of(plugHash)).await();
            plugged = store.record(new Snapshot("plug", "invoice-sync", 0, "ingress", Snapshot.Outcome.OK, Optional.empty(), Instant.EPOCH, 0, 0, Headers.empty(),
                    Props.empty(), Keys.empty(), new Hash("sha256-64", 998L), 1, Optional.empty(), Map.of()));
            until(() -> lockWaiters() == 1, "the plug batch waits on the held payload");
            Future<Void> later = store.record(keyed("m-1", 3, "4711"));
            Future<Void> earlier = store.record(keyed("m-1", 1, "4711"));
            Future.all(later, earlier).await(20, TimeUnit.SECONDS);   // one batch of two, committed while the plug still waits
            assertEquals(1, lockWaiters(), "the plug is still held: the two snapshots went in a batch of their own");
        } finally {
            held.rollback().await();
            holder.close().await();
        }
        plugged.await(20, TimeUnit.SECONDS);
        List<Integer> steps = new ArrayList<>();
        for (io.vertx.sqlclient.Row r : pool.query("select step from message_keys where message_id = 'm-1'").execute().await()) steps.add(r.getInteger(0));
        assertEquals(List.of(1), steps, "one key row, at the lowest step of the batch");
        assertEquals(List.of(1, 3), store.timeline("m-1").await().stream().map(Snapshot::step).toList());
        assertEquals(List.of("m-1"), page("4711", Optional.empty(), Page.MAX_LIMIT).items());
    }

    static String id(int i) { return String.format("m-%03d", i); }

    long lockWaiters() {
        return pool.query("select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'").execute().await()
                .iterator().next().getLong(0);
    }

    /** Polls Postgres (no sleep) until the condition holds; fails after 20 s naming what never happened. */
    static void until(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("not within 20 s: " + what);
            Thread.onSpinWait();
        }
    }
}
