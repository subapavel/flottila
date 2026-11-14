package io.flottila.runtime.journal.peer;

import io.vertx.core.buffer.Buffer;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The HELLO exchange of a peer link (CORE-5 S2, W7), without a socket. Both sides send their HELLO at once on connect — version,
 * role, channel (CORE-5a2 P1: 1 read, 2 repl, 3 bulk), node, epoch, journal id, a 32-byte nonce and the fingerprint of the
 * master key it proves under (the dialer's current one) — then each its proof. A HELLO of version 1 or above is taken, and so
 * are bytes after its v1 fields up to the HELLO cap (P2): the link speaks the lower version of the two, today 1; version 0 or
 * a HELLO shorter than its v1 fields is refused. The link's channel is the dialer's (CORE-5b1 C1): its HELLO names the one it
 * dials, read or repl; the listener's HELLO names read on every link, its bytes the same whatever the dialer dials. A listener
 * refuses a dialer on another channel (bulk comes with CORE-5b3), a dialer a listener whose HELLO names a channel other than
 * read. Both proofs cover both whole HELLOs, so a channel byte rewritten on the way never gets a link established. The listener
 * proves under the key the dialer's fingerprint names, if it holds it as its current or its previous key, and refuses the
 * HELLO at once otherwise (O6: a listener accepts a key it knows). A master key keys no HMAC on the link (P5): its
 * only use there is as the input of the one HKDF that makes its peer subkey, HKDF-SHA256 with an empty salt, info
 * {@value #SUBKEY}; a fingerprint is HMAC
 * under the subkey, and the session keys come from HKDF-SHA256 over the subkey, salted with both nonces (dialer's first),
 * info {@value #INFO}: one proof key and one frame key per direction. A proof is HMAC-SHA256 under its sender's direction key
 * over both whole HELLOs — both nonces, both node ids, both epochs and journal ids, the bytes after the v1 fields — so a
 * reflected proof, one for the other direction or one of another exchange is refused. HELLO is frame sequence 0 (tag zero),
 * the proof sequence 1 under the session, the frames after it from 2.
 *
 * @since 0.1
 */
public final class PeerHandshake {

    static final byte VERSION = 1;
    static final int NONCE = 32;
    static final int FINGERPRINT = 8;
    /** The channel of the reads (P1): what a listener's HELLO names on every link. */
    static final byte READ = 1;
    /** The channel of the replication (CORE-5b1): an owner dialling its holder. */
    static final byte REPL = 2;
    /** A v1 HELLO's fields without its node id: version, role, channel, node length, epoch, journal id, nonce, fingerprint. */
    private static final int FIXED = 1 + 1 + 1 + 2 + 8 + 16 + NONCE + FINGERPRINT;
    /**
     * The longest HELLO frame's {@code len}: a v1 HELLO naming a node id of 65 535 bytes; until a link is proven a longer one is
     * refused (r2 R1). The bytes a later version adds after the v1 fields count against it (O1).
     */
    static final long HELLO_LEN_MAX = Frames.MIN_LEN + FIXED + 0xFFFF;
    /** A proof frame's {@code len}: an HMAC-SHA256; after the peer's HELLO a longer frame is refused (r2 R1). */
    static final long PROOF_LEN = Frames.MIN_LEN + 32;
    private static final String INFO = "flottila-peer-v1";
    private static final String SUBKEY = INFO + " subkey";
    private static final byte DIALER = 1;
    private static final byte LISTENER = 2;
    private static final byte[] PROOF_LABEL = (INFO + " proof").getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FINGERPRINT_LABEL = (INFO + " fingerprint").getBytes(StandardCharsets.US_ASCII);

    private final PeerIdentity self;
    private final byte role;
    private final String peer;   // the dialer's: the node it dials; null for a listener
    private final byte[] nonce;
    private final byte[] hello;  // this side's HELLO payload
    private byte channel;          // the link's: a dialer's own, a listener's once proof() read the dialer's
    private PeerSession session;   // once proof() ran
    private byte[] expected;       // the peer's proof, once proof() ran
    private String peerNode;
    private long peerEpoch;
    private UUID peerJournal;

    private PeerHandshake(PeerIdentity self, byte role, String peer, Supplier<byte[]> nonces, byte channel) {
        this.self = Objects.requireNonNull(self, "self");
        this.role = role;
        this.peer = peer;
        this.nonce = Objects.requireNonNull(nonces, "nonces").get().clone();
        if (nonce.length != NONCE) throw new IllegalArgumentException("a nonce is " + NONCE + " bytes, was " + nonce.length);
        this.channel = channel;
        byte[] node = self.node().getBytes(StandardCharsets.UTF_8);
        ByteBuffer h = ByteBuffer.allocate(FIXED + node.length);
        h.put(VERSION).put(role).put(channel).putShort((short) node.length).put(node).putLong(self.epoch())
                .putLong(self.journal().getMostSignificantBits()).putLong(self.journal().getLeastSignificantBits()).put(nonce)
                .put(fingerprint(self.masters().getFirst()));
        this.hello = h.array();
    }

    /** The dialing side of a read link to {@code peer} (its node id): its HELLO's node must be that one. */
    public static PeerHandshake dialer(PeerIdentity self, String peer, Supplier<byte[]> nonces) {
        return dialer(self, peer, nonces, READ);
    }

    /**
     * The dialing side of a link to {@code peer} on {@code channel} (its HELLO's byte 2: 1 read, 2 repl, 3 bulk): the HELLO of
     * the listener must name {@code peer} and channel read.
     */
    public static PeerHandshake dialer(PeerIdentity self, String peer, Supplier<byte[]> nonces, byte channel) {
        return new PeerHandshake(self, DIALER, Objects.requireNonNull(peer, "peer"), nonces, channel);
    }

    /** The listening side of a link: its HELLO names channel read; the link's channel is the one the dialer's HELLO names. */
    public static PeerHandshake listener(PeerIdentity self, Supplier<byte[]> nonces) {
        return new PeerHandshake(self, LISTENER, null, nonces, READ);
    }

    /** This side's HELLO frame: the same bytes on every call. */
    public Buffer hello() {
        byte[] f = Frames.frame(Frames.HELLO, hello.length);
        System.arraycopy(hello, 0, f, Frames.HEAD, hello.length);
        return Buffer.buffer(f);
    }

    /**
     * This side's proof frame, for the peer's HELLO frame {@code peerHello}.
     *
     * @throws PeerRefusedException the HELLO is not one of version 1 or above, of the other role, with its v1 fields whole, on
     *                              channel read (a listener's) or read or repl (a dialer's), a dialer's of the node it dials, or
     *                              — a listener's — it names a master key this side holds neither as current nor as previous
     */
    public Buffer proof(Buffer peerHello) throws PeerRefusedException {
        byte[] f = peerHello.getBytes();
        if (f.length < Frames.HEAD + Frames.TAG || ByteBuffer.wrap(f).getInt(0) != f.length - 4 || Frames.type(f) != Frames.HELLO || Frames.seq(f) != 0
                || !Arrays.equals(f, f.length - Frames.TAG, f.length, new byte[Frames.TAG], 0, Frames.TAG)) {
            throw new PeerRefusedException("the first frame of a peer link is a HELLO of sequence 0 with a zero tag");
        }
        byte[] theirs = Arrays.copyOfRange(f, Frames.HEAD, f.length - Frames.TAG);
        ByteBuffer in = ByteBuffer.wrap(theirs);
        byte other = role == DIALER ? LISTENER : DIALER;
        if (theirs.length < FIXED || Byte.toUnsignedInt(in.get(0)) < VERSION || in.get(1) != other) {   // P2: the link speaks v1, the lower
            throw new PeerRefusedException("not a HELLO of version " + VERSION + " or above of a " + (other == DIALER ? "dialer" : "listener"));
        }
        int named = Byte.toUnsignedInt(in.get(2));
        if (role == DIALER ? named != READ : named != READ && named != REPL) {   // C1: a listener names read; it serves read and repl
            throw new PeerRefusedException(role == DIALER ? "dialled channel " + channel(channel) + ", answered on channel " + channel(named)
                    + " (a listener's HELLO names channel read)" : "a dialer's HELLO on channel " + channel(named) + ": a listener takes channels 1 and 2 only");
        }
        int nodeLength = Short.toUnsignedInt(in.getShort(3));
        if (theirs.length < FIXED + nodeLength) throw new PeerRefusedException("a HELLO of " + theirs.length + " bytes naming a node of " + nodeLength);
        String node = new String(theirs, 5, nodeLength, StandardCharsets.UTF_8);
        if (peer != null && !peer.equals(node)) throw new PeerRefusedException("dialled node " + peer + ", answered by " + node);
        ByteBuffer ids = ByteBuffer.wrap(theirs, 5 + nodeLength, 8 + 16);
        long epoch = ids.getLong();
        UUID journal = new UUID(ids.getLong(), ids.getLong());
        int at = 5 + nodeLength + 8 + 16;
        byte[] theirNonce = Arrays.copyOfRange(theirs, at, at + NONCE);
        byte[] theirFingerprint = Arrays.copyOfRange(theirs, at + NONCE, at + NONCE + FINGERPRINT);
        SecretKey master = role == DIALER ? self.masters().getFirst() : known(theirFingerprint);
        if (master == null) {
            throw new PeerRefusedException("node " + node + " proves under a master key this node holds neither as current nor as previous (D-106)");
        }
        byte[] dialerHello = role == DIALER ? hello : theirs;
        byte[] listenerHello = role == DIALER ? theirs : hello;
        byte[] salt = concat(role == DIALER ? nonce : theirNonce, role == DIALER ? theirNonce : nonce);
        byte[] keys = hkdf(salt, subkey(master), INFO.getBytes(StandardCharsets.US_ASCII), 4 * 32);
        byte[] dialerProof = Arrays.copyOfRange(keys, 0, 32);
        byte[] listenerProof = Arrays.copyOfRange(keys, 32, 64);
        byte[] dialerFrames = Arrays.copyOfRange(keys, 64, 96);
        byte[] listenerFrames = Arrays.copyOfRange(keys, 96, 128);
        byte[] mine = prove(role == DIALER ? dialerProof : listenerProof, dialerHello, listenerHello);
        expected = prove(role == DIALER ? listenerProof : dialerProof, dialerHello, listenerHello);
        session = role == DIALER ? new PeerSession(dialerFrames, listenerFrames, 1) : new PeerSession(listenerFrames, dialerFrames, 1);
        peerNode = node;
        peerEpoch = epoch;
        peerJournal = journal;
        if (role == LISTENER) channel = (byte) named;
        byte[] p = Frames.frame(Frames.PROOF, mine.length);
        System.arraycopy(mine, 0, p, Frames.HEAD, mine.length);
        return Buffer.buffer(session.seal(p));
    }

    /**
     * The session, once the peer's proof frame {@code peerProof} holds: frames after it are sealed and opened by it.
     *
     * @throws PeerRefusedException the frame fails its tag or sequence, or the proof is not the peer's for this exchange
     * @throws IllegalStateException {@link #proof} did not run yet
     */
    public PeerSession accept(Buffer peerProof) throws PeerRefusedException {
        if (session == null) throw new IllegalStateException("a proof is accepted after this side's own proof");
        byte[] f = peerProof.getBytes();
        if (f.length < Frames.HEAD + Frames.TAG || ByteBuffer.wrap(f).getInt(0) != f.length - 4 || Frames.type(f) != Frames.PROOF) {
            throw new PeerRefusedException("the second frame of a peer link is a proof");
        }
        session.open(f);
        if (!MessageDigest.isEqual(expected, Arrays.copyOfRange(f, Frames.HEAD, f.length - Frames.TAG))) {
            throw new PeerRefusedException("node " + peerNode + "'s proof does not hold for this exchange");
        }
        return session;
    }

    /** The session {@link #proof} derived (its proof sealed), before the peer's proof is accepted; null until then. */
    PeerSession sessionOf() {
        return session;
    }

    /** The link's channel (1 read, 2 repl): a dialer's own; a listener's the dialer's HELLO named, once {@link #proof} took it. */
    int channel() {
        return channel;
    }

    /** The node the peer's HELLO named, once {@link #proof} took it. */
    String peerNode() {
        return peerNode;
    }

    /** The epoch the peer's HELLO named, once {@link #proof} took it. */
    long peerEpoch() {
        return peerEpoch;
    }

    /** The journal id the peer's HELLO named, once {@link #proof} took it. */
    UUID peerJournal() {
        return peerJournal;
    }

    private SecretKey known(byte[] fingerprint) {
        for (SecretKey k : self.masters()) {
            if (MessageDigest.isEqual(fingerprint(k), fingerprint)) return k;
        }
        return null;
    }

    /**
     * A master key's fingerprint: HMAC-SHA256 under its peer subkey of a fixed label, its first {@value #FINGERPRINT} bytes (never
     * the key's hash, never keyed by the master key itself).
     */
    static byte[] fingerprint(SecretKey master) {
        return Arrays.copyOf(PeerSession.mac(subkey(master)).doFinal(FINGERPRINT_LABEL), FINGERPRINT);
    }

    /** A master key's peer subkey (P5): HKDF-SHA256 of it with an empty salt, info {@value #SUBKEY}, 32 bytes. */
    private static byte[] subkey(SecretKey master) {
        return hkdf(new byte[0], master.getEncoded(), SUBKEY.getBytes(StandardCharsets.US_ASCII), 32);
    }

    /** A channel byte's name: read, repl, bulk, else its number. */
    private static String channel(int channel) {
        return switch (channel) {
            case 1 -> "read";
            case 2 -> "repl";
            case 3 -> "bulk";
            default -> channel + " (unknown)";
        };
    }

    private static byte[] prove(byte[] key, byte[] dialerHello, byte[] listenerHello) {
        Mac m = PeerSession.mac(key);
        m.update(PROOF_LABEL);
        m.update(dialerHello);
        m.update(listenerHello);
        return m.doFinal();
    }

    /**
     * HKDF-SHA256 (RFC 5869): extract with {@code salt}, expand to {@code length} bytes under {@code info}. An empty salt is the
     * 32 zero bytes RFC 5869 §2.2 names (the same HMAC key: HMAC pads a key with zeros; a JCE key may not be empty).
     */
    static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) {
        byte[] prk = PeerSession.mac(salt.length == 0 ? new byte[32] : salt).doFinal(ikm);
        Mac expand = PeerSession.mac(prk);
        byte[] out = new byte[length];
        byte[] t = new byte[0];
        for (int i = 1, at = 0; at < length; i++) {
            expand.update(t);
            expand.update(info);
            expand.update((byte) i);
            t = expand.doFinal();
            int k = Math.min(t.length, length - at);
            System.arraycopy(t, 0, out, at, k);
            at += k;
        }
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] c = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }
}
