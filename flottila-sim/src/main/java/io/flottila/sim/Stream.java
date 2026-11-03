package io.flottila.sim;

/**
 * The independent random streams of a run (D-101): each comes from the run seed and its own explicit {@link #salt()}, never
 * from its declaration order. Append-only: a new stream takes a new salt, an existing salt never moves (every pinned seed
 * moves with it).
 */
public enum Stream {
    /** What the workload does in each tick: actions, payloads, keys, the polling worker. */
    WORKLOAD(0x6a09e667f3bcc908L),
    /** The per-call fault decisions. */
    FAULTS(0xbb67ae8584caa73bL),
    /** The order of a tick's units. */
    SCHEDULE(0x3c6ef372fe94f82bL);

    private final long salt;

    Stream(long salt) {
        this.salt = salt;
    }

    /** The explicit salt this stream's seed is {@code SplitMix64(run ^ salt)} of. */
    public long salt() {
        return salt;
    }
}
