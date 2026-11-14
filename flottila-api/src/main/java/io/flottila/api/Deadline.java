package io.flottila.api;

import java.time.Duration;

/**
 * The time a send has left (D-113 draft, amends D-100): what a producer is handed with {@link Producer#send(Envelope, Deadline)}.
 * {@link #remaining()} is live — re-read at each call, non-blocking, callable from any thread — and finite; zero or less means
 * the deadline has passed (negative: by how much). It may grow between two reads (the node's fence was renewed), never past
 * the message's own deadline. A producer starts nothing once it has passed and cuts a send still open when a re-read finds it
 * passed.
 *
 * @since 0.1
 */
@FunctionalInterface
public interface Deadline {

    /** What is left now; zero or less: passed. */
    Duration remaining();
}
