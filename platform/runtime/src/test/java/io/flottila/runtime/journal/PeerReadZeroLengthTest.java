package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.Hasher;
import io.flottila.runtime.journal.peer.PeerReads;
import io.flottila.runtime.journal.peer.RawPeer;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * r2 R6 (CORE-5a, implementer): a peer read asked for no byte answers 0 at once (the {@link InputStream} contract), never waits
 * for the owner's next frame. node-b's journal reads a large record's header with {@code readNBytes}, which asks its stream
 * for 0 bytes once it has them all; a fake node-a (the real handshake over a plain socket) answers the READ with a first BYTES
 * frame of exactly the header's bytes and holds the rest back until the stream is open. Witness: the rest then sent, the
 * record reads to its last byte, checked by node-b's journal.
 */
@Timeout(120)
class PeerReadZeroLengthTest {

    static final BlobKey LARGE = BlobKey.body("m-large", 0);
    static final long LARGE_SIZE = (1L << 20) + 3;   // above the journal's 256 KiB record max: a large record
    static final long BOUND_SECONDS = 20;

    @TempDir Path tmp;
    Vertx vertx;
    final FakeClock clock = FakeClock.at(JournalFixture.START);
    final FakeTimer timer = new FakeTimer(clock);
    Journal a;
    Journal b;
    JournalRef large;
    PeerReads reads;
    ServerSocket listening;

    @BeforeEach
    void open() throws Exception {
        vertx = Vertx.vertx();
        a = journal("node-a");
        b = journal("node-b");
        large = OnVirtualThread.call(() -> a.spill(LARGE, JournalFixture.KEY, new GeneratedInput(61, LARGE_SIZE), LARGE_SIZE, Hasher.sha256()).persist());
        listening = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        reads = new PeerReads(vertx, PeerLinkTest.CONFIG, PeerProtocolTest.b(), b, timer,
                owner -> Optional.of("127.0.0.1:" + listening.getLocalPort()));
    }

    @AfterEach
    void close() throws Exception {
        reads.close().await(BOUND_SECONDS, TimeUnit.SECONDS);
        listening.close();
        a.close();
        b.close();
        vertx.close().await();
    }

    Journal journal(String node) throws Exception {
        JournalFixture.Settings s = JournalFixture.settings(Files.createDirectories(tmp.resolve(node))).segmentAge(Duration.ofHours(1));
        s.node = node;
        JournalConfig config = s.config();
        return OnVirtualThread.call(() -> Journal.open(config, clock, timer, JournalFixture.keys(JournalFixture.KEY), JournalFiles.real()));
    }

    @Test
    void aReadOfNoByteAnswersAtOnceWithNoFrameBuffered() throws Exception {
        byte[] stored;
        try (InputStream raw = a.raw(large)) {
            stored = raw.readNBytes((int) large.length());
        }
        int head = (int) Math.min(large.length(), RecordFormat.MAX_HEADER);
        OnVirtualThread.Call<InputStream> opening = OnVirtualThread.start(() -> reads.open(LARGE, "node-a", PeerLinkTest.JOURNAL_A, large));
        try (RawPeer fake = RawPeer.accept(listening, PeerLinkTest.identity("node-a", PeerLinkTest.ENV))) {
            byte[] readFrame = fake.next();
            assertEquals(RawPeer.READ, RawPeer.type(readFrame), "fixture: node-b asks");
            long id = RawPeer.payload(readFrame).getLong(0);
            fake.send(RawPeer.BYTES, ByteBuffer.allocate(8 + head).putLong(id).put(stored, 0, head).array());

            InputStream in;
            try {
                in = opening.outcome().get(BOUND_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException waiting) {
                throw new AssertionError("the stream did not open on the header's bytes alone: a read of 0 bytes waited for the next frame (its thread: "
                        + Arrays.toString(opening.thread().getStackTrace()) + ")");
            } catch (ExecutionException failed) {
                throw new AssertionError("the stream failed to open", failed.getCause());
            }
            OnVirtualThread.Call<GeneratedInput.Digest> reading = OnVirtualThread.start(() -> {
                try (InputStream s = in) {
                    return GeneratedInput.digestOf(s);
                }
            });
            for (int at = head; at < stored.length; ) {
                int k = Math.min(1 << 20, stored.length - at);
                fake.send(RawPeer.BYTES, ByteBuffer.allocate(8 + k).putLong(id).put(stored, at, k).array());
                at += k;
            }
            try {
                assertEquals(GeneratedInput.expected(61, LARGE_SIZE), reading.outcome().get(BOUND_SECONDS, TimeUnit.SECONDS),
                        "the record read to its last byte after the rest came");
            } catch (ExecutionException failed) {
                fail("the record's read failed", failed.getCause());
            }
        }
    }
}
