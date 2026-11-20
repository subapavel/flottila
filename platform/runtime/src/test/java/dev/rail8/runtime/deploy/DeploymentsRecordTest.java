package dev.rail8.runtime.deploy;

import dev.rail8.api.Ack;
import dev.rail8.api.Headers;
import dev.rail8.api.Runtime;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.runtime.deploy.DeployRefusedException.Kind;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.testkit.FakeRuntime;
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

import static dev.rail8.runtime.deploy.DeploymentsRedeployTest.byId;
import static dev.rail8.runtime.deploy.DeploymentsRedeployTest.changed;
import static dev.rail8.runtime.deploy.DeploymentsRedeployTest.reachedWhile;
import static dev.rail8.runtime.deploy.DeploymentsRedeployTest.syncFlows;
import static dev.rail8.runtime.deploy.DeploymentsRedeployTest.untilWhile;
import static dev.rail8.runtime.deploy.DeploymentsTest.children;
import static dev.rail8.runtime.deploy.DeploymentsTest.joinRefused;
import static dev.rail8.runtime.deploy.DeploymentsTest.until;
import static dev.rail8.runtime.deploy.DeploymentsTest.within;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The deploy's write path. Fetch, write, check, build without problems, then phase A, phase B, then
 * {@code records.save(..).await()}, the commit point: the listing changes and the package's directories are pruned only once the
 * record is written. A failed save refuses a first deploy (500, cleaned up) and rolls a redeploy back with the problem "the deploy
 * record was not written: &lt;cause&gt;". A refused deploy writes no row. Every deploy writes one audit row, its outcome included.
 *
 * <p>Fixtures as in {@link DeploymentsTest}: {@link Doors} at the SPI boundary, in-memory message store, DLQ and work queue, a
 * fake clock — standing at {@code .123456789}, so a record's microseconds show — the package committed to a {@link GitRepos}
 * repository, and {@link Ledger} as the record store, tracing into the one log the Doors events go to.
 */
class DeploymentsRecordTest {

    static final String NODE = "node-1";
    static final Duration STOP_DEADLINE = Duration.ofSeconds(37);
    static final Instant NOW = Instant.parse("2026-10-27T09:30:00.123456789Z");

    @TempDir Path tmp;
    Path data;
    GitRepos repos;

    final FakeRuntime rt = FakeRuntime.create().withClockAt(NOW);
    final Doors doors = new Doors();
    final InMemoryMessageStore messages = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();
    /** The Doors events and the record store's trace, in the order they were made. */
    final List<String> log = new CopyOnWriteArrayList<>();
    Ledger records;
    Deployments deployments;

