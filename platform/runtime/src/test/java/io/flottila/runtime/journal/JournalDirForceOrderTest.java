package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.Hasher;
import io.flottila.runtime.journal.JournalFixture.Outcome;
import io.flottila.runtime.journal.JournalFixture.Settings;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
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

import static io.flottila.runtime.journal.JournalFixture.KEY;
import static io.flottila.runtime.journal.JournalFixture.START;
import static io.flottila.runtime.journal.JournalFixture.keys;
import static io.flottila.runtime.journal.JournalFixture.onVirtual;
import static io.flottila.runtime.journal.JournalFixture.settings;
import static io.flottila.runtime.journal.JournalFixture.until;
import static io.flottila.runtime.journal.JournalFixture.untilParked;
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
            journal = Journal.open(s.config(), clock, timer, keys(KEY), files, (node, segment) -> Future.succeededFuture());
            journals.add(journal);
            Path segments = s.dir.resolve("segments");
            // the open's forces of segments/ are over: the holds below can only meet A's and B's
            until(() -> files.forcesOn(segments).size() >= 2 && files.forcesOn(segments).stream().allMatch(HeldFiles.Op::returnedOk),
                    () -> "the forces of segments/ so far: " + files.forcesOn(segments));
            a = journal.spill(BlobKey.body(name + "-a", 0), KEY, new GeneratedInput(2, 10_000), 1L << 20, Hasher.sha256());
            b = journal.spill(BlobKey.body(name + "-b", 0), KEY, new GeneratedInput(3, 10_000), 1L << 20, Hasher.sha256());
            aForce = files.holdForceOn(segments, files.now());
            pa = onVirtual(a::persist);
            aForce.awaitReached();
            bForce = files.holdForceOn(segments, files.now());
            pb = onVirtual(b::persist);
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
}
