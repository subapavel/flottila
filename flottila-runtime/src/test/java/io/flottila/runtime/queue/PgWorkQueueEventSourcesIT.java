package io.flottila.runtime.queue;

import io.flottila.api.BlobKey;
import io.flottila.core.queue.WorkQueue;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-16b (D-121 (b) B4): what {@link PgWorkQueue} answers for the live sources of work events, read through its public methods.
 * (i) The fast path that takes nothing names the node of the key's next row — only when that row is neither held nor lost and
 * its body lies in another node's journal; a queue of any node takes it instead. (ii) {@link PgWorkQueue#releaseRow} answers the
 * released row's flow and body node. (iii) An enqueue copying a ref names the ref's node only when it is not the queue's own.
 * Rows are written by SQL (their body columns as an intake writes them), the queue being the node {@code node-a}'s.
 */
@Timeout(120)
class PgWorkQueueEventSourcesIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final Duration LEASE = Duration.ofSeconds(30);
    static Vertx vertx;
    static Pool pool;
    static PgWorkQueue onA;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        pool = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()).setCachePreparedStatements(true))
                .with(new PoolOptions().setMaxSize(4)).using(vertx).build();
        io.flottila.runtime.db.Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        onA = new PgWorkQueue(pool, "node-a");
    }

    @AfterAll
    static void stop() { pool.close().await(); vertx.close().await(); pg.stop(); }

    @BeforeEach
    void clean() { pool.query("truncate work, body_refs, journal_segments").execute().await(); }

    /** A plain row of flow {@code orders}, key {@code key}, its body on {@code bodyNode} (null: a pre-V12 row); answers its seq. */
    long row(String id, String key, String bodyNode) {
        return pool.preparedQuery("insert into work(id, flow, step, partition_key, body_node, body_segment, body_position, body_length)"
                + " values ($1, 'orders', 1, $2, $3, 1, 0, 10) returning seq").execute(Tuple.of(id, key, bodyNode)).await().iterator().next().getLong(0);
    }

    /** node-a claims the key's first row (its body on node-a) and completes it by the fast path: what the fast path answered. */
    WorkQueue.Completion completeFirst(PgWorkQueue queue) {
        List<WorkQueue.Item> taken = queue.claim("node-a", "orders", 1, LEASE).await();
        assertEquals(1, taken.size(), "fixture: node-a holds the first row");
        return queue.completeAndTakeNext(taken.get(0).seq(), taken.get(0).attempts(), "node-a", LEASE).await();
    }

    @Test
    void aFastPathThatCannotTakeTheNextRowNamesItsNode() {
        row("m-1", "K", "node-a");
        row("m-2", "K", "node-b");
        WorkQueue.Completion c = completeFirst(onA);
        assertTrue(c.completed());
        assertEquals(Optional.empty(), c.next(), "m-2's body lies on node-b: node-a cannot take it");
        assertEquals(Optional.of("node-b"), c.nextNode(), "and names node-b, whose journal holds it");
    }

    @Test
    void aHeldOrLostNextRowIsNamedForNobody() {
        row("m-1", "K", "node-a");
        long held = row("m-2", "K", "node-b");
        pool.preparedQuery("update work set held_at = now() where seq = $1").execute(Tuple.of(held)).await();
        assertEquals(Optional.empty(), completeFirst(onA).nextNode(), "a held next row is claimable nowhere: no node named");

        pool.query("truncate work").execute().await();
        row("m-3", "L", "node-a");
        long lost = row("m-4", "L", "node-b");
        pool.preparedQuery("update work set lost_at = now() where seq = $1").execute(Tuple.of(lost)).await();
        assertEquals(Optional.empty(), completeFirst(onA).nextNode(), "a lost next row is never claimed: no node named");
    }

    @Test
    void aKeyWithNothingLeftNamesNoNode() {
        row("m-1", "K", "node-a");
        row("m-x", "OTHER", "node-b");   // another key's row on node-b is not the key's next
        WorkQueue.Completion c = completeFirst(onA);
        assertEquals(new WorkQueue.Completion(true, Optional.empty()), c, "completed, nothing taken, no node named");
    }

    @Test
    void aNextRowTheQueueTakesNamesNoNode() {
        row("m-1", "K", "node-a");
        row("m-2", "K", null);   // a pre-V12 row: claimable by any node
        WorkQueue.Completion c = completeFirst(onA);
        assertEquals("m-2", c.next().orElseThrow().messageId(), "taken by the fast path");
        assertEquals(Optional.empty(), c.nextNode());

        pool.query("truncate work").execute().await();
        PgWorkQueue anyNode = new PgWorkQueue(pool);
        row("m-3", "L", "node-a");
        row("m-4", "L", "node-b");
        WorkQueue.Completion any = completeFirst(anyNode);
        assertEquals("m-4", any.next().orElseThrow().messageId(), "a queue of any node takes every body");
        assertEquals(Optional.empty(), any.nextNode());
    }

    /** node-a claims the key's first row and completes it plainly ({@code completeRow}): what it answered. */
    WorkQueue.Completion completeFirstPlainly(PgWorkQueue queue) {
        List<WorkQueue.Item> taken = queue.claim("node-a", "orders", 1, LEASE).await();
        assertEquals(1, taken.size(), "fixture: node-a holds the first row");
        return queue.completeRow(taken.get(0).seq(), taken.get(0).attempts()).await();
    }

    @Test
    void aPlainKeyedCompleteNamesTheNextRowsNodeOnlyWhenItLiesClaimableElsewhere() {
        row("m-1", "K", "node-a");
        row("m-2", "K", "node-b");
        assertEquals(new WorkQueue.Completion(true, Optional.empty(), Optional.of("node-b")), completeFirstPlainly(onA), "m-2 lies on node-b");

        pool.query("truncate work").execute().await();
        row("m-3", "L", "node-a");
        long held = row("m-4", "L", "node-b");
        pool.preparedQuery("update work set held_at = now() where seq = $1").execute(Tuple.of(held)).await();
        assertEquals(Optional.empty(), completeFirstPlainly(onA).nextNode(), "a held next row: no node");

        pool.query("truncate work").execute().await();
        row("m-5", "M", "node-a");
        long lost = row("m-6", "M", "node-b");
        pool.preparedQuery("update work set lost_at = now() where seq = $1").execute(Tuple.of(lost)).await();
        assertEquals(Optional.empty(), completeFirstPlainly(onA).nextNode(), "a lost next row: no node");

        pool.query("truncate work").execute().await();
        row("m-7", "N", "node-a");
        row("m-8", "N", "node-a");
        row("m-x", null, "node-b");
        assertEquals(Optional.empty(), completeFirstPlainly(onA).nextNode(), "the next row on node-a's own journal: no node");
    }

    @Test
    void aPlainCompleteOfAnUnkeyedOrStaleRowNamesNoNode() {
        row("m-1", null, "node-a");
        row("m-2", null, "node-b");
        List<WorkQueue.Item> taken = onA.claim("node-a", "orders", 1, LEASE).await();
        assertEquals(new WorkQueue.Completion(true, Optional.empty()), onA.completeRow(taken.get(0).seq(), taken.get(0).attempts()).await(),
                "unkeyed: completed, no node");
        assertEquals(WorkQueue.Completion.STALE, onA.completeRow(taken.get(0).seq(), taken.get(0).attempts()).await(), "completed once: stale");
    }

    /** An unkeyed plain complete stays one statement on the wire: the next-row read is for a keyed row only. */
    @Test
    void anUnkeyedPlainCompleteIsOneStatement() throws Exception {
        try (io.flottila.runtime.journal.PgWire wire = new io.flottila.runtime.journal.PgWire(pg.getHost(), pg.getMappedPort(5432))) {
            Pool relayed = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost("127.0.0.1").setPort(wire.port()).setDatabase(pg.getDatabaseName())
                    .setUser(pg.getUsername()).setPassword(pg.getPassword())).with(new PoolOptions().setMaxSize(1)).using(vertx).build();
            try {
                PgWorkQueue q = new PgWorkQueue(relayed, "node-a");
                row("m-1", null, "node-a");
                row("m-2", "K", "node-a");
                row("m-3", "K", "node-b");
                List<WorkQueue.Item> taken = q.claim("node-a", "orders", 2, LEASE).await();
                assertEquals(2, taken.size(), "fixture: m-1 and m-2 held by node-a");
                int mark = wire.mark();
                assertTrue(q.completeRow(taken.get(0).seq(), taken.get(0).attempts()).await().completed());
                long unkeyed = wire.since(mark).stream().filter(s -> s.type() == 'E').count();
                mark = wire.mark();
                assertEquals(Optional.of("node-b"), q.completeRow(taken.get(1).seq(), taken.get(1).attempts()).await().nextNode());
                long keyed = wire.since(mark).stream().filter(s -> s.type() == 'E').count();
                assertEquals(1, unkeyed, "the unkeyed complete: its delete only");
                assertEquals(2, keyed, "the keyed one: its delete, then the next row's read");
            } finally {
                relayed.close().await();
            }
        }
    }

    /**
     * Review r1 V3 and V6: a keyed plain complete whose delete committed (autocommit) and whose next-row read is then refused by the
     * server (an ERROR in place of its answer, at the relay): the completion stands — completed, nothing taken, no node named — the
     * row is gone, and the failed read is logged once at WARNING (the reconciliation covers the event it could not name).
     */
    @Test
    void aPlainKeyedCompleteWhoseNextRowReadFailsAfterTheDeleteStandsAndWarnsOnce() throws Exception {
        java.util.logging.Logger queueLog = java.util.logging.Logger.getLogger(PgWorkQueue.class.getName());   // held: JUL keeps loggers weakly
        List<java.util.logging.LogRecord> warnings = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord r) {
                if (r.getLevel() == java.util.logging.Level.WARNING) warnings.add(r);
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        queueLog.addHandler(handler);
        try (io.flottila.runtime.journal.PgWire wire = new io.flottila.runtime.journal.PgWire(pg.getHost(), pg.getMappedPort(5432))) {
            Pool relayed = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost("127.0.0.1").setPort(wire.port()).setDatabase(pg.getDatabaseName())
                    .setUser(pg.getUsername()).setPassword(pg.getPassword())).with(new PoolOptions().setMaxSize(1)).using(vertx).build();
            try {
                PgWorkQueue q = new PgWorkQueue(relayed, "node-a");
                long first = row("m-1", "K", "node-a");
                row("m-2", "K", "node-b");   // a next row on node-b: the read would name node-b
                List<WorkQueue.Item> taken = q.claim("node-a", "orders", 1, LEASE).await();
                assertEquals(1, taken.size(), "fixture: node-a holds m-1");
                io.flottila.runtime.journal.PgWire.ErrorReply refused = wire.errorReplyTo(
                        s -> s.sql() != null && s.sql().startsWith("select (select case when o.held_at"), "57014", "canceling statement due to user request");
                WorkQueue.Completion c = q.completeRow(taken.get(0).seq(), taken.get(0).attempts()).await(20, java.util.concurrent.TimeUnit.SECONDS);
                refused.awaitDone();   // witness: the next-row read was sent and refused
                assertEquals(new WorkQueue.Completion(true, Optional.empty(), Optional.empty()), c, "the committed delete stands; no node named");
                assertEquals(0L, pool.preparedQuery("select count(*) from work where seq = $1").execute(Tuple.of(first)).await().iterator().next().getLong(0),
                        "m-1's row is gone");
                assertEquals(1, warnings.size(), "the failed read logged once at WARNING: " + warnings.stream().map(java.util.logging.LogRecord::getMessage).toList());
            } finally {
                relayed.close().await();
            }
        } finally {
            queueLog.removeHandler(handler);
        }
    }

    @Test
    void aReleaseAnswersTheRowsFlowAndBodyNode() {
        long onB = row("m-1", null, "node-b");
        long preV12 = row("m-2", null, null);
        long queued = row("m-3", null, "node-b");
        pool.preparedQuery("update work set held_at = now(), locked_by = 'node-b', attempts = 2 where seq = any($1)")
                .execute(Tuple.of(new Long[] {onB, preV12})).await();

        assertEquals(Optional.of(new WorkQueue.Released(Optional.of("orders"), Optional.of("node-b"))), onA.releaseRow(onB).await());
        assertEquals(Optional.of(new WorkQueue.Released(Optional.of("orders"), Optional.empty())), onA.releaseRow(preV12).await(),
                "a pre-V12 row names no body node");
        assertEquals(Optional.empty(), onA.releaseRow(queued).await(), "a row not held is not released");
        assertEquals(Optional.empty(), onA.releaseRow(onB).await(), "nor one released already");
        assertFalse(onA.release(queued).await(), "release answers the same as a boolean");
    }

    /** A kept ref of {@code id}'s step-0 body in {@code node}'s journal, with its segment row. */
    void keptOn(String id, String node) {
        pool.preparedQuery("insert into journal_segments(node, segment, copies) values ($1, 7, '{}') on conflict do nothing").execute(Tuple.of(node)).await();
        pool.preparedQuery("insert into body_refs(message_id, step, part, node, segment, position, length) values ($1, 0, 'body', $2, 7, 0, 10)")
                .execute(Tuple.of(id, node)).await();
    }

    @Test
    void anEnqueueCopyingARefNamesTheRefsNodeOnlyWhenItIsAnothers() {
        keptOn("src-b", "node-b");
        keptOn("src-a", "node-a");
        PgWorkQueue.Copied far = onA.enqueue(pool, "m-far", "orders", 1, null, BlobKey.body("src-b", 0), "node-a", LEASE).await();
        assertEquals(new PgWorkQueue.Copied(Optional.empty(), Optional.of("node-b")), far, "a ref of node-b: a plain row, node-b named");
        PgWorkQueue.Copied near = onA.enqueue(pool, "m-near", "orders", 1, null, BlobKey.body("src-a", 0), "node-a", LEASE).await();
        assertTrue(near.leased().isPresent(), "a ref of its own node: written leased");
        assertEquals(Optional.empty(), near.elsewhere(), "and no node named");
        PgWorkQueue.Copied plainNear = onA.enqueue(pool, "m-plain", "orders", 1, null, BlobKey.body("src-a", 0), null, null).await();
        assertEquals(new PgWorkQueue.Copied(Optional.empty(), Optional.empty()), plainNear, "a plain row of its own node's ref names no node");
        PgWorkQueue.Copied anyNode = new PgWorkQueue(pool).enqueue(pool, "m-any", "orders", 1, null, BlobKey.body("src-a", 0), null, null).await();
        assertEquals(Optional.of("node-a"), anyNode.elsewhere(), "a queue of no node names the ref's node, whichever it is");
    }
}
