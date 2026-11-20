package dev.rail8.runtime.deploy;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Package repositories for the deploy tests: one git working folder per repository under a folder, which a {@code file://}
 * {@code RAIL8_GIT_BASE} names. A commit replaces the folder's files with the ones given, so a file left out is deleted, and
 * commits them on {@code main}, each one second after the one before, so no two commits share an id.
 */
public final class GitRepos {

    /**
     * An https base no host serves, for a node in a deployed environment, where a {@code file://} base is refused: only its git
     * cache answers, so it restores what a node on the same data directory deployed, and a fetch fails.
     */
    public static final String UNSERVED = "https://git.rail8.invalid/pool/";

    private final Path dir;
    private long commits;

    /** The repositories of the test JVM, in a temporary directory of its own that is deleted when the JVM exits. */
    private static final class Shared {
        static final GitRepos REPOS = make();

        private static GitRepos make() {
            try {
                Path dir = Files.createTempDirectory("rail8-repos-");
                java.lang.Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        deleteTree(dir);
                    } catch (IOException leftBehind) {
                        // a temporary directory the system cleans up
                    }
                }));
                return new GitRepos(dir);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Repositories under {@code dir}, made when missing. */
    public GitRepos(Path dir) {
        try {
            this.dir = Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The repositories the nodes of an integration test deploy from over the admin API: one set for the test JVM, whose tests run
     * one after another. {@link #with} names them in a node's environment.
     */
    public static GitRepos shared() {
        return Shared.REPOS;
    }

    /** {@code env} with {@code RAIL8_GIT_BASE} set to the {@link #shared} repositories when its {@code RAIL8_ENV} is dev or ci; answers {@code env}. */
    public static Map<String, String> with(Map<String, String> env) {
        String name = env.get("RAIL8_ENV");
        if ("dev".equals(name) || "ci".equals(name)) env.put("RAIL8_GIT_BASE", shared().base().toString());
        return env;
    }

    /** The {@code file://} base the repositories live under, ending in {@code /}. */
    public URI base() {
        return URI.create(dir.toUri().toString().replaceAll("/?$", "/"));
    }

    /** {@code env} with {@code RAIL8_GIT_BASE} set to {@link #base()}: a copy. */
    public Map<String, String> env(Map<String, String> env) {
        Map<String, String> with = new HashMap<>(env);
        with.put("RAIL8_GIT_BASE", base().toString());
        return with;
    }

    /** The working folder of {@code repository}. */
    public Path dir(String repository) {
        return dir.resolve(repository);
    }

    /** Commits {@code p}'s files to the repository of its name; the commit's id, which {@code p} now pins its version at. */
    public String commit(PackageDraft p) {
        return commit(p.name(), p);
    }

    /** Commits {@code p}'s files to {@code repository}; the commit's id, which {@code p} now pins its version at. */
    public String commit(String repository, PackageDraft p) {
        String id = commit(repository, p.files());
        p.committed(repository, id);
        return id;
    }

    /** Commits exactly the regular files under {@code folder}, each by its {@code /}-separated path in it, to {@code repository}; the commit's id. */
    public String commit(String repository, Path folder) {
        Map<String, byte[]> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                files.put(folder.relativize(file).toString().replace(java.io.File.separatorChar, '/'), Files.readAllBytes(file));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return commit(repository, files);
    }

    /** Commits exactly {@code files}, each by its path, to {@code repository}, made when missing; the commit's id. */
    public synchronized String commit(String repository, Map<String, byte[]> files) {
        Path folder = dir(repository);
        try {
            Files.createDirectories(folder);
            try (Stream<Path> children = Files.list(folder)) {
                for (Path child : children.filter(c -> !c.getFileName().toString().equals(".git")).toList()) deleteTree(child);
            }
            for (Map.Entry<String, byte[]> f : files.entrySet()) {
                Path file = folder.resolve(f.getKey());
                Files.createDirectories(file.getParent());
                Files.write(file, f.getValue());
            }
            boolean fresh = Files.notExists(folder.resolve(".git"));
            try (Git git = fresh ? Git.init().setDirectory(folder.toFile()).setInitialBranch("main").call() : Git.open(folder.toFile())) {
                git.add().addFilepattern(".").call();
                git.add().addFilepattern(".").setUpdate(true).call();   // the files deleted
                commits++;
                PersonIdent who = new PersonIdent("Rail8 test", "test@rail8.invalid", Instant.parse("2026-11-19T08:00:00Z").plusSeconds(commits),
                        ZoneOffset.UTC);
                return git.commit().setMessage("commit " + commits + "\n").setAuthor(who).setCommitter(who).setSign(false).setAllowEmpty(true).call()
                        .name();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GitAPIException e) {
            throw new IllegalStateException("fixture: no commit to " + repository, e);
        }
    }

    /** Deletes {@code repository}, its working folder and its history. */
    public synchronized void delete(String repository) {
        try {
            deleteTree(dir(repository));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (Files.notExists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
