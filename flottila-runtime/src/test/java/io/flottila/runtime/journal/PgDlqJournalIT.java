package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobNotFoundException;
import io.flottila.api.Hash;
import io.flottila.api.Headers;
import io.flottila.api.Keys;
import io.flottila.api.Props;
import io.flottila.core.dlq.Parked;
import io.flottila.runtime.store.PgDlq;
import io.flottila.testkit.PostgresImage;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.pgclient.PgConnection;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CORE-4c2 (CORE-3 item 10, last sentence; D-062, D-096 on refs): a park is a reference insert that adds no record. It is one
 * statement whose CTE holds the {@code body_refs} row its body key names and that row's {@code journal_segments} row for key
 * share and inserts the DLQ row from them: without the ref, or with the ref but its segment row gone (swept), the park fails
 * {@link BlobNotFoundException} and writes no DLQ row; a session holding either row for update (the sweeper's batch) makes the
 * park wait, and a delete it then commits leaves the park with no row and no DLQ row. This replaces the {@code bodies}
 * key-share CTE: a {@code bodies} row alone (pre-V12) admits no park. Rows are seeded by SQL as the journal store writes them.
 */
@Timeout(120)
class PgDlqJournalIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static final Duration BOUND = Duration.ofSeconds(20);
    static final Instant T = Instant.parse("2026-11-04T00:00:00Z");
    static Vertx vertx;
    CaseDatabase db;
    PgDlq dlq;
    final List<PgConnection> sessions = new ArrayList<>();

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
        db = CaseDatabase.create(pg, vertx, "dlq_journal_").migrated();
        dlq = new PgDlq(db.pool());
    }

    @AfterEach
    void close() {
        for (PgConnection s : sessions) s.close().await();
        db.close();
    }

    static Parked parked(String id, int step) {
        return new Parked(id, "invoice-sync", step, "partner", "java.lang.IllegalStateException: 503 from partner", T,
                Headers.empty().with("content-type", "application/xml"), Props.empty(), Keys.empty(), new Hash("sha256-64", -7L), 187,
                BlobKey.body(id, step - 1), Map.of("flow", "invoice-sync@0.1.0"));
    }

    void segmentRow(long segment) {
        db.pool().preparedQuery("insert into journal_segments(node, segment, copies) values ('node-a', $1, array['node-a'])").execute(Tuple.of(segment)).await();
    }

    /** The kept ref of {@code k}, as {@code persist} commits it: node-a, {@code segment}, a position and a length. */
    void bodyRef(BlobKey k, long segment) {
        db.pool().preparedQuery("insert into body_refs(message_id, step, part, node, segment, position, length) values ($1, $2, $3, 'node-a', $4, 64, 400)")
                .execute(Tuple.of(k.messageId(), k.step(), k.part(), segment)).await();
    }

    long count(String sql, Object... params) {
        return db.pool().preparedQuery(sql).execute(Tuple.wrap(params)).await().iterator().next().getLong(0);
    }

    long dlqRows(String id) {
        return count("select count(*) from dlq where id = $1", id);
    }

    CompletableFuture<Void> parkOnAVirtualThread(Parked p) {
        CompletableFuture<Void> outcome = new CompletableFuture<>();
        Thread.ofVirtual().name("dlq-journal-it-park").start(() -> {
            try {
                dlq.park(p).await();
                outcome.complete(null);
            } catch (Throwable t) {
                outcome.completeExceptionally(t);
            }
        });
        return outcome;
    }

    static void settled(CompletableFuture<Void> outcome, String what) {
        long deadline = System.nanoTime() + BOUND.toNanos();
        while (!outcome.isDone()) {
            if (System.nanoTime() > deadline) fail(what + " did not end within " + BOUND);
            Thread.onSpinWait();
            Thread.yield();
        }
    }

    void refusedNotFound(Parked p, String what) {
        CompletableFuture<Void> park = parkOnAVirtualThread(p);
        settled(park, what);
        if (!park.isCompletedExceptionally()) fail(what + ": the park succeeded");
        BlobNotFoundException e = assertInstanceOf(BlobNotFoundException.class, park.exceptionNow(), what + ": " + park.exceptionNow());
        assertEquals(p.bodyKey(), e.key(), what + ": it names the body key");
        assertEquals(0, dlqRows(p.messageId()), what + ": no DLQ row");
    }

    PgConnection session() {
        PgConnection s = PgConnection.connect(vertx, db.options()).await();
        sessions.add(s);
        return s;
    }

    static int pid(PgConnection s) {
        return s.query("select pg_backend_pid()").execute().await().iterator().next().getInteger(0);
    }

    long blockedBy(int holder) {
        return count("select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock' and $1 = any(pg_blocking_pids(pid))", holder);
    }

    void waitsBehind(int holder, CompletableFuture<Void> park, String row) {
        long deadline = System.nanoTime() + BOUND.toNanos();
        while (blockedBy(holder) == 0) {
            if (park.isDone()) fail("the park did not wait for the lock on its " + row + ": it ended while the row was locked for update");
            if (System.nanoTime() > deadline) fail("the park was not seen waiting on its " + row + " within " + BOUND);
            Thread.onSpinWait();
            Thread.yield();
        }
    }

    @Test
    void aParkNeedsItsRefAndSegmentRow() {
        // ref and segment row: parked
        Parked whole = parked("m-whole", 3);
        segmentRow(7);
        bodyRef(whole.bodyKey(), 7);
        CompletableFuture<Void> ok = parkOnAVirtualThread(whole);
        settled(ok, "the park of a body with its ref and segment row");
        assertFalse(ok.isCompletedExceptionally(), "parked: " + (ok.isCompletedExceptionally() ? ok.exceptionNow() : ""));
        assertEquals(Optional.of(whole.bodyKey()), dlq.get("m-whole").await().map(Parked::bodyKey));

        // no ref (never persisted): refused
        refusedNotFound(parked("m-unpersisted", 3), "no body_refs row");

        // a ref whose segment row is gone (swept): refused
        Parked swept = parked("m-swept", 3);
        bodyRef(swept.bodyKey(), 8);   // segment 8 has no row
        refusedNotFound(swept, "a ref into a swept segment");

        // a pre-V12 bodies row alone: the bodies key-share CTE is replaced, the park asks the ref
        Parked pre = parked("m-pre", 3);
        db.pool().preparedQuery("insert into bodies(message_id, step, part, bytes) values ($1, $2, $3, $4)")
                .execute(Tuple.of(pre.bodyKey().messageId(), pre.bodyKey().step(), pre.bodyKey().part(), Buffer.buffer("<pre/>"))).await();
        refusedNotFound(pre, "a bodies row without a ref");

        // the sweeper's batch holds the segment row, then deletes it: the park waits, then finds no row
        Parked raced = parked("m-raced-segment", 3);
        segmentRow(9);
        bodyRef(raced.bodyKey(), 9);
        PgConnection sweeper = session();
        Transaction tx = sweeper.begin().await();
        assertEquals(1, sweeper.query("select 1 from journal_segments where node = 'node-a' and segment = 9 for update").execute().await().size(),
                "fixture: the segment row is there to lock");
        CompletableFuture<Void> waiting = parkOnAVirtualThread(raced);
        waitsBehind(pid(sweeper), waiting, "segment row");
        sweeper.query("delete from journal_segments where node = 'node-a' and segment = 9").execute().await();
        tx.commit().await();
        settled(waiting, "the park behind the segment row's delete");
        if (!waiting.isCompletedExceptionally()) fail("the park succeeded after its segment row was deleted");
        assertInstanceOf(BlobNotFoundException.class, waiting.exceptionNow(), String.valueOf(waiting.exceptionNow()));
        assertEquals(0, dlqRows("m-raced-segment"), "no DLQ row names the swept body");

        // a session holds the ref for update, then deletes it (retention): the park waits, then finds no row
        Parked gone = parked("m-raced-ref", 3);
        segmentRow(10);
        bodyRef(gone.bodyKey(), 10);
        PgConnection retention = session();
        Transaction rtx = retention.begin().await();
        assertEquals(1, retention.preparedQuery("select 1 from body_refs where message_id = $1 for update").execute(Tuple.of("m-raced-ref")).await().size(),
                "fixture: the ref is there to lock");
        CompletableFuture<Void> behindRef = parkOnAVirtualThread(gone);
        waitsBehind(pid(retention), behindRef, "body_refs row");
        retention.preparedQuery("delete from body_refs where message_id = $1").execute(Tuple.of("m-raced-ref")).await();
        rtx.commit().await();
        settled(behindRef, "the park behind the ref's delete");
        if (!behindRef.isCompletedExceptionally()) fail("the park succeeded after its ref was deleted");
        assertInstanceOf(BlobNotFoundException.class, behindRef.exceptionNow(), String.valueOf(behindRef.exceptionNow()));
        assertEquals(0, dlqRows("m-raced-ref"), "no DLQ row names the removed body");
        assertEquals(1, count("select count(*) from dlq"), "only the whole one is parked");
    }
}
