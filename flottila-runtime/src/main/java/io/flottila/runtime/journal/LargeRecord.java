package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.BodyTooLargeException;
import io.flottila.api.Hash;
import io.flottila.api.Hasher;
import io.vertx.core.buffer.Buffer;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.function.LongConsumer;

/**
 * A large record ({@link RecordFormat#KIND_LARGE}, CORE-3 item 4) written while its body streams in, and read back one
 * chunk at a time: either side holds one {@value RecordFormat#CHUNK}-byte chunk at a time (its plaintext, its ciphertext,
 * the hasher's copy), never the body.
 */
final class LargeRecord {

    private static final int CHUNK = RecordFormat.CHUNK;
    private static final int TAG = ChunkedAead.TAG_BYTES;
    /** The cipher is fed in slices of this size: a one-shot call per chunk is too rare for the JIT to compile GCM (D-041). */
    private static final int SLICE = 64 * 1024;

    /** A record written: its plaintext's size, the record's whole length and the hash of the plaintext. */
    record Written(long size, long length, Hash hash) { }

    private LargeRecord() { }

    /**
     * Writes the large record of {@code key} at {@code ch}'s position: its header with the plain size 0, then each chunk as
     * it fills (a one-byte look-ahead tells the last), then the header again with the plain size. Reads {@code body} to its
     * end once, hashing every byte once on the way; a body above {@code limit} stops there with a
     * {@link BodyTooLargeException} naming the bytes seen so far. {@code room} takes each write's bytes before it is made (the
     * header, each chunk; not the header's rewrite in place): a refusal it throws stops the record there.
     *
     * @throws GeneralSecurityException the tenant key cannot derive the record's key, or the cipher cannot run
     */
    static Written write(FileChannel ch, BlobKey key, int keyId, SecretKey tenantKey, byte[] salt, InputStream body, long limit,
                         Hasher hasher, LongConsumer room) throws IOException, GeneralSecurityException {
        long at = ch.position();
        byte[] header = RecordFormat.largeHeader(key.messageId(), key.step(), key.part(), keyId, 0, salt);
        SecretKey recordKey = RecordFormat.recordKey(tenantKey, salt, keyId);
        room.accept(header.length);
        JournalIo.writeFully(ch, ByteBuffer.wrap(header));
        byte[] sizeless = RecordFormat.aad(header, header.length);
        Hasher.Session hash = hasher.begin();
        byte[] plain = new byte[CHUNK];
        byte[] sealed = new byte[CHUNK + TAG];
        long size = 0;
        int filled = body.readNBytes(plain, 0, CHUNK);
        for (int i = 0; ; i++) {
            size += filled;
            if (size > limit) throw new BodyTooLargeException(key, size, limit);
            hash.update(Buffer.buffer(filled).appendBytes(plain, 0, filled));
            int ahead = filled == CHUNK ? body.read() : -1;
            if (ahead < 0) {
                header = RecordFormat.largeHeader(key.messageId(), key.step(), key.part(), keyId, size, salt);
                room.accept(filled + TAG);
                JournalIo.writeFully(ch, ByteBuffer.wrap(sealed, 0, seal(recordKey, salt, i | ChunkedAead.LAST,
                        RecordFormat.aad(header, header.length), plain, filled, sealed)));
                ch.position(at);
                JournalIo.writeFully(ch, ByteBuffer.wrap(header));
                return new Written(size, header.length + size + (long) (i + 1) * TAG, hash.finish());
            }
            room.accept(filled + TAG);
            JournalIo.writeFully(ch, ByteBuffer.wrap(sealed, 0, seal(recordKey, salt, i, sizeless, plain, filled, sealed)));
            plain[0] = (byte) ahead;
            filled = 1 + body.readNBytes(plain, 1, CHUNK - 1);
        }
    }

    /** Encrypts {@code plain[0, n)} as the chunk of nonce counter {@code counter} into {@code out}; its length. */
    private static int seal(SecretKey key, byte[] salt, int counter, byte[] aad, byte[] plain, int n, byte[] out) throws GeneralSecurityException {
        Cipher c = ChunkedAead.cipher(key, Cipher.ENCRYPT_MODE, salt, counter);
        c.updateAAD(aad);
        int produced = 0;
        for (int off = 0; off < n; off += SLICE) produced += c.update(plain, off, Math.min(SLICE, n - off), out, produced);
        return produced + c.doFinal(out, produced);
    }

    /**
     * The plaintext of the large record at {@code ref} on {@code ch} (whose header {@code h}, from {@code head}, was
     * checked), read one chunk at a time; every byte it gives was authenticated by its chunk's tag. A chunk cut off, out of
     * place or edited fails the read as {@link JournalCorruptException}, before any byte of it. Closing it closes {@code ch}.
     */
    static InputStream reader(FileChannel ch, JournalRef ref, RecordFormat.Header h, SecretKey recordKey, byte[] head) {
        return new Reader(ch, ref, h, recordKey, head);
    }

    private static final class Reader extends InputStream {
        private final FileChannel ch;
        private final JournalRef ref;
        private final RecordFormat.Header h;
        private final SecretKey key;
        private final byte[] lastAad;
        private final byte[] otherAad;
        private final int chunks;
        private final byte[] sealed;
        private final byte[] plain;
        private int next;
        private int pos;
        private int have;

        Reader(FileChannel ch, JournalRef ref, RecordFormat.Header h, SecretKey key, byte[] head) {
            this.ch = ch;
            this.ref = ref;
            this.h = h;
            this.key = key;
            this.lastAad = RecordFormat.aad(head, h.end());
            this.otherAad = RecordFormat.sizeless(lastAad);
            this.chunks = RecordFormat.chunks(h.plainSize());
            int first = (int) Math.min(CHUNK, h.plainSize());
            this.plain = new byte[first];
            this.sealed = new byte[first + TAG];
        }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            if (len == 0) return 0;
            while (pos == have) {
                if (next == chunks) return -1;
                fill();
            }
            int k = Math.min(len, have - pos);
            System.arraycopy(plain, pos, b, off, k);
            pos += k;
            return k;
        }

        /** Reads, authenticates and decrypts chunk {@code next}. */
        private void fill() throws IOException {
            int n = (int) Math.min(CHUNK, h.plainSize() - (long) next * CHUNK);
            long at = ref.position() + h.end() + (long) next * (CHUNK + TAG);
            try {
                JournalIo.read(ch, at, sealed, n + TAG);
            } catch (EOFException cut) {
                throw Journal.corrupt(ref, "ends inside its chunk " + next + " of " + chunks, cut);
            }
            try {
                Cipher c = ChunkedAead.cipher(key, Cipher.DECRYPT_MODE, h.salt(), RecordFormat.counter(next, chunks));
                c.updateAAD(next == chunks - 1 ? lastAad : otherAad);
                int produced = 0;
                for (int off = 0; off < n + TAG; off += SLICE) produced += c.update(sealed, off, Math.min(SLICE, n + TAG - off), plain, produced);
                c.doFinal(plain, produced);
            } catch (GeneralSecurityException x) {
                throw Journal.corrupt(ref, "fails authentication in its chunk " + next + " of " + chunks, x);
            }
            pos = 0;
            have = n;
            next++;
        }

        @Override public void close() throws IOException {
            ch.close();
        }
    }
}
