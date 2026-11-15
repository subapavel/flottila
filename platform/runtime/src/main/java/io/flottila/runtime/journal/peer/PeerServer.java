package io.flottila.runtime.journal.peer;

import io.flottila.api.Timer;
import io.flottila.runtime.journal.Journal;
import io.flottila.runtime.journal.JournalRef;
import io.flottila.runtime.journal.Replicas;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * A node's peer link listener (CORE-5 S1, S2; 5a: the read side). Each connection is a {@link PeerLink} whose HELLO must prove
 * the environment's master key; on it, READ(id, owner, journal id, n, pos, len) of a record of this node's own journal is
 * answered with BYTES frames of at most 1 MiB each, the record's bytes as they lie in the segment (the reader checks them), and
 * a READ naming another owner, another journal id (W3) or a record this journal cannot hold answers MISS — an answer, not a
 * protocol error: the link stays up. Each READ is served on a virtual thread of its own, at most {@code FLOTTILA_PEER_READS}
 * at a time per link and at most two frames of one READ written ahead of the socket (the reader's flow control holds the
 * rest back); a dialer with more than {@value PeerConfig#READS_MAX} READs open breaks the protocol and its link closes. At most
 * {@value PeerConfig#HANDSHAKES_MAX} connections are held in their handshake (not yet proven) at a time, at most
 * {@value PeerConfig#HANDSHAKES_PER_SOURCE} of them from one source address (CORE-5a2 P4): a further one is closed at once
 * (r2 R1), and so is one a link cannot open on (r2 R2); a link gives its places back once established, refused or closed.
 *
 * <p>The link's channel is the dialer's (CORE-5b1 C1): read links are served as above; a repl link is an owner's, whose
 * segments it holds ({@link Holder}, into {@link Journal#replicas()}). A repl link is closed after its proof when its HELLO's node
 * is not a DNS label (C8) or its epoch is below the owner's bound — the highest epoch accepted from that owner and
 * {@code epochOf(owner)} (the renew's, a lower bound: below 1 none), checked again at every OPEN (C6); else it supersedes the
 * owner's repl link — whose holder is stopped and whose socket is reset, its unsent bytes dropped, so an owner that reads none
 * of them cannot hold it (r1) — and takes its first frame once that one's writer stopped (A8).
 *
 * @since 0.1
 */
public final class PeerServer {

    private static final System.Logger LOG = System.getLogger(PeerServer.class.getName());

    private final NetServer server;
    private final PeerConfig config;
    private final Supplier<PeerIdentity> self;
    private final Journal journal;
    private final Timer timer;
    private final SecureRandom random = new SecureRandom();
    private final Set<PeerLink> links = ConcurrentHashMap.newKeySet();
    private final AtomicInteger handshakes = new AtomicInteger();   // the links not yet established, not yet closed
    private final ConcurrentHashMap<String, Integer> sources = new ConcurrentHashMap<>();   // the same per source address; no entry: 0
    private final ToLongFunction<String> epochOf;
    private final Object holding = new Object();
    private final Map<String, Holder> holders = new HashMap<>();   // guarded by holding: each owner's latest repl link
    private final Map<String, Long> accepted = new HashMap<>();    // guarded by holding: the highest epoch accepted of each owner
    private final Set<Holder> running = ConcurrentHashMap.newKeySet();   // the holders not stopped yet

    private PeerServer(NetServer server, PeerConfig config, Supplier<PeerIdentity> self, Journal journal, Timer timer, ToLongFunction<String> epochOf) {
        this.server = server;
        this.config = config;
        this.self = self;
        this.journal = journal;
        this.timer = timer;
        this.epochOf = epochOf;
    }

    /** Listens on {@code config}'s bind address and port (0: any free one) for the links of the other nodes to {@code self}. */
    public static Future<PeerServer> listen(Vertx vertx, PeerConfig config, PeerIdentity self, Journal journal, Timer timer) {
        Objects.requireNonNull(self, "self");
        return listen(vertx, config, () -> self, journal, timer);
    }

    /**
     * {@link #listen(Vertx, PeerConfig, PeerIdentity, Journal, Timer)} as the identity {@code self} answers when a link opens: a
     * node listens before its join, whose epoch its HELLO names from then on (a link opened before the join names the one before).
     */
    public static Future<PeerServer> listen(Vertx vertx, PeerConfig config, Supplier<PeerIdentity> self, Journal journal, Timer timer) {
        return listen(vertx, config, self, journal, timer, owner -> 0);
    }

    /**
     * {@link #listen(Vertx, PeerConfig, Supplier, Journal, Timer)} with the epochs the membership's renew found for the other
     * nodes ({@code epochOf}, below 1: none known): a repl link of an epoch below its owner's is closed.
     */
    public static Future<PeerServer> listen(Vertx vertx, PeerConfig config, Supplier<PeerIdentity> self, Journal journal, Timer timer,
                                            ToLongFunction<String> epochOf) {
        PeerServer s = new PeerServer(vertx.createNetServer(new NetServerOptions().setHost(config.bind()).setPort(config.port()).setTcpNoDelay(true)),
                Objects.requireNonNull(config, "config"), Objects.requireNonNull(self, "self"), Objects.requireNonNull(journal, "journal"),
                Objects.requireNonNull(timer, "timer"), Objects.requireNonNull(epochOf, "epochOf"));
        s.server.connectHandler(s::connected);
        return s.server.listen().map(s);
    }

    /** The port it listens on: the actual one when {@code FLOTTILA_PEER_PORT} is 0. */
    public int port() {
        return server.actualPort();
    }

    /**
     * Stops listening and closes every link; a READ being served ends with its link. Completes once every holder stopped, its
     * replicas detached (a write or force in progress returns first).
     */
    public Future<Void> close() {
        for (PeerLink l : links) l.close(new IOException("the peer link's server closes"));
        List<Future<Void>> stopping = new ArrayList<>();
        for (Holder h : running) {
            h.stop();
            stopping.add(h.stopped());
        }
        return server.close().compose(v -> Future.join(stopping).mapEmpty());
    }

    private void connected(NetSocket socket) {
        String source = socket.remoteAddress().hostAddress();
        if (!take(source)) {
            LOG.log(System.Logger.Level.WARNING, "a peer connection from {0} is closed: {1} from its address are in their handshake already",
                    socket.remoteAddress(), PeerConfig.HANDSHAKES_PER_SOURCE);
            socket.close();
            return;
        }
        if (handshakes.incrementAndGet() > PeerConfig.HANDSHAKES_MAX) {
            handshakes.decrementAndGet();
            giveBack(source);
            LOG.log(System.Logger.Level.WARNING, "a peer connection from {0} is closed: {1} are in their handshake already",
                    socket.remoteAddress(), PeerConfig.HANDSHAKES_MAX);
            socket.close();
            return;
        }
        PeerLink link;
        try {
            PeerIdentity now = self.get();
            link = PeerLink.open(socket, PeerHandshake.listener(now, this::nonce), timer, config.timeout(), new Listening(now));
        } catch (RuntimeException | Error e) {
            handshakes.decrementAndGet();
            giveBack(source);
            LOG.log(System.Logger.Level.WARNING, "a peer connection from " + socket.remoteAddress() + " is closed: its link could not open", e);
            socket.close();
            return;
        }
        links.add(link);
        link.established().onComplete(proven -> {   // proven, or refused or closed first: once either way
            handshakes.decrementAndGet();
            giveBack(source);
        });
    }

    /** Takes a place of {@code source}'s {@value PeerConfig#HANDSHAKES_PER_SOURCE}: false when they are all taken. */
    private boolean take(String source) {
        boolean[] taken = {false};
        sources.compute(source, (s, n) -> {
            int held = n == null ? 0 : n;
            if (held >= PeerConfig.HANDSHAKES_PER_SOURCE) return n;
            taken[0] = true;
            return held + 1;
        });
        return taken[0];
    }

    /** Gives a place of {@code source}'s back; its entry goes with its last. */
    private void giveBack(String source) {
        sources.computeIfPresent(source, (s, n) -> n == 1 ? null : n - 1);
    }

    /** The number of source addresses with a place taken (for the tests); safe from any thread, as {@code sources} is. */
    int sourcesHeld() {
        return sources.size();
    }

    private byte[] nonce() {
        byte[] n = new byte[PeerHandshake.NONCE];
        random.nextBytes(n);
        return n;
    }

    /** A link's receiver: its READs or its replicas, by the channel the dialer named. */
    private final class Listening implements PeerLink.Receiver {
        private final PeerIdentity self;
        private volatile PeerLink.Receiver on;   // once established

        Listening(PeerIdentity self) {
            this.self = self;
        }

        @Override public void established(PeerLink link) throws PeerRefusedException {
            on = link.channel() == PeerHandshake.REPL ? hold(link) : new Serving(self);
        }

        @Override public void received(PeerLink link, int type, byte[] frame) throws PeerRefusedException {
            on.received(link, type, frame);
        }

        @Override public void closed(PeerLink link, IOException why) {
            links.remove(link);
            PeerLink.Receiver r = on;
            if (r != null) r.closed(link, why);
        }

        @Override public boolean waiting() {
            return on.waiting();   // asked of an established link only
        }
    }

    /** A repl link's holder, once its owner and epoch hold (see the class comment); it supersedes the owner's earlier one. */
    private Holder hold(PeerLink link) throws PeerRefusedException {
        String owner = link.peerNode();
        if (!Replicas.NODE_ID.matcher(owner).matches()) {   // C8: an owner is one directory under replicas/
            throw new PeerRefusedException("a repl link of node '" + owner + "': a node id is a DNS label of at most 63 lower-case letters, digits and inner dashes");
        }
        long epoch = link.peerEpoch();
        synchronized (holding) {
            long least = bound(owner);
            if (epoch < least) {
                throw new PeerRefusedException("a repl link of node " + owner + "'s epoch " + epoch + ": this holder knows epoch " + least + " of it");
            }
            accepted.merge(owner, epoch, Math::max);
            Holder old = holders.get(owner);
            Holder h = new Holder(link, journal.replicas(), this::bound, old == null ? Future.succeededFuture() : old.stopped());
            holders.put(owner, h);
            running.add(h);
            h.stopped().onComplete(v -> stopped(owner, h));
            if (old != null) {   // ended for certain (r1): marked closing, its writer stopped, then its socket reset — in this order,
                // so the writer sends nothing more and its close once stopped queues no graceful close, whichever loop runs the reset
                old.link().abandon(new IOException("superseded by a repl link of node " + owner + "'s epoch " + epoch));
                old.stop();
                old.link().reset();
            }
            return h;
        }
    }

    /** The least epoch a repl link of {@code owner} may have now: the highest accepted, or the renew's when higher. */
    private long bound(String owner) {
        synchronized (holding) {
            return Math.max(accepted.getOrDefault(owner, 0L), epochOf.applyAsLong(owner));
        }
    }

    private void stopped(String owner, Holder h) {
        running.remove(h);
        synchronized (holding) {
            holders.remove(owner, h);
        }
    }

    /** One link's READs. */
    private final class Serving implements PeerLink.Receiver {
        private final PeerIdentity self;
        private final Semaphore serving = new Semaphore(config.reads(), true);
        private final AtomicInteger open = new AtomicInteger();

        Serving(PeerIdentity self) {
            this.self = self;
        }

        @Override public void received(PeerLink link, int type, byte[] frame) throws PeerRefusedException {
            if (type != Frames.READ) throw new PeerRefusedException("a frame of type " + type + " to a listener");
            ByteBuffer p = Frames.payload(frame);
            int ownerLength = p.remaining() >= 10 ? Short.toUnsignedInt(p.getShort(8)) : -1;   // -1: no READ is that short
            if (p.remaining() != 8 + 2 + ownerLength + 16 + 8 + 8 + 8) throw new PeerRefusedException("a READ of " + p.remaining() + " bytes");
            long id = p.getLong();
            byte[] owner = new byte[p.getShort() & 0xFFFF];
            p.get(owner);
            UUID of = new UUID(p.getLong(), p.getLong());
            JournalRef ref = new JournalRef(p.getLong(), p.getLong(), p.getLong());
            if (open.incrementAndGet() > PeerConfig.READS_MAX) {
                throw new PeerRefusedException("more than " + PeerConfig.READS_MAX + " READs open on one link");
            }
            Thread.ofVirtual().name("flottila-peer-serve").start(() -> {
                serving.acquireUninterruptibly();
                try {
                    serve(self, link, id, new String(owner, StandardCharsets.UTF_8), of, ref);
                } finally {
                    serving.release();
                    open.decrementAndGet();
                }
            });
        }

        @Override public void closed(PeerLink link, IOException why) {
            // its READs end with the link: each fails its next write
        }

        @Override public boolean waiting() {
            return false;
        }
    }

    /** Answers one READ: the record's bytes in BYTES frames, or MISS. */
    private void serve(PeerIdentity self, PeerLink link, long id, String owner, UUID of, JournalRef ref) {
        if (!self.node().equals(owner) || !self.journal().equals(of)) {
            miss(link, id, "it names " + owner + "'s journal " + of + ", this is " + self.node() + "'s journal " + self.journal());
            return;
        }
        try (InputStream raw = journal.raw(ref)) {
            Future<Void> ahead = Future.succeededFuture();
            for (long left = ref.length(); left > 0; ) {
                int k = (int) Math.min(Frames.BYTES_MAX, left);
                byte[] f = Frames.frame(Frames.BYTES, 8 + k);
                ByteBuffer.wrap(f).putLong(Frames.HEAD, id);
                if (raw.readNBytes(f, Frames.HEAD + 8, k) < k) throw new IOException("segment " + ref.segment() + " ends inside the record at " + ref);
                ahead.await();   // the frame before this one went out: two at most ahead of the socket
                ahead = link.send(f);
                left -= k;
            }
        } catch (IOException | RuntimeException e) {   // a write of a closed link fails too: its MISS goes nowhere
            miss(link, id, String.valueOf(e.getMessage()));
        }
    }

    private void miss(PeerLink link, long id, String why) {
        LOG.log(System.Logger.Level.DEBUG, "READ {0} from {1}: MISS ({2})", id, link.remote(), why);
        byte[] f = Frames.frame(Frames.MISS, 8);
        ByteBuffer.wrap(f).putLong(Frames.HEAD, id);
        link.send(f);
    }
}
