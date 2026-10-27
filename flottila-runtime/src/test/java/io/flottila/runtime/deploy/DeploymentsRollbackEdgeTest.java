package io.flottila.runtime.deploy;

import io.flottila.api.Ack;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Runtime;
import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.api.StepFactory;
import io.flottila.api.StepResources;
import io.flottila.api.Timer;
import io.flottila.core.dlq.InMemoryDlq;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static io.flottila.runtime.deploy.DeploymentsRedeployTest.STOP_DEADLINE;
import static io.flottila.runtime.deploy.DeploymentsRedeployTest.TICK;
import static io.flottila.runtime.deploy.DeploymentsRedeployTest.assertResult;
import static io.flottila.runtime.deploy.DeploymentsRedeployTest.byId;
import static io.flottila.runtime.deploy.DeploymentsRedeployTest.changed;
import static io.flottila.runtime.deploy.DeploymentsRedeployTest.consumerEvents;
import static io.flottila.runtime.deploy.DeploymentsRedeployTest.reachedWhile;
import static io.flottila.runtime.deploy.DeploymentsRedeployTest.shop;
import static io.flottila.runtime.deploy.DeploymentsRedeployTest.syncFlows;
import static io.flottila.runtime.deploy.DeploymentsRedeployTest.untilWhile;
import static io.flottila.runtime.deploy.DeploymentsRollbackTest.failed;
import static io.flottila.runtime.deploy.DeploymentsTest.joinRefused;
import static io.flottila.runtime.deploy.DeploymentsTest.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P1-09d2c, what {@link DeploymentsRollbackTest} leaves open (implementer): a FAILED flow the next version no longer has is
 * removed with nothing to stop; a flow phase A stopped whose kept directory no longer builds it is FAILED, its problem line naming
 * the file from the package root; a consumer stopped once its late start completed whose stop fails is a WARNING naming the flow;
 * a stop that throws while the rollback stops the failed version ends suppressed in the refusal, and the other flows are stopped
 * and run again all the same. Review round 1: an Error the component throws building a consumer in the rollback leaves that flow
 * FAILED (suppressed in the refusal), and the next deploy restarts it. Round 1b: the rollback's break-off is a problem line, and an
 * Error a step factory throws building a changed flow again in the rollback breaks it off the same way. Round 3: a delivery stop
 * that throws in phase A is a WARNING and the swap goes on.
 */
class DeploymentsRollbackEdgeTest {

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
    TracingTimer timer;
    /** While set, cancelling a worker's poll tick throws an Error: the worker's stop() throws it. */
    volatile boolean tickCancelsThrow;
    /**
     * Armed, the next schedule of a worker's poll tick throws an Error ({@link #TICK_UNSCHEDULABLE}) and disarms: that worker's
     * start() throws it, the next worker's start schedules its tick (d2c r3, FIX-44).
     */
    final AtomicBoolean tickSchedulesThrow = new AtomicBoolean();
    static final String TICK_UNSCHEDULABLE = "the poll tick cannot be scheduled";
    /** The Error the tick hook threw last, and how many it threw: the witness that a worker's start() threw. */
    final AtomicReference<AssertionError> tickError = new AtomicReference<>();
    final AtomicInteger tickThrows = new AtomicInteger();
    /**
     * Armed, the next schedule of a delivery's stop deadline (a {@code Delivery} task after the node's stop deadline: a delivery
     * schedules it only while a message is in flight) throws {@link #DEADLINE_UNSCHEDULABLE} and disarms: that delivery's
     * stop(deadline) throws it (d2c r3).
     */
    final AtomicBoolean deadlineSchedulesThrow = new AtomicBoolean();
    static final String DEADLINE_UNSCHEDULABLE = "the delivery's stop deadline cannot be scheduled";
    /** The exception the deadline hook threw, null before: the witness that a delivery's stop(deadline) threw. */
    final AtomicReference<IllegalStateException> deadlineError = new AtomicReference<>();
    /** The directory (as text) each build of a {@code probe} step was made from, in order. */
    final List<String> probeBuilds = new CopyOnWriteArrayList<>();
    /** While set, a {@code probe} step built from a directory whose path contains it throws {@link #PROBE_ERROR} as an AssertionError. */
    volatile String probeThrowsBuildingFrom;
    static final String PROBE_ERROR = "the probe step cannot be built again";
    Deployments deployments;

