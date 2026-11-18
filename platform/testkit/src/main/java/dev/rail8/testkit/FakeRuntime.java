package dev.rail8.testkit;

import dev.rail8.api.BlobKey;
import dev.rail8.api.Body;
import dev.rail8.api.Component;
import dev.rail8.api.Envelope;
import dev.rail8.api.Hasher;
import dev.rail8.api.Headers;
import dev.rail8.api.Runtime;
import dev.rail8.api.Services;
import dev.rail8.api.StepResources;
import io.vertx.core.buffer.Buffer;

import java.nio.file.Path;
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
    private FakeTimer timer;
    private final Map<String, Component> components = new LinkedHashMap<>();
    private FakeClock clock = FakeClock.at(T0);
    { timer = new FakeTimer(clock); }
    private InMemoryBlobStore blobs = new InMemoryBlobStore(hasher, DEFAULT_TREE_LIMIT);

    private FakeRuntime() { }

    public static FakeRuntime create() { return new FakeRuntime(); }

    public FakeClock clock() { return clock; }

    public FakeIds ids() { return ids; }

    /** The fake timer, driven by {@link #clock()}. */
    public FakeTimer timer() { return timer; }

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
        timer = new FakeTimer(clock);
        return this;
    }

    /** The api {@link Runtime} over exactly these instances. */
    public Runtime runtime() { return new Runtime(clock, timer, ids, blobs, hasher, components); }

    /**
     * What a step's factory gets, over these instances: the store's tree limit as the body limit, {@code baseDir} as the package
     * directory, no dependencies, and {@code services} (a {@link FakeServices}, typically).
     */
    public StepResources stepResources(Path baseDir, Services services) {
        return new StepResources(clock, timer, ids, blobs, hasher, blobs.treeLimit(), baseDir, path -> { }, Map.of(), services);
    }

    /** A stage-0 envelope for {@code flow} whose body holds {@code bytes}, stored under a fresh id. */
    public Envelope ingress(String flow, Headers headers, Buffer bytes) {
        String id = ids.next();
        Body body = blobs.write(BlobKey.body(id, 0), bytes, blobs.treeLimit()).await();
        return Envelope.ingress(id, flow, headers, body);
    }

    /**
     * Stores {@code bytes} as the body of the step after {@code e}, under {@link Envelope#nextKey()} — keyed by the run's attempt
     * from the second on; pass the result to {@link Envelope#next(Body)}.
     */
    public Body store(Envelope e, Buffer bytes) {
        return blobs.write(e.nextKey(), bytes, blobs.treeLimit()).await();
    }
}
