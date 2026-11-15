package io.flottila.runtime.lease;

import io.vertx.core.Future;

import java.time.Duration;

/**
 * Who polls an endpoint (D-083): a key held by one owner at a time, for a ttl. The node holds the lease of each polling consumer
 * it runs ({@code Component.polls}) and runs that consumer only while it holds it. There is no fencing token: no downstream
 * could check one, and an overlap gives a duplicate, never a loss (the save drops duplicates, P2-02D).
 *
 * @since 0.1
 */
public interface PollerLeases {

    /**
     * Takes {@code key} when it is free or its lease lapsed, or renews it when {@code owner} holds it: true, held for {@code ttl}
     * from the call; false when another owner holds it.
     */
    Future<Boolean> hold(String key, String owner, Duration ttl);

    /** Frees {@code key} at once if {@code owner} holds it; a key another owner holds, or nobody, stays as it is. */
    Future<Void> release(String key, String owner);
}
