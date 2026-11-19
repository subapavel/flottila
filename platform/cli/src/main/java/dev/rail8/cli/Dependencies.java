package dev.rail8.cli;

import dev.rail8.api.Timer;
import dev.rail8.runtime.git.Commit;
import dev.rail8.runtime.git.GitCache;
import dev.rail8.runtime.git.GitFailure;
import dev.rail8.runtime.git.GitSettings;
import dev.rail8.runtime.git.PackageRepositories;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.schemas.Package;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A package's dependencies off the node, for {@code lock}, {@code validate} and {@code test}, read through the git seam as a node
 * reads them: the candidates directory — {@code --deps <dir>}, else the package directory's parent — is the {@code file://} base of
 * the package repositories, each clone in it the repository of its folder's name, the package at the clone's root. The seam's cache
 * and the folders of the commits it writes lie in a temporary directory made at the first read and deleted by {@link #close()}.
 *
 * <p>{@code lock} finds a dependency among the candidates ({@link #pin}): the immediate subdirectories of the candidates directory
 * that hold a {@code package.yaml} and load ({@link Package#load}), the package's own directory excluded; a candidate whose files do
 * not load, or cannot be read or walked for its content ({@link Package#content}: an {@link IOException} or
 * {@link UncheckedIOException}), is none. They are read once, at the first lookup: a package without dependencies reads nothing of
 * the directory; a candidates directory that cannot be listed is the one problem {@code cannot list <its name>}. A candidate matches
 * a dependency by its package.yaml's name and version (not its directory's name); two matches with different content are refused.
 * The one candidate must lie at the root of its git working tree, and its files, without {@code .git}, must be those of its
 * {@code HEAD}.
 *
 * <p>{@code validate} and {@code test} read each dependency from the repository and commit its lock pins ({@link #resolve}), never
 * from a candidate found by name.
 *
 * <p>A message names a candidate by its directory's name, the candidates directory by its own name and a commit by its repository
 * and id, never by a path.
 */
final class Dependencies implements AutoCloseable {

    /** The fetches' deadline, on the JDK's shared delay scheduler: the command line owns no timer thread. */
    private static final Timer WALL = (delay, task) -> {
        CompletableFuture<Void> due = CompletableFuture.runAsync(task, CompletableFuture.delayedExecutor(delay.toNanos(), TimeUnit.NANOSECONDS));
        return () -> due.cancel(false);
    };

    private final Path deps;
    private final Path own;
    /** The candidates directory's own name, as the messages name it. */
    private final String under;
    private List<Candidate> candidates;   // null until the first lookup
    private boolean unlisted;   // the candidates directory could not be listed: candidates is empty
    private Path temp;   // null until the first read through git
    private PackageRepositories repositories;
    private int folders;   // the commits written so far, each into a folder of its own
    /** The commits read so far, by {@code <repository> <commit>}. */
    private final Map<String, Found> commits = new HashMap<>();

    private Dependencies(Path deps, Path own) {
        this.deps = deps;
        this.own = own;
        this.under = Objects.toString(deps.getFileName(), "/");
    }

    /** One candidate: its directory's name and path, its package.yaml's name and version as written, its content. */
    private record Candidate(String folder, Path dir, Object name, Object version, String content) {}

    /**
     * A dependency resolved to the commit its lock pins.
     *
     * @param pin  the lock's entry
     * @param dir  the folder the commit's files were written into, the run's own
     * @param lock the dependency's own {@code package.lock} entries ({@link Package#lock()})
     */
    record Found(Package.Locked pin, Path dir, List<Package.Locked> lock) {}

    /** The candidates of the package directory {@code options.dir()}: under {@code --deps} when given (a directory: {@link Cli#options}), else its parent. */
    static Dependencies of(Cli.Options options) {
        Path own = options.dir();
        Path parent = own.toAbsolutePath().normalize().resolve("..");
        return new Dependencies(options.value("--deps").map(Path::of).orElse(parent).toAbsolutePath().normalize(), own);
    }

    /**
     * The pin of {@code name} at {@code version}: its one candidate's folder name as the repository, that clone's {@code HEAD} as the
     * commit, and the content of {@code HEAD}. Empty with a line added to {@code problems} when no candidate has that name and version
     * ({@code dependency <name>@<version>: no candidate under <deps> (it has <name> <versions> | no package <name>)}), two that do
     * have different content, the candidate is not at the root of a git working tree, its files differ from its {@code HEAD}, or git
     * cannot answer its {@code HEAD}; empty with the line {@code cannot list <deps>} (once) when the candidates directory cannot be
     * listed.
     *
     * @throws IOException a candidate cannot be compared with the package's own directory, or a commit's files cannot be written
     */
    Optional<Package.Locked> pin(String name, String version, List<String> problems) throws IOException {
        Optional<Candidate> found = find(name, version, problems);
        if (found.isEmpty()) return Optional.empty();
        Candidate c = found.get();
        String dependency = "dependency " + name + "@" + version + ": ";
        if (!Files.exists(c.dir().resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
            problems.add(dependency + c.folder() + " is not at the root of a git working tree");
            return Optional.empty();
        }
        Commit head;
        try {
            head = repositories().forDeploy(c.folder(), "HEAD");
        } catch (IllegalArgumentException notAName) {
            problems.add(dependency + "the folder name " + c.folder() + " names no repository: " + Cli.oneLine(notAName.getMessage()));
            return Optional.empty();
        } catch (GitFailure failed) {
            problems.add(dependency + failed.getMessage());
            return Optional.empty();
        }
        Optional<Path> files = written(head, dependency, problems);
        if (files.isEmpty()) return Optional.empty();
        String content = Package.content(files.get());
        if (!content.equals(c.content())) {
            problems.add(dependency + c.folder() + " differs from its HEAD " + head.id() + ": commit it first");
            return Optional.empty();
        }
        return Optional.of(new Package.Locked(name, version, content, c.folder(), head.id()));
    }

    /**
     * The one candidate for {@code name} at {@code version}; empty with a line added to {@code problems} when there is none or two
     * with different content, or when the candidates directory cannot be listed (see {@link #pin}).
     */
    private Optional<Candidate> find(String name, String version, List<String> problems) throws IOException {
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
        return Optional.of(matching.getFirst());
    }

    /**
     * The closure of {@code lock} — the lock of the package {@code name} — as a node resolves it, leaves first: each entry's files read
     * from the repository and commit it pins, with the content it pins, then that commit's own lock, each dependency after those it
     * depends on and each once. Every problem is a line added to {@code problems}: a name met again on the way is a cycle
     * ({@code dependency cycle: a -> b -> a}, checked before anything is read), a commit git cannot answer names its repository, its
     * commit and why, and a content other than the pinned one is a drift
     * ({@code package.lock pins <name>@<version> <pinned>; <repository> at <commit> has <content>}).
     *
     * @throws IOException a commit's files cannot be written or read
     */
    List<Found> resolve(String name, List<Package.Locked> lock, List<String> problems) throws IOException {
        Map<String, Found> closure = new LinkedHashMap<>();
        walk(lock, new ArrayList<>(List.of(name)), closure, problems);
        return List.copyOf(closure.values());
    }

    private void walk(List<Package.Locked> entries, List<String> path, Map<String, Found> closure, List<String> problems) throws IOException {
        for (Package.Locked entry : entries) {
            if (path.contains(entry.name())) {
                problems.add("dependency cycle: " + String.join(" -> ", path) + " -> " + entry.name());
                continue;
            }
            Optional<Found> found = read(entry, problems);
            if (found.isEmpty()) continue;
            Found f = found.get();
            path.add(entry.name());
            walk(f.lock(), path, closure, problems);
            path.removeLast();
            closure.putIfAbsent(entry.repository() + " " + entry.commit(), f);   // after its own dependencies: leaves first
        }
    }

    /**
     * The files of the commit {@code entry} pins, read once, checked against the content of every entry that pins it; empty with a
     * line added to {@code problems}.
     */
    private Optional<Found> read(Package.Locked entry, List<String> problems) throws IOException {
        String key = entry.repository() + " " + entry.commit();
        Found known = commits.get(key);
        if (known != null) return drift(entry, known.pin().content(), problems) ? Optional.empty() : Optional.of(known);
        String dependency = "dependency " + entry.name() + "@" + entry.version() + ": ";
        Commit commit;
        try {
            commit = repositories().forDeploy(entry.repository(), entry.commit());
        } catch (GitFailure failed) {
            problems.add(dependency + failed.getMessage());
            return Optional.empty();
        }
        Optional<Path> files = written(commit, dependency, problems);
        if (files.isEmpty()) return Optional.empty();
        if (drift(entry, Package.content(files.get()), problems)) return Optional.empty();
        Package pkg;
        try {
            pkg = Package.load(files.get());
        } catch (IllegalArgumentException unreadable) {   // its message starts with the file's path from the dependency's root
            problems.add(dependency + entry.repository() + " at " + entry.commit() + ": " + Cli.oneLine(unreadable.getMessage()));
            return Optional.empty();
        }
        Found f = new Found(entry, files.get(), pkg.lock());
        commits.put(key, f);
        return Optional.of(f);
    }

    /** Whether {@code entry} pins a content other than {@code content}, its commit's; a line added to {@code problems} when it does. */
    private static boolean drift(Package.Locked entry, String content, List<String> problems) {
        if (content.equals(entry.content())) return false;
        problems.add("package.lock pins " + entry.name() + "@" + entry.version() + " " + entry.content() + "; " + entry.repository() + " at "
                + entry.commit() + " has " + content);
        return true;
    }

    /** The files of {@code commit} written into a folder of the run's own; empty with a line added to {@code problems} when the seam refuses them. */
    private Optional<Path> written(Commit commit, String dependency, List<String> problems) throws IOException {
        Path dir = Files.createDirectory(temp.resolve("commit-" + folders++));
        try {
            commit.write(dir);
            return Optional.of(dir);
        } catch (GitFailure failed) {
            problems.add(dependency + failed.getMessage());
        } catch (IllegalArgumentException refused) {   // a symbolic link, a submodule, a tree over the limits: it names the commit
            problems.add(dependency + Cli.oneLine(refused.getMessage()));
        }
        return Optional.empty();
    }

    /** The seam over the candidates directory, its cache made in the temporary directory at the first call. */
    private PackageRepositories repositories() throws IOException {
        if (repositories == null) {
            temp = Files.createTempDirectory("rail8-git-");
            GitCache cache = new GitCache(new GitSettings(deps.toUri()), temp.resolve("cache"), NodeConfig.MAX_PACKAGE_BYTES_DEFAULT, false, WALL, Set::of);
            repositories = cache.open();
        }
        return repositories;
    }

    /** Deletes the temporary directory, the cache and every commit written in it. */
    @Override
    public void close() throws IOException {
        if (temp != null) FlowTests.deleteTree(temp);
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
                JsonObject manifest = Package.load(d).manifest();
                found.add(new Candidate(d.getFileName().toString(), d, manifest.getValue("name"), manifest.getValue("version"), Package.content(d)));
            } catch (IllegalArgumentException | IOException | UncheckedIOException unreadable) {
                // a file of it does not parse, or it cannot be listed, walked or read: no candidate, and no stop to the others
            }
        }
        return List.copyOf(found);
    }
}
