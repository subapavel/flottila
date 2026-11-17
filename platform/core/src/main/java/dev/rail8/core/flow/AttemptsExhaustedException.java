package dev.rail8.core.flow;

/**
 * The failure a message is parked with when its work item was handed out more often than its schedule of retries allows (the
 * number of retries plus the first run). The last run the bound allowed did not settle the message: it crashed, hung past its
 * lease, was fenced, or failed where nothing could park it. So it is parked at step 1 from its ingress envelope instead of
 * running again.
 *
 * @since 0.1
 */
public final class AttemptsExhaustedException extends RuntimeException {

    public AttemptsExhaustedException(String messageId, int attempts, int bound) {
        super("message " + messageId + " was claimed " + attempts + " times, more than its " + bound + " hand-outs (its retries and its first run):"
                + " parked without another run");
    }
}
