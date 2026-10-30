package io.flottila.cli;

import io.flottila.schemas.Package;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A package's dependencies resolved off the node (D-087, D-089), for {@code lock}, {@code validate} and {@code test}: the candidates
 * are the immediate subdirectories of the candidates directory — {@code --deps <dir>}, else the package directory's parent —
 * that hold a {@code package.yaml} and load ({@link Package#load}), the package's own directory excluded; a candidate whose files
 * do not load, or cannot be read or walked for its content ({@link Package#content}: an {@link IOException} or
 * {@link UncheckedIOException}), is none. They are read once, at the first lookup: a package without dependencies reads nothing
 * of the directory; a candidates directory that cannot be listed is the one problem {@code cannot list <its name>}. A
 * candidate matches a dependency by its package.yaml's name and version (not its directory's name); two matches with different
 * content ({@link Package#content}) are refused. A message names a candidate by its directory's name and the candidates directory
 * by its own name, never by a path.
 */
final class Dependencies {

    private final Path deps;
    private final Path own;
    /** The candidates directory's own name, as the messages name it. */
    private final String under;
    private List<Candidate> candidates;   // null until the first lookup
    private boolean unlisted;   // the candidates directory could not be listed: candidates is empty

    private Dependencies(Path deps, Path own) {
        this.deps = deps;
        this.own = own;
        this.under = Objects.toString(deps.getFileName(), "/");
    }

    /** One candidate: its directory's name and path, the package as loaded, its package.yaml's name and version as written, its content. */
    private record Candidate(String folder, Path dir, Package pkg, Object name, Object version, String content) {}

    /**
     * A dependency resolved to its candidate.
     *
     * @param pin    its name, version and the candidate's content digest
     * @param folder the candidate directory's name
     * @param dir    the candidate directory
     * @param lock   the candidate's own {@code package.lock} entries ({@link Package#lock()})
     */
    record Found(Package.Locked pin, String folder, Path dir, List<Package.Locked> lock) {}

    /** The candidates of the package directory {@code options.dir()}: under {@code --deps} when given (a directory: {@link Cli#options}), else its parent. */
    static Dependencies of(Cli.Options options) {
        Path own = options.dir();
        Path parent = own.toAbsolutePath().normalize().resolve("..");
        return new Dependencies(options.value("--deps").map(Path::of).orElse(parent).toAbsolutePath().normalize(), own);
    }

    /**
     * The one candidate for {@code name} at {@code version}; empty with a line added to {@code problems} when no candidate has that
     * name and version ({@code dependency <name>@<version>: no candidate under <deps> (it has <name> <versions> | no package <name>)})
     * or two that do have different content; empty with the line {@code cannot list <deps>} (once) when the candidates directory
     * cannot be listed.
     *
     * @throws IOException a candidate cannot be compared with the package's own directory
     */
    Optional<Found> find(String name, String version, List<String> problems) throws IOException {
        List<Candidate> all = candidates();
        if (unlisted) {
            String line = "cannot list " + under;   // the directory by its name only, never by its path
            if (!problems.contains(line)) problems.add(line);   // once, whatever number of dependencies is looked up
            return Optional.empty();
        }
        List<Candidate> named = all.stream().filter(c -> name.equals(c.name())).toList();
        List<Candidate> matching = named.stream().filter(c -> version.equals(c.version())).toList();
        String dependency = "dependency " + name + "@" + version + ": ";
        if (matching.isEmpty()) {
            List<String> versions = named.stream().map(c -> String.valueOf(c.version())).distinct().sorted().toList();
            problems.add(dependency + "no candidate under " + under + " ("
                    + (versions.isEmpty() ? "no package " + name : "it has " + name + " " + String.join(", ", versions)) + ")");
            return Optional.empty();
        }
        TreeSet<String> contents = new TreeSet<>();
        for (Candidate c : matching) contents.add(c.content());
        if (contents.size() > 1) {
            problems.add(dependency + "candidates " + matching.stream().map(Candidate::folder).collect(Collectors.joining(", ")) + " under " + under
                    + " have different content");
            return Optional.empty();
        }
        Candidate c = matching.getFirst();
        return Optional.of(new Found(new Package.Locked(name, version, contents.getFirst()), c.folder(), c.dir(), c.pkg().lock()));
    }

    /**
     * The closure of {@code lock} — the lock of the package {@code name} — as a node resolves it (D-087), leaves first: each entry's
     * candidate ({@link #find}) with the content the entry pins, then that candidate's own lock, each dependency after those it
     * depends on and each once. Every problem is a line added to {@code problems}: a name met again on the way is a cycle
     * ({@code dependency cycle: a -> b -> a}, checked before any candidate lookup), a content other than the pinned one is a drift
     * ({@code package.lock pins <name>@<version> <pinned>; candidate <folder> has <content>}).
     */
    List<Found> resolve(String name, List<Package.Locked> lock, List<String> problems) throws IOException {
        Map<Path, Found> closure = new LinkedHashMap<>();
        walk(lock, new ArrayList<>(List.of(name)), closure, problems);
        return List.copyOf(closure.values());
    }

    private void walk(List<Package.Locked> entries, List<String> path, Map<Path, Found> closure, List<String> problems) throws IOException {
        for (Package.Locked entry : entries) {
            if (path.contains(entry.name())) {
                problems.add("dependency cycle: " + String.join(" -> ", path) + " -> " + entry.name());
                continue;
            }
            Optional<Found> found = find(entry.name(), entry.version(), problems);
            if (found.isEmpty()) continue;
            Found f = found.get();
            if (!f.pin().content().equals(entry.content())) {
                problems.add("package.lock pins " + entry.name() + "@" + entry.version() + " " + entry.content() + "; candidate " + f.folder()
                        + " has " + f.pin().content());
                continue;
            }
            path.add(entry.name());
            walk(f.lock(), path, closure, problems);
            path.removeLast();
            closure.putIfAbsent(f.dir(), f);   // after its own dependencies: leaves first
        }
    }

    private List<Candidate> candidates() throws IOException {
        if (candidates == null) candidates = scan();
        return candidates;
    }

    /** Every candidate in directory-name order (see the class comment); none, {@link #unlisted} set, when {@link #deps} cannot be listed. */
    private List<Candidate> scan() throws IOException {
        List<Path> dirs;
        try (Stream<Path> listed = Files.list(deps)) {
            dirs = listed.filter(d -> Files.isRegularFile(d.resolve("package.yaml"))).sorted().toList();
        } catch (IOException | UncheckedIOException cannotList) {   // its message holds the absolute path: find names the folder instead
            unlisted = true;
            return List.of();
        }
        List<Candidate> found = new ArrayList<>();
        for (Path d : dirs) {
            if (Files.isSameFile(d, own)) continue;   // the package itself is no candidate
            try {
                Package pkg = Package.load(d);
                JsonObject manifest = pkg.manifest();
                found.add(new Candidate(d.getFileName().toString(), d, pkg, manifest.getValue("name"), manifest.getValue("version"), Package.content(d)));
            } catch (IllegalArgumentException | IOException | UncheckedIOException unreadable) {
                // a file of it does not parse, or it cannot be listed, walked or read: no candidate, and no stop to the others
            }
        }
        return List.copyOf(found);
    }
}
