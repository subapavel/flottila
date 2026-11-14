package io.flottila.runtime.journal.peer;

import io.flottila.api.Timer;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.internal.net.NetSocketInternal;
import io.vertx.core.net.NetSocket;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One peer link's socket (CORE-5 S1, S2), either side: on open it sends its HELLO, answers the peer's HELLO with its proof and
 * is established once the peer's proof holds; every later frame is checked (sequence, tag) on the socket's event loop before
 * its {@link Receiver} sees it, and a frame that fails — a bad tag, a length out of range, anything the protocol refuses — closes
 * the link. Until the link is proven a frame is at most a HELLO long, after the peer's HELLO at most a proof (r2 R1): a longer
 * length is refused at its four bytes. Writes go out sealed in sequence order under one lock, from any thread.
 *
 * <p>A watchdog on the injected timer closes the link when it is not established within {@code FLOTTILA_PEER_TIMEOUT}, and
 * when the receiver waits for an answer and nothing moved — no bytes, no new wait, no resume, no bytes taken by a read's
 * consumer — for a whole timeout. A link its reader paused is no exception (r2 R3): one whose consumer takes nothing for a
 * whole timeout closes and its reads fail; one whose consumer still drains stays.
 */
final class PeerLink {

    /** What a link's frames are for: the reads of a dialer, the serving or the holding of a listener. */
    interface Receiver {
        /**
         * The peer's proof held, on the link's event loop, before {@link #established()} completes and before any later frame;
         * a throw closes the link (CORE-5b1: a repl link's owner and epoch are checked here).
         */
        default void established(PeerLink link) throws PeerRefusedException { }

        /** A checked frame of {@code type}, on the link's event loop; a throw closes the link. */
        void received(PeerLink link, int type, byte[] frame) throws PeerRefusedException;

        /** The link closed, once, for {@code why}. */
        void closed(PeerLink link, IOException why);

        /** Whether it waits for an answer on the link (the watchdog's question). */
        boolean waiting();
    }

    private static final System.Logger LOG = System.getLogger(PeerLink.class.getName());

    private final NetSocket socket;
    private final Context context;
    private final PeerHandshake handshake;
    private final Receiver receiver;
    private final Timer timer;
    private final Duration timeout;
    private final Frames.Decoder decoder = new Frames.Decoder(PeerHandshake.HELLO_LEN_MAX);
    private final Promise<PeerLink> established = Promise.promise();
    private final Object writes = new Object();
    private final AtomicLong progress = new AtomicLong();
    private PeerSession session;          // the event loop's, once the peer's HELLO came; sealing is under writes
    private boolean proven;               // event loop: the peer's proof held
    private IOException closing;          // guarded by this: why it closes, once
    private int full;                     // guarded by this: the reads whose buffer is full
    private Timer.Cancellable watchdog;   // guarded by this
    private long seen;                    // the watchdog's: progress at its last look

    private PeerLink(NetSocket socket, Context context, PeerHandshake handshake, Timer timer, Duration timeout, Receiver receiver) {
        this.socket = socket;
        this.context = context;
        this.handshake = handshake;
        this.timer = timer;
        this.timeout = timeout;
        this.receiver = receiver;
    }

    /**
     * Opens a link on {@code socket}, on its context (inside its connect handler or callback): its HELLO goes out now. A throw
     * (no context, the timer refusing the watchdog) leaves the socket without a handler of the link: its caller closes it.
     */
    static PeerLink open(NetSocket socket, PeerHandshake handshake, Timer timer, Duration timeout, Receiver receiver) {
        PeerLink link = new PeerLink(socket, Objects.requireNonNull(io.vertx.core.Vertx.currentContext(), "a link opens on its socket's context"),
                handshake, timer, timeout, receiver);
        link.arm();   // first: before the HELLO goes out, the timeout runs from before the peer can see a byte of this link
        socket.handler(link::received);
        socket.exceptionHandler(t -> link.close(new IOException("the peer link failed: " + t, t)));
        socket.closeHandler(v -> link.ended());
        socket.write(handshake.hello());
        return link;
    }

    /** Completes once the peer's proof held; fails if the link closes first. */
    Future<PeerLink> established() {
        return established.future();
    }

    /** Seals {@code frame} (from {@link Frames#frame}, its payload filled) and writes it; the write's future, failed once the link closes. */
    Future<Void> send(byte[] frame) {
        synchronized (writes) {
            synchronized (this) {
                if (closing != null) return Future.failedFuture(closing);
            }
            return socket.write(Buffer.buffer(session.seal(frame)));
        }
    }

    /**
     * Closes the link for {@code why} (the first reason wins): what it sent goes out first, then the socket closes (a peer
     * that reads none of it keeps it open); its receiver hears of it once the socket closed.
     */
    void close(IOException why) {
        if (ending(why)) socket.close();
    }

