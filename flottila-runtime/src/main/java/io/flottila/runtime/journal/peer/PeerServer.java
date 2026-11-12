package io.flottila.runtime.journal.peer;

import io.flottila.api.Timer;
import io.flottila.runtime.journal.Journal;
import io.flottila.runtime.journal.JournalRef;
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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * A node's peer link listener (CORE-5 S1, S2; 5a: the read side). Each connection is a {@link PeerLink} whose HELLO must prove
 * the environment's master key; on it, READ(id, owner, journal id, n, pos, len) of a record of this node's own journal is
 * answered with BYTES frames of at most 1 MiB each, the record's bytes as they lie in the segment (the reader checks them), and
 * a READ naming another owner, another journal id (W3) or a record this journal cannot hold answers MISS — an answer, not a
 * protocol error: the link stays up. Each READ is served on a virtual thread of its own, at most {@code FLOTTILA_PEER_READS}
 * at a time per link and at most two frames of one READ written ahead of the socket (the reader's flow control holds the
 * rest back); a dialer with more than {@value PeerConfig#READS_MAX} READs open breaks the protocol and its link closes. At most
 * {@value PeerConfig#HANDSHAKES_MAX} connections are held in their handshake (not yet proven) at a time: a further one is closed
 * at once (r2 R1), and so is one a link cannot open on (r2 R2).
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

    private PeerServer(NetServer server, PeerConfig config, Supplier<PeerIdentity> self, Journal journal, Timer timer) {
        this.server = server;
        this.config = config;
        this.self = self;
        this.journal = journal;
        this.timer = timer;
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
        PeerServer s = new PeerServer(vertx.createNetServer(new NetServerOptions().setHost(config.bind()).setPort(config.port()).setTcpNoDelay(true)),
                Objects.requireNonNull(config, "config"), Objects.requireNonNull(self, "self"), Objects.requireNonNull(journal, "journal"),
                Objects.requireNonNull(timer, "timer"));
        s.server.connectHandler(s::connected);
        return s.server.listen().map(s);
    }

    /** The port it listens on: the actual one when {@code FLOTTILA_PEER_PORT} is 0. */
    public int port() {
        return server.actualPort();
    }

    /** Stops listening and closes every link; a READ being served ends with its link. */
    public Future<Void> close() {
        for (PeerLink l : links) l.close(new IOException("the peer link's server closes"));
        return server.close();
    }

    private void connected(NetSocket socket) {
        if (handshakes.incrementAndGet() > PeerConfig.HANDSHAKES_MAX) {
            handshakes.decrementAndGet();
            LOG.log(System.Logger.Level.WARNING, "a peer connection from {0} is closed: {1} are in their handshake already",
                    socket.remoteAddress(), PeerConfig.HANDSHAKES_MAX);
            socket.close();
            return;
        }
        PeerLink link;
        try {
            PeerIdentity now = self.get();
            link = PeerLink.open(socket, PeerHandshake.listener(now, this::nonce), timer, config.timeout(), new Serving(now));
        } catch (RuntimeException | Error e) {
            handshakes.decrementAndGet();
            LOG.log(System.Logger.Level.WARNING, "a peer connection from " + socket.remoteAddress() + " is closed: its link could not open", e);
            socket.close();
            return;
        }
        links.add(link);
        link.established().onComplete(proven -> handshakes.decrementAndGet());   // proven, or closed first: once either way
    }

    private byte[] nonce() {
        byte[] n = new byte[PeerHandshake.NONCE];
        random.nextBytes(n);
        return n;
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
            links.remove(link);
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
