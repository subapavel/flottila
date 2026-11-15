package io.flottila.testkit;

import io.flottila.api.Component;
import io.flottila.api.EndpointConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The suite's wait helper: spins (never sleeps) until the condition holds, and gives up after the patience. */
class ComponentContractSoonTest {

    /** A suite instance for the helper alone; its cases are not what this test runs. */
    static final class Impatient extends ComponentContract {
        @Override protected Component component() { throw new UnsupportedOperationException(); }
        @Override protected EndpointConfig endpoint() { throw new UnsupportedOperationException(); }
        @Override protected Duration patience() { return Duration.ofMillis(20); }
    }

    @Test
    void returnsTrueOnceTheConditionHoldsAndFalseWhenThePatienceIsSpent() {
        Impatient suite = new Impatient();
        AtomicInteger polls = new AtomicInteger();
        assertTrue(suite.soon(() -> polls.incrementAndGet() >= 3), "held on the third poll");
        assertEquals(3, polls.get());
        assertFalse(suite.soon(() -> false), "20 ms of patience spent without the condition");
        ComponentContract defaults = new ComponentContract() {
            @Override protected Component component() { throw new UnsupportedOperationException(); }
            @Override protected EndpointConfig endpoint() { throw new UnsupportedOperationException(); }
        };
        assertEquals(Duration.ofSeconds(10), defaults.patience(), "a real transport gets ten seconds by default");
    }
}