    /**
     * Marks the link closing for {@code why} (the first reason wins) without touching its socket: a send fails from now on and
     * a later {@link #close} queues nothing; {@link #reset} then ends the socket (CORE-5b1 r1: a superseded repl link).
     */
    void abandon(IOException why) {
        ending(why);
    }

    /**
     * Resets the link's socket (SO_LINGER 0) on its own event loop, a closing one too, what it had not sent yet dropped, so a
     * peer that reads none of it cannot keep it open; its receiver hears of it as of any close. A link still open is not
     * marked closing by it: {@link #abandon} first.
     */
    void reset() {
        ChannelHandlerContext ctx = ((NetSocketInternal) socket).channelHandlerContext();
        context.runOnContext(v -> {
            if (!ctx.channel().isOpen()) return;   // closed already (its peer closed it, or a close went through): no option on it
            ctx.channel().config().setOption(ChannelOption.SO_LINGER, 0);
            ctx.close();   // from Vert.x's own handler on: past its close, which waits for the flush
        });
    }

    /** Marks the link closing for {@code why}: true for the first reason, its watchdog cancelled and its establishment failed. */
    private boolean ending(IOException why) {
        Timer.Cancellable w;
        synchronized (this) {
            if (closing != null) return false;
            closing = why;
            w = watchdog;
        }
        w.cancel();   // armed when the link opened: never null
        established.tryFail(why);
        return true;
    }

    /** Whether the link closed or is closing. */
    synchronized boolean closed() {
        return closing != null;
    }

    /** A wait began on the link: the watchdog counts it as progress. */
    void waits() {
        progress.incrementAndGet();
    }

    /** A read's consumer took bytes of the link (any thread): the watchdog counts it as progress, the link paused or not. */
    void taken() {
        progress.incrementAndGet();
    }

    /** A read's buffer is full (on the event loop): the socket is paused until no read's is. */
    synchronized void hold() {
        full++;
        socket.pause();   // pausing a paused socket changes nothing
    }

    /** A read's buffer has room again (any thread): the socket resumes, on its event loop, once no read's is full. */
    void unhold() {
        synchronized (this) {
            full--;
        }
        context.runOnContext(v -> {
            synchronized (this) {
                if (full > 0) return;   // another read's buffer is still full
                progress.incrementAndGet();
                socket.resume();
            }
        });
    }

    /** The link's channel (1 read, 2 repl): the one the dialer's HELLO named; a listener's once the peer's HELLO came. */
    int channel() {
        return handshake.channel();
    }

    /** The node the peer's HELLO named (once it came). */
    String peerNode() {
        return handshake.peerNode();
    }

    /** The epoch the peer's HELLO named (once it came). */
    long peerEpoch() {
        return handshake.peerEpoch();
    }

    /** The journal id the peer's HELLO named (once it came). */
    java.util.UUID peerJournal() {
        return handshake.peerJournal();
    }

    /** The peer's address, for a log line. */
    String remote() {
        return String.valueOf(socket.remoteAddress());
    }

    private void received(Buffer chunk) {
        progress.incrementAndGet();
        decoder.feed(chunk);
        try {
            for (byte[] f; (f = decoder.next()) != null && !closed(); ) {
                if (session == null) {
                    Buffer proof = handshake.proof(Buffer.buffer(f));
                    session = handshake.sessionOf();
                    decoder.longest(PeerHandshake.PROOF_LEN);   // the peer's proof comes next
                    socket.write(proof);
                } else if (!proven) {
                    handshake.accept(Buffer.buffer(f));
                    proven = true;
                    decoder.longest(Frames.MAX_LEN);
                    receiver.established(this);
                    established.tryComplete(this);
                } else {
                    session.open(f);
                    receiver.received(this, Frames.type(f), f);
                }
            }
        } catch (PeerRefusedException refused) {
            LOG.log(System.Logger.Level.WARNING, "the peer link with {0} is closed: {1}", remote(), refused.getMessage());
            close(refused);
        }
    }

    /** The socket closed, by either side: the receiver hears it once. */
    private void ended() {
        IOException why;
        synchronized (this) {
            if (closing == null) closing = new IOException("the peer link was closed by the peer " + remote());
            why = closing;
            watchdog.cancel();
        }
        established.tryFail(why);
        receiver.closed(this, why);
    }

    private synchronized void arm() {
        if (closing == null) watchdog = timer.schedule(timeout, this::watch);
    }

    /** The watchdog's look, every timeout: a link not established, or waiting without progress (paused or not, R3), closes. */
    private void watch() {
        long now = progress.get();
        boolean stalled = !established.future().succeeded() || (receiver.waiting() && now == seen);
        seen = now;
        if (stalled) {
            close(new IOException("the peer link with " + remote() + (established.future().succeeded() ? " gave no answer" : " was not established")
                    + " within FLOTTILA_PEER_TIMEOUT " + timeout));
        } else {
            arm();
        }
    }
}
