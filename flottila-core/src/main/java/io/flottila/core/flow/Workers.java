package io.flottila.core.flow;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The node's started {@link FlowWorker}s by flow (D-121 (a), P2-16a): one registry per node, handed to the builder that builds
 * them ({@link FlowBuilder#workers}) and to the node's {@link Intake}. A worker registers at its {@link FlowWorker#start()} and
 * leaves at its {@link FlowWorker#stop()} — a worker started later for the same flow (a redeploy) takes the flow's place, and
 * the earlier one's stop leaves it there. The intake asks {@link #reserve} for a slot of the flow's worker before its insert,
 * so the item it saves is written leased to the node and run by that slot after the commit, with no claim; an item saved
 * without a slot is a plain row, and {@link #wake} tells the worker after the commit that rows wait for a claim.
 *
 * @since 0.1
 */
public final class Workers {

    private final Map<String, FlowWorker> started = new ConcurrentHashMap<>();

    /** A registry with no worker in it. */
    public Workers() {
    }

    /**
     * A slot of {@code flow}'s started worker, reserved until it is run or cancelled: empty when no worker of {@code flow} is
     * started here, or its fence is closed, it is stopped, it has no room left ({@code max − running − reserved}, less what a claim
     * in flight asked for) or it knows of rows waiting for a claim (a new arrival never overtakes a spilled row).
     */
    public Optional<FlowWorker.Slot> reserve(String flow) {
        FlowWorker worker = started.get(Objects.requireNonNull(flow, "flow"));
        return worker == null ? Optional.empty() : worker.reserve();
    }

    /**
     * Tells {@code flow}'s started worker, if any, that rows of it wait for a claim: it claims now when it has room, else at the
     * end of a run (an enqueue without a hand-off: a spilled save, a released hold).
     */
    public void wake(String flow) {
        FlowWorker worker = started.get(Objects.requireNonNull(flow, "flow"));
        if (worker != null) worker.wake("on a wake of flow " + flow);
    }

    /** {@link #wake} for every flow with a started worker here. */
    public void wakeAll() {
        for (FlowWorker worker : started.values()) worker.wake("on a wake of every flow");
    }

    /** {@code worker} runs {@code flow} here from now on, in the place of any worker started for it before. */
    void register(String flow, FlowWorker worker) {
        started.put(flow, worker);
    }

    /** {@code worker} stopped: it leaves {@code flow}'s place, unless a worker started later holds it. */
    void deregister(String flow, FlowWorker worker) {
        started.remove(flow, worker);
    }
}
