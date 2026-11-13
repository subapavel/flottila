package io.flottila.runtime.journal.peer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import java.util.zip.CRC32C;

/**
 * The repl channel's payloads written field by field from the CORE-5b packet's text ("Wire", as amended: SEAL carries the
 * crc32c of the file's bytes [0, end)), never by {@code ReplFrames}: what an owner sends a holder and what it reads back.
 * {@code ReplFramesTest#theWireFixtureWritesTheGoldenBytes} checks these encoders against the payloads computed outside the
 * code ({@code agent/work/CORE-5b1-frames.py}). Big-endian throughout.
 *
 * <pre>
 * A       = u16 L · owner UTF-8 (1..63) · journal id (u64 msb, u64 lsb) · n u64 (≥ 1)
 * OPEN 3  = A · length u64 · header (43 + L bytes, the owner's segment header)
 * DATA 4  = A · pos u64 · bytes (1 .. 4 MiB)
 * SEAL 5  = A · end u64 · crc32c u32
 * RECEIPT 6 = A · end u64 · state u8 (1 OPENED, 2 WRITTEN, 3 SEALED)
 * REFUSE 8  = A · reason u8 (1 FULL, 2 FAILED, 3 MISMATCH) · u16 len · detail UTF-8 (≤ 512)
 * </pre>
 */
public final class ReplWire {

    public static final int OPEN = 3;
    public static final int DATA = 4;
    public static final int SEAL = 5;
    public static final int RECEIPT = 6;
    public static final int HAVE = 7;
    public static final int REFUSE = 8;

    public static final int OPENED = 1;
    public static final int WRITTEN = 2;
    public static final int SEALED = 3;

    public static final int FULL = 1;
    public static final int FAILED = 2;
    public static final int MISMATCH = 3;

    /** The channel bytes of a HELLO (CORE-5a2 P1). */
    public static final byte CHANNEL_READ = 1;
    public static final byte CHANNEL_REPL = 2;
    public static final byte CHANNEL_BULK = 3;

    /** Wire constants (D-139 as amended by C9). */
    public static final int DATA_MAX = 4 << 20;
    public static final int OPEN_MAX = 4;
    public static final long HOLD_AT = 8L << 20;
    public static final long RESUME_AT = 4L << 20;

    private ReplWire() { }

    public static byte[] address(String owner, UUID journal, long n) {
        byte[] o = owner.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(2 + o.length + 24).putShort((short) o.length).put(o).putLong(journal.getMostSignificantBits())
                .putLong(journal.getLeastSignificantBits()).putLong(n).array();
    }

    public static byte[] open(String owner, UUID journal, long n, long length, byte[] header) {
        byte[] a = address(owner, journal, n);
        return ByteBuffer.allocate(a.length + 8 + header.length).put(a).putLong(length).put(header).array();
    }

    public static byte[] data(String owner, UUID journal, long n, long pos, byte[] bytes) {
        return data(owner, journal, n, pos, bytes, 0, bytes.length);
    }

    public static byte[] data(String owner, UUID journal, long n, long pos, byte[] bytes, int off, int len) {
        byte[] a = address(owner, journal, n);
        return ByteBuffer.allocate(a.length + 8 + len).put(a).putLong(pos).put(bytes, off, len).array();
    }

    public static byte[] seal(String owner, UUID journal, long n, long end, int crc32c) {
        byte[] a = address(owner, journal, n);
        return ByteBuffer.allocate(a.length + 12).put(a).putLong(end).putInt(crc32c).array();
    }

    public static byte[] receipt(String owner, UUID journal, long n, long end, int state) {
        byte[] a = address(owner, journal, n);
        return ByteBuffer.allocate(a.length + 9).put(a).putLong(end).put((byte) state).array();
    }

    public static byte[] refuse(String owner, UUID journal, long n, int reason, String detail) {
        byte[] a = address(owner, journal, n);
        byte[] d = detail.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(a.length + 3 + d.length).put(a).put((byte) reason).putShort((short) d.length).put(d).array();
    }

