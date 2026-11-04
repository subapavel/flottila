package io.flottila.runtime.journal;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * A segment record, format v1 (CORE-3 item 2), big-endian: {@code crc32c u32} (over every byte after it) ·
 * {@code length u32} · {@code version u8} · {@code kind u8} · {@code tenant key id u32} · {@code plain size u64} ·
 * {@code message id (u16 length, UTF-8)} · {@code step u32} · {@code part (u16 length, UTF-8)} · {@code salt[16]} ·
 * AEAD chunks. The chunks are {@link ChunkedAead} under HKDF-SHA256(tenant key, salt, {@value #INFO}): AES-256-GCM over
 * {@value #CHUNK}-byte plaintext chunks with one 16-byte tag each (the empty body is one empty last chunk), and every chunk
 * authenticates the header from {@code length} to the salt as AAD, so no header byte changes unnoticed. The bytes do not
 * depend on where the record lies, so a copy elsewhere is byte-identical.
 */
final class RecordFormat {

    static final byte VERSION = 1;
    static final byte KIND_BODY = 1;
    static final int CHUNK = 1 << 20;
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

    /** The plain fields of a record and where its chunks start. */
    record Header(int keyId, long plainSize, String messageId, int step, String part, byte[] salt, int end) { }

    private RecordFormat() { }

    /** The encoded record of {@code r} under {@code key} with a fresh {@code salt}. */
    static ByteBuffer encode(JournalRecord r, SecretKey key, byte[] salt) throws GeneralSecurityException {
        byte[] id = r.messageId().getBytes(StandardCharsets.UTF_8);
        byte[] part = r.part().getBytes(StandardCharsets.UTF_8);
        if (id.length > NAME_MAX || part.length > NAME_MAX) {
            throw new IllegalArgumentException("a message id and a part are at most " + NAME_MAX + " UTF-8 bytes, were " + id.length + " and " + part.length);
        }
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

    /** True when the first four bytes of {@code rec} are the CRC32C of the rest. */
    static boolean crcHolds(byte[] rec) {
        return ByteBuffer.wrap(rec).getInt(0) == JournalIo.crc(rec, 4, rec.length - 4);
    }

    /**
     * The header of a record of {@code length} bytes whose first {@code available} bytes are {@code b} (all of them, or at
     * least {@link #MAX_HEADER}); null when it is not a v1 body record whose sizes add up to its length.
     */
    static Header header(byte[] b, int available, long length) {
        ByteBuffer in = ByteBuffer.wrap(b, 0, available);
        if (Integer.toUnsignedLong(in.getInt(4)) != length || in.get(8) != VERSION || in.get(9) != KIND_BODY) return null;
        int idLength = Short.toUnsignedInt(in.getShort(22));
        int stepAt = STEP_AT_WITHOUT_ID + idLength;
        if (stepAt + 6 > available) return null;
        int partLength = Short.toUnsignedInt(in.getShort(stepAt + 4));
        int end = FIXED + idLength + partLength;
        long plain = in.getLong(14);
        if (plain < 0 || end + plain + (long) chunks(plain) * ChunkedAead.TAG_BYTES != length) return null;
        byte[] salt = new byte[ChunkedAead.SALT_BYTES];
        in.get(end - ChunkedAead.SALT_BYTES, salt);
        return new Header(in.getInt(10), plain, new String(b, 24, idLength, StandardCharsets.UTF_8), in.getInt(stepAt),
                new String(b, stepAt + 6, partLength, StandardCharsets.UTF_8), salt, end);
    }

    /** The record's own key under its tenant key; an unusable tenant key is refused here, before any chunk. */
    static SecretKey recordKey(SecretKey tenantKey, byte[] salt, int keyId) throws GeneralSecurityException {
        return ChunkedAead.derive(tenantKey, salt, INFO, "tenant key " + keyId);
    }

    /** The plaintext of {@code rec} (whose CRC held and whose header is {@code h}); a chunk that fails its tag throws. */
    static byte[] decrypt(byte[] rec, Header h, SecretKey recordKey) throws GeneralSecurityException {
        byte[] plain = new byte[Math.toIntExact(h.plainSize())];
        int chunks = chunks(plain.length);
        for (int i = 0, at = h.end(), off = 0; i < chunks; i++) {
            int n = Math.min(CHUNK, plain.length - off);
            Cipher c = ChunkedAead.cipher(recordKey, Cipher.DECRYPT_MODE, h.salt(), counter(i, chunks));
            c.updateAAD(rec, 4, h.end() - 4);
            c.doFinal(rec, at, n + ChunkedAead.TAG_BYTES, plain, off);
            at += n + ChunkedAead.TAG_BYTES;
            off += n;
        }
        return plain;
    }

    private static int chunks(long plain) {
        return (int) Math.max(1, (plain + CHUNK - 1) / CHUNK);
    }

    private static int counter(int i, int chunks) {
        return i == chunks - 1 ? i | ChunkedAead.LAST : i;
    }
}
