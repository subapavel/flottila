package io.flottila.runtime.journal;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * A segment record, format v1 (CORE-3 item 2), big-endian: {@code crc32c u32} · {@code length u32} · {@code version u8} ·
 * {@code kind u8} · {@code tenant key id u32} · {@code plain size u64} · {@code message id (u16 length, UTF-8)} ·
 * {@code step u32} · {@code part (u16 length, UTF-8)} · {@code salt[16]} · AEAD chunks. The chunks are {@link ChunkedAead}
 * under HKDF-SHA256(tenant key, salt, {@value #INFO}): AES-256-GCM over {@value #CHUNK}-byte plaintext chunks with one
 * 16-byte tag each (the empty body is one empty last chunk), the record's last bytes. Every chunk authenticates the header
 * from {@code length} to the salt as AAD, so no header byte changes unnoticed. The bytes do not depend on where the record
 * lies, so a copy elsewhere is byte-identical.
 *
 * <p>Two kinds. A body record ({@value #KIND_BODY}, at most {@code FLOTTILA_JOURNAL_RECORD_MAX} of plaintext) is whole:
 * {@code length} is the record's length and the CRC32C covers every byte after it. A large record ({@value #KIND_LARGE},
 * CORE-3 item 4: a segment of its own, written while its body streams in) is longer than a u32 may say and its plain size
 * is known only at its end: {@code length} is the header's length and the CRC32C covers the header alone; the chunks run
 * to the record's end, {@code plain size} decides how many there are, and their tags alone authenticate them. Its chunks
 * but the last authenticate the header with the plain size as zero (all a streaming writer knows then); the last one
 * authenticates it with the plain size, so a cut, a swap or an edited size fails a tag.
 */
final class RecordFormat {

    static final byte VERSION = 1;
    static final byte KIND_BODY = 1;
    static final byte KIND_LARGE = 2;
    static final int CHUNK = 1 << 20;
    /** The largest plaintext of any record: the chunk counter has 31 bits beside the last flag. */
    static final long MAX_PLAIN = (long) Integer.MAX_VALUE * CHUNK;
    /** Every header byte but the two names' bytes. */
    static final int FIXED = 4 + 4 + 1 + 1 + 4 + 8 + 2 + 4 + 2 + ChunkedAead.SALT_BYTES;
    /** The shortest record: empty names, an empty body, one tag. */
    static final int MIN_LENGTH = FIXED + ChunkedAead.TAG_BYTES;
    /** A name's u16 length bound. */
    static final int NAME_MAX = 0xFFFF;
    /** The longest header: both names at their bound. */
    static final int MAX_HEADER = FIXED + 2 * NAME_MAX;
    private static final String INFO = "flottila-journal-v1";
    private static final int STEP_AT_WITHOUT_ID = 4 + 4 + 1 + 1 + 4 + 8 + 2;
    private static final int PLAIN_AT = 14;

    /**
     * The plain fields of a record, where its chunks start ({@code end}) and its whole length ({@code length}: a body record's
     * {@code length} field, a large record's header and every chunk).
     */
    record Header(byte kind, int keyId, long plainSize, String messageId, int step, String part, byte[] salt, int end, long length) { }

    private RecordFormat() { }

    /** The encoded record of {@code r} under {@code key} with a fresh {@code salt}. */
    static ByteBuffer encode(JournalRecord r, SecretKey key, byte[] salt) throws GeneralSecurityException {
        byte[] id = name(r.messageId(), r.part());
        byte[] part = r.part().getBytes(StandardCharsets.UTF_8);
        byte[] body = r.body();
        int chunks = chunks(body.length);
        int end = FIXED + id.length + part.length;
        int length = Math.toIntExact((long) end + body.length + (long) chunks * ChunkedAead.TAG_BYTES);
        byte[] rec = new byte[length];
        ByteBuffer.wrap(rec).position(4).putInt(length).put(VERSION).put(KIND_BODY).putInt(r.tenantKeyId()).putLong(body.length)
                .putShort((short) id.length).put(id).putInt(r.step()).putShort((short) part.length).put(part).put(salt);
        SecretKey recordKey = recordKey(key, salt, r.tenantKeyId());
        int at = end;
        for (int i = 0, off = 0; i < chunks; i++) {
            int n = Math.min(CHUNK, body.length - off);
            Cipher c = ChunkedAead.cipher(recordKey, Cipher.ENCRYPT_MODE, salt, counter(i, chunks));
            c.updateAAD(rec, 4, end - 4);
            at += c.doFinal(body, off, n, rec, at);
            off += n;
        }
        ByteBuffer.wrap(rec).putInt(0, JournalIo.crc(rec, 4, length - 4));
        return ByteBuffer.wrap(rec);
    }

    /**
     * The header of a large record (its CRC32C over it), for a body of {@code plain} bytes; a streaming writer writes it
     * first with {@code plain} 0 and again once the body ended.
     */
    static byte[] largeHeader(String messageId, int step, String part, int keyId, long plain, byte[] salt) {
        byte[] id = name(messageId, part);
        byte[] p = part.getBytes(StandardCharsets.UTF_8);
        int end = FIXED + id.length + p.length;
        byte[] h = new byte[end];
        ByteBuffer.wrap(h).position(4).putInt(end).put(VERSION).put(KIND_LARGE).putInt(keyId).putLong(plain)
                .putShort((short) id.length).put(id).putInt(step).putShort((short) p.length).put(p).put(salt);
        ByteBuffer.wrap(h).putInt(0, JournalIo.crc(h, 4, end - 4));
        return h;
    }

    /** The message id's UTF-8 bytes; refused when it or the part is above {@value #NAME_MAX} bytes. */
    private static byte[] name(String messageId, String part) {
        byte[] id = messageId.getBytes(StandardCharsets.UTF_8);
        int partBytes = part.getBytes(StandardCharsets.UTF_8).length;
        if (id.length > NAME_MAX || partBytes > NAME_MAX) {
            throw new IllegalArgumentException("a message id and a part are at most " + NAME_MAX + " UTF-8 bytes, were " + id.length + " and " + partBytes);
        }
        return id;
    }

    /**
     * True when the first four bytes of {@code b} are the CRC32C of the bytes its {@code length} field says it covers, all
     * among the first {@code available}.
     */
    static boolean crcHolds(byte[] b, int available) {
        ByteBuffer in = ByteBuffer.wrap(b);
        long covered = Integer.toUnsignedLong(in.getInt(4));
        return covered >= FIXED && covered <= available && in.getInt(0) == JournalIo.crc(b, 4, (int) covered - 4);
    }

    /**
     * The header of a record whose first {@code available} bytes are {@code b} (all its CRC covers, or at least
     * {@link #MAX_HEADER}); null when it is not a v1 record whose sizes add up to its {@code length} field.
     */
    static Header header(byte[] b, int available) {
        ByteBuffer in = ByteBuffer.wrap(b, 0, available);
        byte kind = in.get(9);
        if (in.get(8) != VERSION || (kind != KIND_BODY && kind != KIND_LARGE)) return null;
        int idLength = Short.toUnsignedInt(in.getShort(22));
        int stepAt = STEP_AT_WITHOUT_ID + idLength;
        if (stepAt + 6 > available) return null;
        int partLength = Short.toUnsignedInt(in.getShort(stepAt + 4));
        int end = FIXED + idLength + partLength;
        long plain = in.getLong(PLAIN_AT);
        if (plain < 0 || plain > MAX_PLAIN) return null;
        long length = end + plain + (long) chunks(plain) * ChunkedAead.TAG_BYTES;
        if (Integer.toUnsignedLong(in.getInt(4)) != (kind == KIND_BODY ? length : end)) return null;
        byte[] salt = new byte[ChunkedAead.SALT_BYTES];
        in.get(end - ChunkedAead.SALT_BYTES, salt);
        return new Header(kind, in.getInt(10), plain, new String(b, 24, idLength, StandardCharsets.UTF_8), in.getInt(stepAt),
                new String(b, stepAt + 6, partLength, StandardCharsets.UTF_8), salt, end, length);
    }

    /** The AAD of the last chunk (the header after the CRC, from {@code header}'s first {@code end} bytes). */
    static byte[] aad(byte[] header, int end) {
        return Arrays.copyOfRange(header, 4, end);
    }

    /** The AAD of a large record's chunk before the last: {@code last}'s with the plain size as zero. */
    static byte[] sizeless(byte[] last) {
        byte[] a = last.clone();
        Arrays.fill(a, PLAIN_AT - 4, PLAIN_AT + 4, (byte) 0);
        return a;
    }

    /** The record's own key under its tenant key; an unusable tenant key is refused here, before any chunk. */
    static SecretKey recordKey(SecretKey tenantKey, byte[] salt, int keyId) throws GeneralSecurityException {
        return ChunkedAead.derive(tenantKey, salt, INFO, "tenant key " + keyId);
    }

    /** The plaintext of {@code rec} (whose CRC held and whose header is {@code h}); a chunk that fails its tag throws. */
    static byte[] decrypt(byte[] rec, Header h, SecretKey recordKey) throws GeneralSecurityException {
        byte[] plain = new byte[Math.toIntExact(h.plainSize())];
        int chunks = chunks(plain.length);
        byte[] last = aad(rec, h.end());
        byte[] other = h.kind() == KIND_LARGE ? sizeless(last) : last;
        for (int i = 0, at = h.end(), off = 0; i < chunks; i++) {
            int n = Math.min(CHUNK, plain.length - off);
            Cipher c = ChunkedAead.cipher(recordKey, Cipher.DECRYPT_MODE, h.salt(), counter(i, chunks));
            c.updateAAD(i == chunks - 1 ? last : other);
            c.doFinal(rec, at, n + ChunkedAead.TAG_BYTES, plain, off);
            at += n + ChunkedAead.TAG_BYTES;
            off += n;
        }
        return plain;
    }

    /** How many chunks a plaintext of {@code plain} bytes (at most {@link #MAX_PLAIN}) takes: at least one. */
    static int chunks(long plain) {
        return (int) Math.max(1, (plain + CHUNK - 1) / CHUNK);
    }

    /** Chunk {@code i}'s nonce counter of {@code chunks}: the last one flagged. */
    static int counter(int i, int chunks) {
        return i == chunks - 1 ? i | ChunkedAead.LAST : i;
    }
}
