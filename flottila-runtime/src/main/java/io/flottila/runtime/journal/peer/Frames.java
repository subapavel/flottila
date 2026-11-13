package io.flottila.runtime.journal.peer;

import io.vertx.core.buffer.Buffer;

import java.nio.ByteBuffer;

/**
 * The peer link's framing v1 (CORE-5 S2): {@code u32 len · u8 type · u64 seq · payload · tag[16]}, big-endian, {@code len}
 * counting every byte after itself; the tag is HMAC-SHA256/128 over every byte before it under the direction's session key
 * (a HELLO's is zero: it is proven by the proofs that follow). A receiver refuses a length below the shortest frame or above
 * the longest it takes now — until the link is proven a HELLO's, then a proof's (r2 R1) — from the length alone, before it
 * waits for a byte it announces.
 */
final class Frames {

    static final int HELLO = 1;
    static final int PROOF = 2;
    /** The repl channel's (CORE-5b1, {@link ReplFrames}): owner to holder OPEN, DATA, SEAL; holder to owner RECEIPT, REFUSE. */
    static final int OPEN = 3;
    static final int DATA = 4;
    static final int SEAL = 5;
    static final int RECEIPT = 6;
    // 7 HAVE (CORE-5b3)
    static final int REFUSE = 8;
    // 9 reserved
    static final int READ = 10;
    static final int BYTES = 11;
    static final int MISS = 12;

    /** The bytes before the payload: len, type, seq. */
    static final int HEAD = 4 + 1 + 8;
    static final int TAG = 16;
    /** The longest payload: a DATA frame's 4 MiB and its addressing (CORE-5b). */
    static final int MAX_PAYLOAD = (4 << 20) + 1024;
    /** The shortest and the longest {@code len}. */
    static final long MIN_LEN = 1 + 8 + TAG;
    static final long MAX_LEN = MIN_LEN + MAX_PAYLOAD;
    /** The most bytes of a record one BYTES frame carries. */
    static final int BYTES_MAX = 1 << 20;

    private Frames() { }

    /** A frame of {@code type} with room for {@code payload} bytes at {@link #HEAD}: its len and type written, its seq and tag not. */
    static byte[] frame(int type, int payload) {
        byte[] f = new byte[HEAD + payload + TAG];
        ByteBuffer.wrap(f).putInt(0, f.length - 4).put(4, (byte) type);
        return f;
    }

    static int type(byte[] frame) {
        return frame[4] & 0xFF;
    }

    static long seq(byte[] frame) {
        return ByteBuffer.wrap(frame).getLong(5);
    }

    static int payloadLength(byte[] frame) {
        return frame.length - HEAD - TAG;
    }

    /** The payload of {@code frame}, read from {@link #HEAD}. */
    static ByteBuffer payload(byte[] frame) {
        return ByteBuffer.wrap(frame, HEAD, payloadLength(frame)).slice();
    }

    /**
     * Cuts a link's inbound bytes into frames: a frame's array is allocated once its length is read, so it is filled with one copy
     * of its bytes. A length below {@link #MIN_LEN} or above the longest it takes now is refused at once.
     */
    static final class Decoder {
        private final byte[] len = new byte[4];
        private long longest;
        private int lenHave;
        private byte[] frame;   // null: reading a length
        private int have;
        private Buffer chunk;
        private int at;

        /** A decoder whose first frame's {@code len} is at most {@code longest}. */
        Decoder(long longest) {
            longest(longest);
        }

        /** From the next frame on, a {@code len} above {@code n} (at most {@link #MAX_LEN}) is refused. */
        void longest(long n) {
            longest = n;
        }

        /** Starts on {@code b}: {@link #next} cuts its frames. */
        void feed(Buffer b) {
            chunk = b;
            at = 0;
        }

        /** The next whole frame of the bytes fed (after a {@link #feed}), or null once they are used up. */
        byte[] next() throws PeerRefusedException {
            while (at < chunk.length()) {
                if (frame == null) {
                    len[lenHave++] = chunk.getByte(at++);
                    if (lenHave < 4) continue;
                    lenHave = 0;
                    long n = ByteBuffer.wrap(len).getInt() & 0xFFFFFFFFL;
                    if (n < MIN_LEN || n > longest) {
                        throw new PeerRefusedException("a frame of " + n + " bytes: frames here are " + MIN_LEN + " to " + longest + " bytes");
                    }
                    frame = new byte[(int) (4 + n)];
                    System.arraycopy(len, 0, frame, 0, 4);
                    have = 4;
                }
                int k = Math.min(frame.length - have, chunk.length() - at);
                chunk.getBytes(at, at + k, frame, have);
                at += k;
                have += k;
                if (have == frame.length) {
                    byte[] done = frame;
                    frame = null;
                    return done;
                }
            }
            return null;
        }
    }
}
