package io.flottila.core.flow;

import io.flottila.api.Ack;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.StepFactory;
import io.flottila.core.admission.Targets;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.node.FencedException;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.ShrinkingMode;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P2-15b (D-101 (2), D-112) as a property: whatever the fence's schedule — its first end, renewals that move it (a renew may
 * come late, after the fence closed, and reopen it) — and whenever the partner answers, a worker's send is never in progress
 * while the node's fence is closed. The run's wait for its send ends exactly when the earlier of these comes: the partner's
 * answer while the fence is open (the run goes on with the reply), the fence's closing (the run fails with a
 * {@link FencedException}). Driven on the fake clock in 50 ms steps; at each step the timers due fire first (at their own
 * instants), then the step's renewals and answer. The message's own deadline is far (10 min) and plays no part.
 *
 * <p>Not covered here: a fence that closes by supersession (openNanos drops to 0 between two timer readings) — the design
 * re-reads only at the timer, and a superseded node fail-stops; see the handback.
 */
class SendFencePropertiesTest {

    static final Instant T0 = FakeRuntime.T0;
    static final long STEP_MS = 50;
    static final Map<String, EndpointConfig> ENDPOINTS = Map.of("partner", new EndpointConfig("partner", URI.create("partner://p:1/in"), Map.of()));

    /** A renewal at step {@code at}: the fence's end moves to {@code max(end, at + by)}. */
    record Renewal(int at, long byMs) {}

    record Schedule(long firstEndMs, List<Renewal> renewals, Integer answerAt) {}

    @Provide
    Arbitrary<Schedule> schedules() {
        Arbitrary<Long> firstEnd = Arbitraries.integers().between(1, 300).map(n -> n * STEP_MS);
        Arbitrary<Renewal> renewal = Combinators.combine(Arbitraries.integers().between(0, 400), Arbitraries.longs().between(1, 15_000))
                .as(Renewal::new);
        Arbitrary<List<Renewal>> renewals = renewal.list().ofMaxSize(6);
        Arbitrary<Integer> answer = Arbitraries.integers().between(0, 700).injectNull(0.3);
        return Combinators.combine(firstEnd, renewals, answer).as(Schedule::new);
    }

    @Property(tries = 60, shrinking = ShrinkingMode.OFF)   // a red try waits out a 5 s bound per check: no shrinking rounds
    void noSendIsInProgressWhileTheFenceIsClosed(@ForAll("schedules") Schedule schedule) throws Exception {
        FakeRuntime rt = FakeRuntime.create();
        DeadlinePartner partner = new DeadlinePartner();
        rt.withComponent(partner);
        InMemoryWorkQueue queue = new InMemoryWorkQueue(rt.clock());
        List<String> ended = new CopyOnWriteArrayList<>();   // what the send's step ended with, and when
        List<RuntimeException> failures = new CopyOnWriteArrayList<>();
        StepFactory probe = ParkingTest.factory("probe", s -> (e, rest) -> {
            try {
                Envelope out = rest.apply(e);
                ended.add("reply at " + rt.clock().instant());
                return out;
            } catch (RuntimeException failure) {
                failures.add(failure);
                ended.add(failure.getClass().getSimpleName() + " at " + rt.clock().instant());
                throw failure;
            }
        });
        ClockFence fence = new ClockFence(rt.clock(), T0.plusMillis(schedule.firstEndMs()));
        FlowBuilder b = new FlowBuilder(rt.runtime(), List.of(probe), List.of(BuiltInStepsTest.HEADERS), ENDPOINTS)
                .recording(new InMemoryMessageStore(), Meter.system()).queue(queue).dlq(new InMemoryDlq())
                .targets(new Targets(Targets.DEFAULTS, Map.of(), rt.timer())).fence(fence);
        FlowDefinition def = FlowBuilderSendDeadlineTest.flow("at-least-once", "10m",
                List.of(Map.of("type", "probe", "name", "probe"), FlowBuilderSendDeadlineTest.TO));
        Envelope in = rt.ingress("orders", Headers.empty(), Buffer.buffer("<order/>"));
        assertEquals(new Ack.Accepted(in.id()), b.delivery(def, FlowWorkerTest.NO_FILES).apply(in).await(5, SECONDS));
        FlowWorker worker = b.worker(def, FlowWorkerTest.NO_FILES, "n1", FlowWorkerTest.policy(Duration.ofMinutes(5), 5));
        try {
            Future<Integer> round = worker.poll();
            spin(() -> partner.calls.size() == 1, () -> "the send started with the fence open: " + partner.calls + ", ended " + ended);

            long end = schedule.firstEndMs();   // the model of the fence's end, in ms after T0
            int last = schedule.renewals().stream().mapToInt(Renewal::at).max().orElse(0);
            int horizon = (int) Math.max(last + 15_000 / STEP_MS + 2, end / STEP_MS + 2);
            if (schedule.answerAt() != null) horizon = Math.max(horizon, schedule.answerAt() + 1);
            boolean running = true;
            for (int step = 0; step <= horizon && running; step++) {
                long t = step * STEP_MS;
                to(rt, Duration.ofMillis(t));
                if (end <= t) {   // closed at or before this instant, before this step's renewals: the run must have stopped waiting
                    long closedAt = end;
                    spin(() -> !ended.isEmpty(), () -> "a send still in progress at T0+" + t + " ms, its fence closed at T0+" + closedAt + " ms — " + schedule);
                    assertEquals(1, failures.size(), "the run ended at the fence's close: " + ended + " — " + schedule);
                    assertInstanceOf(FencedException.class, failures.get(0), "abandoned at the fence: fenced — " + ended + " — " + schedule);
                    running = false;
                    continue;
                }
                for (Renewal r : schedule.renewals()) if (r.at() == step) end = Math.max(end, t + r.byMs());
                fence.renewTo(T0.plusMillis(end));
                if (schedule.answerAt() != null && schedule.answerAt() == step) {
                    partner.call(0).reply();
                    long openUntil = end;
                    spin(() -> !ended.isEmpty(), () -> "the run took the reply given at T0+" + t + " ms with the fence open — " + schedule);
                    assertEquals(List.of(), failures, "answered while the fence was open (until T0+" + openUntil + " ms): the reply, not a failure — " + schedule);
                    running = false;
                    continue;
                }
                if (!ended.isEmpty()) fail("the run stopped waiting at T0+" + t + " ms though its fence is open until T0+" + end + " ms and no answer came: " + ended + " — " + schedule);
            }
            if (running) fail("the schedule ended with the send still in progress: " + schedule);
            round.await(10, SECONDS);
            assertEquals(1, partner.calls.size(), "one send, never retried: " + partner.calls.size() + " — " + schedule);
            assertEquals(List.of(), partner.plainCalls);
        } finally {
            partner.replyAll();
            worker.stop();
        }
    }

    static void to(FakeRuntime rt, Duration sinceT0) {
        Instant target = T0.plus(sinceT0);
        while (rt.timer().pending() > 0 && !rt.clock().instant().plus(rt.timer().nextDelay()).isAfter(target)) rt.clock().advance(rt.timer().nextDelay());
        rt.clock().set(target);
    }

    static void spin(BooleanSupplier condition, java.util.function.Supplier<String> what) {
        long deadline = System.nanoTime() + SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) fail("not within 5 s: " + what.get());
            Thread.onSpinWait();
        }
    }
}
