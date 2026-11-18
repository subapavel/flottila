package dev.rail8.testkit;

import dev.rail8.api.Cache;
import dev.rail8.api.Secret;
import dev.rail8.api.Services;
import dev.rail8.api.StepConfig;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link Services} for a step's unit test: the secrets the test sets, and one in-memory cache on the test's clock that passes
 * {@link CacheContract}. {@link #secret(StepConfig, String)} refuses what a node refuses: an option that is absent, not a
 * string, not a reference, not a well-formed reference, or names a secret the test did not set. A value set again is what
 * a {@link Secret} taken earlier reads from then on, as a rotated secret is.
 *
 * <pre>{@code
 * FakeRuntime rt = FakeRuntime.create();
 * FakeServices services = FakeServices.create(rt.clock()).secret("secret://partner-hmac", "k3y");
 * Stage stage = factory.create(step, rt.stepResources(dir, services));
 * }</pre>
 *
 * @since 0.1
 */
public final class FakeServices implements Services {

    private final Map<String, String> values = new ConcurrentHashMap<>();
    private final Cache cache;

    private FakeServices(Clock clock) {
        this.cache = new FakeCache(clock);
    }

    /** No secrets yet, and an empty cache on {@code clock}. */
    public static FakeServices create(Clock clock) {
        return new FakeServices(clock);
    }

    /** Sets the value of the secret {@code reference}, {@code secret://<name>}; returns these services. */
    public FakeServices secret(String reference, String value) {
        Secret.of(reference, () -> value);   // refuses a malformed reference
        values.put(reference, Objects.requireNonNull(value, "value"));
        return this;
    }

    @Override
    public Secret secret(StepConfig step, String key) {
        Objects.requireNonNull(step, "step");
        String reference = step.requireString(key);
        if (!reference.startsWith("secret://")) {
            throw new IllegalArgumentException(step + ": '" + key + "' must name a secret, secret://<name>; its value is not shown");
        }
        Secret secret;
        try {
            secret = Secret.of(reference, () -> values.get(reference));
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException(step + ": '" + key + "': " + malformed.getMessage(), malformed);
        }
        if (!values.containsKey(reference)) {
            throw new IllegalArgumentException(step + ": '" + key + "': secret '" + reference + "' is not set in these FakeServices");
        }
        return secret;
    }

    @Override
    public Cache cache() {
        return cache;
    }

    /** The references set, never a value. */
    @Override
    public String toString() {
        return "FakeServices[" + new java.util.TreeSet<>(values.keySet()) + "]";
    }
}
