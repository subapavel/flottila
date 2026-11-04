package io.flottila.runtime.journal;

import io.flottila.runtime.journal.JournalFixture.Settings;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.crypto.AEADBadTagException;
import javax.crypto.SecretKey;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;
import java.util.zip.CRC32C;

import static io.flottila.runtime.journal.JournalFixture.START;
import static io.flottila.runtime.journal.JournalFixture.await;
import static io.flottila.runtime.journal.JournalFixture.body;
import static io.flottila.runtime.journal.JournalFixture.crashImage;
import static io.flottila.runtime.journal.JournalFixture.inImage;
import static io.flottila.runtime.journal.JournalFixture.keys;
import static io.flottila.runtime.journal.JournalFixture.live;
import static io.flottila.runtime.journal.JournalFixture.settings;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The record cipher (CORE-3 item 2): the spool construction per record under the record's tenant key (HKDF-SHA256 over a
 * salt, AES-256-GCM, the record header as AAD). Deleting a tenant key shreds its records; an edit of the header that
 * keeps the CRC32C right still fails, at authentication.
 */
class JournalCipherTest {

    @TempDir Path tmp;

    private final FakeClock clock = FakeClock.at(START);
    private final FakeTimer timer = new FakeTimer(clock);
    private final List<Journal> journals = new ArrayList<>();
    private int images;

    @AfterEach
    void closeEverything() throws Exception {
        for (Journal j : journals) j.close();
    }

    private Settings in(Path dir) {
        return settings(dir).segmentAge(Duration.ofHours(1));
    }

    private Journal open(Path dir, IntFunction<SecretKey> keys, JournalFiles files) throws Exception {
        Journal j = Journal.open(in(dir).config(), clock, timer, keys, files);
        journals.add(j);
        return j;
    }

    private void close(Journal j) throws Exception {
        journals.remove(j);
        j.close();
    }

    @Test
    void aDeletedTenantKeyMakesItsRecordsUnreadable() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("shred"));
        Journal journal = open(dir, keys(41, 42), new HeldFiles());
        byte[] of41 = body("dk-41", 2000);
        byte[] of42 = body("dk-42", 2000);
        Journal.Appended a41 = journal.append(new JournalRecord("dk-41", 0, "in", 41, of41), live());
        Journal.Appended a42 = journal.append(new JournalRecord("dk-42", 0, "in", 42, of42), live());
        await(a41.forced());
        await(a42.forced());
        close(journal);
        assertFalse(JournalFixture.anyFileContains(dir, Arrays.copyOf(of41, 64)), "the body is encrypted at rest: no file holds its plaintext");
        assertFalse(JournalFixture.anyFileContains(dir, Arrays.copyOf(of42, 64)), "the body is encrypted at rest: no file holds its plaintext");

        Journal without41 = open(dir, keys(42), new HeldFiles());
        assertArrayEquals(of42, without41.read(a42.ref()), "another tenant's record still reads");
        Throwable unreadable = assertThrows(Exception.class, () -> without41.read(a41.ref()), "a record whose tenant key is gone is unreadable");
        assertFalse(unreadable instanceof JournalCorruptException, "a deleted key is not corruption (that is a CRC failure): " + unreadable);
        close(without41);

        Journal with41 = open(dir, keys(41, 42), new HeldFiles());
        assertArrayEquals(of41, with41.read(a41.ref()), "witness: the same bytes read with the key, so only the key was missing");
    }

    /** The header field an edit changes; each is AAD of the record's AEAD. */
    enum Field { MESSAGE_ID, STEP, PART, NONE }

    private static final String ID = "he-message-0001";
    private static final int STEP = 0x5A0B0C0D;
    private static final String PART = "part-q";

    @ParameterizedTest
    @EnumSource(Field.class)
    void aHeaderEditFailsAuthentication(Field field) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("aad-" + field));
        HeldFiles files = new HeldFiles();
        Journal journal = open(dir, keys(JournalFixture.KEY), files);
        byte[] plain = body(ID, 1500);
        Journal.Appended a = journal.append(new JournalRecord(ID, STEP, PART, JournalFixture.KEY, plain), live());
        await(a.forced());
        Path image = crashImage(dir, files, tmp.resolve("image-" + (++images)));
        Path segment = inImage(dir, image, files.fileOf(ID));
        JournalRef ref = a.ref();

        byte[] all = Files.readAllBytes(segment);
        byte[] rec = Arrays.copyOfRange(all, (int) ref.position(), (int) (ref.position() + ref.length()));
        ByteOrder order = crcOrder(rec);
        edit(rec, field, order);
        ByteBuffer.wrap(rec).order(order).putInt(0, crcOfTheRest(rec));   // the CRC32C is right again: only AEAD can tell
        System.arraycopy(rec, 0, all, (int) ref.position(), rec.length);
        Files.write(segment, all);

        Journal back = open(image, keys(JournalFixture.KEY), new HeldFiles());
        if (field == Field.NONE) {
            assertArrayEquals(plain, back.read(ref), "witness: the CRC fix-up alone leaves a readable record");
            return;
        }
        Throwable failed = assertThrows(Exception.class, () -> back.read(ref), field + " edited");
        assertTrue(causedBy(failed, AEADBadTagException.class), "the edit of " + field + " fails authentication (the header is AAD): " + chain(failed));
    }

    /** CORE-3 item 2: the record starts with crc32c u32 over the rest; which byte order it is written in is read off the record. */
    private static ByteOrder crcOrder(byte[] rec) {
        int crc = crcOfTheRest(rec);
        if (ByteBuffer.wrap(rec).order(ByteOrder.BIG_ENDIAN).getInt(0) == crc) return ByteOrder.BIG_ENDIAN;
        if (ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN).getInt(0) == crc) return ByteOrder.LITTLE_ENDIAN;
        throw new AssertionError("the record's first four bytes are not the CRC32C of the rest (CORE-3 item 2)");
    }

    private static int crcOfTheRest(byte[] rec) {
        CRC32C c = new CRC32C();
        c.update(rec, 4, rec.length - 4);
        return (int) c.getValue();
    }

    private static void edit(byte[] rec, Field field, ByteOrder order) {
        switch (field) {
            case MESSAGE_ID -> rec[at(rec, HeldFiles.bytes(ID)) + ID.length() - 1] ^= 0x03;          // ...0001 → ...0002
            case PART -> rec[at(rec, HeldFiles.bytes(PART)) + PART.length() - 1] ^= 0x03;            // part-q → part-r
            case STEP -> {
                byte[] step = ByteBuffer.allocate(4).order(order).putInt(STEP).array();
                int i = at(rec, step);
                ByteBuffer.wrap(rec).order(order).putInt(i, STEP + 1);
            }
            case NONE -> { }
        }
    }

    private static int at(byte[] rec, byte[] field) {
        int i = HeldFiles.indexOf(rec, field, 0);
        if (i < 0) fail("the record header carries " + Arrays.toString(field) + " in plain (CORE-3 item 2)");
        return i;
    }

    private static boolean causedBy(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) if (type.isInstance(c)) return true;
        return false;
    }

    private static String chain(Throwable t) {
        StringBuilder b = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) b.append(c).append(" <- ");
        return b.toString();
    }
}
