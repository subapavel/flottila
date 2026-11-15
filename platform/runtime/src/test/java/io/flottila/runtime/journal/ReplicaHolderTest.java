package io.flottila.runtime.journal;

import io.flottila.runtime.journal.ReplicaWorld.Owner;
import io.flottila.runtime.journal.peer.ReplWire;
import io.flottila.runtime.journal.peer.ReplWire.Answer;
import io.flottila.runtime.node.OnVirtualThread;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;

import static io.flottila.runtime.journal.ReplicaWorld.B;
import static io.flottila.runtime.journal.ReplicaWorld.C;
import static io.flottila.runtime.journal.ReplicaWorld.CREATED;
import static io.flottila.runtime.journal.ReplicaWorld.HDR;
import static io.flottila.runtime.journal.ReplicaWorld.JOURNAL_B;
import static io.flottila.runtime.journal.ReplicaWorld.JOURNAL_C;
import static io.flottila.runtime.journal.ReplicaWorld.MIB;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-5b1 T4–T10c: node-a holding node-b's (and node-c's) segments over a repl link (packet "Holder disk", "Link closes",
 * "REFUSE" as amended by C2, C4, C5, C7, C9 and the SEAL crc32c). Every write and force of a replica goes through
 * {@link HeldFiles} on one sequence, so an order is asserted on it; where a frame must NOT come yet, the witness is a frame
 * that must come — the first answer after a failed force is REFUSE FAILED (no OPENED before it), and a WRITTEN of another
 * segment, sent by the link's writer while the seal's force or directory force is held on the link's other thread, arrives
 * with no SEALED before it. The rename is no {@link JournalFiles} call: it is pinned between the held force (the {@code .part}
 * still there, no {@code .seg}) and the held directory force (the {@code .seg} there, no {@code .part}), and by a rename that
 * fails (REFUSE FAILED, never SEALED). The fake timer is never advanced. See {@link ReplicaWorld} for the API assumed.
 */
@Timeout(120)
class ReplicaHolderTest {

    @TempDir Path tmp;
    ReplicaWorld world;

    ReplicaWorld world() throws Exception {
        return world(s -> { });
    }

    ReplicaWorld world(Consumer<JournalFixture.Settings> tweak) throws Exception {
        world = new ReplicaWorld(tmp, tweak);
        return world;
    }

