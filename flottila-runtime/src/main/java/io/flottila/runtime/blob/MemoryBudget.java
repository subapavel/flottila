package io.flottila.runtime.blob;

/**
 * The node-wide byte budget for bodies held in memory (ARCHITECTURE §4: "a node-wide byte budget is the
 * second spill trigger"). Finite, positive; reservations are all-or-nothing.
 *
 * @since 0.1
 */
public final class MemoryBudget {

    private final long max;
    private long inUse;

    public MemoryBudget(long maxBytes) {
        if (maxBytes <= 0) throw new IllegalArgumentException("memory budget must be positive, was " + maxBytes);
        this.max = maxBytes;
    }

    public long max() { return max; }

    public synchronized long inUse() { return inUse; }

    /** Reserves {@code bytes} if the budget allows; false leaves the budget untouched. */
    public synchronized boolean tryReserve(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("bytes < 0");
        if (inUse + bytes > max) return false;
        inUse += bytes;
        return true;
    }

    public synchronized void release(long bytes) {
        if (bytes < 0 || bytes > inUse) throw new IllegalArgumentException("release of " + bytes + " with " + inUse + " in use");
        inUse -= bytes;
    }
}
