package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.Envelope;
import io.flottila.api.Hasher;
import io.flottila.api.Headers;
import io.flottila.runtime.blob.RemoteBodyException;
import io.flottila.runtime.blob.TieredBlobStore;
import io.flottila.runtime.journal.peer.FrameRelay;
import io.flottila.runtime.journal.peer.PeerConfig;
import io.flottila.runtime.journal.peer.PeerIdentity;
import io.flottila.runtime.journal.peer.PeerReads;
import io.flottila.runtime.journal.peer.PeerServer;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.flottila.testkit.PostgresImage;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
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

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static io.flottila.runtime.journal.JournalWorld.bytes;
import static io.flottila.runtime.journal.JournalWorld.causes;
import static io.flottila.runtime.journal.JournalWorld.opened;
import static io.flottila.runtime.journal.JournalWorld.settled;
import static io.flottila.runtime.journal.JournalWorld.workRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-5a acceptance A2 (packet S1, S2, S7 read path): two nodes on one Postgres, each its {@link JournalWorld} — node-a's
 * {@code PeerServer} serving its journal, node-b's store reading through a {@code PeerReads} (read order: own journal → READ
 * from the live owner → retryable {@link RemoteBodyException}). node-b finds node-a behind a {@link FrameRelay} (the counting
 * server: a read that went through the link is seen there) at the address its live map names — what the renew answers in
 * production ({@code PgMembershipPeersIT}). Fake timers never advanced: no {@code FLOTTILA_PEER_TIMEOUT} fires. The large body's
 * bounded heap is {@link PeerReadHeapTest}'s (its own JVM at -Xmx48m: the ITs' heap is unbounded).
 *
 * <p>The API assumed is {@link PeerLinkTest}'s, plus: {@code io.flottila.runtime.blob.RemoteBodies} — {@code InputStream
 * open(BlobKey key, String owner, UUID journal, JournalRef ref) throws IOException}, which {@code PeerReads} implements;
 * {@code new TieredBlobStore(Config, Hasher, Journal, int tenantKeyId, Pool, RemoteBodies remote)}: a durable body whose ref
 * names another node is read through {@code remote}, the owner's journal id the one {@code journal_nodes} holds for it.
 */
