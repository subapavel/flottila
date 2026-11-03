package io.flottila.sim;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * What a run is made of (D-101): action weights, fault rates, the four partition keys, the flows' limits. {@link #V1} is frozen
 * once a seed is pinned under it; a changed one is a new name.
 *
 * @param name the name a failure line and the replay command carry ({@code config=V1})
 * @param weights per action, its weight in a tick's draw (absent: 0)
 * @param rates per fault, the chance a call draws it, 0..1 (absent: 0)
 * @param keys the partition keys of the at-least-once flow
 * @param actionsPerTick how many actions the workload draws per tick
 * @param minPayload the smallest payload in bytes
 * @param maxPayload the largest payload in bytes, below {@code maxBodyBytes}
 * @param maxBodyBytes every flow's {@code limits.maxBodyBytes}
 * @param syncMaxInFlight the sync flow's {@code limits.maxInFlight}
 * @param syncMaxInFlightBytes the sync flow's {@code limits.maxInFlightBytes}
 * @param aloMaxInFlight the at-least-once flow's {@code limits.maxInFlight}: also each worker's run limit
 * @param aloMaxInFlightBytes the at-least-once flow's {@code limits.maxInFlightBytes}
 * @param retries the retries of the {@code to} step after its first call
 * @param retryInitial the backoff before the first retry
 * @param retryMax the cap of the backoff
 * @param retryMultiplier the backoff's growth per retry
 * @param maxHeldTicks a DELAY holds a call 1..this many ticks
 * @param resendAfter a Nacked payload is sent again no earlier than this many ticks after its Nack
 * @param drainTicks the bound of the drain after the last tick
 * @param tick how far the fake clock moves per tick
 */
public record SimConfig(String name, Map<Action, Integer> weights, Map<FaultKind, Double> rates, List<String> keys, int actionsPerTick,
                        long minPayload, long maxPayload, long maxBodyBytes, long syncMaxInFlight, long syncMaxInFlightBytes,
                        long aloMaxInFlight, long aloMaxInFlightBytes, int retries, Duration retryInitial, Duration retryMax,
                        double retryMultiplier, int maxHeldTicks, int resendAfter, int drainTicks, Duration tick) {

    /** The per-push configuration, frozen since seeds are pinned under it (P2-10a, bench/RESULTS.md §P2-10). */
    public static final SimConfig V1 = new SimConfig("V1",
            Map.of(Action.SEND_SYNC, 3, Action.SEND_ALO, 4, Action.RESEND, 1, Action.POLL, 4),
            Map.of(FaultKind.DELAY, 0.25, FaultKind.REFUSE, 0.2, FaultKind.STORE_REFUSE, 0.05),
            List.of("k1", "k2", "k3", "k4"), 2, 16, 160, 256, 2, 256, 3, 768, 2, Duration.ofSeconds(1), Duration.ofSeconds(4), 2.0, 8, 3,
            400, Duration.ofSeconds(1));

    public SimConfig {
        weights = Map.copyOf(weights);
        rates = Map.copyOf(rates);
        keys = List.copyOf(keys);
    }

    /** The configuration a failure line names ({@code config=V1}). */
    public static SimConfig named(String name) {
        if (!V1.name.equals(name)) throw new IllegalArgumentException("no simulation config named '" + name + "'; known: [V1]");
        return V1;
    }

    /** The weight of {@code action} in the workload draw. */
    public int weight(Action action) {
        return weights.getOrDefault(action, 0);
    }

    /** The chance a call draws {@code kind}. */
    public double rate(FaultKind kind) {
        return rates.getOrDefault(kind, 0.0);
    }

    /** This configuration with {@code action} drawn at {@code weight}, under a name of its own. */
    public SimConfig withWeight(Action action, int weight) {
        Map<Action, Integer> w = new EnumMap<>(weights);
        w.put(action, weight);
        return new SimConfig(name + "+" + action + "=" + weight, w, rates, keys, actionsPerTick, minPayload, maxPayload, maxBodyBytes,
                syncMaxInFlight, syncMaxInFlightBytes, aloMaxInFlight, aloMaxInFlightBytes, retries, retryInitial, retryMax, retryMultiplier,
                maxHeldTicks, resendAfter, drainTicks, tick);
    }

    /** This configuration with {@code kind} injected at {@code rate} (0..1 per call), under a name of its own. */
    public SimConfig withRate(FaultKind kind, double rate) {
        Map<FaultKind, Double> r = new EnumMap<>(rates);
        r.put(kind, rate);
        return new SimConfig(name + "+" + kind + "=" + rate, weights, r, keys, actionsPerTick, minPayload, maxPayload, maxBodyBytes,
                syncMaxInFlight, syncMaxInFlightBytes, aloMaxInFlight, aloMaxInFlightBytes, retries, retryInitial, retryMax, retryMultiplier,
                maxHeldTicks, resendAfter, drainTicks, tick);
    }

    /** One action drawn by weight, the actions taken in their declared order (the last one takes what is left). */
    Action draw(SplittableRandom random) {
        Action[] all = Action.values();
        int total = 0;
        for (Action a : all) total += weight(a);
        int r = random.nextInt(total);
        for (int i = 0; i < all.length - 1; i++) {
            r -= weight(all[i]);
            if (r < 0) return all[i];
        }
        return all[all.length - 1];
    }
}
