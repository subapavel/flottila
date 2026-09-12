package io.flottila.core;

import io.smallrye.mutiny.Uni;

/** 1→1 unit of work with full control over the reactive type. */
@FunctionalInterface
public interface Step {
    Uni<Envelope> apply(Envelope e);
}
