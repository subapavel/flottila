package io.flottila.core.scope;

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
 * follows the message, never a thread.
 *
 * @param messageId the message id (Envelope.id)
 * @param flow the flow name
 * @param tenant the tenant the message belongs to
 * @param traceId the trace id propagated to monitoring and outbound calls
 * @param deadline the absolute instant after which the message's scope is cancelled
 * @since 0.1
 */
public record MessageContext(String messageId, String flow, String tenant, String traceId, Instant deadline) {

    private static final ScopedValue<MessageContext> CURRENT = ScopedValue.newInstance();

    public MessageContext {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(flow, "flow");
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(deadline, "deadline");
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

    public void run(Runnable body) {
        ScopedValue.where(CURRENT, this).run(body);
    }

    /** The context bound on the calling thread, or null; what a scope captures at fork time. */
    static MessageContext boundOrNull() {
        return CURRENT.isBound() ? CURRENT.get() : null;
    }
}
