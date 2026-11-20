package dev.rail8.runtime.deploy;

import dev.rail8.api.Ack;
import dev.rail8.api.Headers;
import dev.rail8.api.Runtime;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.runtime.deploy.DeployRefusedException.Kind;
import dev.rail8.runtime.git.GitSettings;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Future;
import dev.rail8.runtime.deploy.DeploymentStore.PoolLock;
import dev.rail8.runtime.deploy.DeploymentStore.Snapshot;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static dev.rail8.runtime.deploy.DeploymentsRedeployTest.assertResult;
import static dev.rail8.runtime.deploy.DeploymentsRedeployTest.byId;
import static dev.rail8.runtime.deploy.DeploymentsRedeployTest.reachedWhile;
import static dev.rail8.runtime.deploy.DeploymentsTest.children;
import static dev.rail8.runtime.deploy.DeploymentsTest.joinRefused;
import static dev.rail8.runtime.deploy.DeploymentsTest.until;
import static dev.rail8.runtime.deploy.DeploymentsTest.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Restore at node start. {@code Deployments.restore()} blocks, under the deploy lock, and reads the pool's records
 * ({@code records.list()}, by name); for each it reads the record's commit from the node's git cache, fetching it only when the
 * cache lacks it, writes the package folder into {@code .incoming} (always deleted), checks its content against the record's and
 * installs it in restore mode — no 403 in a deployed environment, no record and no audit row written, repository, commit and
 * {@code deployedAt} from the record; a flow whose consumer does not start is listed RESTARTING and tried again, the others run. A
 * package refused whole (its commit cannot be read, its content differs, the seam refuses its tree, it has problems) is listed
 * FAILED with its problems and no flows or files, with an ERROR log, and the other packages run. Once a fetch cannot reach the git
 * host, the commits the cache lacks fail at once with that reason. Records that cannot be read — and an IOException of the node's
 * own data directory — throw (the node does not start); nothing about one package does. A deploy afterwards: over a package with a
 * RESTARTING flow it is a redeploy, over a FAILED package it is a first deploy.
 *
 * <p>Fixtures: "nodes" of one pool ({@link Peer}), each with its own data directory (its git cache in it), its own {@link Doors}
 * and its own {@code Deployments} over the shared fake runtime, in-memory message store, DLQ and work queue. The pool's records
 * are one {@link Ledger}, its repositories one {@link GitRepos} under {@code <tmp>/repos}, one per package. ERROR logs are read off
 * the {@code dev.rail8} JUL logger (System.Logger ERROR is JUL SEVERE).
 */
class DeploymentsRestoreTest {

    static final Instant NOW = Instant.parse("2026-10-27T09:30:00.123456789Z");
    static final Duration STOP_DEADLINE = Duration.ofSeconds(37);
    /** A content digest no package folder has. */
    static final String NO_CONTENT = "sha256:" + "0".repeat(64);

    @TempDir Path tmp;
    GitRepos repos;

    final FakeRuntime rt = FakeRuntime.create().withClockAt(NOW);
    final InMemoryMessageStore messages = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();
    /** The records' saves, in the order they were made. */
    final List<String> log = new CopyOnWriteArrayList<>();
    final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    /** Strongly held: JUL keeps loggers weakly, and a collected logger loses its handler. */
    final Logger rail8 = Logger.getLogger("dev.rail8");
    final Handler capture = new Handler() {
        @Override public void publish(LogRecord r) { logged.add(r); }
        @Override public void flush() {}
        @Override public void close() {}
    };
    final List<Peer> peers = new ArrayList<>();
    CountingQueue queue;
    /** The pool's records (Postgres on a node). */
    Ledger records;

    @BeforeEach
    void wire() {
        repos = new GitRepos(tmp.resolve("repos"));
        queue = new CountingQueue(rt.clock());
        records = new Ledger(log::add);
        capture.setLevel(Level.ALL);
        rail8.addHandler(capture);
    }

    @AfterEach
    void closeEveryPeer() throws Exception {
        try {
            for (Peer p : peers) p.doors.releaseAll();
            records.releaseAll();
            for (Peer p : peers) p.close();
        } finally {
            rail8.removeHandler(capture);
        }
    }

    // --- fixture: the nodes of one pool

    /** One node: its data directory, its Doors, its Deployments over the pool's {@code rows} and the pool's repositories. */
    final class Peer {
        final String id;
        final Path data;
        final Doors doors = new Doors();
        final Deployments deployments;

        /** A node in {@code env} whose data directory is {@code <tmp>/<id>}. */
        Peer(String id, String env, DeploymentStore rows) {
            this(id, tmp.resolve(id), Map.of("RAIL8_ENV", env), rows);
        }