    @AfterEach
    void close() throws Exception {
        if (world != null) world.close();
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] c = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }

    static HeldFiles.Op first(List<HeldFiles.Op> ops, HeldFiles.Kind kind, long after, String what) {
        return ops.stream().filter(op -> op.kind == kind && op.entered > after).findFirst()
                .orElseThrow(() -> new AssertionError("no " + kind + " " + what + " after #" + after));
    }

    // ------------------------------------------------------------------------------------------------------------ T4 open

    @Test
    void openWritesTheHeaderAndReceiptsOpenedAfterTheFileForceThenTheDirectoryForce() throws Exception {
        world();
        Owner o = world.dial(B);
        byte[] h = o.opened(1, HDR + 4096);
        Path part = world.part(B, 1);
        Path journalDir = world.replicaDir("node-b", JOURNAL_B);
        assertArrayEquals(h, Files.readAllBytes(part), "the .part is the header the OPEN carried");
        assertFalse(Files.exists(world.seg(B, 1)), "no .seg");

        HeldFiles.Op write = first(world.opsOn(part), HeldFiles.Kind.WRITE, 0, "on the .part");
        assertArrayEquals(h, write.data, "the header is the .part's first write");
        HeldFiles.Op force = first(world.opsOn(part), HeldFiles.Kind.FORCE, write.returned, "on the .part after its header write returned");
        HeldFiles.Op dirForce = first(world.opsOn(journalDir), HeldFiles.Kind.FORCE, force.returned, "on " + journalDir + " after the .part's force returned");
        assertTrue(write.returnedOk() && force.returnedOk() && dirForce.returnedOk(), "recorded order: header, file force, directory force: "
                + world.files.describe());
        for (Path created : List.of(world.dir.resolve("replicas"), world.dir.resolve("replicas").resolve("node-b"))) {
            assertTrue(world.opsOn(created).stream().anyMatch(op -> op.kind == HeldFiles.Kind.FORCE),
                    "directories created durably: " + created + " is forced once its new entry is made");
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"file force", "directory force"})
    void anOpenWhoseForceFailsIsRefusedFailedAndNeverReceiptedOpened(String which) throws Exception {
        world();
        Owner o = world.dial(B);
        o.opened(1, HDR + 4096);   // the directories exist now: segment 2's OPEN forces its .part, then the journal's directory
        long before = world.holder.bytes();
        long mark = world.files.now();
        Path target = which.equals("file force") ? world.part(B, 2) : world.replicaDir("node-b", JOURNAL_B);
        world.files.holdForceOn(target, mark).fail(new IOException("fixture: the " + which + " fails"));
        o.open(2, HDR + 4096);
        o.expect(ReplWire.REFUSE, 2, ReplWire.FAILED, "the " + which + " failed: the first answer is REFUSE FAILED, no OPENED before it");
        assertTrue(Files.exists(world.part(B, 2)), "C2: FAILED keeps the .part");
        assertEquals(HDR, Files.size(world.part(B, 2)), "fixture: the header was written before the " + which + " failed");
        assertFalse(world.holder.isFailed(), "a replica's failure is no fail-stop of the holder's journal");
        o.opened(3, HDR + 4096);   // the link is up; its writer took OPEN 3 after OPEN 2's create returned
        assertEquals(before + HDR + (HDR + 4096), world.holder.bytes(),
                "r1 R6: the failed create counts its .part at its size on disk (" + HDR + "), not its reservation; OPEN 3 its length");
    }

    /** CORE-5b1 r1 R2: a create whose header write tears after 20 of its 49 bytes counts the .part at its 20 bytes on disk. */
    @Test
    void anOpenWhoseHeaderWriteTearsIsRefusedFailedAndCountsThePartAtItsSizeOnDisk() throws Exception {
        world();
        Owner o = world.dial(B);
        o.opened(1, HDR + 4096);   // the directories exist: segment 2's first write is its header
        long before = world.holder.bytes();
        Path part2 = world.part(B, 2).toAbsolutePath().normalize();
        world.files.holdFirstWriteOn("2.part", part2::equals).tear(20, new IOException("fixture: the header write tears after 20 bytes"));
        o.open(2, HDR + 4096);
        o.expect(ReplWire.REFUSE, 2, ReplWire.FAILED, "the torn header write: REFUSE FAILED, no OPENED before it");
        assertEquals(20, Files.size(part2), "fixture: the torn write left 20 bytes of the header in the .part");
        o.opened(3, HDR + 4096);   // the link's writer took OPEN 3 after OPEN 2's create returned
        assertEquals(before + 20 + (HDR + 4096), world.holder.bytes(),
                "the failed create counts its .part at its size on disk (20), not 0 nor the header; OPEN 3 its length");
        assertEquals(Files.size(part2), world.holder.bytes() - before - (HDR + 4096), "bytes() is trued to the .part's size");
    }

    // ------------------------------------------------------------------------------------------- T5 .receiptAfterPwrite

    @Test
    void aDataIsReceiptedWrittenOnceItsPwriteReturnedWhileItsForceIsHeld() throws Exception {
        world();
        Owner o = world.dial(B);
        o.opened(1, HDR + 4096);
        HeldFiles.Hold write = world.files.holdWrite("T5-PW-");
        o.data(1, HDR, ReplWire.filled("T5-PW-", 1000));
        write.awaitReached();
        HeldFiles.Hold force = world.files.holdForceAfter("T5-PW-");
        write.release();
        Answer w = o.expect(ReplWire.RECEIPT, 1, ReplWire.WRITTEN, "the pwrite returned");
        assertEquals(HDR + 1000, w.end(), "WRITTEN at pos + len");
        force.awaitReached();   // the drain's force, entered after the write; held, and the receipt is here already
        assertEquals(1000, world.unforced(), "the bytes written and not forced: the DATA's");
        force.release();
        JournalFixture.until(() -> world.unforced() == 0, () -> "unforced() falls to 0 once the force returned: " + world.unforced());
    }

    // ------------------------------------------------------------------------------------- T5b .forceOffTheReceiptPath

    @Test
    void withAForceHeldTheWriterGoesOnUntilHoldAtUnforcedBytes() throws Exception {
        world();
        Owner o = world.dial(B);
        int d = 3 << 20;   // 3 MiB frames: D3 takes unforced past HOLD_AT (8 MiB), so D4 waits under either reading of the bound
        byte[][] data = {ReplWire.filled("T5b-D1-", d), ReplWire.filled("T5b-D2-", d), ReplWire.filled("T5b-D3-", d), ReplWire.filled("T5b-D4-", d)};
        o.opened(1, HDR + 16 * MIB);
        HeldFiles.Hold force = world.files.holdForceAfter("T5b-D1-");
        o.data(1, HDR, data[0]);
        force.awaitReached();
        o.data(1, HDR + d, data[1]);
        o.untilWritten(1, HDR + 2L * d);   // a second DATA receipted WRITTEN while the force is held

        o.data(1, HDR + 2L * d, data[2]);
        o.data(1, HDR + 3L * d, data[3]);
        Thread writer = world.files.firstWrite("T5b-D1-").thread;
        JournalFixture.until(o::allRead, () -> "node-a read every byte the owner sent");
        for (int i = 0; i < 3; i++) world.onLoop();
        JournalFixture.untilParked(writer, "the link's writer, with D3 and D4 taken from the socket");
        assertNull(world.files.firstWrite("T5b-D4-"), "past HOLD_AT unforced bytes the writer waits for the force: D4 not written (unforced "
                + world.unforced() + ")");
        assertTrue(world.unforced() >= 2L * d, "D1 and D2 are written and not forced: " + world.unforced());

        force.release();
        o.untilWritten(1, HDR + 4L * d);
        assertTrue(world.files.firstWrite("T5b-D4-").entered > force.op().returned, "D4 is written only after the held force returned: "
                + world.files.firstWrite("T5b-D4-") + " vs " + force.op());
    }

    // -------------------------------------------------------------------------------------------------- T6 .protocol

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "a SEAL one byte short",
            "an OPEN of another owner",
            "an OPEN of another journal",
            "a DATA of another owner",
            "an OPEN whose header fails its CRC",
            "an OPEN whose header is of another n",
            "an OPEN whose length is below its header",
            "a fifth unsealed segment on the link",
            "a DATA of a segment not opened on the link",
            "a SEAL of a segment not opened on the link",
            "an empty DATA",
            "a DATA past DATA_MAX",
            "a DATA whose pos is not the replica's end",
            "a SEAL whose end is not the replica's end",
            "a DATA before its RECEIPT OPENED",
            "a RECEIPT from the owner",
            "a REFUSE from the owner",
            "a frame of type 9"})
    void eachProtocolBreachClosesTheLinkWithAWarning(String breach) throws Exception {
        world();
        Owner o = world.dial(B);
        byte[] h1 = o.opened(1, HDR + 4096);   // witness: the link serves before its breach
        long warnings = world.logs.count(Level.WARNING);
        HeldFiles.Hold held = null;
        switch (breach) {
            case "a SEAL one byte short" -> {
                byte[] s = ReplWire.seal("node-b", JOURNAL_B, 1, HDR, ReplWire.crc32c(h1));
                o.raw.send(ReplWire.SEAL, Arrays.copyOf(s, s.length - 1));
            }
            case "an OPEN of another owner" -> {
                byte[] h = ReplWire.header("node-c", JOURNAL_B, 2, CREATED);
                o.raw.send(ReplWire.OPEN, ReplWire.open("node-c", JOURNAL_B, 2, h.length + 4096L, h));
            }
            case "an OPEN of another journal" -> {
                byte[] h = ReplWire.header("node-b", JOURNAL_C, 2, CREATED);
                o.raw.send(ReplWire.OPEN, ReplWire.open("node-b", JOURNAL_C, 2, h.length + 4096L, h));
            }
            case "a DATA of another owner" -> o.raw.send(ReplWire.DATA, ReplWire.data("node-c", JOURNAL_B, 1, HDR, new byte[10]));
            case "an OPEN whose header fails its CRC" -> o.open(2, HDR + 4096, ReplWire.flipped(o.header(2), HDR - 1));
            case "an OPEN whose header is of another n" -> o.open(2, HDR + 4096, o.header(3));
            case "an OPEN whose length is below its header" -> o.open(2, HDR - 1);
            case "a fifth unsealed segment on the link" -> {
                for (long n = 2; n <= ReplWire.OPEN_MAX; n++) o.opened(n, HDR + 4096);
                o.open(ReplWire.OPEN_MAX + 1, HDR + 4096);
            }
            case "a DATA of a segment not opened on the link" -> o.data(9, HDR, new byte[10]);
            case "a SEAL of a segment not opened on the link" -> o.seal(9, HDR, ReplWire.crc32c(o.header(9)));
            case "an empty DATA" -> o.data(1, HDR, new byte[0]);
            case "a DATA past DATA_MAX" -> o.data(1, HDR, new byte[ReplWire.DATA_MAX + 1]);
            case "a DATA whose pos is not the replica's end" -> o.data(1, HDR + 1, new byte[10]);
            case "a SEAL whose end is not the replica's end" -> {
                byte[] b = new byte[100];
                o.written(1, HDR, b);
                o.seal(1, HDR + 99, ReplWire.crc32c(concat(h1, b), 0, HDR + 99));
            }
            case "a DATA before its RECEIPT OPENED" -> {
                held = world.files.holdForceOn(world.part(B, 2), world.files.now());
                o.open(2, HDR + 4096);
                held.awaitReached();   // segment 2's OPEN is in its file force: no RECEIPT OPENED can have gone
                o.data(2, HDR, new byte[10]);
            }
            case "a RECEIPT from the owner" -> o.raw.send(ReplWire.RECEIPT, ReplWire.receipt("node-b", JOURNAL_B, 1, HDR, ReplWire.OPENED));
            case "a REFUSE from the owner" -> o.raw.send(ReplWire.REFUSE, ReplWire.refuse("node-b", JOURNAL_B, 1, ReplWire.FULL, "owner"));
            case "a frame of type 9" -> o.raw.send(9, ReplWire.address("node-b", JOURNAL_B, 1));
            default -> throw new AssertionError("fixture: no case " + breach);
        }
        List<Answer> answers;
        try {
            answers = o.untilClosed();
        } finally {
            if (held != null) held.release();
        }
        assertEquals(List.of(), answers, breach + " closes the link, unanswered");
        assertTrue(world.logs.count(Level.WARNING) > warnings, "a WARNING names the cause: " + world.logs.lines());
    }

    // ------------------------------------------------------------------------------------------------------- T7 .full

    /** FLOTTILA_JOURNAL_MAX_BYTES 16 MiB: the intake edge max − max/16 = 15 MiB, the replicas' half of it 7.5 MiB. */
    static final long MAX = 16 * MIB;
    static final long EDGE = 15 * MIB;
    static final long HALF = EDGE / 2;

    @Test
    void anOpenTakingTheReplicasPastHalfTheIntakeEdgeIsRefusedFullAndTheHoldersOwnAppendsStillFit() throws Exception {
        world(s -> s.maxBytes(MAX));
        Owner o = world.dial(B);
        long before = world.holder.bytes();
        o.opened(1, HALF - HDR);
        assertEquals(before + HALF - HDR, world.holder.bytes(), "OPEN reserves its length in the journal's bytes");

        o.open(2, HDR + 1);
        o.expect(ReplWire.REFUSE, 2, ReplWire.FULL, "replica bytes + length = half + 1");
        assertFalse(Files.exists(world.part(B, 2)), "FULL creates nothing");
        assertEquals(before + HALF - HDR, world.holder.bytes(), "FULL changes no bytes");

        o.opened(3, HDR);   // exactly half: the next fitting OPEN is receipted, the link up
        assertEquals(before + HALF, world.holder.bytes());

        long appended = OnVirtualThread.call(() -> {
            long sum = 0;
            for (int i = 0; sum < 7 * MIB; i++) {
                JournalRecord r = JournalFixture.record("t7-own-" + i, 256 << 10);
                JournalFixture.await(world.holder.append(r, JournalFixture.live()).forced());
                sum += 256 << 10;
            }
            return sum;
        });
        assertEquals(7 * MIB, appended, "the holder's own intake appends fit beside the replicas' half");
    }

    @Test
    void anOpenPastTheIntakeEdgeIsRefusedFullThoughUnderTheReplicasHalf() throws Exception {
        world(s -> s.maxBytes(MAX));
        OnVirtualThread.call(() -> {
            for (int i = 0; world.holder.bytes() <= EDGE - MIB; i++) {
                JournalFixture.await(world.holder.append(JournalFixture.record("t7-fill-" + i, 256 << 10), JournalFixture.live()).forced());
            }
            return null;
        });
        long room = EDGE - world.holder.bytes();
        assertTrue(room > HDR && room < MIB, "fixture: the journal is within 1 MiB of its edge: " + room);
        Owner o = world.dial(B);
        o.open(1, room + 1);
        o.expect(ReplWire.REFUSE, 1, ReplWire.FULL, "the journal's bytes + length one byte past the intake edge");
        assertFalse(Files.exists(world.part(B, 1)), "FULL creates nothing");
        o.opened(2, room);   // exactly to the edge
    }

    /**
     * CORE-5b1 r1 R1: OPEN's length is a wire u64; one of Long.MAX_VALUE - 10 after OPEN 1 must not wrap the replicas' sum
     * (replica bytes + length, journal bytes + length) below the bound: REFUSE FULL, nothing created, bytes() unchanged.
     */
    @Test
    void anOpenWhoseLengthWouldOverflowTheCountIsRefusedFullAndChangesNoBytes() throws Exception {
        world();
        Owner o = world.dial(B);
        o.opened(1, HDR + 4096);   // the replicas hold 4145 bytes: their sum with the length passes Long.MAX_VALUE
        long before = world.holder.bytes();
        o.open(2, Long.MAX_VALUE - 10);
        o.expect(ReplWire.REFUSE, 2, ReplWire.FULL, "an OPEN of Long.MAX_VALUE - 10 bytes is past any half of the intake edge");
        assertFalse(Files.exists(world.part(B, 2)), "FULL creates nothing");
        assertEquals(before, world.holder.bytes(), "FULL changes no bytes");
        o.opened(3, HDR + 4096);   // the link is up
        assertEquals(before + HDR + 4096, world.holder.bytes(), "the next fitting OPEN reserves its length");
    }

    // ------------------------------------------------------------------------------------------ T8 .mismatchAndReopen

    @Test
    void anOpenOfASegmentHeldSealedIsRefusedMismatch() throws Exception {
        world();
        Owner o = world.dial(B);
        byte[] h = o.opened(1, HDR + 100);
        byte[] d = ReplWire.filled("T8-SEALED-", 100);
        o.written(1, HDR, d);
        o.seal(1, HDR + 100, ReplWire.crc32c(concat(h, d)));
        o.expect(ReplWire.RECEIPT, 1, ReplWire.SEALED, "the seal");
        byte[] sealed = Files.readAllBytes(world.seg(B, 1));

        o.open(1, HDR + 100);
        o.expect(ReplWire.REFUSE, 1, ReplWire.MISMATCH, "n is held as .seg");
        assertArrayEquals(sealed, Files.readAllBytes(world.seg(B, 1)), "the .seg is untouched");
        assertFalse(Files.exists(world.part(B, 1)), "no .part beside it");
        o.opened(2, HDR + 100);   // the link is up
    }

    @Test
    void aPartNotExactlyThisHeaderIsRefusedMismatchAndAHeaderOnlyPartReopens() throws Exception {
        world();
        Owner first = world.dial(B);
        byte[] h = first.opened(2, HDR + 100);
        first.raw.close();

        Owner second = world.dial(B);
        second.open(2, HDR + 100, ReplWire.header("node-b", JOURNAL_B, 2, CREATED + 1));
        second.expect(ReplWire.REFUSE, 2, ReplWire.MISMATCH, "a .part of another header (another created ms)");
        assertArrayEquals(h, Files.readAllBytes(world.part(B, 2)), "the .part is untouched");

        long mark = world.files.now();
        second.open(2, HDR + 100, h);
        second.expect(ReplWire.RECEIPT, 2, ReplWire.OPENED, "a header-only .part of exactly this header re-OPENs");
        HeldFiles.Op force = first(world.opsOn(world.part(B, 2)), HeldFiles.Kind.FORCE, mark, "on the re-OPENed .part");
        first(world.opsOn(world.replicaDir("node-b", JOURNAL_B)), HeldFiles.Kind.FORCE, force.returned, "on the directory after the re-OPEN's force");
        second.written(2, HDR, ReplWire.filled("T8-REOPEN-", 10));   // the re-OPENed replica takes DATA
        second.raw.close();

        Owner third = world.dial(B);
        third.open(2, HDR + 100, h);
        third.expect(ReplWire.REFUSE, 2, ReplWire.MISMATCH, "a .part with bytes past its header is not exactly this header");
        assertEquals(HDR + 10, Files.size(world.part(B, 2)), "the .part keeps its bytes");
    }

    // ----------------------------------------------------------------------------------------------------- T9 .failed

    @Test
    void aFailedWriteIsRefusedFailedKeepsThePartTruesTheCountAndDropsLaterFramesOfItsSegment() throws Exception {
        world();
        Owner o = world.dial(B);
        long before = world.holder.bytes();
        byte[] h = o.opened(1, HDR + MIB);
        assertEquals(before + HDR + MIB, world.holder.bytes(), "OPEN reserves its length");

        world.files.holdWrite("T9-FAIL-").fail(new IOException("fixture: the pwrite fails"));
        o.data(1, HDR, ReplWire.filled("T9-FAIL-", 1000));
        o.expect(ReplWire.REFUSE, 1, ReplWire.FAILED, "the failed pwrite: REFUSE FAILED, no RECEIPT WRITTEN before it");
        assertTrue(Files.exists(world.part(B, 1)), "C2: the .part stays");
        assertEquals(HDR, Files.size(world.part(B, 1)), "the failed write wrote nothing");
        JournalFixture.until(() -> world.holder.bytes() == before + HDR, () -> "the count trued to the .part's size: " + world.holder.bytes()
                + " vs " + (before + HDR));
        assertFalse(world.holder.isFailed(), "a replica's failure is no fail-stop of the holder's journal");

        o.data(1, HDR, ReplWire.filled("T9-LATE-", 500));
        o.seal(1, HDR, ReplWire.crc32c(h));
        o.opened(2, HDR + 100);   // the next answer: the DATA and SEAL of segment 1 got none, the link is up
        assertNull(world.files.firstWrite("T9-LATE-"), "the later DATA of a FAILED segment is dropped, not written");
        assertEquals(HDR, Files.size(world.part(B, 1)));
        assertFalse(Files.exists(world.seg(B, 1)), "nor sealed");
        assertEquals(before + HDR + HDR + 100, world.holder.bytes(), "segment 1 counts its size, segment 2 its reservation");
    }

    /** CORE-5b1 r1 R2: a DATA whose pwrite tears after 300 of its 1000 bytes: FAILED, and bytes() counts the .part's HDR + 300. */
    @Test
    void aTornWriteIsRefusedFailedAndCountsThePartAtItsSizeOnDisk() throws Exception {
        world();
        Owner o = world.dial(B);
        long before = world.holder.bytes();
        o.opened(1, HDR + MIB);
        world.files.holdWrite("R2-TORN-").tear(300, new IOException("fixture: the pwrite tears after 300 bytes"));
        o.data(1, HDR, ReplWire.filled("R2-TORN-", 1000));
        o.expect(ReplWire.REFUSE, 1, ReplWire.FAILED, "the torn pwrite: REFUSE FAILED, no RECEIPT WRITTEN before it");
        assertEquals(HDR + 300, Files.size(world.part(B, 1)), "fixture: the torn write left 300 bytes in the .part");
        o.opened(2, HDR + 100);   // the link's writer took OPEN 2 after the failed DATA's replica was detached
        assertEquals(before + (HDR + 300) + (HDR + 100), world.holder.bytes(),
                "the failed replica counts its .part at its size on disk (" + (HDR + 300) + "), not its end before the write; OPEN 2 its length");
        assertTrue(Files.exists(world.part(B, 1)), "C2: the .part stays");
    }

    // ------------------------------------------------------------------------------------------------------- T10 .seal

    @Test
    void aSealedReplicaIsTheBytesSentRenamedSeg() throws Exception {
        world();
        Owner o = world.dial(B);
        byte[] h = o.opened(1, HDR + 300);
        byte[] d1 = ReplWire.filled("T10-A-", 200);
        byte[] d2 = ReplWire.filled("T10-B-", 100);
        o.written(1, HDR, d1);
        o.written(1, HDR + 200, d2);
        byte[] all = concat(concat(h, d1), d2);
        o.seal(1, all.length, ReplWire.crc32c(all));
        Answer s = o.expect(ReplWire.RECEIPT, 1, ReplWire.SEALED, "the seal");
        assertEquals(all.length, s.end(), "SEALED at the file's whole length");
        assertArrayEquals(all, Files.readAllBytes(world.seg(B, 1)), "the .seg is the bytes sent, in order");
        assertFalse(Files.exists(world.part(B, 1)), "no .part");
    }

    @Test
    void sealedArrivesOnlyAfterTheForceTheRenameAndTheDirectoryForce() throws Exception {
        world();
        Owner o = world.dial(B);
        byte[] h = o.opened(1, HDR + 1000);
        o.opened(2, HDR + MIB);   // a second segment: its DATA is the writer's witness while the seal is held
        byte[] d = ReplWire.filled("T10-SEAL-", 1000);
        o.written(1, HDR, d);
        JournalFixture.until(() -> world.unforced() == 0, () -> "the DATA's own force returned: " + world.unforced());

        HeldFiles.Hold force = world.files.holdForceOn(world.part(B, 1), world.files.now());
        o.seal(1, HDR + 1000, ReplWire.crc32c(concat(h, d)));
        force.awaitReached();
        assertTrue(Files.exists(world.part(B, 1)) && !Files.exists(world.seg(B, 1)), "the seal's force comes before its rename");
        o.data(2, HDR, ReplWire.filled("T10-W1-", 100));
        o.expect(ReplWire.RECEIPT, 2, ReplWire.WRITTEN, "the writer goes on while the seal's force is held, and no SEALED came before");

        HeldFiles.Hold dirForce = world.files.holdForceOn(world.replicaDir("node-b", JOURNAL_B), world.files.now());
        force.release();
        dirForce.awaitReached();
        assertTrue(Files.exists(world.seg(B, 1)) && !Files.exists(world.part(B, 1)), "renamed to .seg before the directory force");
        o.data(2, HDR + 100, ReplWire.filled("T10-W2-", 100));
        o.expect(ReplWire.RECEIPT, 2, ReplWire.WRITTEN, "renamed, the directory force held: still no SEALED");

        dirForce.release();
        Answer s = o.expect(ReplWire.RECEIPT, 1, ReplWire.SEALED, "after the directory force");
        assertEquals(HDR + 1000, s.end());
    }

    @Test
    void aSealWhoseRenameFailsIsRefusedFailedNeverSealed() throws Exception {
        world();
        Owner o = world.dial(B);
        byte[] h = o.opened(1, HDR + 100);
        byte[] d = ReplWire.filled("T10-RENAME-", 100);
        o.written(1, HDR, d);
        Files.createDirectories(world.seg(B, 1));
        Files.writeString(world.seg(B, 1).resolve("in-the-way"), "x");   // ATOMIC_MOVE onto a non-empty directory fails, root or not
        o.seal(1, HDR + 100, ReplWire.crc32c(concat(h, d)));
        o.expect(ReplWire.REFUSE, 1, ReplWire.FAILED, "the rename failed: REFUSE FAILED, no SEALED");
        assertArrayEquals(concat(h, d), Files.readAllBytes(world.part(B, 1)), "C2: the .part is kept");
        o.opened(2, HDR + 100);   // the link is up
    }

    @Test
    void aSealWhoseCrc32cDiffersIsRefusedMismatchAndThePartKept() throws Exception {
        world();
        Owner o = world.dial(B);
        byte[] h = o.opened(1, HDR + 100);
        byte[] d = ReplWire.filled("T10-CRC-", 100);
        o.written(1, HDR, d);
        o.seal(1, HDR + 100, ReplWire.crc32c(concat(h, d)) ^ 1);
        o.expect(ReplWire.REFUSE, 1, ReplWire.MISMATCH, "SEAL's crc32c is not the file's");
        assertArrayEquals(concat(h, d), Files.readAllBytes(world.part(B, 1)), "the .part is kept");
        assertFalse(Files.exists(world.seg(B, 1)), "not renamed");
        o.opened(2, HDR + 100);   // the link is up
    }

    // ------------------------------------------------------------------------------------------------ T10b .countTrued

    @Test
    void bytesPastTheOpensLengthAreCountedAndASealTruesTheCountToTheFileSize() throws Exception {
        world();
        Owner o = world.dial(B);
        long before = world.holder.bytes();
        byte[] h1 = o.opened(1, HDR + 100);
        byte[] d1 = ReplWire.filled("T10b-PAST-", 1000);
        o.written(1, HDR, d1);
        JournalFixture.until(() -> world.holder.bytes() == before + HDR + 1000,
                () -> "the bytes past the OPEN's length are counted: " + world.holder.bytes() + " vs " + (before + HDR + 1000));
        o.seal(1, HDR + 1000, ReplWire.crc32c(concat(h1, d1)));
        o.expect(ReplWire.RECEIPT, 1, ReplWire.SEALED, "the seal");

        long after1 = before + HDR + 1000;
        byte[] h2 = o.opened(2, HDR + MIB);
        assertEquals(after1 + HDR + MIB, world.holder.bytes(), "segment 2 reserves its length");
        byte[] d2 = ReplWire.filled("T10b-SHORT-", 100);
        o.written(2, HDR, d2);
        o.seal(2, HDR + 100, ReplWire.crc32c(concat(h2, d2)));
        o.expect(ReplWire.RECEIPT, 2, ReplWire.SEALED, "the seal");
        JournalFixture.until(() -> world.holder.bytes() == after1 + HDR + 100,
                () -> "the seal trues the count to the file's size: " + world.holder.bytes() + " vs " + (after1 + HDR + 100));
        assertEquals(Files.size(world.seg(B, 1)) + Files.size(world.seg(B, 2)), world.holder.bytes() - before, "both counted at their size");
    }

    @Test
    void aLinkCloseTruesTheReservationOfEveryUnsealedReplica() throws Exception {
        world();
        Owner o = world.dial(B);
        long before = world.holder.bytes();
        o.opened(1, HDR + MIB);
        o.opened(2, HDR + 2 * MIB);
        o.written(1, HDR, ReplWire.filled("T10b-CLOSE-", 10));
        assertEquals(before + HDR + MIB + HDR + 2 * MIB, world.holder.bytes(), "both reservations counted");
        o.raw.close();
        JournalFixture.until(() -> world.holder.bytes() == before + (HDR + 10) + HDR,
                () -> "the link's close trues each unsealed replica to its size: " + world.holder.bytes() + " vs " + (before + HDR + 10 + HDR));
        assertTrue(Files.exists(world.part(B, 1)) && Files.exists(world.part(B, 2)), "the .part files stay");
    }

    @Test
    void aReplicaWhoseCloseFailsAtTheLinkCloseIsLoggedAsAWarningItsCountStillTruedAndTheHolderStops() throws Exception {
        world();
        Owner o = world.dial(B);
        long before = world.holder.bytes();
        o.opened(1, HDR + MIB);
        o.opened(2, HDR + MIB);
        o.written(1, HDR, ReplWire.filled("T10b-SHUT-", 10));
        world.files.failNextCloseOn(world.part(B, 1));
        o.raw.close();

        JournalFixture.until(() -> !closeWarnings().isEmpty(), () -> "the failed close of 1.part is logged: " + world.logs.lines());
        assertEquals(1, world.files.failedClosesOf(world.part(B, 1)), "fixture: 1.part's close threw once");
        List<java.util.logging.LogRecord> warnings = closeWarnings();
        assertEquals(1, warnings.size(), "one WARNING, for the one close that failed (2.part closed fine): " + world.logs.lines());
        java.util.logging.LogRecord w = warnings.get(0);
        assertTrue(w.getMessage().contains("node-b") && w.getMessage().contains(JOURNAL_B.toString()), "it names the owner and its journal: " + w.getMessage());
        assertTrue(w.getThrown() instanceof IOException && String.valueOf(w.getThrown().getMessage()).contains("1.part"),
                "it carries the close's failure: " + w.getThrown());
        // the slots are detached in turn: 2.part's may follow the WARNING of 1.part's
        JournalFixture.until(() -> world.holder.bytes() == before + (HDR + 10) + HDR,
                () -> "both replicas trued to their size, the one whose close failed too: " + world.holder.bytes() + " vs " + (before + HDR + 10 + HDR));
        assertTrue(Files.exists(world.part(B, 1)) && Files.exists(world.part(B, 2)), "the .part files stay");

        Owner next = world.dial(ReplicaWorld.owner("node-b", 1, JOURNAL_B));
        next.opened(3, HDR + 1024);   // a superseding link takes its first frame only once the old holder stopped
        next.written(3, HDR, ReplWire.filled("T10b-NEXT-", 10));
    }

    /** The WARNINGs of the holder naming a replica that did not close, in order. */
    List<java.util.logging.LogRecord> closeWarnings() {
        return world.logs.records().stream()
                .filter(r -> r.getLevel().equals(Level.WARNING) && String.valueOf(r.getMessage()).contains("did not close")).toList();
    }

    // ------------------------------------------------------------------------------------------ T10c .ownersIndependent

    @Test
    void anOwnersHeldWriteDoesNotStopAnotherOwnersReceipts() throws Exception {
        world();
        Owner x = world.dial(B);
        Owner y = world.dial(C);
        x.opened(1, HDR + 1000);
        byte[] hy = y.opened(1, y.headerLength() + 1000);
        HeldFiles.Hold held = world.files.holdWrite("T10c-X-");
        x.data(1, HDR, ReplWire.filled("T10c-X-", 100));
        held.awaitReached();

        byte[] dy = ReplWire.filled("T10c-Y-", 100);
        y.written(1, hy.length, dy);
        y.seal(1, hy.length + 100, ReplWire.crc32c(concat(hy, dy)));
        y.expect(ReplWire.RECEIPT, 1, ReplWire.SEALED, "node-c's seal while node-b's write is held");
        assertTrue(Files.exists(world.replicaDir("node-c", JOURNAL_C).resolve("1.seg")));

        held.release();
        x.untilWritten(1, HDR + 100);
    }
}
