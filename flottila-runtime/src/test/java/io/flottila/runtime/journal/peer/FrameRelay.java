package io.flottila.runtime.journal.peer;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A loopback TCP relay between a dialing node (B, the reader) and a listening node (A, its {@code PeerServer}) that reads the
 * peer link's frames as they pass (CORE-5a, the counting local server of the negative cases). A frame is
 * {@code u32 len · u8 type · u64 seq · payload · tag[16]}, big-endian, {@code len} counting every byte after the u32 (packet
 * S2; the shape these tests assume) — the relay needs nothing else of the protocol: it never parses a type, a payload or a tag.
 * Bytes are forwarded unchanged, one frame at a time, unless a case arms a rule on one direction: {@link Pipe#hold()} queues
 * the frames, {@link Pipe#flipLastBitOfNext()} flips bit 0 of the next frame's last byte (its tag), {@link Pipe#inject} writes
 * raw bytes between two frames, {@link Pipe#holdAfter(int)} lets n more frames through and holds the rest. Each direction runs
 * on a virtual thread of its own that writes in slices of {@value #SLICE} bytes, so a write the receiver does not read parks
 * it ({@link Pipe#parkedWriting()}). Every connection's close is recorded with the side that closed first.
 */
public final class FrameRelay implements AutoCloseable {

    /** The side a connection's first EOF (or reset) came from; {@code RELAY}: the case cut it ({@link #cut}) or closed the relay. */
    public enum Side { DIALER, LISTENER, RELAY }

    static final int SLICE = 64 * 1024;
    /** Every wait of this fixture is bounded by this: a failure detector, never a timing. */
    static final long BOUND_SECONDS = 20;

    private final String upstreamHost;
    private final int upstreamPort;
    private final ServerSocket server;
    private final Thread acceptor;
    private final List<Connection> connections = new CopyOnWriteArrayList<>();
    /** Applied to every connection's direction toward the listener (B → A) and toward the dialer (A → B). */
    private final Pipe toListener = new Pipe("B->A");
    private final Pipe toDialer = new Pipe("A->B");
    private volatile boolean closed;

    public FrameRelay(String upstreamHost, int upstreamPort) throws IOException {
        this.upstreamHost = upstreamHost;
        this.upstreamPort = upstreamPort;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.acceptor = Thread.ofVirtual().name("frame-relay-accept").start(this::accept);
    }

    /** The relay's port on 127.0.0.1. */
    public int port() { return server.getLocalPort(); }

    /** {@code 127.0.0.1:<port>}: the address a reader is given for the listener behind this relay. */
    public String address() { return "127.0.0.1:" + port(); }

    /** The frames from the dialer to the listener (B's HELLO, READ). */
    public Pipe toListener() { return toListener; }

    /** The frames from the listener to the dialer (A's HELLO, BYTES, MISS). */
    public Pipe toDialer() { return toDialer; }

    /** How many connections the relay accepted from dialers. */
    public int connections() { return connections.size(); }

    /** The side whose close ended connection {@code i} (0 = the first accepted), waiting (bounded) for it. */
    public Side awaitClosedBy(int i, String what) {
        until(() -> connections.size() > i && connections.get(i).closedBy != null,
                () -> what + ": connection " + i + " still open (connections " + connections.size() + "); " + describe());
        return connections.get(i).closedBy;
    }

    /** The dialer's port of connection {@code i} (its ephemeral one): with {@link #port()}, the dialer's socket in /proc/net/tcp. */
    public int dialerPort(int i) { return connections.get(i).down.getPort(); }

    /** Whether connection {@code i} has closed. */
    public boolean closed(int i) { return connections.size() > i && connections.get(i).closedBy != null; }

    /** Closes connection {@code i} on both sides at once (the owner's host is gone mid-stream). */
    public void cut(int i) {
        Connection c = connections.get(i);
        c.close(null);
    }

    public String describe() {
        return "toListener " + toListener.describe() + ", toDialer " + toDialer.describe() + ", connections " + connections.size();
    }

    private void accept() {
        while (!closed) {
            Socket down;
            try {
                down = server.accept();
            } catch (IOException e) {
                return;
            }
            Connection c = new Connection(down);
            connections.add(c);
            try {
                c.up = new Socket(upstreamHost, upstreamPort);
            } catch (IOException refused) {
                c.close(Side.LISTENER);   // nothing listens upstream: the listener's side is gone
                continue;
            }
            c.start();
        }
    }

    @Override public void close() throws IOException {
        closed = true;
        toListener.release();
        toDialer.release();
        server.close();
        for (Connection c : connections) c.close(null);
        acceptor.interrupt();
    }

    static void until(BooleanSupplier condition, Supplier<String> what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOUND_SECONDS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) throw new AssertionError("not within " + BOUND_SECONDS + " s: " + what.get());
            Thread.onSpinWait();
        }
    }

    /** One relayed connection: the dialer's socket and the listener's, a pump each way. */
    private final class Connection {
        final Socket down;
        volatile Socket up;
        volatile Side closedBy;

        Connection(Socket down) { this.down = down; }

        void start() {
            Thread.ofVirtual().name("frame-relay-B->A").start(() -> pump(down, up, toListener, Side.DIALER));
            Thread.ofVirtual().name("frame-relay-A->B").start(() -> pump(up, down, toDialer, Side.LISTENER));
        }

        private void pump(Socket from, Socket to, Pipe pipe, Side fromSide) {
            pipe.pumps.add(Thread.currentThread());
            try {
                DataInputStream in = new DataInputStream(from.getInputStream());
                OutputStream out = to.getOutputStream();
                pipe.outputs.add(out);
                pipe.inputs.add(from.getInputStream());
                while (true) {
                    long len;
                    byte[] head = new byte[4];
                    try {
                        in.readFully(head);
                    } catch (EOFException eof) {
                        close(fromSide);
                        return;
                    }
                    len = ((head[0] & 0xFFL) << 24) | ((head[1] & 0xFFL) << 16) | ((head[2] & 0xFFL) << 8) | (head[3] & 0xFFL);
                    if (len > (256L << 20)) throw new IOException(pipe.name + ": a frame of " + len + " bytes: not the framing these tests assume");
                    byte[] frame = new byte[(int) (4 + len)];
                    System.arraycopy(head, 0, frame, 0, 4);
                    in.readFully(frame, 4, (int) len);
                    pipe.read.incrementAndGet();
                    pipe.forward(out, frame);
                }
            } catch (IOException | InterruptedException e) {
                close(pipe.refused() != null ? Side.RELAY : fromSide);
            }
        }

        synchronized void close(Side side) {
            if (closedBy == null) closedBy = side == null ? Side.RELAY : side;
            closeQuietly(down);
            if (up != null) closeQuietly(up);
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // the relay's own socket, already closing: nothing to report
        }
    }

    /** The rules and counts of one direction, over every connection. */
    public static final class Pipe {
        final String name;
        final AtomicInteger read = new AtomicInteger();
        final AtomicInteger forwarded = new AtomicInteger();
        final AtomicLong bytes = new AtomicLong();
        final ArrayDeque<Object[]> held = new ArrayDeque<>();
        final List<Thread> pumps = new CopyOnWriteArrayList<>();
        final List<OutputStream> outputs = new CopyOnWriteArrayList<>();
        final List<InputStream> inputs = new CopyOnWriteArrayList<>();
        private boolean holding;           // guarded by this
        private int passBeforeHold = -1;   // guarded by this: frames still let through before the hold (-1: none armed)
        private boolean flipNext;          // guarded by this
        private volatile boolean writing;
        private volatile Supplier<String> refuse;
        private volatile String refusedWhy;

        Pipe(String name) { this.name = name; }

        /**
         * Before each frame is forwarded, {@code check} runs; when it answers a reason, that frame and every later one is not
         * forwarded, the reason is kept ({@link #refused()}) and the connection closes.
         */
        public void refuseWhen(Supplier<String> check) { refuse = check; }

        /** The reason {@link #refuseWhen}'s check gave, or null while it gave none. */
        public String refused() { return refusedWhy; }

        /** Frames read from this direction's sender so far. */
        public int frames() { return read.get(); }

        /** Frames written on to the receiver so far. */
        public int forwardedFrames() { return forwarded.get(); }

        /** Bytes written on to the receiver so far, counted slice by slice. */
        public long forwardedBytes() { return bytes.get(); }

        /** From now on frames are queued, not forwarded. */
        public synchronized void hold() { holding = true; }

        /** {@code n} more frames go through, then every later one is held. */
        public synchronized void holdAfter(int n) { passBeforeHold = n; }

        /** How many frames are held now. */
        public synchronized int heldFrames() { return held.size(); }

        /** Forwards every held frame in order and stops holding. */
        public void release() {
            ArrayDeque<Object[]> out;
            synchronized (this) {
                holding = false;
                passBeforeHold = -1;
                out = new ArrayDeque<>(held);
                held.clear();
            }
            for (Object[] f : out) {
                try {
                    write((OutputStream) f[0], (byte[]) f[1]);
                } catch (IOException | InterruptedException gone) {
                    // the receiver closed meanwhile: its pump records the close
                }
            }
        }

        /** Forwards every frame held now, in order, and goes on holding the later ones. */
        public void releaseHeld() {
            ArrayDeque<Object[]> out;
            synchronized (this) {
                out = new ArrayDeque<>(held);
                held.clear();
            }
            for (Object[] f : out) {
                try {
                    write((OutputStream) f[0], (byte[]) f[1]);
                } catch (IOException | InterruptedException gone) {
                    // the receiver closed meanwhile: its pump records the close
                }
            }
        }

        /** The next frame's last byte (the last byte of its tag) has bit 0 flipped. */
        public synchronized void flipLastBitOfNext() { flipNext = true; }

        /** Writes {@code raw} to the receiver of the latest connection now, between two frames. */
        public void inject(byte[] raw) throws IOException, InterruptedException {
            if (outputs.isEmpty()) throw new AssertionError(name + ": no connection to inject into");
            write(outputs.get(outputs.size() - 1), raw);
        }

        /** Whether this direction's latest pump is parked inside a write the receiver does not read (its socket full). */
        public boolean parkedWriting() {
            if (pumps.isEmpty()) return false;
            Thread t = pumps.get(pumps.size() - 1);
            return writing && t.getState() == Thread.State.WAITING;
        }

        /**
         * Whether this direction's latest pump has read everything its sender wrote so far and waits for more: parked, not
         * writing, nothing unread on its socket.
         */
        public boolean caughtUp() throws IOException {
            if (pumps.isEmpty()) return true;
            Thread t = pumps.get(pumps.size() - 1);
            return !writing && t.getState() == Thread.State.WAITING && inputs.get(inputs.size() - 1).available() == 0;
        }

        void forward(OutputStream out, byte[] frame) throws IOException, InterruptedException {
            Supplier<String> check = refuse;
            String why = refusedWhy != null ? refusedWhy : check == null ? null : check.get();
            if (why != null) {
                refusedWhy = why;
                throw new IOException(name + ": refused: " + why);
            }
            synchronized (this) {
                if (flipNext) {
                    flipNext = false;
                    frame[frame.length - 1] ^= 1;
                }
                if (passBeforeHold == 0) {
                    holding = true;
                    passBeforeHold = -1;
                } else if (passBeforeHold > 0) {
                    passBeforeHold--;
                }
                if (holding) {
                    held.add(new Object[] {out, frame});
                    return;
                }
            }
            write(out, frame);
        }

        private void write(OutputStream out, byte[] frame) throws IOException, InterruptedException {
            synchronized (out) {   // one frame at a time per receiver: an injection lands between two frames
                for (int off = 0; off < frame.length; off += SLICE) {
                    int n = Math.min(SLICE, frame.length - off);
                    writing = true;
                    try {
                        out.write(frame, off, n);
                    } finally {
                        writing = false;
                    }
                    bytes.addAndGet(n);
                }
                out.flush();
            }
            forwarded.incrementAndGet();
        }

        String describe() {
            return name + " read " + read.get() + ", forwarded " + forwarded.get() + " (" + bytes.get() + " bytes), held " + heldFrames();
        }
    }
}
