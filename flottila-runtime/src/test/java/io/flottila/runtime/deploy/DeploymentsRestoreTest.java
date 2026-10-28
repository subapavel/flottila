package io.flottila.runtime.deploy;

import io.flottila.api.Ack;
import io.flottila.api.Headers;
import io.flottila.api.Runtime;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.runtime.deploy.DeployRefusedException.Kind;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static io.flottila.runtime.deploy.DeploymentsRedeployTest.assertResult;
import static io.flottila.runtime.deploy.DeploymentsRedeployTest.byId;
import static io.flottila.runtime.deploy.DeploymentsRedeployTest.reachedWhile;
import static io.flottila.runtime.deploy.DeploymentsTest.children;
import static io.flottila.runtime.deploy.DeploymentsTest.joinRefused;
import static io.flottila.runtime.deploy.DeploymentsTest.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P1-09c3b (D-070): restore at node start. {@code Deployments.restore()} blocks, under the deploy lock, and reads the pool's rows
 * ({@code records.list()}, by name); for each row it fetches the archive into a temporary file in {@code .incoming} (always
 * deleted), compares its SHA-256 with the row's and installs it in restore mode — no 403 in a deployed environment, no put, no
 * row written, {@code deployedAt} and {@code revision} from the row; a flow whose start fails is FAILED, the others run. A package
 * refused whole (its fetch failed, its SHA-256 differs, its unpack is refused, it has problems) is listed FAILED with its
 * problems and no flows or files, with an ERROR log, and the other packages run. Rows that cannot be read — and an IOException of
 * the node's own data directory — throw (the node does not start); nothing about one package does, S3 down included (every
 * package FAILED, fetched once, no retry). A PUT afterwards: over a package with FAILED flows it is a redeploy that starts them
 * (a FAILED flow counts as added, D-063), over a FAILED package it is a first deploy.
 *
 * <p>Fixtures: two "nodes" of one pool ({@link Peer}), each with its own data directory, its own {@link Doors} and its own
 * {@code Deployments} over the shared fake runtime, in-memory message store, DLQ and work queue. The pool's rows are one
 * {@link Ledger}; its archive store one {@link Shelf} over {@code <tmp>/bucket} (node 1 deploys through it). Node 2 restores
 * through {@link Fetches}, the same shelf with every fetch recorded (key, target) and failable. ERROR logs are read off the
 * {@code io.flottila} JUL logger (System.Logger ERROR is JUL SEVERE).
 *
 * <p>The shape these cases assume (P1-09c3b test-author): {@code Deployments.restore()} (blocking, on a virtual thread; its
 * return value is not read), {@code DeployedPackage.state()} — an enum whose names are {@code RUNNING} and {@code FAILED} — and
 * {@code DeployedPackage.problems()}, a {@code List<String>}.
 */
class DeploymentsRestoreTest {

    static final Instant NOW = Instant.parse("2026-10-27T09:30:00.123456789Z");
    static final Duration STOP_DEADLINE = Duration.ofSeconds(37);

    @TempDir Path tmp;
    Path bucket;
    Path uploads;

    final FakeRuntime rt = FakeRuntime.create().withClockAt(NOW);
    final InMemoryMessageStore messages = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();
    /** The two stores' traces (save, put, fetch), in the order they were made. */
    final List<String> log = new CopyOnWriteArrayList<>();
    final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    /** Strongly held: JUL keeps loggers weakly, and a collected logger loses its handler. */
    final Logger flottila = Logger.getLogger("io.flottila");
    final Handler capture = new Handler() {
        @Override public void publish(LogRecord r) { logged.add(r); }
        @Override public void flush() {}
        @Override public void close() {}
    };
    final List<Peer> peers = new ArrayList<>();
    CountingQueue queue;
    /** The pool's rows (Postgres on a node). */
    Ledger records;
    /** The pool's archive store (S3 on a node). */
    Shelf shelf;

    @BeforeEach
    void wire() {
        bucket = tmp.resolve("bucket");
        uploads = tmp.resolve("uploads");
        queue = new CountingQueue(rt.clock());
        records = new Ledger(log::add);
        shelf = new Shelf(bucket, tmp.resolve("shelf-reads"), log::add);
        capture.setLevel(Level.ALL);
        flottila.addHandler(capture);
    }

