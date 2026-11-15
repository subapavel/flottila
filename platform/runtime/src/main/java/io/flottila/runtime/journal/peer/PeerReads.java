package io.flottila.runtime.journal.peer;

import io.flottila.api.BlobKey;
import io.flottila.api.Timer;
import io.flottila.runtime.blob.RemoteBodies;
import io.flottila.runtime.blob.RemoteBodyException;
import io.flottila.runtime.journal.Journal;
import io.flottila.runtime.journal.JournalRef;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * This node's reads of records in other nodes' journals (CORE-5a S1, S2, S7): one {@code read} link per peer, dialled on the
 * first read of it at the address {@code addressOf} names (the last renew's live peers) and again after it closed. A read sends
 * READ(id, owner, journal id, n, pos, len) and takes the BYTES frames in order into a buffer of its own; the record is checked
 * by this node's journal ({@link Journal#stream(JournalRef, BlobKey, InputStream)}: CRC32C, AEAD, the key), never trusted as
 * sent. At most {@code FLOTTILA_PEER_READS} reads are in flight to one peer; a read above it waits for one to end, never fails.
 *
 * <p>Flow control: a read whose buffer holds {@value #PAUSE_AT} bytes its consumer has not taken pauses the link's socket
 * until it holds half of that again, so the peer's bytes wait in the sockets (TCP back-pressure), not in this heap. A paused link pauses
 * every read on it; its other reads wait while the paused one's consumer still takes bytes (head-of-line, FIX-177), and fail
 * with it once that consumer took nothing for a whole {@code FLOTTILA_PEER_TIMEOUT} (r2 R3: the link closes).
 *
 * <p>Failures are a retryable {@link RemoteBodyException} naming the owner, at once: an owner that is not live (no address —
 * nothing dialled), a dial that fails or throws, a link that cannot open, is refused, closed or lost (its watchdog:
 * {@code FLOTTILA_PEER_TIMEOUT} without an answer or a byte taken), an owner answering MISS. A stream whose link fails
 * mid-record fails its next read, never ends short.
 *
 * @since 0.1
 */
public final class PeerReads implements RemoteBodies {

    /** The bytes a read holds for its consumer before it pauses its link (S5's window is 8 MiB). */
    static final long PAUSE_AT = 4L << 20;

    private final PeerConfig config;
    private final PeerIdentity self;
    private final Journal local;
    private final Timer timer;
    private final Function<String, Optional<String>> addressOf;
    private final NetClient client;
    private final Context context;
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final SecureRandom random = new SecureRandom();

    /**
     * Reads of the peers {@code addressOf} names ({@code host:port}, empty: not live), as {@code self}, checked with
     * {@code local}'s tenant keys; the links' timeouts on {@code timer}.
     */
    public PeerReads(Vertx vertx, PeerConfig config, PeerIdentity self, Journal local, Timer timer, Function<String, Optional<String>> addressOf) {
        this.config = Objects.requireNonNull(config, "config");
        this.self = Objects.requireNonNull(self, "self");
        this.local = Objects.requireNonNull(local, "local");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.addressOf = Objects.requireNonNull(addressOf, "addressOf");
        this.client = vertx.createNetClient(new NetClientOptions().setConnectTimeout((int) config.timeout().toMillis()).setTcpNoDelay(true));
        this.context = vertx.getOrCreateContext();   // every link's socket on it: its handlers are set before its first bytes
    }

    @Override
    public InputStream open(BlobKey key, String owner, UUID journal, JournalRef ref) throws IOException {
        Optional<String> address = addressOf.apply(owner);
        if (address.isEmpty()) throw failed(key, owner, "node " + owner + " is not live (no peer address in the last renew)", null);
        Peer peer = peers.computeIfAbsent(owner, Peer::new);
        peer.permits.acquireUninterruptibly();
        Read read;
        try {
            Dialed link;
            try {
                link = peer.link(address.get()).await();
            } catch (Exception e) {   // Future.await throws the dial's own failure, checked ones too
                throw failed(key, owner, "no peer link to " + address.get() + ": " + e.getMessage(), e);
            }
            read = link.start(key, journal, ref, peer.permits::release);
        } catch (RuntimeException | Error e) {
            peer.permits.release();
            throw e;
        }
        return local.stream(ref, key, read);
    }

    /** Closes every link: the reads on them fail. */
    public Future<Void> close() {
        for (Peer p : peers.values()) p.close();
        return client.close();
    }

    private RemoteBodyException failed(BlobKey key, String owner, String why, Throwable cause) {
        return new RemoteBodyException(key, owner, self.node(), why, cause);
    }

    /** One peer: its reads' bound and its link. */
    private final class Peer {
        final String node;
        final Semaphore permits = new Semaphore(config.reads(), true);
        private Future<Dialed> link;   // guarded by this: the last dial

        Peer(String node) {
            this.node = node;
        }

        /**
         * The link to {@code address}: the one open or being dialled, else a new dial; one open to another address (the node
         * joined again elsewhere) is closed.
         */
        synchronized Future<Dialed> link(String address) {
            if (link == null || link.failed() || (link.succeeded() && !link.result().usable(address))) {
                if (link != null && link.succeeded()) link.result().link.close(new IOException("node " + node + " is at " + address + " now"));
                link = dial(address);
            }
            return link;
        }

        synchronized void close() {
            if (link != null) link.onSuccess(d -> d.link.close(new IOException("this node's peer reads close")));
        }

        /**
         * Dials {@code address}: the future fails when the connect fails or throws (no TCP port: r2 R2) and when the link cannot
         * open on the connected socket (that socket then closed), so a read never waits on a dial that went nowhere.
         */
        private Future<Dialed> dial(String address) {
            int colon = address.lastIndexOf(':');
            String host = address.substring(0, colon).replace("[", "").replace("]", "");
            int port = Integer.parseInt(address.substring(colon + 1));
            Promise<Dialed> dialed = Promise.promise();
            context.runOnContext(v -> {
                try {
                    client.connect(port, host).onComplete(socket -> {
                        try {
                            Dialed d = new Dialed(node, address);
                            d.link = PeerLink.open(socket, PeerHandshake.dialer(self, node, PeerReads.this::nonce), timer, config.timeout(), d);
                            d.link.established().map(d).onComplete(dialed::tryComplete, dialed::tryFail);
                        } catch (RuntimeException | Error e) {
                            socket.close();
                            dialed.tryFail(e);
                        }
                    }, dialed::tryFail);
                } catch (RuntimeException | Error e) {
                    dialed.tryFail(e);
                }
            });
            return dialed.future();
        }
    }

    private byte[] nonce() {
        byte[] n = new byte[PeerHandshake.NONCE];
        random.nextBytes(n);
        return n;
    }

    /** A read link and its reads in flight. */
    private final class Dialed implements PeerLink.Receiver {
        final String owner;
        final String address;
        final Map<Long, Read> reads = new ConcurrentHashMap<>();
        PeerLink link;

        Dialed(String owner, String address) {
            this.owner = owner;
            this.address = address;
        }

        boolean usable(String now) {
            return address.equals(now) && !link.closed();
        }

        /** Sends READ of {@code ref} and answers its stream; {@code done} runs once when its bytes, its MISS or its link's end came. */
        Read start(BlobKey key, UUID journal, JournalRef ref, Runnable done) {
            Read r = new Read(ids.incrementAndGet(), key, owner, ref.length(), this, done);
            reads.put(r.id, r);
            link.waits();
            byte[] o = owner.getBytes(StandardCharsets.UTF_8);
            byte[] f = Frames.frame(Frames.READ, 8 + 2 + o.length + 16 + 24);
            ByteBuffer.wrap(f, Frames.HEAD, f.length - Frames.HEAD).putLong(r.id).putShort((short) o.length).put(o)
                    .putLong(journal.getMostSignificantBits()).putLong(journal.getLeastSignificantBits())
                    .putLong(ref.segment()).putLong(ref.position()).putLong(ref.length());
            // a link closed before the put fails the write; one closed after it fails its reads (closed below)
            link.send(f).onFailure(t -> r.fail(failed(key, owner, "the READ did not go out: " + t.getMessage(), t)));
            return r;
        }

        @Override public void received(PeerLink l, int type, byte[] frame) throws PeerRefusedException {
            if (type != Frames.BYTES && type != Frames.MISS) throw new PeerRefusedException("a frame of type " + type + " to a reader");
            // a MISS is its read id; BYTES are their read id and at least one record byte (r2 R5: never a read() answering 0)
            if (type == Frames.MISS ? Frames.payloadLength(frame) != 8 : Frames.payloadLength(frame) <= 8) {
                throw new PeerRefusedException("a frame of type " + type + " of " + Frames.payloadLength(frame) + " bytes");
            }
            Read r = reads.get(Frames.payload(frame).getLong(0));
            if (r == null) throw new PeerRefusedException("an answer to no READ in flight");
            if (type == Frames.MISS) {
                r.fail(failed(r.key, owner, "node " + owner + " answered MISS: no record of that journal lies at that ref there", null));
            } else {
                r.bytes(frame, Frames.HEAD + 8, Frames.payloadLength(frame) - 8);
            }
        }

        @Override public void closed(PeerLink l, IOException why) {
            for (Read r : List.copyOf(reads.values())) r.fail(failed(r.key, owner, "the peer link to " + address + " closed: " + why.getMessage(), why));
        }

        @Override public boolean waiting() {
            return !reads.isEmpty();
        }
    }

    /** One read: the bytes of its record as they come, for its one consumer. */
    private static final class Read extends InputStream {
        final long id;
        final BlobKey key;
        final long length;
        private final Dialed on;
        private final Runnable done;
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition arrived = lock.newCondition();
        private final ArrayDeque<byte[]> frames = new ArrayDeque<>();   // guarded by lock: BYTES frames, the first from at
        private int at;               // guarded by lock: the offset in the first frame
        private int end;              // guarded by lock: where the first frame's bytes end
        private long received;        // guarded by lock
        private long buffered;        // guarded by lock
        private boolean ended;        // guarded by lock: every byte came, or it failed
        private RuntimeException failure;   // guarded by lock
        private boolean holding;      // guarded by lock: its link is held for it
        private boolean cancelled;    // guarded by lock: its consumer closed it

        Read(long id, BlobKey key, String owner, long length, Dialed on, Runnable done) {
            this.id = id;
            this.key = key;
            this.length = length;
            this.on = on;
            this.done = done;
        }

        /** {@code n} more bytes of the record at {@code off} in {@code frame}, on the link's event loop. */
        void bytes(byte[] frame, int off, int n) throws PeerRefusedException {
            lock.lock();
            try {
                if (received + n > length) throw new PeerRefusedException("BYTES beyond the " + length + " bytes READ " + id + " asked for");
                received += n;
                if (!cancelled) {
                    if (frames.isEmpty()) {
                        at = off;
                        end = off + n;
                    }
                    frames.add(frame);
                    buffered += n;
                    if (buffered >= PAUSE_AT && !holding) {
                        holding = true;
                        on.link.hold();
                    }
                }
                if (received == length) end(null);
                arrived.signalAll();
            } finally {
                lock.unlock();
            }
        }

        /** Fails the read with {@code why}, unless every byte came already. */
        void fail(RuntimeException why) {
            lock.lock();
            try {
                if (ended) return;
                end(why);
                arrived.signalAll();
            } finally {
                lock.unlock();
            }
        }

        /** Under the lock: the read is over on the wire, its permit given back. */
        private void end(RuntimeException why) {
            ended = true;
            failure = why;
            on.reads.remove(id);
            done.run();
        }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            if (len == 0) return 0;   // InputStream: no byte asked, none waited for (readNBytes asks for 0 once it has them all, R6)
            lock.lock();
            try {
                while (frames.isEmpty() && !ended) arrived.awaitUninterruptibly();
                if (frames.isEmpty()) {
                    if (failure != null) throw failure;
                    return -1;
                }
                int k = Math.min(len, end - at);
                System.arraycopy(frames.peekFirst(), at, b, off, k);
                at += k;
                buffered -= k;
                on.link.taken();
                if (at == end) {
                    frames.removeFirst();
                    byte[] next = frames.peekFirst();
                    if (next != null) {
                        at = Frames.HEAD + 8;
                        end = next.length - Frames.TAG;
                    }
                }
                if (holding && buffered <= PAUSE_AT / 2) {
                    holding = false;
                    on.link.unhold();
                }
                return k;
            } finally {
                lock.unlock();
            }
        }

        @Override public void close() {
            lock.lock();
            try {
                cancelled = true;
                frames.clear();
                buffered = 0;
                if (holding) {
                    holding = false;
                    on.link.unhold();
                }
            } finally {
                lock.unlock();
            }
        }
    }
}
