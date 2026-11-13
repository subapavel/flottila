package io.flottila.runtime.journal;

import io.flottila.runtime.journal.ReplicaWorld.Owner;
import io.flottila.runtime.journal.peer.LoopbackQueues;
import io.flottila.runtime.journal.peer.ReplWire;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.flottila.runtime.journal.ReplicaWorld.HDR;
import static io.flottila.runtime.journal.ReplicaWorld.JOURNAL_B;
import static io.flottila.runtime.journal.ReplicaWorld.MIB;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-5b1 r1 (implementer): a superseded repl link ends for certain. Its socket is reset, not closed after a flush: node-a's
 * end of the old connection is released though the old owner read none of the receipts queued on it (a graceful close would
 * wait for that owner forever) and the old writer takes none of the DATA queued behind them; a quiet link is reset too (its
 * owner reads a reset). A superseded link whose socket is closed already (node-a closed it on a breach, its writer still held
 * in a write) is superseded without an error on node-a's event loop.
 */
@Timeout(300)
class ReplicaSupersedeTest {

    @TempDir Path tmp;
    ReplicaWorld world;

    @AfterEach
    void close() throws Exception {
        if (world != null) world.close();
    }

    @Test
    void aSupersededLinkIsResetSoItsSocketIsReleasedThoughItsOwnerReadNoneOfItsReceipts() throws Exception {
        world = new ReplicaWorld(tmp);
        Owner old = world.dial(ReplicaBackpressureTest.LONG, 64 * 1024);
        int hdr = old.headerLength();
        byte[] h = old.header(1);
        old.open(1, hdr + 64 * MIB, h);
        byte[] openedFrame = old.raw.next();
        assertTrue(ReplWire.answer(openedFrame).is(ReplWire.RECEIPT, 1, ReplWire.OPENED), "OPEN 1: " + ReplWire.answer(openedFrame));
        world.files.recordNothing();
        ReplicaBackpressureTest drive = new ReplicaBackpressureTest();
        drive.world = world;
        drive.untilPausedByItsReceipts(old, h, openedFrame.length);
        Optional<LoopbackQueues.Queues> before = nodeAEnd(old);
        assertTrue(before.isPresent() && before.get().tx() > 0, "fixture: node-a's end of the old connection holds receipts its owner has not read: " + before);
        long stalledAt = Files.size(world.part(old.id, 1));

        Owner next = world.dial(ReplicaWorld.owner(ReplicaBackpressureTest.LONG.node(), 2, JOURNAL_B));
        next.opened(2, hdr + 1024);
        JournalFixture.until(() -> nodeAHolds(old).isEmpty(), () -> "node-a's end of the superseded connection is released in no state (reset, its "
                + "unsent receipts dropped), its owner having read none of them: " + nodeAHolds(old));
        assertTrue(old.raw.closedByPeer(), "the old owner finds its connection closed");
        next.written(2, hdr, ReplWire.filled("SUP-NEXT-", 100));
        assertEquals(stalledAt, Files.size(world.part(old.id, 1)), "the old writer took none of its link's queued DATA once superseded");
    }

    @Test
    void aSupersededLinkIsResetThoughItHasNothingToSend() throws Exception {
        world = new ReplicaWorld(tmp);
        Owner old = world.dial(ReplicaWorld.owner("node-b", 1, JOURNAL_B));
        old.opened(1, HDR + 1024);
        Owner next = world.dial(ReplicaWorld.owner("node-b", 2, JOURNAL_B));
        next.opened(2, HDR + 1024);
        // a close would leave node-a's end in FIN_WAIT2 while the old owner keeps its own open
        JournalFixture.until(() -> nodeAHolds(old).isEmpty(), () -> "node-a's end of the superseded connection is gone at once: " + nodeAHolds(old));
        assertThrows(java.net.SocketException.class, old.raw::next, "the old owner reads a reset, not the end of its stream");
        next.written(2, HDR, ReplWire.filled("SUP-QUIET-", 100));
    }

    @Test
    void aLinkNodeAClosedAlreadyIsSupersededWithoutAnErrorOnItsEventLoop() throws Exception {
        world = new ReplicaWorld(tmp);
        List<Throwable> unhandled = new CopyOnWriteArrayList<>();
        world.vertx.exceptionHandler(unhandled::add);
        Owner old = world.dial(ReplicaWorld.owner("node-b", 1, JOURNAL_B));
        old.opened(1, HDR + 1024);
        HeldFiles.Hold held = world.files.holdWrite("SUP-OLD-");
        old.data(1, HDR, ReplWire.filled("SUP-OLD-", 512));
        held.awaitReached();
        old.data(1, HDR + 1, ReplWire.filled("SUP-BAD-", 1));   // not at the segment's end: node-a closes the link
        assertEquals(List.of(), old.untilClosed(), "node-a closed the old link (its owner reads its close), its held write unanswered");

        Owner next = world.dial(ReplicaWorld.owner("node-b", 1, JOURNAL_B));   // the old holder not stopped yet: superseded
        next.open(2, HDR + 1024);
        JournalFixture.until(next::allRead, () -> "node-a read the new link's OPEN");
        for (int i = 0; i < 3; i++) world.onLoop();
        held.release();
        next.expect(ReplWire.RECEIPT, 2, ReplWire.OPENED, "the new link's OPEN, once the old writer stopped");
        next.written(2, HDR, ReplWire.filled("SUP-NEXT-", 100));
        assertEquals(HDR + 100, Files.size(world.part(next.id, 2)), "the superseding link's DATA is in its .part");
        assertEquals(List.of(), unhandled, "superseding a closed link raised nothing on node-a's event loop");
    }

    /**
     * The kernel's states of node-a's end of {@code o}'s connection, in any state ({@code /proc/net/tcp} and {@code tcp6}, the
     * state column in hex): empty once it is gone — a reset at once, a close only once its unsent bytes were taken.
     */
    static List<String> nodeAHolds(Owner o) {
        List<String> states = new java.util.ArrayList<>();
        for (String file : List.of("/proc/net/tcp", "/proc/net/tcp6")) {
            Path p = Path.of(file);
            if (!Files.exists(p)) continue;
            try {
                for (String line : Files.readAllLines(p)) {
                    String[] f = line.trim().split("\\s+");
                    if (f.length < 5 || !f[1].contains(":")) continue;   // the header's address columns have no colon
                    if (port(f[1]) == o.raw.remotePort() && port(f[2]) == o.raw.localPort()) states.add(f[3] + " " + f[4]);
                }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException("fixture: " + file, e);
            }
        }
        return states;
    }

    private static int port(String hexAddress) {
        return Integer.parseInt(hexAddress.substring(hexAddress.lastIndexOf(':') + 1), 16);
    }

    /** Node-a's end of {@code o}'s connection, while it is established. */
    static Optional<LoopbackQueues.Queues> nodeAEnd(Owner o) {
        try {
            return LoopbackQueues.find(o.raw.remotePort(), o.raw.localPort());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("fixture: /proc/net/tcp", e);
        }
    }
}
