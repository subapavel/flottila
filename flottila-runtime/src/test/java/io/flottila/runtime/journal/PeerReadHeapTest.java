package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.Hasher;
import io.flottila.runtime.journal.GeneratedInput.Digest;
import io.flottila.runtime.journal.peer.FrameRelay;
import io.flottila.runtime.journal.peer.LoopbackQueues;
import io.flottila.runtime.journal.peer.PeerConfig;
import io.flottila.runtime.journal.peer.PeerIdentity;
import io.flottila.runtime.journal.peer.PeerReads;
import io.flottila.runtime.journal.peer.PeerServer;
import io.flottila.runtime.journal.peer.RawPeer;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-5a acceptance A2, "a large body streams under flow control (bounded heap)" (packet S2, S7 read path). Runs in its own JVM
 * with {@code -Xmx48m} (runtime pom, execution {@code heap-bounded}, as {@link LargeBodyHeapTest}; the Postgres ITs run with
 * an unbounded heap, so the bound lives here): a large record of 192 MiB + 7 — four times the heap — lies in node-a's journal
 * (spilled, persisted: its own segment); node-b reads it through node-a's {@code PeerServer} by its ref, through a
 * {@link FrameRelay} that counts the bytes it hands node-b. A reader that holds the body, or what node-a sends ahead of its
 * consumer, dies with OutOfMemoryError (heap or Netty's direct memory, bounded by the same -Xmx).
 *
 * <p>Flow control: the consumer stops every 16 MiB and waits until node-b really stopped reading the link ({@link #notReading}):
 * the relay is parked writing toward node-b, and node-b's socket holds bytes it has not read (its kernel receive queue, from
 * /proc/net/tcp) that stay unread, with nothing more handed over, while node-b's only event loop runs three tasks one after
 * another. Then what the relay handed node-b beyond what the consumer took is at most the reader's window (≤ 8 MiB, S5's
 * unreceived window) + two 1 MiB frames + this machine's loopback socket buffers ({@code tcp_rmem} and {@code tcp_wmem}
 * maxima, read from /proc); a reader that never stops is caught by that bound while the consumer waits, never let through by
 * a write that happened to be parked for a moment (r1: the mutant hold-never-pauses survived that check). The bytes are
 * compared by their SHA-256.
 *
 * <p>Two reads on one link (PeerLink's "another read's buffer is still full"): the owner is a {@link RawPeer} that sends each
 * read's bytes exactly as node-a's journal holds them, in frames of its choosing, so both reads' buffers reach the pause mark
 * in one TCP segment and both hold the link; then the first read's consumer drains its buffer and the link stays paused for
 * the second — node-b's process reads no more of the bytes the owner goes on writing — until the second's consumer drains.
 *
 * <p>The API assumed is {@link PeerLinkTest}'s.
 */
@Timeout(300)
class PeerReadHeapTest {

    static final long MIB = 1L << 20;
    static final long FRAME = MIB - 1024;   // the owner's BYTES frames: one 1 MiB + 37 B frame takes two humongous G1 regions at -Xmx48m (OOM under surefire)
    static final long SIZE = (192L << 20) + 7;
    static final long STALL_EVERY = 16 * MIB;
    /** What node-b's process may hold of the link's bytes beyond its consumer: the pause mark, frames in flight, read buffers. */
    static final long HELD_MAX = 8 * MIB + 2 * MIB;
    static final UUID JOURNAL_A = UUID.fromString("0a0a0a0a-0000-4000-8000-00000000000a");
    static final UUID JOURNAL_B = UUID.fromString("0b0b0b0b-0000-4000-8000-00000000000b");

    @Test
    void aBodyFourTimesTheHeapStreamsThroughTheLinkWithItsReaderUnderFlowControl(@TempDir Path tmp) throws Exception {
        long heap = Runtime.getRuntime().maxMemory();
        assertTrue(heap < 64L << 20, "this test only means something under the small heap: max " + (heap >> 20) + " MiB");
        long bound = 8 * MIB + 2 * MIB + socketMax("/proc/sys/net/ipv4/tcp_rmem") + socketMax("/proc/sys/net/ipv4/tcp_wmem");
        Digest expected = GeneratedInput.expected(52, SIZE);
        FakeClock clock = FakeClock.at(JournalFixture.START);
        FakeTimer timer = new FakeTimer(clock);
        PeerConfig config = new PeerConfig("127.0.0.1", 0, "127.0.0.1", Duration.ofSeconds(2), 16);
        Vertx vertxA = Vertx.vertx();
        Vertx vertxB = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1));
        Throwable primary = null;   // the case's own failure: a close that cannot finish (an event loop killed by OOM) is added to it

        JournalFixture.Settings sa = JournalFixture.settings(Files.createDirectories(tmp.resolve("node-a"))).segmentAge(Duration.ofHours(1));
        sa.node = "node-a";
        JournalFixture.Settings sb = JournalFixture.settings(Files.createDirectories(tmp.resolve("node-b")));
        sb.node = "node-b";
        BlobKey key = BlobKey.body("heap-peer", 0);
        try (Journal a = Journal.open(sa.config(), clock, timer, JournalFixture.keys(JournalFixture.KEY), JournalFiles.real(), new RecordingRows());
             Journal b = OnVirtualThread.call(() -> Journal.open(sb.config(), clock, timer, JournalFixture.keys(JournalFixture.KEY), JournalFiles.real()))) {
            JournalRef ref = OnVirtualThread.call(() -> a.spill(key, JournalFixture.KEY, new GeneratedInput(52, SIZE), SIZE, Hasher.sha256()).persist());
            PeerServer server = PeerServer.listen(vertxA, config, new PeerIdentity("node-a", 1, JOURNAL_A, List.of(PeerLinkTest.ENV)), a, timer)
                    .await(20, TimeUnit.SECONDS);
            try (FrameRelay wire = new FrameRelay("127.0.0.1", server.port())) {
                PeerReads reads = new PeerReads(vertxB, config, new PeerIdentity("node-b", 1, JOURNAL_B, List.of(PeerLinkTest.ENV)), b, timer,
                        owner -> owner.equals("node-a") ? Optional.of(wire.address()) : Optional.empty());
                long[] peakAhead = {0};
                int[] stalls = {0};
                AtomicLong taken = new AtomicLong();
                // a reader that never pauses the link fills the 48 MiB heap before the sockets' share of the bound runs out: the
                // relay measures what node-b's process holds (forwarded, less both sockets' queues, less what its consumer took)
                // before every frame and cuts the link above HELD_MAX, so the case fails on that bound, not on an OutOfMemoryError
                wire.toDialer().refuseWhen(() -> {
                    long held;
                    try {
                        held = heldByB(wire, taken.get());
                    } catch (Exception unmeasured) {
                        return "fixture: the relay could not measure node-b's held bytes: " + unmeasured;   // reported as the case's failure
                    }
                    return held <= HELD_MAX ? null : "node-b's process held " + (held >> 10) + " KiB of the link's bytes beyond its consumer,"
                            + " above " + (HELD_MAX >> 20) + " MiB: its reader never paused the link";
                });
                Digest read = OnVirtualThread.call(() -> {
                    MessageDigest md = GeneratedInput.digest();
                    byte[] buf = new byte[64 * 1024];
                    long consumed = 0;
                    long nextStall = MIB;
                    try (InputStream in = reads.open(key, "node-a", JOURNAL_A, ref)) {
                        for (int n; (n = readOrRefused(in, buf, wire)) >= 0; ) {
                            md.update(buf, 0, n);
                            consumed += n;
                            taken.set(consumed);
                            if (consumed >= nextStall && consumed < SIZE - STALL_EVERY) {
                                long ahead = stalledAhead(wire, vertxB, consumed, bound);
                                peakAhead[0] = Math.max(peakAhead[0], ahead);
                                stalls[0]++;
                                assertTrue(ahead <= bound, "node-b's reader stopped reading the link with " + (ahead >> 20)
                                        + " MiB handed to it beyond its consumer, above the bound " + (bound >> 20) + " MiB (stall " + stalls[0] + ")");
                                nextStall += STALL_EVERY;
                            }
                        }
                    }
                    return new Digest(consumed, GeneratedInput.hashOf(md.digest()));
                });
                assertEquals(expected, read, "the body read through the link, byte for byte");
                assertTrue(stalls[0] >= 10, "fixture: the consumer stopped " + stalls[0] + " times; peak ahead " + (peakAhead[0] >> 20) + " MiB");
                reads.close().await(20, TimeUnit.SECONDS);
            } finally {
                server.close().await(20, TimeUnit.SECONDS);
            }
        } catch (Throwable failed) {
            primary = failed;
            throw failed;
        } finally {
            closeBounded(primary, vertxB, vertxA);
        }
    }

    static final long PAUSE_AT = 4 * MIB;            // a read's pause mark (PeerReads, S5's 8 MiB window halved)
    static final long FIRST = (8L << 20) + 1;
    static final long SECOND = (32L << 20) + 3;
    static final int HEAD = RecordFormat.MAX_HEADER;   // what node-b's journal reads of a large record before open returns

    /**
     * Two reads on one link, both buffers at the pause mark in one TCP segment — both hold the link. The first read's consumer
     * then takes three of its 1 MiB chunks: its buffer is below half the mark and it gives its hold back, but the second's is
     * still full, so the link stays paused: node-b's process reads not one more MiB of what the owner goes on writing (its
     * socket's kernel queues, {@link LoopbackQueues}), and it does not read its socket while its event loop runs. Once the
     * second consumer drains, the link resumes and the second record reads to its last byte, checked by node-b's journal.
     */
    @Test
    void aReadThatGaveItsHoldBackLeavesTheLinkPausedWhileAnotherReadsBufferIsFull(@TempDir Path tmp) throws Exception {
        FakeClock clock = FakeClock.at(JournalFixture.START);
        FakeTimer timer = new FakeTimer(clock);
        PeerConfig config = new PeerConfig("127.0.0.1", 0, "127.0.0.1", Duration.ofSeconds(2), 16);
        Vertx vertxB = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1));
        Throwable primary = null;   // the case's own failure: a close that cannot finish (an event loop killed by OOM) is added to it
        JournalFixture.Settings sa = JournalFixture.settings(Files.createDirectories(tmp.resolve("node-a"))).segmentAge(Duration.ofHours(1));
        sa.node = "node-a";
        JournalFixture.Settings sb = JournalFixture.settings(Files.createDirectories(tmp.resolve("node-b")));
        sb.node = "node-b";
        BlobKey k1 = BlobKey.body("held-1", 0);
        BlobKey k2 = BlobKey.body("held-2", 0);
        try (Journal a = Journal.open(sa.config(), clock, timer, JournalFixture.keys(JournalFixture.KEY), JournalFiles.real(), new RecordingRows());
             Journal b = OnVirtualThread.call(() -> Journal.open(sb.config(), clock, timer, JournalFixture.keys(JournalFixture.KEY), JournalFiles.real()));
             ServerSocket listening = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            JournalRef r1 = OnVirtualThread.call(() -> a.spill(k1, JournalFixture.KEY, new GeneratedInput(71, FIRST), FIRST, Hasher.sha256()).persist());
            JournalRef r2 = OnVirtualThread.call(() -> a.spill(k2, JournalFixture.KEY, new GeneratedInput(72, SECOND), SECOND, Hasher.sha256()).persist());
            PeerReads reads = new PeerReads(vertxB, config, new PeerIdentity("node-b", 1, JOURNAL_B, List.of(PeerLinkTest.ENV)), b, timer,
                    owner -> owner.equals("node-a") ? Optional.of("127.0.0.1:" + listening.getLocalPort()) : Optional.empty());
            CountDownLatch opened1 = new CountDownLatch(1);
            CountDownLatch opened2 = new CountDownLatch(1);
            CountDownLatch drain1 = new CountDownLatch(1);
            CountDownLatch drain2 = new CountDownLatch(1);
            OnVirtualThread.Call<Long> first = OnVirtualThread.start(() -> {
                try (InputStream in = reads.open(k1, "node-a", JOURNAL_A, r1)) {
                    opened1.countDown();
                    drain1.await();
                    return skip(in, 3 * MIB);   // three whole chunks: its buffer of 4 MiB falls to about 1.1 MiB
                }
            });
            try (RawPeer owner = RawPeer.accept(listening, PeerLinkTest.identity("node-a", PeerLinkTest.ENV));
                 InputStream raw1 = a.raw(r1);
                 InputStream raw2 = a.raw(r2)) {
                long id1 = readId(owner);
                // HEAD + 1: open returns once its header is read (the read of 0 bytes that ends readNBytes answers 0 at once since
                // r2 R6, PeerReadZeroLengthTest); the one byte past the header stays buffered and counts in "one byte short" below
                sendBytes(owner, id1, raw1, HEAD + 1);
                opened(opened1, first, "fixture: the first read opened (its header taken)");
                OnVirtualThread.Call<Digest> second = OnVirtualThread.start(() -> {
                    try (InputStream in = reads.open(k2, "node-a", JOURNAL_A, r2)) {
                        opened2.countDown();
                        drain2.await();
                        MessageDigest md = GeneratedInput.digest();
                        byte[] buf = new byte[64 * 1024];
                        long n = 0;
                        for (int k; (k = in.read(buf)) >= 0; n += k) md.update(buf, 0, k);
                        return new Digest(n, GeneratedInput.hashOf(md.digest()));
                    }
                });
                long id2 = readId(owner);
                sendBytes(owner, id2, raw2, HEAD + 1);
                opened(opened2, second, "fixture: the second read opened on the same link");

                // each buffer one byte short of the mark; then the second read's last frame and a 1-byte frame for each read in ONE
                // write: node-b reads it streaming, its read buffer grown, so both 38-byte frames land in one chunk and both hold.
                // (Written alone after node-b caught up, the 76 bytes could be read 64 + 12 — Netty's read buffer shrinks to 64 B
                // when idle — and only the first read held: a false red under the correct code, seen in surefire's fork.)
                sendBytes(owner, id1, raw1, PAUSE_AT - 2);
                sendBytes(owner, id2, raw2, PAUSE_AT - 2 - FRAME);
                byte[] lastOf2 = new byte[8 + (int) FRAME];
                ByteBuffer.wrap(lastOf2).putLong(id2);
                if (raw2.readNBytes(lastOf2, 8, (int) FRAME) < FRAME) throw new AssertionError("fixture: node-a's record ends early");
                byte[] last = owner.sealed(RawPeer.BYTES, lastOf2);
                byte[] t1 = owner.sealed(RawPeer.BYTES, ByteBuffer.allocate(9).putLong(id1).put(raw1.readNBytes(1)).array());
                byte[] t2 = owner.sealed(RawPeer.BYTES, ByteBuffer.allocate(9).putLong(id2).put(raw2.readNBytes(1)).array());
                byte[] together = ByteBuffer.allocate(last.length + t1.length + t2.length).put(last).put(t1).put(t2).array();
                owner.write(together, 0, together.length);
                caughtUp(owner, vertxB);

                AtomicLong written = new AtomicLong();
                OnVirtualThread.Call<Long> writer = OnVirtualThread.start(() -> {   // the second record's rest: it waits in the sockets
                    long left = r2.length() - HEAD - PAUSE_AT;   // the record's bytes as they lie (header and chunk tags), not its body's
                    while (left > 0) {
                        int k = (int) Math.min(FRAME, left);
                        byte[] p = new byte[8 + k];
                        ByteBuffer.wrap(p).putLong(id2);
                        if (raw2.readNBytes(p, 8, k) < k) throw new AssertionError("fixture: node-a's record ends early");
                        byte[] f = owner.sealed(RawPeer.BYTES, p);
                        for (int off = 0; off < f.length; off += 64 * 1024) {
                            int n = Math.min(64 * 1024, f.length - off);
                            owner.write(f, off, n);
                            written.addAndGet(n);
                        }
                        left -= k;
                    }
                    return written.get();
                });
                int bPort = owner.remotePort();
                int aPort = owner.localPort();
                long full = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                // both buffers are full: node-b's process takes no more than what its socket's pause leaves in flight (Vert.x
                // goes on reading into its own queue for a while: 1792 KiB observed under the correct code), never the record's rest
                while (true) {
                    long took = readByB(owner, written.get());
                    assertTrue(took <= PAUSE_AT, "node-b went on reading the link with both reads' buffers full: it read " + (took >> 10)
                            + " KiB of the owner's further bytes (the link never paused)");
                    if (notReading(vertxB, bPort, aPort, written::get)) break;
                    if (System.nanoTime() - full > 0) throw new AssertionError("node-b never stopped reading with both buffers full; written " + written.get());
                    Thread.onSpinWait();
                }
                long before = readByB(owner, written.get());

                drain1.countDown();
                assertEquals(3 * MIB, first.outcome().get(20, TimeUnit.SECONDS), "fixture: the first consumer took three chunks");
                onLoop(vertxB);   // the first read's give-back ran on node-b's loop

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (true) {
                    long more = readByB(owner, written.get()) - before;
                    assertTrue(more <= MIB, "the link resumed while the second read's buffer was full: node-b read " + (more >> 10)
                            + " KiB more of the owner's bytes after the first read gave its hold back");
                    if (notReading(vertxB, bPort, aPort, written::get)) break;
                    if (System.nanoTime() - deadline > 0) throw new AssertionError("node-b went on reading its socket after the first read gave its hold back");
                    Thread.onSpinWait();
                }
                assertTrue(readByB(owner, written.get()) - before <= MIB, "node-b's process read nothing more while the second read's buffer was full");

                drain2.countDown();
                long wrote;   // the writer's BYTES frames are 37 bytes each beyond their record bytes: len, type, seq, READ id, tag
                try {
                    wrote = writer.outcome().get(60, TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException pending) {
                    throw new AssertionError("the owner's writer did not finish once the second consumer drained: written " + written.get()
                            + ", writer " + writer.thread().getState() + " at " + java.util.Arrays.toString(writer.thread().getStackTrace())
                            + "; second consumer at " + java.util.Arrays.toString(second.thread().getStackTrace()));
                }
                long rest = r2.length() - HEAD - PAUSE_AT;
                assertEquals(rest + (rest + FRAME - 1) / FRAME * 37, wrote, "fixture: the owner wrote the second record's every byte once its consumer drained");
                assertEquals(GeneratedInput.expected(72, SECOND), second.outcome().get(60, TimeUnit.SECONDS),
                        "the second record read to its last byte once its consumer drained: the link resumed");
            } finally {
                reads.close().await(20, TimeUnit.SECONDS);
            }
        } catch (Throwable failed) {
            primary = failed;
            throw failed;
        } finally {
            closeBounded(primary, vertxB);
        }
    }

    /**
     * Closes each Vert.x, waiting at most 20 s: a reader that ran out of heap can leave an event loop dead and its close never
     * done. With the case already failed, a close that fails is added to that failure (which stays the one reported).
     */
    static void closeBounded(Throwable primary, Vertx... all) throws Exception {
        for (Vertx v : all) {
            try {
                v.close().await(20, TimeUnit.SECONDS);
            } catch (Exception | Error notClosed) {
                if (primary == null) throw notClosed;
                primary.addSuppressed(notClosed);
            }
        }
    }

    /** Waits (bounded) for {@code latch}; on a timeout fails with what {@code call} came to (its failure, or where it waits). */
    static void opened(CountDownLatch latch, OnVirtualThread.Call<?> call, String what) throws Exception {
        if (latch.await(20, TimeUnit.SECONDS)) return;
        if (call.isDone()) {
            try {
                call.outcome().get();
            } catch (java.util.concurrent.ExecutionException failed) {
                throw new AssertionError(what + ": it failed", failed.getCause());
            }
        }
        throw new AssertionError(what + ": not within 20 s, at " + java.util.Arrays.toString(call.thread().getStackTrace()));
    }

    /** The id of the READ the owner receives next. */
    static long readId(RawPeer owner) throws Exception {
        byte[] f = owner.next();
        assertEquals(RawPeer.READ, RawPeer.type(f), "fixture: node-b asks");
        return RawPeer.payload(f).getLong(0);
    }

    /** {@code n} more bytes of {@code raw} to READ {@code id}, in BYTES frames of at most FRAME bytes. */
    static void sendBytes(RawPeer owner, long id, InputStream raw, long n) throws Exception {
        for (long left = n; left > 0; ) {
            int k = (int) Math.min(FRAME, left);
            byte[] p = new byte[8 + k];
            ByteBuffer.wrap(p).putLong(id);
            if (raw.readNBytes(p, 8, k) < k) throw new AssertionError("fixture: node-a's record ends early");
            owner.send(RawPeer.BYTES, p);
            left -= k;
        }
    }

    /** Waits (bounded) until node-b's process took every byte the owner wrote, then for a task on node-b's loop. */
    static void caughtUp(RawPeer owner, Vertx vertxB) throws Exception {
        PeerReadIT.until(() -> {
            try {
                return LoopbackQueues.of(owner.localPort(), owner.remotePort()).tx() == 0 && LoopbackQueues.of(owner.remotePort(), owner.localPort()).rx() == 0;
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, () -> "fixture: node-b takes what the owner wrote");
        onLoop(vertxB);
    }

    /**
     * The bytes node-b's process holds beyond its consumer's {@code taken}: what the relay wrote toward it, less the relay
     * socket's send queue, less node-b's socket's receive queue (read in that order: a byte moving between them is counted
     * twice, never missed), less {@code taken}.
     */
    static long heldByB(FrameRelay wire, long taken) throws Exception {
        long forwarded = wire.toDialer().forwardedBytes();
        int relay = wire.port();
        int dialer = wire.dialerPort(0);
        long tx = LoopbackQueues.find(relay, dialer).map(LoopbackQueues.Queues::tx).orElse(0L);
        long rx = LoopbackQueues.find(dialer, relay).map(LoopbackQueues.Queues::rx).orElse(0L);
        return forwarded - tx - rx - taken;
    }

    /** {@code in.read(buf)}; a read the relay's refusal failed reports that refusal (the bound), with the read's failure. */
    static int readOrRefused(InputStream in, byte[] buf, FrameRelay wire) throws java.io.IOException {
        try {
            return in.read(buf);
        } catch (java.io.IOException failed) {
            String why = wire.toDialer().refused();
            if (why == null) throw failed;
            throw new AssertionError(why + ": " + wire.describe(), failed);
        }
    }

    /** The bytes node-b's process read of what the owner's writer handed its socket ({@code written}, slice by slice). */
    static long readByB(RawPeer owner, long written) throws Exception {
        return written - LoopbackQueues.of(owner.localPort(), owner.remotePort()).tx() - LoopbackQueues.of(owner.remotePort(), owner.localPort()).rx();
    }

    /** Reads and drops {@code n} bytes of {@code in}; answers how many it read. */
    static long skip(InputStream in, long n) throws Exception {
        byte[] buf = new byte[64 * 1024];
        long got = 0;
        for (int k; got < n && (k = in.read(buf, 0, (int) Math.min(buf.length, n - got))) >= 0; ) got += k;
        return got;
    }

    /**
     * Waits until node-b stopped reading the link — the relay parked writing toward it and {@link #notReading} — and answers
     * the bytes handed to node-b beyond {@code consumed}; fails at once when they pass {@code bound} while it waits (a reader
     * that never stops reading).
     */
    static long stalledAhead(FrameRelay wire, Vertx vertxB, long consumed, long bound) throws Exception {
        FrameRelay.Pipe toB = wire.toDialer();
        int dialer = wire.dialerPort(0);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            if (toB.refused() != null) throw new AssertionError(toB.refused() + ": " + wire.describe());
            long ahead = toB.forwardedBytes() - consumed;
            assertTrue(ahead <= bound, "node-b's reader kept reading the link with " + (ahead >> 20) + " MiB handed to it beyond its consumer,"
                    + " above the bound " + (bound >> 20) + " MiB: " + wire.describe());
            // a reader that never stops buffers what it is sent and runs out of the 48 MiB heap before it reaches the bound: its
            // link closes (the hold-never-pauses mutant) — the same failure, caught here rather than as a vanished socket
            assertFalse(wire.closed(0), "node-b's reader never stopped reading the link: it closed while the consumer waited, with "
                    + (ahead >> 20) + " MiB handed to node-b beyond its consumer (an OutOfMemoryError of a reader holding what it is sent;"
                    + " bound " + (bound >> 20) + " MiB): " + wire.describe());
            // A real stall, not a parked write: node-b's socket holds bytes that stay unread, and the relay hands it nothing more,
            // while node-b's only event loop runs three tasks; a parked write alone passed while node-b still read (r1, hold-never-pauses).
            if (toB.parkedWriting() && notReading(vertxB, dialer, wire.port(), toB::forwardedBytes) && toB.parkedWriting()) {
                return toB.forwardedBytes() - consumed;
            }
            if (System.nanoTime() - deadline > 0) {
                throw new AssertionError("node-b's reader never stopped reading the link within 30 s: " + wire.describe() + ", consumed " + consumed);
            }
            Thread.onSpinWait();
        }
    }

    /**
     * Whether node-b does not read its socket {@code local} → {@code remote}: the kernel holds bytes it has not read, and they
     * stay unread — with {@code sent} (what its sender handed over) unchanged — while node-b's only event loop runs three
     * tasks one after another. A loop that reads the socket reads some of those bytes before or between the tasks: a readable
     * socket's bytes are read in the loop's next I/O pass, and each task waits for the one before it.
     */
    static boolean notReading(Vertx vertxB, int local, int remote, LongSupplier sent) throws Exception {
        long before = sent.getAsLong();
        java.util.Optional<LoopbackQueues.Queues> q = LoopbackQueues.find(local, remote);
        if (q.isEmpty() || q.get().rx() == 0) return false;   // no socket: the link closed, which the caller reports
        for (int i = 0; i < 3; i++) onLoop(vertxB);
        java.util.Optional<LoopbackQueues.Queues> after = LoopbackQueues.find(local, remote);
        return after.isPresent() && after.get().rx() == q.get().rx() && sent.getAsLong() == before;
    }

    /** Runs an empty task on node-b's one event loop and waits (bounded) for it: every task queued there before it ran. */
    static void onLoop(Vertx vertxB) throws Exception {
        Promise<Void> barrier = Promise.promise();
        vertxB.runOnContext(v -> barrier.complete());
        barrier.future().await(20, TimeUnit.SECONDS);
    }

    static long socketMax(String file) throws Exception {
        // readAllLines, not readString: on this VM's procfs (size 0) Files.readString answered "4" of "4096 131072 33554432"
        String[] v = Files.readAllLines(Path.of(file)).getFirst().trim().split("\\s+");
        return Long.parseLong(v[v.length - 1]);
    }

    static void until(BooleanSupplier condition, Supplier<String> what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) throw new AssertionError("not within 30 s: " + what.get());
            Thread.onSpinWait();
        }
    }
}
