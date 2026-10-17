package io.flottila.core;

import io.smallrye.mutiny.Multi;

/** The single pattern contract: stream in, stream out. Cardinality lives in the implementation. */
@FunctionalInterface
public interface Stage {
    Multi<Envelope> apply(Multi<Envelope> in);

    /** Pipeline = composition. Recursive, like Camel's Pipeline but with backpressure. */
    default Stage plug(Stage next) { return in -> next.apply(this.apply(in)); }

    static Stage identity() { return in -> in; }
}