        /**
         * A node whose data directory is {@code data} — another node's, once that one is closed, is a restart — with
         * {@code settings} over the defaults ({@code dev}, the pool's {@code file://} base); an empty value removes the key.
         */
        Peer(String id, Path data, Map<String, String> settings, DeploymentStore rows) {
            this.id = id;
            this.data = data;
            Map<String, String> e = new HashMap<>();
            e.put("RAIL8_ENV", "dev");
            e.put("RAIL8_NODE_ID", id);
            e.put("RAIL8_DATA_DIR", data.toString());
            e.put("RAIL8_STOP_DEADLINE", STOP_DEADLINE.toString());
            e.put("RAIL8_BLOB_DURABLE_MAX", "4096");
            e.put("RAIL8_MAX_PACKAGE_BYTES", Long.toString(2L * 1024 * 1024));
            e.put("RAIL8_GIT_BASE", repos.base().toString());
            settings.forEach((k, v) -> {
                if (v.isEmpty()) e.remove(k);
                else e.put(k, v);
            });
            TracingTimer timer = new TracingTimer(rt.timer());
            Runtime runtime = new Runtime(rt.clock(), timer, rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
            deployments = new Deployments(NodeConfig.from(e), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), timer, List.of(), List.of(), messages, dlq,
                    queue, () -> 0L, rows);
            peers.add(this);
        }

        /** Commits {@code p} to the repository of its name and deploys that commit. */
        DeployResult deploy(PackageDraft p) throws Exception {
            String commit = repos.commit(p);
            return deploy(p.name(), commit);
        }

        DeployResult deploy(String repository, String commit) throws Exception {
            return OnVirtualThread.call(() -> deployments.deploy(repository, commit, "test"));
        }

        DeployRefusedException refused(String repository, String commit) throws Exception {
            return joinRefused(OnVirtualThread.start(() -> deployments.deploy(repository, commit, "test")), "the deploy of " + repository + " on " + id
                    + " is refused");
        }

        OnVirtualThread.Call<Void> restoring() {
            return OnVirtualThread.start(() -> {
                deployments.restore();
                return null;
            });
        }

        /** Restores and waits for it: what it threw is rethrown, unwrapped. */
        void restore() throws Exception {
            OnVirtualThread.join(restoring());
        }

        void close() throws Exception {
            OnVirtualThread.call(() -> {
                deployments.close();
                return null;
            });
        }

        DeployedPackage pkg(String name) {
            return deployments.packages().stream().filter(p -> p.name().equals(name)).findFirst()
                    .orElseGet(() -> fail(id + " lists no package " + name + ": " + deployments.packages()));
        }

        /** The doors of {@code flow} that started and were not stopped since. */
        List<Doors.Door> live(String flow) {
            return doors.doors().stream().filter(d -> d.flow().equals(flow) && d.started() && !d.stopped()).toList();
        }

        /** Every door of the package {@code name} whose start this node called, whatever came of it. */
        List<Doors.Door> startedOf(String name) {
            return doors.doors().stream().filter(d -> d.flow().startsWith(name + "/") && (d.started() || d.history().contains("start"))).toList();
        }

        Ack send(String flow) throws Exception {
            List<Doors.Door> running = live(flow);
            assertEquals(1, running.size(), id + ": one live door of " + flow + ": " + doors.events());
            return within(running.getFirst().deliver(rt.ingress(flow, Headers.empty(), Buffer.buffer("<order/>"))), "the answer of " + flow + " on " + id);
        }
    }

    /** A {@link DeploymentStore} whose rows cannot be read: list fails with {@code message}; it is never written to. */
    record Unreadable(String message) implements DeploymentStore {
        @Override
        public Future<Optional<PoolLock>> lock() {
            return fail("a restore takes no lock");
        }

        @Override
        public Future<Snapshot> read() {
            return Future.failedFuture(new IllegalStateException(message));
        }

        @Override
        public Future<Void> audit(String actor, String repository, JsonObject detail) {
            return fail("a restore writes no audit row: " + detail);
        }

        @Override
        public Future<Void> endMarks() {
            return fail("a restore ends no mark");
        }
    }

    /**
     * An https git host on loopback that accepts every connection and never answers: a fetch from it waits until the git timeout
     * on the node's timer cuts it. It counts the connections it accepted.
     */
    static final class Silent implements AutoCloseable {
        private final ServerSocket server;
        private final List<Socket> held = new CopyOnWriteArrayList<>();
        private final AtomicInteger accepted = new AtomicInteger();

        Silent() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread.ofPlatform().daemon().name("silent-git-host").start(() -> {
                try {
                    while (true) {
                        held.add(server.accept());
                        accepted.incrementAndGet();
                    }
                } catch (IOException closed) {
                    // the server socket closed: the case is over
                }
            });
        }

        /** The base of its repositories. */
        String base() {
            return "https://127.0.0.1:" + server.getLocalPort() + "/pool/";
        }

