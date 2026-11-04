package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobNotFoundException;
import io.flottila.api.BlobStore;
import io.flottila.api.BodyTooLargeException;
import io.flottila.api.Envelope;
import io.flottila.core.queue.WorkQueue;
import io.flottila.runtime.admin.AdminServer;
import io.flottila.runtime.blob.TieredBlobStore;
import io.flottila.runtime.queue.PgWorkQueue;
import io.flottila.testkit.DurableBlobStoreContract;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static io.flottila.runtime.journal.JournalWorld.bodyRef;
import static io.flottila.runtime.journal.JournalWorld.bytes;
import static io.flottila.runtime.journal.JournalWorld.count;
import static io.flottila.runtime.journal.JournalWorld.opened;
import static io.flottila.runtime.journal.JournalWorld.settled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-4c2 (D-105, CORE-3 items 7 and 8): the node's body store with the journal as its durable tier passes the durable
 * contract — whether a body is a segment record (at most RECORD_MAX, a memory copy until its persist) or a large body of its
 * own (a spill, then a segment) — and keeps what the contract cannot see: {@code persist(key)} is the record forced and its
 * {@code body_refs} row committed, never a {@code bodies} row; a read on another node than the body's fails retryable, never
 * as "not found" (CORE-5 adds peer reads); FIX-35's compare reads the stored record back, byte for byte (64 KiB at a time —
 * a difference on either side of a 64 KiB boundary is found), no bytes or hash in Postgres; a key whose ref points into a
 * segment whose row is gone (swept) is persisted again, its row replaced in the same statement.
 *
 * <p>Every case starts from empty journal tables on one migrated database ({@code truncate} is no DELETE) and a journal
 * directory of its own; the shapes assumed are {@link JournalWorld}'s.
 */
