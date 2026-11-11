package io.flottila.runtime.queue;

import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.Page;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-141 (D-126 L1-L3): a row an accepted {@code FLOTTILA_JOURNAL_ACCEPT_LOSS} gave up ({@code work.lost_at}, V13) is never claimed
 * and still heads its key, so the key's later rows wait. {@link PgWorkQueue#lost} pages every lost row with {@code blocks} — the rows
 * of its key behind it — and {@link PgWorkQueue#held}'s rows carry the same count. {@link PgWorkQueue#discard} deletes a row only
 * while it is lost — a keyed one under its key's advisory lock (D-121 A3) — and inserts exactly one {@code audit} row (V16) in the
 * same transaction; then the key's next row is claimable. A leased, held, plain or unknown row is neither deleted nor audited.
 * Rows are written by SQL and marked lost by {@code update work set lost_at} (what the start's ACCEPT_LOSS writes).
 *
 * <p>The API assumed (FIX-141, test-author, for the architect's approval): in core {@code WorkQueue} {@code record Lost(Item item,
 * Instant lostAt, long blocks)}, {@code Held} gaining {@code long blocks} (third component), {@code record Discarded(long auditId,
 * String flow, Optional<String> nextNode)} (nextNode: the body node of the key's next row, to announce), {@code Future<Page<Lost>>
 * lost(Optional<String> after, int limit)} and {@code Future<Optional<Discarded>> discard(long seq, String actor, String remote)}
 * (empty: nothing lost under that seq). The audit row: action {@code work.discard}, subject {@code work/<seq>}, detail
 * {@code {flow, messageId, partitionKey, lostAt, remote}}.
 */
@Timeout(120)
class PgWorkQueueLostIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final Duration LEASE = Duration.ofSeconds(30);
    static final Instant LOST_AT = Instant.parse("2026-11-04T10:00:00Z");
    static Vertx vertx;
    static Pool pool;
    static PgWorkQueue queue;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        pool = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()).setCachePreparedStatements(true))
                .with(new PoolOptions().setMaxSize(6)).using(vertx).build();
        io.flottila.runtime.db.Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        queue = new PgWorkQueue(pool);
    }

    @AfterAll
    static void stop() { pool.close().await(); vertx.close().await(); pg.stop(); }

    @BeforeEach
    void clean() { pool.query("truncate work, audit").execute().await(); }

    /** A plain row of {@code flow}, key {@code key}, its body on {@code bodyNode}; answers its seq. */
    static long row(String flow, String id, String key, String bodyNode) {
        return pool.preparedQuery("insert into work(id, flow, step, partition_key, body_node, body_segment, body_position, body_length)"
                + " values ($1, $2, 1, $3, $4, 1, 0, 10) returning seq").execute(Tuple.of(id, flow, key, bodyNode)).await().iterator().next().getLong(0);
    }

    static long row(String id, String key) {
        return row("orders", id, key, "node-a");
    }

    /** {@code seq} given up, as the start's ACCEPT_LOSS marks it, at {@link #LOST_AT}. */
    static void lose(long seq) {
        pool.preparedQuery("update work set lost_at = $2 where seq = $1").execute(Tuple.of(seq, LOST_AT.atOffset(java.time.ZoneOffset.UTC))).await();
    }

    static void hold(long seq) {
        pool.preparedQuery("update work set held_at = now() where seq = $1").execute(Tuple.of(seq)).await();
    }

    static long count(String sql) {
        return pool.query(sql).execute().await().iterator().next().getLong(0);
    }

    static boolean exists(long seq) {
        return pool.preparedQuery("select count(*) from work where seq = $1").execute(Tuple.of(seq)).await().iterator().next().getLong(0) == 1;
    }

    /** The DB clock now, read in a statement of its own ({@code clock_timestamp()}, not the transaction start). */
    static java.time.OffsetDateTime dbClock() {
        return pool.query("select clock_timestamp()").execute().await().iterator().next().getOffsetDateTime(0);
    }

    static List<String> claimed() {
        return queue.claim("node-a", "orders", 10, LEASE).await().stream().map(WorkQueue.Item::messageId).toList();
    }

    @Test
    void aLostHeadKeepsItsSuccessorUnclaimableWhileAnotherKeyRuns() {
        lose(row("m-1", "K"));
        row("m-2", "K");
        row("m-3", "L");
        assertEquals(List.of("m-3"), claimed(), "the lost head is never claimed and its successor waits; key L's row is claimed (the witness)");
        assertEquals(List.of(), claimed(), "still nothing of K");
    }

    @Test
    void discardDeletesALostKeyedHeadWritesOneAuditRowAndThenItsSuccessorIsClaimable() {
        long head = row("m-1", "K");
        lose(head);
        long otherKey = row("orders", "m-L", "L", "node-c");       // same flow, other key, after the head and before K's successor
        long otherFlow = row("returns", "m-R", "K", "node-d");     // other flow, same key, likewise
        long successor = row("orders", "m-2", "K", "node-b");
        assertTrue(head < otherKey && otherKey < otherFlow && otherFlow < successor, "fixture: seq order head < m-L < m-R < m-2");
        assertEquals(List.of("m-L"), claimed(), "fixture: K waits behind its lost head; key L runs (it stays in work, leased)");

        java.time.OffsetDateTime before = dbClock();
        WorkQueue.Discarded d = queue.discard(head, "ops-anna", "10.0.0.7").await().orElseThrow(() -> new AssertionError("a lost row is discarded"));
        java.time.OffsetDateTime after = dbClock();

        assertFalse(exists(head), "the lost row is deleted");
        assertTrue(exists(otherKey) && exists(otherFlow), "fixture: the other key's and the other flow's rows are still in work");
        assertEquals(1, count("select count(*) from audit"), "exactly one audit row");
        Row a = pool.query("select id, actor, action, subject, detail->>'flow', detail->>'messageId', detail->>'partitionKey',"
                + " (detail->>'lostAt')::timestamptz, detail->>'remote', at from audit").execute().await().iterator().next();
        assertEquals(a.getLong(0).longValue(), d.auditId(), "the answer names the audit row");
        assertEquals(List.of("ops-anna", "work.discard", "work/" + head, "orders", "m-1", "K", LOST_AT, "10.0.0.7"),
                List.of(a.getString(1), a.getString(2), a.getString(3), a.getString(4), a.getString(5), a.getString(6),
                        a.getOffsetDateTime(7).toInstant(), a.getString(8)), "actor, action, subject and the detail");
        java.time.OffsetDateTime at = a.getOffsetDateTime(9);
        assertTrue(!at.isBefore(before) && !at.isAfter(after),
                "the audit row's at is the discard's time on the DB clock (not lost_at " + LOST_AT + "): " + before + " <= " + at + " <= " + after);
        assertEquals("orders", d.flow());
        assertEquals(Optional.of("node-b"), d.nextNode(),
                "the key's next row of this flow lies on node-b: the node to announce (not node-c, another key; not node-d, another flow)");
        assertEquals(List.of("m-2"), claimed(), "the successor is claimable now");
    }

    @Test
    void anUnkeyedLostRowIsDiscardedWithANullKeyAndNoNextNode() {
        long lost = row("m-1", null);
        lose(lost);
        row("m-2", null);
        WorkQueue.Discarded d = queue.discard(lost, "unknown", "127.0.0.1").await().orElseThrow(() -> new AssertionError("discarded"));
        assertFalse(exists(lost), "deleted");
        assertEquals(Optional.empty(), d.nextNode(), "an unkeyed row has no next row of its key");
        Row a = pool.query("select (detail->>'partitionKey') is null, detail->>'messageId', detail->>'flow' from audit").execute().await().iterator().next();
        assertEquals(List.of(true, "m-1", "orders"), List.of(a.getBoolean(0), a.getString(1), a.getString(2)), "the detail names the row, without a key");
    }

    @Test
    void aFailingAuditInsertLeavesTheLostRowInPlaceAndAuditsNothing() {
        long head = row("m-1", "K");
        lose(head);
        row("m-2", "K");
        pool.query("create function fix141_refuse_audit() returns trigger language plpgsql as $$ begin raise exception 'audit refused (test)'; end $$")
                .execute().await();
        pool.query("create trigger fix141_refuse_audit before insert on audit for each row execute function fix141_refuse_audit()").execute().await();
        try {
            Exception failed = assertThrows(Exception.class, () -> queue.discard(head, "ops-anna", "10.0.0.7").await(), "the refused audit fails the discard");
            assertTrue(String.valueOf(failed.getMessage()).contains("audit refused (test)"), "the cause is the audit insert: " + failed);
        } finally {
            pool.query("drop trigger fix141_refuse_audit on audit").execute().await();
            pool.query("drop function fix141_refuse_audit()").execute().await();
        }
        assertTrue(exists(head), "the delete rolled back with the audit insert: the lost row is still there");
        assertEquals(1, count("select count(*) from work where lost_at is not null"), "and still lost");
        assertEquals(0, count("select count(*) from audit"), "nothing audited");
        assertEquals(List.of(), claimed(), "K still waits");
        assertTrue(queue.discard(head, "ops-anna", "10.0.0.7").await().isPresent(), "witness: without the trigger the same discard succeeds");
    }

    @Test
    void discardingALeasedHeldPlainOrUnknownRowDeletesNothingAndAuditsNothing() {
        long leased = row("m-1", null);
        assertEquals(List.of("m-1"), claimed(), "fixture: m-1 leased");
        long held = row("m-2", "H");
        hold(held);
        long plain = row("m-3", "P");
        long lost = row("m-4", "Q");
        lose(lost);
        long unknown = lost + 1000;

        for (long seq : new long[] {leased, held, plain, unknown}) {
            assertEquals(Optional.empty(), queue.discard(seq, "ops-anna", "10.0.0.7").await(), "seq " + seq + " is not lost: nothing to discard");
        }
        assertEquals(4, count("select count(*) from work"), "nothing deleted");
        assertEquals(0, count("select count(*) from audit"), "nothing audited");
        assertTrue(queue.discard(lost, "ops-anna", "10.0.0.7").await().isPresent(), "witness: the lost row of the same table is discarded");
        assertEquals(List.of(leased, held, plain), seqs(), "only the lost row went");
        assertEquals(Optional.empty(), queue.discard(lost, "ops-anna", "10.0.0.7").await(), "a second discard finds nothing");
        assertEquals(1, count("select count(*) from audit"), "audited once");
    }

    static List<Long> seqs() {
        List<Long> seqs = new ArrayList<>();
        for (Row r : pool.query("select seq from work order by seq").execute().await()) seqs.add(r.getLong(0));
        return seqs;
    }

    /** D-121 A3: a keyed discard takes its key's transaction advisory lock (class 0x0121, hashtext(flow U+001F key)). */
    @Test
    void aKeyedDiscardWaitsForItsKeysLock() throws Exception {
        long head = row("m-1", "K");
        lose(head);
        row("m-2", "K");
        SqlConnection locker = pool.getConnection().await();
        Transaction tx = locker.begin().await();
        Future<Optional<WorkQueue.Discarded>> discarding;
        try {
            locker.query("select pg_advisory_xact_lock(289, hashtext('orders' || chr(31) || 'K'))").execute().await();
            discarding = queue.discard(head, "ops-anna", "10.0.0.7");
            untilAStatementWaitsOnALock();
            assertFalse(discarding.isComplete(), "the discard waits for the key's lock");
            assertEquals(0, count("select count(*) from audit"), "nothing committed while it waits");
        } finally {
            tx.commit().await();
            locker.close().await();
        }
        assertTrue(discarding.await(20, TimeUnit.SECONDS).isPresent(), "once the lock is free the discard goes through");
        assertFalse(exists(head));
    }

    /** Spins (never sleeping) until a statement of the database waits on a lock; fails after 20 s. */
    static void untilAStatementWaitsOnALock() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (count("select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'") != 1) {
            if (System.nanoTime() - deadline > 0) throw new AssertionError("no statement waits on the key's lock within 20 s");
            Thread.onSpinWait();
        }
    }

    @Test
    void lostPagesEveryLostRowInSeqOrderWithTheRowsOfItsKeyBehindIt() {
        long k1 = row("m-1", "K");
        row("m-2", "K");
        row("m-3", "K");
        row("invoices", "i-1", "K", "node-a");   // another flow's key K: not behind orders' K
        long u = row("m-4", null);
        row("m-5", "M");
        long m6 = row("m-6", "M");
        row("m-7", "M");
        long held = row("m-8", "H");
        hold(held);
        lose(k1);
        lose(u);
        lose(m6);

        List<String> walked = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        Optional<String> after = Optional.empty();
        do {
            Page<WorkQueue.Lost> page = queue.lost(after, 2).await();
            sizes.add(page.items().size());
            for (WorkQueue.Lost l : page.items()) {
                walked.add(l.item().seq() + " " + l.item().messageId() + " " + l.item().flow() + " " + l.item().partitionKey() + " " + l.lostAt() + " " + l.blocks());
            }
            after = page.next();
        } while (after.isPresent());

        assertEquals(List.of(k1 + " m-1 orders K " + LOST_AT + " 2", u + " m-4 orders null " + LOST_AT + " 0", m6 + " m-6 orders M " + LOST_AT + " 1"),
                walked, "every lost row once, in seq order: K's head blocks m-2 and m-3, an unkeyed row nothing, M's m-6 blocks m-7 only");
        assertEquals(List.of(2, 1), sizes, "paged by the limit");
    }

    @Test
    void heldRowsCarryTheRowsOfTheirKeyBehindThem() {
        long k = row("m-1", "K");
        row("m-2", "K");
        row("invoices", "i-1", "K", "node-a");
        long u = row("m-3", null);
        hold(k);
        hold(u);
        List<String> held = queue.held(Optional.empty(), 10).await().items().stream()
                .map(h -> h.item().messageId() + " " + h.blocks()).toList();
        assertEquals(List.of("m-1 1", "m-3 0"), held, "a held head blocks the rest of its key in its flow; an unkeyed row nothing");
    }
}
