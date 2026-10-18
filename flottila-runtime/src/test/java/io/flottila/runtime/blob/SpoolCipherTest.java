package io.flottila.runtime.blob;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Segmented AES-GCM: a stream on read, authenticated per segment, truncation and reordering detected. */
class SpoolCipherTest {

    static byte[] body(int n) { byte[] b = new byte[n]; for (int i = 0; i < n; i++) b[i] = (byte) (i * 13 + 5); return b; }

    static void write(SpoolCipher cipher, Path file, byte[] plain) throws IOException {
        try (OutputStream out = cipher.encrypting(file)) {
            for (int off = 0; off < plain.length; off += 70_000) out.write(plain, off, Math.min(70_000, plain.length - off));
        }
    }

    /** Reads until EOF or failure; returns the bytes delivered before the failure, which the caller compares with the plaintext. */
    static byte[] readUntilFailure(SpoolCipher cipher, Path file, Class<? extends IOException> expected) throws IOException {
        java.io.ByteArrayOutputStream got = new java.io.ByteArrayOutputStream();
        try (InputStream in = cipher.decrypting(file)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) >= 0) got.write(buf, 0, n);
            if (expected != null) throw new AssertionError("expected " + expected.getSimpleName() + " but the read ended cleanly");
        } catch (IOException e) {
            if (expected == null || !expected.isInstance(e)) throw e;
        }
        return got.toByteArray();
    }

    @Test
    void multiSegmentBodiesRoundTripAndNeverLookAlikeOnDisk(@TempDir Path dir) throws IOException {
        SpoolCipher cipher = new SpoolCipher(TieredBlobStoreTest.key());
        byte[] plain = body(3 * SpoolCipher.SEGMENT + 12_345);
        write(cipher, dir.resolve("a"), plain);
        write(cipher, dir.resolve("b"), plain);
        assertFalse(Arrays.equals(Files.readAllBytes(dir.resolve("a")), Files.readAllBytes(dir.resolve("b"))), "a fresh salt, so a fresh key and nonce prefix, per file");
        assertArrayEquals(plain, readUntilFailure(cipher, dir.resolve("a"), null));
        long expectedSize = SpoolCipher.SALT_BYTES + 4L * 5 + plain.length + 4L * SpoolCipher.TAG_BYTES;   // 4 segments: 3 full + the last
        assertEquals(expectedSize, Files.size(dir.resolve("a")), "salt, four headers, ciphertext, four tags");
        write(cipher, dir.resolve("empty"), new byte[0]);
        assertArrayEquals(new byte[0], readUntilFailure(cipher, dir.resolve("empty"), null));
        try (InputStream in = cipher.decrypting(dir.resolve("a"))) { assertEquals(plain[0] & 0xff, in.read()); assertEquals(0, in.read(new byte[0], 0, 0)); }
        OutputStream single = cipher.encrypting(dir.resolve("single"));
        for (int i = 0; i <= SpoolCipher.SEGMENT; i++) single.write(i);   // the one-byte write path, across a segment boundary
        single.close();
        single.close();                                       // closing twice seals once
        byte[] expected = new byte[SpoolCipher.SEGMENT + 1];
        for (int i = 0; i < expected.length; i++) expected[i] = (byte) i;
        assertArrayEquals(expected, readUntilFailure(cipher, dir.resolve("single"), null));
        try (InputStream in = cipher.decrypting(dir.resolve("a"))) {   // tiny reads straddling a segment boundary
            byte[] got = new byte[plain.length];
            int off = 0, n;
            while ((n = in.read(got, off, Math.min(7, got.length - off))) > 0) off += n;
            assertEquals(plain.length, off);
            assertArrayEquals(plain, got);
            assertEquals(-1, in.read());
            assertEquals(-1, in.read(), "end of stream stays end of stream");
        }
    }

    @Test
    void aFlippedByteFailsItsSegmentAfterEveryEarlierSegmentWasDeliveredIntact(@TempDir Path dir) throws IOException {
        SpoolCipher cipher = new SpoolCipher(TieredBlobStoreTest.key());
        byte[] plain = body(3 * SpoolCipher.SEGMENT + 100);
        Path file = dir.resolve("f");
        write(cipher, file, plain);
        byte[] disk = Files.readAllBytes(file);
        int thirdSegmentBody = SpoolCipher.SALT_BYTES + 2 * (5 + SpoolCipher.SEGMENT + SpoolCipher.TAG_BYTES) + 5 + 1000;
        disk[thirdSegmentBody] ^= 0x01;
        Files.write(file, disk);
        byte[] delivered = readUntilFailure(cipher, file, IOException.class);
        assertEquals(2 * SpoolCipher.SEGMENT, delivered.length, "exactly the two authenticated segments came out, nothing of the third");
        assertArrayEquals(Arrays.copyOf(plain, 2 * SpoolCipher.SEGMENT), delivered);
    }

    @Test
    void truncationReorderingAndBadLengthsAreDetected(@TempDir Path dir) throws IOException {
        SpoolCipher cipher = new SpoolCipher(TieredBlobStoreTest.key());
        byte[] plain = body(2 * SpoolCipher.SEGMENT + 7);
        Path file = dir.resolve("f");
        write(cipher, file, plain);
        byte[] disk = Files.readAllBytes(file);
        int firstSegmentEnd = SpoolCipher.SALT_BYTES + 5 + SpoolCipher.SEGMENT + SpoolCipher.TAG_BYTES;
        Files.write(dir.resolve("cut-clean"), Arrays.copyOf(disk, firstSegmentEnd));                 // whole first segment, then nothing
        assertEquals(SpoolCipher.SEGMENT, readUntilFailure(cipher, dir.resolve("cut-clean"), java.io.EOFException.class).length, "the first segment is genuine, the missing last segment is the error");
        Files.write(dir.resolve("cut-inside"), Arrays.copyOf(disk, firstSegmentEnd + 300));          // inside the second segment
        assertEquals(SpoolCipher.SEGMENT, readUntilFailure(cipher, dir.resolve("cut-inside"), java.io.EOFException.class).length);
        Files.write(dir.resolve("cut-header"), Arrays.copyOf(disk, firstSegmentEnd + 2));            // inside a header
        readUntilFailure(cipher, dir.resolve("cut-header"), java.io.EOFException.class);
        byte[] swapped = disk.clone();                                                                 // segment 2 presented as segment 1: the counter is in the nonce
        int second = firstSegmentEnd;
        int secondLen = 5 + SpoolCipher.SEGMENT + SpoolCipher.TAG_BYTES;
        System.arraycopy(disk, second, swapped, SpoolCipher.SALT_BYTES, secondLen);
        System.arraycopy(disk, SpoolCipher.SALT_BYTES, swapped, SpoolCipher.SALT_BYTES + secondLen, secondLen);
        Files.write(dir.resolve("swapped"), swapped);
        assertEquals(0, readUntilFailure(cipher, dir.resolve("swapped"), IOException.class).length, "the first segment presented is not segment 0: nothing is delivered");
        byte[] lastFlagCleared = disk.clone();                                                         // the last flag participates in the nonce
        lastFlagCleared[disk.length - (5 + 7 + SpoolCipher.TAG_BYTES)] = 0;
        Files.write(dir.resolve("flag"), lastFlagCleared);
        assertEquals(2 * SpoolCipher.SEGMENT, readUntilFailure(cipher, dir.resolve("flag"), IOException.class).length);
        byte[] badLength = disk.clone();
        badLength[SpoolCipher.SALT_BYTES + 1] = (byte) 0x7f;                                          // absurd segment length
        Files.write(dir.resolve("length"), badLength);
        assertEquals(0, readUntilFailure(cipher, dir.resolve("length"), IOException.class).length);
        byte[] oneTooLong = disk.clone();                                                              // one byte above a full segment + tag
        java.nio.ByteBuffer.wrap(oneTooLong, SpoolCipher.SALT_BYTES + 1, 4).putInt(SpoolCipher.SEGMENT + SpoolCipher.TAG_BYTES + 1);
        Files.write(dir.resolve("long"), oneTooLong);
        assertEquals(0, readUntilFailure(cipher, dir.resolve("long"), IOException.class).length);
        byte[] tooShort = disk.clone();                                                                // shorter than a tag
        tooShort[SpoolCipher.SALT_BYTES + 1] = 0; tooShort[SpoolCipher.SALT_BYTES + 2] = 0; tooShort[SpoolCipher.SALT_BYTES + 3] = 0; tooShort[SpoolCipher.SALT_BYTES + 4] = 3;
        Files.write(dir.resolve("tiny"), tooShort);
        assertEquals(0, readUntilFailure(cipher, dir.resolve("tiny"), IOException.class).length);
    }

    @Test
    void everyFileIsEncryptedUnderItsOwnDerivedKeyNeverUnderTheMasterKey(@TempDir Path dir) throws Exception {
        javax.crypto.SecretKey master = TieredBlobStoreTest.key();
        SpoolCipher cipher = new SpoolCipher(master);
        byte[] plain = body(1000);
        Path file = dir.resolve("f");
        write(cipher, file, plain);
        byte[] disk = Files.readAllBytes(file);
        byte[] salt = Arrays.copyOf(disk, SpoolCipher.SALT_BYTES);
        int length = java.nio.ByteBuffer.wrap(disk, SpoolCipher.SALT_BYTES + 1, 4).getInt();
        byte[] ct = Arrays.copyOfRange(disk, SpoolCipher.SALT_BYTES + 5, SpoolCipher.SALT_BYTES + 5 + length);
        byte[] nonce = java.nio.ByteBuffer.allocate(12).put(salt, 0, 8).putInt(0x80000000).array();   // segment 0, flagged last
        javax.crypto.Cipher withMaster = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        withMaster.init(javax.crypto.Cipher.DECRYPT_MODE, master, new javax.crypto.spec.GCMParameterSpec(128, nonce));
        assertThrows(javax.crypto.AEADBadTagException.class, () -> withMaster.doFinal(ct), "the master key never touches a segment");
        javax.crypto.Cipher withDerived = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        withDerived.init(javax.crypto.Cipher.DECRYPT_MODE, SpoolCipher.fileKey(master, salt), new javax.crypto.spec.GCMParameterSpec(128, nonce));
        assertArrayEquals(plain, withDerived.doFinal(ct), "HKDF-SHA256(master, salt) is the file's key — the format is reproducible from this test alone");
        byte[] trailing = Arrays.copyOf(disk, disk.length + 1);                                          // a byte after the last segment
        Files.write(dir.resolve("trailing"), trailing);
        assertArrayEquals(plain, readUntilFailure(cipher, dir.resolve("trailing"), IOException.class), "the body is delivered, the trailing byte is an error at the end");
    }

    @Test
    void aFileTooShortForItsPrefixAndAnUnusableKeyAreIoExceptionsAtOpen(@TempDir Path dir) throws IOException {
        SpoolCipher cipher = new SpoolCipher(TieredBlobStoreTest.key());
        Files.write(dir.resolve("short"), new byte[5]);
        IOException e = assertThrows(IOException.class, () -> cipher.decrypting(dir.resolve("short")));
        assertTrue(e.getMessage().contains("spool cipher"), e.getMessage());
        SpoolCipher wrong = new SpoolCipher(new SecretKeySpec(new byte[8], "DES"));
        assertThrows(IOException.class, () -> wrong.encrypting(dir.resolve("never")));
        write(cipher, dir.resolve("ok"), new byte[] {1});
        assertThrows(IOException.class, () -> wrong.decrypting(dir.resolve("ok")));
    }
}
