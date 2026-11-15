package io.flottila.runtime.journal;

import io.flottila.runtime.journal.peer.LoopbackQueues;
import io.flottila.runtime.journal.peer.PeerConfig;
import io.flottila.runtime.journal.peer.PeerIdentity;
import io.flottila.runtime.journal.peer.PeerServer;
import io.flottila.runtime.journal.peer.RawPeer;
import io.flottila.runtime.journal.peer.ReplWire;
import io.flottila.runtime.journal.peer.ReplWire.Answer;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One holder for the CORE-5b1 holder cases: node-a's journal over {@link HeldFiles} (every write and force of its replicas
 * recorded, any one held or failed), its {@code PeerServer} on a Vert.x of ONE event loop ({@link #onLoop()} is then a barrier
 * for every task queued there before it), a fake timer never advanced (no FLOTTILA_PEER_TIMEOUT closes a link), an
 * {@code epochOf} the case sets per owner (no entry: 0, no bound), and owners dialling it on the repl channel through
 * {@link RawPeer}, their payloads from {@link ReplWire} (the packet's text, never {@code ReplFrames}).
 *
 * <p>The API assumed (CORE-5b1, test-author): {@code PeerServer.listen(Vertx, PeerConfig, Supplier<PeerIdentity>, Journal,
 * Timer, ToLongFunction<String> epochOf)}; {@code Journal.replicas()} answering the journal's {@code Replicas}, whose
 * {@code long unforced()} is the bytes its replicas wrote and have not forced, over all of them; the replicas lie under
 * {@code <FLOTTILA_JOURNAL_DIR>/replicas/<owner>/<journal id>/<n>.part|.seg} and count in {@link Journal#bytes()}.
 */
final class ReplicaWorld implements AutoCloseable {

    static final SecretKey ENV = JournalFixture.key(7_501);
    static final UUID JOURNAL_A = UUID.fromString("0a0a0a0a-0000-4000-8000-00000000000a");
    static final UUID JOURNAL_B = UUID.fromString("0b0b0b0b-0000-4000-8000-00000000000b");
    static final UUID JOURNAL_C = UUID.fromString("0c0c0c0c-0000-4000-8000-00000000000c");
    static final PeerConfig CONFIG = new PeerConfig("127.0.0.1", 0, "127.0.0.1", Duration.ofSeconds(2), 16);
    static final PeerIdentity A = new PeerIdentity("node-a", 1, JOURNAL_A, List.of(ENV));
    static final PeerIdentity B = owner("node-b", 1, JOURNAL_B);
    static final PeerIdentity C = owner("node-c", 1, JOURNAL_C);
    static final long CREATED = JournalFixture.START.toEpochMilli();
    /** node-b's segment header: 43 + 6 bytes. */
    static final int HDR = 49;
    static final long MIB = 1L << 20;

    final Path dir;
    final Vertx vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1));
    final FakeClock clock = FakeClock.at(JournalFixture.START);
    final FakeTimer timer = new FakeTimer(clock);
    final HeldFiles files = new HeldFiles();
    final Map<String, Long> epochs = new ConcurrentHashMap<>();
    final JournalFixture.LogCapture logs = new JournalFixture.LogCapture();
    final Journal holder;
    final PeerServer server;
    private final List<Owner> owners = new ArrayList<>();

    ReplicaWorld(Path tmp) throws Exception {
        this(tmp, s -> { });
    }

    ReplicaWorld(Path tmp, Consumer<JournalFixture.Settings> tweak) throws Exception {
        dir = Files.createDirectories(tmp.resolve("node-a")).toAbsolutePath().normalize();
        JournalFixture.Settings s = JournalFixture.settings(dir);
        s.node = "node-a";
        tweak.accept(s);
        JournalConfig config = s.config();
        holder = OnVirtualThread.call(() -> Journal.open(config, clock, timer, JournalFixture.keys(JournalFixture.KEY), files));
        server = PeerServer.listen(vertx, CONFIG, () -> A, holder, timer, owner -> epochs.getOrDefault(owner, 0L))
                .await(JournalFixture.BOUND.toSeconds(), TimeUnit.SECONDS);
    }

    static PeerIdentity owner(String node, long epoch, UUID journal) {
        return new PeerIdentity(node, epoch, journal, List.of(ENV));
    }

    /** An owner's repl link to node-a, its handshake done. */
    Owner dial(PeerIdentity owner) throws IOException {
        Owner o = new Owner(owner, RawPeer.dial(server.port(), owner, "node-a", ReplWire.CHANNEL_REPL));
        owners.add(o);
        return o;
    }

    /** {@link #dial(PeerIdentity)} from a socket whose receive buffer is {@code receiveBuffer} bytes (set before it connects). */
    Owner dial(PeerIdentity owner, int receiveBuffer) throws IOException {
        Owner o = new Owner(owner, RawPeer.dial(server.port(), owner, "node-a", ReplWire.CHANNEL_REPL, receiveBuffer));
        owners.add(o);
        return o;
    }

    Path replicaDir(String owner, UUID journal) {
        return dir.resolve("replicas").resolve(owner).resolve(journal.toString());
    }

    Path part(PeerIdentity owner, long n) {
        return replicaDir(owner.node(), owner.journal()).resolve(n + ".part");
    }

    Path seg(PeerIdentity owner, long n) {
        return replicaDir(owner.node(), owner.journal()).resolve(n + ".seg");
    }

    /** Every op HeldFiles recorded on {@code file}, in entry order. */
    List<HeldFiles.Op> opsOn(Path file) {
        Path at = file.toAbsolutePath().normalize();
        return files.ops().stream().filter(op -> op.file.equals(at)).toList();
    }

    long unforced() {
        return holder.replicas().unforced();
    }

    /** Runs an empty task on the server's one event loop and waits (bounded) for it: every task queued there before it ran. */
    void onLoop() throws Exception {
        Promise<Void> barrier = Promise.promise();
        vertx.runOnContext(v -> barrier.complete());
        barrier.future().await(JournalFixture.BOUND.toSeconds(), TimeUnit.SECONDS);
    }

    /** The bytes of every file under {@code root} (0 when it is not there), from the disk. */
    static long sizeOf(Path root) throws IOException {
        if (!Files.exists(root)) return 0;
        long sum = 0;
        try (Stream<Path> all = Files.walk(root)) {
            for (Path p : (Iterable<Path>) all::iterator) if (Files.isRegularFile(p)) sum += Files.size(p);
        }
        return sum;
    }

    @Override public void close() throws Exception {
        files.releaseAll();
        for (Owner o : owners) o.raw.close();
        server.close().await(JournalFixture.BOUND.toSeconds(), TimeUnit.SECONDS);
        holder.close();
        vertx.close().await();
        logs.close();
    }

    /** One owner's end of a repl link: its frames sealed in order by the real session, its answers checked by it. */
    static final class Owner {
        final PeerIdentity id;
        final RawPeer raw;

        Owner(PeerIdentity id, RawPeer raw) {
            this.id = id;
            this.raw = raw;
        }

        int headerLength() {
            return 43 + id.node().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }

        byte[] header(long n) {
            return ReplWire.header(id.node(), id.journal(), n, CREATED);
        }

        /** Sends OPEN of segment {@code n} with this owner's header of n and {@code length}; answers the header. */
        byte[] open(long n, long length) throws IOException {
            byte[] h = header(n);
            open(n, length, h);
            return h;
        }

        void open(long n, long length, byte[] header) throws IOException {
            raw.send(ReplWire.OPEN, ReplWire.open(id.node(), id.journal(), n, length, header));
        }

        void data(long n, long pos, byte[] bytes) throws IOException {
            raw.send(ReplWire.DATA, ReplWire.data(id.node(), id.journal(), n, pos, bytes));
        }

        void seal(long n, long end, int crc32c) throws IOException {
            raw.send(ReplWire.SEAL, ReplWire.seal(id.node(), id.journal(), n, end, crc32c));
        }

        Answer next() throws IOException {
            Answer a = ReplWire.answer(raw.next());
            assertEquals(id.node(), a.owner(), "an answer names the link's owner: " + a);
            assertEquals(id.journal(), a.journal(), "an answer names the link's journal: " + a);
            return a;
        }

        /** The next answer, which must be {@code type} (RECEIPT or REFUSE) of {@code n} with that state or reason. */
        Answer expect(int type, long n, int stateOrReason, String what) throws IOException {
            Answer a = next();
            assertTrue(a.is(type, n, stateOrReason), what + ": expected " + (type == ReplWire.RECEIPT ? "RECEIPT " : "REFUSE ") + n + " "
                    + stateOrReason + ", was " + a);
            return a;
        }

        /** OPENs segment {@code n} (this owner's header, {@code length}) and waits for its RECEIPT OPENED. */
        byte[] opened(long n, long length) throws IOException {
            byte[] h = open(n, length);
            Answer a = expect(ReplWire.RECEIPT, n, ReplWire.OPENED, "OPEN " + n);
            assertEquals(h.length, a.end(), "RECEIPT OPENED's end is the file's length: its header");
            return h;
        }

        /** Sends DATA and waits for the RECEIPT WRITTEN whose end is {@code pos + bytes.length}. */
        void written(long n, long pos, byte[] bytes) throws IOException {
            data(n, pos, bytes);
            untilWritten(n, pos + bytes.length);
        }

        /** Reads RECEIPT WRITTEN of {@code n} until one ends at {@code end} (one per drain: several DATA may share one). */
        void untilWritten(long n, long end) throws IOException {
            for (long last = -1; last < end; ) {
                Answer a = next();
                assertTrue(a.is(ReplWire.RECEIPT, n, ReplWire.WRITTEN), "a RECEIPT WRITTEN of " + n + " up to " + end + ", was " + a);
                assertTrue(a.end() > last, "receipt ends never decrease: " + a.end() + " after " + last);
                last = a.end();
                assertTrue(last <= end, "a RECEIPT WRITTEN past what was sent: " + a + ", sent up to " + end);
            }
        }

        /** Every answer until node-a closes the link. */
        List<Answer> untilClosed() throws IOException {
            List<Answer> out = new ArrayList<>();
            for (byte[] f : raw.framesUntilClosed()) out.add(ReplWire.answer(f));
            return out;
        }

        /** This owner's socket queues: [its unsent tx, node-a's unread rx] (loopback, /proc/net/tcp). */
        long[] queues() throws IOException {
            java.util.Optional<LoopbackQueues.Queues> mine = LoopbackQueues.find(raw.localPort(), raw.remotePort());
            java.util.Optional<LoopbackQueues.Queues> theirs = LoopbackQueues.find(raw.remotePort(), raw.localPort());
            return new long[] {mine.map(LoopbackQueues.Queues::tx).orElse(0L), theirs.map(LoopbackQueues.Queues::rx).orElse(0L)};
        }

        /** Whether node-a's process has read every byte this owner wrote. */
        boolean allRead() {
            try {
                long[] q = queues();
                return q[0] == 0 && q[1] == 0;
            } catch (IOException e) {
                throw new java.io.UncheckedIOException("fixture: /proc/net/tcp", e);
            }
        }
    }
}
