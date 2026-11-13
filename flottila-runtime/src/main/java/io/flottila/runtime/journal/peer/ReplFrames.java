package io.flottila.runtime.journal.peer;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * The payloads of the repl channel (CORE-5b1 "Wire"), big-endian, inside framing v1: what an owner sends a holder (OPEN, DATA,
 * SEAL) and what the holder answers (RECEIPT, REFUSE). Every frame starts with its segment's address, {@code u16 L · owner
 * UTF-8 (1..63 bytes) · journal id (u64 msb, u64 lsb) · n u64 (≥ 1)}. A payload not of its layout — a byte short or long, an
 * address out of its bounds, a DATA of no byte or past {@value #DATA_MAX}, a REFUSE detail past {@value #DETAIL_MAX} bytes — is
 * refused ({@link PeerRefusedException}: the link closes).
 *
 * <pre>
 * OPEN 3    = A · length u64 · header (exactly 43 + L bytes: the owner's segment header)
 * DATA 4    = A · pos u64 · bytes (1 .. 4 MiB; pos = the file offset, the header at 0)
 * SEAL 5    = A · end u64 · crc32c u32 (of the file's bytes [0, end))
 * RECEIPT 6 = A · end u64 · state u8 (1 OPENED, 2 WRITTEN, 3 SEALED)
 * REFUSE 8  = A · reason u8 (1 FULL, 2 FAILED, 3 MISMATCH) · u16 len · detail UTF-8
 * </pre>
 *
 * No DATA(n) goes before RECEIPT OPENED(n); HAVE (7) is CORE-5b3's, type 9 is reserved.
 */
final class ReplFrames {

    /** The most bytes one DATA carries (a wire constant, D-139). */
    static final int DATA_MAX = 4 << 20;
    /** The most bytes of a REFUSE's detail. */
    static final int DETAIL_MAX = 512;
    /** The longest owner (a DNS label). */
    static final int OWNER_MAX = 63;
    /** RECEIPT states. */
    static final int OPENED = 1;
    static final int WRITTEN = 2;
    static final int SEALED = 3;
    /** REFUSE reasons. */
    static final int FULL = 1;
    static final int FAILED = 2;
    static final int MISMATCH = 3;
    /** A segment header's bytes beside its owner: magic, version, journal id, owner length, n, created ms, CRC. */
    private static final int HEADER_FIXED = 4 + 1 + 16 + 2 + 8 + 8 + 4;

    private ReplFrames() { }

    /** A frame an owner sends its holder. */
    sealed interface ToHolder permits Open, Data, Seal { }

    /** The segment a frame is of: {@code owner}'s journal {@code journal}, segment {@code n}. */
    record Address(String owner, UUID journal, long n) {

        private byte[] ownerBytes() {
            return owner.getBytes(StandardCharsets.UTF_8);
        }

        private int length() {
            return 2 + ownerBytes().length + 16 + 8;
        }

        private ByteBuffer put(ByteBuffer b) {
            byte[] o = ownerBytes();
            return b.putShort((short) o.length).put(o).putLong(journal.getMostSignificantBits()).putLong(journal.getLeastSignificantBits()).putLong(n);
        }

        /** Reads an address off {@code p}: its owner 1..63 bytes, its n at least 1. */
        static Address decode(ByteBuffer p) throws PeerRefusedException {
            if (p.remaining() < 2) throw new PeerRefusedException("a repl frame of " + p.remaining() + " bytes: shorter than its address");
            int length = Short.toUnsignedInt(p.getShort());
            if (length < 1 || length > OWNER_MAX || p.remaining() < length + 24) {
                throw new PeerRefusedException("a repl frame's address names an owner of " + length + " bytes (1 to " + OWNER_MAX + ") in "
                        + p.remaining() + " bytes");
            }
            byte[] o = new byte[length];
            p.get(o);
            UUID journal = new UUID(p.getLong(), p.getLong());
            long n = p.getLong();
            if (n < 1) throw new PeerRefusedException("a repl frame's address names segment " + n + " (segments are numbered from 1)");
            return new Address(new String(o, StandardCharsets.UTF_8), journal, n);
        }
    }

    /** OPEN: the owner opens segment {@code address.n()} of up to {@code length} bytes on the holder, its header first. */
    record Open(Address address, long length, byte[] header) implements ToHolder {

        byte[] encode() {
            return address.put(ByteBuffer.allocate(address.length() + 8 + header.length)).putLong(length).put(header).array();
        }

        static Open decode(ByteBuffer p) throws PeerRefusedException {
            Address a = Address.decode(p);
            int header = HEADER_FIXED + a.ownerBytes().length;
            if (p.remaining() != 8 + header) throw new PeerRefusedException("an OPEN with " + p.remaining() + " bytes after its address, not " + (8 + header));
            long length = p.getLong();
            byte[] h = new byte[header];
            p.get(h);
            return new Open(a, length, h);
        }
    }

    /** DATA: {@code bytes} of the segment at file offset {@code pos}. */
    record Data(Address address, long pos, byte[] bytes) implements ToHolder {

        byte[] encode() {
            return address.put(ByteBuffer.allocate(address.length() + 8 + bytes.length)).putLong(pos).put(bytes).array();
        }

        static Data decode(ByteBuffer p) throws PeerRefusedException {
            Address a = Address.decode(p);
            if (p.remaining() < 8 + 1 || p.remaining() > 8 + DATA_MAX) {
                throw new PeerRefusedException("a DATA with " + p.remaining() + " bytes after its address: its pos and 1 to " + DATA_MAX + " bytes");
            }
            long pos = p.getLong();
            byte[] b = new byte[p.remaining()];
            p.get(b);
            return new Data(a, pos, b);
        }
    }

    /** SEAL: the segment ends at {@code end} (its footer sent as DATA before), the file's bytes [0, end) of CRC32C {@code crc32c}. */
    record Seal(Address address, long end, int crc32c) implements ToHolder {

        byte[] encode() {
            return address.put(ByteBuffer.allocate(address.length() + 12)).putLong(end).putInt(crc32c).array();
        }

        static Seal decode(ByteBuffer p) throws PeerRefusedException {
            Address a = Address.decode(p);
            if (p.remaining() != 12) throw new PeerRefusedException("a SEAL with " + p.remaining() + " bytes after its address, not 12");
            return new Seal(a, p.getLong(), p.getInt());
        }
    }

    /** RECEIPT: the holder holds the segment up to {@code end} in {@code state} (OPENED, WRITTEN, SEALED). */
    record Receipt(Address address, long end, int state) {

        byte[] encode() {
            return address.put(ByteBuffer.allocate(address.length() + 9)).putLong(end).put((byte) state).array();
        }

        static Receipt decode(ByteBuffer p) throws PeerRefusedException {
            Address a = Address.decode(p);
            if (p.remaining() != 9) throw new PeerRefusedException("a RECEIPT with " + p.remaining() + " bytes after its address, not 9");
            return new Receipt(a, p.getLong(), Byte.toUnsignedInt(p.get()));
        }
    }

    /**
     * REFUSE: the holder does not hold the segment for {@code reason} (FULL, FAILED, MISMATCH); {@code detail} says why. An encoded
     * detail is its first {@value #DETAIL_MAX} bytes of UTF-8 at most, cut at a character's end.
     */
    record Refuse(Address address, int reason, String detail) {

        byte[] encode() {
            byte[] d = utf8Prefix(detail, DETAIL_MAX);
            return address.put(ByteBuffer.allocate(address.length() + 3 + d.length)).put((byte) reason).putShort((short) d.length).put(d).array();
        }

        static Refuse decode(ByteBuffer p) throws PeerRefusedException {
            Address a = Address.decode(p);
            // -4: no REFUSE is that short (3 + -4 matches no remaining count)
            int length = p.remaining() >= 3 ? Short.toUnsignedInt(p.getShort(p.position() + 1)) : -4;
            if (length > DETAIL_MAX || p.remaining() != 3 + length) {
                throw new PeerRefusedException("a REFUSE with " + p.remaining() + " bytes after its address naming a detail of " + length
                        + " bytes (at most " + DETAIL_MAX + ")");
            }
            int reason = Byte.toUnsignedInt(p.get());
            p.getShort();
            byte[] d = new byte[length];
            p.get(d);
            return new Refuse(a, reason, new String(d, StandardCharsets.UTF_8));
        }
    }

    /** The UTF-8 of {@code s}, cut to at most {@code max} bytes at the end of a character. */
    private static byte[] utf8Prefix(String s, int max) {
        CharsetEncoder e = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
        ByteBuffer out = ByteBuffer.allocate(max);
        e.encode(CharBuffer.wrap(s), out, true);   // stops before the character that does not fit
        return Arrays.copyOf(out.array(), out.position());
    }
}
