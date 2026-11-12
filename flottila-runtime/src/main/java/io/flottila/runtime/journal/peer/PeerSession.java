package io.flottila.runtime.journal.peer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

/**
 * An established peer link's frame keys (CORE-5 S2): each direction its own HMAC-SHA256 key from the handshake and its own
 * sequence, so a frame replayed, reordered, reflected or cut out fails its receiver's check. Not thread-safe: the link seals
 * under its write lock and opens on its event loop.
 *
 * @since 0.1
 */
public final class PeerSession {

    private final Mac send;
    private final Mac receive;
    private long sent;
    private long received;

    PeerSession(byte[] sendKey, byte[] receiveKey, long next) {
        this.send = mac(sendKey);
        this.receive = mac(receiveKey);
        this.sent = next;
        this.received = next;
    }

    static Mac mac(byte[] key) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m;
        } catch (GeneralSecurityException x) {
            throw new IllegalStateException("HmacSHA256 is part of every JDK", x);
        }
    }

    /** Writes the next sequence number and the tag into {@code frame} (from {@link Frames#frame}, its payload filled); answers it. */
    byte[] seal(byte[] frame) {
        ByteBuffer.wrap(frame).putLong(5, sent++);
        send.update(frame, 0, frame.length - Frames.TAG);
        System.arraycopy(send.doFinal(), 0, frame, frame.length - Frames.TAG, Frames.TAG);
        return frame;
    }

    /** Checks {@code frame}'s sequence number and tag: the next one in order under the peer's key, else refused. */
    void open(byte[] frame) throws PeerRefusedException {
        long seq = Frames.seq(frame);
        if (seq != received) throw new PeerRefusedException("a frame of sequence " + seq + " where " + received + " is next");
        receive.update(frame, 0, frame.length - Frames.TAG);
        byte[] tag = receive.doFinal();
        if (!MessageDigest.isEqual(java.util.Arrays.copyOf(tag, Frames.TAG), java.util.Arrays.copyOfRange(frame, frame.length - Frames.TAG, frame.length))) {
            throw new PeerRefusedException("a frame of type " + Frames.type(frame) + " (sequence " + seq + ") fails its tag");
        }
        received++;
    }
}
