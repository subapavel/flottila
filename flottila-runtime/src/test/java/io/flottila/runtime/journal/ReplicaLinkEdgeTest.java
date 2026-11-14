package io.flottila.runtime.journal;

import io.flottila.runtime.journal.ReplicaWorld.Owner;
import io.flottila.runtime.journal.peer.ReplWire;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;

import static io.flottila.runtime.journal.ReplicaWorld.B;
import static io.flottila.runtime.journal.ReplicaWorld.HDR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-5b1, the implementer's cases beside T4–T10c: link breaches the packet's list does not name (an OPEN of a segment open on
 * the link, a DATA after its SEAL), and the failures that come off the receipt path — a write failing in the same drain as a
 * written DATA of its segment, a force failing on the link's force thread, a second failure of a segment refused already, a
 * re-OPEN whose force fails — and the socket's pause and resume at exactly HOLD_AT and RESUME_AT queued bytes (wire bytes, r1:
 * DATA frames of exactly 1 MiB on the wire), and a frame queued on a paused link. Observed on the wire through {@link ReplicaWorld} (HeldFiles, the real session); the witness
 * that nothing came between two answers is the next answer the case expects.
 */
@Timeout(120)
class ReplicaLinkEdgeTest {

    @TempDir Path tmp;
    ReplicaWorld world;

    @BeforeEach
    void open() throws Exception {
        world = new ReplicaWorld(tmp);
    }

    @AfterEach
    void close() throws Exception {
        world.close();
    }

    @Test
    void anOpenOfASegmentOpenOnTheLinkAlreadyClosesIt() throws Exception {
        Owner o = world.dial(B);
        o.opened(1, HDR + 100);
        long warnings = world.logs.count(Level.WARNING);
        o.open(1, HDR + 100);
        assertEquals(List.of(), o.untilClosed(), "a second OPEN of segment 1 closes the link, unanswered");
        assertTrue(world.logs.count(Level.WARNING) > warnings, "with a WARNING: " + world.logs.lines());
    }

    @Test
    void aDataAfterItsSealClosesTheLink() throws Exception {
        Owner o = world.dial(B);
        byte[] h = o.opened(1, HDR + 100);
        byte[] d = ReplWire.filled("E-SEALED-", 100);
        o.written(1, HDR, d);
        JournalFixture.until(() -> world.unforced() == 0, () -> "the DATA's force returned: " + world.unforced());
        HeldFiles.Hold force = world.files.holdForceOn(world.part(B, 1), world.files.now());
        o.seal(1, HDR + 100, ReplWire.crc32c(ReplicaHolderTest.concat(h, d)));
        force.awaitReached();   // the seal is in its force: segment 1 is still on the link, sealing
        int mark = world.logs.records().size();
        o.data(1, HDR + 100, ReplWire.filled("E-LATE-", 10));
        List<ReplWire.Answer> answers;
        try {
            answers = o.untilClosed();
        } finally {
            force.release();
        }
        assertEquals(List.of(), answers, "a DATA after the SEAL closes the link, unanswered");
        java.util.logging.SimpleFormatter f = new java.util.logging.SimpleFormatter();
        assertTrue(world.logs.records().stream().skip(mark).anyMatch(r -> r.getLevel().equals(Level.WARNING) && f.formatMessage(r).contains("after its SEAL")),
                "the WARNING names it: " + world.logs.lines());
    }

    @Test
    void aWriteFailingAfterAWrittenDataOfItsDrainSendsNoWrittenAfterItsRefuse() throws Exception {
        Owner o = world.dial(B);
        o.opened(1, HDR + 3000);
        HeldFiles.Hold first = world.files.holdWrite("E-D0-");
        o.data(1, HDR, ReplWire.filled("E-D0-", 1000));
        first.awaitReached();
        world.files.holdWrite("E-D2-").fail(new IOException("fixture: the third pwrite fails"));
        o.data(1, HDR + 1000, ReplWire.filled("E-D1-", 1000));
        o.data(1, HDR + 2000, ReplWire.filled("E-D2-", 1000));
        JournalFixture.until(o::allRead, () -> "node-a read both DATA");
        for (int i = 0; i < 3; i++) world.onLoop();   // both queued behind the held write: the writer's next drain takes them together
        first.release();
        o.expect(ReplWire.RECEIPT, 1, ReplWire.WRITTEN, "the first drain's DATA");
        o.expect(ReplWire.REFUSE, 1, ReplWire.FAILED, "the second drain's failed pwrite, and no WRITTEN of its first DATA before it");
        o.opened(2, HDR + 100);   // the next answer: no WRITTEN of segment 1 came after its REFUSE
        assertTrue(world.files.firstWrite("E-D1-").returnedOk(), "fixture: the drain's first DATA was written");
    }

