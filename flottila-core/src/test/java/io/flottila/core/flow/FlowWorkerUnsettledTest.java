package io.flottila.core.flow;

import io.flottila.core.flow.FlowWorkerTest.World;
import io.vertx.core.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static io.flottila.core.flow.FlowWorkerTest.LEASE;
import static io.flottila.core.flow.FlowWorkerTest.NO_FILES;
import static io.flottila.core.flow.FlowWorkerTest.TO_PARTNER;
import static io.flottila.core.flow.FlowWorkerTest.flow;
import static io.flottila.core.flow.FlowWorkerTest.policy;
import static io.flottila.core.flow.FlowWorkerTest.until;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** FIX-142: {@link FlowWorker#unsettled()} counts the items the worker claimed and still runs — what its close still waits for. */
@Timeout(30)
class FlowWorkerUnsettledTest {

    final World w = new World();

    @AfterEach
    void tearDown() {
        w.close();
    }

    /**
     * Two messages queued, both claimed by one round and held at the gate: 0 before the claim, 2 while both run (kills a
     * constant 0 and a count off by one either way), 1 once the first run ended and settled while the close still waits for
     * the second, 0 once the worker closed.
     */
    @Test
    void unsettledCountsEachClaimedItemUntilItsRunSettles() throws Exception {
        FlowDefinition def = FlowDefinition.from(flow(2, List.of(RunGate.STEP, TO_PARTNER)));
        FlowBuilder b = w.builder();
        Delivery delivery = b.delivery(def, NO_FILES);
        w.accept(delivery);
        w.accept(delivery);
        FlowWorker w1 = w.worker(b, def, "w1", policy(LEASE, 5));
        assertEquals(0, w1.unsettled(), "nothing claimed yet");

        Future<Integer> round = w1.poll();
        until(() -> w.gate.entered() == 2, () -> "both items at the gate");
        assertEquals(2, w1.unsettled(), "both claimed items still run");
        w1.stop();
        Future<Void> closed = w1.close();

        w.gate.runs().get(0).release();
        until(() -> w1.unsettled() == 1, () -> "the first run ended and settled; unsettled " + w1.unsettled());
        assertFalse(closed.isComplete(), "the close still waits for the other item");
        assertEquals(1, w1.unsettled(), "the second item still runs at the gate");
        w.gate.runs().get(1).release();
        assertEquals(2, round.await(5, SECONDS), "the round claimed both items");
        closed.await(5, SECONDS);
        assertEquals(0, w1.unsettled(), "nothing runs once the worker closed");
        assertEquals(2, w.sentIds().size(), "both items reached the partner: " + w.sentIds());
    }
}
