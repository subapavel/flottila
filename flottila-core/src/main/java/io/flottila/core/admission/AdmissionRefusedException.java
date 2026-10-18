package io.flottila.core.admission;

/**
 * The flow's budget has no room for this message right now: told at once, never waited for, so the
 * endpoint can answer 503 + Retry-After, pause its poller or stop polling (ARCHITECTURE §4). Carries the
 * numbers the answer and the monitoring want.
 *
 * @since 0.1
 */
public final class AdmissionRefusedException extends RuntimeException {

    private final long requestedBytes;
    private final long inFlightRequests;
    private final long inFlightBytes;
    private final long maxRequests;
    private final long maxBytes;

    public AdmissionRefusedException(long requestedBytes, long inFlightRequests, long inFlightBytes, long maxRequests, long maxBytes) {
        super("admission refused: " + requestedBytes + " bytes requested with " + inFlightRequests + "/" + maxRequests
                + " requests and " + inFlightBytes + "/" + maxBytes + " bytes in flight");
        this.requestedBytes = requestedBytes;
        this.inFlightRequests = inFlightRequests;
        this.inFlightBytes = inFlightBytes;
        this.maxRequests = maxRequests;
        this.maxBytes = maxBytes;
    }

    public long requestedBytes() { return requestedBytes; }

    public long inFlightRequests() { return inFlightRequests; }

    public long inFlightBytes() { return inFlightBytes; }

    public long maxRequests() { return maxRequests; }

    public long maxBytes() { return maxBytes; }
}
