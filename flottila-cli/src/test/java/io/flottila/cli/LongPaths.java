package io.flottila.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * FIX-110 test fixture: directories a root process cannot read (chmod does not stop root) — a chain whose absolute path is longer
 * than Linux's PATH_MAX (4096 bytes with its NUL), where every system call naming it fails with ENAMETOOLONG. Built and removed by
 * renames of short paths only, so neither needs a path the kernel refuses; a case removes its chain itself (JUnit's {@code @TempDir}
 * cleanup walks absolute paths and could not).
 */
final class LongPaths {

    /** The kernel's PATH_MAX: a path of this many bytes or more (before its NUL) is refused. */
    static final int PATH_MAX = 4096;

    /** A directory name of 200 characters: the chain's links. */
    static final String LINK = "l".repeat(200);

    private LongPaths() {}

    /**
     * Creates {@code top} and below it one directory per entry of {@code below}, each inside the one before, by renames under
     * {@code stage} (created when absent): the deepest first, each moved into a fresh parent. {@code top} and {@code stage} are
     * short paths; the chain may be as long as {@code below} makes it.
     */
    static void chain(Path top, List<String> below, Path stage) throws IOException {
        Files.createDirectories(stage);
        Path built = Files.createDirectory(stage.resolve("built-0"));
        for (int i = below.size() - 1; i >= 0; i--) {
            Path parent = Files.createDirectory(stage.resolve("built-" + (below.size() - i)));
            Files.move(built, parent.resolve(below.get(i)));
            built = parent;
        }
        Files.move(built, top);
    }

    /** Removes what {@link #chain} built at {@code top} with these {@code below}, by renames under {@code stage}; then {@code stage} if empty. */
    static void unchain(Path top, List<String> below, Path stage) throws IOException {
        Files.createDirectories(stage);
        Path at = top;
        for (int i = 0; i < below.size(); i++) {
            Path moved = stage.resolve("removed-" + i);
            Files.move(at.resolve(below.get(i)), moved);
            Files.delete(at);
            at = moved;
        }
        Files.delete(at);
        try (var left = Files.list(stage)) {
            if (left.findAny().isEmpty()) Files.delete(stage);
        }
    }

    /** Links ({@link #LINK}) enough that {@code top}'s absolute path, with them below it, reaches {@link #PATH_MAX}. */
    static List<String> pastPathMax(Path top) {
        List<String> links = new ArrayList<>();
        int length = top.toAbsolutePath().normalize().toString().length();
        while (length < PATH_MAX) {
            links.add(LINK);
            length += 1 + LINK.length();
        }
        return links;
    }

    /**
     * Names below {@code top} — {@code top} relative to the working directory — that end with {@code leaf} and make the absolute
     * path of that leaf exactly {@link #PATH_MAX} long, while its relative path stays shorter: the leaf is reachable relative to the
     * working directory, never by its absolute path.
     */
    static List<String> exactlyPathMax(Path top, String leaf) {
        int cwd = Path.of("").toAbsolutePath().toString().length();
        int relative = PATH_MAX - cwd - 1;   // cwd + '/' + relative = PATH_MAX
        int length = top.toString().length() + 1 + leaf.length();
        List<String> names = new ArrayList<>();
        while (relative - length > LINK.length() + 1) {
            names.add(LINK);
            length += LINK.length() + 1;
        }
        int pad = relative - length - 1;
        if (pad < 1) throw new AssertionError("fixture: no room for a pad link (" + relative + ", " + length + ")");
        names.add("p".repeat(pad));
        names.add(leaf);
        return names;
    }
}