        int accepted() {
            return accepted.get();
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (Socket s : held) s.close();
        }
    }

    Peer first() {
        return new Peer("node-1", "dev", records);
    }

    // --- fixture: packages

    /** {@code name} 1.0.0 with a sync flow per id, each from its own in endpoint {@code <id>-in} to {@code partner}. */
    static PackageDraft pkg(String name, String... flows) {
        PackageDraft p = PackageDraft.of(name, "1.0.0").endpoint("partner", "out", new JsonObject().put("url", "rec://partner"));
        for (String id : flows) {
            p.endpoint(id + "-in", "in", new JsonObject().put("path", "/" + id)).flow(id, "1.0.0", "sync", id + "-in", 1024, PackageDraft.toPartner());
        }
        return p;
    }

    /** A record the pool holds that no node deployed: {@code files} committed to the repository {@code name}, recorded with {@code content}. */
    String stored(String name, Map<String, byte[]> files, String content) {
        String commit = repos.commit(name, files);
        records.save(new DeploymentRecord(name, "1.0.0", name, commit, content, NOW, List.of(), List.of())).await();
        return commit;
    }

    /** {@code name}'s record with {@code content} in place of its own. */
    void recordedWith(String name, String content) {
        DeploymentRecord row = records.rows().stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
        records.save(new DeploymentRecord(row.name(), row.version(), row.repository(), row.commit(), content, row.deployedAt(), row.dependencies(),
                row.atLeastOnce())).await();
    }

    /** {@code p}'s files, each under {@code folder/}. */
    static Map<String, byte[]> under(String folder, PackageDraft p) {
        Map<String, byte[]> files = new TreeMap<>();
        p.files().forEach((path, bytes) -> files.put(folder + "/" + path, bytes));
        return files;
    }

    static String state(DeployedPackage p) {
        return p.state().name();
    }

    /** The package as a node lists it after a refusal: FAILED, with problems, and no flows and no files. */
    static void assertFailed(DeployedPackage p, String what) {
        assertEquals("FAILED", state(p), what + ": listed FAILED: " + p);
        assertFalse(p.problems().isEmpty(), what + ": its problems say why: " + p);
        assertEquals(List.of(), p.flows(), what + ": a FAILED package has no flows: " + p);
        assertEquals(List.of(), p.files(), what + ": nor files: " + p);
    }

    static void assertProblemNames(DeployedPackage p, String text, String what) {
        assertTrue(p.problems().stream().anyMatch(l -> l.contains(text)), what + ": a problem line names '" + text + "': " + p.problems());
    }

    static void assertRunning(DeployedPackage p, String what) {
        assertEquals("RUNNING", state(p), what + ": " + p);
        assertEquals(List.of(), p.problems(), what + ": no problems: " + p);
    }

    /** The ERROR records (JUL SEVERE) one of whose texts names {@code name}. */
    List<LogRecord> errorsNaming(String name) {
        return logged.stream().filter(r -> r.getLevel() == Level.SEVERE && DeploymentsProducersTest.texts(r).stream().anyMatch(t -> t.contains(name))).toList();
    }

    static boolean anyCause(Throwable t, java.util.function.Predicate<Throwable> which) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (which.test(c)) return true;
        }
        return false;
    }

    /** Asserts {@code restored} lists what {@code deployed} listed: name, version, repository, commit, content, flows, files. */
    static void assertSamePackages(List<DeployedPackage> deployed, List<DeployedPackage> restored) {
        assertEquals(deployed.stream().map(DeployedPackage::name).toList(), restored.stream().map(DeployedPackage::name).toList(), "the same packages, by name");
        for (int i = 0; i < deployed.size(); i++) {
            DeployedPackage was = deployed.get(i);
            DeployedPackage now = restored.get(i);
            assertEquals(was.version(), now.version(), now.name());
            assertEquals(was.repository(), now.repository(), now.name());
            assertEquals(was.commit(), now.commit(), now.name());
            assertEquals(was.content(), now.content(), now.name());
            assertEquals(was.flows(), now.flows(), now.name());
            assertEquals(was.files(), now.files(), now.name() + ": the commit's files");
            assertRunning(now, now.name() + " restored");
        }
    }

    // --- restore starts what the records name, in restore mode

    /**
     * Node 1 deployed {@code store}, then {@code shop}, and closed. Node 2, with an empty data directory and its own component,
     * fetches both commits and restores them: no record and no audit row written; every package and flow listed as node 1 listed
     * it (versions, repository, commit, content, files, effective hashes), RUNNING, and every flow serving. The restore holds the
     * deploy lock: a deploy while it runs is a 409 (CONFLICT).
     */
    @Test
    void restoreFetchesEachCommitAndStartsItsPackageWithoutARecord() throws Exception {
        Peer first = first();
        first.deploy(pkg("store", "c"));   // store first: the restore goes by name, shop first
        first.deploy(pkg("shop", "a", "b"));
        List<DeployedPackage> deployed = first.deployments.packages();
        Map<String, DeployedFlow> flows = byId(first.deployments.flows());
        first.close();
        List<DeploymentRecord> saves = records.saves();
        int audited = records.audited().size();
        assertEquals(2, saves.size(), "fixture: both deploys are recorded");
        String other = repos.commit(pkg("other", "x"));

        Peer second = new Peer("node-2", "dev", records);
        assertFalse(Files.exists(second.data), "fixture: node 2 starts with an empty data directory and git cache");
        Doors.Gate held = second.doors.holdStartOf("store/c");
        OnVirtualThread.Call<Void> restoring = second.restoring();
        reachedWhile(restoring, held.reached(), "the restore starts store's flow");
        DeployRefusedException busy = second.refused("other", other);
        assertEquals(Kind.CONFLICT, busy.kind(), "the restore holds the node's deploy lock: " + busy.getMessage());
        held.release();
        OnVirtualThread.join(restoring);

        assertEquals(saves, records.saves(), "a restore writes no record");
        assertEquals(audited, records.audited().size(), "a restore writes no audit row, nor does a deploy refused before its fetch");
        assertSamePackages(deployed, second.deployments.packages());
        assertEquals(flows, byId(second.deployments.flows()), "every flow as node 1 ran it: versions, commit, effective hash, RUNNING");
        for (String flow : List.of("shop/a", "shop/b", "store/c")) {
            assertInstanceOf(Ack.Reply.class, second.send(flow), flow + " serves on node 2");
        }
        assertEquals(1, second.live("shop/a").size(), "a restored flow's consumer runs on node 2: " + second.doors.events());
    }

    /**
     * Node 1 deployed shop and store and closed; then both repositories are gone, and so is every package directory of its data
     * directory. Restarted on that data directory, it restores both from its git cache: listed as before, RUNNING, serving.
     */
    @Test
    void restoreFromAWarmCacheWithTheRepositoriesGone() throws Exception {
        Peer first = first();
        first.deploy(pkg("shop", "a", "b"));
        first.deploy(pkg("store", "c"));
        List<DeployedPackage> deployed = first.deployments.packages();
        first.close();
        repos.delete("shop");
        repos.delete("store");
        DeploymentsDependenciesTest.deleteTree(first.data.resolve("packages"));

        Peer restarted = new Peer("node-1b", first.data, Map.of(), records);
        restarted.restore();

        assertSamePackages(deployed, restarted.deployments.packages());
        for (String flow : List.of("shop/a", "shop/b", "store/c")) {
            assertInstanceOf(Ack.Reply.class, restarted.send(flow), flow + " serves, from the commit its git cache keeps");
        }
    }

    /**
     * A node in a deployed environment ({@code prod}: an https base, here one no host serves, and {@code RAIL8_GIT_REF}), where
     * a manual deploy is 403, restarted on the data directory of a dev node that deployed shop: its restore installs the recorded
     * package all the same — restore mode is no deploy — from its git cache, and its flows run; a deploy on that node is still
     * FORBIDDEN.
     */
    @Test
    void restoreRunsInADeployedEnvWhereADeployIs403() throws Exception {
        Peer first = first();
        PackageDraft shop = pkg("shop", "a", "b");
        first.deploy(shop);
        first.close();
        List<DeploymentRecord> saves = records.saves();

        Peer second = new Peer("node-1b", first.data, Map.of("RAIL8_ENV", "prod", "RAIL8_GIT_BASE", GitRepos.UNSERVED, "RAIL8_GIT_REF", "refs/heads/main"),
                records);
        second.restore();

        assertRunning(second.pkg("shop"), "restored in prod");
        assertEquals(List.of("RUNNING", "RUNNING"), second.deployments.flows().stream().map(f -> f.state().name()).toList(), "both flows run");
        assertInstanceOf(Ack.Reply.class, second.send("shop/a"), "a serves");
        assertInstanceOf(Ack.Reply.class, second.send("shop/b"), "b serves");
        assertEquals(saves, records.saves(), "no record");
        DeployRefusedException deploy = second.refused("shop", shop.commit());
        assertEquals(Kind.FORBIDDEN, deploy.kind(), "witness: the node is deployed, a deploy on it is 403: " + deploy.getMessage());
    }

    /**
     * A flow whose consumer does not start on node 2 runs on, listed RESTARTING — its failed consumer stopped, nothing of it
     * serving — and the others run, the other flows of its package included; the package itself was not refused: it is listed
     * RUNNING with both flows, and the restore returns. A second later a fresh consumer of it starts: it is listed as recorded and
     * serves. RESTARTING is never stored: the records are the ones node 1 wrote, and node 3 restoring them lists b RUNNING.
     */
    @Test
    void aFlowWhoseConsumerDoesNotStartOnRestoreIsTriedAgainTheOthersRun() throws Exception {
        Peer first = first();
        first.deploy(pkg("shop", "a", "b"));
        first.deploy(pkg("store", "c"));
        Map<String, DeployedFlow> before = byId(first.deployments.flows());
        List<DeploymentRecord> rows = records.rows();
        first.close();

        Peer second = new Peer("node-2", "dev", records);
        second.doors.failStartOf("shop/b", "address in use");
        second.restore();

        Map<String, DeployedFlow> flows = byId(second.deployments.flows());
        DeployedFlow b = before.get("shop/b");
        assertEquals(new DeployedFlow(b.id(), b.pkg(), b.packageVersion(), b.commit(), b.flowVersion(), b.delivery(), b.hash(), DeployedFlow.State.RESTARTING),
                flows.get("shop/b"), "b is listed as recorded, RESTARTING: " + flows);
        assertEquals(before.get("shop/a"), flows.get("shop/a"), "a runs");
        assertEquals(before.get("store/c"), flows.get("store/c"), "c runs");
        DeployedPackage shop = second.pkg("shop");
        assertEquals("RUNNING", state(shop), "a flow's start does not refuse its package: " + shop);
        assertEquals(List.of("shop/a", "shop/b"), shop.flows(), "the package lists both flows");
        Doors.Door door = second.doors.door("shop/b");
        assertTrue(door.history().contains("start failed") && door.stopped(), "fixture: the restore called b's start, which failed, and stopped it: " + door.history());
        assertEquals(List.of(), second.live("shop/b"), "nothing of b serves: " + second.doors.events());
        assertInstanceOf(Ack.Reply.class, second.send("shop/a"), "a serves");
        assertInstanceOf(Ack.Reply.class, second.send("store/c"), "c serves");
        assertEquals(rows, records.rows(), "the restore stored nothing: the records are node 1's");

        rt.clock().advance(Duration.ofSeconds(1));

        until(() -> second.live("shop/b").size() == 1, "a fresh consumer of b runs: " + second.doors.events());
        until(() -> before.get("shop/b").equals(byId(second.deployments.flows()).get("shop/b")), "b is listed as recorded: " + second.deployments.flows());
        assertInstanceOf(Ack.Reply.class, second.send("shop/b"), "b serves");
        second.close();
        assertEquals(rows, records.rows(), "nothing of b's restart is stored");
        Peer third = new Peer("node-3", "dev", records);
        third.restore();
        assertEquals(before, byId(third.deployments.flows()), "node 3 lists every flow as recorded, b RUNNING");
    }

    // --- a package refused whole is FAILED, the others run

    /**
     * shop's repository is gone, and node 2's git cache never held its commit: shop is listed FAILED — version, repository,
     * commit and deployedAt from its record, its problem naming the commit not found, no flows, no files — with an ERROR log
     * naming it; nothing of it was built or started; store runs.
     */
    @Test
    void aCommitGoneFromItsRepositoryIsAFailedPackage() throws Exception {
        Peer first = first();
        first.deploy(pkg("shop", "a", "b"));
        first.deploy(pkg("store", "c"));
        first.close();
        DeploymentRecord row = records.rows().getFirst();
        assertEquals("shop", row.name(), "fixture: shop's record");
        repos.delete("shop");

        Peer second = new Peer("node-2", "dev", records);
        second.restore();

        DeployedPackage failed = second.pkg("shop");
        assertFailed(failed, "shop, whose commit is gone");
        assertEquals(row.version(), failed.version(), "the record's version: " + failed);
        assertEquals(Optional.of("shop"), failed.repository(), "the record's repository: " + failed);
        assertEquals(Optional.of(row.commit()), failed.commit(), "the record's commit: " + failed);
        assertEquals(row.deployedAt(), failed.deployedAt(), "the record's deployedAt: " + failed);
        assertEquals(List.of("shop at " + row.commit() + ": not found"), failed.problems(), "the problem names the commit");
        assertFalse(errorsNaming("shop").isEmpty(), "an ERROR log names shop: " + logged.stream().map(LogRecord::getMessage).toList());
        assertEquals(List.of("store/c"), second.deployments.flows().stream().map(DeployedFlow::id).toList(), "only store's flow is listed");
        assertEquals(List.of(), second.startedOf("shop"), "nothing of shop started: " + second.doors.events());
        assertRunning(second.pkg("store"), "store runs");
        assertInstanceOf(Ack.Reply.class, second.send("store/c"), "c serves");
    }

    /**
     * A node configured with another base than the deploy's (whose shop repository lacks the recorded commit) lists shop FAILED,
     * the commit not found; one configured with another {@code RAIL8_GIT_PATH} reads another package folder of the same commit,
     * whose content is not the record's: FAILED naming both digests and the setting, and nothing of that folder starts.
     */
    @Test
    void anotherBaseOrPathIsAFailedPackage() throws Exception {
        PackageDraft shop = pkg("shop", "a", "b");
        PackageDraft elsewhere = pkg("shop", "a", "b");
        elsewhere.manifest().put("version", "2.0.0");
        Map<String, byte[]> both = new TreeMap<>(under("package", shop));
        both.putAll(under("other", elsewhere));
        String commit = repos.commit("shop", both);
        Peer first = new Peer("node-1", tmp.resolve("node-1"), Map.of("RAIL8_GIT_PATH", "package"), records);
        first.deploy("shop", commit);
        first.close();
        GitRepos otherBase = new GitRepos(tmp.resolve("other-repos"));
        otherBase.commit(pkg("shop", "a"));

        Peer base = new Peer("node-2", tmp.resolve("node-2"), Map.of("RAIL8_GIT_BASE", otherBase.base().toString(), "RAIL8_GIT_PATH", "package"), records);
        base.restore();

        assertFailed(base.pkg("shop"), "shop, read under another base");
        assertEquals(List.of("shop at " + commit + ": not found"), base.pkg("shop").problems());

        Peer path = new Peer("node-3", tmp.resolve("node-3"), Map.of("RAIL8_GIT_PATH", "other"), records);
        path.restore();

        DeployedPackage failed = path.pkg("shop");
        assertFailed(failed, "shop, read at another path");
        assertEquals("1.0.0", failed.version(), "the record's version, not the other folder's: " + failed);
        assertEquals(List.of("shop at " + commit + " holds the content " + elsewhere.content() + ", its record " + shop.content()
                + ": the node reads another RAIL8_GIT_BASE or RAIL8_GIT_PATH than the deploy did"), failed.problems());
        assertFalse(errorsNaming("shop").isEmpty(), "an ERROR log names shop");
        assertEquals(List.of(), path.startedOf("shop"), "nothing of the other folder started: " + path.doors.events());
    }

    /**
     * Two packages that cannot be installed on node 2: shop has a problem in node 2's environment ({@code ci} lays
     * {@code level: extreme} over b's in endpoint, which the component's configSchema refuses; node 1 ran in dev), and junk's
     * commit holds no {@code package.yaml} (the seam refuses its tree). Each is listed FAILED with its problems and an ERROR log —
     * shop whole: its flow a, fine on its own, does not start either — and store runs.
     */
    @Test
    void aPackageWithProblemsIsFailedTheOthersRun() throws Exception {
        PackageDraft shop = pkg("shop", "a", "b");
        shop.endpointDoc("b-in").put("environments", new JsonObject().put("ci", new JsonObject().put("config", new JsonObject().put("level", "extreme"))));
        Peer first = first();
        first.deploy(shop);
        first.deploy(pkg("store", "c"));
        first.close();
        stored("junk", Map.of("README.md", "not a package".getBytes(StandardCharsets.UTF_8)), NO_CONTENT);

        Peer second = new Peer("node-2", "ci", records);
        second.restore();

        DeployedPackage refused = second.pkg("shop");
        assertFailed(refused, "shop, with a problem in ci");
        assertProblemNames(refused, "b-in", "the endpoint whose config ci refuses");
        assertFalse(errorsNaming("shop").isEmpty(), "an ERROR log names shop");
        assertEquals(List.of(), second.startedOf("shop"), "a package refused whole starts nothing, not even a: " + second.doors.events());
        DeployedPackage junk = second.pkg("junk");
        assertFailed(junk, "junk, whose commit holds no package");
        assertProblemNames(junk, "package.yaml", "the tree the seam refuses");
        assertFalse(errorsNaming("junk").isEmpty(), "an ERROR log names junk");
        assertRunning(second.pkg("store"), "store runs");
        assertEquals(List.of("store/c"), second.deployments.flows().stream().map(DeployedFlow::id).toList(), "only store's flow is listed");
        assertInstanceOf(Ack.Reply.class, second.send("store/c"), "c serves");
    }

    /**
     * Each record's package folder is written into {@code .incoming} and deleted whatever happened next: after a restore of a good
     * package, a commit not found, a tree the seam refuses and a content other than the record's, {@code .incoming} holds nothing.
     */
    @Test
    void restoreLeavesIncomingEmpty() throws Exception {
        Peer first = first();
        for (String name : List.of("shop", "store", "depot")) first.deploy(pkg(name, name.substring(0, 1)));
        first.close();
        stored("junk", Map.of("README.md", "not a package".getBytes(StandardCharsets.UTF_8)), NO_CONTENT);
        repos.delete("store");
        recordedWith("depot", NO_CONTENT);

        Peer second = new Peer("node-2", "dev", records);
        second.restore();

        assertEquals(List.of("depot", "junk", "shop", "store"), second.deployments.packages().stream().map(DeployedPackage::name).toList(),
                "fixture: every record was restored or refused");
        assertRunning(second.pkg("shop"), "fixture: shop runs");
        for (String failed : List.of("store", "junk", "depot")) assertEquals("FAILED", state(second.pkg(failed)), "fixture: " + failed + " is refused");
        assertEquals(List.of(), children(second.deployments.incomingDir()), "nothing is left in .incoming");
    }

    /**
     * {@code deployedAt}, repository and commit are the record's: node 2 restores five hours after node 1's deploys, and lists each
     * package with the instant its record holds (the deploy's, to the microsecond) — not node 2's clock — and the record's commit,
     * on the package and on its flows.
     */
    @Test
    void restoreListsCommitAndDeployedAtFromTheRecord() throws Exception {
        Peer first = first();
        PackageDraft shop = pkg("shop", "a");
        first.deploy(shop);
        rt.clock().advance(Duration.ofMinutes(1));
        PackageDraft store = pkg("store", "c");
        first.deploy(store);
        first.close();
        Map<String, DeploymentRecord> rows = new HashMap<>();
        records.rows().forEach(r -> rows.put(r.name(), r));
        assertEquals(Instant.parse("2026-10-27T09:30:00.123456Z"), rows.get("shop").deployedAt(), "fixture: shop's record, to the microsecond");
        assertEquals(Instant.parse("2026-10-27T09:31:00.123456Z"), rows.get("store").deployedAt(), "fixture: store's record");
        rt.clock().advance(Duration.ofHours(5));

        Peer second = new Peer("node-2", "dev", records);
        second.restore();

        assertEquals(rows.get("shop").deployedAt(), second.pkg("shop").deployedAt(), "shop's deployedAt is its record's, not node 2's clock ("
                + rt.clock().instant() + ")");
        assertEquals(rows.get("store").deployedAt(), second.pkg("store").deployedAt(), "store's deployedAt is its record's");
        assertEquals(Optional.of(shop.commit()), second.pkg("shop").commit(), "shop's commit is its record's");
        assertEquals(Optional.of("store"), second.pkg("store").repository(), "store's repository is its record's");
        Map<String, DeployedFlow> flows = byId(second.deployments.flows());
        assertEquals(Optional.of(shop.commit()), flows.get("shop/a").commit(), "a flow carries its record's commit");
        assertEquals(Optional.of(store.commit()), flows.get("store/c").commit());
    }

    /**
     * Records that cannot be read make the restore throw — the node does not start — with nothing listed or built, and so does an
     * IOException of the node's own data directory ({@code .incoming} cannot be made: the node's failure, not a package's). A node
     * with no {@code RAIL8_GIT_BASE} reads no commit: every package is listed FAILED saying so, and the restore returns.
     */
    @Test
    void restoreThrowsOnlyWhenRecordsCannotBeRead() throws Exception {
        Peer first = first();
        first.deploy(pkg("shop", "a"));
        first.deploy(pkg("store", "c"));
        first.close();

        Peer noBase = new Peer("node-2", tmp.resolve("node-2"), Map.of("RAIL8_GIT_BASE", ""), records);
        noBase.restore();   // returns: a package it cannot read is no reason to fail the node's start
        for (String name : List.of("shop", "store")) {
            DeployedPackage p = noBase.pkg(name);
            assertFailed(p, name + " on a node with no git base");
            assertEquals(List.of("the node has no RAIL8_GIT_BASE: it reads no commit"), p.problems(), name);
        }
        assertEquals(List.of(), noBase.deployments.flows(), "no flow runs");

        Peer third = new Peer("node-3", "dev", new Unreadable("relation \"deployments\" does not exist"));
        Throwable unreadable = assertThrows(Throwable.class, third::restore, "records that cannot be read fail the restore");
        assertTrue(anyCause(unreadable, c -> String.valueOf(c.getMessage()).contains("relation \"deployments\" does not exist")),
                "the failure carries the store's cause: " + unreadable);
        assertEquals(List.of(), third.deployments.packages(), "nothing is listed");
        assertEquals(List.of(), third.doors.doors(), "nothing is built");

        Peer fourth = new Peer("node-4", "dev", records);
        Files.createDirectories(fourth.data);
        Files.writeString(fourth.deployments.incomingDir(), "a file where the node keeps its .incoming directory");
        Throwable dataDir = assertThrows(Throwable.class, fourth::restore, "an IOException of the data directory fails the restore");
        assertTrue(anyCause(dataDir, c -> c instanceof IOException), "the data directory's IOException: " + dataDir);
        assertEquals(List.of(), fourth.doors.doors().stream().filter(Doors.Door::started).toList(), "nothing started on node 4");
    }

    /**
     * Node 1 deployed alpha and gamma, node 3 beta and delta. Node 1 restarts on its data directory with its git base at a host that
     * accepts and never answers: alpha restores from the cache; beta's fetch is the one connection the host ever sees, cut by the
     * git timeout on the node's clock — beta FAILED, timed out; gamma restores from the cache; delta, which the cache lacks, is
     * FAILED at once with the same reason, without a fetch. The restore returns, and nothing is tried again an hour later.
     */
    @Test
    void anUnreachableGitHostFailsTheLaterPackagesWithoutAFetchEach() throws Exception {
        Peer first = first();
        first.deploy(pkg("alpha", "a"));
        first.deploy(pkg("gamma", "g"));
        first.close();
        Peer other = new Peer("node-3", "dev", records);
        other.deploy(pkg("beta", "b"));
        other.deploy(pkg("delta", "d"));
        other.close();

        try (Silent host = new Silent()) {
            Peer restarted = new Peer("node-1b", first.data, Map.of("RAIL8_GIT_BASE", host.base()), records);
            OnVirtualThread.Call<Void> restoring = restarted.restoring();
            until(() -> host.accepted() == 1, "beta's fetch reaches the git host");
            assertFalse(restoring.isDone(), "the restore waits on beta's fetch");

            rt.clock().advance(GitSettings.TIMEOUT_DEFAULT);
            OnVirtualThread.join(restoring);

            assertRunning(restarted.pkg("alpha"), "alpha, from the cache");
            assertRunning(restarted.pkg("gamma"), "gamma, from the cache after beta's fetch failed");
            String timedOut = ": timed out (RAIL8_GIT_TIMEOUT)";
            for (String name : List.of("beta", "delta")) {
                DeployedPackage p = restarted.pkg(name);
                assertFailed(p, name);
                assertEquals(List.of(name + " at " + p.commit().orElseThrow() + timedOut), p.problems(), name);
            }
            assertEquals(1, host.accepted(), "delta was not fetched: one connection, beta's");
            assertInstanceOf(Ack.Reply.class, restarted.send("gamma/g"), "gamma serves");

            rt.clock().advance(Duration.ofHours(1));   // a retry on the node's timer would be due now
            assertEquals(1, host.accepted(), "no retry: recovery is a restart or a deploy");
            assertEquals("FAILED", state(restarted.pkg("delta")));
        }
    }

    // --- a deploy after a restore

    /**
     * Node 2 restored shop with b RESTARTING (its consumer's start failed). A deploy of the recorded commit is a redeploy that
     * changes no flow: b, stored RUNNING with the same hash, is unchanged and its consumer is tried on; a runs on untouched; the
     * deploy writes its record. A second later a fresh consumer of b starts and b serves.
     */
    @Test
    void aDeployAfterARestoreLeavesARestartingFlowToItsRestart() throws Exception {
        Peer first = first();
        PackageDraft shop = pkg("shop", "a", "b");
        first.deploy(shop);
        first.close();
        Peer second = new Peer("node-2", "dev", records);
        second.doors.failStartOf("shop/b", "address in use");
        second.restore();
        assertEquals(DeployedFlow.State.RESTARTING, byId(second.deployments.flows()).get("shop/b").state(), "fixture: b is RESTARTING after the restore");
        Doors.Door a = second.live("shop/a").getFirst();
        int saves = records.saves().size();
        int built = second.doors.doors().size();

        DeployResult again = second.deploy("shop", shop.commit());

        assertResult(List.of(), List.of(), List.of(), again);
        assertEquals(built, second.doors.doors().size(), "the deploy builds no consumer: " + second.doors.events());
        assertFalse(a.stopped(), "a, unchanged, was not touched: " + a.history());
        assertEquals(List.of(a), second.live("shop/a"), "a's restored door serves on");
        assertEquals(DeployedFlow.State.RESTARTING, byId(second.deployments.flows()).get("shop/b").state(), "b's consumer is still tried again");
        assertEquals(saves + 1, records.saves().size(), "the deploy writes the package's record");

        rt.clock().advance(Duration.ofSeconds(1));

        until(() -> second.live("shop/b").size() == 1, "a fresh consumer of b runs: " + second.doors.events());
        assertInstanceOf(Ack.Reply.class, second.send("shop/b"), "b serves");
        assertRunning(second.pkg("shop"), "the package after the deploy");
    }

    /**
     * Node 2 restored shop FAILED (its commit was gone). A deploy of shop, committed again, is a first deploy: one whose start
     * fails is refused as a first deploy is ("flow ... did not start", no rollback — a redeploy would answer "version X did not
     * start; Y runs again"), listing shop as before, FAILED; the next deploy adds both flows, lists shop RUNNING without problems
     * and writes its record.
     */
    @Test
    void aDeployOverAFailedPackageIsAFirstDeploy() throws Exception {
        Peer first = first();
        PackageDraft shop = pkg("shop", "a", "b");
        first.deploy(shop);
        first.close();
        repos.delete("shop");
        Peer second = new Peer("node-2", "dev", records);
        second.restore();
        assertEquals("FAILED", state(second.pkg("shop")), "fixture: shop is FAILED after the restore");
        List<DeployedPackage> failedListing = second.deployments.packages();
        String again = repos.commit(shop);

        second.doors.failStartOf("shop/a", "address in use");
        DeployRefusedException refused = second.refused("shop", again);
        assertEquals(Kind.FAILED, refused.kind(), refused.getMessage());
        assertTrue(refused.error().contains("did not start") && !refused.error().contains("runs again"),
                "refused as a first deploy, not rolled back as a redeploy: " + refused.error());
        assertEquals(failedListing, second.deployments.packages(), "a refused deploy leaves the listing as it was: shop FAILED");
        assertEquals(List.of(), second.live("shop/a"), "nothing of the refused deploy runs");
        assertEquals(List.of(), second.live("shop/b"), "nothing of the refused deploy runs");

        DeployResult deployed = second.deploy("shop", again);

        assertResult(List.of("shop/a", "shop/b"), List.of(), List.of(), deployed);
        DeployedPackage now = second.pkg("shop");
        assertRunning(now, "shop after the deploy");
        assertEquals(List.of("shop/a", "shop/b"), now.flows());
        assertFalse(now.files().isEmpty(), "its files are listed again");
        assertEquals(List.of(new DeploymentRecord("shop", "1.0.0", "shop", again, shop.content(), deployed.pkg().deployedAt(), List.of(), List.of())), records.rows(),
                "the deploy's record");
        assertInstanceOf(Ack.Reply.class, second.send("shop/a"), "a serves");
        assertInstanceOf(Ack.Reply.class, second.send("shop/b"), "b serves");
    }
}
