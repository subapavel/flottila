package dev.rail8.runtime.journal;

import dev.rail8.api.BlobKey;
import dev.rail8.api.Hasher;
import dev.rail8.runtime.journal.JournalFixture.Outcome;
import dev.rail8.runtime.journal.JournalFixture.Settings;
import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeTimer;
import io.vertx.core.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static dev.rail8.runtime.journal.JournalFixture.KEY;
import static dev.rail8.runtime.journal.JournalFixture.START;
import static dev.rail8.runtime.journal.JournalFixture.keys;
import static dev.rail8.runtime.journal.JournalFixture.onVirtual;
import static dev.rail8.runtime.journal.JournalFixture.settings;
import static dev.rail8.runtime.journal.JournalFixture.until;
import static dev.rail8.runtime.journal.JournalFixture.untilParked;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The order of two persists' forces of {@code segments/} (fsyncgate, CORE-4d): a persist whose own directory force returned
 * still waits for every earlier force of it in flight; it is persisted only if none of them failed. A later force that
 * succeeded proves nothing for an earlier one still running.
 */
class JournalDirForceOrderTest {

    @TempDir Path tmp;

    private final FakeClock clock = FakeClock.at(START);
    private final FakeTimer timer = new FakeTimer(clock);
    private final List<Journal> journals = new ArrayList<>();
    private final List<HeldFiles> seams = new ArrayList<>();

    @AfterEach
    void closeEverything() throws Exception {
        for (HeldFiles f : seams) f.releaseAll();
        for (Journal j : journals) j.close();
    }

    /** Two spills whose persists sit in their forces of segments/, A's force entered first and B's after it, both held. */
    private final class Two {
        final HeldFiles files = new HeldFiles();
        final Journal journal;
        final Journal.Spill a;
        final Journal.Spill b;
        final HeldFiles.Hold aForce;
        final HeldFiles.Hold bForce;
        final Outcome<JournalRef> pa;
        final Outcome<JournalRef> pb;

        Two(String name) throws Exception {
            seams.add(files);
            Settings s = settings(Files.createDirectories(tmp.resolve(name))).segmentAge(Duration.ofHours(1)).segmentRecords(1).recordMax(4096);
            Path segments = s.dir.resolve("segments");
            // FIX-168: the open's pre-open of segment 2 forces segments/ on a thread of its own; it is held at its file's force,
            // before its force of segments/, so that one enters after A's hold is armed — the order a loaded run met, where a
            // hold that took any thread's force held the pre-open's as A's
            HeldFiles.Hold preopen = files.holdForceOn(segments.resolve("2.seg"), 0);
            // opened off the test thread with a bounded join: an open that forced 2.seg itself, or waited for its pre-open,
            // would sit in the held force; it fails here within the bound instead of hanging the run
            Outcome<Journal> opening = onVirtual(() -> Journal.open(s.config(), clock, timer, keys(KEY), files, (node, segment) -> Future.succeededFuture()));
            preopen.awaitReached();
            if (preopen.thread() == opening.thread()) throw new AssertionError("fixture: 2.seg is forced by the opening thread, not a pre-open: " + preopen.op());
            try {
                journal = opening.join();
            } catch (AssertionError e) {
                throw new AssertionError("fixture: Journal.open returns while its pre-open's force of 2.seg is held (" + preopen.op() + ")", e);
            }
            journals.add(journal);
            // the opener's own forces of segments/ (its creation, segment 1's) are over
            until(() -> files.forcesOn(segments).size() >= 2 && files.forcesOn(segments).stream().allMatch(HeldFiles.Op::returnedOk),
                    () -> "the forces of segments/ so far: " + files.forcesOn(segments));
            a = journal.spill(BlobKey.body(name + "-a", 0), KEY, new GeneratedInput(2, 10_000), 1L << 20, Hasher.sha256());
            b = journal.spill(BlobKey.body(name + "-b", 0), KEY, new GeneratedInput(3, 10_000), 1L << 20, Hasher.sha256());
            Persisting persistA = new Persisting(a, segments);
            pa = persistA.call;
            aForce = persistA.force;
            // the pre-open's force of segments/ enters after A's hold is armed, is let through (not A's thread) and returns:
            // no pre-open is in flight once A's persist starts
            preopen.release();
            until(() -> files.forcesOn(segments).stream().anyMatch(op -> op.thread == preopen.thread() && op.returnedOk()),
                    () -> "the pre-open's force of segments/ returned: " + files.forcesOn(segments));
            persistA.go();
            aForce.awaitReached();
            Persisting persistB = new Persisting(b, segments);
            pb = persistB.call;
            bForce = persistB.force;
            persistB.go();
            bForce.awaitReached();
        }

        /**
         * A persist of {@code spill} on a virtual thread of its own, whose first force of {@code segments} is held by that
         * thread (FIX-168: the hold is armed before the thread runs, so it can only meet this persist's own directory force).
         */
        private final class Persisting {
            final CountDownLatch started = new CountDownLatch(1);
            final Outcome<JournalRef> call;
            final HeldFiles.Hold force;

            Persisting(Journal.Spill spill, Path segments) {
                call = onVirtual(() -> {
                    if (!started.await(JournalFixture.BOUND.toSeconds(), TimeUnit.SECONDS)) throw new AssertionError("fixture: the persist was never let go");
                    return spill.persist();
                });
                force = files.holdForceOn(segments, files.now(), call.thread());
            }

            void go() { started.countDown(); }
        }

        /** B's force returns first; B's persist does not return while A's earlier force is still held. */
        void bForceReturnsFirst() {
            bForce.release();
            until(() -> bForce.op().returnedOk(), () -> "B's force of segments/ returned");
            untilParked(pb.thread(), "B's persist waits for A's earlier force");
            assertFalse(pb.done(), "B's own force returned, A's earlier one did not: B's persist has not returned");
        }
    }

    @Test
    void aLaterForceThatReturnedFirstFailsWithTheEarlierOneThatFailed() throws Exception {
        Two t = new Two("earlier-fails");
        t.bForceReturnsFirst();

        t.aForce.fail(new IOException("EIO on the directory force (test)"));
        assertInstanceOf(IOException.class, t.pa.failure(), "A's persist fails with its directory force");
        Throwable bFailed = t.pb.failure();
        assertInstanceOf(JournalFailedException.class, bFailed, "an earlier force of segments/ failed: B is not persisted: " + bFailed);
        assertThrows(IOException.class, () -> t.journal.stream(t.b.ref()), "and its record is not readable as a persisted segment's");
    }

    @Test
    void aLaterForceThatReturnedFirstIsPersistedOnceTheEarlierOneSucceeded() throws Exception {
        Two t = new Two("earlier-succeeds");
        t.bForceReturnsFirst();

        t.aForce.release();
        JournalRef a = t.pa.join();
        JournalRef b = t.pb.join();
        assertArrayEquals(new GeneratedInput(2, 10_000).readAllBytes(), t.journal.stream(a).readAllBytes(), "A is persisted and reads");
        assertArrayEquals(new GeneratedInput(3, 10_000).readAllBytes(), t.journal.stream(b).readAllBytes(), "B is persisted and reads");
    }
}