    /**
     * A segment header as CORE-3 item 1 writes it: {@code FJS1} · version 1 · journal id · u16 L · owner · n u64 · created ms u64
     * · crc32c u32 over the bytes before it.
     */
    public static byte[] header(String owner, UUID journal, long n, long createdMs) {
        byte[] o = owner.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(43 + o.length);
        b.putInt(0x464A5331).put((byte) 1).putLong(journal.getMostSignificantBits()).putLong(journal.getLeastSignificantBits())
                .putShort((short) o.length).put(o).putLong(n).putLong(createdMs);
        b.putInt(crc32c(b.array(), 0, b.capacity() - 4));
        return b.array();
    }

    public static int crc32c(byte[] b, int off, int len) {
        CRC32C c = new CRC32C();
        c.update(b, off, len);
        return (int) c.getValue();
    }

    public static int crc32c(byte[] b) {
        return crc32c(b, 0, b.length);
    }

    /** A RECEIPT or REFUSE as it came: the frame's type and its fields. */
    public record Answer(int type, String owner, UUID journal, long n, long end, int state, int reason, String detail) {

        public boolean is(int type, long n, int stateOrReason) {
            return this.type == type && this.n == n && (type == RECEIPT ? state : reason) == stateOrReason;
        }

        @Override public String toString() {
            return type == RECEIPT ? "RECEIPT(" + owner + "/" + n + " end " + end + " " + stateName(state) + ")"
                    : type == REFUSE ? "REFUSE(" + owner + "/" + n + " " + reasonName(reason) + ": " + detail + ")" : "frame type " + type;
        }
    }

    /** The answer a holder's frame carries; any other type is an AssertionError naming it. */
    public static Answer answer(byte[] frame) {
        int type = RawPeer.type(frame);
        ByteBuffer p = RawPeer.payload(frame);
        if (type != RECEIPT && type != REFUSE) {
            throw new AssertionError("a holder sent a frame of type " + type + ": " + HexFormat.of().formatHex(frame, 0, Math.min(frame.length, 64)));
        }
        byte[] o = new byte[Short.toUnsignedInt(p.getShort())];
        p.get(o);
        String owner = new String(o, StandardCharsets.UTF_8);
        UUID journal = new UUID(p.getLong(), p.getLong());
        long n = p.getLong();
        if (type == RECEIPT) {
            long end = p.getLong();
            int state = Byte.toUnsignedInt(p.get());
            if (p.hasRemaining()) throw new AssertionError("a RECEIPT with " + p.remaining() + " bytes after its state");
            return new Answer(type, owner, journal, n, end, state, 0, null);
        }
        int reason = Byte.toUnsignedInt(p.get());
        byte[] d = new byte[Short.toUnsignedInt(p.getShort())];
        p.get(d);
        if (p.hasRemaining()) throw new AssertionError("a REFUSE with " + p.remaining() + " bytes after its detail");
        return new Answer(type, owner, journal, n, -1, 0, reason, new String(d, StandardCharsets.UTF_8));
    }

    static String stateName(int s) {
        return switch (s) {
            case OPENED -> "OPENED";
            case WRITTEN -> "WRITTEN";
            case SEALED -> "SEALED";
            default -> "state " + s;
        };
    }

    static String reasonName(int r) {
        return switch (r) {
            case FULL -> "FULL";
            case FAILED -> "FAILED";
            case MISMATCH -> "MISMATCH";
            default -> "reason " + r;
        };
    }

    /** {@code n} bytes, every one of them the byte of {@code marker} at its offset modulo its length (a marker HeldFiles finds). */
    public static byte[] filled(String marker, int n) {
        byte[] m = marker.getBytes(StandardCharsets.US_ASCII);
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = m[i % m.length];
        return b;
    }

    /** A copy of {@code b} with byte {@code at} XOR 1. */
    public static byte[] flipped(byte[] b, int at) {
        byte[] c = Arrays.copyOf(b, b.length);
        c[at] ^= 1;
        return c;
    }
}
