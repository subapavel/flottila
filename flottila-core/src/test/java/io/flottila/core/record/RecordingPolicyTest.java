package io.flottila.core.record;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.flottila.core.record.RecordingPolicy.Level;
import static io.flottila.core.record.RecordingPolicy.Payload;
import static io.flottila.core.record.Snapshot.Outcome;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Recording per flow, overridable per step (ARCHITECTURE §6): which steps leave a snapshot, and whether the body stays reachable. */
class RecordingPolicyTest {

    @Test
    void parsesTheSchemaShapeOverABaseAndDefaultsToEverythingKept() {
        assertEquals(new RecordingPolicy(Level.ALL, Payload.KEEP), RecordingPolicy.DEFAULT, "monitoring is the product: all, keep");
        assertEquals(RecordingPolicy.DEFAULT, RecordingPolicy.from(null, RecordingPolicy.DEFAULT));
        assertEquals(new RecordingPolicy(Level.ENDS, Payload.KEEP), RecordingPolicy.from(Map.of("level", "ends"), RecordingPolicy.DEFAULT));
        assertEquals(new RecordingPolicy(Level.ERRORS, Payload.METADATA_ONLY), RecordingPolicy.from(Map.of("level", "errors", "payload", "metadata-only"), RecordingPolicy.DEFAULT));
        RecordingPolicy flow = new RecordingPolicy(Level.OFF, Payload.METADATA_ONLY);
        assertEquals(new RecordingPolicy(Level.ALL, Payload.METADATA_ONLY), RecordingPolicy.from(Map.of("level", "all"), flow), "a step override keeps what it does not name");
        assertThrows(IllegalArgumentException.class, () -> RecordingPolicy.from(Map.of("level", "some"), flow));
        assertThrows(IllegalArgumentException.class, () -> RecordingPolicy.from(Map.of("payload", "none"), flow));
        assertThrows(IllegalArgumentException.class, () -> RecordingPolicy.from("all", flow));
    }

    @Test
    void theLevelDecidesWhichStepsAreRecorded() {
        RecordingPolicy all = new RecordingPolicy(Level.ALL, Payload.KEEP);
        RecordingPolicy ends = new RecordingPolicy(Level.ENDS, Payload.KEEP);
        RecordingPolicy errors = new RecordingPolicy(Level.ERRORS, Payload.KEEP);
        RecordingPolicy off = new RecordingPolicy(Level.OFF, Payload.KEEP);
        assertTrue(all.records(0, false, Outcome.OK) && all.records(2, false, Outcome.OK) && all.records(3, true, Outcome.OK) && all.records(2, false, Outcome.FAILED));
        assertTrue(ends.records(0, false, Outcome.OK), "ingress is an end");
        assertFalse(ends.records(2, false, Outcome.OK));
        assertTrue(ends.records(3, true, Outcome.OK), "the last step is an end");
        assertTrue(ends.records(2, false, Outcome.FAILED), "a failure ends the message");
        assertTrue(ends.records(2, false, Outcome.FILTERED), "so does a filter");
        assertFalse(errors.records(0, false, Outcome.OK) || errors.records(3, true, Outcome.OK) || errors.records(2, false, Outcome.FILTERED));
        assertTrue(errors.records(2, false, Outcome.FAILED));
        assertFalse(off.records(0, false, Outcome.OK) || off.records(2, false, Outcome.FAILED) || off.records(3, true, Outcome.OK));
        assertTrue(all.keepsBody() && !new RecordingPolicy(Level.ALL, Payload.METADATA_ONLY).keepsBody());
    }
}
