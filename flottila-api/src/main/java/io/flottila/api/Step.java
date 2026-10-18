package io.flottila.api;

/**
 * One transformation of the message. Runs on the message's virtual thread and may block on I/O
 * through Vert.x clients and {@code Future.await()}; returns a new {@link Envelope}, never mutates
 * the one it got (ARCHITECTURE §6).
 *
 * @since 0.1
 */
@FunctionalInterface
public interface Step {
    Envelope apply(Envelope e);
}
