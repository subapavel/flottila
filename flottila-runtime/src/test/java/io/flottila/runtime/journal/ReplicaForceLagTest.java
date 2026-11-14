package io.flottila.runtime.journal;

import io.flottila.runtime.journal.ReplicaWorld.Owner;
import io.flottila.runtime.journal.peer.ReplWire;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static io.flottila.runtime.journal.ReplicaWorld.B;
import static io.flottila.runtime.journal.ReplicaWorld.HDR;
import static io.flottila.runtime.journal.ReplicaWorld.MIB;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CORE-5b1 C5, the force lag ({@link Replicas#unforced()}): a force makes durable what was written before it began, so bytes
 * a DATA wrote while the force of an earlier one was held stay unforced once that force returned, until their own force.
 */
@Timeout(120)
class ReplicaForceLagTest {

    @TempDir Path tmp;
    ReplicaWorld world;

    @AfterEach
    void close() throws Exception {
        if (world != null) world.close();
    }

    @Test
    void bytesWrittenDuringAHeldForceStayUnforcedOnceThatForceReturned() throws Exception {
        world = new ReplicaWorld(tmp);
        Owner o = world.dial(B);
        o.opened(1, HDR + MIB);
        HeldFiles.Hold first = world.files.holdForceAfter("LAG-D1-");
        HeldFiles.Hold second = world.files.holdForceAfter("LAG-D2-");
        o.written(1, HDR, ReplWire.filled("LAG-D1-", 1000));
        first.awaitReached();   // D1's force, on the link's force thread
        o.written(1, HDR + 1000, ReplWire.filled("LAG-D2-", 500));   // written and receipted while D1's force is held
        assertEquals(1500, world.unforced(), "fixture: neither force returned");
        first.release();
        second.awaitReached();   // D1's force returned, D2's is held
        assertEquals(500, world.unforced(), "D1's force covers D1's bytes only: D2's 500, written during it, are still unforced");
        second.release();
        JournalFixture.until(() -> world.unforced() == 0, () -> "D2's own force returned: unforced " + world.unforced());
    }
}
