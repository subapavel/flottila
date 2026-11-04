package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.runtime.blob.RemoteBodyException;
import io.flottila.runtime.blob.TieredBlobStore;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static io.flottila.runtime.journal.JournalWorld.bodyRef;
import static io.flottila.runtime.journal.JournalWorld.bytes;
import static io.flottila.runtime.journal.JournalWorld.settled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-4c2, FIX-35 without bytes in Postgres: a persist that meets the key's kept ref on another node cannot read that record
 * back to compare it (CORE-4: the owner node only), so it fails retryable naming the owner and changes nothing.
 */
@Timeout(120)
class JournalPersistElsewhereIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static Vertx vertx;
    static CaseDatabase db;

    @TempDir Path tmp;
    final List<JournalWorld> worlds = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        pg.start();
        vertx = Vertx.vertx();
        db = CaseDatabase.create(pg, vertx, "journal_elsewhere_").migrated();
    }

    @AfterAll
    static void stop() {
        db.close();
        vertx.close().await();
        pg.stop();
    }

    @AfterEach
    void closeWorlds() throws Exception {
        for (JournalWorld w : worlds) w.close();
    }

    JournalWorld world(String node) throws Exception {
        JournalWorld w = JournalWorld.open(vertx, db.pool(), db.pool(), Files.createDirectories(tmp.resolve(node)), node, s -> { },
                new TieredBlobStore.Config(JournalWorld.MAX, JournalWorld.MEMORY_TIER, JournalWorld.MEMORY_BUDGET, JournalWorld.DURABLE_MAX));
        worlds.add(w);
        return w;
    }

    @Test
    void aPersistMeetingTheKeysRefOnAnotherNodeFailsNamingItAndChangesNothing() throws Exception {
        JournalWorld a = world("node-a");
        JournalWorld b = world("node-b");
        BlobKey key = BlobKey.body("p-owned-elsewhere", 0);
        Buffer first = bytes(2_000, 3);
        Buffer again = bytes(2_000, 4);
        settled(a.blobs.write(key, first, JournalWorld.MAX));
        assertTrue(settled(a.blobs.persist(key)).succeeded(), "fixture: the key is durable on node-a");
        settled(b.blobs.write(key, again, JournalWorld.MAX));

        Future<Void> persisted = settled(b.blobs.persist(key));

        assertTrue(persisted.failed(), "node-b cannot compare its bytes with node-a's record");
        RemoteBodyException elsewhere = assertInstanceOf(RemoteBodyException.class, persisted.cause(), "retryable, never 'other bytes' or 'not found'");
        assertTrue(String.valueOf(elsewhere.getMessage()).contains("node-a"), "it names the node holding the key's record: " + elsewhere.getMessage());
        assertEquals("node-a", bodyRef(db.pool(), key).node(), "the key's ref still names node-a's record");
        assertEquals(first, a.record(bodyRef(db.pool(), key)), "with node-a's bytes");
        assertEquals(Optional.of(TieredBlobStore.Location.MEMORY), b.blobs.location(key), "node-b's copy stays local");
        assertEquals(again, settled(b.blobs.read(key)).result(), "and reads back as node-b wrote it");
    }
}
