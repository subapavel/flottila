package io.flottila.runtime.queue;

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
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The proto-m1 WorkQueue claim SQL on the Vert.x 5 client: SKIP LOCKED, per-key order, lease expiry, the same-key fast path, two real workers. */
class PgWorkQueueIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static Vertx vertx;
    static Pool pool;
    static PgWorkQueue queue;
    static final Duration LEASE = Duration.ofSeconds(30);

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        pool = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()).setCachePreparedStatements(true))
                .with(new PoolOptions().setMaxSize(8)).using(vertx).build();
        io.flottila.runtime.db.Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());   // V1 brings the work table
        queue = new PgWorkQueue(pool);
    }

    @AfterAll
    static void stop() { pool.close().await(); vertx.close().await(); pg.stop(); }

    @BeforeEach
    void clean() { pool.query("truncate work").execute().await(); }

    private void enqueue(String id, String key) { queue.enqueue(id, "t", 1, key).await(); }
    private List<WorkQueue.Item> claim(String worker, int max, Duration lease) { return queue.claim(worker, "t", max, lease).await(); }
    private static Set<String> ids(List<WorkQueue.Item> items) { return items.stream().map(WorkQueue.Item::messageId).collect(Collectors.toSet()); }

    @Test
    void partitionOrderingAcrossTwoWorkers() {
        enqueue("a1", "A"); enqueue("a2", "A"); enqueue("b1", "B"); enqueue("b2", "B"); enqueue("n1", null);
        List<WorkQueue.Item> w1 = claim("w1", 10, LEASE);
        assertEquals(Set.of("a1", "b1", "n1"), ids(w1), "only the oldest row per key plus the unordered null-key row");
        assertTrue(claim("w2", 10, LEASE).isEmpty(), "keys A and B are locked");
        long a1 = w1.stream().filter(i -> i.messageId().equals("a1")).findFirst().orElseThrow().seq();
        queue.complete(a1).await();
        assertEquals(List.of("a2"), claim("w2", 10, LEASE).stream().map(WorkQueue.Item::messageId).toList());
        assertTrue(claim("w1", 10, LEASE).isEmpty(), "b2 still behind b1");
    }

    @Test
    void leaseExpiryRedelivers() {
        enqueue("x1", "K");
        List<WorkQueue.Item> first = claim("w1", 1, Duration.ofSeconds(60));
        assertEquals(1, first.size());
        assertTrue(claim("w2", 1, Duration.ofSeconds(60)).isEmpty(), "lease still active");
        // the lease expires by the database clock; move it into the past instead of sleeping
        pool.preparedQuery("update work set locked_until = now() - interval '1 second' where seq = $1").execute(Tuple.of(first.get(0).seq())).await();
        List<WorkQueue.Item> second = claim("w2", 1, LEASE);
        assertEquals("x1", second.get(0).messageId(), "expired lease must re-deliver");
        assertEquals(first.get(0).seq(), second.get(0).seq());
    }

    @Test
    void completeRemovesTheRow() {
        enqueue("c1", null);
        List<WorkQueue.Item> got = claim("w1", 1, LEASE);
        queue.complete(got.get(0).seq()).await();
        int left = pool.preparedQuery("select count(*)::int as n from work where id = $1").execute(Tuple.of("c1")).await().iterator().next().getInteger("n");
        assertEquals(0, left);
    }

    @Test
    void sameKeyFastPathSkipsOlderFreeRowsOfOtherKeys() {
        // n0 is older than a2, free and claimable by anyone: a key-less "take the next eligible row" would return it
        enqueue("a1", "A"); enqueue("n0", null); enqueue("a2", "A"); enqueue("a3", "A"); enqueue("b1", "B");
        WorkQueue.Item a1 = claim("w1", 1, LEASE).get(0);
        assertEquals("a1", a1.messageId());
        Optional<WorkQueue.Item> a2 = queue.completeAndTakeNext(a1.seq(), "w1", LEASE).await();
        assertEquals("a2", a2.orElseThrow().messageId(), "the fast path stays on key A and skips the older free row n0");
        String holder = pool.preparedQuery("select locked_by from work where seq = $1").execute(Tuple.of(a2.get().seq())).await().iterator().next().getString("locked_by");
        assertEquals("w1", holder, "the fast path leases the next row to the finishing worker");
        assertEquals(null, pool.query("select locked_by from work where id = 'n0'").execute().await().iterator().next().getString("locked_by"), "n0 was not touched");
        assertEquals(Set.of("n0", "b1"), ids(claim("w2", 10, LEASE)), "key A stays with w1; everything else is w2's");
        assertEquals("a3", queue.completeAndTakeNext(a2.get().seq(), "w1", LEASE).await().orElseThrow().messageId());
        long a3 = pool.query("select seq from work where id = 'a3'").execute().await().iterator().next().getLong("seq");
        assertTrue(queue.completeAndTakeNext(a3, "w1", LEASE).await().isEmpty(), "key A drained");
        assertTrue(queue.completeAndTakeNext(-1, "w1", LEASE).await().isEmpty(), "completing an unknown seq is a no-op");
        assertTrue(queue.claim("w1", "t", 1, Duration.ofSeconds(-1)).failed(), "a negative lease fails the future, it does not throw");
        assertTrue(queue.completeAndTakeNext(a1.seq(), "w1", Duration.ofSeconds(-1)).failed());
    }

    @Test
    void fastPathOnANullKeyRowJustCompletes() {
        enqueue("n1", null); enqueue("n2", null);
        WorkQueue.Item n1 = claim("w1", 1, LEASE).get(0);
        assertTrue(queue.completeAndTakeNext(n1.seq(), "w1", LEASE).await().isEmpty(), "no key, no ordering, no fast path");
        assertEquals(1, pool.query("select count(*) from work").execute().await().iterator().next().getLong(0));
    }

    // --- P1-08b (D-055): attempts as the fencing token, renew, hold (V4__work_attempts)

    private void expire(long seq) {
        pool.preparedQuery("update work set locked_until = now() - interval '1 second' where seq = $1").execute(Tuple.of(seq)).await();
    }

    private io.vertx.sqlclient.Row row(long seq) {
        return pool.preparedQuery("select locked_by, locked_until, attempts, held_at from work where seq = $1").execute(Tuple.of(seq)).await().iterator().next();
    }

    @Test
    void claimAndFastPathCountAttempts() {
        enqueue("x1", "K"); enqueue("x2", "K");
        WorkQueue.Item first = claim("w1", 10, LEASE).get(0);
        assertEquals("x1", first.messageId());
        assertEquals(1, first.attempts(), "a claim counts itself");
        assertEquals(1, row(first.seq()).getInteger("attempts"));
        expire(first.seq());
        WorkQueue.Item again = claim("w2", 10, LEASE).get(0);
        assertEquals(first.seq(), again.seq());
        assertEquals(2, again.attempts(), "a re-claim after the lease ran out is the second attempt");
        WorkQueue.Item next = queue.completeAndTakeNext(again.seq(), "w2", LEASE).await().orElseThrow();
        assertEquals("x2", next.messageId());
        assertEquals(1, next.attempts(), "the fast path's claim counts as well");
        assertEquals(1, row(next.seq()).getInteger("attempts"));
    }

    @Test
    void renewIsFencedByAttempts() {
        enqueue("r1", null);
        WorkQueue.Item held = claim("w1", 1, Duration.ofSeconds(60)).get(0);
        Object until = row(held.seq()).getValue("locked_until");
        assertFalse(queue.renew(held.seq(), 2, Duration.ofSeconds(600)).await(), "attempts that are not the row's: refused");
        assertFalse(queue.renew(held.seq(), 0, Duration.ofSeconds(600)).await());
        assertEquals(until, row(held.seq()).getValue("locked_until"), "and the lease unchanged");
        assertTrue(queue.renew(held.seq(), held.attempts(), Duration.ofSeconds(600)).await(), "the current holder renews");
        java.time.OffsetDateTime renewed = row(held.seq()).getOffsetDateTime("locked_until");
        assertTrue(renewed.isAfter(((java.time.OffsetDateTime) until).plusSeconds(300)), "extended by the renewal's lease of 600 s: " + until + " -> " + renewed);

        expire(held.seq());
        assertTrue(queue.renew(held.seq(), held.attempts(), LEASE).await(), "an expired lease nobody re-claimed is revived");
        assertTrue(claim("w2", 1, LEASE).isEmpty(), "revived: not claimable");
        assertEquals("w1", row(held.seq()).getString("locked_by"));

        expire(held.seq());
        WorkQueue.Item taken = claim("w2", 1, LEASE).get(0);
        assertEquals(2, taken.attempts());
        Object takenUntil = row(taken.seq()).getValue("locked_until");
        assertFalse(queue.renew(held.seq(), held.attempts(), Duration.ofSeconds(600)).await(), "the old holder is fenced off by the new claim");
        assertEquals(takenUntil, row(taken.seq()).getValue("locked_until"));
        assertEquals("w2", row(taken.seq()).getString("locked_by"));

        assertTrue(queue.hold(taken.seq(), taken.attempts()).await());
        assertFalse(queue.renew(taken.seq(), taken.attempts(), LEASE).await(), "a held row is not renewed");
        queue.complete(taken.seq()).await();
        assertFalse(queue.renew(taken.seq(), taken.attempts(), LEASE).await(), "a completed row is not renewed");
    }

    @Test
    void aHeldRowIsNeverClaimedAndBlocksItsKey() {
        enqueue("a1", "A"); enqueue("a2", "A"); enqueue("n1", null);
        WorkQueue.Item a1 = claim("w1", 1, LEASE).get(0);
        assertEquals("a1", a1.messageId());
        assertFalse(queue.hold(a1.seq(), 0).await(), "fenced: attempts that are not the row's hold nothing");
        assertNull(row(a1.seq()).getValue("held_at"));
        expire(a1.seq());
        WorkQueue.Item again = claim("w2", 1, LEASE).get(0);
        assertEquals(a1.seq(), again.seq(), "the refused hold left the row claimable");
        assertFalse(queue.hold(a1.seq(), a1.attempts()).await(), "the old holder cannot hold what was claimed again");
        assertTrue(queue.hold(again.seq(), again.attempts()).await(), "the current holder holds it");
        assertNotNull(row(again.seq()).getValue("held_at"));

        expire(again.seq());
        List<WorkQueue.Item> rest = claim("w3", 10, LEASE);
        assertEquals(List.of("n1"), rest.stream().map(WorkQueue.Item::messageId).toList(),
                "a1 is held and a2 waits behind it; the null-key row is claimable");
        queue.complete(rest.get(0).seq()).await();
        pool.query("update work set locked_until = now() - interval '1 second'").execute().await();
        assertTrue(claim("w3", 10, LEASE).isEmpty(), "never claimed again, its lease long gone");
        assertEquals(2L, pool.query("select count(*) from work").execute().await().iterator().next().getLong(0), "the held row and the row behind it stay");
        assertFalse(queue.hold(rest.get(0).seq(), rest.get(0).attempts()).await(), "a completed row cannot be held");
    }

    @Test
    void twoConcurrentWorkersKeepPerKeyOrderAndNeverOverlap() throws Exception {
        List<String> keys = List.of("K1", "K2", "K3", "K4", "K5");
        int perKey = 40;
        for (int i = 0; i < perKey; i++) for (String k : keys) enqueue(k + "-" + i, k);
        Map<String, List<Integer>> processed = new ConcurrentHashMap<>();
        Map<String, String> active = new ConcurrentHashMap<>();          // key → worker currently holding it
        List<String> overlaps = new java.util.concurrent.CopyOnWriteArrayList<>();
        Runnable worker = () -> {
            String me = Thread.currentThread().getName();
            while (true) {
                List<WorkQueue.Item> got = queue.claim(me, "t", 3, LEASE).await();
                if (got.isEmpty()) {
                    if (pool.query("select count(*) from work").execute().await().iterator().next().getLong(0) == 0) return;
                    continue;
                }
                for (WorkQueue.Item it : got) {
                    Optional<WorkQueue.Item> cur = Optional.of(it);
                    while (cur.isPresent()) {
                        WorkQueue.Item c = cur.get();
                        String prev = active.putIfAbsent(c.partitionKey(), me);
                        if (prev != null) overlaps.add(c.partitionKey() + " held by " + prev + " and " + me);
                        processed.computeIfAbsent(c.partitionKey(), k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                                .add(Integer.parseInt(c.messageId().substring(c.messageId().indexOf('-') + 1)));
                        active.remove(c.partitionKey());
                        cur = queue.completeAndTakeNext(c.seq(), me, LEASE).await();
                    }
                }
            }
        };
        try (var pool2 = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("worker-", 1).factory())) {
            Future<?> f1 = pool2.submit(worker);
            Future<?> f2 = pool2.submit(worker);
            f1.get(120, TimeUnit.SECONDS);
            f2.get(120, TimeUnit.SECONDS);
        }
        assertEquals(List.of(), overlaps, "no key is ever held by two workers");
        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < perKey; i++) expected.add(i);
        for (String k : keys) assertEquals(expected, processed.get(k), "key " + k + " processed in enqueue order, exactly once");
    }

    /**
     * P1-09d2a (D-063): {@code hasItems(flow)} is true while any row of the flow exists — queued, leased, left to a lease that ran
     * out (backing off until the next claim) or held — and false once only completed rows were the flow's; another flow's rows,
     * one whose id starts with this flow's included, never count. Each true is asserted with only that kind of row present.
     */
    @Test
    void hasItemsSeesQueuedLeasedHeldNotCompletedNorOtherFlows() {
        String flow = "shop/audit";
        String other = "shop/audit-eu";
        assertFalse(queue.hasItems(flow).await(), "an empty queue");
        queue.enqueue("o1", other, 1, null).await();
        assertFalse(queue.hasItems(flow).await(), "only another flow's row, whose id starts with this flow's");
        assertTrue(queue.hasItems(other).await(), "witness: that flow sees its own row");

        queue.enqueue("q1", flow, 1, "K").await();
        assertTrue(queue.hasItems(flow).await(), "a queued row, due");
        WorkQueue.Item first = queue.claim("w1", flow, 1, LEASE).await().get(0);
        assertEquals("q1", first.messageId(), "fixture");
        assertTrue(queue.hasItems(flow).await(), "a leased row");
        expire(first.seq());
        assertTrue(queue.hasItems(flow).await(), "a row left to its lease that ran out (backing off)");
        WorkQueue.Item again = queue.claim("w2", flow, 1, LEASE).await().get(0);
        assertEquals(first.seq(), again.seq(), "fixture: the expired lease handed the row out again");
        assertTrue(queue.hold(again.seq(), again.attempts()).await(), "fixture: held");
        expire(again.seq());
        assertTrue(queue.claim("w3", flow, 10, LEASE).await().isEmpty(), "fixture: a held row is never claimed again");
        assertTrue(queue.hasItems(flow).await(), "a held row");

        queue.complete(again.seq()).await();
        assertFalse(queue.hasItems(flow).await(), "only completed rows: no items");
        queue.enqueue("q2", flow, 1, null).await();
        queue.enqueue("q3", flow, 1, "K").await();
        WorkQueue.Item q2 = queue.claim("w1", flow, 1, LEASE).await().get(0);
        queue.completeAndTakeNext(q2.seq(), "w1", LEASE).await();
        assertTrue(queue.hasItems(flow).await(), "one row completed, one still queued");
        for (WorkQueue.Item left : queue.claim("w1", flow, 10, LEASE).await()) queue.complete(left.seq()).await();
        assertFalse(queue.hasItems(flow).await(), "every row completed");
        assertTrue(queue.hasItems(other).await(), "the other flow's row stays its own");
    }

    /** P1-09d2a review (D-063): both implementations refuse a null flow at the call, as the interface says. */
    @Test
    void hasItemsOfANullFlowThrows() {
        queue.enqueue("n1", "shop/audit", 1, null).await();
        assertThrows(NullPointerException.class, () -> queue.hasItems(null), "a null flow is a NullPointerException at the call, never false");
        assertTrue(queue.hasItems("shop/audit").await(), "witness: the queue answers for a flow");
    }
}
