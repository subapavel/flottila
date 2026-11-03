package io.flottila.sim;

import io.flottila.core.flow.Intake;
import io.flottila.core.record.Snapshot;

/**
 * Broken variant: the at-least-once intake records the ingress snapshot under step 1 instead of 0 — the timeline has no step-0
 * snapshot, and the key step's own step-1 snapshot is dropped (a store keeps the first per step). The worker still finds the
 * ingress by its name, so every message runs and answers as the correct world's. Built over the public {@link Intake} and
 * {@link Snapshot} only.
 */
final class RenumberedIngress implements World {

    static final String NAME = "renumbered-ingress";

    @Override public String name() { return NAME; }

    @Override public Intake intake(Intake sim) {
        return (arrived, s, flow, partitionKey, dedupeKey, abandoned) -> sim.save(arrived,
                new Snapshot(s.messageId(), s.flow(), 1, s.stepName(), s.outcome(), s.error(), s.startedAt(), s.wallNanos(), s.cpuNanos(),
                        s.headers(), s.props(), s.keys(), s.bodyHash(), s.bodySize(), s.bodyKey(), s.versions()),
                flow, partitionKey, dedupeKey, abandoned);
    }
}
