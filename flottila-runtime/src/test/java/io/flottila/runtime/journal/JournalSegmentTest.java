package io.flottila.runtime.journal;

import io.flottila.runtime.journal.HeldFiles.Hold;
import io.flottila.runtime.journal.HeldFiles.Op;
import io.flottila.runtime.journal.JournalFixture.Settings;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static io.flottila.runtime.journal.JournalFixture.START;
import static io.flottila.runtime.journal.JournalFixture.await;
import static io.flottila.runtime.journal.JournalFixture.body;
import static io.flottila.runtime.journal.JournalFixture.keys;
import static io.flottila.runtime.journal.JournalFixture.live;
import static io.flottila.runtime.journal.JournalFixture.record;
import static io.flottila.runtime.journal.JournalFixture.settings;
import static io.flottila.runtime.journal.JournalFixture.until;
import static io.flottila.runtime.journal.JournalFixture.untilParked;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Segments (CORE-3 item 1): the roll at FLOTTILA_JOURNAL_SEGMENT_BYTES, the record count or the age after the segment's
 * first record on the injected clock (an empty segment never rolls); the footer index a sealer writes after the writer's
 * last write and force on n; no acknowledged record waits for that footer.
 */
class JournalSegmentTest {

    @TempDir Path tmp;

    private final FakeClock clock = FakeClock.at(START);
    private final FakeTimer timer = new FakeTimer(clock);
    private final List<HeldFiles> seams = new ArrayList<>();
    private final List<Journal> journals = new ArrayList<>();

    @AfterEach
    void closeEverything() throws Exception {
        seams.forEach(HeldFiles::releaseAll);
        for (Journal j : journals) j.close();
    }

    private Settings in(String name) throws Exception {
        return settings(Files.createDirectories(tmp.resolve(name))).segmentAge(Duration.ofHours(1));
    }

    private HeldFiles seam() {
        HeldFiles files = new HeldFiles();
        seams.add(files);
        return files;
    }

    private Journal open(Settings s, HeldFiles files) throws Exception {
        Journal j = Journal.open(s.config(), clock, timer, keys(JournalFixture.KEY), files);
        journals.add(j);
        return j;
    }

    private static JournalRecord record(String id, int step, String part, int size) {
        return new JournalRecord(id, step, part, JournalFixture.KEY, body(id, size));
    }

    private static JournalRef appendForced(Journal journal, JournalRecord r) throws Exception {
        Journal.Appended a = journal.append(r, live());
        await(a.forced());
        return a.ref();
    }

    @Test
    void rollsAtBytesRecordsOrAgeOnAFakeClock() throws Exception {
        // records: three per segment, the fourth opens the next one
        Journal byCount = open(in("records").segmentRecords(3), seam());
        List<Long> segments = new ArrayList<>();
        for (int i = 0; i < 7; i++) segments.add(appendForced(byCount, JournalFixture.record("rc-" + i, 100)).segment());
        long n = segments.getFirst();
        assertEquals(List.of(n, n, n, n + 1, n + 1, n + 1, n + 2), segments, "FLOTTILA_JOURNAL_SEGMENT_RECORDS 3");

        // bytes: 16 KiB segments of equal 3000-byte records; a segment ends at the bound, give or take the record crossing it
        long bound = 16 * 1024;
        Journal byBytes = open(in("bytes").recordMax(4096).segmentBytes(bound), seam());
        List<JournalRef> refs = new ArrayList<>();
        for (int i = 0; i < 14; i++) refs.add(appendForced(byBytes, JournalFixture.record("rb-" + String.format("%02d", i), 3000)));
        long length = refs.getFirst().length();
        for (JournalRef r : refs) assertEquals(length, r.length(), "equal records encode to equal lengths: " + refs);
        long header = refs.getFirst().position();
        long fit = (bound - header) / length;
        long first = refs.getFirst().segment();
        long inFirst = refs.stream().filter(r -> r.segment() == first).count();
        long inSecond = refs.stream().filter(r -> r.segment() == first + 1).count();
        assertTrue(inFirst == fit || inFirst == fit + 1,
                "a " + bound + "-byte segment holds " + fit + " records of " + length + " bytes after its " + header + "-byte header, or the one crossing the bound; held " + inFirst + ": " + refs);
        assertEquals(inFirst, inSecond, "the next segment rolls at the same bound: " + refs);
        assertEquals(header, refs.get((int) inFirst).position(), "the next segment starts after its own header");

        // age: 1.25 s after the segment's first record on the injected clock (START is .850: the bound crosses a second)
        Duration age = Duration.ofMillis(1250);
        Journal byAge = open(in("age").segmentAge(age), seam());
        long a1 = appendForced(byAge, JournalFixture.record("ra-1", 100)).segment();
        clock.advance(age.minusMillis(1));
        long a2 = appendForced(byAge, JournalFixture.record("ra-2", 100)).segment();
        clock.advance(Duration.ofMillis(1));
        clock.advance(Duration.ofSeconds(10));   // the next segment stays empty for ten seconds: an empty segment never rolls
        long a3 = appendForced(byAge, JournalFixture.record("ra-3", 100)).segment();
        clock.advance(age.minusMillis(1));       // its age counts from its first record (ra-3), not from its creation
        long a4 = appendForced(byAge, JournalFixture.record("ra-4", 100)).segment();
        clock.advance(Duration.ofMillis(1));
        long a5 = appendForced(byAge, JournalFixture.record("ra-5", 100)).segment();
        assertEquals(List.of(a1, a1, a1 + 1, a1 + 1, a1 + 2), List.of(a1, a2, a3, a4, a5),
                "FLOTTILA_JOURNAL_SEGMENT_AGE 1.25 s after the first record: ra-1 ra-2 | ra-3 ra-4 | ra-5");
    }

