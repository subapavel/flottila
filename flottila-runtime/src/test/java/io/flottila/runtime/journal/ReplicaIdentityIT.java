package io.flottila.runtime.journal;

import io.flottila.runtime.journal.ReplicaWorld.Owner;
import io.flottila.runtime.journal.peer.PeerIdentity;
import io.flottila.runtime.journal.peer.ReplWire;
import io.flottila.runtime.journal.peer.ReplWire.Answer;
import io.flottila.runtime.node.OnVirtualThread;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-5b1 T12 (packet "Holder disk": SEAL → force, ATOMIC_MOVE to {@code <n>.seg}, directory force, RECEIPT SEALED;
 * byte-identical to the owner's {@code segments/<n>.seg}): a real journal's sealed segment — more than 4 MiB of records, then
 * its footer index and trailer — sent as an owner sends it: OPEN with its header, the records in DATA frames of at most
 * DATA_MAX, the footer in two DATA frames (DATA, not SEAL, carries it), then SEAL with the file's whole length and crc32c.
 * The holder's {@code replicas/node-b/<journal id>/<n>.seg} is then the owner's file, byte for byte.
 */
@Timeout(120)
class ReplicaIdentityIT {

    @TempDir Path tmp;
    ReplicaWorld world;
    Journal owner;

    @AfterEach
    void close() throws Exception {
        if (owner != null) owner.close();
        if (world != null) world.close();
    }

    @Test
    void aRealJournalsSealedSegmentReplicatedFrameByFrameIsByteIdenticalOnTheHolder() throws Exception {
        JournalFixture.Settings s = JournalFixture.settings(Files.createDirectories(tmp.resolve("node-b")));
        s.node = "node-b";
        JournalConfig config = s.config();
        owner = OnVirtualThread.call(() -> Journal.open(config, world().clock, world.timer, JournalFixture.keys(JournalFixture.KEY), JournalFiles.real()));
        long n = OnVirtualThread.call(() -> {
            JournalRef last = null;
            for (int i = 0; i < 24; i++) {
                Journal.Appended a = owner.append(JournalFixture.record("t12-" + i, 256 << 10), JournalFixture.live());
                JournalFixture.await(a.forced());
                last = a.ref();
            }
            return last.segment();
        });
        java.util.UUID journal = owner.id();
        owner.close();   // seals the open segment
        owner = null;
        byte[] file = Files.readAllBytes(tmp.resolve("node-b").resolve("segments").resolve(n + ".seg"));
        long index = ByteBuffer.wrap(file).getLong(file.length - SegmentFormat.TRAILER);
        assertTrue(index - ReplicaWorld.HDR > ReplWire.DATA_MAX && index < file.length, "fixture: more than 4 MiB of records, then the footer");

        PeerIdentity b = ReplicaWorld.owner("node-b", 1, journal);
        Owner o = world.dial(b);
        int header = o.headerLength();
        byte[] h = java.util.Arrays.copyOf(file, header);
        o.open(n, file.length, h);
        o.expect(ReplWire.RECEIPT, n, ReplWire.OPENED, "the OPEN of the owner's header");
        int frames = 0;
        for (long pos = header; pos < index; pos += ReplWire.DATA_MAX) {
            int len = (int) Math.min(ReplWire.DATA_MAX, index - pos);
            o.raw.send(ReplWire.DATA, ReplWire.data("node-b", journal, n, pos, file, (int) pos, len));
            frames++;
        }
        assertTrue(frames >= 2, "fixture: the records take more than one DATA frame");
        long half = index + (file.length - index) / 2;
        o.raw.send(ReplWire.DATA, ReplWire.data("node-b", journal, n, index, file, (int) index, (int) (half - index)));
        o.raw.send(ReplWire.DATA, ReplWire.data("node-b", journal, n, half, file, (int) half, (int) (file.length - half)));
        o.untilWritten(n, file.length);
        o.seal(n, file.length, ReplWire.crc32c(file));
        Answer sealed = o.expect(ReplWire.RECEIPT, n, ReplWire.SEALED, "the SEAL");
        assertEquals(file.length, sealed.end());

        assertArrayEquals(file, Files.readAllBytes(world.seg(b, n)), "the holder's .seg is the owner's segment, byte for byte");
        assertFalse(Files.exists(world.part(b, n)), "no .part is left");
    }

    ReplicaWorld world() throws Exception {
        if (world == null) world = new ReplicaWorld(tmp);
        return world;
    }
}