    @Test
    void aFailedForceIsRefusedFailedAndTruesTheCount() throws Exception {
        Owner o = world.dial(B);
        long before = world.holder.bytes();
        o.opened(1, HDR + 1000);
        world.files.holdForceAfter("E-FORCE-").fail(new IOException("fixture: the force fails"));
        o.data(1, HDR, ReplWire.filled("E-FORCE-", 10));
        o.expect(ReplWire.RECEIPT, 1, ReplWire.WRITTEN, "the pwrite returned");
        o.expect(ReplWire.REFUSE, 1, ReplWire.FAILED, "the force failed on the force thread");
        JournalFixture.until(() -> world.holder.bytes() == before + HDR + 10, () -> "trued to the .part's size: " + world.holder.bytes());
        assertEquals(0, world.unforced(), "a failed replica has no force lag");
        assertTrue(Files.exists(world.part(B, 1)), "C2: the .part stays");
        o.opened(2, HDR + 100);   // the link is up
    }

    @Test
    void aSegmentRefusedFailedIsRefusedOnceThoughItsQueuedForceFailsToo() throws Exception {
        Owner o = world.dial(B);
        long before = world.holder.bytes();
        o.opened(1, HDR + 100);
        o.opened(2, HDR + 100);
        HeldFiles.Hold force1 = world.files.holdForceAfter("E-ONE-");
        o.data(1, HDR, ReplWire.filled("E-ONE-", 10));
        o.expect(ReplWire.RECEIPT, 1, ReplWire.WRITTEN, "segment 1's DATA");
        force1.awaitReached();   // the force thread is held: segment 2's force waits behind it
        o.written(2, HDR, ReplWire.filled("E-TWO-A-", 10));
        world.files.holdWrite("E-TWO-B-").fail(new IOException("fixture: segment 2's second pwrite fails"));
        o.data(2, HDR + 10, ReplWire.filled("E-TWO-B-", 10));
        o.expect(ReplWire.REFUSE, 2, ReplWire.FAILED, "segment 2's failed pwrite");
        JournalFixture.until(() -> world.holder.bytes() == before + (HDR + 100) + (HDR + 10),
                () -> "segment 2 detached, its file closed and its count trued: " + world.holder.bytes());
        long forcesOn2 = world.files.forces(world.part(B, 2));
        force1.release();   // segment 2's queued force now runs on its closed file and fails
        JournalFixture.until(() -> world.files.forcesOn(world.part(B, 2)).stream().anyMatch(f -> f.entered > 0 && f.returned >= 0 && !f.ok)
                && world.files.forces(world.part(B, 2)) > forcesOn2, () -> "segment 2's queued force ran and failed: " + world.files.describe());
        o.opened(3, HDR + 100);   // the next answer: no second REFUSE of segment 2
    }

    @Test
    void aDataQueuedBeforeItsSegmentFailedIsDropped() throws Exception {
        Owner o = world.dial(B);
        o.opened(1, HDR + 3000);
        HeldFiles.Hold first = world.files.holdWrite("E-Q1-");
        o.data(1, HDR, ReplWire.filled("E-Q1-", 1000));
        first.awaitReached();
        o.data(1, HDR + 1000, ReplWire.filled("E-Q2-", 1000));
        JournalFixture.until(o::allRead, () -> "node-a read the second DATA");
        for (int i = 0; i < 3; i++) world.onLoop();   // taken on arrival (its segment open then): queued behind the held write
        first.fail(new IOException("fixture: the held pwrite fails"));
        o.expect(ReplWire.REFUSE, 1, ReplWire.FAILED, "the failed pwrite");
        o.opened(2, HDR + 100);   // the next answer: the queued DATA got none
        assertNull(world.files.firstWrite("E-Q2-"), "the DATA queued before its segment failed is dropped, not written: " + world.files.describe());
    }