    @BeforeEach
    void wire() {
        data = tmp.resolve("data");
        repos = new GitRepos(tmp.resolve("repos"));
        doors.trace(log::add);
        records = new Ledger(log::add);
        Map<String, String> env = new HashMap<>();
        env.put("RAIL8_ENV", "dev");
        env.put("RAIL8_NODE_ID", NODE);
        env.put("RAIL8_DATA_DIR", data.toString());
        env.put("RAIL8_STOP_DEADLINE", STOP_DEADLINE.toString());
        env.put("RAIL8_MAX_PACKAGE_BYTES", Long.toString(2L * 1024 * 1024));
        TracingTimer timer = new TracingTimer(rt.timer());
        Runtime runtime = new Runtime(rt.clock(), timer, rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
        deployments = new Deployments(NodeConfig.from(repos.env(env)), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), timer, List.of(), List.of(), messages, dlq,
                new CountingQueue(rt.clock()), () -> 0L, records);
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

    /** The name of the directory {@code p} is written into: the hex of its content digest. */
    static String hex(PackageDraft p) {
        return p.content().substring("sha256:".length());
    }

    /** The deploy of {@code p}, committed to the repository {@code shop}, by the actor {@code ops}; started, not awaited. */
    OnVirtualThread.Call<DeployResult> deploying(PackageDraft p) {
        String commit = repos.commit(p);
        return OnVirtualThread.start(() -> deployments.deploy("shop", commit, "ops"));
    }

    DeployResult deploy(PackageDraft p) throws Exception {
        return OnVirtualThread.join(deploying(p));
    }

    DeployRefusedException refused(PackageDraft p) throws Exception {
        return joinRefused(deploying(p), "the deploy of shop " + p.version() + " is refused");
    }

    /** The audit rows written, each as {@code <actor> <subject> <detail>}. */
    List<String> audited() {
        return records.audited().stream().map(a -> a.actor() + " " + a.subject() + " " + a.detail().encode()).toList();
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

    // --- the audit row

    @Test
    void everyDeployWritesOneAuditRowWithItsOutcome() throws Exception {
        PackageDraft v1 = shop("1.0.0", "v1");
        deploy(v1);
        assertEquals(List.of("ops repository/shop {\"repository\":\"shop\",\"commit\":\"" + v1.commit()
                + "\",\"outcome\":\"deployed\",\"package\":\"shop\",\"version\":\"1.0.0\"}"), audited(), "the actor, the repository, the commit, the outcome");

        PackageDraft broken = shop("2.0.0", "v2");
        broken.endpointDoc("b-in").getJsonObject("config").put("refuse", "port taken");   // b's consumer cannot be built: a build problem
        DeployRefusedException invalid = refused(broken);
        assertEquals(Kind.INVALID, invalid.kind(), invalid.getMessage());
        assertEquals(List.of("shop 1.0.0"), records.saves().stream().map(r -> r.name() + " " + r.version()).toList(), "a refused deploy writes no row");
        assertEquals("ops repository/shop {\"repository\":\"shop\",\"commit\":\"" + broken.commit() + "\",\"outcome\":\"refused\",\"error\":\""
                + invalid.error() + "\"}", audited().get(1), "a refused deploy is audited with its error");

        String next = repos.commit(shop("3.0.0", "v3"));
        assertEquals("3.0.0", OnVirtualThread.call(() -> deployments.check("shop", next)).pkg().version(), "fixture: the check passes");
        assertEquals(List.of("shop 1.0.0"), records.saves().stream().map(r -> r.name() + " " + r.version()).toList(), "a check writes no record");
        assertEquals(2, audited().size(), "a check writes no audit row");
        assertThrows(DeployRefusedException.class, () -> OnVirtualThread.call(() -> deployments.deploy("shop", "no-such-branch", "ops")));
        assertEquals("ops repository/shop {\"repository\":\"shop\",\"commit\":\"no-such-branch\",\"outcome\":\"refused\",\"error\":\""
                + "shop at no-such-branch: not found\"}", audited().get(2), "a ref that was never read is audited as asked");
    }

    // --- the record is the commit point

    @Test
    void aDeployIsRecordedOnlyOnceItsFlowsStarted() throws Exception {
        PackageDraft p1 = shop("1.0.0", "v1");
        String sha1 = hex(p1);
        Doors.Gate bStart = doors.holdStartOf("shop/b");
        Ledger.Hold save1 = records.holdNextSave();
        OnVirtualThread.Call<DeployResult> d1 = deploying(p1);
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
        DeploymentRecord rec1 = new DeploymentRecord("shop", "1.0.0", "shop", p1.commit(), p1.content(), v1.pkg().deployedAt(), List.of(), List.of());
        assertEquals(List.of(rec1), records.saves(), "one save: the package, its version, repository, commit and content");
        assertEquals(Instant.parse("2026-10-27T09:30:00.123456Z"), records.saves().getFirst().deployedAt(),
                "deployedAt is the package's (the clock at the checkout, before the second the case advanced), to the microsecond");
        assertEquals(List.of(rec1), records.rows());
        assertEquals(List.of(v1.pkg()), deployments.packages());

        PackageDraft p2 = shop("2.0.0", "v2");
        String sha2 = hex(p2);
        deploy(p2);   // b runs on from v1's directory: v1's is kept for it
        PackageDraft v3 = changed(shop("3.0.0", "v3"), "b", "v3");   // both change: v1's directory is pruned once v3 is recorded
        String sha3 = hex(v3);
        List<DeployedPackage> packagesBefore = deployments.packages();
        List<DeploymentRecord> rowsBefore = records.rows();
        assertEquals(Set.of(sha1, sha2), Set.copyOf(children(shopDir())), "fixture: v1's and v2's directories are kept");
        Ledger.Hold save3 = records.holdNextSave();
        OnVirtualThread.Call<DeployResult> d3 = deploying(v3);
        reachedWhile(d3, save3.reached(), "v3 saves its record");
        parked(d3, "the deploy waits for its record");
        assertTrue(live("shop/a").size() == 1 && live("shop/b").size() == 1, "v3's flows run while its record is written: " + doors.events());
        assertEquals(packagesBefore, deployments.packages(), "while the record is written, the listing is v2's");
        assertEquals(rowsBefore, records.rows(), "and the row v2's");
        assertTrue(Files.isDirectory(shopDir().resolve(sha1)), "nothing is pruned before the record is written: " + children(shopDir()));
        save3.release();
        DeployResult r3 = OnVirtualThread.join(d3);
        assertEquals(List.of(new DeploymentRecord("shop", "3.0.0", "shop", v3.commit(), v3.content(), r3.pkg().deployedAt(), List.of(), List.of())), records.rows(),
                "v3's row replaced v2's");
        assertEquals(List.of(r3.pkg()), deployments.packages());
        assertEquals(Set.of(sha2, sha3), Set.copyOf(children(shopDir())), "pruned once recorded: v3's directory and the previous one stay");
    }

    @Test
    void aFailedRecordRefusesAFirstDeployAndRollsBackARedeploy() throws Exception {
        PackageDraft p1 = shop("1.0.0", "v1");
        String sha1 = hex(p1);
        records.failNextSave("connection reset by peer");
        DeployRefusedException first = refused(p1);
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

        deploy(p1);
        List<DeploymentRecord> rowsBefore = records.rows();
        assertEquals(1, rowsBefore.size(), "fixture: v1 is recorded");
        Map<String, DeployedFlow> flowsBefore = byId(deployments.flows());
        List<DeployedPackage> packagesBefore = deployments.packages();
        Doors.Door b1 = doors.door("shop/b");
        int doorsBefore = doors.doors().size();
        records.failNextSave("connection reset by peer");

        DeployRefusedException second = refused(shop("2.0.0", "v2"));

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
    }

    /**
     * An undeploy whose record delete does not answer within the stop deadline is the node's {@link IOException}: whether the
     * record was deleted is unknown, and nothing else changed — shop and its flows stay listed and no flow is stopped. The node's
     * next tick follows whatever the pool's records hold.
     */
    @Test
    void anUndeployWhoseDeleteDoesNotAnswerInTimeSaysItsOutcomeIsUnknown() throws Exception {
        deploy(shop("1.0.0", "v1"));
        List<DeployedPackage> packagesBefore = deployments.packages();
        List<DeployedFlow> flowsBefore = deployments.flows();
        Doors.Door a = doors.door("shop/a");
        Ledger.Hold held = records.holdNextDelete();
        OnVirtualThread.Call<UndeployResult> undeploying = OnVirtualThread.start(() -> deployments.undeploy("shop"));
        reachedWhile(undeploying, held.reached(), "the undeploy calls the delete of shop's record");

        rt.clock().advance(STOP_DEADLINE);
        IOException unknown = assertThrows(IOException.class, () -> OnVirtualThread.join(undeploying), "the delete did not answer in time");
        assertTrue(unknown.getMessage().startsWith("whether the deploy record of package 'shop' was deleted is unknown: "), unknown.getMessage());
        assertEquals(packagesBefore, deployments.packages(), "shop is listed as before");
        assertEquals(flowsBefore, deployments.flows(), "its flows too");
        assertFalse(a.stopped(), "no flow was stopped: " + a.history());
    }

    @Test
    void aRefusedDeployWritesNoRow() throws Exception {
        PackageDraft p1 = shop("1.0.0", "v1");
        doors.failStartOf("shop/a", "address in use");
        DeployRefusedException first = refused(p1);
        assertEquals(Kind.FAILED, first.kind(), first.getMessage());
        assertEquals(List.of(), records.saves(), "a first deploy whose flow did not start never saves a record");

        deploy(p1);
        List<DeploymentRecord> rowsBefore = records.rows();
        assertEquals(List.of("shop"), rowsBefore.stream().map(DeploymentRecord::name).toList(), "fixture: v1 is recorded");
        doors.failStartOf("shop/a", "address in use");

        DeployRefusedException second = refused(shop("2.0.0", "v2"));

        assertEquals(Kind.FAILED, second.kind(), second.getMessage());
        assertEquals(1, records.saves().size(), "a redeploy rolled back saves no record: " + records.saves());
        assertEquals(rowsBefore, records.rows(), "the row is v1's");
    }
}
