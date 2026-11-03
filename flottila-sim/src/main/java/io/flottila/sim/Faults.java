package io.flottila.sim;

import java.util.function.LongSupplier;

/**
 * The run's per-call faults (D-101): a call's decision is {@code mix(of(FAULTS), kind.code, messageId, attempt, callOrdinal) < rate}
 * — counter-based, so a call decides the same whatever ran before it, and a workload change moves no other call's fault. Off
 * after the last tick (the drain): no call is drawn for then.
 */
final class Faults {

    private final long seed;
    private final SimConfig config;
    private final World world;
    private final LongSupplier tick;
    private volatile boolean on = true;

    Faults(SimSeed seed, SimConfig config, World world, LongSupplier tick) {
        this.seed = seed.of(Stream.FAULTS);
        this.config = config;
        this.world = world;
        this.tick = tick;
    }

    /** The call draws {@code kind}: decided, told to the world, injected when hit; never while faults are off. */
    boolean hit(FaultKind kind, String messageId, int attempt, int callOrdinal) {
        if (!on) return false;
        boolean hit = wouldHit(kind, messageId, attempt, callOrdinal);
        world.onDraw(new World.Draw(tick.getAsLong(), kind, messageId, attempt, callOrdinal, hit));
        return hit;
    }

    /** What {@link #hit} decides for the call, drawn silently: the oracle's prediction. */
    boolean wouldHit(FaultKind kind, String messageId, int attempt, int callOrdinal) {
        return Mix.unit(draw(kind, messageId, attempt, callOrdinal)) < config.rate(kind);
    }

    /** How many ticks a DELAY holds the call: 1..maxHeldTicks, from the call's own draw. */
    int heldTicks(String messageId, int attempt, int callOrdinal) {
        return 1 + (int) Long.remainderUnsigned(Mix.splitMix64(draw(FaultKind.DELAY, messageId, attempt, callOrdinal)), config.maxHeldTicks());
    }

    /** No call draws a fault from now on. */
    void off() {
        on = false;
    }

    private long draw(FaultKind kind, String messageId, int attempt, int callOrdinal) {
        return Mix.mix(seed, kind.code(), Mix.of(messageId), attempt, callOrdinal);
    }
}
