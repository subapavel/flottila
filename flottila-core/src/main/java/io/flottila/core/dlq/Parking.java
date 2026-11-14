package io.flottila.core.dlq;

import io.flottila.api.Body;
import io.flottila.api.Envelope;
import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.core.record.Trail;
import io.vertx.core.Future;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The at-least-once failure path (ARCHITECTURE §4 lifecycle table, §6): a step's failure parks the step's input in the
 * {@link Dlq} by reference — its body persisted first, then the row (D-062) —, waits for the row to be durable and surfaces
 * as a {@link ParkedException}; a park that fails, its persist included, leaves the original failure to surface with the
 * park's failure suppressed, or unchanged with nothing suppressed when the park failed with that same failure object
 * ({@code addSuppressed(self)} throws {@link IllegalArgumentException} and would replace it) — a message is never dropped
 * silently. A step's failure is any {@link Exception}, a checked one too ({@code Future.await()} rethrows a failed future's
 * checked cause as is, FIX-107), except an {@link InterruptedException}: a cancelled run is not parked, it is rethrown with
 * the interrupt flag restored. An interrupt during the park's own wait (the persist or the row) surfaces the original failure
 * with the {@link InterruptedException} suppressed and the interrupt flag restored (D-098): the cancelling caller still sees
 * its interrupt, and the park may still land. A step's own {@link Error} is never caught; an {@link Error} out of the park is
 * suppressed on the step's failure like any park failure (FIX-142).
 * A failure further down the pipeline passes through untouched: the step it happened at parked it. {@link #park} parks a
 * message outside any step's run — the worker's attempts bound (D-055) — and leaves its failure to the caller.
 *
 * @since 0.1
 */
public final class Parking {

    private final Dlq dlq;
    private final Clock clock;
    private final Map<String, String> versions;

    public Parking(Dlq dlq, Clock clock, Map<String, String> versions) {
        this.dlq = Objects.requireNonNull(dlq, "dlq");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.versions = Map.copyOf(versions);
    }

    public Stage wrap(Stage stage, StepConfig step) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(step, "step");
        return (e, rest) -> {
            boolean[] handedOn = {false};
            try {
                return stage.apply(e, out -> { handedOn[0] = true; return rest.apply(out); });
            } catch (Exception failure) {   // a checked one too: await() rethrows a failed future's checked cause as is
                if (failure instanceof InterruptedException) {   // a cancelled run, not the step's failure: never parked
                    Thread.currentThread().interrupt();
                    throw failure;
                }
                if (handedOn[0]) throw failure;   // a later step's failure: parked there (or deliberately not), passes through
                Parked parked;
                try {
                    parked = park(e, step, failure).await();
                } catch (Throwable parkFailure) {
                    // Two choices here (FIX-142, F-30), each with its own witness; neither implies the other.
                    // The width: Throwable, not Exception, because an Error out of the park or its await used to leave the
                    // stage in place of the step's failure, unrecorded; and not RuntimeException | Error (D-104), because a
                    // sneaky checked throwable is reachable (an interrupted await throws InterruptedException, flag cleared).
                    // Pinned by anErrorFromTheParksAwaitLeavesTheStageAsTheStepsOwnFailureWithTheErrorSuppressed,
                    // anErrorThrownByThePersistLeavesTheStageAsTheStepsOwnFailureAndWritesNoRow and
                    // aCheckedParkFailureIsSuppressedOnTheStepsFailureWhichStillLeaves.
                    // The position: park(...) is called inside the try, not above it, because Parked.of reads
                    // failure.toString() for the row's error, so a step failure whose own toString() throws makes park(...)
                    // throw synchronously, before any future exists — above the try that throw would escape the stage and
                    // replace the step's failure. The width alone does not cover it: a throwable out of the persist or the
                    // DLQ becomes a failed future the await rethrows from either position.
                    // Pinned by aStepFailureWhoseToStringThrowsStillLeavesAsThatFailureWithTheErrorSuppressed.
                    if (parkFailure instanceof InterruptedException) Thread.currentThread().interrupt();
                    // addSuppressed(self) throws IllegalArgumentException and would replace the failure (FIX-140): pinned by
                    // aDlqFailingWithTheStepsOwnFailureLeavesThatFailureUnchangedAndSuppressesNothing
                    if (parkFailure != failure) failure.addSuppressed(parkFailure);
                    throw failure;
                }
                throw new ParkedException(parked, failure);
            }
        };
    }

    /**
     * Parks {@code e}, the input of {@code step}, for {@code failure}; completes with the row once it is durable. The row
     * refers to {@code e}'s body, so that body is made durable first — persisted in the store it lives in, under its own key
     * (D-062) — and the row is written only after that completed: a DLQ row implies a durable body. A persist that fails, or
     * throws, fails the park with its cause and writes no row. A persist that succeeded tells the run's {@link Trail}, when one
     * is bound on the calling thread, that the key is durable: the trail does not persist it again (D-066).
     */
    public Future<Parked> park(Envelope e, StepConfig step, Throwable failure) {
        Parked parked = Parked.of(e, step, failure, clock.instant(), versions);
        Optional<Trail> run = Trail.current();   // read here, on the run's thread: the persist answers on another
        return persist(e.body()).onSuccess(durable -> run.ifPresent(t -> t.durable(e.body().key()))).compose(durable -> dlq.park(parked)).map(parked);
    }

    /** {@code body} made durable in its own store; what the store throws becomes the failed future's cause. */
    private static Future<Void> persist(Body body) {
        try {
            return body.store().persist(body.key());
        } catch (Throwable thrown) {
            // Throwable, not RuntimeException (FIX-142, F-30): an Error, or a checked throwable thrown sneakily, fails the
            // park with itself as cause as the javadocs above promise; no RuntimeException | Error (D-104), as a sneaky
            // checked one is reachable. Pinned by
            // anErrorThrownByThePersistFailsTheParkWithItAsCauseAndWritesNoRow and
            // aCheckedThrowableThrownSneakilyByThePersistFailsTheParkWithItAsCauseAndWritesNoRow
            return Future.failedFuture(thrown);
        }
    }
}
