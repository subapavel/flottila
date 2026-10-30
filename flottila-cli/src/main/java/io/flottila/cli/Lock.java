package io.flottila.cli;

import io.flottila.schemas.Package;
import io.flottila.schemas.Schemas;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code flottila lock <dir> [--deps <dir>]} (D-087, D-089): pins every dependency {@code package.yaml} declares (the first
 * declaration of a name) with its candidate's content digest ({@link Dependencies#find}) and writes {@code package.lock} —
 * {@code kind: lock}, the entries sorted by name, an empty list without dependencies — over the previous one, then prints one
 * line per entry, {@code <name>@<version> sha256:<hex>}; exit 0. A file of the package that does not parse, a {@code package.yaml}
 * that does not pass its schema, or a dependency without its one candidate: a {@code problem: } line each, nothing written (an
 * existing lock untouched), exit 1.
 */
final class Lock {

    private Lock() {}

    static int run(Cli.Options options, PrintStream out) throws IOException {
        Path dir = options.dir();
        Package pkg;
        try {
            pkg = Package.load(dir);
        } catch (IllegalArgumentException unreadable) {   // its message starts with the file's path from the package root
            return refused(List.of(Cli.oneLine(unreadable.getMessage())), out);
        }
        JsonObject manifest = pkg.manifest();
        Schemas.Report report = Schemas.PACKAGE.validate(manifest);
        if (!report.valid()) {
            return refused(report.problems().stream().map(p -> "package.yaml at '" + p.path() + "': " + Cli.oneLine(p.message())).toList(), out);
        }
        Map<String, String> declared = new TreeMap<>();   // by name: the lock's order
        for (Object d : manifest.getJsonArray("dependencies", new JsonArray())) {
            declared.putIfAbsent(((JsonObject) d).getString("name"), ((JsonObject) d).getString("version"));   // a second one is Package's problem
        }
        Dependencies deps = Dependencies.of(options);
        List<String> problems = new ArrayList<>();
        List<Package.Locked> pins = new ArrayList<>();
        for (Map.Entry<String, String> d : declared.entrySet()) deps.find(d.getKey(), d.getValue(), problems).ifPresent(f -> pins.add(f.pin()));
        if (!problems.isEmpty()) return refused(problems, out);
        Files.writeString(dir.resolve("package.lock"), text(pins));
        pins.forEach(p -> out.println(p.name() + "@" + p.version() + " " + p.content()));
        return 0;
    }

    private static int refused(List<String> problems, PrintStream out) {
        problems.forEach(p -> out.println("problem: " + p));
        return 1;
    }

    /**
     * {@code package.lock} pinning {@code pins} in their order, as {@link Package#load} reads it and {@link Schemas#LOCK} takes it:
     * every value a double-quoted YAML scalar (backslash and quote escaped), so a name such as {@code null} or {@code true}
     * reads back as the string it is.
     */
    static String text(List<Package.Locked> pins) {
        StringBuilder text = new StringBuilder("# written by flottila lock (D-087): the direct dependencies, sorted by name, each with its content digest\n")
                .append("schemaVersion: 1\nkind: lock\ndependencies:").append(pins.isEmpty() ? " []\n" : "\n");
        for (Package.Locked p : pins) {
            text.append("  - name: ").append(quoted(p.name())).append("\n    version: ").append(quoted(p.version()))
                    .append("\n    content: ").append(quoted(p.content())).append('\n');
        }
        return text.toString();
    }

    /** {@code value} as a double-quoted YAML scalar. */
    private static String quoted(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
