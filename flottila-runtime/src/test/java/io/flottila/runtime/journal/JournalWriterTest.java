package io.flottila.runtime.journal;

import io.flottila.runtime.journal.HeldFiles.Hold;
import io.flottila.runtime.journal.HeldFiles.Op;
import io.flottila.runtime.journal.JournalFixture.LogCapture;
import io.flottila.runtime.journal.JournalFixture.LoggedTimer;
import io.flottila.runtime.journal.JournalFixture.Outcome;
import io.flottila.runtime.journal.JournalFixture.Settings;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Promise;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import static io.flottila.runtime.journal.JournalFixture.BOUND;
import static io.flottila.runtime.journal.JournalFixture.START;
import static io.flottila.runtime.journal.JournalFixture.appendFailure;
import static io.flottila.runtime.journal.JournalFixture.await;
import static io.flottila.runtime.journal.JournalFixture.body;
import static io.flottila.runtime.journal.JournalFixture.failureOf;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The journal writer (CORE-3 item 3, the fail-stop rule): one long-lived writer virtual thread, rounds that share one
 * force per channel, a future that completes only once every position up to its end is forced, each on a fresh virtual
 * thread, the pending-bytes bound with its append wait on the injected timer, and fail-stop after a write or force
 * error. Every interleaving is staged by the {@link HeldFiles} seam (a held write or force), never by a race or a sleep.
 */
class JournalWriterTest {

    @TempDir Path tmp;

    private final FakeClock clock = FakeClock.at(START);
    private final LoggedTimer timer = new LoggedTimer(new FakeTimer(clock));
    private final List<HeldFiles> seams = new ArrayList<>();
    private final List<Journal> journals = new ArrayList<>();
    private final CountDownLatch listenersMayReturn = new CountDownLatch(1);

    @AfterEach
    void closeEverything() throws Exception {
        listenersMayReturn.countDown();
        seams.forEach(HeldFiles::releaseAll);
        for (Journal j : journals) j.close();
    }

