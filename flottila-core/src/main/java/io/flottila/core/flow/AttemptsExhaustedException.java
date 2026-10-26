package io.flottila.core.flow;

/**
 * The failure a message is parked with when its work item was claimed more often than the {@link WorkerPolicy#maxAttempts()}
 * allows (D-055): the runs before did not finish it — they crashed, hung past their lease, or failed where nothing could park
 * them — so it is parked at step 1 from its ingress envelope instead of running again.
 *
 * @since 0.1
 */
public final class AttemptsExhaustedException extends RuntimeException {

    public AttemptsExhaustedException(String messageId, int attempts, int maxAttempts) {
        super("message " + messageId + " was claimed " + attempts + " times, more than maxAttempts " + maxAttempts + ": parked without another run");
    }
}
