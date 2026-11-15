package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobNotFoundException;
import io.flottila.api.Hash;
import io.flottila.api.Headers;
import io.flottila.api.Keys;
import io.flottila.api.Props;
import io.flottila.core.dlq.Parked;
import io.flottila.runtime.queue.PgWorkQueue;
import io.flottila.runtime.store.PgDlq;
import io.flottila.testkit.PostgresImage;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CORE-4c2 (CORE-3 item 10, last sentence; packet item 3): a replay or DLQ-retry enqueue adds no record — it copies its source's
 * ref onto the new work row. It is one statement whose CTE holds the source row (the key's {@code body_refs} row: a kept input
 * snapshot for a replay, the parked body for a retry) and that row's segment row {@code for key share} and inserts the work row
 * from them; no source row, or its segment row gone (swept) → {@link BlobNotFoundException} naming the key, nothing inserted.
 * A session holding either row for update makes the enqueue wait; its delete, once committed, leaves the enqueue with no row.
 *
 * <p>The seam this assumes: {@code PgWorkQueue.enqueue(SqlClient client, String messageId, String flow, int step,
 * String partitionKey, BlobKey body)} — the {@code enqueue} of today with the key whose ref the item takes. Rows are seeded by
 * SQL as the journal store writes them (PgDlqJournalIT's way): the enqueue reads rows, never the journal.
 */
@Timeout(120)
class ReferenceEnqueueIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static final Duration BOUND = Duration.ofSeconds(20);
    static final Instant T = Instant.parse("2026-11-04T10:00:00Z");
    static final String FLOW = "invoice-sync";
    static Vertx vertx;

    CaseDatabase db;
    PgWorkQueue queue;
    final List<PgConnection> sessions = new ArrayList<>();
    final List<AutoCloseable> closing = new ArrayList<>();

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

    @BeforeEach
    void open() throws Exception {
        db = CaseDatabase.create(pg, vertx, "ref_enqueue_").migrated();
        queue = new PgWorkQueue(db.pool(), "node-a");
    }

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable c : closing.reversed()) c.close();
        for (PgConnection s : sessions) s.close().await();
        db.close();
    }

    /** A work row's body ref: node, segment, position, length. */
    record Ref(String node, Long segment, Long position, Long length) { }

    /** {@code id}'s work row's body ref, or null without the row. */
    Ref workRef(Pool on, String id) {
        for (Row r : on.preparedQuery("select body_node, body_segment, body_position, body_length from work where id = $1").execute(Tuple.of(id)).await()) {
            return new Ref(r.getString(0), r.getLong(1), r.getLong(2), r.getLong(3));
        }
        return null;
    }

    /** Waits (bounded) until {@code f} completes, either way. */
    static <T> Future<T> settled(Future<T> f) {
        try {
            f.otherwiseEmpty().await(BOUND.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException pending) {
            fail("still pending after " + BOUND + ": " + f);
        }
        return f;
    }

    void segmentRow(long segment) {
        db.pool().preparedQuery("insert into journal_segments(node, segment, copies) values ('node-a', $1, array['node-a'])")
                .execute(Tuple.of(segment)).await();
    }

    /** {@code k}'s kept ref, as a persist or a keep-input save commits it: node-a, {@code segment}, {@code position}, 400 bytes. */
    void bodyRef(BlobKey k, long segment, long position) {
        db.pool().preparedQuery("insert into body_refs(message_id, step, part, node, segment, position, length) values ($1, $2, $3, 'node-a', $4, $5, 400)")
                .execute(Tuple.of(k.messageId(), k.step(), k.part(), segment, position)).await();
    }

    long count(String sql, Object... params) {
        return db.pool().preparedQuery(sql).execute(Tuple.wrap(params)).await().iterator().next().getLong(0);
    }

    long items(String id) {
        return count("select count(*) from work where id = $1", id);
    }

    static void refusedNotFound(Future<Void> f, BlobKey key, String what) {
        assertTrue(f.failed(), what + ": the enqueue has no source row");
        BlobNotFoundException e = assertInstanceOf(BlobNotFoundException.class, f.cause(), what + ": " + f.cause());
        assertEquals(key, e.key(), what + ": it names the source key");
    }

    PgConnection session() {
        PgConnection s = PgConnection.connect(vertx, db.options()).await();
        sessions.add(s);
        return s;
    }

    static int pid(PgConnection s) {
        return s.query("select pg_backend_pid()").execute().await().iterator().next().getInteger(0);
    }

    void waitsBehind(int holder, Future<Void> enqueue, String row) {
        long deadline = System.nanoTime() + BOUND.toNanos();
        while (count("select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'"
                + " and $1 = any(pg_blocking_pids(pid))", holder) == 0) {
            if (enqueue.isComplete()) fail("the enqueue did not wait for its " + row + " held for update: " + enqueue);
            if (System.nanoTime() > deadline) fail("the enqueue was not seen waiting on its " + row + " within " + BOUND);
            Thread.onSpinWait();
            Thread.yield();
        }
        assertFalse(enqueue.isComplete(), "it waits for the sweeper's batch on its " + row);
    }

    @Test
    void aReplayEnqueueCopiesItsSourcesRefInOneStatement() throws Exception {
        BlobKey input = BlobKey.body("m-src", 0);   // the kept input snapshot a replay from step 1 starts on
        segmentRow(7);
        bodyRef(input, 7, 4_096);
        try (PgWire wire = new PgWire(pg.getHost(), pg.getMappedPort(5432))) {
            Pool counted = PgBuilder.pool().connectingTo(db.options().setHost("127.0.0.1").setPort(wire.port()))
                    .with(new PoolOptions().setMaxSize(1)).using(vertx).build();
            closing.add(() -> counted.close().await());
            PgWorkQueue onTheWire = new PgWorkQueue(counted, "node-a");
            counted.query("select 1").execute().await();   // the one connection is open before the count starts

            int mark = wire.mark();
            Future<Void> enqueued = settled(onTheWire.enqueue(counted, "m-replay", FLOW, 1, "K-1", input));
            assertTrue(enqueued.succeeded(), "enqueued: " + enqueued.cause());
            assertEquals(1, wire.statementsSince(mark).size(), "one statement holds the source rows and inserts the item: " + wire.since(mark));
        }
        assertEquals(new Ref("node-a", 7L, 4_096L, 400L), workRef(db.pool(), "m-replay"),
                "the item takes its source's ref: no record added, the body named where it already lies");
        Row item = db.pool().query("select flow, step, partition_key from work where id = 'm-replay'").execute().await().iterator().next();
        assertEquals(List.of(FLOW, 1, "K-1"), List.of(item.getString(0), item.getInteger(1), item.getString(2)), "the item as asked");
        assertEquals(1, count("select count(*) from body_refs"), "and no ref row added: the source's stays the only one");
    }

    @Test
    void aDlqRetryEnqueueCopiesTheParkedRef() {
        BlobKey parkedBody = BlobKey.body("m-parked", 2);   // the envelope step 2 handed on: step 3 failed and parked it
        segmentRow(9);
        bodyRef(parkedBody, 9, 128);
        Parked p = new Parked("m-parked", FLOW, 3, "partner", "java.lang.IllegalStateException: 503 from partner", T,
                Headers.empty().with("content-type", "application/xml"), Props.empty(), Keys.empty(), new Hash("sha256-64", -7L), 400,
                parkedBody, Map.of("flow", FLOW + "@0.1.0"));
        assertTrue(settled(new PgDlq(db.pool()).park(p)).succeeded(), "fixture: parked on its ref");

        Future<Void> enqueued = settled(queue.enqueue(db.pool(), "m-retry", FLOW, 3, null, p.bodyKey()));
        assertTrue(enqueued.succeeded(), "the retry's item enqueued: " + enqueued.cause());
        assertEquals(new Ref("node-a", 9L, 128L, 400L), workRef(db.pool(), "m-retry"), "the retry's item names the parked body's ref");
        assertEquals(1, count("select count(*) from dlq where id = 'm-parked'"), "the DLQ row is the retry's to remove, not the enqueue's");
    }

    @Test
    void anEnqueueFromAMissingOrSweptSourceFailsBlobNotFoundAndInsertsNothing() {
        BlobKey never = BlobKey.body("m-never", 0);
        refusedNotFound(settled(queue.enqueue(db.pool(), "m-copy-1", FLOW, 1, null, never)), never, "no body_refs row");
        assertEquals(0, items("m-copy-1"), "no item without a source");

        BlobKey swept = BlobKey.body("m-swept", 0);
        bodyRef(swept, 11, 0);   // its segment row deleted by the sweeper (never inserted here)
        refusedNotFound(settled(queue.enqueue(db.pool(), "m-copy-2", FLOW, 1, null, swept)), swept, "a ref whose segment row is gone");
        assertEquals(0, items("m-copy-2"), "no item naming a swept body");

        BlobKey live = BlobKey.body("m-live", 0);
        segmentRow(12);
        bodyRef(live, 12, 0);
        assertTrue(settled(queue.enqueue(db.pool(), "m-copy-3", FLOW, 1, null, live)).succeeded(), "witness: a source with its segment row enqueues");
        assertEquals(1, items("m-copy-3"));
        assertEquals(1, count("select count(*) from work"), "the refused enqueues inserted nothing");
    }

    @Test
    void anEnqueueWaitsForItsLockedSourceAndSegmentRowsAndFindsNoRowOnceTheyAreDeleted() {
        // the source row held for update, then deleted (retention removing the message's refs)
        BlobKey first = BlobKey.body("m-first", 0);
        segmentRow(13);
        bodyRef(first, 13, 0);
        PgConnection retention = session();
        Transaction tx = retention.begin().await();
        RowSet<Row> locked = retention.query("select 1 from body_refs where message_id = 'm-first' for update").execute().await();
        assertEquals(1, locked.size(), "fixture: the source row is there to lock");
        Future<Void> behindSource = queue.enqueue(db.pool(), "m-after-source", FLOW, 1, null, first);
        waitsBehind(pid(retention), behindSource, "source row");
        retention.query("delete from body_refs where message_id = 'm-first'").execute().await();
        tx.commit().await();
        refusedNotFound(settled(behindSource), first, "the enqueue behind its source row's delete");
        assertEquals(0, items("m-after-source"), "no item naming a removed ref");

        // the segment row held for update, then deleted (the sweeper's batch)
        BlobKey second = BlobKey.body("m-second", 0);
        segmentRow(14);
        bodyRef(second, 14, 0);
        PgConnection sweeper = session();
        Transaction sweep = sweeper.begin().await();
        assertEquals(1, sweeper.query("select 1 from journal_segments where node = 'node-a' and segment = 14 for update").execute().await().size(),
                "fixture: the segment row is there to lock");
        Future<Void> behindSegment = queue.enqueue(db.pool(), "m-after-segment", FLOW, 1, null, second);
        waitsBehind(pid(sweeper), behindSegment, "segment row");
        sweeper.query("delete from journal_segments where node = 'node-a' and segment = 14").execute().await();
        sweep.commit().await();
        refusedNotFound(settled(behindSegment), second, "the enqueue behind its segment row's delete");
        assertEquals(0, items("m-after-segment"), "no item naming a swept body");
        assertNull(workRef(db.pool(), "m-after-segment"));
    }
}