    /** Writer cases keep the age roll out of the way: an hour on a clock that moves only when a case says so. */
    private Settings quiet(String name) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve(name));
        return settings(dir).segmentAge(Duration.ofHours(1));
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

    @Test
    void concurrentAppendsShareOneForce() throws Exception {
        HeldFiles files = seam();
        Journal journal = open(quiet("share"), files);
        Hold first = files.holdForceAfter("share-0");
        Journal.Appended a0 = journal.append(record("share-0", 100), live());
        first.awaitReached();
        Path segment = first.file();
        int forcesSoFar = files.forces(segment);   // the held one included

        List<Journal.Appended> queued = new ArrayList<>();
        for (int i = 1; i <= 8; i++) queued.add(journal.append(record("share-" + i, 100), live()));
        for (Journal.Appended q : queued) {
            assertEquals(a0.ref().segment(), q.ref().segment(), "one segment: no roll at these limits");
            assertFalse(q.forced().isComplete(), "queued behind the held force: " + q.ref());
        }

        first.release();
        await(a0.forced());
        for (Journal.Appended q : queued) await(q.forced());

        assertEquals(forcesSoFar + 1, files.forces(segment),
                "the eight appends queued while the force was held complete after exactly one more force: " + files.describe());
        Op held = first.op();
        Op next = files.forcesOn(segment).getLast();
        assertTrue(held.thread.isVirtual(), "the writer is a virtual thread: " + held);
        assertSame(held.thread, next.thread, "one long-lived writer thread forces both rounds: " + held + " / " + next);
    }

    @Test
    void anIdleAppendIsForcedWithoutATimer() throws Exception {
        HeldFiles files = seam();
        Settings s = quiet("idle");
        Journal journal = open(s, files);

        for (String id : List.of("idle-1", "idle-2")) {   // the second one finds the journal idle again
            Journal.Appended a = journal.append(record(id, 100), live());
            CountDownLatch seen = new CountDownLatch(1);
            Map<String, Boolean> forcedAtCompletion = new ConcurrentHashMap<>();
            a.forced().onComplete(ar -> {
                forcedAtCompletion.put(id, files.forcedPast(id));
                seen.countDown();
            });
            await(a.forced());
            assertTrue(seen.await(BOUND.toSeconds(), TimeUnit.SECONDS), "the listener ran");
            assertEquals(Map.of(id, true), forcedAtCompletion, "forced before its future completed: " + files.describe());
        }

        // The fake timer fires only when the clock moves, and it never moved: no commit window was waited for.
        assertEquals(START, clock.instant());
        for (LoggedTimer.Scheduled sch : timer.log()) {
            assertEquals(s.segmentAge, sch.delay, "the only timer an append may arm is its segment's age roll: " + timer.log());
        }
    }

    @Test
    void noFutureCompletesBeforeItsForce() throws Exception {
        HeldFiles files = seam();
        Journal journal = open(quiet("before"), files);
        Hold held = files.holdForceAfter("nf-1");
        Map<String, Journal.Appended> appended = new LinkedHashMap<>();
        appended.put("nf-1", journal.append(record("nf-1", 100), live()));
        held.awaitReached();
        for (String id : List.of("nf-2", "nf-3", "nf-4")) appended.put(id, journal.append(record(id, 100), live()));

        // A listener registered now runs at once on a future that already completed: it sees the force still held.
        Map<String, Boolean> forcedAtCompletion = new ConcurrentHashMap<>();
        CountDownLatch completions = new CountDownLatch(appended.size());
        appended.forEach((id, a) -> a.forced().onComplete(ar -> {
            forcedAtCompletion.put(id, files.forcedPast(id));
            completions.countDown();
        }));
        untilParked(held.thread(), "the writer sits in the held force");
        appended.forEach((id, a) -> assertFalse(a.forced().isComplete(), id + " completed while its force is held"));

        held.release();
        for (Journal.Appended a : appended.values()) await(a.forced());
        assertTrue(completions.await(BOUND.toSeconds(), TimeUnit.SECONDS), "every listener ran");
        assertEquals(Map.of("nf-1", true, "nf-2", true, "nf-3", true, "nf-4", true), forcedAtCompletion,
                "each future completed only after a force covering its record returned: " + files.describe());
    }

    /** The write of n's last record held, or the force after it: what the writer does on n+1 meanwhile goes through. */
    enum Staged { WRITE_OF_N, FORCE_OF_N }

    @ParameterizedTest
    @EnumSource(Staged.class)
    void noFutureCompletesWhileAnEarlierPositionIsUnwritten(Staged staged) throws Exception {
        HeldFiles files = seam();
        Journal journal = open(quiet("order-" + staged).segmentRecords(2), files);
        Hold round0 = files.holdForceAfter("oo-0");
        Journal.Appended r0 = journal.append(record("oo-0", 100), live());
        round0.awaitReached();
        // queued behind round 0, so one round drains them: oo-1 ends segment n, oo-2 and oo-3 open n+1
        Journal.Appended r1 = journal.append(record("oo-1", 100), live());
        Journal.Appended r2 = journal.append(record("oo-2", 100), live());
        Journal.Appended r3 = journal.append(record("oo-3", 100), live());
        assertEquals(r0.ref().segment(), r1.ref().segment(), "segmentRecords 2: oo-1 is n's last record");
        assertEquals(r0.ref().segment() + 1, r2.ref().segment(), "oo-2 opens n+1");
        assertEquals(r2.ref().segment(), r3.ref().segment());

        Hold earlier = staged == Staged.WRITE_OF_N ? files.holdWrite("oo-1") : files.holdForceAfter("oo-1");
        round0.release();
        await(r0.forced());
        earlier.awaitReached();
        untilParked(earlier.thread(), "the writer sits in the held " + staged);
        for (Journal.Appended later : List.of(r1, r2, r3)) {
            assertFalse(later.forced().isComplete(), later.ref() + " completed while n's position before it is not forced");
        }

        // The earlier position never becomes durable: no later future may have completed, so every one of them fails.
        earlier.fail(new IOException("EIO (test): " + staged + " never happens"));
        for (Journal.Appended later : List.of(r1, r2, r3)) {
            assertInstanceOf(JournalFailedException.class, failureOf(later.forced()), later.ref() + ": " + files.describe());
        }
    }

    @Test
    void aRoundSpanningARollForcesBothChannels() throws Exception {
        HeldFiles files = seam();
        Journal journal = open(quiet("span").segmentRecords(2), files);
        Hold round0 = files.holdForceAfter("span-0");
        Journal.Appended r0 = journal.append(record("span-0", 100), live());
        round0.awaitReached();
        Journal.Appended r1 = journal.append(record("span-1", 100), live());   // n's last record
        Journal.Appended r2 = journal.append(record("span-2", 100), live());   // n+1's first
        assertEquals(r0.ref().segment(), r1.ref().segment());
        assertEquals(r1.ref().segment() + 1, r2.ref().segment());

        // Captured when each future completes: has the writer itself forced n and n+1 past these records by then?
        Map<String, List<Boolean>> atCompletion = new ConcurrentHashMap<>();
        CountDownLatch completions = new CountDownLatch(2);
        Map.of("span-1", r1, "span-2", r2).forEach((id, a) -> a.forced().onComplete(ar -> {
            atCompletion.put(id, List.of(files.forcedByItsWriter("span-1"), files.forcedByItsWriter("span-2")));
            completions.countDown();
        }));
        round0.release();
        await(r0.forced());
        await(r1.forced());
        await(r2.forced());
        assertTrue(completions.await(BOUND.toSeconds(), TimeUnit.SECONDS));

        assertNotEquals(files.fileOf("span-1"), files.fileOf("span-2"), "the round wrote two channels");
        assertEquals(Map.of("span-1", List.of(true, true), "span-2", List.of(true, true)), atCompletion,
                "both channels were forced by the writer before either future of the round completed: " + files.describe());
    }

    @Test
    void aFailedForceIsNeverRetriedOnThatFile() throws Exception {
        long healthy = forcesOnTheFileAfterItsForce(false);
        long failed = forcesOnTheFileAfterItsForce(true);
        assertTrue(healthy > 0, "witness: the same script forces that file again in a healthy journal (" + healthy + ")");
        assertEquals(0, failed, "after a failed force the seam sees no further force on that file");
    }

    /**
     * Appends fr-1 with the force after it held, then fails or releases that force; then does what forces the file again
     * in a healthy journal (the next append, the age roll's seal an hour later, close) and counts the forces on the file
     * entered after the held force returned.
     */
    private long forcesOnTheFileAfterItsForce(boolean fail) throws Exception {
        HeldFiles files = seam();
        FakeClock own = FakeClock.at(START);
        Settings s = settings(Files.createDirectories(tmp.resolve("retry-" + fail))).segmentAge(Duration.ofSeconds(1));
        Journal journal = Journal.open(s.config(), own, new FakeTimer(own), keys(JournalFixture.KEY), files);
        journals.add(journal);
        await(journal.append(record("fr-0", 100), live()).forced());
        Hold h = files.holdForceAfter("fr-1");
        Journal.Appended r1 = journal.append(record("fr-1", 100), live());
        h.awaitReached();
        if (fail) {
            h.fail(new IOException("EIO (test)"));
            assertInstanceOf(JournalFailedException.class, failureOf(r1.forced()));
            assertInstanceOf(JournalFailedException.class, appendFailure(journal, record("fr-2", 100)));
        } else {
            h.release();
            await(r1.forced());
            await(journal.append(record("fr-2", 100), live()).forced());
        }
        own.advance(Duration.ofHours(1));   // the age roll: a healthy journal seals the segment (footer, force)
        journals.remove(journal);
        journal.close();                    // a failed journal still closes, and close forces nothing it failed on

        until(() -> h.op().returned >= 0, () -> "the held force returned");
        return files.opsOnAfter(h.file(), h.op().returned).stream().filter(op -> op.kind == HeldFiles.Kind.FORCE).count();
    }

    @Test
    void aFailedJournalFailsQueuedAndLaterAppendsRetryable() throws Exception {
        HeldFiles files = seam();
        try (LogCapture log = new LogCapture()) {
            Journal journal = open(quiet("failed"), files);
            Journal.Appended r0 = journal.append(record("ff-0", 700), live());
            await(r0.forced());

            Hold h = files.holdForceAfter("ff-1");
            Journal.Appended r1 = journal.append(record("ff-1", 100), live());
            h.awaitReached();
            Journal.Appended r2 = journal.append(record("ff-2", 100), live());   // queued behind the held force
            Journal.Appended r3 = journal.append(record("ff-3", 100), live());
            h.fail(new IOException("EIO (test)"));

            assertInstanceOf(JournalFailedException.class, failureOf(r1.forced()), "the round's append");
            assertInstanceOf(JournalFailedException.class, failureOf(r2.forced()), "a queued append");
            assertInstanceOf(JournalFailedException.class, failureOf(r3.forced()), "a queued append");
            until(() -> log.count(Level.SEVERE) >= 1, () -> "the failure is logged at ERROR: " + log.lines());

            assertInstanceOf(JournalFailedException.class, appendFailure(journal, record("ff-4", 100)), "a later append");
            assertInstanceOf(JournalFailedException.class, appendFailure(journal, record("ff-5", 100)), "a later append");

            assertArrayEquals(body("ff-0", 700), journal.read(r0.ref()), "reads go on after the failure");
            assertEquals(1, log.count(Level.SEVERE), "one ERROR for the failure, not one per append: " + log.lines());
        }
    }

    /**
     * Fills the pending bound behind a held force; returns the appends taken, the first one that waits for room and the
     * promise that abandons that one's save (each append has its own).
     */
    private record Filled(Hold held, List<Journal.Appended> taken, Outcome<Journal.Appended> waiting, Promise<Void> abandon) {}

    private Filled fillTheBound(Journal journal, HeldFiles files, Duration appendWait, String prefix) throws Exception {
        Hold held = files.holdForceAfter(prefix + "-0");
        List<Journal.Appended> taken = new ArrayList<>();
        taken.add(journal.append(record(prefix + "-0", 3000), live()));
        held.awaitReached();
        for (int i = 1; i <= 5; i++) {
            int waitsBefore = timer.withDelay(appendWait).size();
            String id = prefix + "-" + i;
            Promise<Void> abandon = Promise.promise();
            Outcome<Journal.Appended> o = onVirtual(() -> journal.append(record(id, 3000), abandon.future()));
            until(() -> o.done() || (parked(o.thread()) && timer.withDelay(appendWait).size() > waitsBefore),
                    () -> id + " returns or waits on the fake timer; timer " + timer.log());
            if (!o.done()) return new Filled(held, taken, o, abandon);
            taken.add(o.join());
        }
        throw new AssertionError("six 3000-byte appends behind a held force never waited for room under an 8192-byte bound");
    }

    private Settings bounded(String name, Duration appendWait) throws IOException {
        return quiet(name).recordMax(4096).pendingBytes(8192).appendWait(appendWait);
    }

    /** After the held round is forced the next append is taken at once, right after the last taken position. */
    private void nothingWasReserved(Journal journal, Filled f, String id) throws Exception {
        f.held().release();
        for (Journal.Appended t : f.taken()) await(t.forced());
        Outcome<Journal.Appended> next = onVirtual(() -> journal.append(record(id, 3000), live()));
        JournalRef last = f.taken().getLast().ref();
        Journal.Appended taken = next.join();
        JournalRef ref = taken.ref();
        assertEquals(last.segment(), ref.segment());
        assertEquals(last.position() + last.length(), ref.position(), "the append that failed reserved nothing: " + last + " → " + ref);
        await(taken.forced());
    }

    @Test
    void waitingBytesAboveTheBoundFailAtTheDeadline() throws Exception {
        HeldFiles files = seam();
        Duration wait = Duration.ofSeconds(2);
        Journal journal = open(bounded("bound", wait), files);
        Filled f = fillTheBound(journal, files, wait, "wb");

        assertTrue(f.taken().size() >= 2 && f.taken().size() <= 3,
                "8192 pending bytes take two 3000-byte records and refuse the next one above the bound (taken " + f.taken().size() + ")");
        assertEquals(1, timer.withDelay(wait).size(), "one wait, armed on the injected timer for FLOTTILA_JOURNAL_APPEND_WAIT");
        clock.advance(wait);
        assertInstanceOf(JournalBusyException.class, f.waiting().failure(), "the waiting append fails retryable at the deadline");
        assertTrue(timer.withDelay(wait).getFirst().fired.get());
        nothingWasReserved(journal, f, "wb-next");
    }

    @Test
    void aWaitingAppendEndsWhenItsSaveIsAbandoned() throws Exception {
        HeldFiles files = seam();
        Duration wait = Duration.ofSeconds(2);
        Journal journal = open(bounded("abandoned", wait), files);
        Filled f = fillTheBound(journal, files, wait, "ab");

        f.abandon().complete();
        assertInstanceOf(JournalBusyException.class, f.waiting().failure(), "the wait ends when its save is abandoned");
        assertEquals(START, clock.instant(), "with no clock advance");
        LoggedTimer.Scheduled its = timer.withDelay(wait).getFirst();
        assertTrue(its.cancelled.get() && !its.fired.get(), "its timer is cancelled, not left pending: " + timer.log());
        nothingWasReserved(journal, f, "ab-next");
    }

    @Test
    void aWaitingAppendEndsAtAppendWaitOnTheFakeTimer() throws Exception {
        HeldFiles files = seam();
        Duration wait = Duration.ofMillis(1500);   // seconds and a fraction: a unit conversion that drops either fails
        Journal journal = open(bounded("deadline", wait), files);
        Filled f = fillTheBound(journal, files, wait, "dl");
        LoggedTimer.Scheduled its = timer.withDelay(wait).getFirst();

        clock.advance(wait.minusNanos(1));
        assertFalse(its.fired.get(), "one nanosecond before FLOTTILA_JOURNAL_APPEND_WAIT nothing fired");
        assertFalse(f.waiting().done(), "and the append still waits");

        clock.advance(Duration.ofNanos(1));
        assertInstanceOf(JournalBusyException.class, f.waiting().failure(), "at FLOTTILA_JOURNAL_APPEND_WAIT the wait fails retryable");
        assertTrue(its.fired.get());
    }

    @Test
    void aWaitingAppendIsTakenWhenRoomFrees() throws Exception {
        HeldFiles files = seam();
        Duration wait = Duration.ofSeconds(2);
        Journal journal = open(bounded("room", wait), files);
        Filled f = fillTheBound(journal, files, wait, "rf");

        f.held().release();   // the held round is forced: its bytes leave the bound
        Journal.Appended taken = f.waiting().join();
        await(taken.forced());
        JournalRef last = f.taken().getLast().ref();
        assertEquals(last.position() + last.length(), taken.ref().position(), "taken right after the last taken one");
        assertEquals(START, clock.instant());
        LoggedTimer.Scheduled its = timer.withDelay(wait).getFirst();
        assertTrue(its.cancelled.get() && !its.fired.get(), "its wait is cancelled once room was found: " + timer.log());
    }

    @Test
    void aBlockingListenerDelaysNoOtherFuture() throws Exception {
        HeldFiles files = seam();
        Journal journal = open(quiet("listeners"), files);
        Hold round0 = files.holdForceAfter("bl-0");
        Journal.Appended r0 = journal.append(record("bl-0", 100), live());
        round0.awaitReached();
        List<Journal.Appended> round = new ArrayList<>();
        for (int i = 1; i <= 3; i++) round.add(journal.append(record("bl-" + i, 100), live()));   // one round

        Set<Thread> listenerThreads = ConcurrentHashMap.newKeySet();
        List<Throwable> interrupted = new CopyOnWriteArrayList<>();
        CountDownLatch inside = new CountDownLatch(round.size());
        for (Journal.Appended a : round) {
            a.forced().onComplete(ar -> {
                listenerThreads.add(Thread.currentThread());
                inside.countDown();
                try {
                    listenersMayReturn.await();   // blocks this listener until the case ends
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    interrupted.add(e);
                }
            });
        }
        round0.release();
        await(r0.forced());

        assertTrue(inside.await(BOUND.toSeconds(), TimeUnit.SECONDS),
                "all three listeners of one round are blocked at the same time: each future completed on a thread of its own ("
                        + (round.size() - inside.getCount()) + " of 3 inside)");
        assertEquals(3, listenerThreads.size());
        for (Thread t : listenerThreads) assertTrue(t.isVirtual(), "a listener never blocks a platform thread: " + t);

        Journal.Appended later = journal.append(record("bl-4", 100), live());
        await(later.forced());   // the writer is not stalled by the blocked listeners either
        assertNotNull(later.ref());
        listenersMayReturn.countDown();
        assertEquals(List.of(), interrupted);
    }
}
