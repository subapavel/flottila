package io.flottila.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * A degraded health (CORE-4c1 r1; the implementer's): its own status beside up and down, with the detail it was given — the
 * body journal reports it while a segment row is retried.
 */
class HealthDegradedTest {

    @Test
    void aDegradedHealthCarriesItsStatusAndDetail() {
        Health degraded = Health.degraded("a segment row is retried");

        assertEquals(new Health(Health.Status.DEGRADED, "a segment row is retried"), degraded);
        assertNotEquals(Health.up(), degraded);
        assertNotEquals(Health.down("a segment row is retried"), degraded);
    }
}
