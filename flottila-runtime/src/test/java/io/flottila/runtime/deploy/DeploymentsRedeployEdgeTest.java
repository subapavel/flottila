package io.flottila.runtime.deploy;

import io.flottila.api.Ack;
import io.flottila.api.Headers;
import io.flottila.api.Runtime;
import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.api.StepFactory;
import io.flottila.api.StepResources;
import io.flottila.api.Timer;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.runtime.deploy.DeployRefusedException.Kind;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static io.flottila.runtime.deploy.DeploymentsRedeployTest.changed;
import static io.flottila.runtime.deploy.DeploymentsRedeployTest.deepChain;
import static io.flottila.runtime.deploy.DeploymentsRedeployTest.dismantle;
import static io.flottila.runtime.deploy.DeploymentsRedeployTest.fileKey;
import static io.flottila.runtime.deploy.DeploymentsRedeployTest.removed;
import static io.flottila.runtime.deploy.DeploymentsRedeployTest.shop;
import static io.flottila.runtime.deploy.DeploymentsRedeployTest.to;
import static io.flottila.runtime.deploy.DeploymentsTest.children;
import static io.flottila.runtime.deploy.DeploymentsTest.regularFiles;
import static io.flottila.runtime.deploy.DeploymentsTest.until;
import static io.flottila.runtime.deploy.DeploymentsTest.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-09d2b, what {@link DeploymentsRedeployTest} leaves open (implementer): a directory the prune cannot delete is a WARNING and
 * the deploy stands; a first deploy prunes what an earlier node left for another archive; a refused redeploy that reused a kept
 * directory leaves it in place; a phase-A stop that fails is a WARNING and the swap goes on; a flow made non-at-least-once is
 * checked again once its old worker drained, like a removed one; a queue that cannot answer the check before the swap fails the
 * deploy (the node's failure, nothing stops), one that cannot answer the check after the drain is a WARNING; an at-least-once flow
 * whose hash cannot read a file its build read closes its own worker's producer at once (the consumer is built after the hash).
 */
class DeploymentsRedeployEdgeTest {

    @TempDir Path tmp;
    Path data;
    Path archives;

    final FakeRuntime rt = FakeRuntime.create();
    final Doors doors = new Doors();
    final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    /** Strongly held: JUL keeps loggers weakly, and a collected logger loses its handler. */
    final Logger flottila = Logger.getLogger("io.flottila");
    final Handler capture = new Handler() {
        @Override public void publish(LogRecord r) { logged.add(r); }
        @Override public void flush() {}
        @Override public void close() {}
    };
    CountingQueue counting;
    /** The hasItems call (1 = the first) from which on the queue answers with a failure; 0: never. */
    volatile int failHasItemsFrom;
    final AtomicInteger hasItemsCalls = new AtomicInteger();
    Deployments deployments;

    /** {@link #counting} at the store boundary, but hasItems fails from call {@link #failHasItemsFrom} on: a queue that cannot answer. */
    final class Faulty implements WorkQueue {
        @Override public Future<Void> enqueue(String messageId, String flow, int step, String partitionKey) { return counting.enqueue(messageId, flow, step, partitionKey); }
        @Override public Future<List<Item>> claim(String workerId, String flow, int max, Duration lease) { return counting.claim(workerId, flow, max, lease); }
        @Override public Future<Void> complete(long seq) { return counting.complete(seq); }
        @Override public Future<Optional<Item>> completeAndTakeNext(long seq, String workerId, Duration lease) { return counting.completeAndTakeNext(seq, workerId, lease); }
        @Override public Future<Boolean> renew(long seq, int attempts, Duration lease) { return counting.renew(seq, attempts, lease); }
        @Override public Future<Boolean> hold(long seq, int attempts) { return counting.hold(seq, attempts); }
        @Override public Future<io.flottila.core.record.Page<Held>> held(Optional<String> after, int limit) { return counting.held(after, limit); }
        @Override public Future<Boolean> release(long seq) { return counting.release(seq); }
        @Override public Future<Boolean> hasItems(String flow) {
            int n = hasItemsCalls.incrementAndGet();
            if (failHasItemsFrom > 0 && n >= failHasItemsFrom) return Future.failedFuture(new IllegalStateException("queue down at hasItems #" + n));
            return counting.hasItems(flow);
        }
    }

    @BeforeEach
    void wire() {
        data = tmp.resolve("data");
        archives = tmp.resolve("archives");
        counting = new CountingQueue(rt.clock());
        deployments = deployments(List.of());
        capture.setLevel(Level.ALL);
        flottila.addHandler(capture);
    }

    Deployments deployments(List<StepFactory> steps) {
        return deployments(steps, rt.timer());
    }

    Deployments deployments(List<StepFactory> steps, Timer timer) {
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "dev");
        env.put("FLOTTILA_NODE_ID", "node-1");
        env.put("FLOTTILA_DATA_DIR", data.toString());
        env.put("FLOTTILA_BLOB_DURABLE_MAX", "4096");
        Runtime runtime = new Runtime(rt.clock(), timer, rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
        return new Deployments(NodeConfig.from(env), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), timer, steps, List.of(),
                new InMemoryMessageStore(), new InMemoryDlq(), new Faulty(), () -> 0L);
    }

    @AfterEach
    void release() {
        doors.releaseAll();
        flottila.removeHandler(capture);
    }

    DeployResult deploy(PackageDraft p) throws Exception {
        Path archive = p.zipTo(archives);
        return OnVirtualThread.call(() -> deployments.deploy("shop", Optional.empty(), archive));
    }

    Path packageDir(String sha256) {
        return data.resolve("packages").resolve("shop").resolve(sha256);
    }

    List<LogRecord> warnings(String... words) {
        return logged.stream().filter(r -> r.getLevel() == Level.WARNING)
                .filter(r -> List.of(words).stream().allMatch(w -> DeploymentsProducersTest.texts(r).stream().anyMatch(t -> t.contains(w)))).toList();
    }

    Ack answer(String flow) throws Exception {
        return within(doors.door(flow).deliver(rt.ingress(flow, Headers.empty(), Buffer.buffer("<order/>"))), flow + "'s answer");
    }

    /** {@code shop} with flow {@code a} only (sync), its yaml carrying {@code text}: each text is another archive that changes a. */
    static PackageDraft one(String text) {
        return changed(DeploymentsTest.flows("shop", Map.of("a", "sync")), "a", text);
    }

    @Test
    void aDirectoryThePruneCannotDeleteIsAWarningAndTheDeployStands() throws Exception {
        String sha1 = deploy(one("v1")).pkg().archiveSha256();
        String sha2 = deploy(one("v2")).pkg().archiveSha256();
        Path dir1 = packageDir(sha1);
        assertTrue(Files.isDirectory(dir1), "fixture: v1's directory is kept as the previous one");
        Path work = Files.createDirectories(tmp.resolve("w"));
        Path chain = deepChain(work);
        Files.move(chain, dir1.resolve("deep"));   // no tree walk gets through it: v1's directory cannot be deleted
        try {
            DeployResult r3 = deploy(one("v3"));

            assertEquals(List.of("shop/a"), r3.swapped(), "the deploy stands: " + r3);
            assertEquals(List.of(r3.pkg()), deployments.packages());
            assertInstanceOf(Ack.Reply.class, answer("shop/a"), "v3 serves");
            assertTrue(Files.exists(dir1.resolve("deep")), "fixture: the prune could not delete v1's directory");
            assertEquals(1, warnings(sha1, "could not be deleted").size(), "one WARNING names the directory: "
                    + logged.stream().map(DeploymentsProducersTest::texts).toList());
            assertTrue(Files.isDirectory(packageDir(sha2)) && Files.isDirectory(packageDir(r3.pkg().archiveSha256())), "previous and current stay");
        } finally {
            Path deep = dir1.resolve("deep");
            if (Files.exists(deep, LinkOption.NOFOLLOW_LINKS)) dismantle(deep, work);
        }
    }

    @Test
    void aFirstDeployPrunesADirectoryAnEarlierNodeLeftForAnotherArchive() throws Exception {
        Path leftover = packageDir("0".repeat(64)).resolve("package.yaml");
        Files.createDirectories(leftover.getParent());
        Files.writeString(leftover, "left by an earlier node");

        String sha = deploy(one("v1")).pkg().archiveSha256();

        assertEquals(List.of(sha), children(data.resolve("packages").resolve("shop")), "the leftover is neither current, previous nor in use");
    }

    /** A redeploy that changes no flow (the package's version only): every flow runs on from v1's directory, v2's is kept all the same. */
    @Test
    void aRedeployThatChangesNoFlowKeepsItsOwnDirectory() throws Exception {
        String sha1 = deploy(shop()).pkg().archiveSha256();
        PackageDraft v2 = shop();
        v2.manifest().put("version", "1.0.1");

        DeployResult r = deploy(v2);

        String sha2 = r.pkg().archiveSha256();
        assertEquals(List.of(List.of(), List.of(), List.of()), List.of(r.added(), r.swapped(), r.removed()), "no flow changed: " + r);
        assertEquals("1.0.1", r.pkg().version(), "the package record is v2's");
        assertEquals(Stream.of(sha1, sha2).sorted().toList(), children(data.resolve("packages").resolve("shop")),
                "v2's directory (current, no flow runs from it) and v1's (previous, in use)");
        assertEquals(new ArrayList<>(v2.files().keySet()), regularFiles(packageDir(sha2)), "v2's directory is kept whole");
    }

    @Test
    void aRefusedRedeployThatReusedAKeptDirectoryLeavesItInPlace() throws Exception {
        PackageDraft v1 = shop();
        byte[] zip1 = v1.zip();
        String sha1 = OnVirtualThread.call(() -> deployments.deploy("shop", Optional.empty(), PackageDraft.write(archives, zip1))).pkg().archiveSha256();
        PackageDraft v2 = shop().endpoint("relay-in", "in", new JsonObject().put("path", "/relay")).flow("relay", "1.0.0", "at-least-once", "relay-in", 1024, to("archive"));
        String sha2 = deploy(v2).pkg().archiveSha256();
        Path dir1 = packageDir(sha1);
        Object key1 = fileKey(dir1);
        List<String> files1 = regularFiles(dir1);
        counting.enqueue("m-left", "shop/relay", 1, null).await();
        List<DeployedFlow> flowsBefore = deployments.flows();
        List<DeployedPackage> packagesBefore = deployments.packages();

        DeployRefusedException refused = assertThrows(DeployRefusedException.class,
                () -> OnVirtualThread.call(() -> deployments.deploy("shop", Optional.empty(), PackageDraft.write(archives, zip1))), "v1 again removes relay, which has an item");

        assertEquals(Kind.INVALID, refused.kind(), refused.error() + " " + refused.problems());
        assertTrue(refused.problems().stream().anyMatch(l -> l.contains("relay")), "a problem names relay: " + refused.problems());
        assertEquals(key1, fileKey(dir1), "v1's directory, kept as the previous one and reused by the refused deploy, is still the same directory");
        assertEquals(files1, regularFiles(dir1), "whole");
        assertTrue(Files.isDirectory(packageDir(sha2)), "the current directory stays");
        assertEquals(flowsBefore, deployments.flows());
        assertEquals(packagesBefore, deployments.packages());
        assertEquals(List.of(), children(deployments.incomingDir()));

        counting.complete(counting.rows().get(0).seq()).await();
        DeployResult again = OnVirtualThread.call(() -> deployments.deploy("shop", Optional.empty(), PackageDraft.write(archives, zip1)));
        assertEquals(List.of("shop/relay"), again.removed(), "witness: without the item v1 deploys again: " + again);
        assertEquals(key1, fileKey(dir1), "from the same directory");
    }

    /**
     * As above, but a file was written into v1's kept directory: the refused deploy of v1's archive unpacked it afresh over the
     * directory, and the directory stays (kept: v2 runs flows from it) with exactly v1's files.
     */
    @Test
    void aRefusedRedeployThatUnpackedAKeptDirectoryAfreshLeavesItInPlace() throws Exception {
        PackageDraft v1 = shop();
        byte[] zip1 = v1.zip();
        String sha1 = OnVirtualThread.call(() -> deployments.deploy("shop", Optional.empty(), PackageDraft.write(archives, zip1))).pkg().archiveSha256();
        PackageDraft v2 = shop().endpoint("relay-in", "in", new JsonObject().put("path", "/relay")).flow("relay", "1.0.0", "at-least-once", "relay-in", 1024, to("archive"));
        String sha2 = deploy(v2).pkg().archiveSha256();
        Path dir1 = packageDir(sha1);
        List<String> files1 = regularFiles(dir1);
        Files.writeString(dir1.resolve("written.txt"), "written after the deploy");
        counting.enqueue("m-left", "shop/relay", 1, null).await();
        List<DeployedFlow> flowsBefore = deployments.flows();

        DeployRefusedException refused = assertThrows(DeployRefusedException.class,
                () -> OnVirtualThread.call(() -> deployments.deploy("shop", Optional.empty(), PackageDraft.write(archives, zip1))), "v1 again removes relay, which has an item");

        assertEquals(Kind.INVALID, refused.kind(), refused.error() + " " + refused.problems());
        assertEquals(files1, regularFiles(dir1), "v1's directory stays, unpacked afresh: v1's files and nothing written after them");
        assertTrue(Files.isDirectory(packageDir(sha2)), "the current directory stays");
        assertEquals(flowsBefore, deployments.flows());
        assertEquals(List.of(), children(deployments.incomingDir()));
    }

    /**
     * v1's directory, after v3 neither current nor previous but in use ({@code b} runs from it), keeps its record: the deploy of
     * v1's archive reuses it as it was unpacked.
     */
    @Test
    void aDirectoryKeptOnlyInUseIsReusedByItsArchive() throws Exception {
        Map<String, String> modes = new LinkedHashMap<>();
        modes.put("a", "sync");
        modes.put("b", "sync");
        byte[] zip1 = DeploymentsTest.flows("shop", modes).zip();
        String sha1 = OnVirtualThread.call(() -> deployments.deploy("shop", Optional.empty(), PackageDraft.write(archives, zip1))).pkg().archiveSha256();
        deploy(changed(DeploymentsTest.flows("shop", modes), "a", "v2"));
        deploy(changed(DeploymentsTest.flows("shop", modes), "a", "v3"));
        Path dir1 = packageDir(sha1);
        Object key1 = fileKey(dir1);

        DeployResult again = OnVirtualThread.call(() -> deployments.deploy("shop", Optional.empty(), PackageDraft.write(archives, zip1)));

        assertEquals(List.of("shop/a"), again.swapped(), "a runs v1's yaml again, b runs on: " + again);
        assertEquals(key1, fileKey(dir1), "v1's directory, kept as in use only, is reused as it was unpacked");
    }

    /** A kept directory deleted from under the node holds none of its recorded files: the deploy of its archive unpacks it afresh. */
    @Test
    void aKeptDirectoryDeletedFromDiskIsUnpackedAfresh() throws Exception {
        PackageDraft v1 = shop();
        byte[] zip = v1.zip();
        String sha = OnVirtualThread.call(() -> deployments.deploy("shop", Optional.of("r1"), PackageDraft.write(archives, zip))).pkg().archiveSha256();
        List<String> files = regularFiles(packageDir(sha));
        List<DeployedFlow> flowsBefore = deployments.flows();
        try (Stream<Path> walk = Files.walk(packageDir(sha))) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }

        DeployResult again = OnVirtualThread.call(() -> deployments.deploy("shop", Optional.of("r2"), PackageDraft.write(archives, zip)));

        assertEquals(List.of(List.of(), List.of(), List.of()), List.of(again.added(), again.swapped(), again.removed()), "no flow changed: " + again);
        assertEquals(files, regularFiles(packageDir(sha)), "the directory is back, fresh from the archive");
        assertEquals(flowsBefore, deployments.flows(), "the flows run on with their stamps");
        assertEquals(List.of(), children(deployments.incomingDir()));
    }

    /**
     * A cleanup step that throws an {@link Error} — the stop of a worker phase B started, its poll tick's cancel throwing — after a
     * start refused the deploy: the refusal is what is thrown, the Error added to it as suppressed, and the steps after it run all
     * the same: the new directory is deleted, {@code packages/shop/} with it, and nothing is left in {@code .incoming}.
     */
    @Test
    void aCleanupStepThatThrowsAnErrorDoesNotSkipTheStepsAfterIt() throws Exception {
        AtomicInteger thrown = new AtomicInteger();
        Timer timer = (delay, task) -> {
            Timer.Cancellable scheduled = rt.timer().schedule(delay, task);
            if (!delay.equals(DeploymentsRedeployTest.TICK)) return scheduled;
            return () -> {
                thrown.incrementAndGet();
                throw new AssertionError("the poll tick cannot be cancelled");
            };
        };
        Deployments d = deployments(List.of(), timer);
        doors.failStart(2, "orders does not start");
        Path archive = shop().zipTo(archives);

        DeployRefusedException refused = assertThrows(DeployRefusedException.class, () -> OnVirtualThread.call(() -> d.deploy("shop", Optional.empty(), archive)));

        assertEquals(Kind.FAILED, refused.kind(), refused.error());
        assertEquals(1, thrown.get(), "fixture: audit's worker started and its stop threw, in the cleanup");
        assertTrue(Arrays.stream(refused.getSuppressed()).anyMatch(e -> e instanceof AssertionError && e.getMessage().equals("the poll tick cannot be cancelled")),
                "the Error is added to the refusal: " + Arrays.toString(refused.getSuppressed()));
        assertFalse(Files.exists(data.resolve("packages").resolve("shop")), "the steps after it ran: the new directory is deleted, packages/shop/ with it");
        assertEquals(List.of(), children(d.incomingDir()));
    }

    @Test
    void aStopThatFailsInPhaseAIsAWarningAndTheSwapGoesOn() throws Exception {
        deploy(shop());
        Doors.Door v1 = doors.door("shop/orders");
        doors.failStop("shop/orders", "socket stuck");

        DeployResult r = deploy(changed(shop(), "orders", "v2"));

        assertEquals(List.of("shop/orders"), r.swapped(), r.toString());
        assertTrue(v1.stopped(), "v1's orders consumer was stopped (its stop failed)");
        assertEquals(1, warnings("shop/orders", "socket stuck").size(), "a WARNING names the flow and the failure: "
                + logged.stream().map(DeploymentsProducersTest::texts).toList());
        assertTrue(doors.door("shop/orders") != v1 && doors.door("shop/orders").started(), "v2's orders consumer started");
        assertInstanceOf(Ack.Reply.class, answer("shop/orders"), "and serves");
    }

    @Test
    void aFlowMadeNonAtLeastOnceIsCheckedAgainOnceItsWorkerDrained() throws Exception {
        deploy(shop());
        counting.afterFirstHasItems("shop/audit", () -> counting.enqueue("m-late", "shop/audit", 1, null).await());   // arrives after the check
        PackageDraft v2 = shop();
        v2.flowDoc("audit").getJsonObject("delivery").put("mode", "sync");

        DeployResult r = deploy(v2);

        assertEquals(List.of("shop/audit"), r.swapped(), r.toString());
        until(() -> counting.hasItemsCalls().size() == 2, "audit is checked before the swap and again once its old worker drained: " + counting.hasItemsCalls());
        assertEquals(List.of("shop/audit rows=0", "shop/audit rows=1"), counting.hasItemsCalls());
        until(() -> !warnings("shop/audit", "still holds items").isEmpty(), "a WARNING names the flow whose item is left: "
                + logged.stream().map(DeploymentsProducersTest::texts).toList());
    }

    @Test
    void aQueueThatCannotAnswerTheCheckAfterTheDrainIsAWarning() throws Exception {
        deploy(shop());
        failHasItemsFrom = 2;   // the check before the swap answers, the one after the drain fails

        DeployResult r = deploy(removed(shop(), "audit").without("endpoints/archive.yaml"));

        assertEquals(List.of("shop/audit"), r.removed(), r.toString());
        until(() -> !warnings("shop/audit", "could not say", "queue down at hasItems #2").isEmpty(), "a WARNING names the flow and the queue's failure: "
                + logged.stream().map(DeploymentsProducersTest::texts).toList());
    }

    @Test
    void aQueueThatCannotAnswerTheCheckBeforeTheSwapFailsTheDeployAndNothingStops() throws Exception {
        deploy(shop());
        Doors.Door audit = doors.door("shop/audit");
        List<DeployedFlow> flowsBefore = deployments.flows();
        List<DeployedPackage> packagesBefore = deployments.packages();
        failHasItemsFrom = 1;
        PackageDraft v2 = removed(shop(), "audit").without("endpoints/archive.yaml");
        Path archive = v2.zipTo(archives);
        int events = doors.events().size();

        Exception failed = assertThrows(Exception.class, () -> OnVirtualThread.call(() -> deployments.deploy("shop", Optional.empty(), archive)));

        assertFalse(failed instanceof DeployRefusedException, "the queue's failure is the node's, not a refusal of the package: " + failed);
        List<String> chain = new ArrayList<>();
        for (Throwable t = failed; t != null; t = t.getCause()) chain.add(String.valueOf(t.getMessage()));
        assertTrue(chain.stream().anyMatch(m -> m.contains("queue down at hasItems #1")), "it is the queue's failure: " + chain);
        assertEquals(List.of(), doors.events().subList(events, doors.events().size()), "nothing was built, stopped or started");
        assertFalse(audit.stopped());
        assertEquals(flowsBefore, deployments.flows());
        assertEquals(packagesBefore, deployments.packages());
        assertFalse(Files.exists(packageDir(PackageDraft.sha256(archive))), "the new directory is deleted");
        assertEquals(List.of(), children(deployments.incomingDir()));
    }

    /**
     * A step that reports a file of its flow's directory the hash cannot read: {@code unreadable}, a link it makes there to
     * {@code /proc/self/mem} (compared as a path, the link stays inside; read, it fails with EIO whoever runs the test).
     */
    record Unreadable() implements StepFactory {
        @Override public String type() { return "unreadable"; }
        @Override public String configSchema() { return "{\"type\":\"object\"}"; }
        @Override public Stage create(StepConfig step, StepResources resources) {
            Path link = resources.baseDir().resolve("unreadable");
            try {
                if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) Files.createSymbolicLink(link, Path.of("/proc/self/mem"));
            } catch (IOException e) {
                throw new UncheckedIOException("fixture: the link could not be made", e);
            }
            resources.file("unreadable");
            return (e, rest) -> rest.apply(e);
        }
    }

    @Test
    void anAtLeastOnceFlowWhoseHashCannotReadAFileClosesItsWorkersProducer() throws Exception {
        Deployments d = deployments(List.of(new Unreadable()));
        PackageDraft p = shop();
        p.flowDoc("audit").put("steps", new JsonArray().add(new JsonObject().put("type", "unreadable").put("name", "u")).addAll(to("archive")));
        Path archive = p.zipTo(archives);

        IOException failed = assertThrows(IOException.class, () -> OnVirtualThread.call(() -> d.deploy("shop", Optional.empty(), archive)),
                "a file the hash cannot read is the node's failure");

        assertTrue(doors.producers().stream().anyMatch(o -> o.endpoint().equals("archive")), "fixture: audit's worker was built: " + doors.producers() + " " + failed);
        assertEquals(List.of(), doors.openProducers(), "audit's worker producer is closed, and every other the build opened: " + doors.producers());
        assertEquals(List.of(), doors.producersClosedTwice(), "each once: " + doors.producers());
        assertEquals(List.of(), doors.events(), "no consumer was built: the hash comes first");
        assertEquals(List.of(), d.flows());
        assertFalse(Files.exists(data.resolve("packages").resolve("shop")), "the refused first deploy leaves nothing on disk");
        long polls = counting.claims().size();
        rt.clock().advance(DeploymentsRedeployTest.TICK.multipliedBy(3));
        assertEquals(polls, counting.claims().size(), "audit's worker never polls");
    }
}
