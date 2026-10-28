package io.flottila.runtime.queue;

import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.Page;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The proto-m1 WorkQueue claim SQL on the Vert.x 5 client: SKIP LOCKED, per-key order, lease expiry, the same-key fast path, two real workers.
 * P1-09c1 (D-067): the held rows a page at a time (V5's {@code work_held}), and release — the same cases as InMemoryWorkQueueTest.
 */
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
        WorkQueue.Item a1 = w1.stream().filter(i -> i.messageId().equals("a1")).findFirst().orElseThrow();
        assertTrue(queue.complete(a1.seq(), a1.attempts()).await(), "completed by its holder");
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
        assertTrue(queue.complete(got.get(0).seq(), got.get(0).attempts()).await(), "true: removed");
        int left = pool.preparedQuery("select count(*)::int as n from work where id = $1").execute(Tuple.of("c1")).await().iterator().next().getInteger("n");
        assertEquals(0, left);
    }

    @Test
    void sameKeyFastPathSkipsOlderFreeRowsOfOtherKeys() {
        // n0 is older than a2, free and claimable by anyone: a key-less "take the next eligible row" would return it
        enqueue("a1", "A"); enqueue("n0", null); enqueue("a2", "A"); enqueue("a3", "A"); enqueue("b1", "B");
        WorkQueue.Item a1 = claim("w1", 1, LEASE).get(0);
        assertEquals("a1", a1.messageId());
        WorkQueue.Completion done = queue.completeAndTakeNext(a1.seq(), a1.attempts(), "w1", LEASE).await();
        assertTrue(done.completed(), "a1 completed by its holder");
        Optional<WorkQueue.Item> a2 = done.next();
        assertEquals("a2", a2.orElseThrow().messageId(), "the fast path stays on key A and skips the older free row n0");
        String holder = pool.preparedQuery("select locked_by from work where seq = $1").execute(Tuple.of(a2.get().seq())).await().iterator().next().getString("locked_by");
        assertEquals("w1", holder, "the fast path leases the next row to the finishing worker");
        assertEquals(null, pool.query("select locked_by from work where id = 'n0'").execute().await().iterator().next().getString("locked_by"), "n0 was not touched");
        assertEquals(Set.of("n0", "b1"), ids(claim("w2", 10, LEASE)), "key A stays with w1; everything else is w2's");
        WorkQueue.Item a3 = queue.completeAndTakeNext(a2.get().seq(), a2.get().attempts(), "w1", LEASE).await().next().orElseThrow();
        assertEquals("a3", a3.messageId());
        assertEquals(WorkQueue.Completion.done(Optional.empty()), queue.completeAndTakeNext(a3.seq(), a3.attempts(), "w1", LEASE).await(), "a3 completed; key A drained");
        assertEquals(WorkQueue.Completion.STALE, queue.completeAndTakeNext(-1, 1, "w1", LEASE).await(), "completing an unknown seq is a no-op");
        assertTrue(queue.claim("w1", "t", 1, Duration.ofSeconds(-1)).failed(), "a negative lease fails the future, it does not throw");
        assertTrue(queue.completeAndTakeNext(a1.seq(), a1.attempts(), "w1", Duration.ofSeconds(-1)).failed());
    }

    @Test
    void fastPathOnANullKeyRowJustCompletes() {
        enqueue("n1", null); enqueue("n2", null);
        WorkQueue.Item n1 = claim("w1", 1, LEASE).get(0);
        assertEquals(WorkQueue.Completion.done(Optional.empty()), queue.completeAndTakeNext(n1.seq(), n1.attempts(), "w1", LEASE).await(),
                "completed; no key, no ordering, no fast path");
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
        WorkQueue.Item next = queue.completeAndTakeNext(again.seq(), again.attempts(), "w2", LEASE).await().next().orElseThrow();
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
        assertTrue(queue.release(taken.seq()).await(), "D-074: a held row leaves only through release, claim, complete");
        WorkQueue.Item last = claim("w3", 1, LEASE).get(0);
        assertEquals(3, last.attempts(), "fixture");
        assertTrue(queue.complete(last.seq(), last.attempts()).await(), "fixture: completed by its holder");
        assertFalse(queue.renew(last.seq(), last.attempts(), LEASE).await(), "a completed row is not renewed");
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
        assertTrue(queue.complete(rest.get(0).seq(), rest.get(0).attempts()).await(), "fixture");
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
                        WorkQueue.Completion done = queue.completeAndTakeNext(c.seq(), c.attempts(), me, LEASE).await();
                        if (!done.completed()) overlaps.add(c + " not completed by its holder " + me);
                        cur = done.next();
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

        assertFalse(queue.complete(again.seq(), again.attempts()).await(), "D-074: a held row is not completed, not even by its holder");
        assertTrue(queue.hasItems(flow).await(), "still a held row");
        assertTrue(queue.release(again.seq()).await(), "fixture: a held row leaves through release, claim, complete");
        WorkQueue.Item last = queue.claim("w3", flow, 1, LEASE).await().get(0);
        assertTrue(queue.complete(last.seq(), last.attempts()).await(), "fixture");
        assertFalse(queue.hasItems(flow).await(), "only completed rows: no items");
        queue.enqueue("q2", flow, 1, null).await();
        queue.enqueue("q3", flow, 1, "K").await();
        WorkQueue.Item q2 = queue.claim("w1", flow, 1, LEASE).await().get(0);
        assertTrue(queue.completeAndTakeNext(q2.seq(), q2.attempts(), "w1", LEASE).await().completed(), "fixture");
        assertTrue(queue.hasItems(flow).await(), "one row completed, one still queued");
        for (WorkQueue.Item left : queue.claim("w1", flow, 10, LEASE).await()) assertTrue(queue.complete(left.seq(), left.attempts()).await(), "fixture");
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

    // --- P1-09c1 (D-067): held(after, limit) a page at a time in seq order, release(seq)

    /** Refused with IllegalArgumentException (the admin API's 400), thrown at the call or as the failed future's cause. */
    static void refused(org.junit.jupiter.api.function.Executable call, String what) {
        Throwable t = assertThrows(Throwable.class, call, what + " is refused");
        assertInstanceOf(IllegalArgumentException.class, t, what + ": " + t);
    }

    static List<String> heldIds(List<WorkQueue.Held> held) {
        return held.stream().map(h -> h.item().messageId()).toList();
    }

    static WorkQueue.Item one(List<WorkQueue.Item> claimed, String id) {
        return claimed.stream().filter(i -> i.messageId().equals(id)).findFirst().orElseThrow(() -> new AssertionError(id + " not in " + claimed));
    }

    /** The row's {@code held_at} as the database stored it. */
    private Instant heldAt(long seq) {
        return row(seq).getOffsetDateTime("held_at").toInstant();
    }

    /**
     * Rows held in two flows, a queued row behind a held one and a leased row between them: the listing holds only the held rows,
     * across flows, in seq order, each as last handed out with its {@code held_at}; pages of 2 walk them, the last page has no cursor
     * (an exact fit included), and the cursor is a position: releasing the rows up to the cursor skips nothing after it.
     */
    @Test
    void heldListsOnlyHeldRowsInSeqOrderAPageAtATime() {
        queue.enqueue("h1", "t", 1, "A").await();
        queue.enqueue("q1", "t", 1, "A").await();
        queue.enqueue("h2", "t", 2, null).await();
        queue.enqueue("l1", "t", 1, null).await();
        queue.enqueue("h3", "u", 3, null).await();
        queue.enqueue("h4", "t", 1, "B").await();
        assertEquals(new Page<WorkQueue.Held>(List.of(), Optional.empty()), queue.held(Optional.empty(), 10).await(), "nothing held yet: an empty last page");

        List<WorkQueue.Item> t = claim("w1", 10, LEASE);
        assertEquals(List.of("h1", "h2", "l1", "h4"), t.stream().map(WorkQueue.Item::messageId).toList(), "fixture: q1 waits behind h1");
        WorkQueue.Item u1 = queue.claim("w2", "u", 1, LEASE).await().get(0);
        expire(u1.seq());
        WorkQueue.Item u2 = queue.claim("w2", "u", 1, LEASE).await().get(0);
        assertEquals(u1.seq(), u2.seq(), "fixture");
        assertEquals(2, u2.attempts(), "fixture");
        for (WorkQueue.Item it : List.of(one(t, "h4"), u2, one(t, "h1"), one(t, "h2"))) {   // held out of seq order
            assertTrue(queue.hold(it.seq(), it.attempts()).await(), "fixture: " + it.messageId() + " held");
        }

        List<WorkQueue.Held> expected = new ArrayList<>();
        for (WorkQueue.Item it : List.of(one(t, "h1"), one(t, "h2"), u2, one(t, "h4"))) expected.add(new WorkQueue.Held(it, heldAt(it.seq())));
        Page<WorkQueue.Held> all = queue.held(Optional.empty(), Page.MAX_LIMIT).await();
        assertEquals(expected, all.items(), "only held rows, every flow's, in seq order, as last handed out (h3 at attempt 2), with held_at");
        assertEquals(Optional.empty(), all.next());

        Page<WorkQueue.Held> first = queue.held(Optional.empty(), 2).await();
        assertEquals(List.of("h1", "h2"), heldIds(first.items()));
        assertTrue(first.next().isPresent(), "two more held rows");
        Page<WorkQueue.Held> second = queue.held(first.next(), 2).await();
        assertEquals(List.of("h3", "h4"), heldIds(second.items()));
        assertEquals(Optional.empty(), second.next(), "exactly limit rows left: no cursor to an empty page");
        Page<WorkQueue.Held> ofThree = queue.held(Optional.empty(), 3).await();
        assertEquals(List.of("h1", "h2", "h3"), heldIds(ofThree.items()));
        Page<WorkQueue.Held> rest = queue.held(ofThree.next(), 3).await();
        assertEquals(List.of("h4"), heldIds(rest.items()));
        assertEquals(Optional.empty(), rest.next());

        assertTrue(queue.release(one(t, "h1").seq()).await(), "fixture");
        assertTrue(queue.release(one(t, "h2").seq()).await(), "fixture: the cursor's own row released");
        assertEquals(List.of("h3", "h4"), heldIds(queue.held(first.next(), 2).await().items()),
                "the cursor is a position in seq order, never an offset: the rows released before it skip nothing");
    }

    @Test
    void heldRefusesABadLimitOrCursor() {
        enqueue("h1", null); enqueue("h2", null);
        for (WorkQueue.Item it : claim("w1", 10, LEASE)) assertTrue(queue.hold(it.seq(), it.attempts()).await(), "fixture");
        for (int limit : new int[] {0, -1, Page.MAX_LIMIT + 1, Integer.MIN_VALUE}) refused(() -> queue.held(Optional.empty(), limit).await(), "limit " + limit);
        String notASeq = Base64.getUrlEncoder().withoutPadding().encodeToString("x".getBytes(StandardCharsets.UTF_8));
        for (String cursor : List.of("!!!", notASeq)) refused(() -> queue.held(Optional.of(cursor), 1).await(), "cursor " + cursor);
        assertEquals(List.of("h1"), heldIds(queue.held(Optional.empty(), 1).await().items()), "1 is a limit");
        assertEquals(List.of("h1", "h2"), heldIds(queue.held(Optional.empty(), Page.MAX_LIMIT).await().items()), "500 is a limit");
        Optional<String> next = queue.held(Optional.empty(), 1).await().next();
        assertEquals(List.of("h2"), heldIds(queue.held(next, 1).await().items()), "the cursor the store handed out is one it takes");
    }

    /**
     * Handed out twice, held by the second claim whose lease still runs: release answers true and clears held_at, locked_by and
     * locked_until; attempts stay 2 — the row is claimable at once, before the row behind it, and its next hand-out is the third.
     */
    @Test
    void releaseClearsHoldAndLeaseKeepsAttempts() {
        enqueue("r1", "K"); enqueue("r2", "K");
        WorkQueue.Item first = claim("w1", 10, LEASE).get(0);
        expire(first.seq());
        WorkQueue.Item again = claim("w2", 10, LEASE).get(0);
        assertEquals(first.seq(), again.seq(), "fixture");
        assertEquals(2, again.attempts(), "fixture");
        assertTrue(queue.hold(again.seq(), again.attempts()).await(), "fixture: held by its current holder");
        assertEquals("w2", row(again.seq()).getString("locked_by"), "fixture: the hold left w2's lease");
        assertNotNull(row(again.seq()).getValue("locked_until"), "fixture");

        assertTrue(queue.release(again.seq()).await(), "a held row is released");
        io.vertx.sqlclient.Row r = row(again.seq());
        assertNull(r.getValue("held_at"), "the hold cleared");
        assertNull(r.getValue("locked_by"), "the lease cleared: no holder");
        assertNull(r.getValue("locked_until"), "the lease cleared: no expiry");
        assertEquals(2, r.getInteger("attempts"), "attempts kept: the row still counts its two hand-outs");
        assertEquals(List.of(), queue.held(Optional.empty(), Page.MAX_LIMIT).await().items(), "no longer listed");
        assertEquals(List.of(new WorkQueue.Item(again.seq(), "r1", "t", 1, "K", 3)), claim("w3", 10, LEASE),
                "claimable at once, before r2, and its next hand-out is the third attempt");
    }

    @Test
    void releaseOfAnUnheldOrUnknownSeqIsFalse() {
        enqueue("l1", null); enqueue("q1", "K"); enqueue("h1", null);
        WorkQueue.Item l1 = claim("w1", 1, LEASE).get(0);
        assertEquals("l1", l1.messageId(), "fixture: leased, never held");
        long q1 = pool.query("select seq from work where id = 'q1'").execute().await().iterator().next().getLong("seq");
        Object leased = row(l1.seq()).getValue("locked_until");
        assertFalse(queue.release(-1L).await(), "an unknown seq");
        assertFalse(queue.release(q1).await(), "a queued row never handed out");
        assertFalse(queue.release(l1.seq()).await(), "a leased row that is not held");
        assertEquals("w1", row(l1.seq()).getString("locked_by"), "the refused release left the lease where it was");
        assertEquals(leased, row(l1.seq()).getValue("locked_until"));
        assertEquals(1, row(l1.seq()).getInteger("attempts"));
        List<WorkQueue.Item> rest = claim("w2", 10, LEASE);
        assertEquals(List.of("q1", "h1"), rest.stream().map(WorkQueue.Item::messageId).toList(), "the refused release changed nothing: q1 claimable as before");
        assertEquals(1, one(rest, "q1").attempts(), "q1's first hand-out");
        WorkQueue.Item h1 = one(rest, "h1");
        assertTrue(queue.hold(h1.seq(), h1.attempts()).await(), "fixture");
        assertTrue(queue.release(h1.seq()).await(), "witness: a held row is released");
        assertFalse(queue.release(h1.seq()).await(), "released already: no longer held");
        assertTrue(queue.complete(h1.seq(), h1.attempts()).await(),
                "D-074: a released row keeps its attempts, so its last holder's token stays current until the next claim");
        assertFalse(queue.release(h1.seq()).await(), "completed: unknown");
    }

    /** a1 held blocks a2 behind it; released, a1 is the key's next hand-out, and a2 follows only once a1 completed. */
    @Test
    void aReleasedRowIsClaimedBeforeTheRowsBehindItsKey() {
        enqueue("a1", "A"); enqueue("a2", "A"); enqueue("b1", "B");
        List<WorkQueue.Item> first = claim("w1", 10, LEASE);
        assertEquals(List.of("a1", "b1"), first.stream().map(WorkQueue.Item::messageId).toList(), "fixture");
        WorkQueue.Item a1 = one(first, "a1");
        assertTrue(queue.hold(a1.seq(), a1.attempts()).await(), "fixture");
        assertTrue(queue.complete(one(first, "b1").seq(), one(first, "b1").attempts()).await(), "fixture");
        expire(a1.seq());
        assertTrue(claim("w2", 10, LEASE).isEmpty(), "held: a1 is never claimed and a2 waits behind it");

        assertTrue(queue.release(a1.seq()).await());
        assertEquals(List.of(new WorkQueue.Item(a1.seq(), "a1", "t", 1, "A", 2)), claim("w2", 10, LEASE), "the released row comes first; a2 stays behind it");
        assertTrue(claim("w3", 10, LEASE).isEmpty(), "a1 leased again: key A blocked by its live lease");
        WorkQueue.Item a2 = queue.completeAndTakeNext(a1.seq(), 2, "w2", LEASE).await().next().orElseThrow();
        assertEquals("a2", a2.messageId(), "a2 once a1 completed");
        assertEquals(1, a2.attempts());
    }

    // --- Q-1 (D-074): complete and the fast path are fenced by the attempts token, as renew and hold are — the same cases as InMemoryWorkQueueTest

    /** Every row of the table in seq order, lease, attempts and hold included: what a refused call must leave as it was. */
    private List<String> state() {
        List<String> rows = new ArrayList<>();
        for (io.vertx.sqlclient.Row r : pool.query("select seq, id, partition_key, attempts, locked_by, locked_until, held_at from work order by seq").execute().await()) {
            rows.add(r.getLong("seq") + " " + r.getString("id") + " key " + r.getString("partition_key") + " attempts " + r.getInteger("attempts")
                    + " locked by " + r.getString("locked_by") + " until " + r.getValue("locked_until") + " held " + r.getValue("held_at"));
        }
        return rows;
    }

    private List<String> ids() {
        List<String> ids = new ArrayList<>();
        for (io.vertx.sqlclient.Row r : pool.query("select id from work order by seq").execute().await()) ids.add(r.getString("id"));
        return ids;
    }

    @Test
    void completeIsFencedByAttempts() {
        enqueue("x1", "K"); enqueue("x2", "K");
        WorkQueue.Item mine = claim("w1", 10, LEASE).get(0);
        expire(mine.seq());
        WorkQueue.Item theirs = claim("w2", 10, LEASE).get(0);
        assertEquals(mine.seq(), theirs.seq(), "fixture: claimed again");
        assertEquals(2, theirs.attempts(), "fixture");
        List<String> before = state();

        assertFalse(queue.complete(mine.seq(), mine.attempts()).await(), "the old holder's complete is stale: false");
        assertEquals(before, state(), "and deletes nothing: x1 stays w2's, at attempt 2, x2 behind it");
        assertTrue(claim("w3", 10, LEASE).isEmpty(), "x1 still leased to w2, x2 still behind it");
        assertFalse(queue.complete(theirs.seq(), theirs.attempts() + 1).await(), "attempts ahead of the row's: false");
        assertEquals(before, state());

        assertTrue(queue.complete(theirs.seq(), theirs.attempts()).await(), "the current holder's complete deletes the row: true");
        assertEquals(List.of("x2"), ids());
        assertFalse(queue.complete(theirs.seq(), theirs.attempts()).await(), "completed already: false");
        assertFalse(queue.complete(-1L, 1).await(), "an unknown seq: false");
        assertEquals(List.of("x2"), claim("w3", 10, LEASE).stream().map(WorkQueue.Item::messageId).toList(), "x2 once x1 is gone");
    }

    /** The guarded delete matches nothing, so the next-of-key claim never runs: x2 is not taken, nothing changes. */
    @Test
    void aStaleFastPathCompletesNothingAndTakesNothing() {
        enqueue("x1", "K"); enqueue("x2", "K");
        WorkQueue.Item mine = claim("w1", 10, LEASE).get(0);
        expire(mine.seq());
        WorkQueue.Item theirs = claim("w2", 10, LEASE).get(0);
        assertEquals(mine.seq(), theirs.seq(), "fixture: claimed again");
        List<String> before = state();

        assertEquals(WorkQueue.Completion.STALE, queue.completeAndTakeNext(mine.seq(), mine.attempts(), "w1", LEASE).await(),
                "the old holder's fast path is stale");
        assertEquals(before, state(), "x1 not completed, x2 not taken: still at attempt 0, leased to nobody");

        WorkQueue.Completion done = queue.completeAndTakeNext(theirs.seq(), theirs.attempts(), "w2", LEASE).await();
        long x2 = pool.query("select seq from work where id = 'x2'").execute().await().iterator().next().getLong("seq");
        assertEquals(WorkQueue.Completion.done(Optional.of(new WorkQueue.Item(x2, "x2", "t", 1, "K", 1))), done,
                "the current holder's fast path completes x1 and takes x2");
        assertEquals("w2", row(x2).getString("locked_by"));
        List<String> after = state();
        assertEquals(WorkQueue.Completion.STALE, queue.completeAndTakeNext(theirs.seq(), theirs.attempts(), "w2", LEASE).await(),
                "completed already: stale");
        assertEquals(after, state());
    }

    @Test
    void holdIsFencedByAttempts() {
        enqueue("x1", "K"); enqueue("x2", "K");
        WorkQueue.Item mine = claim("w1", 10, LEASE).get(0);
        expire(mine.seq());
        WorkQueue.Item theirs = claim("w2", 10, LEASE).get(0);
        assertEquals(mine.seq(), theirs.seq(), "fixture: claimed again");
        List<String> before = state();

        assertFalse(queue.hold(mine.seq(), mine.attempts()).await(), "the old holder's hold is stale: false");
        assertFalse(queue.hold(theirs.seq(), theirs.attempts() + 1).await(), "attempts ahead of the row's: false");
        assertEquals(List.of(), queue.held(Optional.empty(), Page.MAX_LIMIT).await().items(), "nothing held");
        assertEquals(before, state());
        assertTrue(queue.complete(theirs.seq(), theirs.attempts()).await(), "not held: the current holder completes it");
        assertFalse(queue.hold(theirs.seq(), theirs.attempts()).await(), "a completed row is not held");
        assertEquals(List.of(), queue.held(Optional.empty(), Page.MAX_LIMIT).await().items());
    }

    @Test
    void aHeldRowIsNotCompletedByItsHolder() {
        enqueue("x1", "K"); enqueue("x2", "K");
        WorkQueue.Item x1 = claim("w1", 10, LEASE).get(0);
        assertTrue(queue.hold(x1.seq(), x1.attempts()).await(), "fixture: held by its holder");
        List<String> before = state();

        assertFalse(queue.complete(x1.seq(), x1.attempts()).await(), "a held row is not completed, not even by its holder");
        assertEquals(WorkQueue.Completion.STALE, queue.completeAndTakeNext(x1.seq(), x1.attempts(), "w1", LEASE).await(),
                "nor through the fast path, which takes nothing");
        assertEquals(before, state(), "x1 and x2 as they were");
        assertEquals(List.of(x1), queue.held(Optional.empty(), Page.MAX_LIMIT).await().items().stream().map(WorkQueue.Held::item).toList(),
                "x1 still held");

        assertTrue(queue.release(x1.seq()).await(), "fixture");
        WorkQueue.Item again = claim("w2", 10, LEASE).get(0);
        assertEquals(new WorkQueue.Item(x1.seq(), "x1", "t", 1, "K", 2), again, "fixture: the released row comes first");
        assertFalse(queue.complete(x1.seq(), x1.attempts()).await(), "after the claim the old token is stale");
        assertTrue(queue.complete(again.seq(), again.attempts()).await(), "the new holder completes it");
        assertEquals(List.of("x2"), ids());
    }

    /** {@code attempts > 0}: a row never handed out has attempts 0 (V4's default), and token 0 matches it for nothing. */
    @Test
    void aTokenOfZeroMatchesNoRow() {
        enqueue("q1", "K"); enqueue("q2", "K");
        long q1 = pool.query("select seq from work where id = 'q1'").execute().await().iterator().next().getLong("seq");
        assertEquals(0, row(q1).getInteger("attempts"), "fixture: never handed out");
        List<String> before = state();

        assertFalse(queue.renew(q1, 0, LEASE).await(), "renew with token 0");
        assertFalse(queue.hold(q1, 0).await(), "hold with token 0");
        assertFalse(queue.complete(q1, 0).await(), "complete with token 0");
        assertEquals(WorkQueue.Completion.STALE, queue.completeAndTakeNext(q1, 0, "w1", LEASE).await(), "fast path with token 0");
        assertEquals(before, state(), "nothing renewed, held, completed or taken");

        WorkQueue.Item first = claim("w1", 10, LEASE).get(0);
        assertEquals(new WorkQueue.Item(q1, "q1", "t", 1, "K", 1), first, "witness: q1 untouched, handed out at attempt 1");
        assertTrue(queue.renew(q1, 1, LEASE).await(), "witness: its token renews");
        assertTrue(queue.complete(q1, 1).await(), "and completes");
    }
}
