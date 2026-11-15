package io.flottila.core.flow;

import java.time.Duration;
import java.util.Objects;

/**
 * How a {@link FlowWorker} holds and drives its items (D-055, draft — numbers to be measured in P1-10). How often an item runs
 * before it is parked is the flow's schedule of retries, not the policy's (CORE-14a, D-135 draft: {@link FlowBuilder#retries}).
 *
 * @param lease how long a claim leases an item; renewed every third of it while the item runs
 * @param pollInterval how often a {@linkplain FlowWorker#start() started} worker reconciles: one claim every interval, the first
 *                     an interval plus {@code floorMod(flow.hashCode(), interval in ms)} ms after its start, so the flows of one
 *                     node do not claim together (D-121 (b) B5; work events wake it in between, {@link WorkSignals})
 * @param maxChain how many items of one key a worker takes by the same-key fast path after the item it claimed before the key
 *                 goes back to the seq-ordered claim (D-075; at least 0, 0 = the fast path is off; 32 unmeasured)
 * @since 0.1
 */
public record WorkerPolicy(Duration lease, Duration pollInterval, int maxChain) {

    /** 30 s lease, a reconciliation every 30 s (B5, PT30S; B7: no env key until P2-16c), a chain of at most 32 fast-path items. */
    public static final WorkerPolicy DEFAULT = new WorkerPolicy(Duration.ofSeconds(30), Duration.ofSeconds(30));

    public WorkerPolicy {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(pollInterval, "pollInterval");
        if (!lease.isPositive()) throw new IllegalArgumentException("lease must be positive, was " + lease);
        if (!pollInterval.isPositive()) throw new IllegalArgumentException("pollInterval must be positive, was " + pollInterval);
        if (maxChain < 0) throw new IllegalArgumentException("maxChain must be at least 0 (0: no fast path), was " + maxChain);
    }

    /** The policy with a chain of at most 32 fast-path items (D-075). */
    public WorkerPolicy(Duration lease, Duration pollInterval) {
        this(lease, pollInterval, 32);
    }
}
