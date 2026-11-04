package io.flottila.runtime.journal;

import io.flottila.api.Timer;
import io.flottila.runtime.journal.HeldFiles.Hold;
import io.flottila.runtime.journal.JournalFixture.LogCapture;
import io.flottila.runtime.journal.JournalFixture.LoggedTimer;
import io.flottila.runtime.journal.JournalFixture.Outcome;
import io.flottila.runtime.journal.JournalFixture.Settings;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.logging.Level;

import static io.flottila.runtime.journal.JournalFixture.START;
import static io.flottila.runtime.journal.JournalFixture.appendFailure;
import static io.flottila.runtime.journal.JournalFixture.await;
import static io.flottila.runtime.journal.JournalFixture.body;
import static io.flottila.runtime.journal.JournalFixture.failureOf;
import static io.flottila.runtime.journal.JournalFixture.key;
import static io.flottila.runtime.journal.JournalFixture.keys;
import static io.flottila.runtime.journal.JournalFixture.live;
import static io.flottila.runtime.journal.JournalFixture.onVirtual;
import static io.flottila.runtime.journal.JournalFixture.parked;
import static io.flottila.runtime.journal.JournalFixture.record;
import static io.flottila.runtime.journal.JournalFixture.settings;
import static io.flottila.runtime.journal.JournalFixture.until;
import static io.flottila.runtime.journal.JournalFixture.untilParked;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Appends beyond the packet's writer cases (CORE-3 items 1 and 3, the fail-stop rule): what an append refuses before it
 * reserves, the bound's edges (nothing pending, an interrupt, a close), the round's limits, an age roll with no timer and
 * a stale age timer, and the failures that do not come from a record's own write or force: a pre-open, a sealer, two
 * failures, end markers queued behind a failure.
 */
class JournalAppendTest {

    @TempDir Path tmp;

    private final FakeClock clock = FakeClock.at(START);
    private final LoggedTimer timer = new LoggedTimer(new FakeTimer(clock));
    private final List<HeldFiles> seams = new ArrayList<>();
    private final List<Journal> journals = new ArrayList<>();

    @AfterEach
    void closeEverything() throws Exception {
        seams.forEach(HeldFiles::releaseAll);
        for (Journal j : journals) j.close();
    }

    private Settings quiet(String name) throws IOException {
        return settings(Files.createDirectories(tmp.resolve(name))).segmentAge(Duration.ofHours(1));
    }

    private HeldFiles seam() {
        HeldFiles files = new HeldFiles();
        seams.add(files);
        return files;
    }

    private Journal open(Settings s, Timer t, IntFunction<SecretKey> k, JournalFiles files) throws IOException {
        Journal j = Journal.open(s.config(), clock, t, k, files);
        journals.add(j);
        return j;
    }

    private Journal open(Settings s, JournalFiles files) throws IOException {
        return open(s, timer, keys(JournalFixture.KEY), files);
    }

    private static JournalRef appendForced(Journal j, JournalRecord r) throws Exception {
        Journal.Appended a = j.append(r, live());
        await(a.forced());
        return a.ref();
    }

