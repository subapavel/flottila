package io.flottila.core.scope;

import io.flottila.api.Envelope;
import io.flottila.api.Pipeline;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * What every step and every child of a message's {@link RequestScope} may ask for without it being
 * threaded through signatures (ARCHITECTURE §4): message id, flow, tenant, trace id and the absolute
 * deadline. Bound as a {@link ScopedValue} for the duration of {@link #call(Callable)} / {@link #run(Runnable)};
 * {@link RequestScope#fork(Callable)} rebinds the forking thread's context in each child, so the binding
 * follows the message, never a thread. Every pipeline the flow builder builds binds the context of the message it runs in its
 * first stage, through {@link #apply(Pipeline, Envelope)} (D-100).
 *
 * @param messageId the message id (Envelope.id)
 * @param flow the flow name
 * @param tenant the tenant the message belongs to
 * @param traceId the trace id propagated to monitoring and outbound calls
 * @param deadline the absolute instant the message's run should be done by: a send that waits for its target waits no longer
 *                 (D-100); nothing cancels the run at it yet
 * @since 0.1
 */
public record MessageContext(String messageId, String flow, String tenant, String traceId, Instant deadline) {

    /** The tenant of every message until a package carries one (ARCHITECTURE §9, D-100). */
    public static final String DEFAULT_TENANT = "default";

    private static final ScopedValue<MessageContext> CURRENT = ScopedValue.newInstance();

    public MessageContext {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(flow, "flow");
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(deadline, "deadline");
    }

    /** The context of {@code e}'s run: its id, its flow, the {@link #DEFAULT_TENANT}, its id as trace id, and {@code deadline}. */
    public static MessageContext of(Envelope e, Instant deadline) {
        return new MessageContext(e.id(), e.flow(), DEFAULT_TENANT, e.id(), deadline);
    }

    /** The context of the message this thread is working on. */
    public static MessageContext current() {
        return CURRENT.orElseThrow(() -> new IllegalStateException("no message context is bound on this thread"));
    }

    public static boolean isBound() { return CURRENT.isBound(); }

    /** Time left until the deadline on the given clock, never negative. */
    public Duration remaining(Clock clock) {
        Duration left = Duration.between(clock.instant(), deadline);
        return left.isNegative() ? Duration.ZERO : left;
    }

    /** Runs {@code body} with this context bound; the binding ends when the call returns. */
    public <T> T call(Callable<T> body) throws Exception {
        return ScopedValue.where(CURRENT, this).call(body::call);   // CallableOp<T, Exception> in the final API (JDK 25 Temurin), not Callable
    }

    /** {@code rest} applied to {@code e} with this context bound; what it throws passes through as thrown. */
    public Envelope apply(Pipeline rest, Envelope e) {
        return ScopedValue.where(CURRENT, this).call(() -> rest.apply(e));
    }

    public void run(Runnable body) {
        ScopedValue.where(CURRENT, this).run(body);
    }

    /** The context bound on the calling thread, or null; what a scope captures at fork time. */
    static MessageContext boundOrNull() {
        return CURRENT.isBound() ? CURRENT.get() : null;
    }
}
