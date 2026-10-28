package io.flottila.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Runs {@code Cli.run(args, env, out, err)} in this JVM — never {@code System.exit} — and keeps what it printed (P2-03a test
 * fixture). The packages the cases use: the repository's {@code packages/demo} and the fixtures under
 * {@code src/test/resources/packages} (copies of the demo with one change each, and {@code tree}).
 */
final class CliRuns {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final Path DEMO = Path.of("../packages/demo");
    static final Path FIXTURES = Path.of("src/test/resources/packages");

    private CliRuns() {}

    /** What one run returned and printed; {@code out} and {@code err} split into lines (none for an empty stream). */
    record Ran(int exit, String out, String err) {
        List<String> outLines() { return out.lines().toList(); }
        List<String> errLines() { return err.lines().toList(); }

        /** The lines of {@code out} that start with {@code problem: }. */
        List<String> problems() { return outLines().stream().filter(l -> l.startsWith("problem: ")).toList(); }

        @Override public String toString() { return "exit " + exit + "\n--- out\n" + out + "--- err\n" + err; }
    }

    static Ran run(Map<String, String> env, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit;
        try (PrintStream o = new PrintStream(out, true, StandardCharsets.UTF_8); PrintStream e = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            exit = Cli.run(args, env, o, e);
        }
        return new Ran(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    static Ran run(String... args) {
        return run(Map.of(), args);
    }

    /** A copy of the package {@code from} at {@code to}, every regular file at its relative path. */
    static Path copy(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                Files.createDirectories(target.getParent());
                Files.copy(p, target);
            }
        }
        return to;
    }

    /** {@code file} with each {@code from → to} of {@code rewrites} applied; every one must hit. */
    static void rewrite(Path file, Map<String, String> rewrites) throws IOException {
        String text = Files.readString(file);
        for (Map.Entry<String, String> r : rewrites.entrySet()) {
            if (!text.contains(r.getKey())) throw new AssertionError("fixture: " + file + " holds no '" + r.getKey() + "': " + text);
            text = text.replace(r.getKey(), r.getValue());
        }
        Files.writeString(file, text);
    }
}
