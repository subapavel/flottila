package io.flottila.runtime;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.vertx.core.AbstractVerticle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One flow = one verticle = one pipeline. Undeploy cancels the subscription (drain to be added). */
public class FlowVerticle extends AbstractVerticle {
    private static final Logger log = LoggerFactory.getLogger(FlowVerticle.class);
    private final Flow flow;

    public FlowVerticle(Flow flow) { this.flow = flow; }

    @Override
    public Uni<Void> asyncStart() {
        return flow.consumer().start().invoke(() -> {
            flow.pipeline().apply(flow.consumer().stream()).subscribe().with(
                    out -> flow.consumer().reply(out),
                    t -> log.error("flow {} stream died", flow.name(), t),
                    () -> log.info("flow {} completed", flow.name()));
            log.info("flow {} deployed with {} stages", flow.name(), flow.stages().size());
        });
    }
}
