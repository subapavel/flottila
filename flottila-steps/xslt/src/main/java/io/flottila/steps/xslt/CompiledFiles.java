package io.flottila.steps.xslt;

import io.flottila.api.Hasher;
import io.flottila.api.StepResources;
import io.vertx.core.buffer.Buffer;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * What one compilation read besides its main file — a module it includes or imports, nested ones, an external entity,
 * a static {@code doc()} or {@code unparsed-text()}, a file {@code unparsed-text-available()} probed as absent, a
 * directory {@code uri-collection()} listed — each with what it held then: the bytes' hash, the absence, the entry
 * names. The compile cache serves the compiled stylesheet only to a flow whose files are {@link #sameIn the same}
 * (FIX-17).
 *
 * <p>A read lies where a compile in another flow would read it: a relative reference from the main file or from a
 * module that moves lies at its path relative to the main file's directory, {@code ..} kept, and is confined there as
 * the compile would confine it. One reached through an absolute reference, relative to a module that was, or handed
 * by Saxon already absolute and without a base (a static {@code unparsed-text()}, a collection) stays where it was, so
 * another flow reaches it only if it lies inside that flow's directory too, and is otherwise refused there.
 */
final class CompiledFiles {

    /** What a path that is neither a directory nor a regular file holds. */
    private static final Object ABSENT = new Object();

    /** Where each read lies (relative to the main file's directory, or absolute when it does not move) and what it held. */
    private final Map<Path, Object> held;

    private CompiledFiles(Map<Path, Object> held) {
        this.held = held;
    }

    /**
     * Whether {@code resources}' flow, compiling the main file {@code main}, would read the same: every read located
     * against {@code main}'s directory lies inside the flow's directory (D-052: one outside means the compile there is
     * refused) and holds what it held. When it does, every located read is reported to the flow's {@code reads} sink, as
     * the compile there would report it; when it does not, none is.
     */
    boolean sameIn(Path main, StepResources resources) {
        StepResources unreported = new StepResources(resources.clock(), resources.timer(), resources.ids(), resources.blobs(),
                resources.hasher(), resources.maxBodyBytes(), resources.baseDir());
        List<Path> located = new ArrayList<>(held.size());
        for (Map.Entry<Path, Object> read : held.entrySet()) {
            Path file;
            try {
                file = unreported.file(main.getParent().resolve(read.getKey()).toString());
            } catch (IllegalArgumentException outside) {
                return false;
            }
            if (!read.getValue().equals(held(file, resources.hasher()))) return false;
            located.add(file);
        }
        located.forEach(resources.reads());
        return true;
    }

    /**
     * What {@code path} holds now: a directory its entry names, sorted; a regular file its bytes' hash; anything else
     * (nothing there) absent; one that cannot be read a value equal to no other, so it is never the same.
     */
    static Object held(Path path, Hasher hasher) {
        try {
            if (Files.isDirectory(path)) {
                try (Stream<Path> entries = Files.list(path)) {
                    return entries.map(entry -> entry.getFileName().toString()).sorted().toList();
                }
            }
            return Files.isRegularFile(path) ? hasher.hashOf(Buffer.buffer(Files.readAllBytes(path))) : ABSENT;
        } catch (IOException unreadable) {
            return new Object();
        }
    }

    /** Records one compilation's reads, on the thread that compiles. */
    static final class Recorder {
        /** Where a read lies → the path read. */
        private final Map<Path, Path> reads = new LinkedHashMap<>();
        /**
         * The URI each read (and the main file) hands as the base of what it reads → where its directory lies: relative
         * to the main file's directory when it moves with it, absolute when it does not.
         */
        private final Map<String, Path> dirs = new HashMap<>();

        /** @param main the main file, absolute and normalised */
        Recorder(Path main) {
            dirs.put(main.toUri().toString(), Path.of(""));
        }

        /**
         * {@code path} was read, {@code reference} resolved against {@code baseUri} ({@code null}: Saxon made the reference
         * absolute itself); a reference that
         * resolved is a URI reference. It lies at the reference's path against where its base's directory lies,
         * {@code ..} kept, so another flow resolves and confines it in its own directory as its compile would: a relative
         * reference from a module that moves moves, an absolute one (a path is absolute) stays. A read with no base, or
         * one the recorder did not see, stays at {@code path}.
         */
        void read(String baseUri, String reference, Path path) {
            Path at = Optional.ofNullable(dirs.get(baseUri)).map(dir -> dir.resolve(URI.create(reference).getPath())).orElse(path);
            dirs.put(path.toUri().toString(), at.resolveSibling(""));
            reads.putIfAbsent(at, path);
        }

        /** The reads recorded and what each holds now. */
        CompiledFiles held(Hasher hasher) {
            Map<Path, Object> held = new LinkedHashMap<>();
            reads.forEach((at, path) -> held.put(at, CompiledFiles.held(path, hasher)));
            return new CompiledFiles(held);
        }
    }
}
