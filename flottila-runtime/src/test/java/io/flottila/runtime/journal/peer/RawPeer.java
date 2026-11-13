package io.flottila.runtime.journal.peer;

import io.vertx.core.buffer.Buffer;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.function.Supplier;

/**
 * One end of a peer link over a plain blocking socket, for the cases that need a peer that breaks the protocol on purpose
 * (CORE-5a): it runs the real handshake ({@link PeerHandshake}) as dialer or listener, then sends frames sealed by the real
 * session — any type, any payload, even a frame sent twice — and reads the frames the other side sends, checked by it. Reads
 * are bounded by {@value #BOUND_SECONDS} s: a failure detector, never a timing.
 */
public final class RawPeer implements AutoCloseable {

    public static final int READ = Frames.READ;
    public static final int BYTES = Frames.BYTES;
    public static final int MISS = Frames.MISS;
    static final int BOUND_SECONDS = 20;

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private PeerSession session;
    private byte[] last;
    private byte[] pending;   // the listener's HELLO, read by connect before any handshake

    private RawPeer(Socket socket) throws IOException {
        this.socket = socket;
        socket.setSoTimeout(BOUND_SECONDS * 1000);
        this.in = new DataInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    /** A dialer to the listener on 127.0.0.1:{@code port}, node {@code peer}, its handshake done. */
    public static RawPeer dial(int port, PeerIdentity self, String peer) throws IOException {
        RawPeer p = new RawPeer(new Socket(InetAddress.getLoopbackAddress(), port));
        p.handshake(PeerHandshake.dialer(self, peer, nonces()));
        return p;
    }

    /** A listener on the next connection {@code server} accepts, its handshake done. */
    public static RawPeer accept(ServerSocket server, PeerIdentity self) throws IOException {
        RawPeer p = new RawPeer(server.accept());
        p.handshake(PeerHandshake.listener(self, nonces()));
        return p;
    }

    /**
     * A connection to the listener on 127.0.0.1:{@code port} that says nothing: it waits (bounded) for the listener's HELLO — the
     * link is open on the listener, in its handshake — and keeps it for a later {@link #handshakeAsDialer}.
     */
    public static RawPeer connect(int port) throws IOException {
        RawPeer p = new RawPeer(new Socket(InetAddress.getLoopbackAddress(), port));
        p.pending = p.frame();
        return p;
    }

    /** {@link #dial(int, PeerIdentity, String)} from the local address {@code from} (a 127.0.0.x: one source address per x, CORE-5a2 P4). */
    public static RawPeer dial(InetAddress from, int port, PeerIdentity self, String peer) throws IOException {
        RawPeer p = new RawPeer(new Socket(InetAddress.getLoopbackAddress(), port, from, 0));
        p.handshake(PeerHandshake.dialer(self, peer, nonces()));
        return p;
    }

    /** {@link #connect(int)} from the local address {@code from} (a 127.0.0.x: one source address per x, CORE-5a2 P4). */
    public static RawPeer connect(InetAddress from, int port) throws IOException {
        RawPeer p = new RawPeer(new Socket(InetAddress.getLoopbackAddress(), port, from, 0));
        try {
            p.pending = p.frame();
        } catch (IOException closed) {
            p.close();
            throw closed;
        }
        return p;
    }

    /** Runs the dialer's side of the handshake on a {@link #connect}ed peer, as {@code self} dialling node {@code peer}. */
    public void handshakeAsDialer(PeerIdentity self, String peer) throws IOException {
        handshake(PeerHandshake.dialer(self, peer, nonces()));
    }

    private static Supplier<byte[]> nonces() {
        return () -> new byte[PeerHandshake.NONCE];
    }

    private void handshake(PeerHandshake h) throws IOException {
        out.write(h.hello().getBytes());
        byte[] theirs = pending != null ? pending : frame();
        pending = null;
        out.write(h.proof(Buffer.buffer(theirs)).getBytes());
        session = h.sessionOf();
        h.accept(Buffer.buffer(frame()));
    }

    /** Sends a frame of {@code type} with {@code payload}, sealed in sequence by the session. */
    public void send(int type, byte[] payload) throws IOException {
        byte[] f = Frames.frame(type, payload.length);
        System.arraycopy(payload, 0, f, Frames.HEAD, payload.length);
        last = session.seal(f);
        out.write(last);
        out.flush();
    }

    /** A frame of {@code type} with {@code payload}, sealed as the next in sequence, NOT sent: for {@link #write}. */
    public byte[] sealed(int type, byte[] payload) {
        byte[] f = Frames.frame(type, payload.length);
        System.arraycopy(payload, 0, f, Frames.HEAD, payload.length);
        return session.seal(f);
    }

    /** Writes {@code raw[off, off + len)} as it is, in one socket write: frames from {@link #sealed}, any number of them. */
    public void write(byte[] raw, int off, int len) throws IOException {
        out.write(raw, off, len);
        out.flush();
    }

    /** This end's local port. */
    public int localPort() { return socket.getLocalPort(); }

    /** The other end's port: the dialer's ephemeral one, for a listener. */
    public int remotePort() { return socket.getPort(); }

    /** Sends the last frame sent again, byte for byte: a replay. */
    public void replay() throws IOException {
        out.write(last);
        out.flush();
    }

    /** The next frame the other side sent, checked by the session; its payload from {@link #payload}. */
    public byte[] next() throws IOException {
        byte[] f = frame();
        session.open(f);
        return f;
    }

    public static int type(byte[] frame) {
        return Frames.type(frame);
    }

    public static ByteBuffer payload(byte[] frame) {
        return Frames.payload(frame);
    }

    /** A READ's payload: what a reader sends for {@code ref} of {@code owner}'s journal {@code journal}. */
    public static byte[] read(long id, String owner, java.util.UUID journal, long segment, long position, long length) {
        byte[] o = owner.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ByteBuffer.allocate(8 + 2 + o.length + 16 + 24).putLong(id).putShort((short) o.length).put(o)
                .putLong(journal.getMostSignificantBits()).putLong(journal.getLeastSignificantBits())
                .putLong(segment).putLong(position).putLong(length).array();
    }

    /**
     * Whether the other side closed the connection: every byte it sent before is read and dropped, then the end — true; false
     * when nothing ends it within the bound.
     */
    public boolean closedByPeer() throws IOException {
        return closedByPeer(socket);
    }

    /** {@link #closedByPeer()} of a plain socket. */
    public static boolean closedByPeer(Socket s) throws IOException {
        s.setSoTimeout(BOUND_SECONDS * 1000);
        byte[] drain = new byte[64 * 1024];
        try {
            while (true) {
                if (s.getInputStream().read(drain) < 0) return true;
            }
        } catch (SocketTimeoutException open) {
            return false;
        } catch (java.net.SocketException reset) {
            return true;   // a reset is a close too
        }
    }

    private byte[] frame() throws IOException {
        byte[] head = new byte[4];
        in.readFully(head);
        int len = ByteBuffer.wrap(head).getInt();
        if (len < 0 || len > Frames.MAX_LEN) throw new EOFException("fixture: a frame of " + len + " bytes");
        byte[] f = new byte[4 + len];
        System.arraycopy(head, 0, f, 0, 4);
        in.readFully(f, 4, len);
        return f;
    }

    @Override public void close() throws IOException {
        socket.close();
    }
}