@Timeout(180)
class PeerReadIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static final long LARGE_MAX = 4L << 20;
    static Vertx vertx;

    @TempDir Path tmp;
    CaseDatabase db;
    JournalWorld a;
    JournalWorld b;
    Vertx vertxB;
    final FakeTimer timerA = new FakeTimer(FakeClock.at(JournalFixture.START));
    final FakeTimer timerB = new FakeTimer(FakeClock.at(JournalFixture.START));
    PeerServer server;
    boolean serverClosed;
    FrameRelay wire;
    /** The live peers' addresses node-b knows: what its last renew answered. */
    final Map<String, String> live = new ConcurrentHashMap<>();
    final List<PeerReads> readers = new ArrayList<>();

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
        db = CaseDatabase.create(pg, vertx, "peer_read_").migrated();
        a = JournalWorld.open(vertx, db.pool(), db.pool(), Files.createDirectories(tmp.resolve("node-a")), "node-a");
        b = JournalWorld.open(vertx, db.pool(), db.pool(), Files.createDirectories(tmp.resolve("node-b")), "node-b");
        vertxB = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1));
        server = PeerServer.listen(vertx, config(16), identity("node-a"), a.journal, timerA).await(20, TimeUnit.SECONDS);
        wire = new FrameRelay("127.0.0.1", server.port());
        live.put("node-a", wire.address());
    }

    @AfterEach
    void close() throws Exception {
        for (PeerReads r : readers) r.close().await(20, TimeUnit.SECONDS);
        wire.close();
        if (!serverClosed) server.close().await(20, TimeUnit.SECONDS);
        a.close();
        b.close();
        vertxB.close().await();
        db.close();
    }

    static PeerConfig config(int reads) {
        return new PeerConfig("127.0.0.1", 0, "127.0.0.1", Duration.ofSeconds(2), reads);
    }

    UUID journalOf(String node) {
        return db.pool().preparedQuery("select journal_id from journal_nodes where node = $1").execute(Tuple.of(node)).await().iterator().next().getUUID(0);
    }

    PeerIdentity identity(String node) {
        return new PeerIdentity(node, 1, journalOf(node), List.of(TestJournals.MASTER));
    }

    /** node-b's reader with FLOTTILA_PEER_READS {@code reads}, on node-b's one event loop. */
    PeerReads reads(int reads) {
        PeerReads r = new PeerReads(vertxB, config(reads), identity("node-b"), b.journal, timerB, owner -> Optional.ofNullable(live.get(owner)));
        readers.add(r);
        return r;
    }

    /** node-b's store, reading another node's bodies through {@code remote}. */
    TieredBlobStore storeB(PeerReads remote) {
        return new TieredBlobStore(b.config, Hasher.sha256(), b.journal, b.opened.tenant(), db.pool(), remote);
    }

    /** An item of node-a: its ingress body of {@code bytes} saved in node-a's journal (a body above the memory tier spills). */
    void savedOnA(String id, Buffer bytes) {
        Future<Body> written = settled(a.blobs.write(BlobKey.body(id, 0), bytes, LARGE_MAX));
        assertTrue(written.succeeded(), "fixture: written " + written.cause());
        Envelope in = Envelope.ingress(id, JournalWorld.FLOW, Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), written.result());
        Future<String> saved = a.save(in, false, JournalWorld.undecided());
        assertTrue(saved.succeeded(), "fixture: saved on node-a " + saved.cause());
        assertEquals("node-a", workRef(db.pool(), id).node(), "fixture: its body lies in node-a's journal");
    }

    static void until(BooleanSupplier condition, Supplier<String> what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) throw new AssertionError("not within 20 s: " + what.get());
            Thread.onSpinWait();
        }
    }

    static RemoteBodyException retryable(Future<Buffer> read, String what) {
        assertTrue(read.failed(), what + ": no bytes: " + (read.succeeded() ? read.result().length() + " bytes" : null));
        for (Throwable t = read.cause(); t != null; t = t.getCause()) {
            if (t instanceof RemoteBodyException r) {
                assertTrue(String.valueOf(r.getMessage()).contains("node-a"), what + ": naming the owner: " + r.getMessage());
                return r;
            }
        }
        throw new AssertionError(what + ": a retryable RemoteBodyException, was " + causes(read.cause()));
    }

    // ---------------------------------------------------------------------------------------------------- read through the link

    /**
     * A body whose segment lies in node-a's journal reads on node-b through node-a's link, byte for byte: a small record (one
     * BYTES frame) and a large one of 3 MiB + 5 (spilled, read in chunks, more than one 1 MiB BYTES frame). Witnesses: node-b's
     * store without a peer reader fails the same key retryable (the bytes are not on node-b), and the reads went through the
     * relay (READ frames seen there).
     */
    @Test
    void aBodyInAnotherLiveNodesJournalIsReadThroughItsLink() throws Exception {
        Buffer small = bytes(2_000, 11);
        Buffer large = bytes((3 << 20) + 5, 12);
        savedOnA("m-small", small);
        savedOnA("m-large", large);
        retryable(opened(b.blobs, BlobKey.body("m-small", 0)), "witness: node-b's store without a peer reader");
        assertEquals(0, wire.connections(), "fixture: nothing reached node-a yet");

        TieredBlobStore onB = storeB(reads(16));
        Future<Buffer> readSmall = opened(onB, BlobKey.body("m-small", 0));
        Future<Buffer> readLarge = opened(onB, BlobKey.body("m-large", 0));

        assertTrue(readSmall.succeeded(), "the small body reads on node-b: " + (readSmall.failed() ? causes(readSmall.cause()) : ""));
        assertEquals(small, readSmall.result(), "byte for byte");
        assertTrue(readLarge.succeeded(), "the large body reads on node-b: " + (readLarge.failed() ? causes(readLarge.cause()) : ""));
        assertEquals(large.length(), readLarge.result().length());
        assertEquals(large, readLarge.result(), "byte for byte");
        assertTrue(wire.toListener().frames() >= 3, "through node-a's link (HELLO and two READs at least): " + wire.describe());
    }

    /**
     * The reader checks what it is given (S7: CRC and AEAD by the reader; a READ names no key, so only the reader can check the
     * record is the key's): a work row whose ref names another message's record in node-a's journal fails as corrupt, never
     * with that message's bytes; a record whose bytes were changed on node-a's disk fails, never with the changed bytes.
     * Witness: both records it was given read back right by their own keys over the same link.
     */
    @Test
    void theReaderChecksWhatItReadsAndNeverAnswersWrongBytes() throws Exception {
        Buffer x = bytes(2_000, 21);
        Buffer y = bytes(2_000, 22);
        Buffer z = bytes(2_000, 23);
        savedOnA("m-x", x);
        savedOnA("m-y", y);
        savedOnA("m-z", z);
        TieredBlobStore onB = storeB(reads(16));
        assertEquals(y, settled(opened(onB, BlobKey.body("m-y", 0))).result(), "witness: m-y reads by its own key");

        JournalWorld.Ref ofY = workRef(db.pool(), "m-y");
        db.pool().preparedQuery("update work set body_segment = $2, body_position = $3, body_length = $4 where id = $1")
                .execute(Tuple.of("m-x", ofY.segment(), ofY.position(), ofY.length())).await();
        Future<Buffer> misaddressed = opened(onB, BlobKey.body("m-x", 0));
        assertTrue(misaddressed.failed(), "a ref naming m-y's record answers nothing for m-x: "
                + (misaddressed.succeeded() ? (misaddressed.result().equals(y) ? "m-y's bytes" : misaddressed.result().length() + " bytes") : ""));
        assertTrue(causes(misaddressed.cause()).stream().anyMatch(c -> c.contains(JournalCorruptException.class.getName())),
                "checked against its key by the reader: " + causes(misaddressed.cause()));

        JournalWorld.Ref ofZ = workRef(db.pool(), "m-z");
        Path segment = LargeBodyTest.segmentFile(tmp.resolve("node-a"), ofZ.segment());
        try (FileChannel ch = FileChannel.open(segment, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long at = ofZ.position() + ofZ.length() / 2;
            ByteBuffer one = ByteBuffer.allocate(1);
            ch.read(one, at);
            one.put(0, (byte) (one.get(0) ^ 0x40)).rewind();
            ch.write(one, at);
            ch.force(false);
        }
        Future<Buffer> tampered = opened(onB, BlobKey.body("m-z", 0));
        assertTrue(tampered.failed(), "a record changed on node-a's disk answers nothing: "
                + (tampered.succeeded() ? tampered.result().length() + " bytes" : ""));
        assertEquals(y, settled(opened(onB, BlobKey.body("m-y", 0))).result(), "witness: the link still reads m-y");
    }

    // ---------------------------------------------------------------------------------------------------- FLOTTILA_PEER_READS

    /**
     * Reads above FLOTTILA_PEER_READS per peer wait, never fail. With 2, five reads of node-a's bodies at once: node-a's answers
     * are held at the relay, so the first two READs stay in flight; once every reader is parked and node-b's one event loop ran
     * a task after their writes, the relay has seen exactly two READs. Each release of the answers held lets the next ones go:
     * four, then five. Every read answers its own bytes; none fails while it waits (the timers stand still, so no timeout is
     * what made it wait).
     */
    @Test
    void readsAbovePeerReadsPerPeerWaitAndNeverFail() throws Exception {
        List<String> ids = List.of("m-0", "m-1", "m-2", "m-3", "m-4", "m-5");
        Map<String, Buffer> bodies = new java.util.HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            bodies.put(ids.get(i), bytes(2_000, 30 + i));
            savedOnA(ids.get(i), bodies.get(ids.get(i)));
        }
        UUID journalA = journalOf("node-a");
        PeerReads two = reads(2);
        assertEquals(bodies.get("m-0"), Buffer.buffer(read(two, "m-0", journalA).outcome().get(20, TimeUnit.SECONDS)), "witness: the link reads");
        int mark = wire.toListener().frames();
        wire.toDialer().hold();

        List<OnVirtualThread.Call<byte[]>> calls = new ArrayList<>();
        for (String id : ids.subList(1, 6)) calls.add(read(two, id, journalA));

        settledAt(calls, mark + 2, 0, "two READs in flight, three waiting");
        until(() -> wire.toDialer().heldFrames() == 2, () -> "node-a answered the two READs in flight: " + wire.describe());
        wire.toDialer().releaseHeld();
        until(() -> calls.stream().filter(OnVirtualThread.Call::isDone).count() == 2, () -> "the two answered reads end: " + wire.describe());
        settledAt(calls, mark + 4, 2, "the next two READs once two ended");
        until(() -> wire.toDialer().heldFrames() == 2, () -> "node-a answered the next two READs: " + wire.describe());
        wire.toDialer().releaseHeld();
        until(() -> calls.stream().filter(OnVirtualThread.Call::isDone).count() == 4, () -> "four reads end: " + wire.describe());
        settledAt(calls, mark + 5, 4, "the last READ");
        wire.toDialer().release();

        for (int i = 0; i < calls.size(); i++) {
            String id = ids.get(i + 1);
            byte[] got = calls.get(i).outcome().handle((v, t) -> {
                if (t != null) throw new AssertionError(id + ": a read above FLOTTILA_PEER_READS waits, it never fails: " + causes(t), t);
                return v;
            }).get(20, TimeUnit.SECONDS);
            assertEquals(bodies.get(id), Buffer.buffer(got), id + ": its own bytes");
        }
        assertEquals(1, wire.connections(), "one read link: " + wire.describe());
    }

    OnVirtualThread.Call<byte[]> read(PeerReads r, String id, UUID journal) {
        JournalRef ref = workRef(db.pool(), id).journalRef();
        return OnVirtualThread.start(() -> {
            try (InputStream in = r.open(BlobKey.body(id, 0), "node-a", journal, ref)) {
                return in.readAllBytes();
            }
        });
    }

    /**
     * Waits until every read not ended is parked, runs a task on node-b's one event loop (after it, every write a parked
     * reader issued has reached the kernel) and the relay has read all it was sent; then node-b has sent exactly
     * {@code reads} READ frames in all, and {@code done} reads have ended — none failed.
     */
    void settledAt(List<OnVirtualThread.Call<byte[]>> calls, int reads, int done, String what) throws Exception {
        until(() -> wire.toListener().frames() >= reads && calls.stream().allMatch(c -> c.isDone() || c.thread().getState() == Thread.State.WAITING),
                () -> what + ": " + wire.describe());
        Promise<Void> barrier = Promise.promise();
        vertxB.runOnContext(v -> barrier.complete());
        barrier.future().await(20, TimeUnit.SECONDS);
        until(() -> {
            try {
                return wire.toListener().caughtUp();
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
        }, () -> what + ": the relay reads what node-b sent: " + wire.describe());
        assertEquals(reads, wire.toListener().frames(), what + ": at most FLOTTILA_PEER_READS (2) READs in flight to node-a: " + wire.describe());
        for (OnVirtualThread.Call<byte[]> c : calls) {
            if (c.outcome().isCompletedExceptionally()) {
                Throwable t = c.outcome().handle((v, x) -> x).get();
                throw new AssertionError(what + ": a read above FLOTTILA_PEER_READS failed instead of waiting: " + causes(t), t);
            }
        }
        assertEquals(done, calls.stream().filter(OnVirtualThread.Call::isDone).count(), what + ": reads ended");
    }

    // ---------------------------------------------------------------------------------------------------- a dead owner

    /**
     * A dead owner without a copy (none exist before CORE-5b) fails the read retryable, never with wrong or partial bytes:
     * node-a not live (no address in the renew's answer) fails at once, nothing dialled; node-a dying mid-stream (its server
     * closed, the connection cut after the first BYTES frame of a 3 MiB body) fails the stream, never ending it short; node-a
     * still listed but listening nowhere fails too.
     */
    @Test
    void aDeadOwnerWithoutACopyFailsRetryableNeverWrongBytes() throws Exception {
        savedOnA("m-small", bytes(2_000, 41));
        savedOnA("m-large", bytes((3 << 20) + 5, 42));
        TieredBlobStore onB = storeB(reads(16));

        live.remove("node-a");
        retryable(opened(onB, BlobKey.body("m-small", 0)), "node-a not live");
        assertEquals(0, wire.connections(), "a peer that is not live is not dialled: " + wire.describe());

        live.put("node-a", wire.address());
        assertTrue(settled(opened(onB, BlobKey.body("m-small", 0))).succeeded(), "witness: node-a live again, the link reads");
        wire.toDialer().holdAfter(1);
        OnVirtualThread.Call<Future<Buffer>> midStream = OnVirtualThread.start(() -> opened(onB, BlobKey.body("m-large", 0)));
        until(() -> wire.toDialer().heldFrames() >= 1, () -> "node-a is answering the large body: " + wire.describe());
        server.close().await(20, TimeUnit.SECONDS);
        serverClosed = true;
        wire.cut(wire.connections() - 1);
        Future<Buffer> cut = midStream.outcome().get(30, TimeUnit.SECONDS);
        retryable(cut, "node-a gone mid-stream");

        retryable(opened(onB, BlobKey.body("m-small", 0)), "node-a listed, listening nowhere");
        retryable(opened(onB, BlobKey.body("m-large", 0)), "and the large body neither");
    }
}
