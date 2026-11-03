package io.flottila.core.admission;

import io.flottila.api.NackException;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Future;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CLAUDE.md: property tests for admission — the per-target limit (D-099) over random arrivals of several flows naming one target,
 * random settles of the permits in flight and random advances of the fake clock (the injected timer runs maxWait). Single-threaded:
 * {@code acquire()} never blocks, a grant completes the waiter's future on the thread that freed the permit, a refusal on the
 * thread that advanced the clock. All state is built inside the property (jqwik runs no JUnit lifecycle).
 */
class TargetLimitPropertyTest {

    static final String T = "https://partner.example:443";
    static final Duration WAIT = Duration.ofMillis(100);
    static final int FLOWS = 3;

    sealed interface Op permits Arrive, Settle, Advance {}
    /** A message of flow {@code flow} asks for a permit. */
    record Arrive(int flow) implements Op {}
    /** The {@code k}-th send in flight (mod their number) settles: its permit is closed. */
    record Settle(int k) implements Op {}
    /** The clock moves on by {@code millis}: around maxWait on purpose (just before, at, just after). */
    record Advance(long millis) implements Op {}

    @Provide
    Arbitrary<List<Op>> ops() {
        Arbitrary<Op> arrive = Arbitraries.integers().between(0, FLOWS - 1).map(f -> (Op) new Arrive(f));
        Arbitrary<Op> settle = Arbitraries.integers().between(0, 7).map(k -> (Op) new Settle(k));
        Arbitrary<Op> advance = Arbitraries.of(0L, 1L, 40L, 99L, 100L, 101L).map(ms -> (Op) new Advance(ms));
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(5, arrive),
                net.jqwik.api.Tuple.of(3, settle),
                net.jqwik.api.Tuple.of(2, advance)).list().ofMinSize(1).ofMaxSize(80);
    }

    /** One arrival: the future acquire answered, when it arrived, and whether the test has closed its permit. */
    static final class Arrival {
        final int index;
        final Instant at;
        final Future<TargetLimit.Permit> answer;
        boolean closed;
        Arrival(int index, Instant at, Future<TargetLimit.Permit> answer) { this.index = index; this.at = at; this.answer = answer; }
        boolean held() { return answer.succeeded() && !closed; }
        @Override public String toString() { return "#" + index + "@" + at + (answer.succeeded() ? (closed ? " closed" : " held") : answer.failed() ? " refused" : " waiting"); }
    }

    /**
     * Two or more flows on one target never have more than {@code maxCalls} permits out at once, whatever the arrivals, settles
     * and clock moves; the gauge's in-flight count is the permits out; once every held permit is closed and the waiters are
     * granted or refused, nothing is in flight and nothing waits.
     */
    @Property(tries = 300)
    void inFlightNeverExceedsMaxCallsOverRandomArrivals(@ForAll @IntRange(min = 1, max = 4) int maxCalls, @ForAll("ops") List<Op> ops) {
        FakeClock clock = FakeClock.at(Instant.parse("2026-01-01T00:00:00Z"));
        FakeTimer timer = new FakeTimer(clock);
        Targets targets = new Targets(new Targets.Limit(maxCalls, WAIT), Map.of(), timer);
        List<TargetLimit> flows = new ArrayList<>();
        for (int f = 0; f < FLOWS; f++) flows.add(targets.of(T));   // every flow resolves its limit by the target it names
        TargetLimit limit = flows.get(0);
        List<Arrival> arrivals = new ArrayList<>();
        int peak = 0;
        for (Op op : ops) {
            switch (op) {
                case Arrive a -> arrivals.add(new Arrival(arrivals.size(), clock.instant(), flows.get(a.flow()).acquire()));
                case Settle s -> {
                    List<Arrival> held = arrivals.stream().filter(Arrival::held).toList();
                    if (!held.isEmpty()) close(held.get(s.k() % held.size()));
                }
                case Advance d -> clock.advance(Duration.ofMillis(d.millis()));
            }
            long out = arrivals.stream().filter(Arrival::held).count();
            peak = (int) Math.max(peak, out);
            assertTrue(out <= maxCalls, "in flight " + out + " > maxCalls " + maxCalls + " after " + op + ": " + arrivals);
            assertEquals(out, (long) limit.gauge().inFlight(), "the gauge counts the permits out after " + op + ": " + arrivals);
        }
        // drain: close what is out, let the waiters be granted (and close those) or refused past maxWait
        for (int round = 0; round <= arrivals.size() + 1; round++) {
            arrivals.stream().filter(Arrival::held).forEach(TargetLimitPropertyTest::close);
            if (arrivals.stream().noneMatch(x -> !x.answer.isComplete())) break;
            if (arrivals.stream().noneMatch(Arrival::held)) clock.advance(WAIT);
        }
        assertTrue(arrivals.stream().allMatch(x -> x.answer.isComplete()), "every arrival answered: " + arrivals);
        assertEquals(0, (long) limit.gauge().inFlight(), "nothing in flight at the end: " + arrivals);
        assertEquals(0, (long) limit.gauge().waiting(), "nothing waiting at the end: " + arrivals);
        assertTrue(peak <= maxCalls);
    }

    /**
     * A model of the limit: a free permit goes to the arrival only when nobody waits; a freed permit goes to the earliest waiter
     * (FIFO across flows); a waiter is refused exactly when maxWait has passed since it arrived — not one millisecond earlier —
     * with a retryable NackException naming the target and the calls in flight. After every op each arrival's state (waiting,
     * granted, refused) and the gauge (in flight, waiting, refused) equal the model's.
     */
    @Property(tries = 300)
    void grantsInArrivalOrderAndRefusesOnlyAfterMaxWait(@ForAll @IntRange(min = 1, max = 4) int maxCalls, @ForAll("ops") List<Op> ops) {
        FakeClock clock = FakeClock.at(Instant.parse("2026-01-01T00:00:00Z"));
        FakeTimer timer = new FakeTimer(clock);
        Targets targets = new Targets(new Targets.Limit(maxCalls, WAIT), Map.of(), timer);
        List<TargetLimit> flows = new ArrayList<>();
        for (int f = 0; f < FLOWS; f++) flows.add(targets.of(T));
        TargetLimit limit = flows.get(0);

        List<Arrival> arrivals = new ArrayList<>();
        // the model
        List<String> expected = new ArrayList<>();   // per arrival: "waiting", "granted", "refused"
        Deque<Integer> queue = new ArrayDeque<>();   // waiting arrivals, in arrival order
        int modelHeld = 0;
        int modelRefused = 0;

        for (Op op : ops) {
            switch (op) {
                case Arrive a -> {
                    int i = arrivals.size();
                    arrivals.add(new Arrival(i, clock.instant(), flows.get(a.flow()).acquire()));
                    if (queue.isEmpty() && modelHeld < maxCalls) {
                        expected.add("granted");
                        modelHeld++;
                    } else {
                        expected.add("waiting");
                        queue.addLast(i);
                    }
                }
                case Settle s -> {
                    List<Arrival> held = arrivals.stream().filter(Arrival::held).toList();
                    if (!held.isEmpty()) {
                        close(held.get(s.k() % held.size()));
                        modelHeld--;
                        if (!queue.isEmpty()) {
                            expected.set(queue.pollFirst(), "granted");
                            modelHeld++;
                        }
                    }
                }
                case Advance d -> {
                    clock.advance(Duration.ofMillis(d.millis()));
                    Instant now = clock.instant();
                    while (!queue.isEmpty() && !arrivals.get(queue.peekFirst()).at.plus(WAIT).isAfter(now)) {
                        expected.set(queue.pollFirst(), "refused");
                        modelRefused++;
                    }
                }
            }
            for (Arrival x : arrivals) {
                String actual = x.answer.succeeded() ? "granted" : x.answer.failed() ? "refused" : "waiting";
                assertEquals(expected.get(x.index), actual, "arrival #" + x.index + " (at " + x.at + ", now " + clock.instant() + ") after " + op + ": " + arrivals);
                if (x.answer.failed()) {
                    NackException busy = assertInstanceOf(NackException.class, x.answer.cause(), "a refusal is a NackException");
                    assertTrue(busy.retryable(), "busy is retryable: " + busy.reason());
                    assertTrue(busy.reason().startsWith("target " + T + " busy: " + maxCalls + " calls in flight, waited "), busy.reason());
                }
            }
            var g = limit.gauge();
            assertEquals(modelHeld, (long) g.inFlight(), "gauge in flight after " + op);
            assertEquals(queue.size(), (long) g.waiting(), "gauge waiting after " + op);
            assertEquals(modelRefused, (long) g.refused(), "gauge refused after " + op);
            assertEquals(T, g.target());
            assertEquals(maxCalls, (long) g.maxCalls());
            assertEquals(WAIT, g.maxWait());
        }
    }

    static void close(Arrival a) {
        a.closed = true;
        a.answer.result().close();
    }
}
