package io.flottila.runtime.journal;

import io.flottila.runtime.journal.ReplicaWorld.Owner;
import io.flottila.runtime.journal.peer.PeerIdentity;
import io.flottila.runtime.journal.peer.ReplWire;
import io.flottila.runtime.journal.peer.ReplWire.Answer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.flottila.runtime.journal.ReplicaWorld.HDR;
import static io.flottila.runtime.journal.ReplicaWorld.JOURNAL_B;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-5b1 T3 (packet A8 epoch rule as amended by C6): after the proof, a repl link is closed when its HELLO's epoch is below
 * max(the highest epoch accepted from that owner, epochOf(owner)); else it supersedes the owner's existing repl link — the old
 * one closes (its owner reads EOF), and the new link's frames are taken only once the old link's writer stopped. The bound is
 * checked again at every OPEN: an epochOf raised after the proof closes the link at its next OPEN. A closed link's old peer
 * still receipts. The event order of "taken only after the old writer stopped" is HeldFiles' one sequence: the new link's
 * first write on its replica enters after the old writer's held write returned, though the holder had read the new OPEN, its
 * loop had run it and the old writer sat parked in the hold before the release.
 */
@Timeout(120)
class PeerReplEpochTest {

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

    @ParameterizedTest(name = "epoch {0} after epoch 2")
    @ValueSource(longs = {2, 3})
    void aSameOrHigherEpochSupersedesTheOldLinkWhoseWriterStopsFirst(long epoch) throws Exception {
        Owner old = world.dial(ReplicaWorld.owner("node-b", 2, JOURNAL_B));
        old.opened(1, HDR + 1024);
        HeldFiles.Hold held = world.files.holdWrite("T3-OLD-");
        old.data(1, HDR, ReplWire.filled("T3-OLD-", 512));
        held.awaitReached();

        Owner next = world.dial(ReplicaWorld.owner("node-b", epoch, JOURNAL_B));
        assertEquals(List.of(), old.untilClosed(), "the old link closes (its owner reads EOF), its held write unanswered");
        next.open(2, HDR + 1024);
        JournalFixture.until(next::allRead, () -> "node-a read the new link's OPEN");
        for (int i = 0; i < 3; i++) world.onLoop();
        JournalFixture.untilParked(held.thread(), "the old writer in its held write");
        assertEquals(List.of(), world.opsOn(world.part(next.id, 2)),
                "nothing of the new link is written while the old writer is held: " + world.files.describe());

        held.release();
        next.expect(ReplWire.RECEIPT, 2, ReplWire.OPENED, "the new link's OPEN, once the old writer stopped");
        HeldFiles.Op first = world.opsOn(world.part(next.id, 2)).stream().findFirst()
                .orElseThrow(() -> new AssertionError("no write on 2.part: " + world.files.describe()));
        assertTrue(first.entered > held.op().returned, "the new link's first write (" + first + ") enters after the old writer's held write returned ("
                + held.op() + ")");
    }

    @Test
    void aLowerEpochIsClosedWhileTheOldLinkStillReceipts() throws Exception {
        Owner current = world.dial(ReplicaWorld.owner("node-b", 2, JOURNAL_B));
        current.opened(1, HDR + 1024);
        assertTrue(closed(ReplicaWorld.owner("node-b", 1, JOURNAL_B)), "a link of epoch 1 after one of epoch 2 is closed");
        current.written(1, HDR, ReplWire.filled("T3-LOW-", 100));
        assertFalse(Files.exists(world.part(current.id, 9)), "the closed link opened nothing");
    }

    /**
     * CORE-5b1 r1 R5: the bound is the HIGHEST epoch accepted, not the first: epoch 2 accepted, then 3 (superseding it), a
     * link of epoch 2 is closed while the link of epoch 3 still receipts.
     */
    @Test
    void aLinkBelowTheHighestAcceptedEpochIsClosedAfterTwoAccepts() throws Exception {
        Owner two = world.dial(ReplicaWorld.owner("node-b", 2, JOURNAL_B));
        two.opened(1, HDR + 1024);
        Owner three = world.dial(ReplicaWorld.owner("node-b", 3, JOURNAL_B));
        assertEquals(List.of(), two.untilClosed(), "epoch 3 supersedes epoch 2: the old link closes");
        three.opened(2, HDR + 1024);
        assertTrue(closed(ReplicaWorld.owner("node-b", 2, JOURNAL_B)), "a link of epoch 2 after epochs 2 and 3 were accepted is closed");
        three.written(2, HDR, ReplWire.filled("R5-THREE-", 100));   // the link of epoch 3 was not superseded
        assertFalse(Files.exists(world.part(three.id, 9)), "the closed link opened nothing");
    }

    @Test
    void anEpochBelowEpochOfIsClosedAndOneAtItIsServed() throws Exception {
        world.epochs.put("node-b", 3L);
        assertTrue(closed(ReplicaWorld.owner("node-b", 2, JOURNAL_B)), "a link of epoch 2 while the renew answers epoch 3 for node-b is closed");
        Owner at = world.dial(ReplicaWorld.owner("node-b", 3, JOURNAL_B));
        at.opened(1, HDR + 1024);
        assertTrue(closed(ReplicaWorld.owner("node-b", 2, JOURNAL_B)), "and again while a link of epoch 3 is up");
        at.written(1, HDR, ReplWire.filled("T3-AT-", 100));
    }

    @Test
    void theBoundIsCheckedAgainAtEveryOpen() throws Exception {
        Owner b = world.dial(ReplicaWorld.owner("node-b", 1, JOURNAL_B));
        b.opened(1, HDR + 1024);
        world.epochs.put("node-b", 2L);   // the renew answered a later incarnation of node-b after this link's proof
        b.data(1, HDR, ReplWire.filled("T3-RE-", 100));   // a DATA is no OPEN: not checked
        b.untilWritten(1, HDR + 100);
        b.open(2, HDR + 1024);
        assertEquals(List.of(), b.untilClosed(), "the next OPEN closes the link, unanswered");
        assertFalse(Files.exists(world.part(b.id, 2)), "segment 2 is not opened");
    }

    /**
     * Whether a repl link of {@code who} is closed by node-a — inside its handshake or at its first OPEN — unanswered: true on
     * the close; an answer fails the case, and so does a link left open past the bound (RawPeer#framesUntilClosed).
     */
    boolean closed(PeerIdentity who) throws IOException {
        try {
            Owner o = world.dial(who);
            o.open(9, HDR + 1024);
            List<Answer> answers = o.untilClosed();
            assertEquals(List.of(), answers, "a closed link answers nothing");
            return true;
        } catch (java.io.EOFException | java.net.SocketException gone) {
            return true;   // closed inside the handshake, or before the OPEN went out
        }
    }
}
