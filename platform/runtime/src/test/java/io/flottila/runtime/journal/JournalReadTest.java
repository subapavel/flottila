package io.flottila.runtime.journal;

import io.flottila.runtime.journal.JournalFixture.LogCapture;
import io.flottila.runtime.journal.JournalFixture.Settings;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.InvalidKeyException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.logging.Level;
import java.util.zip.CRC32C;

import static io.flottila.runtime.journal.JournalFixture.START;
import static io.flottila.runtime.journal.JournalFixture.await;
import static io.flottila.runtime.journal.JournalFixture.body;
import static io.flottila.runtime.journal.JournalFixture.keys;
import static io.flottila.runtime.journal.JournalFixture.live;
import static io.flottila.runtime.journal.JournalFixture.record;
import static io.flottila.runtime.journal.JournalFixture.settings;
import static io.flottila.runtime.journal.JournalFixture.until;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads by ref and the footer index beyond the packet's cases (CORE-3 item 8): a ref no record can lie at, a file cut
 * short under the journal, a record whose CRC holds but whose header is not v1 or does not add up, a tenant key that
 * cannot be used, a footer index edited under an open journal, and a closed journal.
 */
class JournalReadTest {

    @TempDir Path tmp;

    private final FakeClock clock = FakeClock.at(START);
    private final FakeTimer timer = new FakeTimer(clock);
    private final List<Journal> journals = new ArrayList<>();

    @AfterEach
    void closeEverything() throws Exception {
        for (Journal j : journals) j.close();
    }

    private Path dir(String name) throws IOException {
        return Files.createDirectories(tmp.resolve(name));
    }

    private static Settings in(Path dir) {
        return settings(dir).segmentAge(Duration.ofHours(1));
    }

    private Journal open(Settings s, IntFunction<SecretKey> keys, JournalFiles files) throws IOException {
        Journal j = Journal.open(s.config(), clock, timer, keys, files);
        journals.add(j);
        return j;
    }

    private void close(Journal j) throws IOException {
        journals.remove(j);
        j.close();
    }

    private static JournalRef appendForced(Journal j, JournalRecord r) throws Exception {
        Journal.Appended a = j.append(r, live());
        await(a.forced());
        return a.ref();
    }

    @Test
    void aRefNoRecordCanLieAtIsRefusedAndIsNoCorruption() throws Exception {
        Journal j = open(in(dir("outside")), keys(JournalFixture.KEY), JournalFiles.real());
        JournalRef ref = appendForced(j, record("ro-1", 100));
        List<JournalRef> nowhere = List.of(
                new JournalRef(ref.segment() + 100, ref.position(), ref.length()),   // a segment the journal does not hold
                new JournalRef(ref.segment(), 0, ref.length()),                       // inside the header
                new JournalRef(ref.segment(), ref.position(), 10),                    // shorter than any record
                new JournalRef(ref.segment(), ref.position() + ref.length(), ref.length()));   // past the last forced record
        for (JournalRef r : nowhere) {
            IOException e = assertThrows(IOException.class, () -> j.read(r), String.valueOf(r));
            assertFalse(e instanceof JournalCorruptException, "no record there is not corruption: " + e);
        }
        assertArrayEquals(body("ro-1", 100), j.read(ref));
        assertEquals(Optional.empty(), j.index(ref.segment() + 100), "a segment the journal does not hold has no index");
    }

    @Test
    void aSegmentCutShortUnderTheJournalReadsAsAnEndOfFile() throws Exception {
        HeldFiles files = new HeldFiles();
        Journal j = open(in(dir("eof")), keys(JournalFixture.KEY), files);
        JournalRef ref = appendForced(j, record("eof-1", 1000));
        try (FileChannel c = FileChannel.open(files.fileOf("eof-1"), StandardOpenOption.WRITE)) {
            c.truncate(ref.position() + 10);
        }
        assertThrows(EOFException.class, () -> j.read(ref));
    }

    /** A field of the record header changed with the CRC32C made right again (CORE-3 item 2 field order). */
    enum Edit { VERSION, KIND, LENGTH, ID_LENGTH, PLAIN_NEGATIVE, PLAIN_PLUS_ONE }

    @ParameterizedTest
    @EnumSource(Edit.class)
    void aRecordWhoseCrcHoldsButWhoseHeaderIsNoV1RecordFailsAsCorrupt(Edit edit) throws Exception {
        Path dir = dir("edit-" + edit);
        HeldFiles files = new HeldFiles();
        Journal j = open(in(dir), keys(JournalFixture.KEY), files);
        JournalRef ref = appendForced(j, record("mf-1", 700));
        Path segment = files.fileOf("mf-1");
        close(j);   // sealed: the start takes the segment as it is, the read meets the edit

        byte[] all = Files.readAllBytes(segment);
        byte[] rec = Arrays.copyOfRange(all, (int) ref.position(), (int) (ref.position() + ref.length()));
        ByteBuffer b = ByteBuffer.wrap(rec);   // crc u32 · length u32 · version u8 · kind u8 · key id u32 · plain u64 · id length u16
        switch (edit) {
            case VERSION -> b.put(8, (byte) 2);
            case KIND -> b.put(9, (byte) 2);
            case LENGTH -> b.putInt(4, rec.length + 1);
            case ID_LENGTH -> b.putShort(22, (short) 0xFFFF);
            case PLAIN_NEGATIVE -> b.putLong(14, -1);
            case PLAIN_PLUS_ONE -> b.putLong(14, 701);
        }
        CRC32C crc = new CRC32C();
        crc.update(rec, 4, rec.length - 4);
        b.putInt(0, (int) crc.getValue());
        System.arraycopy(rec, 0, all, (int) ref.position(), rec.length);
        Files.write(segment, all);

        try (LogCapture log = new LogCapture()) {
            Journal back = open(in(dir), keys(JournalFixture.KEY), JournalFiles.real());
            assertThrows(JournalCorruptException.class, () -> back.read(ref), edit + " edited");
            until(() -> log.count(Level.SEVERE) >= 1, () -> "the corrupt read is an ERROR: " + log.lines());
            close(back);
        }
        Journal keyless = open(in(dir), keys(), JournalFiles.real());
        assertThrows(JournalCorruptException.class, () -> keyless.read(ref), edit + " edited: no v1 record, so no tenant key is asked for");
    }

