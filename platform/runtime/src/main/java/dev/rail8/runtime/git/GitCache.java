package dev.rail8.runtime.git;

import dev.rail8.api.Timer;
import dev.rail8.runtime.git.GitFailure.Reason;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.NoRemoteRepositoryException;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.FetchConnection;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.TransportHttp;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.FileUtils;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * The node's git cache, and the {@link PackageRepositories} read through it, on JGit with no git binary. The cache holds a bare
 * repository per package repository, {@code <name>.git} in its folder, which only this cache writes; the node uses
 * {@code <dataDir>/git}, the command line a temporary folder.
 *
 * <ul>
 *   <li>A fetch asks for the pool's ref ({@code RAIL8_GIT_REF}), or for every branch and tag when none is set, with their full
 *       history, then looks the commit up in the cache: a server need not serve a commit by its id. Each repository is fetched at
 *       most once for one deploy, so the pool ref's tip a commit is proved against is the one that fetch saw.</li>
 *   <li>A commit the cache answers is kept by a reference of its own, {@code refs/rail8/kept/<id>}, so a force-push on the server
 *       or a cleanup of the cache never drops it. Its whole history must be in the cache: a source with a shallow history is
 *       refused as incomplete.</li>
 *   <li>The cache's size is checked before a fetch and while it receives. When it would pass {@code RAIL8_GIT_MAX_BYTES}, the
 *       cached repositories that {@code named} does not name are deleted, except those the same deploy has read; a fetch that
 *       would still pass the limit stops.</li>
 *   <li>The token is held in memory and given only to the base's scheme and host; nothing is written into a repository's
 *       configuration. Over https a redirect stays on the base's scheme and host, and the timer stops a fetch that waits on a
 *       silent server.</li>
 * </ul>
 *
 * One fetch, write or deletion runs at a time. Blocking: called on a virtual thread.
 *
 * @since 0.1
 */
public final class GitCache {

    private static final String KEPT = "refs/rail8/kept/";

    private final GitSettings settings;
    private final Path dir;
    private final long maxPackageBytes;
    private final boolean deployed;
    private final Timer timer;
    private final Supplier<Set<String>> named;
    private final SSLSocketFactory tls;
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * @param settings the {@code RAIL8_GIT_*} settings
     * @param dir the cache's folder, made when it is missing
     * @param maxPackageBytes {@code RAIL8_MAX_PACKAGE_BYTES}: the most one package's files may hold in all
     * @param deployed whether the node runs in a deployed environment, where a deploy names a commit by its id only
     * @param timer the node's timer, on which the fetches' {@code RAIL8_GIT_TIMEOUT} runs
     * @param named the repositories that a record or a pin names; asked when the cache is full
     */
    public GitCache(GitSettings settings, Path dir, long maxPackageBytes, boolean deployed, Timer timer, Supplier<Set<String>> named) {
        this(settings, dir, maxPackageBytes, deployed, timer, named, HttpsURLConnection.getDefaultSSLSocketFactory());
    }

