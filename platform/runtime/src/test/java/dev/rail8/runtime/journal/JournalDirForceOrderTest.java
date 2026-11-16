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
import java.util.Arrays;
import java.util.List;

import static dev.rail8.runtime.journal.JournalFixture.KEY;
import static dev.rail8.runtime.journal.JournalFixture.START;
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
            // FIX-168: the open's pre-open of segment 2 is held until A's hold is armed (OwnDirForce.Preopen)
            OwnDirForce.Preopen preopen = new OwnDirForce.Preopen(s, files, clock, timer, (node, segment) -> Future.succeededFuture());
            journal = preopen.journal;
            journals.add(journal);
            // the opener's own forces of segments/ (its creation, segment 1's) are over
            until(() -> files.forcesOn(segments).size() >= 2 && files.forcesOn(segments).stream().allMatch(HeldFiles.Op::returnedOk),
                    () -> "the forces of segments/ so far: " + files.forcesOn(segments));
            a = journal.spill(BlobKey.body(name + "-a", 0), KEY, new GeneratedInput(2, 10_000), 1L << 20, Hasher.sha256());
            b = journal.spill(BlobKey.body(name + "-b", 0), KEY, new GeneratedInput(3, 10_000), 1L << 20, Hasher.sha256());
            OwnDirForce.Persisting persistA = new OwnDirForce.Persisting(files, a, segments);
            pa = persistA.call;
            aForce = persistA.force;
            // the pre-open's force of segments/ enters after A's hold is armed, is let through (not A's thread) and returns:
            // no pre-open is in flight once A's persist starts
            preopen.letGo();
            persistA.go();
            aForce.awaitReached();
            OwnDirForce.Persisting persistB = new OwnDirForce.Persisting(files, b, segments);
            pb = persistB.call;
            bForce = persistB.force;
            persistB.go();
            bForce.awaitReached();
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

    /**
     * FIX-280: the mirror of the two above. A's force returns first while B's later force is still held: A's persist returns at
     * once - an earlier persist never waits for a force of segments/ begun after its own.
     */
    @Test
    void anEarlierPersistReturnsWhileALaterForceIsStillHeld() throws Exception {
        Two t = new Two("later-held");

        t.aForce.release();
        until(t.pa::done, () -> "A's persist returns while B's later force of segments/ is held; A's thread is " + t.pa.thread().getState()
                + " at " + Arrays.toString(t.pa.thread().getStackTrace()));
        JournalRef a = t.pa.join();
        assertFalse(t.bForce.op().returnedOk(), "B's force of segments/ is still held");
        assertFalse(t.pb.done(), "B's persist has not returned while its own force is held");
        untilParked(t.pb.thread(), "B's persist sits in its held force of segments/");
        assertArrayEquals(new GeneratedInput(2, 10_000).readAllBytes(), t.journal.stream(a).readAllBytes(), "A is persisted and reads");

        t.bForce.release();
        JournalRef b = t.pb.join();
        assertArrayEquals(new GeneratedInput(3, 10_000).readAllBytes(), t.journal.stream(b).readAllBytes(), "B is persisted and reads");
    }
}
