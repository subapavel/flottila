package io.flottila.core.pattern;

import io.flottila.api.Envelope;
import io.flottila.api.Pipeline;
import io.flottila.api.Stage;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * Message filter (ARCHITECTURE §4): a message the predicate accepts goes on; any other ends here, handed back
 * as it was without the rest of the pipeline running — the runtime records it as filtered, not failed.
 *
 * @param keep the condition to go on
 * @since 0.1
 */
public record Filter(Predicate<Envelope> keep) implements Stage {

    public Filter {
        Objects.requireNonNull(keep, "keep");
    }

    @Override
    public Envelope apply(Envelope e, Pipeline rest) {
        return keep.test(e) ? rest.apply(e) : e;
    }
}
