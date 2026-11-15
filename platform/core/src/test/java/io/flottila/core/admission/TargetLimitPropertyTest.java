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

    // ---- FIX-115 (D-100): call(within, send), every waiter bounded by the earlier of maxWait and its own deadline -------------

    sealed interface Move permits Call, Answer, Tick {}
    /** A message of flow {@code flow} with {@code withinMillis} left until its deadline calls the target (zero or less: passed). */
    record Call(int flow, long withinMillis) implements Move {}
    /** The {@code k}-th send out at the partner (mod their number) is answered. */
    record Answer(int k) implements Move {}
    /** The clock moves on by {@code millis}. */
    record Tick(long millis) implements Move {}

    @Provide
    Arbitrary<List<Move>> moves() {
        Arbitrary<Move> call = net.jqwik.api.Combinators.combine(Arbitraries.integers().between(0, FLOWS - 1),
                Arbitraries.of(-5L, 0L, 1L, 40L, 99L, 101L, 250L)).as((f, w) -> (Move) new Call(f, w));
        Arbitrary<Move> answer = Arbitraries.integers().between(0, 7).map(k -> (Move) new Answer(k));
        Arbitrary<Move> tick = Arbitraries.of(0L, 1L, 39L, 40L, 60L, 99L, 100L, 101L).map(ms -> (Move) new Tick(ms));
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(5, call),
                net.jqwik.api.Tuple.of(3, answer),
                net.jqwik.api.Tuple.of(3, tick)).list().ofMinSize(1).ofMaxSize(40);
    }

    /** One call on a virtual thread of its own: when, with how much time left, whether its send ran, and how the call ended. */
    static final class Caller {
        final int index;
        final java.time.Instant at;
        final long within;
        final io.vertx.core.Promise<String> reply = io.vertx.core.Promise.promise();
        final java.util.concurrent.CompletableFuture<String> outcome = new java.util.concurrent.CompletableFuture<>();
        volatile boolean sent;
        boolean answered;
        Thread thread;
        Caller(int index, java.time.Instant at, long within) { this.index = index; this.at = at; this.within = within; }
        String state() { return sent ? "granted" : outcome.isCompletedExceptionally() ? "refused" : "waiting"; }
        @Override public String toString() { return "#" + index + "@" + at + " within " + within + "ms " + state() + (answered ? " answered" : ""); }
    }

    /**
     * A model of {@code call(within, send)} over random calls of several flows on one target, random answers of the sends out
     * and random clock moves: a free permit goes to a caller only when nobody waits — whatever its deadline, a passed one too;
     * a caller that must wait with no time left is refused at once with "the message's deadline has passed", no bound armed;
     * every other waiter is refused exactly when the earlier of maxWait and its own deadline has passed since it called — not
     * 1 ms earlier — and says which bounded it; a freed permit goes to the earliest waiter still waiting (FIFO across flows).
     * After every move each caller's state, the gauge (in flight, waiting, refused) and the bounds pending on the timer equal the
     * model's. Each call blocks its own virtual thread; the test waits for the limit to be quiet (every caller sent, refused or
     * counted as waiting, and the permits out = the sends not answered) before it compares.
     */
    @Property(tries = 200)
    void noWaiterWaitsPastTheEarlierOfMaxWaitAndItsDeadline(@ForAll @IntRange(min = 1, max = 3) int maxCalls, @ForAll("moves") List<Move> moves) throws Exception {
        FakeClock clock = FakeClock.at(Instant.parse("2026-01-01T00:00:00Z"));
        FakeTimer timer = new FakeTimer(clock);
        Targets targets = new Targets(new Targets.Limit(maxCalls, WAIT), Map.of(), timer);
        List<TargetLimit> flows = new ArrayList<>();
        for (int f = 0; f < FLOWS; f++) flows.add(targets.of(T));
        TargetLimit limit = flows.get(0);
        List<Caller> callers = new ArrayList<>();
        List<String> expected = new ArrayList<>();   // per caller: "waiting", "granted", "refused"
        Map<Integer, String> refusedSays = new java.util.HashMap<>();   // per refused caller: how its reason ends
        Deque<Integer> queue = new ArrayDeque<>();   // waiting callers, in call order
        int held = 0;
        int refused = 0;
        try {
            for (Move move : moves) {
                switch (move) {
                    case Call c -> {
                        Caller x = new Caller(callers.size(), clock.instant(), c.withinMillis());
                        callers.add(x);
                        TargetLimit via = flows.get(c.flow());
                        x.thread = Thread.ofVirtual().name("caller-" + x.index).start(() -> {
                            try {
                                x.outcome.complete(via.call(Duration.ofMillis(x.within), () -> {
                                    x.sent = true;
                                    return x.reply.future();
                                }));
                            } catch (Throwable t) {
                                x.outcome.completeExceptionally(t);
                            }
                        });
                        if (queue.isEmpty() && held < maxCalls) {
                            expected.add("granted");
                            held++;
                        } else if (x.within <= 0) {
                            expected.add("refused");
                            refused++;
                            refusedSays.put(x.index, "the message's deadline has passed");
                        } else {
                            expected.add("waiting");
                            queue.addLast(x.index);
                        }
                    }
                    case Answer a -> {
                        List<Caller> out = callers.stream().filter(x -> x.sent && !x.answered).toList();
                        if (!out.isEmpty()) {
                            Caller x = out.get(a.k() % out.size());
                            x.answered = true;
                            x.reply.complete("reply " + x.index);
                            held--;
                            if (!queue.isEmpty()) {
                                expected.set(queue.pollFirst(), "granted");
                                held++;
                            }
                        }
                    }
                    case Tick t -> {
                        clock.advance(Duration.ofMillis(t.millis()));
                        Instant now = clock.instant();
                        for (java.util.Iterator<Integer> it = queue.iterator(); it.hasNext(); ) {
                            Caller x = callers.get(it.next());
                            boolean deadlineFirst = x.within < WAIT.toMillis();
                            Duration bound = deadlineFirst ? Duration.ofMillis(x.within) : WAIT;
                            if (!x.at.plus(bound).isAfter(now)) {
                                it.remove();
                                expected.set(x.index, "refused");
                                refused++;
                                refusedSays.put(x.index, deadlineFirst ? "waited " + Duration.ofMillis(x.within) + " until the message's deadline" : "waited " + WAIT);
                            }
                        }
                    }
                }
                quiet(limit, callers, move);
                for (Caller x : callers) {
                    assertEquals(expected.get(x.index), x.state(), "caller #" + x.index + " (at " + x.at + ", within " + x.within + " ms, now " + clock.instant()
                            + ") after " + move + ": " + callers);
                    if (x.outcome.isCompletedExceptionally()) {
                        Throwable cause = x.outcome.handle((v, e) -> e).get();
                        NackException busy = assertInstanceOf(NackException.class, cause, "a refusal is a NackException");
                        assertTrue(busy.retryable(), "busy is retryable: " + busy.reason());
                        assertTrue(busy.reason().startsWith("target " + T + " busy: "), busy.reason());
                        assertTrue(busy.reason().endsWith(refusedSays.get(x.index)), "caller #" + x.index + " says '" + refusedSays.get(x.index) + "': " + busy.reason());
                    }
                }
                var g = limit.gauge();
                assertEquals(held, (long) g.inFlight(), "gauge in flight after " + move + ": " + callers);
                assertEquals(queue.size(), (long) g.waiting(), "gauge waiting after " + move + ": " + callers);
                assertEquals(refused, (long) g.refused(), "gauge refused after " + move + ": " + callers);
                assertEquals(queue.size(), timer.pending(), "one bound pending per waiter, none for a caller refused at once or granted, after " + move + ": " + callers);
            }
        } finally {
            drain(clock, limit, callers);
        }
        assertTrue(callers.stream().allMatch(x -> x.outcome.isDone()), "every caller answered: " + callers);
        assertEquals(0, (long) limit.gauge().inFlight(), "nothing in flight at the end: " + callers);
        assertEquals(0, (long) limit.gauge().waiting(), "nothing waiting at the end: " + callers);
    }

    /**
     * Waits until the limit is quiet after {@code move}: every caller has sent, been refused or is counted as waiting, and the
     * permits out are the sends not yet answered (an answered send's permit was given back or handed on, and the waiter handed
     * it has sent). Never longer than 5 s.
     */
    static void quiet(TargetLimit limit, List<Caller> callers, Move move) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (true) {
            var g = limit.gauge();
            long sent = callers.stream().filter(x -> x.sent).count();
            long failed = callers.stream().filter(x -> x.outcome.isCompletedExceptionally()).count();
            long out = callers.stream().filter(x -> x.sent && !x.answered).count();
            if (sent + failed + g.waiting() == callers.size() && g.inFlight() == out) return;
            if (System.nanoTime() - deadline > 0) {
                throw new AssertionError("not quiet within 5 s after " + move + ": sent " + sent + ", refused " + failed + ", " + g + ", out " + out + ": " + callers);
            }
            Thread.onSpinWait();
        }
    }

    /**
     * Best effort, at most 5 s in all, never throwing (a failed property keeps its own failure): answers every send out, moves
     * the clock by maxWait until every caller has ended (each waiter's bound is at most maxWait), then interrupts any caller
     * still alive and waits for its thread.
     */
    static void drain(FakeClock clock, TargetLimit limit, List<Caller> callers) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() - deadline < 0 && !callers.stream().allMatch(x -> x.outcome.isDone())) {
            callers.stream().filter(x -> x.sent).forEach(x -> x.reply.tryComplete("drained"));
            long sent = callers.stream().filter(x -> x.sent).count();
            long failed = callers.stream().filter(x -> x.outcome.isCompletedExceptionally()).count();
            if (sent + failed + limit.gauge().waiting() == callers.size() && limit.gauge().inFlight() == 0) clock.advance(WAIT);
            Thread.onSpinWait();
        }
        for (Caller x : callers) {
            if (x.thread == null) continue;
            if (!x.thread.join(Duration.ofMillis(100))) x.thread.interrupt();
            x.thread.join(Duration.ofSeconds(1));
        }
    }
}
