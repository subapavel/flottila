package dev.rail8.testkit;

import dev.rail8.api.Component;
import dev.rail8.api.EndpointConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

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

    /**
     * The default patience, for the helpers' true outcomes: they answer on the third poll and wait for nothing, and a slow
     * scheduler cannot spend ten seconds before the third poll as it could spend 20 ms.
     */
    static final class Patient extends ComponentContract {
        @Override protected Component component() { throw new UnsupportedOperationException(); }
        @Override protected EndpointConfig endpoint() { throw new UnsupportedOperationException(); }
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

    /** A polled value that counts its polls: {@code before} on the first two, {@code from} from the third poll on. */
    private static IntSupplier fromTheThirdPoll(AtomicInteger polls, int before, int from) {
        return () -> polls.incrementAndGet() >= 3 ? from : before;
    }

    @Test
    void soonEqualsAnswersTrueOnceThePolledValueEqualsAndFalseWhenThePatienceIsSpent() {
        Patient patient = new Patient();
        AtomicInteger polls = new AtomicInteger();
        assertTrue(patient.soonEquals(fromTheThirdPoll(polls, 0, 7), 7), "7 is polled from the third poll on");
        assertEquals(3, polls.get(), "0 on the first two polls is not 7; the third poll answers");
        assertFalse(new Impatient().soonEquals(fromTheThirdPoll(new AtomicInteger(), 0, 6), 7), "20 ms of patience spent while the value stays below 7");
    }

    @Test
    void soonEqualsIsNeverSatisfiedByAValueAboveTheExpectedOne() {
        assertFalse(new Impatient().soonEquals(() -> 10, 7), "10 from the first poll on: at least 7, never 7");
    }

    @Test
    void soonAtLeastAnswersTrueOnceThePolledValueReachesTheLeastAndFalseWhenThePatienceIsSpent() {
        Patient patient = new Patient();
        AtomicInteger polls = new AtomicInteger();
        assertTrue(patient.soonAtLeast(fromTheThirdPoll(polls, 0, 7), 7), "7 is polled from the third poll on: at least 7");
        assertEquals(3, polls.get(), "0 on the first two polls is below 7; the third poll answers");
        assertFalse(new Impatient().soonAtLeast(fromTheThirdPoll(new AtomicInteger(), 0, 6), 7), "20 ms of patience spent while the value stays below 7");
    }

    @Test
    void soonAtLeastIsSatisfiedByAValueAboveTheLeast() {
        Patient patient = new Patient();
        AtomicInteger polls = new AtomicInteger();
        assertTrue(patient.soonAtLeast(fromTheThirdPoll(polls, 0, 10), 7), "0, 0, then 10: above 7 is at least 7, though not 7");
        assertEquals(3, polls.get(), "the jump past 7 on the third poll answers");
    }

    @Test
    void soonPresentAnswersTrueOnceThePolledValueIsNonNullAndFalseWhenThePatienceIsSpent() {
        Patient patient = new Patient();
        AtomicInteger polls = new AtomicInteger();
        Supplier<String> value = () -> polls.incrementAndGet() >= 3 ? "there" : null;
        assertTrue(patient.soonPresent(value), "non-null from the third poll on");
        assertEquals(3, polls.get(), "null on the first two polls is absent; the third poll answers");
        assertFalse(new Impatient().soonPresent(() -> null), "20 ms of patience spent while the value stays null");
    }
}