    @Test
    void aServerCloseStopsAWriterWaitingForForceRoom() throws Exception {
        Owner o = world.dial(B);
        long before = world.holder.bytes();
        int f = ReplWire.DATA_MAX;
        o.opened(1, HDR + 1000);
        o.opened(2, HDR + 3L * f);
        HeldFiles.Hold force1 = world.files.holdForceAfter("E-W1-");
        o.data(1, HDR, ReplWire.filled("E-W1-", 10));
        force1.awaitReached();   // the force thread holds: its snapshot is segment 1's first 10 bytes
        o.data(1, HDR + 10, ReplWire.filled("E-W1B-", 1 << 20));
        o.data(2, HDR, ReplWire.filled("E-W2A-", f));
        o.data(2, HDR + f, ReplWire.filled("E-W2B-", f));
        o.data(2, HDR + 2L * f, ReplWire.filled("E-W2C-", f));
        Thread writer = world.files.firstWrite("E-W1-").thread;
        JournalFixture.until(o::allRead, () -> "node-a read every DATA");
        for (int i = 0; i < 3; i++) world.onLoop();
        JournalFixture.untilParked(writer, "the writer, 9 MiB written and not forced");
        o.open(3, HDR + 100);   // a segment pending on the link while the writer waits: it has no replica yet
        JournalFixture.until(o::allRead, () -> "node-a read the OPEN");
        for (int i = 0; i < 3; i++) world.onLoop();
        JournalFixture.untilParked(writer, "the writer, still waiting");
        assertNull(world.files.firstWrite("E-W2C-"), "past HOLD_AT the writer waits: " + world.unforced());

        io.vertx.core.Future<Void> closing = world.server.close();   // stops the holder while its writer waits
        force1.release();   // the force thread ends its force, then stops: 9 MiB stay unforced
        closing.await(JournalFixture.BOUND.toSeconds(), java.util.concurrent.TimeUnit.SECONDS);
        assertNull(world.files.firstWrite("E-W2C-"), "a closed link's writer makes no write after its wait");
        assertEquals(before + (HDR + 10 + (1 << 20)) + (HDR + 2L * f), world.holder.bytes(), "every replica of the link detached, trued to its size");
    }

    /**
     * The DATA bytes of node-b's frame of exactly 1 MiB on the wire (r1: {@code queued} counts a frame's length): the packet's
     * framing v1, head 4 + 1 + 8 and tag 16, around the payload, its address 2 + 6 ("node-b") + 16 + 8 and its pos 8.
     */
    static final int MIB_FRAME_DATA = (1 << 20) - 13 - 16 - 32 - 8;

    /** A DATA of segment 1 at {@code pos} whose frame is exactly 1 MiB on the wire. */
    static void mibFrame(Owner o, long pos, String marker) throws IOException {
        byte[] bytes = ReplWire.filled(marker, MIB_FRAME_DATA);
        assertEquals((1 << 20) - 13 - 16, ReplWire.data("node-b", ReplicaWorld.JOURNAL_B, 1, pos, bytes).length, "fixture: a 1 MiB frame's payload");
        o.data(1, pos, bytes);
    }

    @Test
    void aPausedLinkResumesOnceItsQueuedBytesFallToResumeAt() throws Exception {
        Owner o = world.dial(B);
        int mib = 1 << 20;
        o.opened(1, HDR + 16L * mib);
        int d = MIB_FRAME_DATA;
        HeldFiles.Hold first = world.files.holdWrite("E-R0-");
        mibFrame(o, HDR, "E-R0-");
        first.awaitReached();   // taken, not done: 1 MiB queued (wire bytes)
        for (int i = 1; i <= 8; i++) mibFrame(o, HDR + (long) i * d, "E-R" + i + "-");
        JournalFixture.until(o::allRead, () -> "node-a read the nine DATA");
        for (int i = 0; i < 3; i++) world.onLoop();   // 9 MiB queued, above HOLD_AT: the socket is paused
        HeldFiles.Hold sixth = world.files.holdWrite("E-R5-");
        first.release();
        sixth.awaitReached();   // DATA 0..4 done: exactly 4 MiB queued, RESUME_AT, and the sixth DATA's write held
        o.data(9, HDR, ReplWire.filled("E-R9-", 10));   // a breach the event loop sees only on a resumed socket
        List<ReplWire.Answer> answers;
        try {
            answers = o.untilClosed();
        } finally {
            sixth.release();
        }
        assertEquals(1, answers.size(), "the first drain's WRITTEN, then the close: " + answers);
        assertTrue(answers.get(0).is(ReplWire.RECEIPT, 1, ReplWire.WRITTEN) && answers.get(0).end() == HDR + d, "witness: " + answers);
    }

