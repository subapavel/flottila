package io.flottila.api;

import java.util.List;

/**
 * A composition of stages built once from the flow config; {@code Pipeline.of(List.of(s0, s1, s2))} applies
 * {@code s0} with the pipeline {@code (s1, s2)} as its {@code rest}, and so on. The empty pipeline
 * is the identity.
 *
 * @since 0.1
 */
@FunctionalInterface
public interface Pipeline {

    /** The empty pipeline: returns what it is given. */
    Pipeline END = e -> e;

    Envelope apply(Envelope e);

    static Pipeline of(List<Stage> stages) {
        Pipeline p = END;
        for (int i = stages.size() - 1; i >= 0; i--) {
            Stage stage = stages.get(i);
            Pipeline rest = p;
            p = e -> stage.apply(e, rest);
        }
        return p;
    }
}
