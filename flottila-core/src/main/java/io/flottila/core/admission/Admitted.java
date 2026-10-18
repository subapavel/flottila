package io.flottila.core.admission;

import io.flottila.api.Timer;
import io.flottila.core.scope.MessageContext;
import io.flottila.core.scope.RequestScope;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * The message path in one place (ARCHITECTURE §4 threading): admit the message — requests <em>and</em> bytes —
 * bind its {@link MessageContext}, open its {@link RequestScope} with the context's deadline, run the body,
 * and release the permit whatever happened. Over the budget the caller gets {@link AdmissionRefusedException}
 * at once and nothing runs; a deadline already in the past is a caller error.
 *
 * @since 0.1
 */
public final class Admitted {

    private Admitted() {}

    /** The message's work: runs with the scope open and the context bound; may block (join, await) and throw. @since 0.1 */
    @FunctionalInterface
    public interface Body<T> {
        T run(RequestScope scope) throws Exception;
    }

    /**
     * @param admission the flow's budget
     * @param context the message's context; its deadline bounds the scope
     * @param bytes the body size counted against the byte budget
     * @param body runs on the calling (virtual) thread with the context bound and the scope open
     * @throws AdmissionRefusedException when the budget has no room now
     * @throws IllegalArgumentException when the body can never fit the budget or the deadline has passed
     * @throws Exception whatever the body throws, after the permit is released
     * <p>The scope's deadline is {@code clock.instant() + remaining} with {@code remaining} computed once at entry: equal to
     * the context's deadline on a fake clock, microseconds later on a real one.
     */
    public static <T> T run(Admission admission, MessageContext context, long bytes, Clock clock, Timer timer, Body<T> body) throws Exception {
        Objects.requireNonNull(admission, "admission");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(body, "body");
        Duration remaining = Duration.between(clock.instant(), context.deadline());
        if (remaining.isZero() || remaining.isNegative()) throw new IllegalArgumentException("deadline " + context.deadline() + " has already passed");
        Optional<Admission.Permit> permit = admission.tryAcquire(bytes);
        if (permit.isEmpty()) {
            throw new AdmissionRefusedException(bytes, admission.inFlightRequests(), admission.inFlightBytes(), admission.maxRequests(), admission.maxBytes());
        }
        try (Admission.Permit p = permit.get()) {
            return context.call(() -> {
                try (RequestScope scope = RequestScope.open(clock, timer, remaining)) {
                    return body.run(scope);
                }
            });
        }
    }
}
