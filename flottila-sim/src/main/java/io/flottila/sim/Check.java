package io.flottila.sim;

/** The oracle's checks (D-101); {@link #EMPTY} fails a run that compared nothing. */
public enum Check {
    PREDICTED, ADMISSION_BOUNDS, KEY_ORDER, ONE_ID_PER_DEDUPE_KEY, REPLAY_YIELDS_RECORDED, ACKED_IS_FINAL, SNAPSHOT_PER_STEP, DRAINED,
    EMPTY
}
