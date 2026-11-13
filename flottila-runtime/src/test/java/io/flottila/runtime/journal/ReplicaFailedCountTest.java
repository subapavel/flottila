package io.flottila.runtime.journal;

import io.flottila.runtime.journal.ReplicaWorld.Owner;
import io.flottila.runtime.journal.peer.ReplWire;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static io.flottila.runtime.journal.ReplicaWorld.B;
import static io.flottila.runtime.journal.ReplicaWorld.HDR;
import static io.flottila.runtime.journal.ReplicaWorld.JOURNAL_B;
import static io.flottila.runtime.journal.ReplicaWorld.MIB;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-5b1 r1 R2, past the packet's cases: a failed replica stays counted at its size on disk once its link closes (the
 * writer detaches every replica of the link again at its end), and a failed create whose close fails too still counts its
 * {@code .part} at its size, the close's failure kept beside the create's in the holder's WARNING; one that failed before its
 * file counts nothing.
 */
@Timeout(120)
class ReplicaFailedCountTest {

    @TempDir Path tmp;
    ReplicaWorld world;

    @AfterEach
    void close() throws Exception {
        if (world != null) world.close();
    }

    @Test
    void aTornReplicaStaysCountedAtItsSizeOnDiskOnceItsLinkClosed() throws Exception {
        world = new ReplicaWorld(tmp);
        Owner o = world.dial(B);
        long before = world.holder.bytes();
        o.opened(1, HDR + MIB);
        world.files.holdWrite("R2-AGAIN-").tear(300, new IOException("fixture: the pwrite tears after 300 bytes"));
        o.data(1, HDR, ReplWire.filled("R2-AGAIN-", 1000));
        o.expect(ReplWire.REFUSE, 1, ReplWire.FAILED, "the torn pwrite");
        o.opened(2, HDR + 100);   // the writer took OPEN 2 after it detached the failed replica
        assertEquals(before + (HDR + 300) + (HDR + 100), world.holder.bytes(), "fixture: the failed replica counts its size on disk");
        o.raw.close();

        Owner next = world.dial(ReplicaWorld.owner("node-b", 1, JOURNAL_B));
        next.opened(3, HDR + 100);   // taken only once the old link's writer stopped and detached each of its replicas
        assertEquals(HDR + 300, Files.size(world.part(B, 1)), "the torn .part as it was");
        assertEquals(before + (HDR + 300) + HDR + (HDR + 100), world.holder.bytes(),
                "segment 1 still counts its size on disk after the link's end detached it again, segment 2 its header, segment 3 its length");
    }

    @Test
    void aFirstOpenWhoseDirectoryForceFailsIsRefusedFailedWithNoPartAndNothingCounted() throws Exception {
        world = new ReplicaWorld(tmp);
        Owner o = world.dial(B);
        long before = world.holder.bytes();
        long mark = world.files.now();
        world.files.holdForceOn(world.replicaDir("node-b", JOURNAL_B), mark).fail(new IOException("fixture: the directory force fails"));
        o.open(1, HDR + 4096);   // its directories are made first: the journal id's is forced before any .part
        o.expect(ReplWire.REFUSE, 1, ReplWire.FAILED, "the failed directory force");
        assertFalse(Files.exists(world.part(B, 1)), "the create failed before its file");
        o.opened(2, HDR + 4096);   // the link is up, the directories there now
        assertEquals(before + HDR + 4096, world.holder.bytes(), "the failed create counts nothing; OPEN 2 its length");
        List<LogRecord> refused = refusedWarnings(1);
        assertEquals(1, refused.size(), "one WARNING for segment 1: " + world.logs.lines());
        assertEquals(0, refused.get(0).getThrown().getSuppressed().length,
                "a create that failed before its file had nothing to close: " + refused.get(0).getThrown());
    }

    /** The holder's WARNINGs refusing segment {@code n}. */
    List<LogRecord> refusedWarnings(long n) {
        return world.logs.records().stream()
                .filter(r -> r.getLevel().equals(Level.WARNING) && String.valueOf(r.getMessage()).contains("replica " + n + " ")).toList();
    }

    @Test
    void aFailedCreateWhoseCloseFailsTooCountsThePartAtItsSizeAndKeepsBothFailures() throws Exception {
        world = new ReplicaWorld(tmp);
        Owner o = world.dial(B);
        o.opened(1, HDR + 4096);   // the directories exist: segment 2's first write is its header
        long before = world.holder.bytes();
        Path part2 = world.part(B, 2).toAbsolutePath().normalize();
        world.files.holdFirstWriteOn("2.part", part2::equals).tear(20, new IOException("fixture: the header write tears after 20 bytes"));
        world.files.failNextCloseOn(part2);
        o.open(2, HDR + 4096);
        o.expect(ReplWire.REFUSE, 2, ReplWire.FAILED, "the torn header write");
        o.opened(3, HDR + 4096);   // the writer took OPEN 3 after OPEN 2's create returned
        assertEquals(1, world.files.failedClosesOf(part2), "fixture: 2.part's close threw");
        assertEquals(20, Files.size(part2), "fixture: 20 bytes of the header on disk");
        assertEquals(before + 20 + (HDR + 4096), world.holder.bytes(), "the failed create counts its 20 bytes, its close failing or not");

        List<LogRecord> refused = refusedWarnings(2);
        assertEquals(1, refused.size(), "one WARNING for segment 2: " + world.logs.lines());
        Throwable why = refused.get(0).getThrown();
        assertTrue(why instanceof IOException && String.valueOf(why.getMessage()).contains("tears"), "the create's failure: " + why);
        assertEquals(1, why.getSuppressed().length, "the close's failure kept beside it: " + why);
        assertTrue(String.valueOf(why.getSuppressed()[0].getMessage()).contains("close of"), "it is the close's: " + why.getSuppressed()[0]);
    }
}