    @Test
    void aLinkOfExactlyHoldAtQueuedBytesIsNotPaused() throws Exception {
        Owner o = world.dial(B);
        int mib = 1 << 20;
        o.opened(1, HDR + 16L * mib);
        int d = MIB_FRAME_DATA;
        HeldFiles.Hold first = world.files.holdWrite("E-P0-");
        mibFrame(o, HDR, "E-P0-");
        first.awaitReached();   // taken, not done: 1 MiB queued (wire bytes)
        for (int i = 1; i <= 7; i++) mibFrame(o, HDR + (long) i * d, "E-P" + i + "-");   // exactly 8 MiB queued: HOLD_AT
        JournalFixture.until(o::allRead, () -> "node-a read the eight DATA");
        for (int i = 0; i < 3; i++) world.onLoop();   // the breach comes in a read of its own: a pause lands between reads
        o.data(9, HDR, ReplWire.filled("E-P9-", 10));   // a breach the event loop sees only on a socket not paused
        List<ReplWire.Answer> answers;
        try {
            answers = o.untilClosed();
        } finally {
            first.release();
        }
        assertEquals(List.of(), answers, "closed with the first DATA's write held: nothing written, nothing receipted");
    }

    @Test
    void aFrameInTheReadThatPausedTheLinkIsTakenAndTheLinkResumesOnce() throws Exception {
        Owner o = world.dial(B);
        int mib = 1 << 20;
        o.opened(1, HDR + 16L * mib);
        int d = MIB_FRAME_DATA;
        HeldFiles.Hold first = world.files.holdWrite("E-S0-");
        mibFrame(o, HDR, "E-S0-");
        first.awaitReached();   // taken, not done: 1 MiB queued (wire bytes)
        for (int i = 1; i <= 7; i++) mibFrame(o, HDR + (long) i * d, "E-S" + i + "-");   // exactly 8 MiB queued: HOLD_AT, not paused
        JournalFixture.until(o::allRead, () -> "node-a read the eight DATA");
        for (int i = 0; i < 3; i++) world.onLoop();
        long at = HDR + 8L * d;
        byte[] both = ReplicaHolderTest.concat(
                o.raw.sealed(ReplWire.DATA, ReplWire.data("node-b", ReplicaWorld.JOURNAL_B, 1, at, ReplWire.filled("E-S8-", 10))),
                o.raw.sealed(ReplWire.DATA, ReplWire.data("node-b", ReplicaWorld.JOURNAL_B, 1, at + 10, ReplWire.filled("E-S9-", 10))));
        o.raw.write(both, 0, both.length);   // one read: its first DATA pauses the link, its second is queued on the paused one
        JournalFixture.until(o::allRead, () -> "node-a read the two small DATA");
        for (int i = 0; i < 3; i++) world.onLoop();
        first.release();
        long last = -1;
        while (last < at + 20) {
            ReplWire.Answer a = o.next();
            assertTrue(a.is(ReplWire.RECEIPT, 1, ReplWire.WRITTEN), "only RECEIPT WRITTEN of segment 1, was " + a);
            last = a.end();
        }
        assertEquals(at + 20, last, "every DATA written");
        o.opened(2, HDR + 100);   // the link reads again: paused once, resumed once
    }

    @Test
    void anIdleReplLinkOutlivesItsPeerTimeout() throws Exception {
        Owner o = world.dial(B);
        o.opened(1, HDR + 100);
        for (int i = 0; i < 3; i++) world.clock.advance(ReplicaWorld.CONFIG.timeout());   // the watchdog looks three times
        o.opened(2, HDR + 100);   // a holder waits for no answer: the link is up
    }

    @Test
    void aReopenWhoseForceFailsIsRefusedFailedAndGivesItsReservationBack() throws Exception {
        Owner first = world.dial(B);
        long before = world.holder.bytes();
        first.opened(2, HDR + 100);
        first.raw.close();
        JournalFixture.until(() -> world.holder.bytes() == before + HDR, () -> "the closed link's replica counts its header: " + world.holder.bytes());

        Owner second = world.dial(B);
        world.files.holdForceOn(world.part(B, 2), world.files.now()).fail(new IOException("fixture: the re-OPEN's force fails"));
        second.open(2, HDR + 1000, second.header(2));
        second.expect(ReplWire.REFUSE, 2, ReplWire.FAILED, "the re-OPEN's force failed");
        assertEquals(before + HDR, world.holder.bytes(), "the re-OPEN's reservation is given back: the .part counts its size");
        assertTrue(Files.exists(world.part(B, 2)), "the .part stays");
        assertFalse(Files.exists(world.seg(B, 2)));
    }
}
