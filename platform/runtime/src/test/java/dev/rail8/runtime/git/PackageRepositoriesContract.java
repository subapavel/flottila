package dev.rail8.runtime.git;

import dev.rail8.runtime.git.GitFailure.Reason;
import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeTimer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What every {@link PackageRepositories} does, run on the fake and on JGit over {@code file://} repositories the test builds: the
 * package folder found at the path and written as committed, the tree rules and the limits, reachability from the pool's ref, refs
 * in dev and ci only, a shallow source refused, the fetches of one deploy timed out together on a fake clock, a full cache freed of
 * the repositories nothing names, and a recorded commit read from the cache with its source gone.
 */
abstract class PackageRepositoriesContract {

    /** A tree entry the test commits. */
    sealed interface Entry permits File, Link, Submodule {}

    /** A regular file. */
    record File(byte[] bytes) implements Entry {}

    /** A symbolic link to {@code target}. */
    record Link(String target) implements Entry {}

    /** A submodule at {@code commit}. */
    record Submodule(String commit) implements Entry {}

    /** The repositories the test builds under the base, each with its branch {@code main} as its {@code HEAD}. */
    interface Source {

        /** Commits {@code tree}, the whole tree of the new commit, on {@code branch} after its tip; answers the commit's id. */
        String commit(String repository, String branch, Map<String, Entry> tree);

        /** Makes or moves {@code branch} to {@code commit}. */
        void branch(String repository, String branch, String commit);

        /** Tags {@code commit} with an annotated tag. */
        void tag(String repository, String tag, String commit);

        /** Deletes the repository from the source. */
        void delete(String repository);

        /** Makes {@code to} a copy of {@code from} that holds the tip of {@code main} without its history. */
        void shallowCopy(String from, String to);
    }

    /** A node's cache over the source; each {@link #open()} serves one deploy. */
    interface Cache {
        PackageRepositories open();
    }

    /**
     * The settings a cache is made with.
     *
     * @param path {@code RAIL8_GIT_PATH}
     * @param ref {@code RAIL8_GIT_REF}
     * @param deployed whether the environment is a deployed one
     * @param maxBytes {@code RAIL8_GIT_MAX_BYTES}
     * @param maxPackageBytes {@code RAIL8_MAX_PACKAGE_BYTES}
     * @param named the repositories records and pins name
     */
    record Options(String path, Optional<String> ref, boolean deployed, long maxBytes, long maxPackageBytes, Supplier<Set<String>> named) {

        static Options defaults() {
            return new Options("", Optional.empty(), false, GitSettings.MAX_BYTES_DEFAULT, 1L << 20, Set::of);
        }

        Options path(String value) {
            return new Options(value, ref, deployed, maxBytes, maxPackageBytes, named);
        }

        Options ref(String value) {
            return new Options(path, Optional.of(value), deployed, maxBytes, maxPackageBytes, named);
        }

        Options inADeployedEnvironment() {
            return new Options(path, ref, true, maxBytes, maxPackageBytes, named);
        }

        Options maxBytes(long value) {
            return new Options(path, ref, deployed, value, maxPackageBytes, named);
        }

        Options maxPackageBytes(long value) {
            return new Options(path, ref, deployed, maxBytes, value, named);
        }

        Options named(Supplier<Set<String>> value) {
            return new Options(path, ref, deployed, maxBytes, maxPackageBytes, value);
        }
    }

    protected final FakeClock clock = FakeClock.at(Instant.parse("2026-11-19T08:00:00Z"));
    protected final FakeTimer timer = new FakeTimer(clock);

    @TempDir
    protected Path temp;

    protected Source source;

    /** A new, empty source of repositories for one test. */
    protected abstract Source newSource();

    /** A cache over the source with these options; the caches of one test share what is cached, as one node's restarts do. */
    protected abstract Cache cache(Options options);

    @BeforeEach
    final void makeSource() {
        source = newSource();
    }

    static File file(String text) {
        return new File(text.getBytes(StandardCharsets.UTF_8));
    }

    static Map<String, Entry> pkg(String name) {
        return Map.of("package.yaml", file("name: " + name + "\n"));
    }

    private Path empty(String name) throws IOException {
        return Files.createDirectories(temp.resolve("out").resolve(name));
    }

    private static Map<String, byte[]> files(Path dir) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                files.put(dir.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
            }
        }
        return files;
    }

    private static boolean isEmpty(Path dir) throws IOException {
        try (Stream<Path> list = Files.list(dir)) {
            return list.findAny().isEmpty();
        }
    }

    private static GitFailure failure(Reason reason, GitFailureCall call) {
        GitFailure failure = assertThrows(GitFailure.class, call::run);
        assertEquals(reason, failure.reason(), failure.getMessage());
        return failure;
    }

    /** A call that may fail with a {@link GitFailure}. */
    interface GitFailureCall {
        void run() throws GitFailure;
    }

    @Test
    void writesThePackageFolderAtThePathWithTheBytesGitStores() throws Exception {
        byte[] crlf = "name: orders\r\nversion: 1\r\n".getBytes(StandardCharsets.UTF_8);
        String id = source.commit("orders", "main", Map.of(
                "pkg/package.yaml", new File(crlf),
                "pkg/flows/in.yaml", file("flow: in\n"),
                "README.md", file("not the package\n")));
        PackageRepositories repositories = cache(Options.defaults().path("pkg")).open();
        assertEquals("pkg", repositories.path());
        assertTrue(repositories.base().toString().endsWith("/"), repositories.base().toString());

        Commit commit = repositories.forDeploy("orders", id);
        assertEquals("orders", commit.repository());
        assertEquals(id, commit.id());
        Path dir = empty("orders");
        commit.write(dir);

        Map<String, byte[]> files = files(dir);
        assertEquals(Set.of("package.yaml", "flows/in.yaml"), files.keySet());
        assertArrayEquals(crlf, files.get("package.yaml"));
    }

    @Test
    void writesTheRepositorysRootWhenThePathIsEmpty() throws Exception {
        String id = source.commit("orders", "main", Map.of("package.yaml", file("name: orders\n"), "flows/in.yaml", file("flow: in\n")));
        Path dir = empty("orders");
        cache(Options.defaults()).open().forDeploy("orders", id).write(dir);
        assertEquals(Set.of("package.yaml", "flows/in.yaml"), files(dir).keySet());
    }

    @Test
    void takesACommitsIdInEitherCase() throws Exception {
        String id = source.commit("orders", "main", pkg("orders"));
        assertEquals(id, cache(Options.defaults()).open().forDeploy("orders", id.toUpperCase(java.util.Locale.ROOT)).id());
    }

    @Test
    void refusesAFolderWithoutPackageYaml() throws Exception {
        String id = source.commit("orders", "main", Map.of("flows/in.yaml", file("flow: in\n")));
        Commit commit = cache(Options.defaults()).open().forDeploy("orders", id);
        Path dir = empty("orders");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> commit.write(dir));
        assertTrue(refused.getMessage().contains("package.yaml"), refused.getMessage());
        assertTrue(isEmpty(dir));
    }

    @Test
    void refusesACommitWithNoFolderAtThePath() throws Exception {
        String none = source.commit("orders", "main", pkg("orders"));
        String aFile = source.commit("orders", "main", Map.of("pkg", file("a file, not a folder\n")));
        PackageRepositories repositories = cache(Options.defaults().path("pkg")).open();
        for (String id : List.of(none, aFile)) {
            Commit commit = repositories.forDeploy("orders", id);
            Path dir = empty(id);
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> commit.write(dir));
            assertTrue(refused.getMessage().contains("no package folder at pkg"), refused.getMessage());
        }
    }

    @Test
    void refusesSymbolicLinksSubmodulesAndUnsafeNamesBeforeWritingAnything() throws Exception {
        Map<String, Entry> link = new HashMap<>(pkg("orders"));
        link.put("flows/in.yaml", new Link("../../etc/passwd"));
        Map<String, Entry> submodule = new HashMap<>(pkg("orders"));
        submodule.put("lib", new Submodule("0123456789abcdef0123456789abcdef01234567"));
        Map<String, Entry> unsafe = new HashMap<>(pkg("orders"));
        unsafe.put(".git/config", file("[core]\n"));
        Map<String, Map<String, Entry>> trees = Map.of("symbolic link", link, "submodule", submodule, "not a safe file name", unsafe);
        Map<String, String> ids = new HashMap<>();
        trees.forEach((why, tree) -> ids.put(why, source.commit("orders", "main", tree)));
        PackageRepositories repositories = cache(Options.defaults()).open();
        for (Map.Entry<String, Map<String, Entry>> tree : trees.entrySet()) {
            String id = ids.get(tree.getKey());
            Commit commit = repositories.forDeploy("orders", id);
            Path dir = empty(id);
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> commit.write(dir), tree.getKey());
            assertTrue(refused.getMessage().contains(tree.getKey()), refused.getMessage());
            assertTrue(isEmpty(dir), tree.getKey());
        }
    }

    @Test
    void refusesMoreThanTenThousandFiles() throws Exception {
        Map<String, Entry> tree = new HashMap<>(pkg("orders"));
        File same = file("x\n");
        for (int i = 0; i < PackageTree.MAX_FILES; i++) tree.put("data/" + i + ".txt", same);
        String id = source.commit("orders", "main", tree);
        Commit commit = cache(Options.defaults()).open().forDeploy("orders", id);
        Path dir = empty("orders");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> commit.write(dir));
        assertTrue(refused.getMessage().contains("more than 10000 files"), refused.getMessage());
        assertTrue(isEmpty(dir));
    }

    @Test
    void refusesMoreThanThePackageLimitInAll() throws Exception {
        byte[] yaml = "name: orders\n".getBytes(StandardCharsets.UTF_8);
        long limit = 100;
        String fits = source.commit("orders", "main", Map.of("package.yaml", new File(yaml), "data.bin", new File(new byte[(int) limit - yaml.length])));
        String over = source.commit("orders", "main", Map.of("package.yaml", new File(yaml), "data.bin", new File(new byte[(int) limit - yaml.length + 1])));
        PackageRepositories repositories = cache(Options.defaults().maxPackageBytes(limit)).open();

        repositories.forDeploy("orders", fits).write(empty("fits"));
        Commit commit = repositories.forDeploy("orders", over);
        Path dir = empty("over");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> commit.write(dir));
        assertTrue(refused.getMessage().contains("RAIL8_MAX_PACKAGE_BYTES"), refused.getMessage());
        assertTrue(isEmpty(dir));
    }

    @Test
    void writesIntoAnEmptyDirectoryOnly() throws Exception {
        String id = source.commit("orders", "main", pkg("orders"));
        Path dir = empty("orders");
        Files.writeString(dir.resolve("left.txt"), "from before\n");
        Commit commit = cache(Options.defaults()).open().forDeploy("orders", id);
        assertThrows(IllegalArgumentException.class, () -> commit.write(dir));
    }

    @Test
    void provesACommitReachableFromThePoolRef() throws Exception {
        String first = source.commit("orders", "main", pkg("orders"));
        String second = source.commit("orders", "main", Map.of("package.yaml", file("name: orders\nversion: 2\n")));
        PackageRepositories repositories = cache(Options.defaults().ref("refs/heads/main")).open();
        assertEquals(first, repositories.forDeploy("orders", first).id());
        assertEquals(second, repositories.forDeploy("orders", second).id());
    }

    @Test
    void refusesACommitOnAnotherBranchWhenAPoolRefIsSet() throws Exception {
        String main = source.commit("orders", "main", pkg("orders"));
        source.branch("orders", "feature", main);
        String feature = source.commit("orders", "feature", Map.of("package.yaml", file("name: orders\nfeature: true\n")));
        GitFailure failure = failure(Reason.NOT_REACHABLE,
                () -> cache(Options.defaults().ref("refs/heads/main")).open().forDeploy("orders", feature));
        assertEquals("orders at " + feature + ": not reachable from the pool's ref", failure.getMessage());
        assertEquals("orders", failure.repository());
        assertEquals(feature, failure.target());
    }

    @Test
    void refusesAPoolRefTheRepositoryLacks() throws Exception {
        String main = source.commit("orders", "main", pkg("orders"));
        GitFailure failure = failure(Reason.NOT_FOUND,
                () -> cache(Options.defaults().ref("refs/heads/release")).open().forDeploy("orders", main));
        assertEquals("refs/heads/release", failure.target());
    }

    @Test
    void findsACommitOnAnyBranchOrTagWithoutAPoolRef() throws Exception {
        String main = source.commit("orders", "main", pkg("orders"));
        source.branch("orders", "feature", main);
        String feature = source.commit("orders", "feature", Map.of("package.yaml", file("name: orders\nfeature: true\n")));
        source.branch("orders", "tagged", main);
        String tagged = source.commit("orders", "tagged", Map.of("package.yaml", file("name: orders\ntagged: true\n")));
        source.tag("orders", "v1", tagged);
        source.branch("orders", "tagged", main);

        PackageRepositories repositories = cache(Options.defaults()).open();
        assertEquals(feature, repositories.forDeploy("orders", feature).id());
        assertEquals(tagged, repositories.forDeploy("orders", tagged).id());
    }

    @Test
    void answersNotFoundForACommitOrARepositoryTheSourceLacks() throws Exception {
        source.commit("orders", "main", pkg("orders"));
        String unknown = "0123456789abcdef0123456789abcdef01234567";
        PackageRepositories repositories = cache(Options.defaults()).open();
        assertEquals("orders at " + unknown + ": not found", failure(Reason.NOT_FOUND, () -> repositories.forDeploy("orders", unknown)).getMessage());
        failure(Reason.NOT_FOUND, () -> repositories.forDeploy("missing", unknown));
        failure(Reason.NOT_FOUND, () -> repositories.recorded("missing", unknown));
    }

    @Test
    void resolvesARefInDevAndCiAtTheFetch() throws Exception {
        String first = source.commit("orders", "main", pkg("orders"));
        source.tag("orders", "v1", first);
        String second = source.commit("orders", "main", Map.of("package.yaml", file("name: orders\nversion: 2\n")));
        source.branch("orders", "v1-branch", first);
        source.tag("orders", "both", first);
        source.branch("orders", "both", second);

        PackageRepositories repositories = cache(Options.defaults()).open();
        assertEquals(second, repositories.forDeploy("orders", "HEAD").id());
        assertEquals(second, repositories.forDeploy("orders", "main").id());
        assertEquals(second, repositories.forDeploy("orders", "refs/heads/main").id());
        assertEquals(first, repositories.forDeploy("orders", "v1").id());
        assertEquals(first, repositories.forDeploy("orders", "refs/tags/v1").id());
        assertEquals(second, repositories.forDeploy("orders", "both").id(), "a branch before a tag of the same name");
        assertEquals("orders at nope: not found", failure(Reason.NOT_FOUND, () -> repositories.forDeploy("orders", "nope")).getMessage());
    }

    @Test
    void provesARefsCommitReachableFromThePoolRef() throws Exception {
        String main = source.commit("orders", "main", pkg("orders"));
        source.branch("orders", "feature", main);
        source.commit("orders", "feature", Map.of("package.yaml", file("name: orders\nfeature: true\n")));
        PackageRepositories repositories = cache(Options.defaults().ref("refs/heads/main")).open();
        assertEquals(main, repositories.forDeploy("orders", "main").id());
        failure(Reason.NOT_REACHABLE, () -> repositories.forDeploy("orders", "feature"));
    }

    @Test
    void refusesARefInADeployedEnvironmentBeforeAnyFetch() {
        PackageRepositories repositories = cache(Options.defaults().inADeployedEnvironment().ref("refs/heads/main")).open();
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> repositories.forDeploy("missing", "main"));
        assertTrue(refused.getMessage().contains("40 hex digits only"), refused.getMessage());
    }

    @Test
    void refusesARepositoryNameOtherThanOneSegmentAndACommitThatIsNoId() {
        PackageRepositories repositories = cache(Options.defaults()).open();
        String id = "0123456789abcdef0123456789abcdef01234567";
        for (String name : List.of("", ".", "..", "a/b", "../orders", "a b", "x".repeat(101), "orders\\x")) {
            assertThrows(IllegalArgumentException.class, () -> repositories.forDeploy(name, id), name);
            assertThrows(IllegalArgumentException.class, () -> repositories.recorded(name, id), name);
        }
        assertThrows(IllegalArgumentException.class, () -> repositories.recorded("orders", "main"));
        assertThrows(IllegalArgumentException.class, () -> repositories.recorded("orders", id.substring(1)));
        assertThrows(IllegalArgumentException.class, () -> repositories.forDeploy("orders", "refs/remotes/origin/main"));
        assertThrows(IllegalArgumentException.class, () -> repositories.forDeploy("orders", "bad..ref"));
        assertThrows(IllegalArgumentException.class, () -> repositories.forDeploy("orders", ""));
        assertEquals("x".repeat(100), Requests.repository("x".repeat(100)));
    }

    @Test
    void refusesAShallowSourceAsIncomplete() throws Exception {
        source.commit("orders", "main", pkg("orders"));
        source.commit("orders", "main", Map.of("package.yaml", file("name: orders\nversion: 2\n")));
        String tip = source.commit("orders", "main", Map.of("package.yaml", file("name: orders\nversion: 3\n")));
        source.shallowCopy("orders", "shallow");
        failure(Reason.HISTORY_INCOMPLETE, () -> cache(Options.defaults()).open().forDeploy("shallow", tip));
        failure(Reason.HISTORY_INCOMPLETE, () -> cache(Options.defaults()).open().recorded("shallow", tip));
    }

    @Test
    void timesOutTheFetchesOfOneDeployTogether() throws Exception {
        String orders = source.commit("orders", "main", pkg("orders"));
        String common = source.commit("common", "main", pkg("common"));
        Cache cache = cache(Options.defaults());
        PackageRepositories deploy = cache.open();
        deploy.forDeploy("orders", orders);
        clock.advance(GitSettings.TIMEOUT_DEFAULT);
        GitFailure failure = failure(Reason.TIMED_OUT, () -> deploy.forDeploy("common", common));
        assertEquals("common at " + common + ": timed out (RAIL8_GIT_TIMEOUT)", failure.getMessage());

        assertEquals(common, cache.open().forDeploy("common", common).id(), "the next deploy has a deadline of its own");
    }

    @Test
    void freesTheRepositoriesNothingNamesWhenTheCacheIsFull() throws Exception {
        Random random = new Random(7);
        byte[] big = new byte[3 << 19];
        Map<String, String> ids = new HashMap<>();
        for (String name : List.of("orders", "common", "billing")) {
            random.nextBytes(big);
            ids.put(name, source.commit(name, "main", Map.of("package.yaml", file("name: " + name + "\n"), "data.bin", new File(big.clone()))));
        }
        Set<String> named = new java.util.HashSet<>();
        Cache cache = cache(Options.defaults().maxBytes(2L << 20).maxPackageBytes(2L << 20).named(() -> Set.copyOf(named)));

        cache.open().forDeploy("orders", ids.get("orders"));
        cache.open().forDeploy("common", ids.get("common"));
        source.delete("orders");
        failure(Reason.NOT_FOUND, () -> cache.open().recorded("orders", ids.get("orders")));

        named.add("common");
        GitFailure full = failure(Reason.CACHE_FULL, () -> cache.open().forDeploy("billing", ids.get("billing")));
        assertEquals("billing at " + ids.get("billing") + ": the git cache is full (RAIL8_GIT_MAX_BYTES)", full.getMessage());
        source.delete("common");
        cache.open().recorded("common", ids.get("common")).write(empty("common"));
    }

    @Test
    void readsARecordedCommitFromTheCacheWithTheSourceGone() throws Exception {
        String id = source.commit("orders", "main", Map.of("package.yaml", file("name: orders\n"), "flows/in.yaml", file("flow: in\n")));
        Cache cache = cache(Options.defaults());
        cache.open().forDeploy("orders", id);
        source.delete("orders");

        Commit commit = cache.open().recorded("orders", id);
        assertEquals(id, commit.id());
        Path dir = empty("orders");
        commit.write(dir);
        assertEquals(Set.of("package.yaml", "flows/in.yaml"), files(dir).keySet());
    }

    @Test
    void fetchesARecordedCommitTheCacheLacks() throws Exception {
        String first = source.commit("orders", "main", pkg("orders"));
        source.commit("orders", "main", Map.of("package.yaml", file("name: orders\nversion: 2\n")));
        Commit commit = cache(Options.defaults()).open().recorded("orders", first);
        commit.write(empty("orders"));
        failure(Reason.NOT_FOUND, () -> cache(Options.defaults()).open().recorded("orders", "0123456789abcdef0123456789abcdef01234567"));
    }

    @Test
    void checksNoReachabilityOfARecordedCommit() throws Exception {
        String main = source.commit("orders", "main", pkg("orders"));
        source.branch("orders", "feature", main);
        String feature = source.commit("orders", "feature", Map.of("package.yaml", file("name: orders\nfeature: true\n")));
        cache(Options.defaults()).open().forDeploy("orders", feature);

        Cache withRef = cache(Options.defaults().ref("refs/heads/main"));
        assertEquals(feature, withRef.open().recorded("orders", feature).id());
        failure(Reason.NOT_REACHABLE, () -> withRef.open().forDeploy("orders", feature));
    }

    @Test
    void neverShowsTheSourcesOwnMessage() throws Exception {
        String id = source.commit("orders", "main", pkg("orders"));
        GitFailure failure = failure(Reason.NOT_FOUND, () -> cache(Options.defaults()).open().forDeploy("other", id));
        assertEquals("other at " + id + ": not found", failure.getMessage());
        assertEquals(null, failure.getCause());
        assertEquals(0, failure.getStackTrace().length);
        assertEquals(0, failure.getSuppressed().length);
    }
}
