package dev.rail8.core.flow;

import java.time.Duration;
import java.util.Objects;

/**
 * How a {@link FlowWorker} holds and drives its items; the default numbers are not measured yet. How often an item runs
 * before it is parked is set by the flow's schedule of retries ({@link FlowBuilder#retries}), not by this policy.
 *
 * @param lease how long a claim leases an item; renewed every third of it while the item runs
 * @param pollInterval how often a {@linkplain FlowWorker#start() started} worker reconciles: one claim every interval, the first
 *                     an interval plus {@code floorMod(flow.hashCode(), interval in ms)} ms after its start, so the flows of one
 *                     node do not claim together (work events wake it in between, {@link WorkSignals})
 * @param maxChain how many items of one key a worker takes by the same-key fast path after the item it claimed before the key
 *                 goes back to the seq-ordered claim (at least 0, 0 = the fast path is off; the default 32 is not measured)
 * @since 0.1
 */
public record WorkerPolicy(Duration lease, Duration pollInterval, int maxChain) {

    /** 30 s lease, a reconciliation every 30 s, a chain of at most 32 fast-path items. */
    public static final WorkerPolicy DEFAULT = new WorkerPolicy(Duration.ofSeconds(30), Duration.ofSeconds(30));

    public WorkerPolicy {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(pollInterval, "pollInterval");
        if (!lease.isPositive()) throw new IllegalArgumentException("lease must be positive, was " + lease);
        if (!pollInterval.isPositive()) throw new IllegalArgumentException("pollInterval must be positive, was " + pollInterval);
        if (maxChain < 0) throw new IllegalArgumentException("maxChain must be at least 0 (0: no fast path), was " + maxChain);
    }

    /** The policy with a chain of at most 32 fast-path items. */
    public WorkerPolicy(Duration lease, Duration pollInterval) {
        this(lease, pollInterval, 32);
    }
}
