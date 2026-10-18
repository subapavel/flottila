package io.flottila.api;

import java.util.Objects;

/**
 * A pipeline element that controls cardinality: it may call {@code rest} zero times (filter),
 * once (step), or N times (split) and returns the envelope the caller sees as the result of the
 * pipeline from here on. Plain code on the message's virtual thread, no reactive contract
 * (ARCHITECTURE §6).
 *
 * @since 0.1
 */
@FunctionalInterface
public interface Stage {

    Envelope apply(Envelope e, Pipeline rest);

    /** The stage that applies {@code step} and hands its result to the rest of the pipeline. */
    static Stage of(Step step) {
        Objects.requireNonNull(step, "step");
        return (e, rest) -> rest.apply(step.apply(e));
    }
}
