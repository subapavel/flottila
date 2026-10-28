package io.flottila.api;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;

/**
 * What a {@link StepFactory} or {@link Expressions} may hold on to at build time: the injected clock, timer,
 * ids, blob store and hasher, the flow's body limit, the directory its relative file references resolve
 * against (the package's: a reference is a path from the package root, D-078) and the sink told each file read through {@link #file} and {@link #resolve}. Not the {@link Runtime}: that is used inside the flow builder and nowhere else
 * (CLAUDE.md); components are reached only through the builder's {@code to} step.
 *
 * @param baseDir where a step's relative file reference (a stylesheet, a schema) resolves, and the directory every
 *                file the step reads at build time lies inside ({@link #file}, {@link #resolve}): the package directory (D-078);
 *                "the flow's directory" below and in the refusals means this directory
 * @param reads   gets every path {@link #file} and {@link #resolve} return, once per call, as returned, and never one they
 *                refuse: the files a flow's build read, for its effective hash (D-063)
 * @since 0.1
 */
public record StepResources(Clock clock, Timer timer, IdGenerator ids, BlobStore blobs, Hasher hasher, long maxBodyBytes, Path baseDir,
                            java.util.function.Consumer<Path> reads) {

    public StepResources {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(timer, "timer");
        Objects.requireNonNull(ids, "ids");
        Objects.requireNonNull(blobs, "blobs");
        Objects.requireNonNull(hasher, "hasher");
        Objects.requireNonNull(baseDir, "baseDir");
        Objects.requireNonNull(reads, "reads");
        if (maxBodyBytes <= 0) throw new IllegalArgumentException("maxBodyBytes must be positive, was " + maxBodyBytes);
    }

    /** Resources whose {@link #reads} sink is a no-op: nobody is told what the step read. */
    public StepResources(Clock clock, Timer timer, IdGenerator ids, BlobStore blobs, Hasher hasher, long maxBodyBytes, Path baseDir) {
        this(clock, timer, ids, blobs, hasher, maxBodyBytes, baseDir, path -> { });
    }

    /**
     * {@code relative} against {@link #baseDir()}, absolute and normalised; a file a step reads at build time lies
     * inside the flow's directory (D-052), so a path that leaves it after normalisation — {@code ..}, an absolute path
     * elsewhere — is refused. Symbolic links are compared as paths, not followed. The path returned is reported to {@link #reads}.
     *
     * @throws IllegalArgumentException naming {@code relative} and the directory when the path leaves it
     */
    public Path file(String relative) {
        Objects.requireNonNull(relative, "relative");
        Path root = root();
        return confined(root.resolve(relative).normalize(), relative, root);
    }

    /**
     * A file that a file the step reads includes or imports: {@code reference} resolved against {@code baseUri} (the
     * {@code file:} URI of the including file; {@code null} or blank: the flow's directory), held to the same rule as
     * {@link #file} (D-052) — the result is a file inside the flow's directory, compared as paths with symbolic links
     * not followed. A reference that resolves to anything but a {@code file:} URI (a URL over the network, a jar
     * entry) is refused as not a file. The path returned is reported to {@link #reads}.
     *
     * @throws IllegalArgumentException naming {@code reference} and the directory when it is not a file or leaves it
     */
    public Path resolve(String baseUri, String reference) {
        Objects.requireNonNull(reference, "reference");
        Path root = root();
        Path target = null;
        IllegalArgumentException malformed = null;
        try {
            URI resolved = base(baseUri, root).resolve(reference);
            if ("file".equalsIgnoreCase(resolved.getScheme())) target = Path.of(resolved);
        } catch (IllegalArgumentException x) {   // not a URI, or a file: URI that names no local path
            malformed = x;
        }
        if (target == null) throw new IllegalArgumentException(reference + " is not a file in the flow's directory " + root, malformed);
        return confined(target.normalize(), reference, root);
    }

    private Path root() {
        return baseDir.toAbsolutePath().normalize();
    }

    /** The directory as a base URI; with the trailing slash a directory that does not exist yet lacks. */
    private static URI base(String baseUri, Path root) {
        if (baseUri != null && !baseUri.isBlank()) return URI.create(baseUri);
        String dir = root.toUri().toString();
        return URI.create(dir.endsWith("/") ? dir : dir + "/");
    }

    /** {@code path} when it lies inside {@code root}, reported to {@link #reads}; refused, and not reported, when it leaves it. */
    private Path confined(Path path, String reference, Path root) {
        if (!path.startsWith(root)) throw new IllegalArgumentException(reference + " leaves the flow's directory " + root);
        reads.accept(path);
        return path;
    }
}
