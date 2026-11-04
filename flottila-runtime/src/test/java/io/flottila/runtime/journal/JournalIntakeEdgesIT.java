package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.Envelope;
import io.flottila.core.queue.WorkQueue;
import io.flottila.runtime.blob.RemoteBodyException;
import io.flottila.runtime.blob.TieredBlobStore;
import io.flottila.runtime.queue.PgWorkQueue;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static io.flottila.runtime.journal.JournalWorld.bytes;
import static io.flottila.runtime.journal.JournalWorld.causes;
import static io.flottila.runtime.journal.JournalWorld.count;
import static io.flottila.runtime.journal.JournalWorld.settled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-4c2 edges of the intake and the claim on journal refs that the packet's cases leave out: an abandoned save of a body
 * that was durable already, a store-backed queue claiming a pre-V12 item, and a queue of any node claiming another node's item.
 */
@Timeout(120)
class JournalIntakeEdgesIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final Duration LEASE = Duration.ofSeconds(30);
    static Vertx vertx;
    static CaseDatabase db;

    @TempDir Path tmp;
    final List<JournalWorld> worlds = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        pg.start();
        vertx = Vertx.vertx();
        db = CaseDatabase.create(pg, vertx, "journal_edges_").migrated();
    }

    @AfterAll
    static void stop() {
        db.close();
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void emptyTables() {
        db.pool().query("truncate body_refs, journal_segments, journal_nodes, work, bodies, messages, dlq").execute().await();
    }

    @AfterEach
    void closeWorlds() throws Exception {
        for (JournalWorld w : worlds) w.close();
    }

    JournalWorld world(String node) throws Exception {
        JournalWorld w = JournalWorld.open(vertx, db.pool(), db.pool(), Files.createDirectories(tmp.resolve(node)), node);
        worlds.add(w);
        return w;
    }

    @Test
    void anAbandonedSaveOfABodyDurableAlreadyFailsWithItsAbandonmentAndLeavesNoOpenSave() throws Exception {
        JournalWorld a = world("node-a");
        Envelope in = a.arrive("m-durable", bytes(2_000, 7));
        assertTrue(settled(a.blobs.persist(in.body().key())).succeeded(), "fixture: durable before its save, no local copy");

        Future<String> saved = a.save(in, true, io.vertx.core.Future.succeededFuture());

        assertTrue(saved.failed(), "abandoned before its commit: no save");
        assertTrue(causes(saved.cause()).stream().anyMatch(c -> c.contains("abandoned")), "failed with the abandonment: " + causes(saved.cause()));
        assertEquals(0, count(db.pool(), "select count(*) from work where id = 'm-durable'"), "no item");
        assertEquals(0, count(db.pool(), "select count(*) from messages where id = 'm-durable'"), "no ingress row");
        assertEquals(List.of(), List.copyOf(a.intake.openSaves()), "and no open save: it added no record of its own");
    }

    @Test
    void aStoreBackedQueueClaimsAPreV12ItemWithoutIndexingIt() throws Exception {
        JournalWorld a = world("node-a");
        db.pool().preparedQuery("insert into work(id, flow, step, partition_key) values ($1, $2, 1, 'K-1')")
                .execute(Tuple.of("old-1", JournalWorld.FLOW)).await();
        PgWorkQueue onA = new PgWorkQueue(db.pool(), "node-a", a.blobs);

        List<WorkQueue.Item> claimed = onA.claim("w-a", JournalWorld.FLOW, 1, LEASE).await();

        assertEquals(List.of("old-1"), claimed.stream().map(WorkQueue.Item::messageId).toList(), "a row without a body node is anyone's");
        assertEquals(Boolean.FALSE, settled(a.blobs.exists(BlobKey.body("old-1", 0))).result(),
                "and its key names nothing the store holds or indexed: no ref, no bodies row");
    }

    @Test
    void aQueueOfAnyNodeClaimingAnotherNodesItemLeavesItsRefOutOfThisStoresIndex() throws Exception {
        JournalWorld a = world("node-a");
        JournalWorld b = world("node-b");
        Buffer bytes = bytes(2_000, 8);
        assertTrue(b.save(b.arrive("m-on-b", bytes), false, JournalWorld.undecided()).succeeded(), "fixture: node-b's item, its body in node-b's journal");
        PgWorkQueue anyNode = new PgWorkQueue(db.pool(), null, a.blobs);

        List<WorkQueue.Item> claimed = anyNode.claim("w-any", JournalWorld.FLOW, 1, LEASE).await();
        assertEquals(List.of("m-on-b"), claimed.stream().map(WorkQueue.Item::messageId).toList(), "fixture: claimed by a queue of any node");

        Future<Buffer> read = settled(a.blobs.read(BlobKey.body("m-on-b", 0)));
        assertTrue(read.failed(), "node-a's journal does not hold the record");
        RemoteBodyException elsewhere = assertInstanceOf(RemoteBodyException.class, read.cause(),
                "read by its work row, never by a ref indexed into the wrong journal: " + read.cause());
        assertTrue(elsewhere.getMessage().contains("node-b"), elsewhere.getMessage());
    }
}
