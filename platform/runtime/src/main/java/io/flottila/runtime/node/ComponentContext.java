package io.flottila.runtime.node;

import io.flottila.api.BlobStore;
import io.flottila.api.IdGenerator;
import io.flottila.api.Timer;
import io.vertx.core.Vertx;

import java.time.Clock;
import java.util.Objects;

/**
 * What a node gives the factory of its components (D-057 amended): the caller's Vert.x and the node's own blob store, ids,
 * timer and clock — the node builds its components once, from this, before it touches Postgres.
 *
 * @param vertx the Vert.x the node runs on (its caller's)
 * @param blobs the node's blob store
 * @param ids the node's id generator
 * @param timer the node's timer
 * @param clock the node's clock
 * @since 0.1
 */
public record ComponentContext(Vertx vertx, BlobStore blobs, IdGenerator ids, Timer timer, Clock clock) {
    public ComponentContext {
        Objects.requireNonNull(vertx, "vertx");
        Objects.requireNonNull(blobs, "blobs");
        Objects.requireNonNull(ids, "ids");
        Objects.requireNonNull(timer, "timer");
        Objects.requireNonNull(clock, "clock");
    }
}
