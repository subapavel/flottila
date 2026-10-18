package io.flottila.testkit;

import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.Component;
import io.flottila.api.Envelope;
import io.flottila.api.Hasher;
import io.flottila.api.Headers;
import io.flottila.api.Runtime;
import io.vertx.core.buffer.Buffer;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Everything a step or stage test needs from {@link Runtime}, deterministic and in memory: a
 * {@link FakeClock} at {@link #T0}, {@link FakeIds} with prefix {@code m}, an
 * {@link InMemoryBlobStore} and the sha256 hasher. The builder methods mutate and return this
 * instance; use it from one test thread.
 *
 * @since 0.1
 */
public final class FakeRuntime {

    /** Where every fake clock starts. */
    public static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    /** The default {@link InMemoryBlobStore#treeLimit()}: 1 MiB. */
    public static final long DEFAULT_TREE_LIMIT = 1_048_576L;

    private final Hasher hasher = Hasher.sha256();
    private final FakeIds ids = new FakeIds("m");
    private final Map<String, Component> components = new LinkedHashMap<>();
    private FakeClock clock = FakeClock.at(T0);
    private InMemoryBlobStore blobs = new InMemoryBlobStore(hasher, DEFAULT_TREE_LIMIT);

    private FakeRuntime() { }

    public static FakeRuntime create() { return new FakeRuntime(); }

    public FakeClock clock() { return clock; }

    public FakeIds ids() { return ids; }

    public InMemoryBlobStore blobs() { return blobs; }

    public Hasher hasher() { return hasher; }

    /** Registers {@code component} under its scheme. */
    public FakeRuntime withComponent(Component component) {
        Objects.requireNonNull(component, "component");
        components.put(component.scheme(), component);
        return this;
    }

    /** Replaces the blob store by an empty one with this tree limit. */
    public FakeRuntime withTreeLimit(long treeLimit) {
        blobs = new InMemoryBlobStore(hasher, treeLimit);
        return this;
    }

    /** Replaces the clock by one standing at {@code start}. */
    public FakeRuntime withClockAt(Instant start) {
        clock = FakeClock.at(start);
        return this;
    }

    /** The api {@link Runtime} over exactly these instances. */
    public Runtime runtime() { return new Runtime(clock, ids, blobs, hasher, components); }

    /** A stage-0 envelope for {@code flow} whose body holds {@code bytes}, stored under a fresh id. */
    public Envelope ingress(String flow, Headers headers, Buffer bytes) {
        String id = ids.next();
        Body body = blobs.write(BlobKey.body(id, 0), bytes, blobs.treeLimit()).await();
        return Envelope.ingress(id, flow, headers, body);
    }

    /** Stores {@code bytes} as the body of the step after {@code e}; pass the result to {@link Envelope#next(Body)}. */
    public Body store(Envelope e, Buffer bytes) {
        return blobs.write(BlobKey.body(e.id(), e.step() + 1), bytes, blobs.treeLimit()).await();
    }
}
