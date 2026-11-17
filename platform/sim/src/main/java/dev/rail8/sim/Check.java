package dev.rail8.sim;

/** The oracle's checks; {@link #EMPTY} fails a run that compared nothing. */
public enum Check {
    PREDICTED, ADMISSION_BOUNDS, KEY_ORDER, ACKED_IS_FINAL, SNAPSHOT_PER_STEP, DRAINED,
    EMPTY
}
