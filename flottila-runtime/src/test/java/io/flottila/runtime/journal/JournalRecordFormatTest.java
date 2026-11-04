package io.flottila.runtime.journal;

import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;

import static io.flottila.runtime.journal.JournalFixture.START;
import static io.flottila.runtime.journal.JournalFixture.await;
import static io.flottila.runtime.journal.JournalFixture.body;
import static io.flottila.runtime.journal.JournalFixture.key;
import static io.flottila.runtime.journal.JournalFixture.keys;
import static io.flottila.runtime.journal.JournalFixture.live;
import static io.flottila.runtime.journal.JournalFixture.record;
import static io.flottila.runtime.journal.JournalFixture.recordBytes;
import static io.flottila.runtime.journal.JournalFixture.settings;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The record's key and chunk on disk, rebuilt from CORE-3 item 2 alone (the twin of SpoolCipherTest's derived-key case): a
 * record is encrypted under HKDF-SHA256(tenant key, its salt, "flottila-journal-v1") — never under the tenant key itself —
 * with the nonce salt[0..8) ‖ counter|LAST and the header bytes after the CRC as associated data.
 */
class JournalRecordFormatTest {

    @TempDir Path dir;

    @Test
    void everyRecordIsEncryptedUnderItsOwnDerivedKeyNeverUnderTheTenantKey() throws Exception {
        FakeClock clock = FakeClock.at(START);
        byte[][] salts = new byte[2][];
        try (Journal j = Journal.open(settings(dir).segmentAge(Duration.ofHours(1)).config(), clock, new FakeTimer(clock),
                keys(JournalFixture.KEY), JournalFiles.real())) {
            for (int i = 0; i < 2; i++) {
                Journal.Appended a = j.append(record("rf-" + i, 1000), live());
                await(a.forced());
                byte[] rec = recordBytes(dir.resolve("segments").resolve(a.ref().segment() + ".seg"), a.ref());
                salts[i] = decryptsUnderItsDerivedKeyOnly(rec, key(JournalFixture.KEY), body("rf-" + i, 1000));
            }
        }
        assertFalse(Arrays.equals(salts[0], salts[1]), "each record has a salt, so a key, of its own");
    }

    /** Checks {@code rec} by hand and returns its salt. */
    private static byte[] decryptsUnderItsDerivedKeyOnly(byte[] rec, SecretKey tenantKey, byte[] plain) throws Exception {
        // crc32c u32 · length u32 · version u8 · kind u8 · key id u32 · plain u64 · id (u16 + bytes) · step u32 · part (u16 + bytes) · salt[16]
        ByteBuffer b = ByteBuffer.wrap(rec);
        int idLength = Short.toUnsignedInt(b.getShort(22));
        int partAt = 24 + idLength + 4;
        int saltAt = partAt + 2 + Short.toUnsignedInt(b.getShort(partAt));
        int end = saltAt + 16;
        byte[] salt = Arrays.copyOfRange(rec, saltAt, end);
        byte[] chunk = Arrays.copyOfRange(rec, end, rec.length);   // one chunk: the body is below 1 MiB
        byte[] nonce = ByteBuffer.allocate(12).put(salt, 0, 8).putInt(0x80000000).array();   // chunk 0, flagged last

        Cipher withTenantKey = Cipher.getInstance("AES/GCM/NoPadding");
        withTenantKey.init(Cipher.DECRYPT_MODE, tenantKey, new GCMParameterSpec(128, nonce));
        withTenantKey.updateAAD(rec, 4, end - 4);
        assertThrows(AEADBadTagException.class, () -> withTenantKey.doFinal(chunk), "the tenant key never encrypts a record");

        Cipher withDerived = Cipher.getInstance("AES/GCM/NoPadding");
        withDerived.init(Cipher.DECRYPT_MODE, hkdfSha256(tenantKey.getEncoded(), salt, "flottila-journal-v1"), new GCMParameterSpec(128, nonce));
        withDerived.updateAAD(rec, 4, end - 4);
        assertArrayEquals(plain, withDerived.doFinal(chunk), "HKDF-SHA256(tenant key, salt) is the record's key: the format is reproducible from this test alone");
        return salt;
    }

    /** RFC 5869 with one output block: PRK = HMAC(salt, ikm); OKM = HMAC(PRK, info ‖ 0x01). */
    private static SecretKey hkdfSha256(byte[] ikm, byte[] salt, String info) throws Exception {
        Mac extract = Mac.getInstance("HmacSHA256");
        extract.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = extract.doFinal(ikm);
        Mac expand = Mac.getInstance("HmacSHA256");
        expand.init(new SecretKeySpec(prk, "HmacSHA256"));
        expand.update(info.getBytes(StandardCharsets.US_ASCII));
        expand.update((byte) 0x01);
        return new SecretKeySpec(expand.doFinal(), "AES");
    }
}