    @Test
    void aBodyAboveRecordMaxIsRefusedAndReservesNothing() throws Exception {
        Journal j = open(quiet("record-max").recordMax(4096), JournalFiles.real());
        JournalRef first = appendForced(j, record("rm-1", 4096));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> j.append(record("rm-2", 4097), live()));
        assertTrue(e.getMessage().contains("FLOTTILA_JOURNAL_RECORD_MAX"), e.getMessage());
        JournalRef next = appendForced(j, record("rm-3", 10));
        assertEquals(first.position() + first.length(), next.position(), "the refused append reserved nothing");
    }

    @Test
    void aTenantKeyThatIsNotThereOrIsNoAesKeyIsRefused() throws Exception {
        SecretKey notAes = new SecretKeySpec(new byte[32], "HmacSHA256");
        Journal j = open(quiet("keys"), timer, id -> id == 1 ? key(1) : id == 8 ? notAes : null, JournalFiles.real());
        assertThrows(IllegalArgumentException.class, () -> j.append(new JournalRecord("tk-7", 0, "in", 7, body("tk-7", 10)), live()));
        IllegalArgumentException unusable = assertThrows(IllegalArgumentException.class,
                () -> j.append(new JournalRecord("tk-8", 0, "in", 8, body("tk-8", 10)), live()));
        assertInstanceOf(InvalidKeyException.class, unusable.getCause());
        JournalRef ok = appendForced(j, record("tk-1", 10));
        assertArrayEquals(body("tk-1", 10), j.read(ok));
    }

    @Test
    void aMessageIdOrAPartAbove65535Utf8BytesIsRefused() throws Exception {
        Journal j = open(quiet("names"), JournalFiles.real());
        String longest = "i".repeat(65_535);
        assertThrows(IllegalArgumentException.class, () -> j.append(new JournalRecord(longest + "i", 0, "in", 1, new byte[1]), live()));
        assertThrows(IllegalArgumentException.class, () -> j.append(new JournalRecord("nm-1", 0, longest + "p", 1, new byte[1]), live()));
        JournalRef ref = appendForced(j, new JournalRecord(longest, 0, longest, 1, body("nm-2", 10)));
        assertArrayEquals(body("nm-2", 10), j.read(ref));
    }

    @Test
    void aRecordAboveTheBoundIsTakenWhenNothingIsPending() throws Exception {
        Journal j = open(quiet("alone").recordMax(4096).pendingBytes(4096), JournalFiles.real());
        // a body at FLOTTILA_JOURNAL_RECORD_MAX encodes above FLOTTILA_JOURNAL_PENDING_BYTES (its header and tag come on top);
        // on a thread of its own, so an append that waited for room would fail the test here and not hang it
        Outcome<JournalRef> taken = onVirtual(() -> appendForced(j, record("al-1", 4096)));
        JournalRef ref = taken.join();
        assertTrue(ref.length() > 4096, "the record is above the bound: " + ref);
        assertArrayEquals(body("al-1", 4096), j.read(ref));
    }

    @Test
    void aTimerThatRefusesASegmentsAgeTimerFailsThatAppendAndLeavesTheSegmentAsItWas() throws Exception {
        AtomicInteger schedules = new AtomicInteger();
        Timer refusesTheSecond = (delay, task) -> {
            if (schedules.incrementAndGet() == 2) throw new IllegalStateException("no timer (test)");
            return timer.schedule(delay, task);
        };
        Journal j = open(quiet("timer-refused").segmentRecords(1), refusesTheSecond, keys(JournalFixture.KEY), JournalFiles.real());
        JournalRef first = appendForced(j, record("tr-1", 100));   // the first age timer; the segment rolls by count
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> j.append(record("tr-2", 100), live()));
        assertEquals("no timer (test)", refused.getMessage(), "the append fails with the timer's refusal");

        JournalRef next = appendForced(j, record("tr-3", 100));
        assertEquals(new JournalRef(first.segment() + 1, first.position(), next.length()), next,
                "the next segment takes the next record at its start (the first record of a segment starts where the header ends)");
        assertArrayEquals(body("tr-3", 100), j.read(next));
        Outcome<Boolean> closing = onVirtual(() -> {
            journals.remove(j);
            j.close();
            return true;
        });
        assertTrue(closing.join(), "the close returns");
    }

    @Test
    void aSegmentAgeIsAtMostAnHour() throws IOException {
        Settings s = quiet("age-max");
        s.segmentAge(Duration.ofHours(1)).config();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> s.segmentAge(Duration.ofHours(1).plusNanos(1)).config());
        assertTrue(e.getMessage().contains("FLOTTILA_JOURNAL_SEGMENT_AGE"), e.getMessage());
    }

    @Test
    void pendingBytesBelowRecordMaxAreRefusedNamingBothKeys() throws IOException {
        Settings s = quiet("pending-min").recordMax(8192);
        s.pendingBytes(8192).config();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> s.pendingBytes(8191).config());
        assertTrue(e.getMessage().contains("FLOTTILA_JOURNAL_PENDING_BYTES") && e.getMessage().contains("FLOTTILA_JOURNAL_RECORD_MAX"),
                e.getMessage());
    }

    private Settings bounded(String name) throws IOException {
        return quiet(name).recordMax(4096).pendingBytes(8192).appendWait(Duration.ofSeconds(2));
    }

    /** Two 3000-byte records behind a held force fill an 8192-byte bound: the next append waits on a thread of its own. */
    private record Full(Hold held, JournalRef last, Outcome<Journal.Appended> waiting) { }

    private Full fill(Journal j, HeldFiles files, String prefix, AtomicBoolean interruptedAfter) throws Exception {
        Hold held = files.holdForceAfter(prefix + "-0");
        j.append(record(prefix + "-0", 3000), live());
        held.awaitReached();
        JournalRef last = j.append(record(prefix + "-1", 3000), live()).ref();
        int waits = timer.withDelay(Duration.ofSeconds(2)).size();
        Outcome<Journal.Appended> waiting = onVirtual(() -> {
            try {
                return j.append(record(prefix + "-2", 3000), live());
            } finally {
                interruptedAfter.set(Thread.currentThread().isInterrupted());
            }
        });
        until(() -> parked(waiting.thread()) && timer.withDelay(Duration.ofSeconds(2)).size() > waits, () -> "the third append waits for room");
        return new Full(held, last, waiting);
    }

    @Test
    void anInterruptedWaitFailsBusyKeepsTheInterruptAndReservesNothing() throws Exception {
        HeldFiles files = seam();
        Journal j = open(bounded("interrupt"), files);
        AtomicBoolean interrupted = new AtomicBoolean();
        Full full = fill(j, files, "iw", interrupted);

        full.waiting().thread().interrupt();
        Throwable busy = full.waiting().failure();
        assertInstanceOf(JournalBusyException.class, busy);
        assertInstanceOf(InterruptedException.class, busy.getCause());
        assertTrue(interrupted.get(), "the interrupt is kept for the caller");

        full.held().release();
        JournalRef next = appendForced(j, record("iw-3", 10));
        assertEquals(full.last().position() + full.last().length(), next.position(), "the interrupted append reserved nothing");
    }

    @Test
    void aWaitingAppendIsRefusedWhenTheJournalCloses() throws Exception {
        HeldFiles files = seam();
        Journal j = open(bounded("close-wait"), files);
        Full full = fill(j, files, "cw", new AtomicBoolean());

        journals.remove(j);
        Outcome<Boolean> closing = onVirtual(() -> {
            j.close();
            return true;
        });
        assertInstanceOf(IllegalStateException.class, full.waiting().failure(), "a close ends the wait");
        full.held().release();
        assertTrue(closing.join(), "the close returns once the held round is forced");
    }

    @Test
    void aRoundTakesAtMost4096Records() throws Exception {
        HeldFiles files = seam();
        Journal j = open(quiet("records"), files);
        Hold held = files.holdForceAfter("rr-0");
        Journal.Appended first = j.append(record("rr-0", 10), live());
        held.awaitReached();
        List<Journal.Appended> queued = new ArrayList<>();
        for (int i = 1; i <= Journal.ROUND_RECORDS + 1; i++) queued.add(j.append(record("rr-" + i, 10), live()));
        held.release();
        await(first.forced());
        for (Journal.Appended a : queued) await(a.forced());

        Path segment = held.file();
        assertEquals(2, files.opsOnAfter(segment, held.op().returned).stream().filter(op -> op.kind == HeldFiles.Kind.FORCE).count(),
                "4097 queued records take two rounds");
        assertNotSame(files.firstWrite("rr-1"), files.firstWrite("rr-" + (Journal.ROUND_RECORDS + 1)), "the last one is in the second round");
        // a gathering write takes at most 1 024 buffers (IOV_MAX): the round's write goes on past each call's last buffer
        for (int i : List.of(1, 1024, 1025, 2048, 2049, Journal.ROUND_RECORDS, Journal.ROUND_RECORDS + 1)) {
            assertArrayEquals(body("rr-" + i, 10), j.read(queued.get(i - 1).ref()), "rr-" + i);
        }
    }

    @Test
    void aRoundTakesAtMost4MiB() throws Exception {
        HeldFiles files = seam();
        Journal j = open(quiet("bytes"), files);
        Hold held = files.holdForceAfter("mb-00");
        Journal.Appended first = j.append(record("mb-00", 100), live());
        held.awaitReached();
        List<Journal.Appended> queued = new ArrayList<>();
        for (int i = 1; i <= 17; i++) queued.add(j.append(record(String.format("mb-%02d", i), 256 * 1024), live()));   // 17 × 256 KiB > 4 MiB
        held.release();
        await(first.forced());
        for (Journal.Appended a : queued) await(a.forced());

        assertEquals(2, files.opsOnAfter(held.file(), held.op().returned).stream().filter(op -> op.kind == HeldFiles.Kind.FORCE).count(),
                "4.25 MiB queued take two rounds");
    }

    @Test
    void anAgeRollWithNoTimerFiredAppliesToTheNextAppend() throws Exception {
        Timer never = (delay, task) -> () -> false;
        Journal j = open(settings(Files.createDirectories(tmp.resolve("no-timer"))).segmentAge(Duration.ofSeconds(1)), never,
                keys(JournalFixture.KEY), JournalFiles.real());
        JournalRef r1 = appendForced(j, record("nt-1", 10));
        clock.advance(Duration.ofMillis(999));
        JournalRef r2 = appendForced(j, record("nt-2", 10));
        clock.advance(Duration.ofMillis(1));
        JournalRef r3 = appendForced(j, record("nt-3", 10));
        assertEquals(List.of(r1.segment(), r1.segment(), r1.segment() + 1), List.of(r1.segment(), r2.segment(), r3.segment()));
    }

    @Test
    void anAgeTimerOfASegmentThatRolledByCountRollsNothing() throws Exception {
        Journal j = open(settings(Files.createDirectories(tmp.resolve("stale"))).segmentRecords(1).segmentAge(Duration.ofSeconds(1)),
                JournalFiles.real());
        JournalRef r1 = appendForced(j, record("sa-1", 10));   // n is full at once: the next record opens n+1
        clock.advance(Duration.ofSeconds(1));                   // n's age timer fires for a segment that already rolled
        JournalRef r2 = appendForced(j, record("sa-2", 10));
        assertEquals(r1.segment() + 1, r2.segment(), "the stale timer rolled nothing: n+1 still takes the next record");
        assertArrayEquals(body("sa-2", 10), j.read(r2));
    }

    @Test
    void aPreOpenThatFailsFailsTheJournalWhenItsSegmentIsNeeded() throws Exception {
        HeldFiles files = seam();
        // the node id is in plain in NODE and in every segment header: the third write carrying it is segment 2's pre-open
        files.holdWrite("node-1").release();   // NODE
        files.holdWrite("node-1").release();   // segment 1, created by the open
        files.holdWrite("node-1").fail(new IOException("ENOSPC (test)"));   // segment 2, pre-opened
        try (LogCapture log = new LogCapture()) {
            Journal j = open(quiet("preopen").segmentRecords(1), files);
            JournalRef r1 = appendForced(j, record("po-1", 10));   // segment 1 ends with it
            Journal.Appended r2 = j.append(record("po-2", 10), live());   // segment 2 is needed now
            assertEquals(r1.segment() + 1, r2.ref().segment());
            Throwable failed = failureOf(r2.forced());
            assertInstanceOf(JournalFailedException.class, failed);
            assertTrue(causedBy(failed, "ENOSPC (test)"), "the pre-open's own failure is the cause: " + chain(failed));
            assertInstanceOf(JournalFailedException.class, appendFailure(j, record("po-3", 10)));
            assertArrayEquals(body("po-1", 10), j.read(r1), "reads go on");
            journals.remove(j);
            j.close();
            assertEquals(1, log.count(Level.SEVERE), log.lines().toString());
        }
    }

    private static boolean causedBy(Throwable t, String message) {
        for (Throwable c = t; c != null; c = c.getCause()) if (message.equals(c.getMessage())) return true;
        return false;
    }

    private static String chain(Throwable t) {
        StringBuilder b = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) b.append(c).append(" <- ");
        return b.toString();
    }

    @Test
    void anIdleSegmentIsSealedAtItsAgeOnTheTimer() throws Exception {
        Journal j = open(settings(Files.createDirectories(tmp.resolve("idle-age"))).segmentAge(Duration.ofSeconds(1)), JournalFiles.real());
        JournalRef ref = appendForced(j, record("ia-1", 10));
        assertEquals(Optional.empty(), j.index(ref.segment()));
        clock.advance(Duration.ofSeconds(1));   // no append follows: the age timer alone rolls the segment
        until(() -> sealed(j, ref.segment()), () -> "the idle segment is sealed at its age");
        assertEquals(List.of("ia-1"), j.index(ref.segment()).orElseThrow().stream().map(JournalIndexEntry::messageId).toList());
    }

    private static boolean sealed(Journal j, long n) {
        try {
            return j.index(n).isPresent();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aWaitingAppendFailsWhenTheJournalFails() throws Exception {
        HeldFiles files = seam();
        Journal j = open(bounded("fail-wait"), files);
        Full full = fill(j, files, "fw", new AtomicBoolean());
        full.held().fail(new IOException("EIO (test)"));
        assertInstanceOf(JournalFailedException.class, full.waiting().failure(), "the failure ends the wait, with no clock advance");
        assertEquals(START, clock.instant());
    }

    @Test
    void aCloseSealsTheOpenSegment() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("close-seals"));
        HeldFiles files = seam();
        Journal j = open(settings(dir).segmentAge(Duration.ofHours(1)), files);
        JournalRef ref = appendForced(j, record("cs-1", 10));
        Path segment = files.fileOf("cs-1");
        journals.remove(j);
        j.close();

        HeldFiles reopen = seam();
        Journal back = open(settings(dir).segmentAge(Duration.ofHours(1)), reopen);
        assertEquals(List.of("cs-1"), back.index(ref.segment()).orElseThrow().stream().map(JournalIndexEntry::messageId).toList());
        assertEquals(List.of(), reopen.opsOnAfter(segment.toAbsolutePath().normalize(), 0), "the start found it sealed: it wrote nothing to it");
    }

    @Test
    void aCloseWaitsForEverySealer() throws Exception {
        HeldFiles files = seam();
        Journal j = open(quiet("close-sealer"), files);
        Hold seal = files.holdSealWriteOf("cx-1");
        appendForced(j, record("cx-1", 10));
        journals.remove(j);
        Outcome<Boolean> closing = onVirtual(() -> {
            j.close();   // rolls the open segment: its sealer is held in the footer write
            return true;
        });
        seal.awaitReached();
        untilParked(closing.thread(), "the close waits");
        assertFalse(closing.done(), "no close returns while a sealer writes");
        seal.release();
        assertTrue(closing.join());
        assertTrue(seal.op().returnedOk() && files.forces(seal.file()) >= 3, "the footer was written and forced before the close returned: " + files.describe());
    }

    @Test
    void aSealerThatFailsFailsTheJournalWhileTheWriterIsIdle() throws Exception {
        HeldFiles files = seam();
        try (LogCapture log = new LogCapture()) {
            Journal j = open(quiet("sealer").segmentRecords(2), files);
            Hold seal = files.holdSealWriteOf("sf-1", "sf-2");
            JournalRef r1 = appendForced(j, record("sf-1", 10));
            appendForced(j, record("sf-2", 10));
            seal.awaitReached();
            untilParked(seal.thread(), "the sealer sits in n's footer write");
            seal.fail(new IOException("EIO (test)"));
            until(() -> log.count(Level.SEVERE) >= 1, () -> "the sealer's failure is an ERROR: " + log.lines());

            assertInstanceOf(JournalFailedException.class, appendFailure(j, record("sf-3", 10)), "the journal failed");
            assertArrayEquals(body("sf-1", 10), j.read(r1), "reads go on");
            assertEquals(1, log.count(Level.SEVERE), log.lines().toString());
        }
    }

    @Test
    void aSecondFailureLogsNoSecondError() throws Exception {
        HeldFiles files = seam();
        try (LogCapture log = new LogCapture()) {
            Journal j = open(quiet("twice").segmentRecords(1), files);
            Hold seal = files.holdSealWriteOf("tw-1");
            Hold force = files.holdForceAfter("tw-2");
            appendForced(j, record("tw-1", 10));
            seal.awaitReached();
            Journal.Appended r2 = j.append(record("tw-2", 10), live());
            force.awaitReached();
            force.fail(new IOException("EIO (test): the writer's force"));
            assertInstanceOf(JournalFailedException.class, failureOf(r2.forced()));
            seal.fail(new IOException("EIO (test): the sealer's write"));
            journals.remove(j);
            j.close();   // waits for the sealer
            assertEquals(1, log.count(Level.SEVERE), "one ERROR for the journal, not one per failure: " + log.lines());
        }
    }

    @Test
    void aFailureFailsEveryQueuedRecordAcrossEndMarkers() throws Exception {
        HeldFiles files = seam();
        Journal j = open(quiet("queued").segmentRecords(1), files);
        Hold force = files.holdForceAfter("fq-1");
        Journal.Appended r1 = j.append(record("fq-1", 10), live());
        force.awaitReached();
        Journal.Appended r2 = j.append(record("fq-2", 10), live());   // each record ends its segment: an end marker follows it
        Journal.Appended r3 = j.append(record("fq-3", 10), live());
        force.fail(new IOException("EIO (test)"));
        for (Journal.Appended a : List.of(r1, r2, r3)) assertInstanceOf(JournalFailedException.class, failureOf(a.forced()), String.valueOf(a.ref()));
    }
}
