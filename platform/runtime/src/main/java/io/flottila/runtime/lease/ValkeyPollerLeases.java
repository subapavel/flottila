package io.flottila.runtime.lease;

import io.vertx.core.Future;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;

import java.time.Duration;
import java.util.List;

/**
 * {@link PollerLeases} in Valkey (D-083): a lease is the key {@code flottila:poller:<key>} holding its owner, with a millisecond
 * ttl. A hold is one {@code EVAL}: no key, {@code SET .. PX ttl} (taken); the owner's, {@code PEXPIRE ttl} (renewed); another
 * owner's, nothing (refused). A release is one {@code EVAL} that deletes the key only while it holds the owner. Each script runs
 * atomically on the server: of many owners racing for one free key, one wins.
 *
 * @since 0.1
 */
public final class ValkeyPollerLeases implements PollerLeases {

    /** The namespace of the node's leases in Valkey. */
    static final String PREFIX = "flottila:poller:";

    /** KEYS[1] the lease, ARGV[1] the owner, ARGV[2] the ttl in ms: 1 held, 0 refused. */
    private static final String HOLD = """
            local held = redis.call('GET', KEYS[1])
            if held == false then
              redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
              return 1
            end
            if held == ARGV[1] then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
              return 1
            end
            return 0""";

    /** KEYS[1] the lease, ARGV[1] the owner: the number of keys deleted. */
    private static final String RELEASE = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0""";

    private final RedisAPI api;

    /** @param redis the node's client: it stays the caller's, who closes it */
    public ValkeyPollerLeases(Redis redis) {
        this.api = RedisAPI.api(redis);
    }

    @Override
    public Future<Boolean> hold(String key, String owner, Duration ttl) {
        return api.eval(List.of(HOLD, "1", PREFIX + key, owner, Long.toString(ttl.toMillis()))).map(held -> held.toInteger() == 1);
    }

    @Override
    public Future<Void> release(String key, String owner) {
        return api.eval(List.of(RELEASE, "1", PREFIX + key, owner)).mapEmpty();
    }
}
