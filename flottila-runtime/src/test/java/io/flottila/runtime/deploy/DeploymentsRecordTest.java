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
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static io.flottila.runtime.deploy.DeploymentsRedeployTest.byId;
import static io.flottila.runtime.deploy.DeploymentsRedeployTest.changed;
import static io.flottila.runtime.deploy.DeploymentsRedeployTest.reachedWhile;
import static io.flottila.runtime.deploy.DeploymentsRedeployTest.syncFlows;
import static io.flottila.runtime.deploy.DeploymentsRedeployTest.untilWhile;
import static io.flottila.runtime.deploy.DeploymentsTest.children;
import static io.flottila.runtime.deploy.DeploymentsTest.joinRefused;
import static io.flottila.runtime.deploy.DeploymentsTest.until;
import static io.flottila.runtime.deploy.DeploymentsTest.within;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P1-09c3a (D-070): the deploy's write path. Unpack, check, build without problems, then {@code archives.put} — before anything
 * stops; a failed put is {@link Kind#FAILED} (500) "archive sha256:&lt;hex&gt; not stored: &lt;cause&gt;", cleaned up, and the old
 * version runs — then phase A, phase B, then {@code records.save(..).await()}, the commit point: the listing changes and the
 * package's directories are pruned only once the record is written. A failed save refuses a first deploy (500, cleaned up) and
 * rolls a redeploy back with the problem "the deploy record was not written: &lt;cause&gt;". A deploy refused after its put writes
 * no row and keeps its object: nothing deletes archives in Phase 1.
 *
 * <p>Fixtures as in {@link DeploymentsTest}: {@link Doors} at the SPI boundary, in-memory message store, DLQ and work queue, a
 * fake clock — standing at {@code .123456789}, so a record's microseconds show — and, new here, the two stores at the SPI
 * boundary of {@code Deployments}: {@link Ledger} (records) and {@link Shelf} (archives over a {@link DirArchiveStore} at
 * {@code <dataDir>/archives}), both tracing into the one log the Doors events go to.
 *
 * <p>The shape these cases assume (P1-09c3a test-author): {@code Deployments(NodeConfig, Runtime, Map<String, Component>, Clock,
 * Timer, List<StepFactory>, List<Expressions>, MessageStore, Dlq, WorkQueue, Meter, DeploymentStore, ArchiveStore)}, and the record
 * written is {@code DeploymentRecord(name, version, revision, archiveSha256, deployedAt)} with the {@code deployedAt} of the
 * {@link DeployedPackage} the deploy answers (truncated to the microsecond by the record).
 */
class DeploymentsRecordTest {

    static final String NODE = "node-1";
    static final Duration STOP_DEADLINE = Duration.ofSeconds(37);
    static final Instant NOW = Instant.parse("2026-10-27T09:30:00.123456789Z");

    @TempDir Path tmp;
    Path data;
    Path uploads;

    final FakeRuntime rt = FakeRuntime.create().withClockAt(NOW);
    final Doors doors = new Doors();
    final InMemoryMessageStore messages = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();
    /** The Doors events and the two stores' traces, in the order they were made. */
    final List<String> log = new CopyOnWriteArrayList<>();
    Ledger records;
    Shelf shelf;
    Deployments deployments;

    @BeforeEach
    void wire() {
        data = tmp.resolve("data");
        uploads = tmp.resolve("uploads");
        doors.trace(log::add);
        records = new Ledger(log::add);
        shelf = new Shelf(data.resolve("archives"), tmp.resolve("shelf-reads"), log::add);
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "dev");
        env.put("FLOTTILA_NODE_ID", NODE);
        env.put("FLOTTILA_DATA_DIR", data.toString());
        env.put("FLOTTILA_STOP_DEADLINE", STOP_DEADLINE.toString());
        env.put("FLOTTILA_MAX_PACKAGE_BYTES", Long.toString(2L * 1024 * 1024));
        TracingTimer timer = new TracingTimer(rt.timer());
        Runtime runtime = new Runtime(rt.clock(), timer, rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
        deployments = new Deployments(NodeConfig.from(env), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), timer, List.of(), List.of(), messages, dlq,
                new CountingQueue(rt.clock()), () -> 0L, records, shelf);
    }

    @AfterEach
    void releaseWhatACaseHeld() {
        doors.releaseAll();
        records.releaseAll();
    }

    // --- fixture

    /** {@code shop} {@code version}: sync flows {@code a} and {@code b} to {@code partner}; {@code a}'s yaml carries {@code aText}. */
    static PackageDraft shop(String version, String aText) {
        PackageDraft p = changed(syncFlows("a", "b"), "a", aText);
        p.manifest().put("version", version);
        return p;
    }

    static String sha256(byte[] zip) {
        return PackageDraft.sha256(zip);
    }

    OnVirtualThread.Call<DeployResult> deploying(byte[] zip, String revision) {
        Path archive = PackageDraft.write(uploads, zip);
        return OnVirtualThread.start(() -> deployments.deploy("shop", Optional.of(revision), archive));
    }

    DeployResult deploy(byte[] zip, String revision) throws Exception {
        return OnVirtualThread.join(deploying(zip, revision));
    }

    DeployRefusedException refused(byte[] zip, String revision) throws Exception {
        return joinRefused(deploying(zip, revision), "the deploy of shop " + revision + " is refused");
    }

    Path shopDir() {
        return data.resolve("packages").resolve("shop");
    }

    int mark() {
        return log.size();
    }

    List<String> since(int mark) {
        List<String> all = List.copyOf(log);
        return all.subList(mark, all.size());
    }

    static int at(List<String> events, String event) {
        int i = events.indexOf(event);
        if (i < 0) fail("no '" + event + "' in the log: " + events);
        return i;
    }

    static void assertBefore(List<String> events, String first, String then, String what) {
        assertTrue(at(events, first) < at(events, then), what + ": '" + first + "' before '" + then + "' in " + events);
    }

    /** Spins until the deploy's virtual thread is parked (it waits for something it cannot do itself); a deploy that ends first fails the case. */
    static void parked(OnVirtualThread.Call<?> d, String what) {
        untilWhile(d, () -> d.thread().getState() == Thread.State.WAITING || d.thread().getState() == Thread.State.TIMED_WAITING, what);
    }

    Ack send(Doors.Door door) throws Exception {
        return within(door.deliver(rt.ingress(door.flow(), Headers.empty(), Buffer.buffer("<order/>"))), "the answer of " + door);
    }

    /** The doors of {@code flow} that started and were not stopped since. */
    List<Doors.Door> live(String flow) {
        return doors.doors().stream().filter(d -> d.flow().equals(flow) && d.started() && !d.stopped()).toList();
    }

    static void assertProblem(DeployRefusedException refused, String... words) {
        assertTrue(refused.problems().stream().anyMatch(l -> Arrays.stream(words).allMatch(l::contains)),
                "a problem line names " + List.of(words) + ": " + refused.problems() + " (error: " + refused.error() + ")");
    }

    // --- the archive is put after the build, before anything stops

    @Test
    void theArchiveIsStoredBeforeAnythingStops() throws Exception {
        byte[] zip1 = shop("1.0.0", "v1").zip();
        String sha1 = sha256(zip1);
        int first = mark();
        DeployResult v1 = deploy(zip1, "r1");
        List<String> firstDeploy = since(first);
        assertEquals(sha1, v1.pkg().archiveSha256(), "fixture: the archive's sha256");
        assertEquals(List.of(sha1), shelf.puts(), "a first deploy puts its archive once, under its sha256");
        assertArrayEquals(zip1, shelf.bytes(sha1), "the object is the archive uploaded, byte for byte");
        for (String flow : List.of("shop/a", "shop/b")) {
            assertBefore(firstDeploy, "consumer " + flow, "put " + sha1, "the put follows the build, consumers included");
            assertBefore(firstDeploy, "stored " + sha1, "start " + flow, "the archive is stored before any flow starts");
        }

        Doors.Door a1 = doors.door("shop/a");
        byte[] zip2 = shop("2.0.0", "v2").zip();
        String sha2 = sha256(zip2);
        int second = mark();
        deploy(zip2, "r2");
        List<String> redeploy = since(second);
        assertTrue(a1.stopped(), "fixture: phase A stopped the running a, which v2 changes: " + a1.history());
        assertEquals(List.of(sha1, sha2), shelf.puts(), "the redeploy puts its own archive");
        assertBefore(redeploy, "consumer shop/a", "put " + sha2, "the put follows the new build");
        assertBefore(redeploy, "stored " + sha2, "stop shop/a", "the archive is stored before phase A stops anything");
        assertEquals(List.of(), shelf.platformPuts(), "put blocks on the deploy's virtual thread, never on a platform thread");

        PackageDraft broken = shop("3.0.0", "v3");
        broken.endpointDoc("b-in").getJsonObject("config").put("refuse", "port taken");   // b's consumer cannot be built: a build problem
        byte[] zip3 = broken.zip();
        DeployRefusedException invalid = refused(zip3, "r3");
        assertEquals(Kind.INVALID, invalid.kind(), invalid.getMessage());
        assertEquals(List.of(sha1, sha2), shelf.puts(), "a package whose build has problems is never put");
        assertFalse(shelf.holds(sha256(zip3)), "nor stored");
        assertEquals(List.of("shop 1.0.0", "shop 2.0.0"), records.saves().stream().map(r -> r.name() + " " + r.version()).toList(), "nor recorded");
    }

    @Test
    void aFailedPutIs500AndTheOldVersionRuns() throws Exception {
        byte[] zip1 = shop("1.0.0", "v1").zip();
        String sha1 = sha256(zip1);
        shelf.failNextPut(new IOException("no space left on device"));
        DeployRefusedException first = refused(zip1, "r1");
        assertEquals(Kind.FAILED, first.kind(), first.getMessage());
        assertTrue(first.error().startsWith("archive sha256:" + sha1 + " not stored: ") && first.error().contains("no space left on device"),
                "the error names the archive and the cause: " + first.error());
        assertEquals(List.of(), deployments.packages(), "a first deploy whose put failed lists nothing");
        assertEquals(List.of(), deployments.flows());
        assertEquals(List.of(), children(shopDir()), "its directory is deleted");
        assertEquals(List.of(), children(deployments.incomingDir()), "nothing is left in .incoming");
        assertTrue(doors.doors().stream().noneMatch(Doors.Door::started), "nothing started: " + doors.events());
        until(() -> doors.openProducers().isEmpty(), "every producer the refused deploy built is closed: " + doors.producers());
        assertEquals(List.of(), records.saves(), "no row");

        deploy(zip1, "r1");   // the operator's retry: v1 runs and is recorded
        Doors.Door a1 = doors.door("shop/a");
        Doors.Door b1 = doors.door("shop/b");
        Map<String, DeployedFlow> flowsBefore = byId(deployments.flows());
        List<DeployedPackage> packagesBefore = deployments.packages();
        List<DeploymentRecord> rowsBefore = records.rows();
        List<Doors.Out> openBefore = doors.openProducers();
        int doorsBefore = doors.doors().size();
        byte[] zip2 = shop("2.0.0", "v2").zip();
        String sha2 = sha256(zip2);
        shelf.failNextPut(new IOException("bucket unreachable"));
        int m = mark();

        DeployRefusedException second = refused(zip2, "r2");

        assertEquals(Kind.FAILED, second.kind(), second.getMessage());
        assertTrue(second.error().startsWith("archive sha256:" + sha2 + " not stored: ") && second.error().contains("bucket unreachable"),
                "the error names the archive and the cause: " + second.error());
        assertEquals(List.of(), since(m).stream().filter(e -> e.startsWith("stop ") || e.startsWith("start ")).toList(),
                "nothing stopped and nothing started: " + since(m));
        assertFalse(a1.stopped() || b1.stopped(), "v1 runs on: " + a1.history() + " " + b1.history());
        assertInstanceOf(Ack.Reply.class, send(a1), "v1's a serves");
        assertEquals(flowsBefore, byId(deployments.flows()), "listed as before");
        assertEquals(packagesBefore, deployments.packages(), "the package listed is v1's");
        assertEquals(rowsBefore, records.rows(), "the record is v1's");
        assertEquals(1, records.saves().size(), "the refused redeploy wrote no row");
        assertEquals(List.of(sha1), children(shopDir()), "v2's directory is deleted, v1's stays");
        assertEquals(List.of(), children(deployments.incomingDir()), "nothing is left in .incoming");
        List<Doors.Door> v2Doors = doors.doors().subList(doorsBefore, doors.doors().size());
        assertTrue(v2Doors.stream().noneMatch(Doors.Door::started), "nothing of v2 started: " + v2Doors);
        until(() -> doors.openProducers().equals(openBefore), "every producer v2 built is closed, v1's stay open: " + doors.producers());
    }

    // --- the record is the commit point

    @Test
    void aDeployIsRecordedOnlyOnceItsFlowsStarted() throws Exception {
        byte[] zip1 = shop("1.0.0", "v1").zip();
        String sha1 = sha256(zip1);
        Doors.Gate bStart = doors.holdStartOf("shop/b");
        Ledger.Hold save1 = records.holdNextSave();
        OnVirtualThread.Call<DeployResult> d1 = deploying(zip1, "r1");
        reachedWhile(d1, bStart.reached(), "phase B calls b's start");
        parked(d1, "the deploy waits for b's start");
        assertEquals(List.of(), records.saves(), "no record while a flow's start is pending");
        rt.clock().advance(Duration.ofSeconds(1));   // the record carries the package's deployedAt, not the clock at the save
        log.add("released b's start");
        bStart.release();
        reachedWhile(d1, save1.reached(), "the deploy saves its record once every flow started");
        assertBefore(log, "released b's start", "save shop 1.0.0", "the save follows the last start's completion");
        parked(d1, "the deploy waits for its record");
        assertEquals(List.of(), deployments.packages(), "the listing changes only once the record is written");
        assertEquals(List.of(), deployments.flows());
        save1.release();
        DeployResult v1 = OnVirtualThread.join(d1);
        DeploymentRecord rec1 = new DeploymentRecord("shop", "1.0.0", Optional.of("r1"), sha1, v1.pkg().deployedAt());
        assertEquals(List.of(rec1), records.saves(), "one save: the package, its version, revision and archive sha256");
        assertEquals(Instant.parse("2026-10-27T09:30:00.123456Z"), records.saves().getFirst().deployedAt(),
                "deployedAt is the package's (the clock at the unpack, before the second the case advanced), to the microsecond");
        assertEquals(List.of(rec1), records.rows());
        assertEquals(List.of(v1.pkg()), deployments.packages());

        byte[] zip2 = shop("2.0.0", "v2").zip();
        String sha2 = sha256(zip2);
        deploy(zip2, "r2");   // b runs on from v1's directory: v1's is kept for it
        PackageDraft v3 = changed(shop("3.0.0", "v3"), "b", "v3");   // both change: v1's directory is pruned once v3 is recorded
        byte[] zip3 = v3.zip();
        String sha3 = sha256(zip3);
        List<DeployedPackage> packagesBefore = deployments.packages();
        List<DeploymentRecord> rowsBefore = records.rows();
        assertEquals(Set.of(sha1, sha2), Set.copyOf(children(shopDir())), "fixture: v1's and v2's directories are kept");
        Ledger.Hold save3 = records.holdNextSave();
        OnVirtualThread.Call<DeployResult> d3 = deploying(zip3, "r3");
        reachedWhile(d3, save3.reached(), "v3 saves its record");
        parked(d3, "the deploy waits for its record");
        assertTrue(live("shop/a").size() == 1 && live("shop/b").size() == 1, "v3's flows run while its record is written: " + doors.events());
        assertEquals(packagesBefore, deployments.packages(), "while the record is written, the listing is v2's");
        assertEquals(rowsBefore, records.rows(), "and the row v2's");
        assertTrue(Files.isDirectory(shopDir().resolve(sha1)), "nothing is pruned before the record is written: " + children(shopDir()));
        save3.release();
        DeployResult r3 = OnVirtualThread.join(d3);
        assertEquals(List.of(new DeploymentRecord("shop", "3.0.0", Optional.of("r3"), sha3, r3.pkg().deployedAt())), records.rows(), "v3's row replaced v2's");
        assertEquals(List.of(r3.pkg()), deployments.packages());
        assertEquals(Set.of(sha2, sha3), Set.copyOf(children(shopDir())), "pruned once recorded: v3's directory and the previous one stay");
    }

    @Test
    void aFailedRecordRefusesAFirstDeployAndRollsBackARedeploy() throws Exception {
        byte[] zip1 = shop("1.0.0", "v1").zip();
        String sha1 = sha256(zip1);
        records.failNextSave("connection reset by peer");
        DeployRefusedException first = refused(zip1, "r1");
        assertEquals(Kind.FAILED, first.kind(), first.getMessage());
        assertTrue(first.getMessage().contains("connection reset by peer"), "the refusal names the cause: " + first.getMessage());
        List<Doors.Door> built = doors.doors();
        assertEquals(Set.of("shop/a", "shop/b"), built.stream().map(Doors.Door::flow).collect(Collectors.toSet()), "fixture: one door per flow");
        for (Doors.Door door : built) assertTrue(door.started() && door.stopped(), door + " started, then the refused deploy stopped it: " + door.history());
        until(() -> doors.openProducers().isEmpty(), "every producer the refused deploy built is closed: " + doors.producers());
        assertEquals(List.of(), deployments.packages(), "nothing is listed");
        assertEquals(List.of(), deployments.flows());
        assertEquals(List.of(), children(shopDir()), "its directory is deleted");
        assertEquals(List.of(), children(deployments.incomingDir()), "nothing is left in .incoming");
        assertEquals(List.of(), records.rows(), "no row");
        assertArrayEquals(zip1, shelf.bytes(sha1), "the object stays (orphans are kept)");

        deploy(zip1, "r1");
        List<DeploymentRecord> rowsBefore = records.rows();
        assertEquals(1, rowsBefore.size(), "fixture: v1 is recorded");
        Map<String, DeployedFlow> flowsBefore = byId(deployments.flows());
        List<DeployedPackage> packagesBefore = deployments.packages();
        Doors.Door b1 = doors.door("shop/b");
        int doorsBefore = doors.doors().size();
        byte[] zip2 = shop("2.0.0", "v2").zip();
        String sha2 = sha256(zip2);
        records.failNextSave("connection reset by peer");

        DeployRefusedException second = refused(zip2, "r2");

        assertEquals(Kind.FAILED, second.kind(), second.getMessage());
        assertProblem(second, "the deploy record was not written: ", "connection reset by peer");
        List<Doors.Door> since = doors.doors().subList(doorsBefore, doors.doors().size());
        assertEquals(List.of("shop/a", "shop/a"), since.stream().map(Doors.Door::flow).toList(), "v2's a, then v1's a rebuilt: " + doors.events());
        assertTrue(since.get(0).started() && since.get(0).stopped(), "v2's a started, then the rollback stopped it: " + since.get(0).history());
        assertTrue(since.get(1).started() && !since.get(1).stopped(), "v1's a runs again: " + since.get(1).history());
        assertFalse(b1.stopped(), "b, unchanged, runs on");
        assertInstanceOf(Ack.Reply.class, send(since.get(1)), "a serves again");
        assertEquals(flowsBefore, byId(deployments.flows()), "every flow listed as before v2");
        assertEquals(packagesBefore, deployments.packages(), "the package listed is v1's");
        assertEquals(rowsBefore, records.rows(), "the row is v1's");
        assertEquals(List.of(sha1), children(shopDir()), "v2's directory is deleted, v1's stays");
        assertArrayEquals(zip2, shelf.bytes(sha2), "v2's object stays (orphans are kept)");
    }

    @Test
    void aRefusedDeployWritesNoRowAndKeepsItsObject() throws Exception {
        byte[] zip1 = shop("1.0.0", "v1").zip();
        String sha1 = sha256(zip1);
        doors.failStartOf("shop/a", "address in use");
        DeployRefusedException first = refused(zip1, "r1");
        assertEquals(Kind.FAILED, first.kind(), first.getMessage());
        assertEquals(List.of(sha1), shelf.puts(), "fixture: the archive was put before phase B");
        assertEquals(List.of(), records.saves(), "a first deploy whose flow did not start never saves a record");
        assertArrayEquals(zip1, shelf.bytes(sha1), "its object stays: nothing deletes archives");

        deploy(zip1, "r1");
        assertEquals(List.of(sha1, sha1), shelf.puts(), "the same archive deployed again is put again");
        List<DeploymentRecord> rowsBefore = records.rows();
        assertEquals(List.of("shop"), rowsBefore.stream().map(DeploymentRecord::name).toList(), "fixture: v1 is recorded");
        byte[] zip2 = shop("2.0.0", "v2").zip();
        String sha2 = sha256(zip2);
        doors.failStartOf("shop/a", "address in use");

        DeployRefusedException second = refused(zip2, "r2");

        assertEquals(Kind.FAILED, second.kind(), second.getMessage());
        assertEquals(1, records.saves().size(), "a redeploy rolled back saves no record: " + records.saves());
        assertEquals(rowsBefore, records.rows(), "the row is v1's");
        assertArrayEquals(zip2, shelf.bytes(sha2), "v2's object stays");
        assertArrayEquals(zip1, shelf.bytes(sha1), "and v1's");
    }
}
