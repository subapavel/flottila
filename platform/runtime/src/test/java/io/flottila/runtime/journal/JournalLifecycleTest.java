package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.Hasher;
import io.flottila.runtime.journal.HeldFiles.Hold;
import io.flottila.runtime.journal.JournalFixture.LogCapture;
import io.flottila.runtime.journal.JournalFixture.Outcome;
import io.flottila.runtime.journal.JournalFixture.Settings;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import static io.flottila.runtime.journal.JournalFixture.BOUND;
import static io.flottila.runtime.journal.JournalFixture.KEY;
import static io.flottila.runtime.journal.JournalFixture.START;
import static io.flottila.runtime.journal.JournalFixture.appendFailure;
import static io.flottila.runtime.journal.JournalFixture.await;
import static io.flottila.runtime.journal.JournalFixture.body;
import static io.flottila.runtime.journal.JournalFixture.keys;
import static io.flottila.runtime.journal.JournalFixture.live;
import static io.flottila.runtime.journal.JournalFixture.onVirtual;
import static io.flottila.runtime.journal.JournalFixture.record;
import static io.flottila.runtime.journal.JournalFixture.settings;
import static io.flottila.runtime.journal.JournalFixture.until;
import static io.flottila.runtime.journal.LargeBodyTest.onlyFile;
import static io.flottila.runtime.journal.LargeBodyTest.readAll;
import static io.flottila.runtime.journal.LargeBodyTest.segmentFile;
import static io.flottila.runtime.journal.LargeBodyTest.spillDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The carries from CORE-4a/4b into CORE-4c1 (packet Scope 7): {@code close()} waits for the spills and persists in flight
 * and refuses new ones before it gives the {@code LOCK} up; a force failure in a persist — the file's or the directory's —
 * fails the journal (fail-stop, CORE-3 item 3); a failed journal refuses spills, persists and appends retryable
 * ({@link JournalFailedException}); a failed directory force after the move leaves the spill unpersistable; the fail-stop
 * accessor the admin health reads.
 *
 * <p>The API beyond CORE-4b these cases compile against (proposed by the test-author, CORE-4c1 handback):
 * {@code boolean Journal.isFailed()} — false until the journal fails, true from its fail-stop on.
 *
 * <p>"Close waits" is witnessed by the close thread parked (or blocked) and not returned, twice — before and after a second
 * open is refused because the {@code LOCK} is still held — while the persist or spill is held by the case; then, once the
 * case lets the held one go, by what the close thread finds as its close returns: the persist's segment in place, the
 * spill's file written whole. (Not by the order in which the two calls return: the held call ends inside the journal,
 * before its caller can note it, so the close may return first — a race, implementer's fix of the expectation.)
 */
class JournalLifecycleTest {

    static final int LARGE = (2 << 20) + 17;

    @TempDir Path tmp;

    private final FakeClock clock = FakeClock.at(START);
    private final FakeTimer timer = new FakeTimer(clock);
    private final List<HeldFiles> seams = new ArrayList<>();
    private final List<Journal> journals = new ArrayList<>();
    private final List<CountDownLatch> gates = new ArrayList<>();

    @AfterEach
    void closeEverything() throws Exception {
        gates.forEach(CountDownLatch::countDown);   // a case that failed while holding a call lets it go before the close
        seams.forEach(HeldFiles::releaseAll);
        for (Journal j : journals) j.close();
    }

    private Settings in(String name) throws IOException {
        return settings(Files.createDirectories(tmp.resolve(name))).recordMax(4096).segmentAge(Duration.ofHours(1));
    }

    private Journal open(Settings s, JournalFiles files, SegmentRows rows) throws IOException {
        Journal j = Journal.open(s.config(), clock, timer, keys(KEY), files, rows);
        journals.add(j);
        return j;
    }

    private CountDownLatch gate() {
        CountDownLatch g = new CountDownLatch(1);
        gates.add(g);
        return g;
    }

