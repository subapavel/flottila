package io.flottila.runtime.journal;

import io.flottila.runtime.journal.JournalFixture.Settings;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static io.flottila.runtime.journal.JournalFixture.KEY;
import static io.flottila.runtime.journal.JournalFixture.START;
import static io.flottila.runtime.journal.JournalFixture.await;
import static io.flottila.runtime.journal.JournalFixture.body;
import static io.flottila.runtime.journal.JournalFixture.keys;
import static io.flottila.runtime.journal.JournalFixture.live;
import static io.flottila.runtime.journal.JournalFixture.record;
import static io.flottila.runtime.journal.JournalFixture.settings;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-4c3 packet item 6 (CORE-3 item 8, carried from CORE-4a): a read preads over a bounded set of open channels —
 * {@code FLOTTILA_JOURNAL_READ_CHANNELS} of them — that every read of a segment shares; when a read needs one more, the least
 * recently used is closed first; a channel whose read failed is closed and dropped, so the next read of that segment opens a
 * fresh one; {@link Journal#close()} closes every one. Observed through {@link HeldFiles}: the read-only channels opened and
 * closed per segment file (a directory opened to be forced is not one) and the most that were open at once. Every record is a
 * segment record of a segment of its own ({@code SEGMENT_RECORDS} 1).
 *
 * <p>The API assumed (CORE-4c3, test-author): {@code JournalConfig.readChannels()} — set here through
 * {@link Settings#readChannels(int)}. A large body's stream ({@link Journal#stream} past the segment record bound) is not
 * covered: it reads one chunk after another on a channel of its own.
 */
class JournalReadChannelsTest {

    @TempDir Path tmp;

    private final FakeClock clock = FakeClock.at(START);
    private final FakeTimer timer = new FakeTimer(clock);
    private final List<Journal> journals = new ArrayList<>();

    @AfterEach
    void closeEverything() throws Exception {
        for (Journal j : journals) j.close();
    }

    private Journal open(int readChannels, HeldFiles files, String name) throws IOException {
        Settings s = settings(Files.createDirectories(tmp.resolve(name))).segmentAge(Duration.ofHours(1)).segmentRecords(1).readChannels(readChannels);
        Journal j = Journal.open(s.config(), clock, timer, keys(KEY), files);
        journals.add(j);
        return j;
    }

    private static JournalRef appendForced(Journal j, String id) throws Exception {
        Journal.Appended a = j.append(record(id, 500), live());
        await(a.forced());
        return a.ref();
    }

    private static List<Long> opens(HeldFiles files, Path... segments) {
        List<Long> n = new ArrayList<>();
        for (Path p : segments) n.add(files.readOpensOf(p));
        return n;
    }

    private static List<Long> closes(HeldFiles files, Path... segments) {
        List<Long> n = new ArrayList<>();
        for (Path p : segments) n.add(files.readClosesOf(p));
        return n;
    }

    /**
     * READ_CHANNELS 2 over three segments: two reads of s1 share one channel; s2 opens a second; s3 needs a third, so the least
     * recently used (s1) is closed; s2 read again is still open (no open); s1 again opens anew and closes the least recently
     * used, s3 — not s2, which was used after s3 (an eviction in opening order would close s2). Never more than two open at
     * once; the close closes the two left.
     */
    @Test
    void readsShareABoundedSetOfChannels() throws Exception {
        HeldFiles files = new HeldFiles();
        Journal j = open(2, files, "lru");
        JournalRef r1 = appendForced(j, "ch-1");
        JournalRef r2 = appendForced(j, "ch-2");
        JournalRef r3 = appendForced(j, "ch-3");
        assertEquals(3, List.of(r1.segment(), r2.segment(), r3.segment()).stream().distinct().count(), "fixture: a segment per record");
        Path s1 = files.fileOf("ch-1"), s2 = files.fileOf("ch-2"), s3 = files.fileOf("ch-3");
        assertEquals(List.of(0L, 0L, 0L), opens(files, s1, s2, s3), "fixture: nothing read yet");

        assertArrayEquals(body("ch-1", 500), j.read(r1));
        assertArrayEquals(body("ch-1", 500), j.read(r1));
        assertEquals(1, files.readOpensOf(s1), "two reads of one segment share one channel");

        assertArrayEquals(body("ch-2", 500), j.read(r2));
        assertEquals(List.of(1L, 1L, 0L), opens(files, s1, s2, s3));
        assertEquals(List.of(0L, 0L, 0L), closes(files, s1, s2, s3), "two channels fit READ_CHANNELS 2: none is closed");

        assertArrayEquals(body("ch-3", 500), j.read(r3));
        assertEquals(List.of(1L, 1L, 1L), opens(files, s1, s2, s3));
        assertEquals(List.of(1L, 0L, 0L), closes(files, s1, s2, s3), "a third segment closes the least recently used channel, s1's");

        assertArrayEquals(body("ch-2", 500), j.read(r2));
        assertEquals(1, files.readOpensOf(s2), "s2's channel is still open: read without an open");

        assertArrayEquals(body("ch-1", 500), j.read(r1));
        assertEquals(List.of(2L, 1L, 1L), opens(files, s1, s2, s3), "s1 is opened anew");
        assertEquals(List.of(1L, 0L, 1L), closes(files, s1, s2, s3), "and the least recently used closes: s3, not s2 (read after s3)");

        assertEquals(2, files.readChannelsPeak(), "never more than READ_CHANNELS read channels open at once");
        assertEquals(2, files.readChannelsOpen());
        journals.remove(j);
        j.close();
        assertEquals(0, files.readChannelsOpen(), "close() closes every read channel");
        assertEquals(List.of(2L, 1L, 1L), closes(files, s1, s2, s3), "each channel once");
    }

    /**
     * A read whose channel fails (the segment's file gone under it, an I/O error) does not keep that channel: it is closed, and
     * the next read of the segment opens a fresh one and reads the record. The failing read itself either fails as an I/O
     * error — never as corruption — or was answered from a fresh channel already; either way exactly one channel was dropped.
     */
    @Test
    void aChannelWhoseReadFailedIsDroppedAndTheNextReadOpensAFreshOne() throws Exception {
        HeldFiles files = new HeldFiles();
        Journal j = open(4, files, "dropped");
        JournalRef r1 = appendForced(j, "dr-1");
        Path s1 = files.fileOf("dr-1");
        assertArrayEquals(body("dr-1", 500), j.read(r1));
        assertEquals(1, files.readOpensOf(s1), "fixture: one channel, cached");

        files.failNextReadOn(s1);
        Object outcome;
        try {
            outcome = j.read(r1);
        } catch (IOException failed) {
            outcome = failed;
        }

        if (outcome instanceof byte[] bytes) {
            assertArrayEquals(body("dr-1", 500), bytes, "answered from a fresh channel");
        } else {
            assertFalse(outcome instanceof JournalCorruptException, "an I/O failure is no corruption: " + outcome);
        }
        assertEquals(1, files.readClosesOf(s1), "the channel whose read failed is closed, not kept for the next read");
        assertArrayEquals(body("dr-1", 500), j.read(r1), "the next read reads the record");
        assertEquals(2, files.readOpensOf(s1), "on a fresh channel: one more open, in all");
        assertTrue(files.readChannelsOpen() <= 1, "the dropped channel is not counted open: " + files.readChannelsOpen());
    }
}