    @AfterEach
    void closeEveryPeer() throws Exception {
        try {
            for (Peer p : peers) p.doors.releaseAll();
            records.releaseAll();
            for (Peer p : peers) p.close();
        } finally {
            flottila.removeHandler(capture);
        }
    }

    // --- fixture: the nodes of one pool

    /** One node: its data directory {@code <tmp>/<id>}, its Doors, its Deployments over the pool's {@code rows} and {@code archives}. */
    final class Peer {
        final String id;
        final Path data;
        final Doors doors = new Doors();
        final Deployments deployments;

        Peer(String id, String env, DeploymentStore rows, ArchiveStore archives) {
            this.id = id;
            this.data = tmp.resolve(id);
            Map<String, String> e = new HashMap<>();
            e.put("FLOTTILA_ENV", env);
            e.put("FLOTTILA_NODE_ID", id);
            e.put("FLOTTILA_DATA_DIR", data.toString());
            e.put("FLOTTILA_STOP_DEADLINE", STOP_DEADLINE.toString());
            e.put("FLOTTILA_BLOB_DURABLE_MAX", "4096");
            e.put("FLOTTILA_MAX_PACKAGE_BYTES", Long.toString(2L * 1024 * 1024));
            TracingTimer timer = new TracingTimer(rt.timer());
            Runtime runtime = new Runtime(rt.clock(), timer, rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
            deployments = new Deployments(NodeConfig.from(e), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), timer, List.of(), List.of(), messages, dlq,
                    queue, () -> 0L, rows, archives);
            peers.add(this);
        }

        DeployResult deploy(String name, byte[] zip, Optional<String> revision) throws Exception {
            Path archive = PackageDraft.write(uploads, zip);
            return OnVirtualThread.call(() -> deployments.deploy(name, revision, archive));
        }

