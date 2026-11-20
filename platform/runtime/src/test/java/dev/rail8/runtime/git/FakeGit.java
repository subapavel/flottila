package dev.rail8.runtime.git;

import dev.rail8.api.Timer;
import dev.rail8.runtime.git.GitFailure.Reason;
import dev.rail8.runtime.git.PackageRepositoriesContract.Entry;
import dev.rail8.runtime.git.PackageRepositoriesContract.File;
import dev.rail8.runtime.git.PackageRepositoriesContract.Link;
import dev.rail8.runtime.git.PackageRepositoriesContract.Submodule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * A fake of the package repositories for tests: the source's repositories and a node's cache, both in memory, held to the rules
 * {@link GitCache} holds. A fetch copies the commits of the pool's ref, or of every branch and tag, into the cache; the cache's
 * size is the bytes of the files of the commits it holds, and the fetches of one {@link Cache#open()} share one deadline on the
 * timer. Not for concurrent use.
 */
final class FakeGit implements PackageRepositoriesContract.Source {

    private record FakeCommit(String id, Optional<String> parent, Map<String, Entry> tree) {

        long bytes() {
            return tree.values().stream().mapToLong(e -> e instanceof File f ? f.bytes().length : 0).sum();
        }
    }

    private static final class Repository {
        final Map<String, FakeCommit> commits = new HashMap<>();
        final Map<String, String> refs = new TreeMap<>();
    }

    private static final URI BASE = URI.create("file:///fake/");

    private final Map<String, Repository> source = new HashMap<>();
    private final Map<String, Map<String, FakeCommit>> cache = new LinkedHashMap<>();
    private long next;

    @Override
    public String commit(String repository, String branch, Map<String, Entry> tree) {
        Repository at = source.computeIfAbsent(repository, name -> new Repository());
        String id = String.format("%040x", ++next * 0x9e3779b97f4a7c15L & Long.MAX_VALUE);
        at.commits.put(id, new FakeCommit(id, Optional.ofNullable(at.refs.get("refs/heads/" + branch)), Map.copyOf(tree)));
        at.refs.put("refs/heads/" + branch, id);
        return id;
    }

    @Override
    public void branch(String repository, String branch, String commit) {
        source.get(repository).refs.put("refs/heads/" + branch, commit);
    }

    @Override
    public void tag(String repository, String tag, String commit) {
        source.get(repository).refs.put("refs/tags/" + tag, commit);
    }

    @Override
    public void delete(String repository) {
        source.remove(repository);
    }

    @Override
    public void shallowCopy(String from, String to) {
        Repository original = source.get(from);
        Repository copy = new Repository();
        String tip = original.refs.get("refs/heads/main");
        copy.commits.put(tip, original.commits.get(tip));
        copy.refs.put("refs/heads/main", tip);
        source.put(to, copy);
    }

    /** One deploy's or one restore's reads, over the cache this fake keeps. */
    interface Cache {
        PackageRepositories open();
    }

    /** A cache with these settings over this fake's source and cache. */
    Cache cache(String path, Optional<String> ref, boolean deployed, Duration timeout, long maxBytes, long maxPackageBytes, Timer timer,
                Supplier<Set<String>> named) {
        return () -> new Session(path, ref, deployed, timeout, maxBytes, maxPackageBytes, timer, named);
    }

    private long size() {
        return cache.values().stream().flatMap(c -> c.values().stream()).mapToLong(FakeCommit::bytes).sum();
    }

    private final class Session implements PackageRepositories {

        private final String path;
        private final Optional<String> ref;
        private final boolean deployed;
        private final Duration timeout;
        private final long maxBytes;
        private final long maxPackageBytes;
        private final Timer timer;
        private final Supplier<Set<String>> named;
        private final Map<String, Map<String, String>> fetched = new HashMap<>();
        private final Set<String> reading = new HashSet<>();
        private boolean started;
        private boolean expired;
        private Timer.Cancellable deadline;

        Session(String path, Optional<String> ref, boolean deployed, Duration timeout, long maxBytes, long maxPackageBytes, Timer timer,
                Supplier<Set<String>> named) {
            this.path = path;
            this.ref = ref;
            this.deployed = deployed;
            this.timeout = timeout;
            this.maxBytes = maxBytes;
            this.maxPackageBytes = maxPackageBytes;
            this.timer = timer;
            this.named = named;
        }

        @Override
        public URI base() {
            return BASE;
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public Commit forDeploy(String repository, String commitOrRef) throws GitFailure {
            Requests.repository(repository);
            boolean byId = Requests.isCommit(commitOrRef);
            List<String> names = byId ? List.of() : Requests.ref(commitOrRef, deployed);
            String target = byId ? Requests.commit(commitOrRef) : commitOrRef;
            reading.add(repository);
            Map<String, String> refs = fetch(repository, target);
            String wanted = byId ? target : names.stream().map(refs::get).filter(Objects::nonNull).findFirst()
                    .orElseThrow(() -> new GitFailure(Reason.NOT_FOUND, repository, target));
            Map<String, FakeCommit> cached = cache.getOrDefault(repository, Map.of());
            if (!cached.containsKey(wanted)) throw new GitFailure(ref.isPresent() ? Reason.NOT_REACHABLE : Reason.NOT_FOUND, repository, target);
            complete(cached, wanted, repository, target);
            if (ref.isPresent() && !history(cached, refs.get(ref.get())).contains(wanted)) {
                throw new GitFailure(Reason.NOT_REACHABLE, repository, target);
            }
            return new CachedCommit(repository, wanted);
        }

        @Override
        public Commit recorded(String repository, String commit) throws GitFailure {
            Requests.repository(repository);
            String id = Requests.commit(commit);
            reading.add(repository);
            if (!cache.getOrDefault(repository, Map.of()).containsKey(id)) {
                fetch(repository, id);
                if (!cache.getOrDefault(repository, Map.of()).containsKey(id)) throw new GitFailure(Reason.NOT_FOUND, repository, id);
            }
            complete(cache.get(repository), id, repository, id);
            return new CachedCommit(repository, id);
        }

        @Override
        public void close() {
            if (deadline != null) deadline.cancel();
        }

        private static void complete(Map<String, FakeCommit> cached, String id, String repository, String target) throws GitFailure {
            for (String at = id; at != null; ) {
                FakeCommit commit = cached.get(at);
                if (commit == null) throw new GitFailure(Reason.HISTORY_INCOMPLETE, repository, target);
                at = commit.parent().orElse(null);
            }
        }

        /** The ids of {@code tip} and the commits before it that {@code commits} holds. */
        private static Set<String> history(Map<String, FakeCommit> commits, String tip) {
            Set<String> ids = new HashSet<>();
            Deque<String> todo = new ArrayDeque<>();
            if (tip != null) todo.add(tip);
            while (!todo.isEmpty()) {
                FakeCommit commit = commits.get(todo.pop());
                if (commit != null && ids.add(commit.id())) commit.parent().ifPresent(todo::push);
            }
            return ids;
        }

        private Map<String, String> fetch(String repository, String target) throws GitFailure {
            Map<String, String> done = fetched.get(repository);
            if (done != null) return done;
            if (!started) {
                started = true;
                deadline = timer.schedule(timeout, () -> expired = true);
            }
            if (expired) throw new GitFailure(Reason.TIMED_OUT, repository, target);
            Repository at = source.get(repository);
            if (at == null) throw new GitFailure(Reason.NOT_FOUND, repository, target);
            Map<String, String> refs = new HashMap<>(at.refs);
            refs.put("HEAD", at.refs.get("refs/heads/main"));
            List<String> wants = new ArrayList<>();
            if (ref.isPresent()) {
                String tip = refs.get(ref.get());
                if (tip == null) throw new GitFailure(Reason.NOT_FOUND, repository, ref.get());
                wants.add(tip);
            } else {
                at.refs.forEach((name, id) -> {
                    if (name.startsWith("refs/heads/") || name.startsWith("refs/tags/")) wants.add(id);
                });
            }
            Map<String, FakeCommit> cached = cache.computeIfAbsent(repository, name -> new HashMap<>());
            Map<String, FakeCommit> incoming = new HashMap<>();
            for (String tip : wants) {
                for (String id : history(at.commits, tip)) {
                    if (!cached.containsKey(id)) incoming.put(id, at.commits.get(id));
                }
            }
            long bytes = incoming.values().stream().mapToLong(FakeCommit::bytes).sum();
            if (!incoming.isEmpty() && size() + bytes > maxBytes) {
                Set<String> keep = new HashSet<>(named.get());
                keep.addAll(reading);
                cache.keySet().removeIf(name -> !keep.contains(name));
                if (size() + bytes > maxBytes) throw new GitFailure(Reason.CACHE_FULL, repository, target);
            }
            cached.putAll(incoming);
            Map<String, String> answered = Map.copyOf(refs);
            fetched.put(repository, answered);
            return answered;
        }

        private final class CachedCommit implements Commit {

            private final String repository;
            private final String id;

            CachedCommit(String repository, String id) {
                this.repository = repository;
                this.id = id;
            }

            @Override
            public String repository() {
                return repository;
            }

            @Override
            public String id() {
                return id;
            }

            @Override
            public void write(Path dir) throws GitFailure {
                FakeCommit commit = cache.getOrDefault(repository, Map.of()).get(id);
                if (commit == null) throw new GitFailure(Reason.HISTORY_INCOMPLETE, repository, id);
                String prefix = path.isEmpty() ? "" : path + "/";
                if (!path.isEmpty() && commit.tree().keySet().stream().noneMatch(name -> name.startsWith(prefix))) {
                    throw PackageTree.noFolder(repository, id, path);
                }
                PackageTree rules = new PackageTree(repository, id, maxPackageBytes);
                Map<String, byte[]> files = new LinkedHashMap<>();
                for (Map.Entry<String, Entry> entry : new TreeMap<>(commit.tree()).entrySet()) {
                    if (!entry.getKey().startsWith(prefix)) continue;
                    String name = entry.getKey().substring(prefix.length());
                    switch (entry.getValue()) {
                        case File file -> {
                            rules.add(name, PackageTree.Kind.FILE, file.bytes().length);
                            files.put(name, file.bytes());
                        }
                        case Link link -> rules.add(name, PackageTree.Kind.LINK, 0);
                        case Submodule submodule -> rules.add(name, PackageTree.Kind.SUBMODULE, 0);
                    }
                }
                rules.finish();
                try {
                    try (var entries = Files.list(dir)) {
                        if (entries.findAny().isPresent()) throw new IllegalArgumentException("the package is written into an empty directory only");
                    }
                    for (Map.Entry<String, byte[]> file : files.entrySet()) {
                        Path to = dir.resolve(file.getKey());
                        Files.createDirectories(to.getParent());
                        Files.write(to, file.getValue(), StandardOpenOption.CREATE_NEW);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
    }
}
