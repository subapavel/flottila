package io.flottila.api;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;

/**
 * What a {@link StepFactory} or {@link Expressions} may hold on to at build time: the injected clock, timer,
 * ids, blob store and hasher, the flow's body limit and the directory its relative file references resolve
 * against (the flow file's). Not the {@link Runtime}: that is used inside the flow builder and nowhere else
 * (CLAUDE.md); components are reached only through the builder's {@code to} step.
 *
 * @param baseDir where a step's relative file reference (a stylesheet, a schema) resolves
 * @since 0.1
 */
public record StepResources(Clock clock, Timer timer, IdGenerator ids, BlobStore blobs, Hasher hasher, long maxBodyBytes, Path baseDir) {

    public StepResources {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(timer, "timer");
        Objects.requireNonNull(ids, "ids");
        Objects.requireNonNull(blobs, "blobs");
        Objects.requireNonNull(hasher, "hasher");
        Objects.requireNonNull(baseDir, "baseDir");
        if (maxBodyBytes <= 0) throw new IllegalArgumentException("maxBodyBytes must be positive, was " + maxBodyBytes);
    }

    /** {@code relative} against {@link #baseDir()}; an absolute path stays as it is. */
    public Path file(String relative) {
        return baseDir.resolve(Objects.requireNonNull(relative, "relative")).normalize();
    }
}
