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
        // the proto-m1 `work` table (db/init.sql); P0-08 turns it into the V1 migration
        pool.query("""
                create table work (
                    seq           bigserial   primary key,
                    id            text        not null,
                    flow          text        not null,
                    step          int         not null,
                    partition_key text,
                    available_at  timestamptz not null default now(),
                    locked_by     text,
                    locked_until  timestamptz
                )""").execute().await();
        pool.query("create index work_flow_seq on work (flow, seq)").execute().await();
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
}
