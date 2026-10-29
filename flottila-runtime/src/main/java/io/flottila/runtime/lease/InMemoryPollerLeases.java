package io.flottila.runtime.lease;

import io.vertx.core.Future;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@link PollerLeases} in this process's memory, on the injected clock: a lease covers [call, call + ttl), a renew replaces its
 * expiry, and a lapsed one is free — never "always held". A node without Valkey holds its leases here (D-083): enough for a single
 * node; two such nodes on one endpoint both poll (duplicates, never a loss).
 *
 * @since 0.1
 */
public final class InMemoryPollerLeases implements PollerLeases {

    private record Lease(String owner, Instant until) {}

    private final Clock clock;
    /** By key; guarded by this. A lapsed lease stays until it is taken again: one entry per key ever held. */
    private final Map<String, Lease> leases = new HashMap<>();

    public InMemoryPollerLeases(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized Future<Boolean> hold(String key, String owner, Duration ttl) {
        Instant now = clock.instant();
        Lease held = leases.get(key);
        if (held != null && !held.owner().equals(owner) && now.isBefore(held.until())) return Future.succeededFuture(false);
        leases.put(key, new Lease(owner, now.plus(ttl)));
        return Future.succeededFuture(true);
    }

    @Override
    public synchronized Future<Void> release(String key, String owner) {
        Lease held = leases.get(key);
        if (held != null && held.owner().equals(owner)) leases.remove(key);
        return Future.succeededFuture();
    }
}