    private static void awaitGate(CountDownLatch g, String what) {
        try {
            if (!g.await(BOUND.toSeconds(), TimeUnit.SECONDS)) throw new AssertionError(what + " was not opened within " + BOUND);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while " + what, e);
        }
    }

    private static Journal.Spill spill(Journal j, String id, long seed, long size) throws IOException {
        return j.spill(BlobKey.body(id, 0), KEY, new GeneratedInput(seed, size), size, Hasher.sha256());
    }

    private static JournalRef appendForced(Journal j, JournalRecord r) throws Exception {
        Journal.Appended a = j.append(r, live());
        await(a.forced());
        return a.ref();
    }

    /** The bytes of the files directly under {@code dir}. */
    private static long bytesUnder(Path dir) throws IOException {
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            long sum = 0;
            for (Path f : files.toList()) sum += Files.size(f);
            return sum;
        }
    }

    /** True when {@code t} waits — parked with a blocker, or blocked on a monitor — rather than runs. */
    private static boolean waiting(Thread t) {
        return JournalFixture.parked(t) || t.getState() == Thread.State.BLOCKED;
    }

    /** Waits until the close on {@code close} waits (or returned, which the caller then fails on). */
    private static void untilWaitingOrDone(Outcome<?> close) {
        until(() -> close.done() || waiting(close.thread()),
                () -> "close waits: " + close.thread().getState() + " at " + Arrays.toString(close.thread().getStackTrace()));
    }

    /** A second open of {@code s}'s directory while the LOCK is held: refused, and the refusal names the LOCK. */
    private void assertLockHeld(Settings s, String when) {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Journal.open(s.config(), clock, timer, keys(KEY), JournalFiles.real(), new RecordingRows()), when + ": the LOCK is still held");
        assertTrue(e.getMessage().contains("LOCK"), e.getMessage());
    }

    /** Forces on {@code dir} entered by the calling thread (a pre-open forces the directory too, on a thread of its own). */
    private static long forcesByThisThread(HeldFiles files, Path dir) {
        Thread me = Thread.currentThread();
        return files.forcesOn(dir.toAbsolutePath().normalize()).stream().filter(op -> op.thread == me).count();
    }

    @Test
    void closeWaitsForAnInFlightPersistAndRefusesNewWorkBeforeItGivesTheLockUp() throws Exception {
        RecordingRows rows = new RecordingRows();
        Settings s = in("close-persist");
        Journal j = open(s, JournalFiles.real(), rows);
        Journal.Spill held = spill(j, "cp-1", 1, LARGE);
        Journal.Spill waiting = spill(j, "cp-2", 2, 10_000);   // spilled before the close: its persist after the close began is refused
        CountDownLatch inInsert = new CountDownLatch(1);
        CountDownLatch mayAnswer = gate();
        rows.onInsert(held.segment(), insert -> {
            inInsert.countDown();
            awaitGate(mayAnswer, "the held segment-row insert");
        });
        List<String> events = new CopyOnWriteArrayList<>();
        Outcome<JournalRef> persist = onVirtual(() -> {
            try {
                return held.persist();
            } finally {
                events.add("persist returned");
            }
        });
        assertTrue(inInsert.await(BOUND.toSeconds(), TimeUnit.SECONDS), "the persist reached its segment-row insert");

        Path heldSegment = s.dir.resolve("segments").resolve(held.segment() + ".seg");
        Outcome<Void> close = onVirtual(() -> {
            j.close();
            events.add(Files.exists(heldSegment) ? "close returned, the persist's segment in place" : "close returned before the persist's move");
            return null;
        });
        untilWaitingOrDone(close);
        assertFalse(close.done(), "close does not return while a persist is in flight");
        assertLockHeld(s, "while the persist is in flight");
        assertThrows(IllegalStateException.class, () -> spill(j, "cp-3", 3, 10_000), "a new spill is refused once the close began");
        assertThrows(IllegalStateException.class, waiting::persist, "a new persist is refused once the close began");
        untilWaitingOrDone(close);
        assertFalse(close.done(), "close still waits after the refusals: " + events);

        mayAnswer.countDown();
        JournalRef ref = persist.join();
        close.join();
        journals.remove(j);

        assertTrue(events.contains("close returned, the persist's segment in place"), "the in-flight persist ends before the close returns: " + events);
        Journal back = open(s, JournalFiles.real(), rows);   // the LOCK was given back
        assertArrayEquals(GeneratedInput.bytes(1, LARGE), readAll(back.stream(ref)), "the persist the close waited for is durable");
    }

    /** A body stream that gives its first {@code before} bytes, then waits inside {@code read} until {@link #go} opens. */
    private final class HeldInput extends FilterInputStream {
        private final long before;
        private long given;
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch go = gate();

        HeldInput(InputStream in, long before) {
            super(in);
            this.before = before;
        }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (given >= before && reached.getCount() > 0) {
                reached.countDown();
                try {
                    if (!go.await(BOUND.toSeconds(), TimeUnit.SECONDS)) throw new InterruptedIOException("the held body was never let go");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("interrupted inside the held body");
                }
            }
            int n = super.read(b, off, given < before ? (int) Math.min(len, before - given) : len);
            if (n > 0) given += n;
            return n;
        }
    }

    @Test
    void closeWaitsForAnInFlightSpillBeforeItGivesTheLockUp() throws Exception {
        Settings s = in("close-spill");
        Journal j = open(s, JournalFiles.real(), new RecordingRows());
        HeldInput body = new HeldInput(new GeneratedInput(4, LARGE), (1 << 20) + 5);
        List<String> events = new CopyOnWriteArrayList<>();
        Outcome<String> spilling = onVirtual(() -> {
            try {
                j.spill(BlobKey.body("cs-1", 0), KEY, body, LARGE, Hasher.sha256());
                return "spilled";
            } catch (IOException | RuntimeException refused) {
                return "refused: " + refused;
            } finally {
                events.add("spill returned");
            }
        });
        assertTrue(body.reached.await(BOUND.toSeconds(), TimeUnit.SECONDS), "the spill is reading its body");

        Path spills = s.dir.resolve("spill");
        Outcome<Long> close = onVirtual(() -> {
            j.close();
            events.add("close returned");
            return bytesUnder(spills);
        });
        untilWaitingOrDone(close);
        assertFalse(close.done(), "close does not return while a spill is in flight");
        assertLockHeld(s, "while the spill is in flight");
        untilWaitingOrDone(close);
        assertFalse(close.done(), "close still waits: " + events);

        body.go.countDown();
        String outcome = spilling.join();
        long atClose = close.join();
        journals.remove(j);

        assertEquals("spilled", outcome, "the in-flight spill ends whole");
        assertEquals(bytesUnder(spills), atClose, "the spill's file was written whole when the close returned: " + events);
        open(s, JournalFiles.real(), new RecordingRows());   // the LOCK was given back
    }

    @Test
    void aPersistWhoseFileForceFailsFailsTheJournalAndLaterWritesAreRefusedRetryable() throws Exception {
        HeldFiles files = new HeldFiles();
        seams.add(files);
        RecordingRows rows = new RecordingRows();
        Settings s = in("persist-force");
        Journal j = open(s, files, rows);
        JournalRef before = appendForced(j, record("pf-0", 300));
        Journal.Spill earlier = spill(j, "pf-1", 5, 10_000);
        Journal.Spill failing = spill(j, "pf-2", 6, 10_000);
        assertFalse(j.isFailed(), "a journal that has not failed says so");
        Path spilled = spillDir(s.dir).resolve(failing.segment() + ".seg");
        assertTrue(Files.isRegularFile(spilled), "fixture: the spill file of " + failing.segment());
        Hold force = files.holdForceOn(spilled, files.now(), Thread.currentThread());
        IOException eio = new IOException("EIO on the spill's footer force (test)");
        force.fail(eio);

        try (LogCapture log = new LogCapture()) {
            IOException first = assertThrows(IOException.class, failing::persist);
            assertSame(eio, first, "the persist fails with the force's own failure");

            assertTrue(j.isFailed(), "a failed persist force fails the journal (fail-stop)");
            assertInstanceOf(JournalFailedException.class, assertThrows(Exception.class, () -> spill(j, "pf-3", 7, 10_000)), "a later spill is refused retryable");
            assertInstanceOf(JournalFailedException.class, assertThrows(Exception.class, earlier::persist), "the persist of another spill is refused retryable");
            assertInstanceOf(JournalFailedException.class, appendFailure(j, record("pf-4", 100)), "a later append is refused retryable");
            assertEquals(List.of(), rows.insertsOf(earlier.segment()), "the refused persist inserted no row");
            assertFalse(Files.exists(segmentFile(s.dir, failing.segment())), "nothing was moved into segments/");
            assertArrayEquals(body("pf-0", 300), j.read(before), "reads go on");
            assertEquals(1, log.count(Level.SEVERE), "one ERROR for the fail-stop: " + log.lines());
        }
    }

    @Test
    void aPersistWhoseDirectoryForceFailsFailsTheJournalAndItsSpillIsNeverPersisted() throws Exception {
        HeldFiles files = new HeldFiles();
        seams.add(files);
        RecordingRows rows = new RecordingRows();
        Settings s = in("persist-dir-force");
        Journal j = open(s, files, rows);
        Journal.Spill failing = spill(j, "pd-1", 8, 10_000);
        Path segments = s.dir.resolve("segments");
        Hold force = files.holdForceOn(segments, files.now(), Thread.currentThread());
        IOException eio = new IOException("EIO on the segments directory force (test)");
        force.fail(eio);

        IOException first = assertThrows(IOException.class, failing::persist);
        assertSame(eio, first, "the persist fails with the directory force's own failure");
        assertTrue(force.isReached(), "fixture: the failed force is the persist's directory force after the move");
        assertTrue(Files.isRegularFile(segmentFile(s.dir, failing.segment())), "fixture: the file was moved before the directory force");

        assertTrue(j.isFailed(), "a failed directory force fails the journal (fail-stop)");
        long forces = forcesByThisThread(files, segments);
        assertThrows(Exception.class, failing::persist, "the spill whose directory force failed is never answered as persisted");
        assertEquals(forces, forcesByThisThread(files, segments), "the failed directory force is never retried");
        assertInstanceOf(JournalFailedException.class, assertThrows(Exception.class, () -> spill(j, "pd-2", 9, 10_000)), "a later spill is refused retryable");
        assertInstanceOf(JournalFailedException.class, appendFailure(j, record("pd-3", 100)), "a later append is refused retryable");
    }

    @Test
    void aWriterForceFailureRefusesSpillsAndPersistsTooAndTheAccessorReportsIt() throws Exception {
        HeldFiles files = new HeldFiles();
        seams.add(files);
        Settings s = in("writer-force");
        Journal j = open(s, files, new RecordingRows());
        Journal.Spill earlier = spill(j, "wf-1", 10, 10_000);
        Hold force = files.holdForceAfter("wf-0");
        force.fail(new IOException("EIO on the writer's force (test)"));
        assertFalse(j.isFailed());

        assertInstanceOf(JournalFailedException.class, JournalFixture.failureOf(j.append(record("wf-0", 200), live()).forced()));

        until(j::isFailed, () -> "the accessor reports the writer's fail-stop");
        assertInstanceOf(JournalFailedException.class, assertThrows(Exception.class, () -> spill(j, "wf-2", 11, 10_000)), "a spill is refused retryable");
        assertInstanceOf(JournalFailedException.class, assertThrows(Exception.class, earlier::persist), "a persist is refused retryable");
        assertTrue(Files.isRegularFile(onlyFile(spillDir(s.dir))), "the earlier spill stays until abandoned");
        earlier.abandon();
    }
}
