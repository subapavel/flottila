package io.rail8.bench;

import io.rail8.api.BlobStore;
import io.rail8.api.BodyKind;
import io.rail8.api.Stage;
import io.rail8.api.StepConfig;
import io.rail8.api.StepFactory;
import io.rail8.api.StepResources;

/**
 * The bench's stream step (P1-10c, GATE 9 "a 50 MB body through a stream step without heap growth"), type {@value #TYPE}: a
 * passthrough that reads the body through {@link io.rail8.api.Body#open()} and writes it as a stream into a new body under
 * {@link io.rail8.api.Envelope#nextKey()}, bounded by the flow's max body ({@link io.rail8.api.BodyTooLargeException} above it), then hands
 * the next envelope to the rest of the pipeline. It never holds the body in memory, so it is a {@link BodyKind#STREAM} step: a
 * flow whose max body is above the store's tree limit deploys it. No configuration. The stream-heap run's child node
 * ({@link StreamHeapNode}) names it among its steps, as {@code io.rail8.runtime.Main} names the shipped ones.
 *
 * @since 0.1
 */
public final class StreamCopy implements StepFactory {

    /** The flow's step type. */
    static final String TYPE = "stream-copy";

    @Override public String type() { return TYPE; }

    @Override public String configSchema() { return "{\"type\":\"object\"}"; }

    @Override public BodyKind bodyKind() { return BodyKind.STREAM; }

    @Override
    public Stage create(StepConfig step, StepResources resources) {
        BlobStore blobs = resources.blobs();
        long maxBodyBytes = resources.maxBodyBytes();
        return (e, rest) -> rest.apply(e.next(blobs.write(e.nextKey(), e.body().open(), maxBodyBytes).await()));
    }
}
