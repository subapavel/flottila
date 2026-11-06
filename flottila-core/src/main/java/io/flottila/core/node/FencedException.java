package io.flottila.core.node;

/**
 * What an at-least-once send, claim, park or settle meets while the node's fence is closed (D-101 (2)): the node cannot prove its lease is
 * live, so another node may take its items over. Never retried, never parked: the item is left unsettled for whoever runs it
 * next.
 *
 * @since 0.1
 */
public final class FencedException extends RuntimeException {

    public FencedException(String message) {
        super(message);
    }

    /** One that stands for {@code cause}, a failure the fence kept from being settled — a step's failure not parked. */
    public FencedException(String message, Throwable cause) {
        super(message, cause);
    }
}
