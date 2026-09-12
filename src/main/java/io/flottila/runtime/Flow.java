package io.flottila.runtime;

import io.flottila.core.Envelope;
import io.flottila.core.Stage;
import io.smallrye.mutiny.Multi;

import java.util.List;

/** A built flow: named stages + the ingress consumer. Replay = run the tail of the pipeline. */
public record Flow(String name, Component.Consumer consumer, List<Named> stages) {
    public record Named(String name, Stage stage) {}

    /** Stages k..n-1 composed. Stage k consumes envelopes at step k. */
    public Stage from(int k) {
        Stage s = Stage.identity();
        for (int i = k; i < stages.size(); i++) s = s.plug(stages.get(i).stage());
        return s;
    }
    public Stage pipeline() { return from(0); }

    public Multi<Envelope> replay(Envelope stored, int fromStage) {
        Envelope e = stored.withId(java.util.UUID.randomUUID().toString())
                .with(Envelope.REPLAY_OF, stored.id()).withStep(fromStage);
        return from(fromStage).apply(Multi.createFrom().item(e));
    }
}