@Timeout(120)
class JournalBlobStoreIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static Vertx vertx;
    static CaseDatabase db;

    @TempDir Path tmp;
    final List<JournalWorld> worlds = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        pg.start();
        vertx = Vertx.vertx();
        db = CaseDatabase.create(pg, vertx, "journal_store_").migrated();
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

    JournalWorld world(String node, Consumer<JournalFixture.Settings> tweak, TieredBlobStore.Config config) throws Exception {
        JournalWorld w = JournalWorld.open(vertx, db.pool(), db.pool(), Files.createDirectories(tmp.resolve(node + "-" + worlds.size())), node, tweak, config);
        worlds.add(w);
        return w;
    }

    JournalWorld world(String node) throws Exception {
        return world(node, s -> { }, new TieredBlobStore.Config(JournalWorld.MAX, JournalWorld.MEMORY_TIER, JournalWorld.MEMORY_BUDGET, JournalWorld.DURABLE_MAX));
    }

    /** The contract over a journal store; a case calls {@code store()} once. */
    abstract class OnTheJournal extends DurableBlobStoreContract {
        abstract JournalWorld open() throws Exception;

        @Override protected BlobStore store() {
            try {
                return open().blobs;
            } catch (Exception e) {
                throw new IllegalStateException("fixture: the journal store did not open", e);
            }
        }
    }

    /** RECORD_MAX 256 KiB, a 1 MiB memory tier: the suite's small bodies are segment records, its 3 MiB stream a large body. */
    @Nested
    class SegmentRecords extends OnTheJournal {
        @Override JournalWorld open() throws Exception {
            return world("node-a", s -> { }, new TieredBlobStore.Config(TREE_LIMIT, 1L << 20, 8L << 20, 4L << 20));
        }
    }

    /** RECORD_MAX and the memory tier at their floor, 4 KiB: every body above 4 KiB is a large body of its own. */
    @Nested
    class LargeBodies extends OnTheJournal {
        @Override JournalWorld open() throws Exception {
            return world("node-a", s -> s.recordMax(4096).pendingBytes(1L << 20), new TieredBlobStore.Config(TREE_LIMIT, 4096, 1L << 20, 16L << 20));
        }

        @Override protected long bigSize() { return 5L << 20; }
    }

    // --- beyond the contract

    @Test
    void persistCommitsABodyRefAndNoBodiesRow() throws Exception {
        JournalWorld a = world("node-a");
        Buffer bytes = bytes(10_000, 1);
        BlobKey key = BlobKey.body("p-ref", 0);
        settled(a.blobs.write(key, bytes, JournalWorld.MAX));

        Future<Boolean> forcedAtCompletion = settled(a.blobs.persist(key).map(v -> a.files.forcedPast("p-ref")));
        assertTrue(forcedAtCompletion.succeeded(), "persisted: " + forcedAtCompletion.cause());
        assertTrue(forcedAtCompletion.result(), "persist completes once the record is forced (CORE-3 item 7)");
        JournalWorld.Ref ref = bodyRef(db.pool(), key);
        assertNotNull(ref, "and its body_refs row is committed: another session sees it");
        assertEquals("node-a", ref.node(), "on the node whose journal holds the record");
        assertEquals(bytes, a.record(ref), "the row's ref is the record of these bytes");
        assertEquals(1, count(db.pool(), "select count(*) from journal_segments where node = 'node-a' and segment = $1", ref.segment()),
                "in a segment with its row");
        assertEquals(0, count(db.pool(), "select count(*) from bodies"), "no bytes go to bodies any more (rows there are pre-V12, read only)");
        assertEquals(0, a.blobs.memoryInUse(), "the memory copy went with the persist");
        assertEquals(bytes, settled(a.blobs.read(key)).result(), "and the key reads back from the journal");
    }

    @Test
    void aReadOnAnotherNodeFailsRetryable() throws Exception {
        JournalWorld a = world("node-a");
        JournalWorld b = world("node-b");
        Buffer bytes = bytes(2_000, 2);
        BlobKey key = BlobKey.body("p-elsewhere", 0);
        settled(a.blobs.write(key, bytes, JournalWorld.MAX));
        assertTrue(settled(a.blobs.persist(key)).succeeded(), "fixture: durable on node-a");
        assertEquals(bytes, settled(a.restartedStore().read(key)).result(), "fixture: the body reads on its own node, from Postgres and its journal");

        Future<Buffer> read = settled(b.blobs.read(key));
        Future<Buffer> streamed = opened(b.blobs, key);
        for (Future<Buffer> f : List.of(read, streamed)) {
            assertTrue(f.failed(), "node-b holds no copy of a body node-a's journal holds (CORE-4: the owner node only)");
            Throwable cause = f.cause();
            assertFalse(cause instanceof BlobNotFoundException, "never 'not found': the body exists, on another node — " + cause);
            assertFalse(cause instanceof IllegalArgumentException, "nor the api's no-such-key family (D-096): retryable — " + cause);
            assertTrue(String.valueOf(cause.getMessage()).contains("node-a"), "it names the node holding the body: " + cause);
        }
        assertEquals(Boolean.TRUE, settled(b.blobs.exists(key)).result(), "the body exists (D-096: no bytes read): it is durable on node-a");
    }

    /** {@code first} with byte {@code at} flipped. */
    static Buffer flipped(Buffer first, int at) {
        Buffer other = first.copy();
        other.setByte(at, (byte) (first.getByte(at) ^ 0x40));
        return other;
    }

    /** FIX-35's refusal text (DurableBlobStoreContract.otherBytesRefused): the key and the size of the bytes refused. */
    static String otherBytesRefused(BlobKey key, long size) {
        return "the durable body under " + key + " holds other bytes than these " + size
                + ": a key's durable bytes never change (D-061, FIX-35); nothing was persisted and the body stays local";
    }

    @Test
    void otherBytesAreFoundByReadingTheRecordBack() throws Exception {
        JournalWorld a = world("node-a");
        // a segment record (≤ RECORD_MAX 256 KiB) and a large body (above it); a difference on either side of the first 64 KiB
        // boundary, and in the last byte
        for (int size : new int[] {200_000, 300_000}) {
            Buffer first = bytes(size, size % 251);
            for (int at : new int[] {65_535, 65_536, size - 1}) {
                BlobKey key = BlobKey.body("p-back-" + size + "-" + at, 1);
                settled(a.blobs.write(key, first, JournalWorld.MAX));
                assertTrue(settled(a.blobs.persist(key)).succeeded(), "fixture: the first bytes are durable (" + size + ")");
                JournalWorld.Ref durable = bodyRef(db.pool(), key);
                assertEquals(first, a.record(durable), "fixture: the stored record holds them");

                Buffer other = flipped(first, at);
                settled(a.blobs.write(key, other, JournalWorld.MAX));
                Future<Void> refused = settled(a.blobs.persist(key));
                assertTrue(refused.failed(), "one byte other at " + at + " of " + size + " is found by reading the record back");
                IllegalStateException e = assertInstanceOf(IllegalStateException.class, refused.cause(), size + "/" + at + ": " + refused.cause());
                assertEquals(otherBytesRefused(key, size), e.getMessage());
                assertEquals(durable, bodyRef(db.pool(), key), "nothing changes: the row names the first record (" + size + "/" + at + ")");
                assertEquals(other, settled(a.blobs.read(key)).result(), "the body stays as written, local (" + size + "/" + at + ")");
            }
            BlobKey same = BlobKey.body("p-same-" + size, 1);
            settled(a.blobs.write(same, first, JournalWorld.MAX));
            assertTrue(settled(a.blobs.persist(same)).succeeded(), "fixture: durable (" + size + ")");
            JournalWorld.Ref durable = bodyRef(db.pool(), same);
            settled(a.blobs.write(same, first.copy(), JournalWorld.MAX));
            Future<Void> quiet = settled(a.blobs.persist(same));
            assertTrue(quiet.succeeded(), "equal bytes over several 64 KiB blocks: quiet (" + size + "): " + quiet.cause());
            assertEquals(durable, bodyRef(db.pool(), same), "and the row still names the first record (" + size + ")");
        }
        assertEquals(0, count(db.pool(), "select count(*) from bodies"), "no bytes in Postgres to compare against");
    }

    @Test
    void aRefIntoASweptSegmentIsReplacedByTheNextPersist() throws Exception {
        JournalWorld a = world("node-a", s -> s.segmentRecords(1),   // one record per segment: the swept one holds only the first bytes
                new TieredBlobStore.Config(JournalWorld.MAX, JournalWorld.MEMORY_TIER, JournalWorld.MEMORY_BUDGET, JournalWorld.DURABLE_MAX));
        BlobKey key = BlobKey.body("p-swept", 2);
        Buffer first = bytes(5_000, 5);
        settled(a.blobs.write(key, first, JournalWorld.MAX));
        assertTrue(settled(a.blobs.persist(key)).succeeded(), "fixture: durable");
        JournalWorld.Ref swept = bodyRef(db.pool(), key);
        // the sweeper's delete of the segment row (CORE-4d): from now on the key reads as never written (D-096)
        assertEquals(1, count(db.pool(), "with gone as (delete from journal_segments where node = 'node-a' and segment = $1 returning 1) select count(*) from gone",
                swept.segment()), "fixture: the segment row is deleted");

        Buffer other = flipped(first, 4_999);
        settled(a.blobs.write(key, other, JournalWorld.MAX));
        Future<Void> persisted = settled(a.blobs.persist(key));
        assertTrue(persisted.succeeded(), "a row whose segment row is gone is no durable body to compare with: replaced — " + persisted.cause());
        JournalWorld.Ref now = bodyRef(db.pool(), key);
        assertNotEquals(swept.segment(), now.segment(), "the row names the new record's segment");
        assertEquals(1, count(db.pool(), "select count(*) from journal_segments where node = 'node-a' and segment = $1", now.segment()),
                "a segment with its row");
        assertEquals(other, a.record(now), "holding the bytes persisted now");
        assertEquals(1, count(db.pool(), "select count(*) from body_refs where message_id = 'p-swept'"), "one row per key, replaced in place");
        assertEquals(other, settled(a.restartedStore().read(key)).result(), "a store with an empty index reads the new bytes");
    }

    // --- review r1: the durable bound, a rewrite during a persist, the location index

    /**
     * The durable bound's edge (D-061; the twin on the journal of the retired spool-tier cases): a body of exactly
     * {@code durableMaxBytes} persists, one byte more is refused with {@link BodyTooLargeException} and stays local and readable,
     * with no row — through {@code persist} and through the intake's save alike (the same bound, its own check).
     */
    @Test
    void aBodyAtTheDurableBoundPersistsAndOneByteAboveIsRefusedAndStaysLocal() throws Exception {
        int bound = 8_192;
        JournalWorld a = world("node-a", s -> { }, new TieredBlobStore.Config(JournalWorld.MAX, JournalWorld.MEMORY_TIER, JournalWorld.MEMORY_BUDGET, bound));
        BlobKey at = BlobKey.body("p-at", 0);
        Buffer atBytes = bytes(bound, 50);
        assertTrue(settled(a.blobs.write(at, atBytes, JournalWorld.MAX)).succeeded(), "fixture: written");
        Future<Void> persisted = settled(a.blobs.persist(at));
        assertTrue(persisted.succeeded(), "a body of exactly the bound persists: " + persisted.cause());
        assertEquals(atBytes, a.record(bodyRef(db.pool(), at)), "its row names the record of these bytes");

        BlobKey over = BlobKey.body("p-over", 0);
        Buffer overBytes = bytes(bound + 1, 51);
        assertTrue(settled(a.blobs.write(over, overBytes, JournalWorld.MAX)).succeeded(), "fixture: written (the flow's max is larger)");
        Future<Void> refused = settled(a.blobs.persist(over));
        assertTrue(refused.failed(), "one byte above the bound is no persist");
        assertInstanceOf(BodyTooLargeException.class, refused.cause(), String.valueOf(refused.cause()));
        assertNull(bodyRef(db.pool(), over), "no row names it");
        assertEquals(Optional.of(TieredBlobStore.Location.MEMORY), a.blobs.location(over), "it stays where it is");
        assertEquals(overBytes, settled(a.blobs.read(over)).result(), "and reads");

        Envelope fits = a.arrive("m-at", bytes(bound, 52));
        Future<String> saved = a.save(fits, true, () -> false);
        assertTrue(saved.succeeded(), "the intake saves a body of exactly the bound: " + saved.cause());
        Envelope tooLarge = a.arrive("m-over", bytes(bound + 1, 53));
        Future<String> notSaved = a.save(tooLarge, true, () -> false);
        assertTrue(notSaved.failed(), "and refuses one byte more");
        assertTrue(JournalWorld.causes(notSaved.cause()).stream().anyMatch(c -> c.contains(BodyTooLargeException.class.getSimpleName())),
                "as too large: " + JournalWorld.causes(notSaved.cause()));
        assertEquals(0, count(db.pool(), "select count(*) from work where id = 'm-over'"), "no item");
        assertEquals(Optional.of(TieredBlobStore.Location.MEMORY), a.blobs.location(tooLarge.body().key()), "its body stays local");
    }

    /**
     * D-062 (a key's local copy goes only when it is the copy the persist made durable): a key written again while its persist's
     * force is held keeps the new copy local and readable with the new bytes; the persist's row names the first copy's record.
     */
    @Test
    void aKeyWrittenAgainWhileItsPersistsForceIsHeldKeepsTheNewCopyLocalAndReadable() throws Exception {
        JournalWorld a = world("node-a");
        BlobKey key = BlobKey.body("p-again", 0);
        Buffer first = bytes(2_000, 60);
        Buffer again = bytes(2_000, 61);
        assertTrue(settled(a.blobs.write(key, first, JournalWorld.MAX)).succeeded(), "fixture: written");
        HeldFiles.Hold force = a.files.holdForceAfter("p-again");

        Future<Void> persist = a.blobs.persist(key);
        force.awaitReached();
        assertTrue(settled(a.blobs.write(key, again, JournalWorld.MAX)).succeeded(), "fixture: written again while the persist's force is held");
        force.release();

        assertTrue(settled(persist).succeeded(), "the persist completes: " + persist.cause());
        assertEquals(first, a.record(bodyRef(db.pool(), key)), "it made the first copy durable");
        assertEquals(Optional.of(TieredBlobStore.Location.MEMORY), a.blobs.location(key), "the copy written meanwhile stays local");
        assertEquals(again, opened(a.blobs, key).result(), "and is what the key reads");
    }

    /** {@link #aKeyWrittenAgainWhileItsPersistsForceIsHeldKeepsTheNewCopyLocalAndReadable}'s twin on the intake's save (its commit). */
    @Test
    void anIngressBodyWrittenAgainWhileItsSavesForceIsHeldKeepsTheNewCopyLocalAndReadable() throws Exception {
        JournalWorld a = world("node-a");
        Buffer first = bytes(2_000, 62);
        Buffer again = bytes(2_000, 63);
        Envelope in = a.arrive("m-again", first);
        HeldFiles.Hold force = a.files.holdForceAfter("m-again");

        Future<String> saved = a.start(in, true, () -> false);
        force.awaitReached();
        assertTrue(settled(a.blobs.write(in.body().key(), again, JournalWorld.MAX)).succeeded(), "fixture: written again while the save's force is held");
        force.release();

        assertTrue(settled(saved).succeeded(), "the save completes: " + saved.cause());
        assertEquals(first, a.record(bodyRef(db.pool(), in.body().key())), "it made the first copy durable");
        assertEquals(Optional.of(TieredBlobStore.Location.MEMORY), a.blobs.location(in.body().key()), "the copy written meanwhile stays local");
        assertEquals(again, opened(a.blobs, in.body().key()).result(), "and is what the key reads");
    }

    record Answer(int status, Buffer body) {
        JsonObject json() { return new JsonObject(body); }
    }

    static Answer get(HttpClientAgent client, int port, String path) {
        return client.request(new RequestOptions().setMethod(HttpMethod.GET).setAbsoluteURI("http://127.0.0.1:" + port + path))
                .compose(req -> {
                    req.putHeader("Authorization", "Bearer t0ken");
                    req.response().onSuccess(HttpClientResponse::pause);
                    return req.send();
                })
                .compose(res -> {
                    Future<Buffer> body = res.body();
                    res.resume();
                    return body.map(b -> new Answer(res.statusCode(), b));
                })
                .await();
    }

    /**
     * Saves {@code id} keeping its input — its commit indexes the ref — then deletes its work row and its {@code body_refs} row,
     * as retention does after a COMPLETE that never reached the store: the index entry is stale. Witnessed: Postgres names the
     * body no more (a store with an empty index answers false).
     */
    BlobKey staleEntry(JournalWorld a, String id, Buffer bytes) {
        Envelope in = a.arrive(id, bytes);
        assertTrue(a.save(in, true, () -> false).succeeded(), "fixture: saved; its commit indexed the ref");
        assertEquals(Boolean.TRUE, settled(a.blobs.exists(in.body().key())).result(), "witness: the body exists while its rows are there");
        db.pool().preparedQuery("delete from work where id = $1").execute(io.vertx.sqlclient.Tuple.of(id)).await();
        db.pool().preparedQuery("delete from body_refs where message_id = $1").execute(io.vertx.sqlclient.Tuple.of(id)).await();
        assertEquals(Boolean.FALSE, settled(a.restartedStore().exists(in.body().key())).result(), "witness: Postgres names the body no more");
        return in.body().key();
    }

    /**
     * Architect decision, review r1 (6): {@code exists()} never answers from the location index — it asks Postgres (the key's
     * {@code body_refs} row, then its message's work row, then {@code bodies}); the index serves {@code open()} only. A stale
     * index entry ({@link #staleEntry}) does not make a body exist.
     */
    @Test
    void aStaleIndexEntryDoesNotAnswerExists() throws Exception {
        JournalWorld a = world("node-a");
        BlobKey key = staleEntry(a, "m-stale", bytes(2_000, 70));

        assertEquals(Boolean.FALSE, settled(a.blobs.exists(key)).result(), "exists asks Postgres, never the location index");
    }

    /**
     * The same decision at the admin seam: a GET of a body whose rows are gone answers 404 "removed by retention" (D-057), a stale
     * index entry ({@link #staleEntry}) notwithstanding. Witness: the same GET is 200 with the bytes while the rows are there.
     */
    @Test
    void aStaleIndexEntryDoesNotAnswerTheAdminRead() throws Exception {
        JournalWorld a = world("node-a");
        Buffer bytes = bytes(2_000, 71);
        AdminServer server = new AdminServer(new AdminServer.Config("127.0.0.1", 0, Optional.of("t0ken")), a.messages, a.dlq, a.blobs,
                new AdminServer.HealthProbe(Map.of(), () -> 0L));
        HttpClientAgent client = vertx.createHttpClient();
        try {
            int port = server.start(vertx).await();
            Envelope witness = a.arrive("m-read", bytes);
            assertTrue(a.save(witness, true, () -> false).succeeded(), "fixture: saved");
            Answer read = get(client, port, "/admin/messages/m-read/steps/0/body");
            assertEquals(200, read.status(), "witness: the admin reads a body whose rows are there: " + read.body());
            assertEquals(bytes, read.body());

            staleEntry(a, "m-stale", bytes);
            Answer gone = get(client, port, "/admin/messages/m-stale/steps/0/body");
            assertEquals(404, gone.status(), "the admin read answers what Postgres names, not a stale index entry: " + gone.body().length() + " bytes answered");
            assertEquals(new JsonObject().put("error", "the bytes of step 0 of message m-stale are no longer stored (removed by retention)"), gone.json());
        } finally {
            server.stop().await();
            client.close().await();
        }
    }

    /**
     * An async item completed over {@link PgWorkQueue} takes its input body's index entry with it (CORE-3 item 8, "pins forget"):
     * at level none nothing else names the body once the item is gone, so a read of it fails {@link BlobNotFoundException} —
     * a stale entry would still serve it from the journal. Both completions: {@code complete} and {@code completeAndTakeNext}.
     */
    @Test
    void anItemCompletedOverTheWorkQueueLeavesNoIndexEntryToReadItsBodyBy() throws Exception {
        JournalWorld a = world("node-a");
        PgWorkQueue queue = new PgWorkQueue(db.pool(), "node-a", a.blobs);
        Buffer firstBytes = bytes(2_000, 72);
        Envelope first = a.arrive("m-done-1", firstBytes);
        Envelope second = a.arrive("m-done-2", bytes(2_000, 73));
        assertTrue(a.save(first, false, () -> false).succeeded(), "fixture: saved at level none");
        assertTrue(a.save(second, false, () -> false).succeeded(), "fixture: saved at level none, the same partition key");
        assertNull(bodyRef(db.pool(), first.body().key()), "fixture: no body_refs row at level none");

        List<WorkQueue.Item> claimed = queue.claim("w-1", JournalWorld.FLOW, 1, Duration.ofMinutes(5)).await();
        assertEquals(List.of("m-done-1"), claimed.stream().map(WorkQueue.Item::messageId).toList(), "fixture: the oldest item claimed");
        assertEquals(firstBytes, opened(a.blobs, first.body().key()).result(), "witness: its body reads while its item is there");

        WorkQueue.Completion next = queue.completeAndTakeNext(claimed.getFirst().seq(), claimed.getFirst().attempts(), "w-1", Duration.ofMinutes(5)).await();
        assertTrue(next.completed(), "fixture: completed");
        assertEquals(Optional.of("m-done-2"), next.next().map(WorkQueue.Item::messageId), "fixture: and the next of its key taken");
        Future<Buffer> afterTakeNext = opened(a.blobs, first.body().key());
        assertTrue(afterTakeNext.failed(), "completed by completeAndTakeNext: its body is read by no index entry");
        assertInstanceOf(BlobNotFoundException.class, afterTakeNext.cause(), String.valueOf(afterTakeNext.cause()));

        assertTrue(queue.complete(next.next().get().seq(), next.next().get().attempts()).await(), "fixture: completed");
        Future<Buffer> afterComplete = opened(a.blobs, second.body().key());
        assertTrue(afterComplete.failed(), "completed by complete: its body is read by no index entry");
        assertInstanceOf(BlobNotFoundException.class, afterComplete.cause(), String.valueOf(afterComplete.cause()));
    }
}