    /** As the public constructor, with the TLS sockets an https fetch uses: the tests' own trust. */
    GitCache(GitSettings settings, Path dir, long maxPackageBytes, boolean deployed, Timer timer, Supplier<Set<String>> named,
             SSLSocketFactory tls) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.dir = Objects.requireNonNull(dir, "dir");
        if (maxPackageBytes <= 0) throw new IllegalArgumentException("maxPackageBytes must be positive, was " + maxPackageBytes);
        this.maxPackageBytes = maxPackageBytes;
        this.deployed = deployed;
        this.timer = Objects.requireNonNull(timer, "timer");
        this.named = Objects.requireNonNull(named, "named");
        this.tls = Objects.requireNonNull(tls, "tls");
    }

    /** The repositories for one deploy or one package's restore: its fetches take at most {@code RAIL8_GIT_TIMEOUT} together. */
    public PackageRepositories open() {
        return new Session(null);
    }

    /**
     * The repositories as the cache holds them now, for a restore that tries no further fetch: a commit the cache holds is
     * answered as {@link #open()} answers it, and one it lacks fails at once as {@code reason}, with nothing fetched.
     */
    public PackageRepositories cached(Reason reason) {
        return new Session(Objects.requireNonNull(reason, "reason"));
    }

    private Path repositoryDir(String name) {
        return dir.resolve(name + ".git");
    }

    /** The cached repository {@code name}, made empty when it is missing. */
    private Repository openRepository(String name) throws IOException {
        Repository repository = new FileRepositoryBuilder().setGitDir(repositoryDir(name).toFile()).setBare().build();
        if (!repository.getObjectDatabase().exists()) repository.create(true);
        return repository;
    }

    /** The bytes of every file in the cache. */
    private long size() {
        if (!Files.isDirectory(dir)) return 0;
        long[] bytes = {0};
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile()) bytes[0] += attributes.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
                    if (e instanceof NoSuchFileException) return FileVisitResult.CONTINUE;
                    throw e;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes[0];
    }

    /** Deletes every cached repository that neither {@code named} nor {@code reading} names. */
    private void evict(Set<String> reading) {
        Set<String> keep = new HashSet<>(named.get());
        keep.addAll(reading);
        try (DirectoryStream<Path> repositories = Files.newDirectoryStream(dir, "*.git")) {
            for (Path repository : repositories) {
                String name = repository.getFileName().toString();
                if (!keep.contains(name.substring(0, name.length() - ".git".length()))) {
                    FileUtils.delete(repository.toFile(), FileUtils.RECURSIVE | FileUtils.SKIP_MISSING);
                }
            }
        } catch (NoSuchFileException e) {
            // an empty cache has nothing to delete
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void update(Repository repository, String name, AnyObjectId id) throws IOException {
        Ref current = repository.exactRef(name);
        if (current != null && id.equals(current.getObjectId())) return;
        RefUpdate update = repository.updateRef(name);
        update.setNewObjectId(id);
        RefUpdate.Result result = update.forceUpdate();
        if (result != RefUpdate.Result.NEW && result != RefUpdate.Result.FORCED && result != RefUpdate.Result.NO_CHANGE) {
            throw new IOException("the cache could not move its reference " + name + ": " + result);
        }
    }

    /** One deploy's or one restore's reads: one deadline, each repository fetched at most once. */
    private final class Session implements PackageRepositories {

        /** The refs each fetched repository advertised at its fetch. */
        private final Map<String, Map<String, Ref>> fetched = new HashMap<>();
        private final Set<String> reading = new HashSet<>();
        /** Why this session fetches nothing; null when it fetches. */
        private final Reason unfetched;
        private volatile boolean expired;
        private volatile Fetch current;
        private boolean started;
        private Timer.Cancellable deadline;   // once the first fetch started it

        Session(Reason unfetched) {
            this.unfetched = unfetched;
        }

        @Override
        public URI base() {
            return settings.base();
        }

        @Override
        public String path() {
            return settings.path();
        }

        @Override
        public Commit forDeploy(String repository, String commitOrRef) throws GitFailure {
            Requests.repository(repository);
            boolean byId = Requests.isCommit(commitOrRef);
            List<String> names = byId ? List.of() : Requests.ref(commitOrRef, deployed);
            String target = byId ? Requests.commit(commitOrRef) : commitOrRef;
            lock.lock();
            try {
                reading.add(repository);
                Map<String, Ref> refs = fetch(repository, target);
                Reason absent = settings.ref().isPresent() ? Reason.NOT_REACHABLE : Reason.NOT_FOUND;
                AnyObjectId wanted;
                if (byId) {
                    wanted = ObjectId.fromString(target);
                } else {
                    Ref ref = names.stream().map(refs::get).filter(Objects::nonNull).findFirst()
                            .orElseThrow(() -> new GitFailure(Reason.NOT_FOUND, repository, target));
                    wanted = ref.getPeeledObjectId() != null ? ref.getPeeledObjectId() : ref.getObjectId();
                }
                try (Repository cached = openRepository(repository); RevWalk walk = new RevWalk(cached)) {
                    RevCommit commit = commit(walk, wanted);
                    if (commit == null) throw new GitFailure(absent, repository, target);
                    complete(cached, commit, repository, target);
                    if (settings.ref().isPresent()) {
                        RevCommit tip = commit(walk, refs.get(settings.ref().get()).getObjectId());
                        if (tip == null || !walk.isMergedInto(commit, tip)) throw new GitFailure(Reason.NOT_REACHABLE, repository, target);
                    }
                    update(cached, KEPT + commit.name(), commit);
                    return new CachedCommit(repository, commit.name());
                } catch (MissingObjectException e) {
                    throw new GitFailure(Reason.HISTORY_INCOMPLETE, repository, target);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } finally {
                lock.unlock();
            }
        }

        @Override
        public Commit recorded(String repository, String commit) throws GitFailure {
            Requests.repository(repository);
            String id = Requests.commit(commit);
            lock.lock();
            try {
                reading.add(repository);
                if (!cached(repository, id)) {
                    fetch(repository, id);
                    if (!cached(repository, id)) throw new GitFailure(Reason.NOT_FOUND, repository, id);
                }
                try (Repository cached = openRepository(repository); RevWalk walk = new RevWalk(cached)) {
                    RevCommit found = walk.parseCommit(ObjectId.fromString(id));
                    complete(cached, found, repository, id);
                    update(cached, KEPT + id, found);
                    return new CachedCommit(repository, id);
                } catch (MissingObjectException e) {
                    throw new GitFailure(Reason.HISTORY_INCOMPLETE, repository, id);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } finally {
                lock.unlock();
            }
        }

        /** Whether the cache holds the commit {@code id} of {@code repository}. */
        private boolean cached(String repository, String id) {
            if (!Files.isDirectory(repositoryDir(repository))) return false;
            try (Repository cached = openRepository(repository); RevWalk walk = new RevWalk(cached)) {
                return commit(walk, ObjectId.fromString(id)) != null;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** The commit {@code id} names, peeling a tag; {@code null} when the cache lacks it or it names no commit. */
        private static RevCommit commit(RevWalk walk, AnyObjectId id) throws IOException {
            try {
                RevObject object = walk.peel(walk.parseAny(id));
                return object instanceof RevCommit commit ? commit : null;
            } catch (MissingObjectException | IncorrectObjectTypeException e) {
                return null;
            }
        }

        /** Walks the commit's whole history: a missing commit fails as incomplete. */
        private static void complete(Repository cached, RevCommit commit, String repository, String target) throws IOException, GitFailure {
            try (RevWalk walk = new RevWalk(cached)) {
                walk.setRetainBody(false);
                walk.markStart(walk.parseCommit(commit));
                while (walk.next() != null) {
                    // every parent is parsed on the way
                }
            } catch (MissingObjectException e) {
                throw new GitFailure(Reason.HISTORY_INCOMPLETE, repository, target);
            }
        }

        /**
         * Fetches {@code repository} once for this session: the pool's ref, or every branch and tag, into the cache under the same
         * names. Answers the refs the repository advertised.
         */
        private Map<String, Ref> fetch(String repository, String target) throws GitFailure {
            Map<String, Ref> done = fetched.get(repository);
            if (done != null) return done;
            if (unfetched != null) throw new GitFailure(unfetched, repository, target);
            if (!started) {
                started = true;
                deadline = timer.schedule(settings.timeout(), this::expire);
            }
            if (expired) throw new GitFailure(Reason.TIMED_OUT, repository, target);
            Repository cached;
            URIish uri;
            try {
                cached = openRepository(repository);
                uri = new URIish(settings.repository(repository).toString());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (URISyntaxException e) {
                throw new IllegalStateException("the repository's URI is not one git reads", e);
            }
            try (cached) {
                Fetch fetch = new Fetch(repository);
                current = fetch;
                Map<String, Ref> refs;
                List<Ref> wants = new ArrayList<>();
                try (Transport transport = Transport.open(cached, uri)) {
                    fetch.configure(transport);
                    try (FetchConnection connection = transport.openFetch()) {
                        refs = Map.copyOf(connection.getRefsMap());
                        if (settings.ref().isPresent()) {
                            String pool = settings.ref().get();
                            Ref ref = refs.get(pool);
                            if (ref == null) throw new GitFailure(Reason.NOT_FOUND, repository, pool);
                            wants.add(ref);
                        } else {
                            for (Ref ref : refs.values()) {
                                if (ref.getName().startsWith(Constants.R_HEADS) || ref.getName().startsWith(Constants.R_TAGS)) wants.add(ref);
                            }
                        }
                        List<Ref> missing = new ArrayList<>();
                        for (Ref ref : wants) {
                            if (!cached.getObjectDatabase().has(ref.getObjectId())) missing.add(ref);
                        }
                        if (!missing.isEmpty()) {
                            fetch.before();
                            connection.fetch(fetch, missing, Set.of());
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    fetch.rethrowLocal();
                    throw new GitFailure(fetch.reason(e), repository, target);
                } finally {
                    current = null;
                }
                fetch.rethrowLocal();
                for (Ref ref : wants) update(cached, ref.getName(), ref.getObjectId());
                fetched.put(repository, refs);
                return refs;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void close() {
            if (deadline != null) deadline.cancel();
        }

        @Override
        public void abort() {
            expire();
        }

        /** The deadline passed, or the session was aborted: no further fetch starts, and the one running stops. */
        private void expire() {
            expired = true;
            Fetch fetch = current;
            if (fetch != null) fetch.abort();
        }

        /** One fetch of one repository: its limit on the cache's size, its https connections and why it failed. */
        private final class Fetch implements ProgressMonitor {

            private final Path objects;
            private volatile GuardedHttps https;
            private long used;
            private boolean evicted;
            private boolean full;
            private RuntimeException local;
            private Path incoming;
            private long received;

            Fetch(String repository) {
                this.objects = repositoryDir(repository).resolve("objects");
            }

            void configure(Transport transport) {
                transport.setCredentialsProvider(new Credentials());
                if (transport instanceof TransportHttp http) {
                    GuardedHttps connections = new GuardedHttps(settings.base(), tls, bytes -> {
                        received = bytes;
                        if (over()) throw new IOException("the git cache is full");
                    });
                    https = connections;
                    http.setHttpConnectionFactory(connections);
                    // The deadline may have passed after this fetch's check of it and before its connections were set: the timer
                    // then found nothing to close. Read after both were set, so either this thread or the timer's closes them.
                    if (expired) connections.abort();
                }
            }

            /** Before objects are received: the cache's bytes, freed of unnamed repositories when it is full. */
            void before() throws IOException {
                try {
                    used = size();
                    if (used < settings.maxBytes()) return;
                    evicted = true;
                    evict(reading);
                    used = size();
                    if (used < settings.maxBytes()) return;
                } catch (RuntimeException e) {
                    local = e;
                }
                full = true;
                throw new IOException("the git cache is full");
            }

            /** A failure of this node's own disk, or of {@code named}, while the fetch ran: the caller's to see as it is. */
            void rethrowLocal() {
                if (local != null) throw local;
            }

            /** Whether what the fetch has received would pass the limit; frees unnamed repositories once first. */
            private boolean over() {
                if (full) return true;
                try {
                    long receiving = Math.max(received, incoming());
                    if (used + receiving <= settings.maxBytes()) return false;
                    if (!evicted) {
                        evicted = true;
                        evict(reading);
                        used = size() - incoming();
                        if (used + receiving <= settings.maxBytes()) return false;
                    }
                } catch (RuntimeException e) {
                    local = e;
                }
                full = true;
                return true;
            }

            /** The bytes of the pack being received, in a file of its own in the repository's objects. */
            private long incoming() {
                try {
                    if (incoming == null) {
                        try (DirectoryStream<Path> files = Files.newDirectoryStream(objects, "incoming_*.pack")) {
                            for (Path file : files) incoming = file;
                        }
                    }
                    return incoming == null ? 0 : Files.size(incoming);
                } catch (NoSuchFileException e) {
                    return 0;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            void abort() {
                GuardedHttps connections = https;
                if (connections != null) connections.abort();
            }

            /** The one reason of the formatter's list this failure is, from what the fetch saw, never from the exception's text. */
            Reason reason(Exception e) {
                if (expired) return Reason.TIMED_OUT;
                if (full) return Reason.CACHE_FULL;
                if (https != null && https.refused()) return Reason.TRANSPORT_FAILED;
                int status = https == null ? 0 : https.status();
                if (status == 401 || status == 403) return Reason.CREDENTIALS_REFUSED;
                if (status == 404 || e instanceof NoRemoteRepositoryException) return Reason.NOT_FOUND;
                return Reason.TRANSPORT_FAILED;
            }

            @Override
            public boolean isCancelled() {
                return expired || over();
            }

            @Override
            public void start(int totalTasks) {}

            @Override
            public void beginTask(String title, int totalWork) {}

            @Override
            public void update(int completed) {}

            @Override
            public void endTask() {}

            @Override
            public void showDuration(boolean enabled) {}
        }
    }

    /** The user and the token, for the base's scheme and host only. */
    private final class Credentials extends CredentialsProvider {

        @Override
        public boolean isInteractive() {
            return false;
        }

        @Override
        public boolean supports(CredentialItem... items) {
            for (CredentialItem item : items) {
                if (!(item instanceof CredentialItem.Username || item instanceof CredentialItem.Password)) return false;
            }
            return true;
        }

        @Override
        public boolean get(URIish uri, CredentialItem... items) {
            if (settings.credentials().isEmpty() || !GuardedHttps.sameOrigin(settings.base(), uri.getScheme(), uri.getHost(), uri.getPort())) {
                return false;
            }
            GitSettings.Credentials credentials = settings.credentials().get();
            for (CredentialItem item : items) {
                if (item instanceof CredentialItem.Username user) user.setValue(credentials.user());
                else if (item instanceof CredentialItem.Password password) password.setValue(credentials.token().toCharArray());
                else return false;
            }
            return true;
        }
    }

    /** One commit of one cached repository. */
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
        public void write(Path target) throws GitFailure {
            lock.lock();
            try {
                if (!Files.isDirectory(repositoryDir(repository))) throw new GitFailure(Reason.HISTORY_INCOMPLETE, repository, id);
                try (Repository cached = openRepository(repository); ObjectReader reader = cached.newObjectReader(); RevWalk walk = new RevWalk(reader)) {
                    AnyObjectId folder = walk.parseCommit(ObjectId.fromString(id)).getTree();
                    if (!settings.path().isEmpty()) {
                        try (TreeWalk at = TreeWalk.forPath(reader, settings.path(), folder)) {
                            if (at == null || !at.isSubtree()) throw PackageTree.noFolder(repository, id, settings.path());
                            folder = at.getObjectId(0);
                        }
                    }
                    PackageTree rules = new PackageTree(repository, id, maxPackageBytes);
                    Map<String, ObjectId> files = new LinkedHashMap<>();
                    try (TreeWalk walkTree = new TreeWalk(reader)) {
                        walkTree.addTree(folder);
                        walkTree.setRecursive(true);
                        while (walkTree.next()) {
                            String path = rules.name(walkTree.getRawPath());
                            int type = walkTree.getRawMode(0) & FileMode.TYPE_MASK;
                            PackageTree.Kind kind = switch (type) {
                                case FileMode.TYPE_FILE -> PackageTree.Kind.FILE;
                                case FileMode.TYPE_SYMLINK -> PackageTree.Kind.LINK;
                                case FileMode.TYPE_GITLINK -> PackageTree.Kind.SUBMODULE;
                                default -> PackageTree.Kind.OTHER;
                            };
                            ObjectId blob = walkTree.getObjectId(0);
                            rules.add(path, kind, kind == PackageTree.Kind.FILE ? reader.getObjectSize(blob, Constants.OBJ_BLOB) : 0);
                            files.put(path, blob);
                        }
                    }
                    rules.finish();
                    try (DirectoryStream<Path> entries = Files.newDirectoryStream(target)) {
                        if (entries.iterator().hasNext()) throw new IllegalArgumentException("the package is written into an empty directory only");
                    }
                    for (Map.Entry<String, ObjectId> file : files.entrySet()) {
                        Path to = target.resolve(file.getKey());
                        Files.createDirectories(to.getParent());
                        try (OutputStream out = Files.newOutputStream(to, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                            reader.open(file.getValue(), Constants.OBJ_BLOB).copyTo(out);
                        }
                    }
                }
            } catch (MissingObjectException e) {
                throw new GitFailure(Reason.HISTORY_INCOMPLETE, repository, id);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                lock.unlock();
            }
        }
    }
}
