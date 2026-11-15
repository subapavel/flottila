package io.flottila.api;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * @param dependencies the package's declared dependencies, each name to the directory its resolved version is unpacked in
 *                (D-087): {@code file("<name>:<path>")} reads {@code <path>} from that directory and stays inside it; empty
 *                for a package without dependencies
 * @since 0.1
 */
public record StepResources(Clock clock, Timer timer, IdGenerator ids, BlobStore blobs, Hasher hasher, long maxBodyBytes, Path baseDir,
                            java.util.function.Consumer<Path> reads, Map<String, Path> dependencies) {

    /**
     * A dependency reference: a package name before the first {@code /} (D-087). Anything else is a path from the package root;
     * a root file whose name has a colon is named {@code ./<name>}.
     */
    private static final Pattern DEPENDENCY = Pattern.compile("([a-z][a-z0-9-]{0,63}):");

    public StepResources {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(timer, "timer");
        Objects.requireNonNull(ids, "ids");
        Objects.requireNonNull(blobs, "blobs");
        Objects.requireNonNull(hasher, "hasher");
        Objects.requireNonNull(baseDir, "baseDir");
        Objects.requireNonNull(reads, "reads");
        dependencies = Map.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        if (maxBodyBytes <= 0) throw new IllegalArgumentException("maxBodyBytes must be positive, was " + maxBodyBytes);
    }

    /** Resources whose {@link #reads} sink is a no-op: nobody is told what the step read. */
    public StepResources(Clock clock, Timer timer, IdGenerator ids, BlobStore blobs, Hasher hasher, long maxBodyBytes, Path baseDir) {
        this(clock, timer, ids, blobs, hasher, maxBodyBytes, baseDir, path -> { });
    }

    /** Resources for a package without dependencies. */
    public StepResources(Clock clock, Timer timer, IdGenerator ids, BlobStore blobs, Hasher hasher, long maxBodyBytes, Path baseDir,
                         java.util.function.Consumer<Path> reads) {
        this(clock, timer, ids, blobs, hasher, maxBodyBytes, baseDir, reads, Map.of());
    }

    /**
     * {@code relative} against {@link #baseDir()}, absolute and normalised; a file a step reads at build time lies
     * inside the flow's directory (D-052), so a path that leaves it after normalisation — {@code ..}, an absolute path
     * elsewhere — is refused. Symbolic links are compared as paths, not followed. The path returned is reported to {@link #reads}.
     * {@code <name>:<path>} (a name matching {@code [a-z][a-z0-9-]{0,63}} before the first {@code /}) is {@code <path>} in the
     * directory of the dependency {@code <name>} and is confined to that directory; a name not in {@link #dependencies} is refused.
     * A relative path is confined to the flow's directory; an absolute one to whichever directory holds it, the package's or a
     * dependency's (D-087).
     *
     * @throws IllegalArgumentException naming {@code relative} (and the directory, when the path leaves it) when the path leaves
     *                                  its directory or names a package that is not a dependency
     */
    public Path file(String relative) {
        Objects.requireNonNull(relative, "relative");
        Matcher named = DEPENDENCY.matcher(relative);
        if (named.lookingAt()) {
            Path dir = dependencies.get(named.group(1));
            if (dir == null) throw new IllegalArgumentException(relative + " names package '" + named.group(1) + "', which is not a dependency");
            Path root = dir.toAbsolutePath().normalize();
            return confined(root.resolve(relative.substring(named.end())).normalize(), relative, root);
        }
        Path path = root().resolve(relative).normalize();
        return confined(path, relative, Path.of(relative).isAbsolute() ? holder(path.toUri()) : root());
    }

    /**
     * A file that a file the step reads includes or imports: {@code reference} resolved against {@code baseUri} (the
     * {@code file:} URI of the including file; {@code null} or blank: the flow's directory), held to the same rule as
     * {@link #file} (D-052) — the result is a file inside the flow's directory, compared as paths with symbolic links
     * not followed. A reference that resolves to anything but a {@code file:} URI (a URL over the network, a jar
     * entry) is refused as not a file. The path returned is reported to {@link #reads}. The directory is the one that holds
     * the including file: an include in a dependency's file stays in that dependency, one in the package stays in the package
     * (D-087); {@code <name>:} inside XML is a URI of another scheme, refused as not a file.
     *
     * @throws IllegalArgumentException naming {@code reference} and the directory when it is not a file or leaves it
     */
    public Path resolve(String baseUri, String reference) {
        Objects.requireNonNull(reference, "reference");
        Path root = root();
        Path target = null;
        IllegalArgumentException malformed = null;
        try {
            URI base = base(baseUri, root);
            URI resolved = base.resolve(reference);
            if ("file".equalsIgnoreCase(resolved.getScheme())) {
                target = Path.of(resolved);
                root = holder(base);
            }
        } catch (IllegalArgumentException x) {   // not a URI, or a file: URI that names no local path
            malformed = x;
        }
        if (target == null) throw new IllegalArgumentException(reference + " is not a file in the flow's directory " + root, malformed);
        return confined(target.normalize(), reference, root);
    }

    private Path root() {
        return baseDir.toAbsolutePath().normalize();
    }

    /**
     * The dependency directory that holds {@code file}, a URI compared by its normalised path (a {@code file:} URI below the
     * directory's); the package directory when none does — a URI of another scheme included — where it is then confined.
     */
    private Path holder(URI file) {
        for (Path dir : dependencies.values()) {
            Path root = dir.toAbsolutePath().normalize();
            if (!directory(root).relativize(file).equals(file)) return root;
        }
        return root();
    }

    /** {@code baseUri}; without one, the directory's URI. */
    private static URI base(String baseUri, Path root) {
        if (baseUri != null && !baseUri.isBlank()) return URI.create(baseUri);
        return directory(root);
    }

    /** The directory as a URI; with the trailing slash a directory that does not exist yet lacks. */
    private static URI directory(Path dir) {
        String uri = dir.toUri().toString();
        return URI.create(uri.endsWith("/") ? uri : uri + "/");
    }

    /** {@code path} when it lies inside {@code root}, reported to {@link #reads}; refused, and not reported, when it leaves it. */
    private Path confined(Path path, String reference, Path root) {
        if (!path.startsWith(root)) throw new IllegalArgumentException(reference + " leaves the flow's directory " + root);
        reads.accept(path);
        return path;
    }
}
