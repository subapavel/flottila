package io.flottila.runtime;

import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Tuple;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs against the docker-compose Postgres (localhost:5432); skips itself when unreachable. */
@TestMethodOrder(MethodOrderer.MethodName.class)
class WorkQueueTest {
    static Vertx vertx;
    static PgPool pool;
    static WorkQueue queue;
    static final Duration AWAIT = Duration.ofSeconds(5);

    @BeforeAll static void connect() {
        vertx = Vertx.vertx();
        PgConnectOptions co = new PgConnectOptions().setHost("localhost")
                .setPort(Integer.parseInt(System.getenv().getOrDefault("FLOTTILA_PG_PORT", "5432")))
                .setDatabase("flottila").setUser("flottila").setPassword("flottila");
        pool = PgPool.pool(vertx, co, new PoolOptions().setMaxSize(4));
        try {
            pool.query("select 1").execute().await().atMost(AWAIT);
        } catch (RuntimeException e) {
            assumeTrue(false, "Postgres not reachable: " + e.getMessage());
        }
        queue = new WorkQueue.Postgres(pool);
    }

    @AfterAll static void close() { if (vertx != null) vertx.close().await().indefinitely(); }

    @BeforeEach void clean() {
        pool.query("truncate work").execute().await().atMost(AWAIT);
    }

    private void enqueue(String id, String key) {
        queue.enqueue(id, "t", 1, key).await().atMost(AWAIT);
    }
    private List<WorkQueue.Item> claim(String worker, int max, Duration lease) {
        return queue.claim(worker, "t", max, lease).await().atMost(AWAIT);
    }

    @Test void partitionOrderingAcrossTwoWorkers() {
        enqueue("a1", "A"); enqueue("a2", "A"); enqueue("b1", "B"); enqueue("b2", "B"); enqueue("n1", null);
        List<WorkQueue.Item> w1 = claim("w1", 10, Duration.ofSeconds(30));
        // UPDATE..RETURNING order is unspecified and cross-key order carries no guarantee — compare the set
        assertEquals(java.util.Set.of("a1", "b1", "n1"),
                w1.stream().map(WorkQueue.Item::messageId).collect(java.util.stream.Collectors.toSet()),
                "only the oldest row per key plus the unordered null-key row");
        assertTrue(claim("w2", 10, Duration.ofSeconds(30)).isEmpty(), "keys A and B are locked");
        // completing a1 releases key A; a2 must go to whichever worker claims next — order preserved
        long a1seq = w1.stream().filter(i -> i.messageId().equals("a1")).findFirst().orElseThrow().seq();
        queue.complete(a1seq).await().atMost(AWAIT);
        List<WorkQueue.Item> w2 = claim("w2", 10, Duration.ofSeconds(30));
        assertEquals(List.of("a2"), w2.stream().map(WorkQueue.Item::messageId).toList());
        assertTrue(claim("w1", 10, Duration.ofSeconds(30)).isEmpty(), "b2 still behind b1");
    }

    @Test void leaseExpiryRedelivers() throws InterruptedException {
        enqueue("x1", "K");
        List<WorkQueue.Item> first = claim("w1", 1, Duration.ofSeconds(1));
        assertEquals(1, first.size());
        assertTrue(claim("w2", 1, Duration.ofSeconds(1)).isEmpty(), "lease still active");
        Thread.sleep(1300);
        List<WorkQueue.Item> second = claim("w2", 1, Duration.ofSeconds(30));
        assertEquals("x1", second.get(0).messageId(), "expired lease must re-deliver");
        assertEquals(first.get(0).seq(), second.get(0).seq());
    }

    @Test void completeRemovesTheRow() {
        enqueue("c1", null);
        List<WorkQueue.Item> got = claim("w1", 1, Duration.ofSeconds(30));
        queue.complete(got.get(0).seq()).await().atMost(AWAIT);
        Integer left = pool.preparedQuery("select count(*)::int as n from work where id = $1")
                .execute(Tuple.of("c1")).await().atMost(AWAIT).iterator().next().getInteger("n");
        assertEquals(0, left);
    }
}
