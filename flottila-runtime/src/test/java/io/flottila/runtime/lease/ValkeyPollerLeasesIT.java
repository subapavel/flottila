package io.flottila.runtime.lease;

import io.vertx.core.Vertx;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P2-02L (D-083): the Valkey store passes the lease contract against valkey/valkey:8, and keeps a lease as the one key
 * {@code flottila:poller:<key>} whose value is the owner and whose ttl is set in milliseconds ({@code SET .. PX} to take,
 * {@code PEXPIRE} to renew). A lapse is Valkey's own expiry, waited for (bounded) by polling {@code EXISTS}, never slept.
 *
 * <p>The shape these cases assume (P2-02L test-author): {@code new ValkeyPollerLeases(Redis)}; the store prefixes the key it is
 * given with {@code flottila:poller:} (the key the node gives is {@code <package>/<flow>/<endpoint uri>}).
 */
class ValkeyPollerLeasesIT extends PollerLeasesContract {

    static final String PREFIX = "flottila:poller:";

    @SuppressWarnings("resource")   // stopped in stop()
    static final GenericContainer<?> valkey = new GenericContainer<>(DockerImageName.parse("valkey/valkey:8")).withExposedPorts(6379);
    static Vertx vertx;
    static Redis redis;
    static RedisAPI api;

    @BeforeAll
    static void start() {
        valkey.start();
        vertx = Vertx.vertx();
        redis = Redis.createClient(vertx, "redis://" + valkey.getHost() + ":" + valkey.getMappedPort(6379));
        api = RedisAPI.api(redis);
    }

    @AfterAll
    static void stop() {
        redis.close().await();
        vertx.close().await();
        valkey.stop();
    }

    @Override
    protected PollerLeases emptyStore() {
        api.flushall(List.of()).await();
        return new ValkeyPollerLeases(redis);
    }

    @Override
    protected Duration shortTtl() { return Duration.ofMillis(300); }

    @Override
    protected void lapse(String key, Duration ttl) {
        long deadline = System.nanoTime() + ttl.plusSeconds(10).toNanos();
        while (api.exists(List.of(PREFIX + key)).await().toInteger() != 0) {
            if (System.nanoTime() > deadline) fail(PREFIX + key + " did not expire within " + ttl + " + 10 s");
            Thread.onSpinWait();
        }
    }

    /**
     * The lease is the key {@code flottila:poller:<key>} holding the owner, with a millisecond ttl: 45.678 s taken with
     * {@code SET .. PX} (not seconds, not none), 20.345 s on a renew ({@code PEXPIRE}); nothing else is written.
     */
    @Test
    void theKeyCarriesTheOwnerAndAPxTtl() {
        assertTrue(hold(KEY, A, Duration.ofMillis(45_678)));

        Response value = api.get(PREFIX + KEY).await();
        assertEquals(A, value == null ? null : value.toString(), "the value is the owner");
        long taken = api.pttl(PREFIX + KEY).await().toLong();
        assertTrue(taken > 40_000 && taken <= 45_678, "the ttl is the hold's, in milliseconds: PTTL " + taken);
        assertEquals(1, api.dbsize().await().toInteger(), "one key per lease");

        assertTrue(hold(KEY, A, Duration.ofMillis(20_345)));

        long renewed = api.pttl(PREFIX + KEY).await().toLong();
        assertTrue(renewed > 15_000 && renewed <= 20_345, "a renew sets the new ttl, in milliseconds: PTTL " + renewed);
        assertEquals(A, api.get(PREFIX + KEY).await().toString(), "still the owner");
    }
}