    @BeforeEach
    void wire() {
        data = tmp.resolve("data");
        archives = tmp.resolve("archives");
        timer = new TracingTimer(rt.timer());
        Timer ticksMayThrow = (delay, task) -> {
            if (delay.equals(TICK) && tickSchedulesThrow.compareAndSet(true, false)) {
                AssertionError unschedulable = new AssertionError(TICK_UNSCHEDULABLE);
                tickError.set(unschedulable);
                tickThrows.incrementAndGet();
                throw unschedulable;
            }
            if (delay.equals(STOP_DEADLINE) && TracingTimer.owner(task).equals("Delivery") && deadlineSchedulesThrow.compareAndSet(true, false)) {
                IllegalStateException unschedulable = new IllegalStateException(DEADLINE_UNSCHEDULABLE);
                deadlineError.set(unschedulable);
                throw unschedulable;
            }
            Timer.Cancellable scheduled = timer.schedule(delay, task);
            if (!delay.equals(TICK)) return scheduled;
            return () -> {
                if (tickCancelsThrow) throw new AssertionError("the poll tick cannot be cancelled");
                return scheduled.cancel();
            };
        };
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "dev");
        env.put("FLOTTILA_NODE_ID", "node-1");
        env.put("FLOTTILA_DATA_DIR", data.toString());
        env.put("FLOTTILA_BLOB_DURABLE_MAX", "4096");
        env.put("FLOTTILA_STOP_DEADLINE", STOP_DEADLINE.toString());
        Runtime runtime = new Runtime(rt.clock(), ticksMayThrow, rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
        deployments = new Deployments(NodeConfig.from(env), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), ticksMayThrow, List.of(new Probe()), List.of(),
                new InMemoryMessageStore(), new InMemoryDlq(), new CountingQueue(rt.clock()), () -> 0L);
        capture.setLevel(Level.ALL);
        flottila.addHandler(capture);
    }

    @AfterEach
    void release() {
        tickCancelsThrow = false;
        tickSchedulesThrow.set(false);
        deadlineSchedulesThrow.set(false);
        doors.releaseAll();
        flottila.removeHandler(capture);
    }

    Path packageDir(String sha256) {
        return data.resolve("packages").resolve("shop").resolve(sha256);
    }

    DeployResult deploy(PackageDraft p, String revision) throws Exception {
        Path archive = p.zipTo(archives);
        return OnVirtualThread.call(() -> deployments.deploy("shop", Optional.of(revision), archive));
    }

    OnVirtualThread.Call<DeployResult> deploying(PackageDraft p, String revision) {
        Path archive = p.zipTo(archives);
        return OnVirtualThread.start(() -> deployments.deploy("shop", Optional.of(revision), archive));
    }

    /** {@code zip} as the archive, byte for byte: deployed twice, it is the same archive (a zip entry carries the time it was made). */
    DeployResult deploy(byte[] zip, String revision) throws Exception {
        Path archive = PackageDraft.write(archives, zip);
        return OnVirtualThread.call(() -> deployments.deploy("shop", Optional.of(revision), archive));
    }

    /** Waits until {@code call} ended, however (at most {@link OnVirtualThread#BOUND}); what it ended with is read afterwards. */
    static void ended(OnVirtualThread.Call<?> call, String what) throws Exception {
        try {
            call.outcome().handle((result, thrown) -> null).get(OnVirtualThread.BOUND.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException stillRunning) {
            fail(what + ": still running after " + OnVirtualThread.BOUND + ", at " + Arrays.toString(call.thread().getStackTrace()));
        }
    }

    static PackageDraft version(PackageDraft p, String version) {
        p.manifest().put("version", version);
        return p;
    }

    /** {@code p} with a {@code probe} step ahead of flow {@code id}'s steps. */
    static PackageDraft probed(PackageDraft p, String id) {
        p.flowDoc(id).put("steps", new JsonArray().add(new JsonObject().put("type", "probe").put("name", "probe")).addAll(PackageDraft.toPartner()));
        return p;
    }

    /** Step {@code probe}: passes the message on; records the directory it is built from, and throws an Error where the case says. */
    final class Probe implements StepFactory {
        @Override public String type() { return "probe"; }
        @Override public String configSchema() { return "{\"type\":\"object\"}"; }
        @Override public Stage create(StepConfig step, StepResources resources) {
            String from = resources.baseDir().toString();
            probeBuilds.add(from);
            String throwsFrom = probeThrowsBuildingFrom;
            if (throwsFrom != null && from.contains(throwsFrom)) throw new AssertionError(PROBE_ERROR);
            return (e, rest) -> rest.apply(e);
        }
    }

    /**
     * v1 runs a and b; v2 changes b, whose start fails in v2 and again in the rollback: b is FAILED. v3 has no b: b is removed,
     * nothing stops or starts (a is unchanged, b has nothing running), only a is listed; close then stops every consumer once.
     */
    @Test
    void aFailedFlowTheNextVersionRemovesIsRemovedWithNothingToStop() throws Exception {
        deploy(syncFlows("a", "b"), "r1");
        doors.failStartOf("shop/b", "address in use");
        doors.failStartOf("shop/b", "still in use");
        DeployRefusedException refused = joinRefused(deploying(version(changed(syncFlows("a", "b"), "b", "v2"), "2.0.0"), "r2"), "v2, whose b does not start");
        assertEquals(DeployedFlow.State.FAILED, byId(deployments.flows()).get("shop/b").state(), "fixture: b is FAILED: " + refused.error());
        int mark = doors.events().size();

        DeployResult r = deploy(version(syncFlows("a"), "3.0.0"), "r3");

        assertResult(List.of(), List.of(), List.of("shop/b"), r);
        assertEquals(List.of(), consumerEvents(doors.events().subList(mark, doors.events().size())), "nothing is built, stopped or started");
        assertEquals(List.of("shop/a"), deployments.flows().stream().map(DeployedFlow::id).toList());
        assertEquals(List.of("shop/a"), r.pkg().flows());
        OnVirtualThread.call(() -> {
            deployments.close();
            return null;
        });
        for (Doors.Door d : doors.doors()) assertEquals(1, d.stops(), d + " is stopped once: " + d.history());
    }

    /**
     * v1 runs a; its yaml is deleted from v1's kept directory; v2 changes a, whose start fails. The rollback cannot build a again
     * from v1's directory (its hash reads the yaml): a is FAILED under its v1 record, a problem line names the file from the
     * package root, and no producer of any build of a is left open.
     */
    @Test
    void aRetiredFlowWhoseDirectoryNoLongerBuildsIsFAILED() throws Exception {
        String sha1 = deploy(syncFlows("a"), "r1").pkg().archiveSha256();
        DeployedFlow v1 = deployments.flows().getFirst();
        Files.delete(packageDir(sha1).resolve("flows/a.yaml"));
        doors.failStartOf("shop/a", "address in use");

        DeployRefusedException refused = joinRefused(deploying(version(changed(syncFlows("a"), "a", "v2"), "2.0.0"), "r2"), "v2, whose a does not start");

        assertEquals(Kind.FAILED, refused.kind(), refused.error() + " " + refused.problems());
        assertEquals("version 2.0.0 did not start; 1.0.0 runs again; flows [shop/a] FAILED", refused.error());
        assertTrue(refused.problems().stream().anyMatch(l -> l.startsWith("flow 'a' does not build again: ") && l.contains("flows/a.yaml") && !l.contains(data.toString())),
                "a line says a does not build again, naming the file from the package root: " + refused.problems());
        assertEquals(List.of(new DeployedFlow(v1.id(), v1.pkg(), v1.packageVersion(), v1.revision(), v1.flowVersion(), v1.delivery(), v1.hash(),
                DeployedFlow.State.FAILED)), deployments.flows(), "a is listed under its v1 record, FAILED");
        DeploymentsTest.until(() -> doors.openProducers().isEmpty(), "no producer of a is open: " + doors.producers());
    }

    /**
     * A first deploy whose only consumer start never completes is FAILED at the node's stop deadline; when that start then
     * succeeds, the consumer is stopped, and a stop that fails then is a WARNING naming the flow.
     */
    @Test
    void aLateStartWhoseStopFailsIsAWarning() throws Exception {
        Doors.Gate stuck = doors.holdStartOf("shop/b");
        OnVirtualThread.Call<DeployResult> d = deploying(syncFlows("b"), "r1");
        reachedWhile(d, stuck.reached(), "the deploy calls b's start");
        // the fake timer's own count: TracingTimer lists a task a moment before the fake timer holds it
        untilWhile(d, () -> rt.timer().pending() == 1, "b's start is bounded on the node's timer: " + timer.all());
        assertEquals(STOP_DEADLINE, rt.timer().nextDelay(), "by the node's stop deadline");
        rt.clock().advance(STOP_DEADLINE);
        assertEquals(Kind.FAILED, joinRefused(d, "b's start that never completes").kind());
        doors.failStop("shop/b", "socket stuck");

        stuck.release();

        DeploymentsTest.until(() -> logged.stream().anyMatch(r -> r.getLevel() == Level.WARNING && DeploymentsProducersTest.texts(r).stream()
                .anyMatch(t -> t.contains("shop/b") && t.contains("late start"))), "a WARNING names b, whose late stop failed: " + logged);
        assertTrue(stuck.door().last("stop") > stuck.door().first("started"), "b was stopped after its late start: " + stuck.door().history());
    }

    /**
     * v1 runs orders, audit (at-least-once) and ping; v2 changes orders and audit. v2's audit starts (its worker's tick scheduled),
     * then v2's orders does not start, and the rollback's stop of v2's audit throws an Error (its tick cannot be cancelled). The
     * refusal is FAILED with that Error suppressed; v2's orders is stopped all the same, and v1's orders and audit run again.
     */
    @Test
    void anErrorAStopThrowsInTheRollbackIsSuppressedInTheRefusal() throws Exception {
        deploy(shop(), "r1");
        Doors.Gate ordersStart = doors.holdStartOf("shop/orders");
        OnVirtualThread.Call<DeployResult> d = deploying(version(changed(changed(shop(), "audit", "v2"), "orders", "v2"), "2.0.0"), "r2");
        reachedWhile(d, ordersStart.reached(), "phase B calls v2 orders' start");
        Doors.Door auditV2 = doors.door("shop/audit");
        untilWhile(d, () -> auditV2.started() && timer.live().stream().filter(t -> t.delay().equals(TICK)).count() == 1, "v2's audit and its worker started: " + timer.all());
        tickCancelsThrow = true;

        ordersStart.fail("address in use");
        DeployRefusedException refused = joinRefused(d, "v2, whose orders does not start");
        tickCancelsThrow = false;

        assertEquals(Kind.FAILED, refused.kind(), refused.error());
        assertEquals("version 2.0.0 did not start; 1.0.0 runs again", refused.error(), "no flow is FAILED");
        assertTrue(Arrays.stream(refused.getSuppressed()).anyMatch(e -> e instanceof AssertionError && "the poll tick cannot be cancelled".equals(e.getMessage())),
                "the Error is added to the refusal: " + Arrays.toString(refused.getSuppressed()));
        assertTrue(ordersStart.door().stopped() && auditV2.stopped(), "v2's orders and audit are stopped all the same");
        for (String flow : List.of("shop/orders", "shop/audit")) {
            Doors.Door again = doors.door(flow);
            assertTrue(again.started() && !again.stopped(), flow + " runs again: " + again.history());
        }
        assertFalse(deployments.flows().stream().anyMatch(f -> f.state() == DeployedFlow.State.FAILED), "every flow runs: " + deployments.flows());
    }

    /**
     * v1 runs a and b; v2 changes b, whose start fails. The rollback builds b again from v1's directory, but the component throws
     * an Error (not a RuntimeException) building its consumer: b is FAILED under its v1 record — not listed RUNNING over the
     * consumer phase A stopped — and the deploy is refused FAILED naming it, the Error suppressed in the refusal (as a stop's Error
     * is, {@link #anErrorAStopThrowsInTheRollbackIsSuppressedInTheRefusal}). v1's archive deployed again adds b — built and
     * started, nothing stopped, a untouched — and b serves; close then stops every consumer the node built exactly once and
     * closes every producer once.
     */
    @Test
    void anErrorBuildingAConsumerInTheRollbackLeavesTheFlowFAILED() throws Exception {
        byte[] zip1 = syncFlows("a", "b").zip();
        deploy(zip1, "r1");
        Map<String, DeployedFlow> v1 = byId(deployments.flows());
        Doors.Door aDoor = doors.door("shop/a");
        doors.failStartOf("shop/b", "address in use");   // v2's b
        String unbuildable = "b's consumer cannot be built again";
        AtomicInteger bBuilds = new AtomicInteger();
        doors.onConsumer(cfg -> {
            if ("shop/b".equals(cfg.options().get("flow")) && bBuilds.incrementAndGet() == 2) throw new AssertionError(unbuildable);   // the rollback's b
        });

        OnVirtualThread.Call<DeployResult> d = deploying(version(changed(syncFlows("a", "b"), "b", "v2"), "2.0.0"), "r2");
        ended(d, "the deploy of v2, whose b does not start");

        assertEquals(2, bBuilds.get(), "fixture: v2 built b's consumer, then the rollback tried once");
        Map<String, DeployedFlow> after = byId(deployments.flows());
        assertEquals(failed(v1.get("shop/b")), after.get("shop/b"), "b is listed under its v1 record, FAILED: nothing of it runs");
        assertEquals(v1.get("shop/a"), after.get("shop/a"), "a runs on as it was");
        DeployRefusedException refused = joinRefused(d, "v2, whose b does not start and whose consumer cannot be built again");
        assertEquals(Kind.FAILED, refused.kind(), refused.error() + " " + refused.problems());
        assertEquals("version 2.0.0 did not start; 1.0.0 runs again; flows [shop/b] FAILED", refused.error());
        assertTrue(Arrays.stream(refused.getSuppressed()).anyMatch(e -> e instanceof AssertionError && unbuildable.equals(e.getMessage())),
                "the Error is added to the refusal: " + Arrays.toString(refused.getSuppressed()));
        assertTrue(refused.problems().contains("the rollback broke off: " + unbuildable),
                "a problem line says the rollback broke off, with the Error's reason: " + refused.problems());
        int mark = doors.events().size();

        DeployResult r = deploy(zip1, "r3");

        assertResult(List.of("shop/b"), List.of(), List.of(), r);
        assertEquals(List.of("consumer shop/b", "start shop/b"), consumerEvents(doors.events().subList(mark, doors.events().size())),
                "only b is built and started; nothing stops");
        assertEquals(DeployedFlow.State.RUNNING, byId(deployments.flows()).get("shop/b").state(), "b runs: " + deployments.flows());
        Doors.Door bAgain = doors.door("shop/b");
        assertTrue(bAgain.started() && !bAgain.stopped(), "b's new consumer runs: " + bAgain.history());
        assertInstanceOf(Ack.Reply.class, within(bAgain.deliver(rt.ingress("shop/b", Headers.empty(), Buffer.buffer("<order/>"))), "b's answer"), "b serves");
        assertTrue(aDoor == doors.door("shop/a") && !aDoor.stopped(), "a's consumer runs on, untouched: " + aDoor.history());
        OnVirtualThread.call(() -> {
            deployments.close();
            return null;
        });
        for (Doors.Door x : doors.doors()) assertEquals(1, x.stops(), x + " is stopped once: " + x.history());
        DeploymentsTest.until(() -> doors.openProducers().isEmpty(), "every producer is closed: " + doors.producers());
        assertEquals(List.of(), doors.producersClosedTwice(), "each producer is closed once: " + doors.producers());
    }

    /**
     * v1 runs a and b, b with a {@code probe} step; v2 changes b, whose start fails. The rollback builds b again from v1's
     * directory, and the step factory throws an Error (not a RuntimeException) building b's probe step: the rollback breaks off.
     * b is FAILED under its v1 record, a runs on untouched, and the deploy is refused FAILED naming b, with the problem line "the
     * rollback broke off: <reason>" and the Error suppressed in the refusal; close then stops every consumer once and closes every
     * producer once.
     */
    @Test
    void anErrorRebuildingAChangedFlowInTheRollbackLeavesItFAILED() throws Exception {
        String sha1 = deploy(probed(syncFlows("a", "b"), "b"), "r1").pkg().archiveSha256();
        Map<String, DeployedFlow> v1 = byId(deployments.flows());
        Doors.Door aDoor = doors.door("shop/a");
        doors.failStartOf("shop/b", "address in use");   // v2's b
        probeThrowsBuildingFrom = sha1;   // only the rollback builds b from v1's directory again

        DeployRefusedException refused = joinRefused(deploying(probed(version(changed(syncFlows("a", "b"), "b", "v2"), "2.0.0"), "b"), "r2"),
                "v2, whose b does not start and does not build again");

        assertEquals(List.of(true, true, false, false, true), probeBuilds.stream().map(dir -> dir.contains(sha1)).toList(),
                "fixture: b's probe was built by v1 from its directory and by v2 from its own, twice each (delivery and replay, D-067), then once by the "
                        + "rollback from v1's, which threw: " + probeBuilds);
        assertEquals(Kind.FAILED, refused.kind(), refused.error() + " " + refused.problems());
        assertEquals("version 2.0.0 did not start; 1.0.0 runs again; flows [shop/b] FAILED", refused.error());
        assertTrue(refused.problems().contains("the rollback broke off: " + PROBE_ERROR),
                "a problem line says the rollback broke off, with the Error's reason: " + refused.problems());
        assertTrue(Arrays.stream(refused.getSuppressed()).anyMatch(e -> e instanceof AssertionError && PROBE_ERROR.equals(e.getMessage())),
                "the Error is added to the refusal: " + Arrays.toString(refused.getSuppressed()));
        Map<String, DeployedFlow> after = byId(deployments.flows());
        assertEquals(failed(v1.get("shop/b")), after.get("shop/b"), "b is listed under its v1 record, FAILED: nothing of it runs");
        assertEquals(v1.get("shop/a"), after.get("shop/a"), "a runs on as it was");
        assertTrue(aDoor == doors.door("shop/a") && !aDoor.stopped(), "a's consumer runs on, untouched: " + aDoor.history());
        OnVirtualThread.call(() -> {
            deployments.close();
            return null;
        });
        for (Doors.Door x : doors.doors()) assertEquals(1, x.stops(), x + " is stopped once: " + x.history());
        DeploymentsTest.until(() -> doors.openProducers().isEmpty(), "every producer is closed: " + doors.producers());
        assertEquals(List.of(), doors.producersClosedTwice(), "each producer is closed once: " + doors.producers());
    }

    // --- d2c r3 (D-063 "Listing", FIX-44): a worker start that throws is its flow's failed start

    /** {@code shop} 1.0.0: {@code a} (at-least-once: a worker, whose start schedules its poll tick) and {@code u} (sync), each from its own in endpoint to {@code partner}. */
    static PackageDraft queuedAndSync() {
        return PackageDraft.of("shop", "1.0.0").endpoint("partner", "out", new JsonObject().put("url", "rec://partner"))
                .endpoint("a-in", "in", new JsonObject().put("path", "/a")).flow("a", "1.0.0", "at-least-once", "a-in", 1024, PackageDraft.toPartner())
                .endpoint("u-in", "in", new JsonObject().put("path", "/u")).flow("u", "1.0.0", "sync", "u-in", 1024, PackageDraft.toPartner());
    }

    /** The live poll ticks of workers: one per worker that started and has not stopped. */
    long liveTicks() {
        return timer.live().stream().filter(t -> t.delay().equals(TICK) && t.owner().equals("FlowWorker")).count();
    }

    /** The doors built for {@code flow}, in build order. */
    List<Doors.Door> doorsOf(String flow) {
        return doors.doors().stream().filter(x -> x.flow().equals(flow)).toList();
    }

    void closeNode() throws Exception {
        OnVirtualThread.call(() -> {
            deployments.close();
            return null;
        });
    }

    /**
     * v1 runs a (at-least-once) and u (sync); v2 (2.0.0) changes a. Phase B starts v2's a: its consumer starts, then its worker's
     * start throws an Error (its poll tick cannot be scheduled, armed for v2's a only) — that is a's failed start, never a
     * Throwable lost in a listener. The deploy rolls back and is refused (500) "version 2.0.0 did not start; 1.0.0 runs again",
     * a problem line naming shop/a and the Error's message. The listing is the one before v2 — a RUNNING under its v1 record, u
     * untouched — and a runs again: the rollback's consumer of it runs and its worker ticks. v2's a is stopped once and every
     * producer v2 built is closed once. {@code then close}: close stops every consumer once. {@code then v1 again}: v1's archive
     * deployed again changes nothing — nothing added, swapped or removed, nothing built, stopped or started.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"then close", "then v1 again"})
    void aWorkerStartThatThrowsIsAFailedStartAndRollsBack(String then) throws Exception {
        byte[] zip1 = queuedAndSync().zip();
        deploy(zip1, "r1");
        List<DeployedFlow> flowsBefore = deployments.flows();
        List<DeployedPackage> packagesBefore = deployments.packages();
        Doors.Door aV1 = doors.door("shop/a");
        Doors.Door uDoor = doors.door("shop/u");
        assertEquals(1, liveTicks(), "fixture: v1's a worker ticks: " + timer.all());
        int producersBefore = doors.producers().size();
        AtomicInteger v2Built = new AtomicInteger(-1);   // the producers built once v2 builds a's consumer: v2's flows are built by then, the rollback's not yet
        AtomicInteger aConsumers = new AtomicInteger();
        doors.onConsumer(cfg -> {
            if ("shop/a".equals(cfg.options().get("flow")) && aConsumers.incrementAndGet() == 1) v2Built.set(doors.producers().size());
        });
        tickSchedulesThrow.set(true);   // v2's a: the first worker start from here on

        DeployRefusedException refused = joinRefused(deploying(version(changed(queuedAndSync(), "a", "v2"), "2.0.0"), "r2"),
                "v2, whose a's worker start throws");

        assertEquals(1, tickThrows.get(), "fixture: starting v2's a's worker threw the Error once: " + tickError.get());
        List<Doors.Door> aDoors = doorsOf("shop/a");
        assertEquals(3, aDoors.size(), "fixture: a's consumer was built by v1, v2 and the rollback: " + doors.events());
        Doors.Door aV2 = aDoors.get(1);
        Doors.Door aAgain = aDoors.get(2);
        assertTrue(aV2.started(), "fixture: v2's a consumer started before its worker's start threw: " + aV2.history());
        assertEquals(Kind.FAILED, refused.kind(), refused.error() + " " + refused.problems());
        assertEquals("version 2.0.0 did not start; 1.0.0 runs again", refused.error(), "a runs again: no flow is FAILED");
        assertTrue(refused.problems().stream().anyMatch(l -> l.contains("shop/a") && l.contains(TICK_UNSCHEDULABLE)),
                "a problem line names a and the Error's message: " + refused.problems());
        assertEquals(flowsBefore, deployments.flows(), "the listing is the one before v2: a RUNNING under its v1 record and hash, u as it was");
        assertEquals(packagesBefore, deployments.packages(), "the package record is v1's");
        assertTrue(aAgain.started() && !aAgain.stopped(), "the rollback's consumer of a runs: " + aAgain.history());
        assertEquals(1, liveTicks(), "a's rebuilt worker ticks, and no other worker of a: " + timer.all());
        assertEquals(1, aV2.stops(), "v2's a is stopped once: " + aV2.history());
        assertEquals(1, aV1.stops(), "phase A stopped v1's a once: " + aV1.history());
        assertTrue(uDoor == doors.door("shop/u") && !uDoor.stopped(), "u's consumer runs on, untouched: " + uDoor.history());
        List<Doors.Out> v2Producers = doors.producers().stream().filter(o -> o.ordinal() > producersBefore && o.ordinal() <= v2Built.get()).toList();
        assertFalse(v2Producers.isEmpty(), "fixture: v2's build opened producers (v2 built them by producer #" + v2Built.get() + "): " + doors.producers());
        DeploymentsTest.until(() -> v2Producers.stream().allMatch(o -> o.closes() > 0), "every producer v2 built is closed: " + v2Producers);
        assertEquals(List.of(), doors.producersClosedTwice(), "each producer is closed once: " + doors.producers());
        List<Doors.Out> rebuilt = doors.producers().stream().filter(o -> o.ordinal() > v2Built.get()).toList();
        assertTrue(!rebuilt.isEmpty() && rebuilt.stream().allMatch(o -> o.closes() == 0), "the producers of the rebuilt a stay open: " + rebuilt);
        int mark = doors.events().size();

        if (then.equals("then close")) {
            closeNode();
            for (Doors.Door x : doors.doors()) assertEquals(1, x.stops(), x + " is stopped once: " + x.history());
            DeploymentsTest.until(() -> doors.openProducers().isEmpty(), "every producer is closed: " + doors.producers());
            assertEquals(List.of(), doors.producersClosedTwice(), "each producer is closed once: " + doors.producers());
        } else {
            DeployResult r = deploy(zip1, "r3");
            assertResult(List.of(), List.of(), List.of(), r);
            assertEquals(List.of(), consumerEvents(doors.events().subList(mark, doors.events().size())), "nothing is built, stopped or started");
            assertEquals(flowsBefore, deployments.flows(), "every flow runs on as it was: a under its v1 record");
            assertTrue(aAgain == doors.door("shop/a") && !aAgain.stopped(), "a's consumer runs on, untouched: " + aAgain.history());
            assertEquals(1, liveTicks(), "a's worker ticks: " + timer.all());
        }
    }

    /**
     * v1 runs a (at-least-once) and u (sync); v2 (2.0.0) changes a, whose consumer does not start (its worker is not started:
     * no tick is scheduled). The rollback builds a again from v1's directory and starts it: its consumer starts, then its worker's
     * start throws an Error (its poll tick cannot be scheduled) — a's start again failed. The deploy is refused (500) naming
     * "flows [shop/a] FAILED", a problem line naming a and the Error's message; a is listed FAILED under its v1 record, u runs on
     * as it was, the package record is v1's; the consumer the rollback started is stopped exactly once and no worker of a ticks.
     * {@code then close}: close stops only u's consumer — nothing of the FAILED a is left to stop. {@code then v1 again}: v1's
     * archive deployed again adds a — only a is built and started — and a runs, its worker ticking.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"then close", "then v1 again"})
    void aWorkerStartThatThrowsInTheRollbackLeavesTheFlowFAILED(String then) throws Exception {
        byte[] zip1 = queuedAndSync().zip();
        deploy(zip1, "r1");
        Map<String, DeployedFlow> v1 = byId(deployments.flows());
        List<DeployedPackage> packagesBefore = deployments.packages();
        Doors.Door uDoor = doors.door("shop/u");
        doors.failStartOf("shop/a", "address in use");   // v2's a: its worker is not started
        tickSchedulesThrow.set(true);                     // the first worker start from here on: the rollback's a

        DeployRefusedException refused = joinRefused(deploying(version(changed(queuedAndSync(), "a", "v2"), "2.0.0"), "r2"),
                "v2, whose a does not start and whose worker cannot start again");

        List<Doors.Door> aDoors = doorsOf("shop/a");
        assertEquals(3, aDoors.size(), "fixture: a's consumer was built by v1, v2 and the rollback: " + doors.events());
        Doors.Door restarted = aDoors.get(2);
        assertTrue(restarted.started(), "fixture: the rollback's a consumer started before its worker's start threw: " + restarted.history());
        assertEquals(1, tickThrows.get(), "fixture: only the rollback's start of a's worker threw (v2's a never started its worker): " + tickError.get());
        assertEquals(Kind.FAILED, refused.kind(), refused.error() + " " + refused.problems());
        assertEquals("version 2.0.0 did not start; 1.0.0 runs again; flows [shop/a] FAILED", refused.error());
        assertTrue(refused.problems().stream().anyMatch(l -> l.contains("shop/a") && l.contains(TICK_UNSCHEDULABLE)),
                "a problem line names a and the Error's message: " + refused.problems());
        Map<String, DeployedFlow> after = byId(deployments.flows());
        assertEquals(failed(v1.get("shop/a")), after.get("shop/a"), "a is listed FAILED under its v1 record: " + after);
        assertEquals(v1.get("shop/u"), after.get("shop/u"), "u runs on as it was");
        assertEquals(List.of("shop/a", "shop/u"), List.copyOf(after.keySet()), "nothing else is listed");
        assertEquals(packagesBefore, deployments.packages(), "the package record is v1's");
        assertEquals(1, restarted.stops(), "the consumer the rollback started is stopped exactly once: " + restarted.history());
        assertEquals(0, liveTicks(), "no worker of the FAILED a ticks: " + timer.all());
        assertTrue(uDoor == doors.door("shop/u") && !uDoor.stopped(), "u's consumer runs on, untouched: " + uDoor.history());
        int mark = doors.events().size();

        if (then.equals("then close")) {
            closeNode();
            assertEquals(List.of("stop shop/u"), consumerEvents(doors.events().subList(mark, doors.events().size())),
                    "close stops only u's consumer: nothing of the FAILED a is left to stop");
            for (Doors.Door x : doors.doors()) assertEquals(1, x.stops(), x + " is stopped once: " + x.history());
            DeploymentsTest.until(() -> doors.openProducers().isEmpty(), "every producer is closed: " + doors.producers());
            assertEquals(List.of(), doors.producersClosedTwice(), "each producer is closed once: " + doors.producers());
        } else {
            DeployResult r = deploy(zip1, "r3");
            assertResult(List.of("shop/a"), List.of(), List.of(), r);
            assertEquals(List.of("consumer shop/a", "start shop/a"), consumerEvents(doors.events().subList(mark, doors.events().size())),
                    "only a is built and started; nothing stops");
            DeployedFlow a = byId(deployments.flows()).get("shop/a");
            assertEquals(DeployedFlow.State.RUNNING, a.state(), "a runs: " + a);
            assertEquals(v1.get("shop/a").hash(), a.hash(), "v1's build of it: " + a);
            Doors.Door aAgain = doors.door("shop/a");
            assertTrue(aAgain.started() && !aAgain.stopped(), "a's new consumer runs: " + aAgain.history());
            assertEquals(1, liveTicks(), "a's new worker ticks: " + timer.all());
            Ack accepted = within(aAgain.deliver(rt.ingress("shop/a", Headers.empty(), Buffer.buffer("<order/>"))), "a's answer");
            assertInstanceOf(Ack.Accepted.class, accepted, "a serves");
        }
    }

    /**
     * v1 runs a, r and u (sync); a message to a is in flight, its send to the partner held. v2 (2.0.0) changes a and removes r.
     * Phase A stops a and r: a's delivery's stop throws (the node's timer cannot schedule its stop deadline). Phase A never
     * throws: a's stop is a WARNING naming a and carrying that exception, it counts as settled, and it keeps r from nothing — r
     * is stopped and closed all the same; phase B starts v2's a, and the deploy answers (a swapped, r removed). The listing is
     * v2's — a RUNNING under its 2.0.0 record, u as it was — and the producer of the message still in flight is not closed under
     * it. Each door is stopped at most once (v1's a and r once, u and v2's a not), v2's a serves, and close then stops v2's a and
     * u once each: every door is stopped exactly once.
     */
    @Test
    void aDeliveryStopThatThrowsInPhaseAIsAWarningAndTheSwapGoesOn() throws Exception {
        deploy(syncFlows("a", "r", "u"), "r1");
        Map<String, DeployedFlow> v1 = byId(deployments.flows());
        Doors.Door aV1 = doors.door("shop/a");
        Doors.Door rV1 = doors.door("shop/r");
        Doors.Door uDoor = doors.door("shop/u");
        Envelope toR = rt.ingress("shop/r", Headers.empty(), Buffer.buffer("<order/>"));
        assertInstanceOf(Ack.Reply.class, within(rV1.deliver(toR), "r's answer"), "fixture: r serves");
        Doors.Out rOut = producerOf(toR);
        int sent = doors.partner().sent().size();
        CountDownLatch held = doors.partner().holdNext(1);
        try {
            Envelope toA = rt.ingress("shop/a", Headers.empty(), Buffer.buffer("<order/>"));
            Future<Ack> inFlight = aV1.deliver(toA);
            doors.partner().awaitSent(sent + 1);
            Doors.Out aOut = producerOf(toA);
            assertNotSame(rOut, aOut, "fixture: a and r each own their producer of the partner: " + doors.producers());
            deadlineSchedulesThrow.set(true);

            OnVirtualThread.Call<DeployResult> d = deploying(version(changed(syncFlows("a", "u"), "a", "v2"), "2.0.0"), "r2");
            ended(d, "the deploy of v2, whose stop of v1's a throws in phase A");

            IllegalStateException thrown = deadlineError.get();
            assertTrue(thrown != null && !deadlineSchedulesThrow.get(), "fixture: stopping v1's a scheduled its delivery's stop deadline, and the timer threw");
            assertFalse(inFlight.isComplete(), "fixture: a's message is still in flight: " + inFlight);
            assertFalse(d.outcome().isCompletedExceptionally(), "the deploy answers, phase A never throws: " + DeploymentsRedeployTest.ended(d));
            DeployResult r = d.outcome().getNow(null);
            assertResult(List.of(), List.of("shop/a"), List.of("shop/r"), r);
            assertTrue(logged.stream().anyMatch(l -> l.getLevel() == Level.WARNING && l.getThrown() == thrown && DeploymentsProducersTest.texts(l).stream()
                    .anyMatch(t -> t.contains("shop/a") && t.contains("did not stop cleanly"))), "a WARNING names a, carrying the exception its stop threw: " + logged);

            assertEquals(1, aV1.stops(), "v1's a is stopped once: " + aV1.history());
            assertEquals(1, rV1.stops(), "r is stopped once, a's stop that threw notwithstanding: " + rV1.history());
            assertEquals(1, rOut.closes(), "r's producer is closed once its stop settled: " + rOut);
            assertEquals(0, aOut.closes(), "the producer of a's message still in flight is not closed under it: " + aOut);
            assertTrue(uDoor == doors.door("shop/u") && !uDoor.stopped(), "u's consumer runs on, untouched: " + uDoor.history());
            List<Doors.Door> aDoors = doorsOf("shop/a");
            assertEquals(2, aDoors.size(), "fixture: a's consumer was built by v1 and v2: " + doors.events());
            Doors.Door aV2 = aDoors.get(1);
            assertTrue(aV2.started() && !aV2.stopped(), "phase B started v2's a: " + aV2.history());

            Map<String, DeployedFlow> after = byId(deployments.flows());
            assertEquals(List.of("shop/a", "shop/u"), List.copyOf(after.keySet()), "a and u are listed, r is not: " + after);
            DeployedFlow a = after.get("shop/a");
            assertEquals(DeployedFlow.State.RUNNING, a.state(), "a runs: " + a);
            assertEquals("2.0.0", a.packageVersion(), "a is listed under its v2 record: " + a);
            assertNotEquals(v1.get("shop/a").hash(), a.hash(), "a is listed with v2's hash: " + a);
            assertEquals(v1.get("shop/u"), after.get("shop/u"), "u is listed as it was");
            assertEquals(List.of(r.pkg()), deployments.packages(), "the package record is v2's");
            assertEquals("2.0.0", r.pkg().version());
            assertInstanceOf(Ack.Reply.class, within(aV2.deliver(rt.ingress("shop/a", Headers.empty(), Buffer.buffer("<order/>"))), "v2 a's answer"),
                    "v2's a serves");

            closeNode();
            assertTrue(aV2.stopped() && uDoor.stopped(), "close stops v2's a and u");
            for (Doors.Door x : doors.doors()) assertEquals(1, x.stops(), x + " is stopped exactly once: " + x.history());
        } finally {
            held.countDown();
        }
    }

    /** The producer {@code e} was sent through; fails the case when none was. */
    Doors.Out producerOf(Envelope e) {
        return doors.producers().stream().filter(o -> o.sentIds().contains(e.id())).findFirst()
                .orElseGet(() -> fail("message " + e.id() + " reached no producer: " + doors.producers()));
    }
}
