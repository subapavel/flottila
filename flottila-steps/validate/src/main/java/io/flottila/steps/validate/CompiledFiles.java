package io.flottila.steps.validate;

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

/**
 * The files one schema compilation read besides its main file — every schema it includes or imports, nested ones
 * included — each with its bytes' hash then. The compile cache serves the compiled schema only to a flow whose files
 * are {@link #sameIn the same} (FIX-17). A compilation that read a file it could not find fails, so a read is always a
 * file.
 *
 * <p>A read lies where a compile in another flow would read it: a relative reference from the main file or from a
 * schema that moves lies at its path relative to the main file's directory, {@code ..} kept, and is confined there as
 * the compile would confine it. One reached through an absolute reference, or relative to a schema that was, stays
 * where it was, so another flow reaches it only if it lies inside that flow's directory too, and is otherwise refused
 * there.
 */
final class CompiledFiles {

    /** Where each read lies (relative to the main file's directory, or absolute when it does not move) and its bytes' hash. */
    private final Map<Path, Object> held;

    private CompiledFiles(Map<Path, Object> held) {
        this.held = held;
    }

    /**
     * Whether {@code resources}' flow, compiling the main file {@code main}, would read the same: every read located
     * against {@code main}'s directory lies inside the flow's directory (D-052: one outside means the compile there is
     * refused) and holds the same bytes. When it does, every located read is reported to the flow's {@code reads} sink,
     * as the compile there would report it; when it does not, none is.
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

    /** The hash of the file's bytes; for one that is missing or cannot be read a value equal to no other, so it is never the same. */
    static Object held(Path file, Hasher hasher) {
        try {
            return hasher.hashOf(Buffer.buffer(Files.readAllBytes(file)));
        } catch (IOException missingOrUnreadable) {
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
         * {@code path} was read, {@code reference} resolved against {@code baseUri} ({@code null}: none); a reference that
         * resolved is a URI reference. It lies at the reference's path against where its base's directory lies,
         * {@code ..} kept, so another flow resolves and confines it in its own directory as its compile would: a relative
         * reference from a schema that moves moves, an absolute one (a path is absolute) stays. A read with no base, or
         * one the recorder did not see, stays at {@code path}.
         */
        void read(String baseUri, String reference, Path path) {
            Path at = Optional.ofNullable(dirs.get(baseUri)).map(dir -> dir.resolve(URI.create(reference).getPath())).orElse(path);
            dirs.put(path.toUri().toString(), at.resolveSibling(""));
            reads.putIfAbsent(at, path);
        }

        /** The reads recorded and the hash of each one's bytes now. */
        CompiledFiles held(Hasher hasher) {
            Map<Path, Object> held = new LinkedHashMap<>();
            reads.forEach((at, path) -> held.put(at, CompiledFiles.held(path, hasher)));
            return new CompiledFiles(held);
        }
    }
}
