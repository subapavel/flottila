package io.flottila.runtime.journal;

import io.flottila.core.queue.WorkQueue;
import io.flottila.runtime.queue.PgWorkQueue;
import io.flottila.testkit.PostgresImage;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-4c2 (CORE-3 items 5, 8, 9): until CORE-5 a body is read on the node whose journal holds it, so a node claims only the
 * work whose {@code body_node} is itself — by the claim and by the same-key fast path — or null (a pre-V12 item, its body in
 * {@code bodies}: claimable by any node); its own items are claimed again after a restart (a queue of the same node id). Rows
 * are seeded by SQL as the intake writes them; every case asks both nodes, so a row one node does not take is shown claimable
 * by the other.
 *
 * <p>The shape assumed: {@code new PgWorkQueue(Pool pool, String node)}.
 */
@Timeout(60)
class WorkClaimIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static final Duration LEASE = Duration.ofSeconds(30);
    static Vertx vertx;
    CaseDatabase db;

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
        db = CaseDatabase.create(pg, vertx, "work_claim_").migrated();
    }

    @AfterEach
    void close() {
        db.close();
    }

    /** A work row of flow {@code orders} at step 1, its body on {@code node} (null: a pre-V12 row, no ref). */
    void item(String id, String key, String node) {
        if (node == null) {
            db.pool().preparedQuery("insert into work(id, flow, step, partition_key) values ($1, 'orders', 1, $2)")
                    .execute(io.vertx.sqlclient.Tuple.of(id, key)).await();
        } else {
            db.pool().preparedQuery("insert into work(id, flow, step, partition_key, body_node, body_segment, body_position, body_length) "
                    + "values ($1, 'orders', 1, $2, $3, 1, 64, 400)").execute(io.vertx.sqlclient.Tuple.of(id, key, node)).await();
        }
    }

    static List<String> ids(List<WorkQueue.Item> items) {
        return items.stream().map(WorkQueue.Item::messageId).toList();
    }

    void leasesExpire() {
        db.pool().query("update work set locked_until = now() - interval '1 second' where locked_until is not null").execute().await();
    }

    @Test
    void aNodeClaimsOnlyItemsWhoseBodyItHolds() {
        item("a-1", null, "node-a");
        item("b-1", null, "node-b");
        item("a-2", null, "node-a");
        item("b-2", null, "node-b");
        PgWorkQueue onA = new PgWorkQueue(db.pool(), "node-a");
        PgWorkQueue onB = new PgWorkQueue(db.pool(), "node-b");

        assertEquals(List.of("a-1", "a-2"), ids(onA.claim("w-a", "orders", 10, LEASE).await()), "node-a takes its own items, none of node-b's");
        assertEquals(List.of("b-1", "b-2"), ids(onB.claim("w-b", "orders", 10, LEASE).await()), "which node-b then takes: they were claimable");

        // a restart of node-a (a queue of the same node id), its leases expired: its own items again, still none of node-b's
        leasesExpire();
        PgWorkQueue restarted = new PgWorkQueue(db.pool(), "node-a");
        List<WorkQueue.Item> again = restarted.claim("w-a2", "orders", 10, LEASE).await();
        assertEquals(List.of("a-1", "a-2"), ids(again), "node-a's items are claimed again after a restart");
        assertEquals(List.of(2, 2), again.stream().map(WorkQueue.Item::attempts).toList(), "a second hand-out each");
        assertEquals(List.of(), ids(onA.claim("w-a", "orders", 10, LEASE).await()), "node-b's expired items are still not node-a's");

        // the same-key fast path: the next item of the key is node-b's, so node-a's completion takes nothing
        db.pool().query("delete from work").execute().await();
        item("a-k1", "K-7", "node-a");
        item("b-k2", "K-7", "node-b");
        WorkQueue.Item first = onA.claim("w-a", "orders", 10, LEASE).await().getFirst();
        assertEquals("a-k1", first.messageId(), "fixture: node-a holds the key's first item");
        WorkQueue.Completion done = onA.completeAndTakeNext(first.seq(), first.attempts(), "w-a", LEASE).await();
        assertTrue(done.completed(), "node-a completed its item");
        assertEquals(Optional.empty(), done.next(), "the key's next item is node-b's body: not taken by node-a's fast path");
        assertEquals(List.of("b-k2"), ids(onB.claim("w-b", "orders", 10, LEASE).await()), "node-b takes it");
    }

    @Test
    void aPreV12ItemIsClaimableByAnyNode() {
        item("old-1", null, null);
        item("old-2", null, null);
        PgWorkQueue onA = new PgWorkQueue(db.pool(), "node-a");
        PgWorkQueue onB = new PgWorkQueue(db.pool(), "node-b");

        assertEquals(List.of("old-1"), ids(onB.claim("w-b", "orders", 1, LEASE).await()), "a row without a body node: node-b claims it");
        assertEquals(List.of("old-2"), ids(onA.claim("w-a", "orders", 1, LEASE).await()), "and node-a the other");
        leasesExpire();
        assertEquals(List.of("old-1", "old-2"), ids(onA.claim("w-a", "orders", 10, LEASE).await()), "either node, whichever asks");

        // the fast path takes a pre-V12 next item of the key too
        db.pool().query("delete from work").execute().await();
        item("a-k1", "K-8", "node-a");
        item("old-k2", "K-8", null);
        WorkQueue.Item first = onA.claim("w-a", "orders", 10, LEASE).await().getFirst();
        assertEquals("a-k1", first.messageId(), "fixture");
        WorkQueue.Completion done = onA.completeAndTakeNext(first.seq(), first.attempts(), "w-a", LEASE).await();
        assertEquals(Optional.of("old-k2"), done.next().map(WorkQueue.Item::messageId), "a pre-V12 next item of the key is node-a's to take");
    }
}
