package io.flottila.core.record;

import io.flottila.api.Envelope;
import io.flottila.api.Hash;
import io.flottila.api.Headers;
import io.flottila.api.Keys;
import io.flottila.api.Props;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FIX-175 (P2): a snapshot's attempt is an int of at least 1 in its props; a value of another type is no attempt. */
class SnapshotAttemptTest {

    static Snapshot withAttempt(Object attempt) {
        return new Snapshot("m-1", "orders", 1, "validate", Snapshot.Outcome.OK, Optional.empty(), Instant.EPOCH, 0L, Snapshot.CPU_UNKNOWN, Headers.empty(),
                new Props(Map.of(Envelope.ATTEMPT.name(), attempt)), Keys.empty(), new Hash("xxh3-64", 1L), 0L, Optional.empty(), Map.of());
    }

    @Test
    void anAttemptThatIsNotAnIntIsRefusedAtConstruction() {
        for (Object notAnInt : new Object[] {"2", 2L, 2.0}) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> withAttempt(notAnInt), "attempt " + notAnInt.getClass().getSimpleName());
            assertTrue(refused.getMessage().contains("attempt"), refused.getMessage());
        }
        assertEquals(2, withAttempt(2).attempt(), "witness: an int of at least 1 is the attempt");
    }
}
