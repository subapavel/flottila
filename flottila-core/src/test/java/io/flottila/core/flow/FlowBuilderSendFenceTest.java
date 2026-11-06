package io.flottila.core.flow;

import io.flottila.api.Ack;
import io.flottila.api.Component;
import io.flottila.api.Consumer;
import io.flottila.api.Deadline;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Health;
import io.flottila.api.Producer;
import io.flottila.api.Runtime;
import io.flottila.api.Stage;
import io.flottila.api.StepFactory;
import io.flottila.api.Timer;
import io.flottila.core.admission.Targets;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.dlq.Parked;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.core.record.Page;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static io.flottila.core.flow.FlowWorkerTest.NO_FILES;
import static io.flottila.core.flow.FlowWorkerTest.until;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-15b (D-113 draft), beside {@link FlowBuilderSendDeadlineTest}: what the fence's timer of a worker's send does when the send
 * never starts or ends early. The fence's re-read is the only timer task of the fence's length here: the fence is open for
 * {@link #FENCE} from T0 and the timer below counts each schedule of exactly that delay ({@code armed}) and each cancel of one
 * ({@code cancelled}). A send refused for its deadline arms nothing; a producer that throws or answers no future leaves no timer behind; a re-read
 * that fires after its send settled (its cancel came too late) arms no other.
 */
@Timeout(60)
class FlowBuilderSendFenceTest {

    static final Instant T0 = FakeRuntime.T0;
    static final Duration FENCE = Duration.ofSeconds(17);
    static final Map<String, Object> ONCE = Map.of("type", "to", "name", "partner", "endpoint", "partner");
    static final Map<String, Object> TICK = Map.of("type", "tick", "name", "slow");
    static final Map<String, EndpointConfig> ENDPOINTS = Map.of("partner", new EndpointConfig("partner", URI.create("partner://p:1/in"), Map.of()));

    final FakeRuntime rt = FakeRuntime.create();
    final InMemoryWorkQueue queue = new InMemoryWorkQueue(rt.clock());
    final InMemoryDlq dlq = new InMemoryDlq();
    final AtomicInteger armed = new AtomicInteger();
    final AtomicInteger cancelled = new AtomicInteger();
    /** While true, a cancel of the fence's timer reaches nothing: the task fires as if its cancel came too late. */
    volatile boolean lateCancels;
    final List<FlowWorker> workers = new CopyOnWriteArrayList<>();
    final List<Runnable> cleanups = new CopyOnWriteArrayList<>();
    final ClockFence fence = new ClockFence(rt.clock(), T0.plus(FENCE));

    @AfterEach
    void tearDown() {
        cleanups.forEach(Runnable::run);
        workers.forEach(FlowWorker::stop);
    }

    /** The runtime's timer, the fence's schedules counted (see the class comment). */
    Runtime runtime() {
        Runtime r = rt.runtime();
        Timer counting = (delay, task) -> {
            Timer.Cancellable c = rt.timer().schedule(delay, task);
            if (!delay.equals(FENCE)) return c;
            armed.incrementAndGet();
            return () -> {
                cancelled.incrementAndGet();
                return !lateCancels && c.cancel();
            };
        };
        return new Runtime(r.clock(), counting, r.ids(), r.blobs(), r.hasher(), r.components());
    }

    /** An at-least-once flow of {@code timeout}, its {@code steps} after a message was accepted, and its worker, under the fence. */
    FlowWorker accepted(Component partner, String timeout, List<Map<String, Object>> steps) throws Exception {
        rt.withComponent(partner);
        StepFactory tick = ParkingTest.factory("tick", s -> Stage.of(e -> { rt.clock().advance(Duration.ofMillis(500)); return e; }));
        FlowBuilder b = new FlowBuilder(runtime(), List.of(tick), List.of(BuiltInStepsTest.HEADERS), ENDPOINTS)
                .recording(new InMemoryMessageStore(), Meter.system()).queue(queue).dlq(dlq)
                .targets(new Targets(Targets.DEFAULTS, Map.of(), rt.timer())).fence(fence);
        FlowDefinition def = FlowBuilderSendDeadlineTest.flow("at-least-once", timeout, steps);
        Envelope in = rt.ingress("orders", Headers.empty(), Buffer.buffer("<order/>"));
        assertEquals(new Ack.Accepted(in.id()), b.delivery(def, NO_FILES).apply(in).await(5, SECONDS));
        FlowWorker w = b.worker(def, NO_FILES, "n1", FlowWorkerTest.policy(Duration.ofMinutes(5), 5));
        workers.add(w);
        return w;
    }

    List<Parked> parked() { return dlq.list(Optional.empty(), Page.MAX_LIMIT).await().items(); }

    @Test
    void aWorkersSendPastTheMessageDeadlineArmsNoFenceTimer() throws Exception {
        DeadlinePartner partner = new DeadlinePartner();
        cleanups.add(partner::replyAll);
        FlowWorker w = accepted(partner, "500ms", List.of(TICK, ONCE));   // the tick takes all of the message's 500 ms

        assertEquals(1, w.poll().await(10, SECONDS));
        until(() -> parked().size() == 1, () -> "the refused send parked at the to (the fence open): " + parked());
        assertTrue(parked().get(0).error().contains("deadline passed"), parked().get(0).error());
        assertEquals(List.of(), partner.calls, "the producer was not called");
        assertEquals(0, armed.get(), "no time left: the fence's re-read never armed");
    }

    @Test
    void aProducerThatThrowsLeavesNoFenceTimerBehind() throws Exception {
        IllegalStateException broke = new IllegalStateException("producer broke before sending anything");
        FlowWorker w = accepted(new Throwing(broke), "30s", List.of(ONCE));

        assertEquals(1, w.poll().await(10, SECONDS));
        until(() -> parked().size() == 1, () -> "the producer's own failure parked at the to: " + parked());
        assertTrue(parked().get(0).error().contains(broke.getMessage()), parked().get(0).error());
        assertEquals(1, armed.get(), "the fence's re-read armed just before the producer was called");
        assertEquals(1, cancelled.get(), "and cancelled when the producer threw: nothing re-reads a fence for a send that never left");
    }

    @Test
    void aProducerThatAnswersNoFutureLeavesNoFenceTimerBehind() throws Exception {
        FlowWorker w = accepted(new Throwing(null), "30s", List.of(ONCE));   // its send answers null

        assertEquals(1, w.poll().await(10, SECONDS));
        until(() -> parked().size() == 1, () -> "the null answer parked at the to: " + parked());
        assertTrue(parked().get(0).error().contains("answered no future"), parked().get(0).error());
        assertEquals(1, armed.get(), "the fence's re-read armed just before the producer was called");
        assertEquals(1, cancelled.get(), "and cancelled when the producer answered no future");

        fence.renewTo(T0.plus(FENCE).plus(FENCE));   // renewed: a re-read still pending would find the fence open and arm again
        rt.clock().advance(FENCE);
        assertEquals(1, armed.get(), "no re-read was left to fire: nothing re-arms for a send that never left");
    }

    @Test
    void anUnfencedRunsThrowingProducerFailsTheSendAsItThrewAndArmsNothing() throws Exception {
        IllegalStateException broke = new IllegalStateException("producer broke before sending anything");
        rt.withComponent(new Throwing(broke));
        FlowBuilder b = new FlowBuilder(runtime(), List.of(), List.of(BuiltInStepsTest.HEADERS), ENDPOINTS).fence(fence);
        io.flottila.api.Pipeline sync = b.build(FlowBuilderSendDeadlineTest.flow("sync", "30s", List.of(ONCE)), NO_FILES);   // a sync run: no Fence

        Throwable thrown = FlowBuilderTargetTest.failureOf(FlowBuilderTargetTest.run(sync, rt.ingress("orders", Headers.empty(), Buffer.buffer("<o/>"))));
        assertEquals(broke, thrown, "the producer's own exception, as it threw");
        assertEquals(0, armed.get(), "an unfenced run watches no fence");
    }

    @Test
    void aReReadThatFiresAfterItsSendSettledArmsNoOther() throws Exception {
        DeadlinePartner partner = new DeadlinePartner();
        cleanups.add(partner::replyAll);
        FlowWorker w = accepted(partner, "60s", List.of(ONCE));
        lateCancels = true;

        Future<Integer> round = w.poll();
        until(() -> partner.calls.size() == 1, () -> "the send reached the partner: " + partner.calls);
        partner.call(0).reply();
        assertEquals(1, round.await(10, SECONDS));
        until(() -> queue.size() == 0, () -> "the item completed: " + queue.snapshot());
        assertEquals(1, armed.get());
        assertEquals(1, cancelled.get(), "the send settled and cancelled its re-read, too late");

        fence.renewTo(T0.plus(FENCE).plus(FENCE));   // renewed: at the re-read the fence has FENCE left, as long as at the send
        assertEquals(T0, rt.clock().instant(), "fixture: the clock has not moved");
        rt.clock().advance(FENCE);   // the stale re-read fires, the fence still open
        assertEquals(1, armed.get(), "a re-read for a settled send stops there: it arms no other, though the fence is open");
        assertEquals(List.of(), parked());
    }

    /** A partner whose send throws {@code failure} before it starts anything; with none, answers no future (null). */
    static final class Throwing implements Component {
        final RuntimeException failure;

        Throwing(RuntimeException failure) { this.failure = failure; }

        @Override public String scheme() { return "partner"; }
        @Override public String configSchema() { return "{}"; }
        @Override public Optional<String> target(EndpointConfig cfg) { return Optional.of(DeadlinePartner.TARGET); }
        @Override public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) { throw new UnsupportedOperationException(); }
        @Override public Future<Health> health() { return Future.succeededFuture(Health.up()); }

        @Override public Producer producer(EndpointConfig cfg) {
            return new Producer() {
                @Override public Future<Envelope> send(Envelope e, Deadline d) { return send(e); }
                @Override public Future<Envelope> send(Envelope e) {
                    if (failure != null) throw failure;
                    return null;
                }
                @Override public Future<Void> close() { return Future.succeededFuture(); }
            };
        }
    }
}
