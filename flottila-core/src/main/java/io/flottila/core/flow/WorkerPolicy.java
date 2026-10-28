package io.flottila.core.flow;

import java.time.Duration;
import java.util.Objects;

/**
 * How a {@link FlowWorker} holds and drives its items (D-055, draft — numbers to be measured in P1-10).
 *
 * @param lease how long a claim leases an item; renewed every third of it while the item runs
 * @param maxAttempts how many claims an item may have; one claimed past it is parked without a run (at least 1)
 * @param pollInterval how often a {@linkplain FlowWorker#start() started} worker polls
 * @param maxChain how many items of one key a worker takes by the same-key fast path after the item it claimed before the key
 *                 goes back to the seq-ordered claim (D-075; at least 0, 0 = the fast path is off; 32 unmeasured)
 * @since 0.1
 */
public record WorkerPolicy(Duration lease, int maxAttempts, Duration pollInterval, int maxChain) {

    /** 30 s lease, 5 attempts, a poll every second, a chain of at most 32 fast-path items. */
    public static final WorkerPolicy DEFAULT = new WorkerPolicy(Duration.ofSeconds(30), 5, Duration.ofSeconds(1));

    public WorkerPolicy {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(pollInterval, "pollInterval");
        if (!lease.isPositive()) throw new IllegalArgumentException("lease must be positive, was " + lease);
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be at least 1, was " + maxAttempts);
        if (!pollInterval.isPositive()) throw new IllegalArgumentException("pollInterval must be positive, was " + pollInterval);
        if (maxChain < 0) throw new IllegalArgumentException("maxChain must be at least 0 (0: no fast path), was " + maxChain);
    }

    /** The policy with a chain of at most 32 fast-path items (D-075). */
    public WorkerPolicy(Duration lease, int maxAttempts, Duration pollInterval) {
        this(lease, maxAttempts, pollInterval, 32);
    }
}
