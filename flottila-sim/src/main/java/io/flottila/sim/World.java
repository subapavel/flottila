package io.flottila.sim;

import io.flottila.api.Ack;
import io.flottila.api.Envelope;
import io.flottila.api.Step;
import io.flottila.core.flow.Delivery;
import io.flottila.core.flow.Intake;
import io.flottila.core.queue.WorkQueue;
import io.vertx.core.Future;

import java.time.Clock;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The system a run simulates, as seams over what the simulation builds (D-101): the correct world takes each as it is; a broken
 * variant (src/test) replaces one through its public type only. The observers see what the driver does, never change it.
 */
public interface World {

    /** The correct world: every seam as the simulation builds it. */
    World CORRECT = () -> "correct";

    /** The name a verdict and a failure line carry ({@code world=correct}). */
    String name();

    /** The intake the at-least-once flow saves through, given the simulation's (with its STORE_REFUSE faults). */
    default Intake intake(Intake sim) {
        return sim;
    }

    /**
     * The work queue of the at-least-once flow, given the simulation's in-memory one and the run's clock. The simulation's claim
     * cap (a claim at most 1, {@code []} to a claim from any thread but the driver) wraps what this returns; the simulation reads
     * the queue only through {@link WorkQueue}.
     */
    default WorkQueue queue(WorkQueue sim, Clock clock) {
        return sim;
    }

    /** What a send to {@code flow} goes through, given a supplier that builds the flow's delivery (one admission each). */
    default Function<Envelope, Future<Ack>> entry(String flow, Supplier<Delivery> deliveries) {
        return deliveries.get();
    }

    /** The transform step of every flow, given the simulation's. Runs on the message's thread. */
    default Step transform(Step sim) {
        return sim;
    }

    /** Called on the driver as each unit starts. */
    default void onUnit(Unit unit) {}

    /** Called for each per-call fault decision, hit or not. */
    default void onDraw(Draw draw) {}

    /**
     * One unit of the driver.
     *
     * @param tick the tick it runs in
     * @param kind what it is
     * @param messageId the message it starts, releases, wakes or the poll claimed; empty for an ADVANCE or a poll that claimed nothing
     */
    record Unit(long tick, Kind kind, Optional<String> messageId) {

        /** The driver's units (D-101). */
        public enum Kind { START, POLL, RELEASE, WAKE, ADVANCE }
    }

    /**
     * One per-call fault decision: {@code mix(of(FAULTS), kind.code, messageId, attempt, callOrdinal) < rate}.
     *
     * @param tick the tick the call was made in
     * @param kind the fault drawn for
     * @param messageId the message making the call
     * @param attempt the message's attempt
     * @param callOrdinal the call's ordinal within the attempt
     * @param hit the fault is injected
     */
    record Draw(long tick, FaultKind kind, String messageId, int attempt, int callOrdinal, boolean hit) {}
}
