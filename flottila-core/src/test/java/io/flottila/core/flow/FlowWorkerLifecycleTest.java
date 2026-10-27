package io.flottila.core.flow;

import io.flottila.api.Envelope;
import io.flottila.api.Timer;
import io.flottila.core.flow.FlowWorkerTest.Logs;
import io.flottila.core.flow.FlowWorkerTest.World;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.Meter;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

import static io.flottila.core.flow.FlowWorkerTest.LEASE;
import static io.flottila.core.flow.FlowWorkerTest.NO_FILES;
import static io.flottila.core.flow.FlowWorkerTest.TICK;
import static io.flottila.core.flow.FlowWorkerTest.TO_PARTNER;
import static io.flottila.core.flow.FlowWorkerTest.flow;
import static io.flottila.core.flow.FlowWorkerTest.policy;
import static io.flottila.core.flow.FlowWorkerTest.until;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-08b, D-055: the worker's lifecycle edges the acceptance cases leave open — starting twice, starting after stop, a full
 * worker, and the races where a timer task or a renewal's answer arrives after what it served has ended. A timer task that
 * already fired cannot be cancelled: {@link ManualTimer} hands the test such tasks to run when it chooses.
 */
@Timeout(30)
class FlowWorkerLifecycleTest {

    World w;
    Logs logs;

    @BeforeEach
    void setUp() {
        w = new World();
        logs = Logs.attach();
    }

    @AfterEach
    void tearDown() {
        logs.detach();
        w.close();
    }

    /** Records every task and never runs one; its handles report the task as already fired, so a cancelled task still runs when the test runs it. */
    static final class ManualTimer implements Timer {
        final List<Runnable> tasks = new CopyOnWriteArrayList<>();

        @Override public Cancellable schedule(Duration delay, Runnable task) {
            tasks.add(task);
            return () -> false;
        }
    }

    /** {@link WorkQueue} over the world's recording queue whose renewals stay open until the test answers them. */
    static final class DeferredRenewals implements WorkQueue {
        final WorkQueue delegate;
        final List<Promise<Boolean>> open = new CopyOnWriteArrayList<>();

        DeferredRenewals(WorkQueue delegate) { this.delegate = delegate; }

        @Override public Future<Void> enqueue(String messageId, String flow, int step, String partitionKey) { return delegate.enqueue(messageId, flow, step, partitionKey); }
        @Override public Future<List<Item>> claim(String workerId, String flow, int max, Duration lease) { return delegate.claim(workerId, flow, max, lease); }
        @Override public Future<Void> complete(long seq) { return delegate.complete(seq); }
        @Override public Future<Optional<Item>> completeAndTakeNext(long seq, String workerId, Duration lease) { return delegate.completeAndTakeNext(seq, workerId, lease); }
        @Override public Future<Boolean> hold(long seq, int attempts) { return delegate.hold(seq, attempts); }
        @Override public Future<io.flottila.core.record.Page<Held>> held(Optional<String> after, int limit) { return delegate.held(after, limit); }
        @Override public Future<Boolean> release(long seq) { return delegate.release(seq); }
        @Override public Future<Boolean> hasItems(String flow) { return delegate.hasItems(flow); }

        @Override public Future<Boolean> renew(long seq, int attempts, Duration lease) {
            Promise<Boolean> answer = Promise.promise();
            open.add(answer);
            return answer.future();
        }
    }

