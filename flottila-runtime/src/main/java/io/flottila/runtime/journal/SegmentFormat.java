package io.flottila.runtime.journal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * The segment file of one journal (CORE-3 item 1), big-endian. Header: {@code magic FJS1} · {@code version u8} ·
 * {@code journal id (UUID)} · {@code node id (u16 length, UTF-8)} · {@code n u64} · {@code created ms u64} ·
 * {@code crc32c u32} (over the header's other bytes); then records; at seal a footer index of one entry per record,
 * {@code message id (u16 length)} · {@code step u32} · {@code part (u16 length)} · {@code position u64} ·
 * {@code length u64} (a large record, CORE-3 item 4, may be longer than a u32 says), and a {@value #TRAILER}-byte
 * trailer: {@code index position u64} · {@code count u32} · {@code sealed ms u64} · {@code index crc32c u32} ·
 * {@code crc32c u32} (over the trailer's first 24 bytes) · {@code magic FJSE}. Neither CRC covers the records: each
 * record carries its own.
 *
 * <p>The identity file {@code NODE}: {@code magic FJN1} · {@code version u8} · {@code journal id} · {@code node id
 * (u16 length)} · {@code crc32c u32}.
 */
final class SegmentFormat {

    static final int MAGIC = 0x464A5331;
    static final int END_MAGIC = 0x464A5345;
    static final int NODE_MAGIC = 0x464A4E31;
    static final byte VERSION = 1;
    static final int TRAILER = 32;
    private static final int TRAILER_CRC_AT = 24;
    private static final int UUID_AT = 5;
    /** The bytes of an identity file or a segment header up to the end of its journal id. */
    static final int ID_END = UUID_AT + 16;

    /** A trailer whose magic and CRC held: where the index lies, how many entries it has and its CRC. */
    record Trailer(long indexPosition, int count, long sealedMs, int indexCrc) { }

    /** A footer: its bytes (index then trailer), the index's length and CRC. */
    record Footer(ByteBuffer bytes, int indexLength, int indexCrc) { }

    private final UUID journal;
    private final byte[] node;
    private final int headerLength;

    SegmentFormat(UUID journal, String node) {
        this.journal = journal;
        this.node = node.getBytes(StandardCharsets.UTF_8);
        this.headerLength = 4 + 1 + 16 + 2 + this.node.length + 8 + 8 + 4;
    }

    UUID journal() { return journal; }

    int headerLength() { return headerLength; }

    ByteBuffer header(long n, long createdMs) {
        ByteBuffer b = ByteBuffer.allocate(headerLength);
        b.putInt(MAGIC).put(VERSION).putLong(journal.getMostSignificantBits()).putLong(journal.getLeastSignificantBits())
                .putShort((short) node.length).put(node).putLong(n).putLong(createdMs);
        b.putInt(JournalIo.crc(b.array(), 0, headerLength - 4));
        return b.flip();
    }

    /** True when the last four bytes of {@code header} (of {@link #headerLength()} bytes) are the CRC32C of the others. */
    boolean crcHolds(byte[] header) {
        return ByteBuffer.wrap(header).getInt(headerLength - 4) == JournalIo.crc(header, 0, headerLength - 4);
    }

    /** True when {@code header} is the header this journal writes for segment {@code n} (whatever its created ms). */
    boolean ours(byte[] header, long n) {
        long created = ByteBuffer.wrap(header).getLong(headerLength - 12);
        return Arrays.equals(header, header(n, created).array());
    }

    /** The identity file's bytes for this journal id and node. */
    byte[] identity() {
        ByteBuffer b = ByteBuffer.allocate(4 + 1 + 16 + 2 + node.length + 4);
        b.putInt(NODE_MAGIC).put(VERSION).putLong(journal.getMostSignificantBits()).putLong(journal.getLeastSignificantBits())
                .putShort((short) node.length).put(node);
        b.putInt(JournalIo.crc(b.array(), 0, b.capacity() - 4));
        return b.array();
    }

    /** The journal id an identity file names, or null when it is too short to name one. */
    static UUID journalOf(byte[] identity) {
        if (identity.length < UUID_AT + 16) return null;
        ByteBuffer b = ByteBuffer.wrap(identity);
        return new UUID(b.getLong(UUID_AT), b.getLong(UUID_AT + 8));
    }

    static Footer footer(List<JournalIndexEntry> entries, long indexPosition, long sealedMs) {
        List<byte[]> names = new ArrayList<>(entries.size() * 2);
        int length = 0;
        for (JournalIndexEntry e : entries) {
            byte[] id = e.messageId().getBytes(StandardCharsets.UTF_8);
            byte[] part = e.part().getBytes(StandardCharsets.UTF_8);
            names.add(id);
            names.add(part);
            length += 2 + id.length + 4 + 2 + part.length + 8 + 8;
        }
        ByteBuffer b = ByteBuffer.allocate(length + TRAILER);
        for (int i = 0; i < entries.size(); i++) {
            JournalIndexEntry e = entries.get(i);
            byte[] id = names.get(2 * i);
            byte[] part = names.get(2 * i + 1);
            b.putShort((short) id.length).put(id).putInt(e.step()).putShort((short) part.length).put(part)
                    .putLong(e.ref().position()).putLong(e.ref().length());
        }
        int indexCrc = JournalIo.crc(b.array(), 0, length);
        b.putLong(indexPosition).putInt(entries.size()).putLong(sealedMs).putInt(indexCrc);
        b.putInt(JournalIo.crc(b.array(), length, TRAILER_CRC_AT)).putInt(END_MAGIC);
        return new Footer(b.flip(), length, indexCrc);
    }

    /** The trailer in {@code t} (a segment's last {@value #TRAILER} bytes), or null when its magic or its CRC fails. */
    static Trailer trailer(byte[] t) {
        ByteBuffer b = ByteBuffer.wrap(t);
        if (b.getInt(TRAILER_CRC_AT + 4) != END_MAGIC || b.getInt(TRAILER_CRC_AT) != JournalIo.crc(t, 0, TRAILER_CRC_AT)) return null;
        return new Trailer(b.getLong(0), b.getInt(8), b.getLong(12), b.getInt(20));
    }

    /** The entries of a footer index whose CRC held. */
    static List<JournalIndexEntry> entries(byte[] index, long segment, int count) {
        ByteBuffer b = ByteBuffer.wrap(index);
        List<JournalIndexEntry> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String id = name(b);
            int step = b.getInt();
            String part = name(b);
            out.add(new JournalIndexEntry(id, step, part, new JournalRef(segment, b.getLong(), b.getLong())));
        }
        return out;
    }

    private static String name(ByteBuffer b) {
        byte[] n = new byte[Short.toUnsignedInt(b.getShort())];
        b.get(n);
        return new String(n, StandardCharsets.UTF_8);
    }
}
