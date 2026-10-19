package io.flottila.components.http;

import io.flottila.api.Fault;

/**
 * The partner answered a non-2xx status: the send failed with the status and the first part of the response body.
 * {@link #retryable()} is true for 5xx and 429 (the retry policy's question), false for the other 4xx. A
 * {@link Fault.Kind#DOWNSTREAM} fault: the partner failed, not the sender.
 *
 * @since 0.1
 */
public final class HttpStatusException extends RuntimeException implements Fault {

    private final int status;

    public HttpStatusException(int status, String body) {
        super("HTTP " + status + (body == null || body.isEmpty() ? "" : ": " + body));
        this.status = status;
    }

    public int status() { return status; }

    public boolean retryable() { return status >= 500 || status == 429; }

    /** {@link Fault.Kind#DOWNSTREAM}: the partner answered with an error. */
    @Override
    public Kind kind() { return Kind.DOWNSTREAM; }
}
