package io.flottila.runtime.journal;

import io.flottila.runtime.journal.ReplicaWorld.Owner;
import io.flottila.runtime.journal.peer.ReplWire;
import io.flottila.runtime.journal.peer.ReplWire.Answer;
import io.flottila.runtime.node.OnVirtualThread;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static io.flottila.runtime.journal.ReplicaWorld.B;
import static io.flottila.runtime.journal.ReplicaWorld.HDR;
import static io.flottila.runtime.journal.ReplicaWorld.MIB;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CORE-5b1 T11 (packet: the holder pauses its socket above HOLD_AT 8 MiB queued, resumes at 4 MiB): with the link's writer
 * held in its first DATA's write, an owner sends 64 MiB more in 4 MiB DATA frames; once node-a stopped reading — the owner's
 * sender parked in a socket write, node-a's socket holding bytes it has not read that stay unread, with nothing more sent,
 * across three tasks of node-a's one event loop — what node-a's process took off the socket beyond the held frame is at most
 * HOLD_AT plus one DATA (12 MiB) plus the one Netty read loop that may be in flight when the pause lands (1 MiB). It is
 * measured outside the holder: the bytes the sender wrote less its socket's unsent queue and node-a's unread one
 * ({@code /proc/net/tcp}); a byte node-a received but has not ACKed is in both queues and is subtracted twice, so a reading
 * may fall short of what node-a read. Hence both false-stall floors leave room for it: T11's is HOLD_AT less DATA 0's wire
 * length less 1 MiB, {@link #STALL_FLOOR} (1-byte frames) HOLD_AT less 128 KiB. Released, every frame is receipted in order,
 * the ends strictly increasing, and the {@code .part} is the bytes sent.
 */
@Timeout(300)
class ReplicaBackpressureTest {

    static final int FRAME = ReplWire.DATA_MAX;
    static final int FRAMES = 16;
    /** What node-a may hold of the link beyond its writer's held frame: HOLD_AT, one DATA, one read loop in flight. */
    static final long QUEUED_MAX = ReplWire.HOLD_AT + FRAME + MIB;
    /**
     * The least node-a can have read of 1-byte DATA frames beyond its held frame once it stopped reading: a holder pauses only
     * above HOLD_AT queued, less 128 KiB (the held frame counted in {@code queued}, one 64 KiB sender chunk in the queues not yet
     * counted, and the bytes node-a received but has not ACKed, in both the owner's send queue and node-a's receive queue and so
     * subtracted twice by {@link #readByHolder}). Below it the stall detector saw a false stall, and the upper bound would pass
     * vacuously.
     */
    static final long STALL_FLOOR = ReplWire.HOLD_AT - 128 * 1024;

    @TempDir Path tmp;
    ReplicaWorld world;

    @AfterEach
    void close() throws Exception {
        if (world != null) world.close();
    }

    static byte[] frameBytes(int i) {
        return ReplWire.filled(String.format("T11-D%02d-", i), FRAME);
    }

    @Test
    void aHeldWriterStopsTheHolderReadingPastHoldAtAndReleasedEveryFrameIsReceiptedInOrder() throws Exception {
        world = new ReplicaWorld(tmp);
        Owner o = world.dial(B);
        byte[] h = o.opened(1, HDR + (long) (FRAMES + 1) * FRAME);
        HeldFiles.Hold held = world.files.holdWrite("T11-D00-");
        byte[] d0 = o.raw.sealed(ReplWire.DATA, ReplWire.data("node-b", ReplicaWorld.JOURNAL_B, 1, HDR, frameBytes(0)));
        o.raw.write(d0, 0, d0.length);
        held.awaitReached();
        // The floor follows from the holder's count: the held DATA 0 stays in its queued bytes until its write returns, so a
        // holder pausing above HOLD_AT queued has read beyond DATA 0 more than HOLD_AT less DATA 0's full wire length; less
        // 1 MiB for the reading's error: one 64 KiB sender chunk still in the socket queues, not yet in written, and the bytes
        // node-a received but has not ACKed, in both the owner's send queue and node-a's receive queue and so subtracted twice
        // by readByHolder. Below it the stall was false and the upper bound passed vacuously.
        long floor = ReplWire.HOLD_AT - d0.length - MIB;

        AtomicLong written = new AtomicLong();
        java.util.concurrent.atomic.AtomicBoolean writing = new java.util.concurrent.atomic.AtomicBoolean();
        OnVirtualThread.Call<Void> sender = OnVirtualThread.start(() -> {
            for (int i = 1; i <= FRAMES; i++) {
                byte[] f = o.raw.sealed(ReplWire.DATA, ReplWire.data("node-b", ReplicaWorld.JOURNAL_B, 1, HDR + (long) i * FRAME, frameBytes(i)));
                for (int off = 0; off < f.length; off += 64 * 1024) {
                    int n = Math.min(64 * 1024, f.length - off);
                    writing.set(true);
                    o.raw.write(f, off, n);
                    writing.set(false);
                    written.addAndGet(n);
                }
            }
        });

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        long beyond;
        while (true) {
            beyond = readByHolder(o, written.get());
            assertTrue(beyond <= QUEUED_MAX, "node-a read " + (beyond >> 10) + " KiB of the link beyond its held frame, above "
                    + (QUEUED_MAX >> 10) + " KiB, while its writer is held");
            if (sender.isDone()) {
                OnVirtualThread.join(sender);   // the sender's exception or OOM surfaces at once, not at the deadline
                beyond = readByHolder(o, written.get());
                assertTrue(beyond <= QUEUED_MAX, "node-a read " + (beyond >> 10) + " KiB of the link beyond its held frame, above "
                        + (QUEUED_MAX >> 10) + " KiB, while its writer is held");
                fail("fixture: the sender wrote its last byte before node-a stopped reading (else nothing was bounded): queues "
                        + java.util.Arrays.toString(o.queues()));
            }
            if (writing.get() && sender.thread().getState() == Thread.State.WAITING && notReading(o, written)) break;
            assertTrue(System.nanoTime() - deadline < 0, "node-a never stopped reading with its writer held: written " + written.get()
                    + ", queues " + java.util.Arrays.toString(o.queues()) + ", sender " + sender.thread().getState());
            Thread.onSpinWait();
        }
        assertTrue(written.get() < (long) FRAMES * (FRAME + 64), "fixture: the sender stalled before its last byte (else nothing was bounded)");
        beyond = readByHolder(o, written.get());
        System.out.println("aHeldWriter: stalled, node-a read " + (beyond >> 10) + " KiB beyond its held frame, floor " + (floor >> 10)
                + " KiB, bound " + (QUEUED_MAX >> 10) + " KiB, queues " + java.util.Arrays.toString(o.queues()));
        assertTrue(beyond <= QUEUED_MAX, "stalled, node-a holds " + (beyond >> 10) + " KiB beyond its held frame: at most " + (QUEUED_MAX >> 10));
        assertTrue(beyond >= floor, "false stall: node-a read only " + (beyond >> 10) + " KiB beyond its held frame, below "
                + (floor >> 10) + " KiB (HOLD_AT less DATA 0's wire length less 1 MiB for a sender chunk not yet counted and the bytes"
                + " node-a received but has not ACKed, counted in both queues), where a holder pausing above HOLD_AT queued cannot have paused");

        held.release();
        long last = -1;
        long end = HDR + (long) (FRAMES + 1) * FRAME;
        while (last < end) {
            Answer a = o.next();
            assertTrue(a.is(ReplWire.RECEIPT, 1, ReplWire.WRITTEN), "only RECEIPT WRITTEN of segment 1, was " + a);
            assertTrue(a.end() > last, "receipt ends strictly increase: " + a.end() + " after " + last);
            assertEquals(0, (a.end() - HDR) % FRAME, "a receipt ends at a frame's end: " + a.end());
            last = a.end();
        }
        assertEquals(end, last);
        OnVirtualThread.join(sender);

        MessageDigest sent = MessageDigest.getInstance("SHA-256");
        sent.update(h);
        for (int i = 0; i <= FRAMES; i++) sent.update(frameBytes(i));
        MessageDigest onDisk = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(world.part(B, 1))) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) onDisk.update(buf, 0, n);
        }
        assertArrayEquals(sent.digest(), onDisk.digest(), "the .part is the header and every frame's bytes, in order");
    }

    // ------------------------------------------------------------------------------- r1: queued counts frame bytes

    /**
     * CORE-5b1 r1 (queued counts a frame's length, not its payload): with the writer held in its first DATA's write, an owner
     * sends 3 × HOLD_AT of 1-byte DATA frames (70 bytes each on the wire; 2 × HOLD_AT fit node-a's reads and the two kernel
     * queues, ~6 MiB observed, and the sender finished). node-a stops reading with what it took off the socket
     * beyond the held frame at most HOLD_AT + {@link #PAUSE_SLACK} (4 MiB), in wire bytes; counted by payload (1 byte
     * a frame) it would read all of them (24 MiB, the bound 12 MiB). Released, every frame is receipted in order and the
     * .part holds every byte.
     *
     * <p>{@link #PAUSE_SLACK}: what node-a may take off the socket past HOLD_AT, in frame bytes, is empirical headroom over
     * what Vert.x 5.1 was observed to deliver after {@code pause()}, not derived from it: up to ~2.1 MiB observed (9611–10350
     * KiB beyond the held frame against HOLD_AT's 8192, agent/work/CORE-5b1-r1-flake.log; the holder paused at queued =
     * 8388653 in a debug run). 4 MiB is about twice that, and 12 MiB below what a holder counting payload reads (all
     * 24 MiB). Every run prints what it read.
     */
    static final long PAUSE_SLACK = 4 * MIB;

    @Test
    void oneByteDataFramesPauseTheSocketWithinHoldAtCountedInFrameBytes() throws Exception {
        world = new ReplicaWorld(tmp);
        Owner o = world.dial(B);
        byte[] first = ReplWire.filled("FLEN-D00-", 64);
        int wire = o.raw.sealed(ReplWire.DATA, ReplWire.data("node-b", ReplicaWorld.JOURNAL_B, 1, 0, new byte[1])).length;
        o.raw.close();   // that seal moved its session on: a fresh link for the case
        o = world.dial(B);
        final Owner owner = o;
        long frames = 3 * ReplWire.HOLD_AT / wire;
        long end = HDR + first.length + frames;
        o.opened(1, end);
        HeldFiles.Hold held = world.files.holdWrite("FLEN-D00-");
        o.data(1, HDR, first);
        held.awaitReached();
        world.files.recordNothing();   // 10^5 pwrites follow

        long bound = ReplWire.HOLD_AT + PAUSE_SLACK;
        AtomicLong written = new AtomicLong();
        java.util.concurrent.atomic.AtomicBoolean writing = new java.util.concurrent.atomic.AtomicBoolean();
        OnVirtualThread.Call<Void> sender = OnVirtualThread.start(() -> {
            ByteArrayOutputStream chunk = new ByteArrayOutputStream(64 * 1024 + 128);
            long pos = HDR + first.length;
            for (long i = 0; i < frames; i++) {
                chunk.writeBytes(owner.raw.sealed(ReplWire.DATA, ReplWire.data("node-b", ReplicaWorld.JOURNAL_B, 1, pos + i, new byte[] {(byte) i})));
                if (chunk.size() >= 64 * 1024 || i == frames - 1) {
                    byte[] c = chunk.toByteArray();
                    chunk.reset();
                    writing.set(true);
                    owner.raw.write(c, 0, c.length);
                    writing.set(false);
                    written.addAndGet(c.length);
                }
            }
        });

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        long beyond;
        while (true) {
            beyond = readByHolder(o, written.get());
            assertTrue(beyond <= bound, "node-a read " + (beyond >> 10) + " KiB of 1-byte DATA frames beyond its held frame, above "
                    + (bound >> 10) + " KiB (HOLD_AT + 4 MiB, in frame bytes), while its writer is held");
            if (sender.isDone()) {
                OnVirtualThread.join(sender);   // the sender's exception or OOM surfaces at once, not at the deadline
                beyond = readByHolder(o, written.get());
                assertTrue(beyond <= bound, "node-a read " + (beyond >> 10) + " KiB of 1-byte DATA frames beyond its held frame, above "
                        + (bound >> 10) + " KiB (HOLD_AT + 4 MiB, in frame bytes), while its writer is held");
                fail("fixture: the sender wrote its last byte before node-a stopped reading (else nothing was bounded): queues "
                        + java.util.Arrays.toString(o.queues()));
            }
            if (writing.get() && sender.thread().getState() == Thread.State.WAITING && notReading(o, written)) break;
            assertTrue(System.nanoTime() - deadline < 0, "node-a never stopped reading with its writer held: written " + written.get()
                    + ", queues " + java.util.Arrays.toString(o.queues()) + ", sender " + sender.thread().getState());
            Thread.onSpinWait();
        }
        assertTrue(written.get() < frames * wire, "fixture: the sender stalled before its last byte (else nothing was bounded)");
        beyond = readByHolder(o, written.get());
        System.out.println("oneByteDataFrames: stalled, node-a read " + (beyond >> 10) + " KiB of 1-byte DATA frames beyond its held frame, bound "
                + (bound >> 10) + " KiB (HOLD_AT " + (ReplWire.HOLD_AT >> 10) + " KiB), queues " + java.util.Arrays.toString(o.queues()));
        assertTrue(beyond <= bound, "stalled, node-a holds " + (beyond >> 10) + " KiB beyond its held frame: at most " + (bound >> 10));
        assertTrue(beyond >= STALL_FLOOR, "false stall: node-a read only " + (beyond >> 10) + " KiB of 1-byte DATA frames beyond its held frame, below "
                + (STALL_FLOOR >> 10) + " KiB (HOLD_AT less 128 KiB for the held frame, a sender chunk not yet counted and the bytes"
                + " node-a received but has not ACKed, counted in both queues), where a holder pausing above HOLD_AT queued cannot have paused");

        held.release();
        o.untilWritten(1, end);
        OnVirtualThread.join(sender);
        assertEquals(end, Files.size(world.part(B, 1)), "the .part holds the header and every byte sent");
    }

    // ----------------------------------------------------------------------- r1 R3: an owner that never reads

    /** Paced DATA (each sent once the one before it is in the .part): one drain, one RECEIPT WRITTEN, per DATA. */
    static final int SMALL = 16;
    /** DATA sent once node-a read the one before it but has not written it (its writer stopped): HOLD_AT is reached in 256. */
    static final int BIG = 32 * 1024;

    /**
     * CORE-5b1 r1 R3 (RECEIPT and REFUSE no longer fire-and-forget): an owner sends DATA and reads nothing. node-a's receipts
     * fill the two sockets' queues (node-a's send queue, the owner's receive queue); past them node-a holds at most a few
     * receipts (its writer waits for its last send before it takes more), so its writer stops, its queue of frames reaches
     * HOLD_AT and its socket pauses. Measured outside the holder: the receipts node-a made (at most one per DATA in the .part,
     * DATA paced one at a time) less the bytes in the two kernel queues ({@code /proc/net/tcp}); and, paused, the DATA bytes
     * node-a took off the socket and did not write (wire bytes) are at most HOLD_AT + one DATA + one read loop. Then the owner
     * reads: every RECEIPT WRITTEN in order, ends strictly increasing at frame ends, up to every byte sent, which the .part holds.
     */
    @Test
    void anOwnerThatNeverReadsStopsTheHolderWithItsReceiptsBoundedByTheSocketQueues() throws Exception {
        world = new ReplicaWorld(tmp);
        // a 63-byte owner: 127-byte receipts; its receive buffer fixed at 64 KiB (the kernel doubles it): an owner's receive
        // queue autotuned grows to tens of MiB, and the sockets' queues then hold 10^5 receipts and more
        Owner o = world.dial(LONG, 64 * 1024);
        int hdr = o.headerLength();
        byte[] h = o.header(1);
        o.open(1, hdr + 64 * MIB, h);
        byte[] openedFrame = o.raw.next();
        Answer opened = ReplWire.answer(openedFrame);
        assertTrue(opened.is(ReplWire.RECEIPT, 1, ReplWire.OPENED), "OPEN 1: " + opened);
        int receipt = openedFrame.length;   // a RECEIPT's bytes on the wire: WRITTEN's are OPENED's (same address, end, state)
        world.files.recordNothing();   // 10^4 to 10^5 DATA follow
        Path part = world.part(LONG, 1);
        Stalled stalled = untilPausedByItsReceipts(o, h, receipt);
        MessageDigest sent = stalled.sent();
        List<long[]> frames = stalled.frames();
        Set<Long> ends = stalled.ends();
        long end = stalled.end();
        long wire = stalled.wire();
        long batched = stalled.batched();

        long size = Files.size(part);
        assertTrue(size < end, "fixture: node-a stopped with DATA it did not write");
        assertReceiptsBounded(o, frames, size, batched, receipt);
        long[] q = o.queues();
        long readNotWritten = wire - q[0] - q[1] - wireUpTo(frames, size);
        long bound = ReplWire.HOLD_AT + BIG + 1024 + MIB;   // one DATA with its frame bytes
        assertTrue(readNotWritten <= bound, "paused, node-a holds " + (readNotWritten >> 10) + " KiB of DATA it took off the socket and did "
                + "not write: at most " + (bound >> 10) + " KiB (HOLD_AT, one DATA, one read loop)");

        long last = -1;
        while (last < end) {
            Answer a = o.next();
            assertTrue(a.is(ReplWire.RECEIPT, 1, ReplWire.WRITTEN), "only RECEIPT WRITTEN of segment 1, was " + a);
            assertTrue(a.end() > last, "receipt ends strictly increase: " + a.end() + " after " + last);
            assertTrue(ends.contains(a.end()), "a receipt ends at a DATA's end: " + a.end());
            last = a.end();
        }
        assertEquals(end, last, "the last receipt covers every byte sent");
        MessageDigest onDisk = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(part)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) onDisk.update(buf, 0, n);
        }
        assertArrayEquals(sent.digest(), onDisk.digest(), "the .part is the header and every DATA's bytes, in order");
    }

    /**
     * CORE-5b1 r1 (a superseded link whose owner never reads): the owner's link of epoch 1 is driven as in R3 until node-a's
     * socket paused, its writer stopped behind receipts its owner never reads (node-a's send queue to it not empty); then the
     * same owner dials at epoch 2. The new link's OPEN is receipted OPENED within RawPeer's bound and its DATA written: the
     * old writer, stopped on its unsent receipts, does not hold the supersede forever (its link closed under it).
     */
    @Test
    void aSupersedingLinkIsServedThoughTheOldLinksOwnerNeverReadsItsReceipts() throws Exception {
        world = new ReplicaWorld(tmp);
        Owner old = world.dial(LONG, 64 * 1024);
        int hdr = old.headerLength();
        byte[] h = old.header(1);
        old.open(1, hdr + 64 * MIB, h);
        byte[] openedFrame = old.raw.next();
        assertTrue(ReplWire.answer(openedFrame).is(ReplWire.RECEIPT, 1, ReplWire.OPENED), "OPEN 1: " + ReplWire.answer(openedFrame));
        world.files.recordNothing();   // 10^4 to 10^5 DATA follow
        Stalled stalled = untilPausedByItsReceipts(old, h, openedFrame.length);
        assertTrue(Files.size(world.part(LONG, 1)) < stalled.end(), "fixture: the old link stopped with DATA it did not write");
        long[] r = receiptQueues(old, stalled.frames(), Files.size(world.part(LONG, 1)), openedFrame.length);
        assertTrue(r[1] > 0 && r[2] > 0, "fixture: the old link's receipts back up in both queues: " + receipts(r, stalled.batched()));

        Owner next = world.dial(ReplicaWorld.owner(LONG.node(), 2, ReplicaWorld.JOURNAL_B));
        try {
            next.opened(2, hdr + 1024);
        } catch (java.net.SocketTimeoutException stuck) {
            throw new AssertionError("the superseding link's OPEN was not receipted within the bound: the old writer, stopped on its "
                    + "owner's unread receipts, holds the supersede (" + receipts(r, stalled.batched()) + "; the old connection now: "
                    + io.flottila.runtime.journal.peer.LoopbackQueues.find(old.raw.remotePort(), old.raw.localPort()) + ")", stuck);
        }
        next.written(2, hdr, ReplWire.filled("R3-NEXT-", 100));
        assertEquals(hdr + 100, Files.size(world.part(next.id, 2)), "the superseding link's DATA is in its .part");
    }

    /** An owner whose node id is the longest DNS label, 63 bytes. */
    static final io.flottila.runtime.journal.peer.PeerIdentity LONG = ReplicaWorld.owner("node-" + "r".repeat(58), 1, ReplicaWorld.JOURNAL_B);

    /** Where {@link #untilPausedByItsReceipts} left a link: what was sent of segment 1, per DATA its end and the wire up to it. */
    record Stalled(MessageDigest sent, List<long[]> frames, Set<Long> ends, long end, long wire, long batched) { }

    /**
     * The R3 drive: segment 1 open (its header {@code h}), {@code o} sends paced DATA and reads nothing until node-a's socket
     * is paused (bytes that reached it stay unread across three loop tasks, the .part unchanged), its receipts bounded on the way.
     */
    Stalled untilPausedByItsReceipts(Owner o, byte[] h, int receipt) throws Exception {
        MessageDigest sent = MessageDigest.getInstance("SHA-256");
        sent.update(h);
        Path part = world.part(o.id, 1);
        List<long[]> frames = new ArrayList<>();   // per DATA sent: its end, the wire bytes of every DATA up to it
        Set<Long> ends = new HashSet<>();
        long end = h.length;
        long wire = 0;
        long batched = 0;   // DATA sent before the one before it was in the .part: it may share that one's receipt
        boolean paced = true;
        boolean paused = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        for (int i = 0; !paused; i++) {
            byte[] d = ReplWire.filled(String.format("R3-%06d-", i), paced ? SMALL : BIG);
            if (!paced) batched++;
            byte[] f = o.raw.sealed(ReplWire.DATA, ReplWire.data(o.id.node(), o.id.journal(), 1, end, d));
            o.raw.write(f, 0, f.length);
            long at = System.nanoTime();
            sent.update(d);
            end += d.length;
            wire += f.length;
            frames.add(new long[] {end, wire});
            ends.add(end);
            while (true) {
                long size = Files.size(part);
                if (size >= end) {
                    paced = true;
                    if (i % 1024 == 0) assertReceiptsBounded(o, frames, size, batched, receipt);
                    break;
                }
                // a pacing heuristic, never asserted: a DATA not in the .part 2 ms after it went is looked at from outside
                if (System.nanoTime() - at < TimeUnit.MILLISECONDS.toNanos(2)) {
                    Thread.onSpinWait();
                    continue;
                }
                at = System.nanoTime();
                assertTrue(at - deadline < 0, "node-a neither wrote DATA " + i + " nor stopped reading in 120 s: .part " + size + " of "
                        + end + ", queues " + java.util.Arrays.toString(o.queues()) + ", " + receipts(o, frames, size, batched, receipt));
                assertReceiptsBounded(o, frames, size, batched, receipt);
                long[] q = o.queues();
                for (int k = 0; k < 3; k++) world.onLoop();
                if (Files.size(part) >= end) {
                    paced = true;
                    break;
                }
                if (q[0] == 0 && q[1] == 0) {   // node-a read DATA i and has not written it: its writer stopped, or is slow
                    paced = false;
                    break;
                }
                // bytes that reached node-a's socket stay unread while its loop runs: paused (an owner's byte not yet acked, q[0],
                // may have reached node-a: it is no witness)
                if (q[1] > 0 && java.util.Arrays.equals(q, o.queues()) && Files.size(part) == size) {
                    paused = true;
                    break;
                }
            }
        }

        return new Stalled(sent, frames, ends, end, wire, batched);
    }

    /**
     * node-a's receipts beyond the kernel's queues are at most 4 (+1 per DATA that may share a receipt): the receipts it made,
     * at most one per DATA in the .part ({@code size} read first), less node-a's send queue and the owner's receive queue (read
     * after, in that order: a byte moving between them is counted twice, never missed).
     */
    static void assertReceiptsBounded(Owner o, List<long[]> frames, long size, long batched, int receipt) throws Exception {
        long[] r = receiptQueues(o, frames, size, receipt);
        long held = r[0] * receipt - r[1] - r[2];
        long allowed = (4 + batched) * receipt;
        assertTrue(held <= allowed, "node-a holds " + held + " bytes of receipts its owner has not read beyond the two sockets' queues ("
                + receipts(r, batched) + "): at most " + allowed + " (its outbound must stay bounded)");
    }

    /** [receipts made (DATA in the .part), node-a's send queue, the owner's receive queue]. */
    static long[] receiptQueues(Owner o, List<long[]> frames, long size, int receipt) throws Exception {
        long made = 0;
        for (long[] f : frames) if (f[0] <= size) made++;
        long theirTx = io.flottila.runtime.journal.peer.LoopbackQueues.find(o.raw.remotePort(), o.raw.localPort())
                .map(io.flottila.runtime.journal.peer.LoopbackQueues.Queues::tx).orElse(0L);
        long myRx = io.flottila.runtime.journal.peer.LoopbackQueues.find(o.raw.localPort(), o.raw.remotePort())
                .map(io.flottila.runtime.journal.peer.LoopbackQueues.Queues::rx).orElse(0L);
        return new long[] {made, theirTx, myRx};
    }

    static String receipts(Owner o, List<long[]> frames, long size, long batched, int receipt) throws Exception {
        return receipts(receiptQueues(o, frames, size, receipt), batched);
    }

    static String receipts(long[] r, long batched) {
        return r[0] + " receipts made, " + batched + " DATA batched, " + r[1] + " bytes in node-a's send queue, " + r[2]
                + " in the owner's receive queue";
    }

    /** The wire bytes of the DATA whose end is at most {@code size} (those in the .part). */
    static long wireUpTo(List<long[]> frames, long size) {
        long w = 0;
        for (long[] f : frames) if (f[0] <= size) w = f[1];
        return w;
    }

    /**
     * The bytes of the counted frames (DATA 1..16) node-a's process took off the owner's socket: those written less the owner's
     * unsent and node-a's unread queue. DATA 0, the HELLO, the proofs and the OPEN were read before (DATA 0 is in the held write).
     */
    static long readByHolder(Owner o, long counted) throws Exception {
        long[] q = o.queues();
        return counted - q[0] - q[1];
    }

    /** node-a does not read: its socket holds unread bytes that stay unread, with nothing more sent, across three loop tasks. */
    boolean notReading(Owner o, AtomicLong written) throws Exception {
        long before = written.get();
        long[] q = o.queues();
        if (q[1] == 0) return false;
        for (int i = 0; i < 3; i++) world.onLoop();
        long[] after = o.queues();
        return after[1] == q[1] && written.get() == before;
    }
}
