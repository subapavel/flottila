package io.flottila.runtime;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.smallrye.mutiny.vertx.core.AbstractVerticle;

/**
 * Writer verticle: processing never waits for monitoring. Events arrive over the local event bus;
 * in the real thing a persistent buffer sits in between and this batches into Postgres/ClickHouse.
 */
public class MonitorVerticle extends AbstractVerticle {
    public static final String ADDRESS = "flottila.monitor";
    private final MonitoringStore store;

    public MonitorVerticle(MonitoringStore store) { this.store = store; }

    @Override
    public Uni<Void> asyncStart() {
        vertx.eventBus().<JsonObject>consumer(ADDRESS).handler(msg -> store.append(Snapshot.fromJson(msg.body())));
        return Uni.createFrom().voidItem();
    }
}
