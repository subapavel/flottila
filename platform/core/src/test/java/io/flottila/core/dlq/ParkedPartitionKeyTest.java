package io.flottila.core.dlq;

import io.flottila.api.Props;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * CORE-14b O7 (implementer's cases): {@link Parked#partitionKey()} is never null — a row built without one (the 13-component
 * constructor: a step's park, a replay's, every row parked before the key was kept) keeps none, and a null key is refused at
 * construction, as every other reference component is.
 */
class ParkedPartitionKeyTest {

    @Test
    void aRowBuiltWithoutAKeyKeepsNone() {
        Parked p = InMemoryDlqTest.parked("m-1", 1, java.time.Instant.EPOCH);
        assertEquals(Optional.empty(), p.partitionKey());
    }

    @Test
    void aNullKeyIsRefused() {
        Parked p = InMemoryDlqTest.parked("m-1", 1, java.time.Instant.EPOCH);
        NullPointerException refused = assertThrows(NullPointerException.class, () -> new Parked(p.messageId(), p.flow(), p.step(), p.stepName(),
                p.error(), p.parkedAt(), p.headers(), Props.empty(), p.keys(), p.bodyHash(), p.bodySize(), p.bodyKey(), p.versions(), null));
        assertEquals("partitionKey", refused.getMessage());
    }
}
