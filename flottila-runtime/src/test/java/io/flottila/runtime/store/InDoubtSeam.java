package io.flottila.runtime.store;

import io.flottila.runtime.blob.TieredBlobStore;

import java.util.List;

/**
 * FIX-145: the journal package's sweep ITs reach {@link PgIntake}'s package-private in-doubt seam through this test-only bridge.
 * A transaction id Postgres refuses to answer for (a future xid, SQLSTATE 22023) cannot come out of a real commit on the same
 * server — {@code pg_current_xact_id()} answers an id the server has already assigned — so the case registers one by hand.
 *
 * <p>The seam assumed (FIX-145, test-author): {@code void PgIntake.inDoubt(String xid, List<TieredBlobStore.Durable> saves)},
 * package-private — {@code saves} become the members of a group whose commit is in doubt under transaction id {@code xid}, exactly
 * as a commit whose reply was lost registers its members writing rows (they stay in the open saves until {@code pg_xact_status}
 * of {@code xid} is known).
 */
public final class InDoubtSeam {

    private InDoubtSeam() { }

    /** {@code saves} in doubt under {@code xid} in {@code intake}, as a lost commit reply leaves them. */
    public static void register(PgIntake intake, String xid, List<TieredBlobStore.Durable> saves) {
        intake.inDoubt(xid, saves);
    }
}