    @Test
    void aWorkerNeedsADlqToParkAnItemPastTheBound() {
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER)));
        FlowBuilder noDlq = new FlowBuilder(w.rt.runtime(), List.of(w.gate, w.crash), List.of(BuiltInStepsTest.HEADERS), w.endpoints)
                .recording(w.store, Meter.system()).queue(w.queue);
        String refused = assertThrows(IllegalStateException.class, () -> noDlq.worker(def, NO_FILES, "w1", policy(LEASE, 5))).getMessage();
        assertTrue(refused.contains("DLQ") && refused.contains("dlq(...)"), refused);
    }

    @Test
    void aHoldThatFailsFailsTheRoundAndLeavesTheItemToItsLease() throws Exception {
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER)));
        FlowBuilder b = w.builder();
        w.accept(b.delivery(def, NO_FILES));
        w.rows.claim("crashed", "orders", 1, LEASE).await();   // attempt 1: a worker claimed it and died at once
        w.rt.clock().advance(LEASE);
        w.dlq.failParks(new IllegalStateException("dlq down"));
        IllegalStateException holdDown = new IllegalStateException("pg away");
        w.queue.failHolds(1, holdDown);
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, 1));

        Future<Integer> round = w1.poll();
        until(round::isComplete, () -> "the round of the unparkable item");
        assertTrue(round.failed(), "a hold that fails fails the round");
        assertEquals(holdDown, round.cause());
        assertEquals(1, w.rows.size(), "nothing completed, nothing held");
        w.rt.clock().advance(LEASE);
        w1.poll().otherwiseEmpty().await(5, SECONDS);
        assertEquals(List.of(2, 3), w.claimedAttempts(), "its slot freed, the item is claimed again after its lease");
    }

    @Test
    void startingTwiceTicksOnce() {
        FlowWorker w1 = w.worker(FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER))), "w1", policy(LEASE, 5));
        w1.start();
        w1.start();
        assertEquals(1, w.rt.timer().pending(), "one tick, however often the worker is started");
        w.rt.clock().advance(TICK);
        assertEquals(1, w.queue.claims().size(), "one claim per interval: " + w.queue.claims());
        assertEquals(1, w.rt.timer().pending());
    }

    @Test
    void aStoppedWorkerDoesNotStartAgain() throws Exception {
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER)));
        FlowWorker never = w.worker(def, "never", policy(LEASE, 5));
        never.stop();
        never.start();
        assertEquals(0, w.rt.timer().pending(), "stopped before it was ever started: start does nothing");

        FlowWorker w1 = w.worker(def, "w1", policy(LEASE, 5));
        w1.start();
        w1.stop();
        w1.start();
        assertEquals(0, w.rt.timer().pending(), "stopped after a start: a second start does nothing");
        for (int i = 0; i < 3; i++) w.rt.clock().advance(TICK);
        assertEquals(0, w1.poll().await(5, SECONDS), "a stopped worker starts no round, not even on an explicit poll");
        assertEquals(0, never.poll().await(5, SECONDS));
        assertEquals(List.of(), w.queue.claims());
    }

    @Test
    void aWorkerRunningItsLimitClaimsNothing() throws Exception {
        FlowDefinition def = FlowDefinition.from(flow(1, List.of(RunGate.STEP, TO_PARTNER)));
        FlowBuilder b = w.builder();
        Delivery delivery = b.delivery(def, NO_FILES);
        Envelope first = w.accept(delivery);
        Envelope second = w.accept(delivery);
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, 5));

        Future<Integer> round = w1.poll();
        until(() -> w.gate.entered() == 1 && w.queue.claimsInFlight() == 0, () -> "the first item at the gate");
        assertEquals(0, w1.poll().await(5, SECONDS), "running its limit of 1: no room");
        assertEquals(1, w.queue.claims().size(), "and no claim was issued: " + w.queue.claims());

        RunGate.Run run = w.gate.runs().get(0);
        run.release();
        assertEquals(1, round.await(5, SECONDS));
        run.joined();
        assertEquals(2, w.queue.claims().size(), "the finished item made room and polled");
        until(() -> w.gate.entered() == 2, () -> "the second item at the gate");
        assertEquals(List.of(first.id(), second.id()), w.gate.runs().stream().map(r -> r.messageId).toList());
    }

    @Test
    void aTickThatFiresAsTheWorkerStopsPollsNoMore() {
        ManualTimer timer = new ManualTimer();
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER)));
        FlowWorker w1 = w.worker(w.builder(w.runtimeWith(timer)), def, "w1", policy(LEASE, 5));
        w1.start();
        assertEquals(1, timer.tasks.size(), "the first tick");
        w1.stop();

        timer.tasks.get(0).run();   // it had fired already when stop cancelled it
        assertEquals(List.of(), w.queue.claims(), "no round after stop");
        assertEquals(1, timer.tasks.size(), "and no further tick");
    }

    @Test
    void aRenewalThatFiresAsTheRunEndsRenewsNothing() {
        ManualTimer timer = new ManualTimer();
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER)));
        Envelope in = w.accept(w.builder().delivery(def, NO_FILES));   // its save timeout on the fake timer, not on this one
        FlowWorker w1 = w.worker(w.builder(w.runtimeWith(timer)), def, "w1", policy(LEASE, 5));

        w1.poll();
        until(() -> w.gate.entered() == 1 && timer.tasks.size() == 1, () -> "the run at the gate with its renewal scheduled");
        RunGate.Run run = w.gate.runs().get(0);
        run.release();
        run.joined();
        assertEquals(List.of(in.id()), w.sentIds());

        timer.tasks.get(0).run();   // it had fired already when the run's end cancelled it
        assertEquals(List.of(), w.queue.renews(), "a run that ended renews nothing");
        assertEquals(1, timer.tasks.size(), "and schedules nothing");
    }

    @Test
    void aRenewalAnsweredAfterTheRunEndedIsIgnored() throws Exception {
        DeferredRenewals queue = new DeferredRenewals(w.queue);
        FlowDefinition def = FlowDefinition.from(flow(4, List.of(RunGate.STEP, TO_PARTNER)));
        FlowBuilder b = new FlowBuilder(w.rt.runtime(), List.of(w.gate, w.crash), List.of(BuiltInStepsTest.HEADERS), w.endpoints)
                .recording(w.store, Meter.system()).queue(queue).dlq(w.dlq);
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, 5));

        Future<Integer> round = w1.poll();
        until(() -> w.gate.entered() == 1 && w.rt.timer().pending() == 1, () -> "the run at the gate with its renewal scheduled");
        w.rt.clock().advance(LEASE.dividedBy(3));
        assertEquals(1, queue.open.size(), "the renewal is in flight");
        assertEquals(0, w.rt.timer().pending());

        RunGate.Run run = w.gate.runs().get(0);
        run.release();
        assertEquals(1, round.await(5, SECONDS));
        run.joined();
        queue.open.get(0).complete(true);
        assertEquals(0, w.rt.timer().pending(), "the answer of a renewal for a run that ended schedules no further renewal");
        assertEquals(List.of(), logs.at(Level.WARNING, in.id()), "nor is it logged: " + logs.all());
        assertEquals(0, w.rows.size(), "the item completed");
    }
}