    @Test
    void theFooterListsEveryRecord() throws Exception {
        Journal journal = open(in("footer").segmentRecords(4), seam());
        List<JournalRecord> records = List.of(
                record("fl-alpha", 0, "in", 500),
                record("fl-beta", 3, "out", 0),
                record("fl-beta", 4, "in", 1200),
                record("fl-gamma", 7, "headers", 64));
        List<JournalIndexEntry> expected = new ArrayList<>();
        for (JournalRecord r : records) {
            JournalRef ref = appendForced(journal, r);
            expected.add(new JournalIndexEntry(r.messageId(), r.step(), r.part(), ref));
        }
        JournalRef next = appendForced(journal, record("fl-delta", 1, "in", 10));   // n is full: this one is in n+1
        long n = expected.getFirst().ref().segment();
        assertEquals(n + 1, next.segment());

        until(() -> index(journal, n).isPresent(), () -> "segment " + n + " is sealed");
        assertEquals(expected, sorted(index(journal, n).orElseThrow()), "n's footer lists every record, each with its ref");
        assertEquals(Optional.empty(), journal.index(n + 1), "the open segment has no footer yet");

        journals.remove(journal);
        journal.close();
        Journal reopened = open(in("footer").segmentRecords(4), seam());
        assertEquals(expected, sorted(reopened.index(n).orElseThrow()), "the footer is on disk: a restart reads the same list");
    }

    @Test
    void noAcknowledgedRecordWaitsForTheFooter() throws Exception {
        HeldFiles files = seam();
        Journal journal = open(in("no-wait").segmentRecords(2), files);
        Hold footer = files.holdSealWriteOf("nw-1", "nw-2");   // the sealer's first write on n
        JournalRef r1 = appendForced(journal, JournalFixture.record("nw-1", 100));
        JournalRef r2 = appendForced(journal, JournalFixture.record("nw-2", 100));
        Journal.Appended r3 = journal.append(JournalFixture.record("nw-3", 100), live());
        long n = r1.segment();
        assertEquals(n, r2.segment());
        assertEquals(n + 1, r3.ref().segment());

        footer.awaitReached();
        untilParked(footer.thread(), "the sealer sits in n's footer write");
        await(r3.forced());
        JournalRef r4 = appendForced(journal, JournalFixture.record("nw-4", 100));
        assertEquals(n + 1, r4.segment());
        assertEquals(Optional.empty(), journal.index(n), "n is not sealed while its footer write is held");

        footer.release();
        until(() -> index(journal, n).isPresent(), () -> "n is sealed once its footer is written");
    }

    @Test
    void theSealerStartsOnlyAfterTheLastForceOnN() throws Exception {
        HeldFiles files = seam();
        Journal journal = open(in("sealer").segmentRecords(2), files);
        Hold footer = files.holdSealWriteOf("ss-1", "ss-2");   // armed first: the sealer's first write on n
        Hold round1 = files.holdForceAfter("ss-1");
        Journal.Appended r1 = journal.append(JournalFixture.record("ss-1", 100), live());
        round1.awaitReached();
        Journal.Appended r2 = journal.append(JournalFixture.record("ss-2", 100), live());   // n's last record
        Journal.Appended r3 = journal.append(JournalFixture.record("ss-3", 100), live());   // n+1: the next round spans the roll
        assertEquals(r1.ref().segment(), r2.ref().segment());
        assertEquals(r1.ref().segment() + 1, r3.ref().segment());

        Hold last = files.holdForceAfter("ss-2");   // the writer's last force on n
        round1.release();
        await(r1.forced());
        last.awaitReached();
        untilParked(last.thread(), "the writer sits in its last force on n");
        assertFalse(footer.isReached(), "no footer write on n while the writer's last force on n is held: " + files.describe());

        last.release();
        footer.awaitReached();
        Op lastForce = last.op();
        Op footerWrite = footer.op();
        assertTrue(footerWrite.entered > lastForce.returned,
                "the sealer's first write on n comes after the writer's last force on n returned: " + files.describe());
        assertNotSame(lastForce.thread, footerWrite.thread, "n is handed to a sealer thread");
        assertTrue(footerWrite.thread.isVirtual(), "the sealer is a virtual thread: " + footerWrite);
        footer.release();
        await(r2.forced());
        await(r3.forced());
        long n = r1.ref().segment();
        until(() -> index(journal, n).isPresent(), () -> "n is sealed");
        for (Op op : files.opsOnAfter(lastForce.file, lastForce.returned)) {
            assertNotSame(lastForce.thread, op.thread, "the writer never touches n after its last force: " + op);
        }
    }

    private static Optional<List<JournalIndexEntry>> index(Journal journal, long segment) {
        try {
            return journal.index(segment);
        } catch (Exception e) {
            throw new AssertionError("reading the index of " + segment + " failed", e);
        }
    }

    private static List<JournalIndexEntry> sorted(List<JournalIndexEntry> entries) {
        return entries.stream().sorted(Comparator.comparingLong(e -> e.ref().position())).toList();
    }
}