        DeployRefusedException refused(String name, byte[] zip, Optional<String> revision) throws Exception {
            Path archive = PackageDraft.write(uploads, zip);
            return joinRefused(OnVirtualThread.start(() -> deployments.deploy(name, revision, archive)), "the deploy of " + name + " on " + id + " is refused");
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

    /**
     * Node 2's view of the pool's archive store: the {@link Shelf}, with every fetch recorded — its key and its target, before
     * anything else happens — and failable: {@link #down} fails every fetch with an IOException, writing nothing (S3 down);
     * {@link #failAfterWriting} writes half an archive into the target, then fails (a connection reset mid-body).
     */
    static final class Fetches implements ArchiveStore {
        private final ArchiveStore pool;
        private final List<String> keys = new CopyOnWriteArrayList<>();
        private final List<Path> targets = new CopyOnWriteArrayList<>();
        private final Map<String, String> failAfterWriting = new ConcurrentHashMap<>();
        private volatile String down;

        Fetches(ArchiveStore pool) {
            this.pool = pool;
        }

        void down(String message) {
            down = message;
        }

        void failAfterWriting(String sha256, String message) {
            failAfterWriting.put(sha256, message);
        }

        /** The key of every fetch call, in order. */
        List<String> keys() {
            return List.copyOf(keys);
        }

        /** The target of every fetch call, in order. */
        List<Path> targets() {
            return List.copyOf(targets);
        }

        long fetchesOf(String sha256) {
            return keys.stream().filter(sha256::equals).count();
        }

        @Override
        public void put(String sha256, Path archive) throws IOException {
            pool.put(sha256, archive);
        }

        @Override
        public void fetch(String sha256, Path target) throws IOException {
            keys.add(sha256);
            targets.add(target);
            String unreachable = down;
            if (unreachable != null) throw new IOException(unreachable);
            String reset = failAfterWriting.get(sha256);
            if (reset != null) {
                Files.write(target, "PK half an archive".getBytes(StandardCharsets.UTF_8));
                throw new IOException(reset);
            }
            pool.fetch(sha256, target);
        }
    }

    /** A {@link DeploymentStore} whose rows cannot be read: list fails with {@code message}; it is never saved to. */
    record Unreadable(String message) implements DeploymentStore {
        @Override
        public Future<Void> save(DeploymentRecord record) {
            return fail("a restore writes no row: " + record);
        }

        @Override
        public Future<List<DeploymentRecord>> list() {
            return Future.failedFuture(new IllegalStateException(message));
        }
    }

    Peer first() {
        return new Peer("node-1", "dev", records, shelf);
    }

    Fetches fetches() {
        return new Fetches(shelf);
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

    static String sha(byte[] zip) {
        return PackageDraft.sha256(zip);
    }

    /** The stored object of {@code sha256} in the pool's archive store. */
    Path object(String sha256) {
        return bucket.resolve(sha256 + ".zip");
    }

    /** A row and an object the pool holds without any node having deployed them: {@code bytes} stored under their SHA-256. */
    String stored(String name, byte[] bytes) throws Exception {
        String sha256 = sha(bytes);
        Path file = PackageDraft.write(uploads, bytes);
        OnVirtualThread.call(() -> {
            shelf.put(sha256, file);
            return null;
        });
        records.save(new DeploymentRecord(name, "1.0.0", Optional.empty(), sha256, NOW)).await();
        return sha256;
    }

    static String state(DeployedPackage p) {
        return p.state().name();
    }

    /** The package as node 2 lists it after a refusal: FAILED, with problems, and no flows and no files. */
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

    // --- restore starts what the rows name, in restore mode

    /**
     * Node 1 deployed {@code store} (r1), then {@code shop} (r2) and closed. Node 2, with an empty data directory and its own
     * component, restores both from the pool's rows and archive store: one fetch per row, in name order; no put and no row;
     * every package and flow listed as node 1 listed it (same versions, revision, archive, files, effective hashes), RUNNING, and
     * every flow serving. The restore holds the deploy lock: a deploy while it runs is a 409 (CONFLICT).
     */
    @Test
    void restoreStartsEachPackageWithoutAPutOrARow() throws Exception {
        Peer first = first();
        byte[] store = pkg("store", "c").zip();
        byte[] shop = pkg("shop", "a", "b").zip();
        first.deploy("store", store, Optional.of("r1"));   // store first: the restore goes by name, shop first
        first.deploy("shop", shop, Optional.of("r2"));
        List<DeployedPackage> deployed = first.deployments.packages();
        Map<String, DeployedFlow> flows = byId(first.deployments.flows());
        first.close();
        List<String> puts = shelf.puts();
        List<DeploymentRecord> saves = records.saves();
        assertEquals(2, saves.size(), "fixture: both deploys are recorded");

        Fetches fetches = fetches();
        Peer second = new Peer("node-2", "dev", records, fetches);
        assertFalse(Files.exists(second.data), "fixture: node 2 starts with an empty data directory");
        Doors.Gate held = second.doors.holdStartOf("store/c");
        OnVirtualThread.Call<Void> restoring = second.restoring();
        reachedWhile(restoring, held.reached(), "the restore starts store's flow");
        Path other = PackageDraft.write(uploads, pkg("other", "x").zip());
        DeployRefusedException busy = joinRefused(OnVirtualThread.start(() -> second.deployments.deploy("other", Optional.empty(), other)),
                "a deploy while the restore runs");
        assertEquals(Kind.CONFLICT, busy.kind(), "the restore holds the node's deploy lock: " + busy.getMessage());
        held.release();
        OnVirtualThread.join(restoring);

        assertEquals(List.of(sha(shop), sha(store)), fetches.keys(), "one fetch per row, in name order");
        assertEquals(puts, shelf.puts(), "a restore puts nothing");
        assertEquals(saves, records.saves(), "a restore writes no row");
        List<DeployedPackage> restored = second.deployments.packages();
        assertEquals(List.of("shop", "store"), restored.stream().map(DeployedPackage::name).toList(), "both packages, by name");
        for (int i = 0; i < 2; i++) {
            DeployedPackage was = deployed.get(i);
            DeployedPackage now = restored.get(i);
            assertEquals(was.name(), now.name());
            assertEquals(was.version(), now.version(), now.name());
            assertEquals(was.revision(), now.revision(), now.name());
            assertEquals(was.archiveSha256(), now.archiveSha256(), now.name());
            assertEquals(was.flows(), now.flows(), now.name());
            assertEquals(was.files(), now.files(), now.name() + ": the archive's files, unpacked on node 2");
            assertRunning(now, now.name() + " restored");
        }
        assertEquals(flows, byId(second.deployments.flows()), "every flow as node 1 ran it: versions, revision, effective hash, RUNNING");
        for (String flow : List.of("shop/a", "shop/b", "store/c")) {
            assertInstanceOf(Ack.Reply.class, second.send(flow), flow + " serves on node 2");
        }
        assertTrue(second.deployments.replay("shop/a").isPresent(), "a restored flow has its replay");
    }

    /**
     * Node 2 runs in a deployed environment ({@code prod}), where a manual deploy is 403: its restore installs the recorded
     * package all the same — restore mode is no deploy — and its flows run; a PUT on that node is still FORBIDDEN.
     */
    @Test
    void restoreRunsInADeployedEnvWhereAPutIs403() throws Exception {
        Peer first = first();
        byte[] shop = pkg("shop", "a", "b").zip();
        first.deploy("shop", shop, Optional.of("r1"));
        first.close();
        List<String> puts = shelf.puts();
        List<DeploymentRecord> saves = records.saves();

        Peer second = new Peer("node-2", "prod", records, fetches());
        second.restore();

        assertRunning(second.pkg("shop"), "restored in prod");
        assertEquals(List.of("RUNNING", "RUNNING"), second.deployments.flows().stream().map(f -> f.state().name()).toList(), "both flows run");
        assertInstanceOf(Ack.Reply.class, second.send("shop/a"), "a serves");
        assertInstanceOf(Ack.Reply.class, second.send("shop/b"), "b serves");
        assertEquals(puts, shelf.puts(), "no put");
        assertEquals(saves, records.saves(), "no row");
        DeployRefusedException put = second.refused("shop", shop, Optional.of("r2"));
        assertEquals(Kind.FORBIDDEN, put.kind(), "witness: the node is deployed, a PUT on it is 403: " + put.getMessage());
    }

    /**
     * A flow whose consumer does not start on node 2 is listed FAILED — nothing of it runs, it has no replay — and the others
     * run, the other flows of its package included; the package itself was not refused: it is listed RUNNING with both flows,
     * and the restore returns.
     */
    @Test
    void aFlowThatDoesNotStartOnRestoreIsFailedTheOthersRun() throws Exception {
        Peer first = first();
        first.deploy("shop", pkg("shop", "a", "b").zip(), Optional.of("r1"));
        first.deploy("store", pkg("store", "c").zip(), Optional.empty());
        Map<String, DeployedFlow> before = byId(first.deployments.flows());
        first.close();

        Peer second = new Peer("node-2", "dev", records, fetches());
        second.doors.failStartOf("shop/b", "address in use");
        second.restore();

        Map<String, DeployedFlow> flows = byId(second.deployments.flows());
        DeployedFlow b = before.get("shop/b");
        assertEquals(new DeployedFlow(b.id(), b.pkg(), b.packageVersion(), b.revision(), b.flowVersion(), b.delivery(), b.hash(), DeployedFlow.State.FAILED),
                flows.get("shop/b"), "b is listed as recorded, FAILED: " + flows);
        assertEquals(before.get("shop/a"), flows.get("shop/a"), "a runs");
        assertEquals(before.get("store/c"), flows.get("store/c"), "c runs");
        DeployedPackage shop = second.pkg("shop");
        assertEquals("RUNNING", state(shop), "a flow's start does not refuse its package: " + shop);
        assertEquals(List.of("shop/a", "shop/b"), shop.flows(), "the package lists both flows");
        Doors.Door door = second.doors.door("shop/b");
        assertTrue(door.history().contains("start failed"), "fixture: the restore called b's start, which failed: " + door.history());
        assertEquals(List.of(), second.live("shop/b"), "nothing of b runs: " + second.doors.events());
        assertTrue(second.deployments.replay("shop/b").isEmpty(), "a FAILED flow has no replay");
        assertInstanceOf(Ack.Reply.class, second.send("shop/a"), "a serves");
        assertInstanceOf(Ack.Reply.class, second.send("store/c"), "c serves");
    }

    // --- a package refused whole is FAILED, the others run

    /**
     * The object of shop's archive is gone from the archive store: shop is listed FAILED — version, revision, archive and deployedAt
     * from its row, its problems naming the archive, no flows, no files — with an ERROR log naming it; nothing of it was built or
     * started; store runs.
     */
    @Test
    void aMissingArchiveIsAFailedPackage() throws Exception {
        Peer first = first();
        byte[] shop = pkg("shop", "a", "b").zip();
        first.deploy("shop", shop, Optional.of("r1"));
        first.deploy("store", pkg("store", "c").zip(), Optional.empty());
        first.close();
        DeploymentRecord row = records.rows().getFirst();
        assertEquals("shop", row.name(), "fixture: shop's row");
        Files.delete(object(sha(shop)));

        Fetches fetches = fetches();
        Peer second = new Peer("node-2", "dev", records, fetches);
        second.restore();

        DeployedPackage failed = second.pkg("shop");
        assertFailed(failed, "shop, whose archive is gone");
        assertEquals(row.version(), failed.version(), "the row's version: " + failed);
        assertEquals(Optional.of("r1"), failed.revision(), "the row's revision: " + failed);
        assertEquals(row.archiveSha256(), failed.archiveSha256(), "the row's archive: " + failed);
        assertEquals(row.deployedAt(), failed.deployedAt(), "the row's deployedAt: " + failed);
        assertProblemNames(failed, sha(shop), "the missing archive");
        assertEquals(1, fetches.fetchesOf(sha(shop)), "fixture: the restore asked the store for shop's archive");
        assertFalse(errorsNaming("shop").isEmpty(), "an ERROR log names shop: " + logged.stream().map(LogRecord::getMessage).toList());
        assertEquals(List.of("store/c"), second.deployments.flows().stream().map(DeployedFlow::id).toList(), "only store's flow is listed");
        assertEquals(List.of(), second.startedOf("shop"), "nothing of shop started: " + second.doors.events());
        assertTrue(second.deployments.replay("shop/a").isEmpty(), "a FAILED package's flows have no replay");
        assertRunning(second.pkg("store"), "store runs");
        assertInstanceOf(Ack.Reply.class, second.send("store/c"), "c serves");
    }

    /**
     * The object stored under shop's SHA-256 holds other bytes — another version of shop, which would deploy and run: the restore
     * compares the fetched archive's SHA-256 with the row's, lists shop FAILED with a problem naming the expected SHA-256 and an
     * ERROR log, and runs nothing of what it fetched; store runs.
     */
    @Test
    void anArchiveWhoseSha256DiffersIsAFailedPackage() throws Exception {
        Peer first = first();
        byte[] shop = pkg("shop", "a", "b").zip();
        first.deploy("shop", shop, Optional.of("r1"));
        first.deploy("store", pkg("store", "c").zip(), Optional.empty());
        first.close();
        PackageDraft v2 = pkg("shop", "a", "b");
        v2.manifest().put("version", "2.0.0");
        v2.flowDoc("a").put("description", "not what the row recorded");
        byte[] substitute = v2.zip();
        assertFalse(sha(substitute).equals(sha(shop)), "fixture: other bytes");
        Files.write(object(sha(shop)), substitute);

        Fetches fetches = fetches();
        Peer second = new Peer("node-2", "dev", records, fetches);
        second.restore();

        DeployedPackage failed = second.pkg("shop");
        assertFailed(failed, "shop, whose stored bytes are not its archive");
        assertEquals("1.0.0", failed.version(), "the row's version, not the substitute's: " + failed);
        assertEquals(sha(shop), failed.archiveSha256(), "the row's archive: " + failed);
        assertProblemNames(failed, sha(shop), "the SHA-256 that differs");
        assertEquals(1, fetches.fetchesOf(sha(shop)), "fixture: the archive was fetched");
        assertFalse(errorsNaming("shop").isEmpty(), "an ERROR log names shop: " + logged.stream().map(LogRecord::getMessage).toList());
        assertEquals(List.of(), second.startedOf("shop"), "nothing of the substitute started: " + second.doors.events());
        assertEquals(List.of("store/c"), second.deployments.flows().stream().map(DeployedFlow::id).toList(), "only store's flow is listed");
        assertInstanceOf(Ack.Reply.class, second.send("store/c"), "store runs");
    }

    /**
     * Two packages that cannot be installed on node 2: shop has a problem in node 2's environment ({@code ci} lays
     * {@code level: extreme} over b's in endpoint, which the component's configSchema refuses; node 1 ran in dev), and junk's
     * archive is not a zip (its unpack is refused). Each is listed FAILED with its problems and an ERROR log — shop whole: its
     * flow a, fine on its own, does not start either — and store runs.
     */
    @Test
    void aPackageWithProblemsIsFailedTheOthersRun() throws Exception {
        PackageDraft shop = pkg("shop", "a", "b");
        shop.endpointDoc("b-in").put("environments", new JsonObject().put("ci", new JsonObject().put("config", new JsonObject().put("level", "extreme"))));
        Peer first = first();
        first.deploy("shop", shop.zip(), Optional.of("r1"));
        first.deploy("store", pkg("store", "c").zip(), Optional.empty());
        first.close();
        String junk = stored("junk", "not a zip at all".getBytes(StandardCharsets.UTF_8));

        Fetches fetches = fetches();
        Peer second = new Peer("node-2", "ci", records, fetches);
        second.restore();

        DeployedPackage refused = second.pkg("shop");
        assertFailed(refused, "shop, with a problem in ci");
        assertProblemNames(refused, "b-in", "the endpoint whose config ci refuses");
        assertFalse(errorsNaming("shop").isEmpty(), "an ERROR log names shop");
        assertEquals(List.of(), second.startedOf("shop"), "a package refused whole starts nothing, not even a: " + second.doors.events());
        DeployedPackage unpacked = second.pkg("junk");
        assertFailed(unpacked, "junk, whose archive is not a zip");
        assertEquals(1, fetches.fetchesOf(junk), "fixture: junk was fetched");
        assertFalse(errorsNaming("junk").isEmpty(), "an ERROR log names junk");
        assertRunning(second.pkg("store"), "store runs");
        assertEquals(List.of("store/c"), second.deployments.flows().stream().map(DeployedFlow::id).toList(), "only store's flow is listed");
        assertInstanceOf(Ack.Reply.class, second.send("store/c"), "c serves");
    }

    /**
     * Each row's archive is fetched into a temporary file in {@code .incoming}, deleted whatever happened next: after a restore of a
     * good package, a missing object, a fetch that wrote half an archive and failed, an archive that is not a zip and one whose
     * SHA-256 differs, every fetch target was in {@code .incoming} and none is left, and {@code .incoming} holds nothing.
     */
    @Test
    void restoreLeavesIncomingEmpty() throws Exception {
        Peer first = first();
        byte[] shop = pkg("shop", "a").zip();
        byte[] store = pkg("store", "c").zip();
        byte[] relay = pkg("relay", "r").zip();
        byte[] depot = pkg("depot", "d").zip();
        for (Map.Entry<String, byte[]> p : Map.of("shop", shop, "store", store, "relay", relay, "depot", depot).entrySet()) {
            first.deploy(p.getKey(), p.getValue(), Optional.empty());
        }
        first.close();
        String junk = stored("junk", "not a zip at all".getBytes(StandardCharsets.UTF_8));
        Files.delete(object(sha(store)));
        Files.write(object(sha(depot)), pkg("depot", "e").zip());

        Fetches fetches = fetches();
        fetches.failAfterWriting(sha(relay), "connection reset by peer");
        Peer second = new Peer("node-2", "dev", records, fetches);
        second.restore();

        Path incoming = second.deployments.incomingDir();
        assertEquals(List.of("depot", "junk", "relay", "shop", "store"), second.deployments.packages().stream().map(DeployedPackage::name).toList(),
                "fixture: every row was restored or refused");
        assertRunning(second.pkg("shop"), "fixture: shop runs");
        for (String failed : List.of("store", "relay", "junk", "depot")) assertEquals("FAILED", state(second.pkg(failed)), "fixture: " + failed + " is refused");
        assertEquals(java.util.Set.of(sha(shop), sha(store), sha(relay), sha(depot), junk), java.util.Set.copyOf(fetches.keys()), "fixture: every row was fetched");
        for (Path target : fetches.targets()) {
            assertTrue(target.toAbsolutePath().normalize().startsWith(incoming.toAbsolutePath().normalize()), "a fetch lands in .incoming: " + target);
            assertFalse(Files.exists(target), "the fetched file is deleted: " + target);
        }
        assertEquals(List.of(), children(incoming), "nothing is left in .incoming");
    }

    /**
     * {@code deployedAt} and {@code revision} are the row's: node 2 restores five hours after node 1's deploys, and lists each
     * package with the instant its row holds (the deploy's, to the microsecond) — not node 2's clock — and the row's revision, r7 or
     * none, on the package and on its flows.
     */
    @Test
    void restoreListsRevisionAndDeployedAtFromTheRow() throws Exception {
        Peer first = first();
        first.deploy("shop", pkg("shop", "a").zip(), Optional.of("r7"));
        rt.clock().advance(Duration.ofMinutes(1));
        first.deploy("store", pkg("store", "c").zip(), Optional.empty());
        first.close();
        Map<String, DeploymentRecord> rows = new HashMap<>();
        records.rows().forEach(r -> rows.put(r.name(), r));
        assertEquals(Instant.parse("2026-10-27T09:30:00.123456Z"), rows.get("shop").deployedAt(), "fixture: shop's row, to the microsecond");
        assertEquals(Instant.parse("2026-10-27T09:31:00.123456Z"), rows.get("store").deployedAt(), "fixture: store's row");
        rt.clock().advance(Duration.ofHours(5));

        Peer second = new Peer("node-2", "dev", records, fetches());
        second.restore();

        DeployedPackage shop = second.pkg("shop");
        DeployedPackage store = second.pkg("store");
        assertEquals(rows.get("shop").deployedAt(), shop.deployedAt(), "shop's deployedAt is its row's, not node 2's clock (" + rt.clock().instant() + ")");
        assertEquals(rows.get("store").deployedAt(), store.deployedAt(), "store's deployedAt is its row's");
        assertEquals(Optional.of("r7"), shop.revision(), "shop's revision is its row's");
        assertEquals(Optional.empty(), store.revision(), "store's row has no revision");
        Map<String, DeployedFlow> flows = byId(second.deployments.flows());
        assertEquals(Optional.of("r7"), flows.get("shop/a").revision(), "a flow carries its row's revision");
        assertEquals(Optional.empty(), flows.get("store/c").revision());
    }

    /**
     * Rows that cannot be read make the restore throw — the node does not start — with nothing fetched, started or listed, and
     * so does an IOException of the node's own data directory ({@code .incoming} cannot be made: the node's failure, not a
     * package's). Nothing about one package throws: with the archive store down every fetch fails, every package is listed FAILED
     * naming the cause, the restore returns, and no fetch is retried — once per row, also after the node's clock moved on an hour.
     */
    @Test
    void restoreThrowsOnlyWhenRowsCannotBeRead() throws Exception {
        Peer first = first();
        byte[] shop = pkg("shop", "a").zip();
        byte[] store = pkg("store", "c").zip();
        first.deploy("shop", shop, Optional.empty());
        first.deploy("store", store, Optional.empty());
        first.close();

        Fetches down = fetches();
        down.down("connect to s3.example:443 refused");
        Peer second = new Peer("node-2", "dev", records, down);
        second.restore();   // returns: S3 down is no reason to fail the node's start
        for (String name : List.of("shop", "store")) {
            DeployedPackage p = second.pkg(name);
            assertFailed(p, name + " with the archive store down");
            assertProblemNames(p, "connect to s3.example:443 refused", name + ": the fetch's failure");
            assertFalse(errorsNaming(name).isEmpty(), "an ERROR log names " + name);
        }
        assertEquals(List.of(), second.deployments.flows(), "no flow runs");
        assertEquals(List.of(sha(shop), sha(store)), down.keys(), "one fetch per row");
        rt.clock().advance(Duration.ofHours(1));   // a retry on the node's timer would be due now
        assertEquals(List.of(sha(shop), sha(store)), down.keys(), "no retry: recovery is a restart or a PUT");

        Fetches unasked = fetches();
        Peer third = new Peer("node-3", "dev", new Unreadable("relation \"deployments\" does not exist"), unasked);
        Throwable unreadable = assertThrows(Throwable.class, third::restore, "rows that cannot be read fail the restore");
        assertTrue(anyCause(unreadable, c -> String.valueOf(c.getMessage()).contains("relation \"deployments\" does not exist")),
                "the failure carries the store's cause: " + unreadable);
        assertEquals(List.of(), third.deployments.packages(), "nothing is listed");
        assertEquals(List.of(), unasked.keys(), "nothing is fetched");
        assertEquals(List.of(), third.doors.doors(), "nothing is built");

        Fetches fourthFetches = fetches();
        Peer fourth = new Peer("node-4", "dev", records, fourthFetches);
        Files.createDirectories(fourth.data);
        Files.writeString(fourth.deployments.incomingDir(), "a file where the node keeps its .incoming directory");
        Throwable dataDir = assertThrows(Throwable.class, fourth::restore, "an IOException of the data directory fails the restore");
        assertTrue(anyCause(dataDir, c -> c instanceof IOException), "the data directory's IOException: " + dataDir);
        assertEquals(List.of(), fourth.doors.doors().stream().filter(Doors.Door::started).toList(), "nothing started on node 4");
    }

    // --- a PUT after a restore

    /**
     * Node 2 restored shop with b FAILED (its start failed). A PUT of the recorded archive is a redeploy over that package: b, FAILED,
     * counts as added and starts; a, unchanged, runs on untouched; the deploy writes its row.
     */
    @Test
    void aPutAfterAFailedRestoreRestartsTheFailedFlows() throws Exception {
        Peer first = first();
        byte[] shop = pkg("shop", "a", "b").zip();
        first.deploy("shop", shop, Optional.of("r1"));
        first.close();
        Peer second = new Peer("node-2", "dev", records, fetches());
        second.doors.failStartOf("shop/b", "address in use");
        second.restore();
        assertEquals(DeployedFlow.State.FAILED, byId(second.deployments.flows()).get("shop/b").state(), "fixture: b is FAILED after the restore");
        Doors.Door a = second.live("shop/a").getFirst();
        int saves = records.saves().size();

        DeployResult put = second.deploy("shop", shop, Optional.of("r2"));

        assertResult(List.of("shop/b"), List.of(), List.of(), put);
        Map<String, DeployedFlow> flows = byId(second.deployments.flows());
        assertEquals(DeployedFlow.State.RUNNING, flows.get("shop/b").state(), "b runs: " + flows);
        assertEquals(DeployedFlow.State.RUNNING, flows.get("shop/a").state(), "a runs: " + flows);
        assertFalse(a.stopped(), "a, unchanged, was not touched: " + a.history());
        assertEquals(List.of(a), second.live("shop/a"), "a's restored door serves on");
        assertInstanceOf(Ack.Reply.class, second.send("shop/b"), "b serves");
        assertRunning(second.pkg("shop"), "the package after the PUT");
        assertEquals(saves + 1, records.saves().size(), "the PUT writes the package's row");
        assertEquals(Optional.of("r2"), records.rows().getFirst().revision(), "the row is the PUT's");
    }

    /**
     * Node 2 restored shop FAILED (its archive was gone). A PUT of shop is a first deploy: one whose start fails is refused as a
     * first deploy is ("flow ... did not start", no rollback — a redeploy would answer "version X did not start; Y runs again"),
     * listing shop as before, FAILED; the next PUT adds both flows, lists shop RUNNING without problems, writes the row and stores
     * the archive again.
     */
    @Test
    void aPutOverAFailedPackageIsAFirstDeploy() throws Exception {
        Peer first = first();
        byte[] shop = pkg("shop", "a", "b").zip();
        first.deploy("shop", shop, Optional.of("r1"));
        first.close();
        Files.delete(object(sha(shop)));
        Peer second = new Peer("node-2", "dev", records, fetches());
        second.restore();
        assertEquals("FAILED", state(second.pkg("shop")), "fixture: shop is FAILED after the restore");
        List<DeployedPackage> failedListing = second.deployments.packages();

        second.doors.failStartOf("shop/a", "address in use");
        DeployRefusedException refused = second.refused("shop", shop, Optional.of("r2"));
        assertEquals(Kind.FAILED, refused.kind(), refused.getMessage());
        assertTrue(refused.error().contains("did not start") && !refused.error().contains("runs again"),
                "refused as a first deploy, not rolled back as a redeploy: " + refused.error());
        assertEquals(failedListing, second.deployments.packages(), "a refused deploy leaves the listing as it was: shop FAILED (D-063)");
        assertEquals(List.of(), second.live("shop/a"), "nothing of the refused deploy runs");
        assertEquals(List.of(), second.live("shop/b"), "nothing of the refused deploy runs");

        DeployResult put = second.deploy("shop", shop, Optional.of("r2"));

        assertResult(List.of("shop/a", "shop/b"), List.of(), List.of(), put);
        DeployedPackage now = second.pkg("shop");
        assertRunning(now, "shop after the PUT");
        assertEquals(List.of("shop/a", "shop/b"), now.flows());
        assertFalse(now.files().isEmpty(), "its files are listed again");
        assertEquals(List.of(new DeploymentRecord("shop", "1.0.0", Optional.of("r2"), sha(shop), put.pkg().deployedAt())), records.rows(), "the PUT's row");
        assertTrue(shelf.holds(sha(shop)), "the PUT stored the archive again");
        assertInstanceOf(Ack.Reply.class, second.send("shop/a"), "a serves");
        assertInstanceOf(Ack.Reply.class, second.send("shop/b"), "b serves");
    }
}
