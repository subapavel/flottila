package dev.rail8.sim;

/** The oracle's checks (D-143); {@link #EMPTY} fails a run that compared nothing. */
public enum Check {
    PREDICTED, ADMISSION_BOUNDS, KEY_ORDER, ONE_ID_PER_DEDUPE_KEY, ACKED_IS_FINAL, SNAPSHOT_PER_STEP, DRAINED,
    EMPTY
}
