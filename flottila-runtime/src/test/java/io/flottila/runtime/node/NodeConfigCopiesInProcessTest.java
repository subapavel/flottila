package io.flottila.runtime.node;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-5a S11 (implementer): an in-process {@link NodeConfig} — built by its canonical constructor, not read from an env — is held
 * to the range {@code FLOTTILA_JOURNAL_COPIES} names (1 or 2), the message naming the key; in range, the copies given are kept.
 */
class NodeConfigCopiesInProcessTest {

    /** {@code base} with {@code copies}, every other component its own. */
    static NodeConfig with(NodeConfig base, int copies) {
        return new NodeConfig(base.nodeId(), base.env(), base.admin(), base.dataDir(), base.stopDeadline(), base.maxPackageBytes(), base.pg(),
                base.blobs(), base.archives(), base.s3(), base.secrets(), base.leases(), base.retention(), base.cpuSampling(), base.targets(),
                base.journal(), base.journalKey(), base.journalSweep(), base.intakeGroup(), base.nodeLease(), base.nodeLeaseRenew(), base.retries(),
                base.peer(), copies);
    }

    final NodeConfig base = NodeConfig.from(NodeConfigRetriesTest.env());

    @Test
    void bothCopiesInRangeAreKept() {
        assertEquals(1, with(base, 1).journalCopies());
        assertEquals(2, with(base, 2).journalCopies());
    }

    @Test
    void copiesOutOfTheRangeAreRefusedNamingTheKey() {
        for (int copies : new int[] {0, 3}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> with(base, copies), "copies " + copies);
            assertTrue(e.getMessage().contains("FLOTTILA_JOURNAL_COPIES") && e.getMessage().contains(String.valueOf(copies)), e.getMessage());
        }
    }
}