    @Test
    void aStoredCrcThatNoLongerMatchesFailsTheReadAsCorrupt() throws Exception {
        Path dir = dir("stored-crc");
        HeldFiles files = new HeldFiles();
        Journal j = open(in(dir), keys(JournalFixture.KEY), files);
        JournalRef ref = appendForced(j, record("sc-1", 300));
        Path segment = files.fileOf("sc-1");
        close(j);
        byte[] all = Files.readAllBytes(segment);
        all[(int) ref.position()] ^= 0x01;   // the CRC32C itself: not under the AEAD, so only the CRC check can tell
        Files.write(segment, all);

        Journal back = open(in(dir), keys(JournalFixture.KEY), JournalFiles.real());
        assertThrows(JournalCorruptException.class, () -> back.read(ref));
    }

    @Test
    void aMissingTenantKeyFailsTheReadAsAKeyFailure() throws Exception {
        Path dir = dir("missing-key");
        Journal j = open(in(dir), keys(JournalFixture.KEY), JournalFiles.real());
        JournalRef ref = appendForced(j, record("mk-1", 300));
        close(j);

        Journal back = open(in(dir), keys(), JournalFiles.real());
        JournalKeyException e = assertThrows(JournalKeyException.class, () -> back.read(ref));
        assertTrue(e.getMessage().contains("not there"), e.getMessage());
    }

    @Test
    void aRecordWrittenButNotYetForcedIsNotReadable() throws Exception {
        HeldFiles files = new HeldFiles();
        Journal j = open(in(dir("unforced")), keys(JournalFixture.KEY), files);
        appendForced(j, record("uf-0", 100));
        HeldFiles.Hold force = files.holdForceAfter("uf-1");
        Journal.Appended a = j.append(record("uf-1", 100), live());
        force.awaitReached();   // its bytes are written, its force is held
        try {
            IOException e = assertThrows(IOException.class, () -> j.read(a.ref()), "a ref past the last forced record");
            assertFalse(e instanceof JournalCorruptException, String.valueOf(e));
        } finally {
            force.release();   // else the close in the AfterEach waits on the held force
        }
        await(a.forced());
        assertArrayEquals(body("uf-1", 100), j.read(a.ref()), "readable once forced");
    }

    @Test
    void aTenantKeyThatCannotBeUsedFailsTheReadAsAKeyFailure() throws Exception {
        Path dir = dir("unusable");
        Journal j = open(in(dir), keys(JournalFixture.KEY), JournalFiles.real());
        JournalRef ref = appendForced(j, record("uk-1", 300));
        close(j);

        SecretKey notAes = new SecretKeySpec(new byte[32], "HmacSHA256");
        Journal back = open(in(dir), id -> notAes, JournalFiles.real());
        JournalKeyException e = assertThrows(JournalKeyException.class, () -> back.read(ref));
        assertInstanceOf(InvalidKeyException.class, e.getCause());
    }

    @Test
    void aFooterIndexEditedUnderAnOpenJournalFailsAsCorrupt() throws Exception {
        HeldFiles files = new HeldFiles();
        Journal j = open(in(dir("index-edit")).segmentRecords(2), keys(JournalFixture.KEY), files);
        appendForced(j, record("ie-1", 100));
        JournalRef second = appendForced(j, record("ie-2", 100));
        until(() -> sealed(j, second.segment()), () -> "n is sealed");
        Path segment = files.fileOf("ie-1");
        byte[] all = Files.readAllBytes(segment);
        all[(int) (second.position() + second.length()) + 3] ^= 0x01;   // inside the first index entry
        Files.write(segment, all);

        assertThrows(JournalCorruptException.class, () -> j.index(second.segment()));
    }

    private static boolean sealed(Journal j, long n) {
        try {
            return j.index(n).isPresent();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aClosedJournalRefusesReadsIndexAndAppendsAndClosesAgainQuietly() throws Exception {
        Journal j = open(in(dir("closed")), keys(JournalFixture.KEY), JournalFiles.real());
        JournalRef ref = appendForced(j, record("cl-1", 100));
        close(j);
        assertThrows(IllegalStateException.class, () -> j.read(ref));
        assertThrows(IllegalStateException.class, () -> j.index(ref.segment()));
        assertThrows(IllegalStateException.class, () -> j.append(record("cl-2", 100), live()));
        j.close();
        assertTrue(Files.exists(tmp.resolve("closed").resolve("NODE")));
    }
}
