package io.flottila.runtime.journal.peer;

import io.vertx.core.buffer.Buffer;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * One end of a peer link whose HELLO is written field by field and whose keys and proofs are derived here from the CORE-5a2
 * packet's text (P1, P2, P5), never by {@link PeerHandshake}: a HELLO of any version, role, channel and length, with bytes after
 * its v1 fields, and the proofs over the whole of both HELLOs as they went over the wire. {@code PeerKnownAnswerTest
 * #theScriptedPeerDerivesTheKnownAnswers} checks these derivations against the literals computed outside the code.
 *
 * <p>HELLO v1 payload (P1): u8 version · u8 role (1 dialer, 2 listener) · u8 channel (1 read, 2 repl, 3 bulk) · u16 node length
 * · node (UTF-8) · i64 epoch · journal id (16) · nonce (32) · fingerprint (8) · anything after (P2). P5: K_peer =
 * HKDF-SHA256(salt empty, ikm master, info "flottila-peer-v1 subkey", 32); fingerprint = HMAC-SHA256(K_peer, "flottila-peer-v1
 * fingerprint")[0..8]; keys = HKDF-SHA256(salt dialer nonce ‖ listener nonce, ikm K_peer, info "flottila-peer-v1", 128) = dialer
 * proof, listener proof, dialer frames, listener frames; a proof = HMAC(its direction's proof key, "flottila-peer-v1 proof" ‖
 * dialer HELLO payload ‖ listener HELLO payload) in a PROOF frame of sequence 1 tagged under its direction's frame key. Reads
 * are bounded by {@value #BOUND_SECONDS} s: a failure detector, never a timing.
 */
public final class ScriptedPeer implements AutoCloseable {

    public static final int DIALER = 1;
    public static final int LISTENER = 2;
    public static final int CHANNEL_READ = 1;
    public static final int CHANNEL_REPL = 2;
    public static final int CHANNEL_BULK = 3;
    public static final int HELLO = Frames.HELLO;
    public static final int PROOF = Frames.PROOF;
    public static final int READ = Frames.READ;
    public static final int MISS = Frames.MISS;
    static final int BOUND_SECONDS = 20;
    /** RFC 5869 §2.2: a salt not provided is HashLen zeros (HMAC pads an empty key to the same block). */
    private static final byte[] NO_SALT = new byte[32];

    /** A HELLO's fields, each settable; its payload is written from them in P1's order, then {@code trailing}, then cut. */
    public static final class Hello {
        private final int role;
        private final long epoch;
        private final UUID journal;
        private final byte[] nonce;
        private final byte[] fingerprint;
        private int version = 1;
        private int channel = CHANNEL_READ;
        private boolean withoutChannel;
        private String node;
        private byte[] trailing = new byte[0];
        private int cut;

        private Hello(int role, PeerIdentity who, byte[] nonce) {
            this.role = role;
            this.node = who.node();
            this.epoch = who.epoch();
            this.journal = who.journal();
            this.nonce = nonce.clone();
            this.fingerprint = fingerprint(who.masters().getFirst());
        }

        /** {@code who}'s v1 HELLO as a dialer on the read channel, under its current key. */
        public static Hello dialer(PeerIdentity who, byte[] nonce) { return new Hello(DIALER, who, nonce); }

        /** {@code who}'s v1 HELLO as a listener on the read channel, under its current key. */
        public static Hello listener(PeerIdentity who, byte[] nonce) { return new Hello(LISTENER, who, nonce); }

        public Hello version(int v) { version = v; return this; }

        public Hello channel(int c) { channel = c; return this; }

        /** The 5a layout: no channel byte after the role. */
        public Hello withoutChannel() { withoutChannel = true; return this; }

        public Hello node(String n) { node = n; return this; }

        /** Bytes after the v1 fields (P2). */
        public Hello trailing(byte[] t) { trailing = t.clone(); return this; }

        /** The payload without its last {@code n} bytes. */
        public Hello cut(int n) { cut = n; return this; }

        public byte[] payload() {
            byte[] n = node.getBytes(StandardCharsets.UTF_8);
            ByteBuffer b = ByteBuffer.allocate(3 + 2 + n.length + 8 + 16 + 32 + 8 + trailing.length);
            b.put((byte) version).put((byte) role);
            if (!withoutChannel) b.put((byte) channel);
            b.putShort((short) n.length).put(n).putLong(epoch).putLong(journal.getMostSignificantBits())
                    .putLong(journal.getLeastSignificantBits()).put(nonce).put(fingerprint).put(trailing);
            return Arrays.copyOf(b.array(), b.position() - cut);
        }

        /** The HELLO frame: u32 len · type 1 · seq 0 · payload · zero tag. */
        public Buffer frame() {
            byte[] p = payload();
            return Buffer.buffer().appendInt(1 + 8 + p.length + 16).appendByte((byte) HELLO).appendLong(0).appendBytes(p)
                    .appendBytes(new byte[16]);
        }
    }

    // ---------------------------------------------------------------------------------------------- P5, written from the packet

    public static byte[] hmac(byte[] key, byte[]... parts) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            for (byte[] p : parts) m.update(p);
            return m.doFinal();
        } catch (GeneralSecurityException x) {
            throw new IllegalStateException(x);
        }
    }

    /** HKDF-SHA256 (RFC 5869); an empty salt is HashLen zeros. */
    public static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int n) {
        byte[] prk = hmac(salt.length == 0 ? NO_SALT : salt, ikm);
        byte[] out = new byte[n];
        byte[] t = new byte[0];
        for (int i = 1, at = 0; at < n; i++) {
            t = hmac(prk, t, info, new byte[] {(byte) i});
            int k = Math.min(t.length, n - at);
            System.arraycopy(t, 0, out, at, k);
            at += k;
        }
        return out;
    }

    /** K_peer of {@code master}. */
    public static byte[] subkey(SecretKey master) {
        return hkdf(new byte[0], master.getEncoded(), ascii("flottila-peer-v1 subkey"), 32);
    }

    /** The fingerprint a HELLO under {@code master} carries. */
    public static byte[] fingerprint(SecretKey master) {
        return Arrays.copyOf(hmac(subkey(master), ascii("flottila-peer-v1 fingerprint")), 8);
    }

    /** The four keys of an exchange of these HELLO payloads (nonces at their v1 positions): dialer proof, listener proof, dialer frames, listener frames. */
    static byte[][] keys(byte[] dialerHello, byte[] listenerHello, SecretKey master) {
        byte[] salt = concat(nonceOf(dialerHello), nonceOf(listenerHello));
        byte[] k = hkdf(salt, subkey(master), ascii("flottila-peer-v1"), 128);
        return new byte[][] {Arrays.copyOfRange(k, 0, 32), Arrays.copyOfRange(k, 32, 64), Arrays.copyOfRange(k, 64, 96), Arrays.copyOfRange(k, 96, 128)};
    }

    /** Both proof frames of an exchange of these two HELLO payloads under {@code master}: [0] the dialer's, [1] the listener's. */
    public static byte[][] proofFrames(byte[] dialerHello, byte[] listenerHello, SecretKey master) {
        byte[][] k = keys(dialerHello, listenerHello, master);
        return new byte[][] {proofFrame(k[0], k[2], dialerHello, listenerHello), proofFrame(k[1], k[3], dialerHello, listenerHello)};
    }

    private static byte[] proofFrame(byte[] proofKey, byte[] frameKey, byte[] dialerHello, byte[] listenerHello) {
        byte[] f = new byte[4 + 1 + 8 + 32 + 16];
        ByteBuffer.wrap(f).putInt(f.length - 4).put((byte) PROOF).putLong(1)
                .put(hmac(proofKey, ascii("flottila-peer-v1 proof"), dialerHello, listenerHello));
        System.arraycopy(hmac(frameKey, Arrays.copyOf(f, f.length - 16)), 0, f, f.length - 16, 16);
        return f;
    }

    /** A HELLO payload's nonce, at its v1 position. */
    static byte[] nonceOf(byte[] hello) {
        int at = hello.length < 5 ? hello.length : 3 + 2 + Short.toUnsignedInt(ByteBuffer.wrap(hello).getShort(3)) + 8 + 16;
        if (at + 32 + 8 > hello.length) {
            throw new AssertionError("fixture: a HELLO of " + hello.length + " bytes is shorter than the v1 fields of P1 (u8 version · u8 role · u8 channel · "
                    + "u16 node length …) it names: " + HexFormat.of().formatHex(hello, 0, Math.min(hello.length, 8)) + "…");
        }
        return Arrays.copyOfRange(hello, at, at + 32);
    }

    /** A HELLO frame's payload. */
    public static byte[] payloadOf(Buffer helloFrame) {
        byte[] f = helloFrame.getBytes();
        return Arrays.copyOfRange(f, 4 + 1 + 8, f.length - 16);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] c = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }

    // ---------------------------------------------------------------------------------------------------------------- the socket

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private PeerSession session;
    private byte[] theirHello;

    private ScriptedPeer(Socket socket) throws IOException {
        this.socket = socket;
        socket.setSoTimeout(BOUND_SECONDS * 1000);
        this.in = new DataInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    private static Socket connected(InetAddress from, int port) throws IOException {
        Socket s = new Socket();
        s.bind(from == null ? null : new InetSocketAddress(from, 0));
        s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), BOUND_SECONDS * 1000);
        return s;
    }

    /**
     * Dials the listener on 127.0.0.1:{@code port} (from {@code from}, null: any local address) with {@code mine}, a dialer's HELLO,
     * and runs the handshake under {@code master}: the listener's proof must be the one P5 derives over both whole HELLOs.
     */
    public static ScriptedPeer dial(InetAddress from, int port, Hello mine, SecretKey master) throws IOException {
        ScriptedPeer p = new ScriptedPeer(connected(from, port));
        p.handshake(mine, master);
        return p;
    }

    /** As the listener on the next connection {@code server} accepts, with {@code mine}, a listener's HELLO; see {@link #dial}. */
    public static ScriptedPeer accept(ServerSocket server, Hello mine, SecretKey master) throws IOException {
        ScriptedPeer p = new ScriptedPeer(server.accept());
        p.handshake(mine, master);
        return p;
    }

    /**
     * Dials 127.0.0.1:{@code port}, sends {@code mine} and nothing else, and reads until the listener closes: the types of the
     * frames it sent before its close, in order. An AssertionError when it does not close within the bound.
     */
    public static List<Integer> refusedAfter(int port, Hello mine) throws IOException {
        try (ScriptedPeer p = new ScriptedPeer(connected(null, port))) {
            p.out.write(mine.frame().getBytes());
            p.out.flush();
            List<Integer> types = new ArrayList<>();
            try {
                while (true) types.add(Frames.type(p.frame()));
            } catch (EOFException | SocketException closed) {
                return types;
            } catch (SocketTimeoutException open) {
                throw new AssertionError("fixture: the listener did not close the link within " + BOUND_SECONDS + " s after frames " + types);
            }
        }
    }

    private void handshake(Hello mine, SecretKey master) throws IOException {
        byte[] mineHello = mine.payload();
        out.write(mine.frame().getBytes());
        out.flush();
        byte[] f = frame();
        if (Frames.type(f) != HELLO) throw new AssertionError("fixture: the peer's first frame is of type " + Frames.type(f));
        theirHello = Arrays.copyOfRange(f, Frames.HEAD, f.length - Frames.TAG);
        boolean dialer = mine.role == DIALER;
        byte[] dh = dialer ? mineHello : theirHello;
        byte[] lh = dialer ? theirHello : mineHello;
        byte[][] proofs = proofFrames(dh, lh, master);
        byte[][] keys = keys(dh, lh, master);
        out.write(dialer ? proofs[0] : proofs[1]);
        out.flush();
        byte[] theirs = frame();
        byte[] expected = dialer ? proofs[1] : proofs[0];
        if (!Arrays.equals(expected, theirs)) {
            throw new AssertionError("the peer's proof frame is not the one P5 derives over both whole HELLOs: expected "
                    + HexFormat.of().formatHex(expected) + ", was " + HexFormat.of().formatHex(theirs));
        }
        session = dialer ? new PeerSession(keys[2], keys[3], 2) : new PeerSession(keys[3], keys[2], 2);
    }

    /** The peer's HELLO payload as it came. */
    public byte[] theirHello() {
        return theirHello.clone();
    }

    /** Sends a frame of {@code type} with {@code payload}, sealed in sequence (from 2, after the proof). */
    public void send(int type, byte[] payload) throws IOException {
        byte[] f = Frames.frame(type, payload.length);
        System.arraycopy(payload, 0, f, Frames.HEAD, payload.length);
        out.write(session.seal(f));
        out.flush();
    }

    /** The next frame the peer sent, checked under the frame key P5 derives. */
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

    private byte[] frame() throws IOException {
        int len = in.readInt();
        if (len < 0 || len > Frames.MAX_LEN) throw new EOFException("fixture: a frame of " + len + " bytes");
        byte[] f = new byte[4 + len];
        ByteBuffer.wrap(f).putInt(len);
        in.readFully(f, 4, len);
        return f;
    }

    @Override public void close() throws IOException {
        socket.close();
    }
}
