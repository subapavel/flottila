package io.flottila.runtime.deploy;

import io.flottila.api.Ack;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Runtime;
import io.flottila.api.Timer;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Snapshot;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.flottila.runtime.deploy.DeploymentsRedeployTest.assertResult;
import static io.flottila.runtime.deploy.DeploymentsRedeployTest.byId;
import static io.flottila.runtime.deploy.DeploymentsRedeployTest.changed;
import static io.flottila.runtime.deploy.DeploymentsRedeployTest.consumerEvents;
import static io.flottila.runtime.deploy.DeploymentsRedeployTest.count;
import static io.flottila.runtime.deploy.DeploymentsRedeployTest.fileKey;
import static io.flottila.runtime.deploy.DeploymentsRedeployTest.reachedWhile;
import static io.flottila.runtime.deploy.DeploymentsRedeployTest.removed;
import static io.flottila.runtime.deploy.DeploymentsRedeployTest.shop;
import static io.flottila.runtime.deploy.DeploymentsRedeployTest.sorted;
import static io.flottila.runtime.deploy.DeploymentsRedeployTest.syncFlows;
import static io.flottila.runtime.deploy.DeploymentsRedeployTest.to;
import static io.flottila.runtime.deploy.DeploymentsRedeployTest.untilWhile;
import static io.flottila.runtime.deploy.DeploymentsTest.children;
import static io.flottila.runtime.deploy.DeploymentsTest.joinRefused;
import static io.flottila.runtime.deploy.DeploymentsTest.regularFiles;
import static io.flottila.runtime.deploy.DeploymentsTest.until;
import static io.flottila.runtime.deploy.DeploymentsTest.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P1-09d2c (D-063): a redeploy whose phase B fails. Every flow of the new version that started is stopped with the node's stop
 * deadline, every other one with a zero deadline, and every build of the new version is closed; then the flows the swap changed
 * or removed are rebuilt — each from the kept directory it ran from, with the stamps it ran with — and go through the same two
 * phases: nothing of them starts before every stop of the new version settled. The deploy is refused {@link Kind#FAILED} (500
 * {@code {error, problems}}): "version X did not start; Y runs again", the package record unchanged, the new directory deleted
 * unless it is a kept one. A flow whose rebuild or start fails is {@link DeployedFlow.State#FAILED}: no objects of it run, it is
 * listed under the package's running record, and the error names it ("flows [..] FAILED"); the next deploy treats it as added.
 * {@code consumer.start()} is bounded by the node's stop deadline (a start that never completes is a failed start, its late success
 * is followed by a stop), and so is phase A's {@code consumer.stop()} (a WARNING, the swap goes on). Stopping many flows tries
 * every one: a stop that throws does not skip the others. A kept file the deploy cannot read is a mismatch, and a deploy of the
 * running archive keeps the real previous directory.
 *
 * <p>Fixtures as in {@link DeploymentsRedeployTest}: {@link Doors} at the SPI boundary (P1-09d2c: {@link Doors#holdStartOf},
 * {@link Doors#failStartOf}, {@link Doors#holdStopOf}, each door's history on one sequence with every producer's close), a
 * {@link TracingTimer} over the fake timer (P1-09d2c: each task names the class that scheduled it, so a delivery's stop deadline
 * is told from any bound the deploy arms with the same delay), a {@link CountingQueue}; one log of the Doors events and the timer.
 * {@code Deployments} gets the node's timer explicitly, right after the clock — the same fake timer the {@code Runtime} carries
 * ({@code Runtime} itself is read inside {@code FlowBuilder} only): a bound the deploy arms is driven by the fake clock, never by
 * wall time.
 */
class DeploymentsRollbackTest {

    static final String NODE = "node-1";
    static final Duration STOP_DEADLINE = DeploymentsRedeployTest.STOP_DEADLINE;
    static final Duration TICK = DeploymentsRedeployTest.TICK;
    static final String TICK_STUCK = "the poll tick cannot be cancelled";
    static final Pattern FAILED_FLOWS = Pattern.compile("flows \\[([^\\]]*)\\] FAILED");

    @TempDir Path tmp;
    Path data;
    Path archives;

    final FakeRuntime rt = FakeRuntime.create();
    final Doors doors = new Doors();
    final InMemoryMessageStore messages = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();
    final List<CountDownLatch> holds = new CopyOnWriteArrayList<>();
    /** The Doors events, producer closes and timer schedules/cancels, in the order they were made. */
    final List<String> log = new CopyOnWriteArrayList<>();
    final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    /** Strongly held: JUL keeps loggers weakly, and a collected logger loses its handler. */
    final Logger flottila = Logger.getLogger("io.flottila");
    final Handler capture = new Handler() {
        @Override public void publish(LogRecord r) { logged.add(r); }
        @Override public void flush() {}
        @Override public void close() {}
    };
    /** While set, cancelling a worker's poll tick throws: the worker's stop() throws synchronously. */
    volatile boolean tickCancelsThrow;
    TracingTimer timer;
    CountingQueue queue;
    Deployments deployments;

    @BeforeEach
    void wire() {
        data = tmp.resolve("data");
        archives = tmp.resolve("archives");
        timer = new TracingTimer(rt.timer()).trace(log::add);
        queue = new CountingQueue(rt.clock());
        doors.trace(log::add);
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "dev");
        env.put("FLOTTILA_NODE_ID", NODE);
        env.put("FLOTTILA_DATA_DIR", data.toString());
        env.put("FLOTTILA_STOP_DEADLINE", STOP_DEADLINE.toString());
        env.put("FLOTTILA_BLOB_DURABLE_MAX", "4096");
        env.put("FLOTTILA_MAX_PACKAGE_BYTES", Long.toString(2L * 1024 * 1024));
        Timer ticksMayThrow = (delay, task) -> {
            Timer.Cancellable scheduled = timer.schedule(delay, task);
            if (!delay.equals(TICK)) return scheduled;
            return () -> {
                if (tickCancelsThrow) throw new IllegalStateException(TICK_STUCK);
                return scheduled.cancel();
            };
        };
        Runtime runtime = new Runtime(rt.clock(), ticksMayThrow, rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
        deployments = new Deployments(NodeConfig.from(env), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), ticksMayThrow, List.of(), List.of(), messages, dlq,
                queue, () -> 0L);
        capture.setLevel(Level.ALL);
        flottila.addHandler(capture);
    }

    @AfterEach
    void releaseWhatACaseHeld() {
        tickCancelsThrow = false;
        for (CountDownLatch l : holds) l.countDown();
        doors.releaseAll();
        doors.onConsumer(cfg -> {});
        flottila.removeHandler(capture);
    }

    // --- fixture: deploys

    Path packageDir(String sha256) {
        return data.resolve("packages").resolve("shop").resolve(sha256);
    }

    Path shopDir() {
        return data.resolve("packages").resolve("shop");
    }

    DeployResult deploy(PackageDraft p, String revision) throws Exception {
        return deploy(p.zip(), revision);
    }

    DeployResult deploy(byte[] zip, String revision) throws Exception {
        Path archive = PackageDraft.write(archives, zip);
        return OnVirtualThread.call(() -> deployments.deploy("shop", Optional.of(revision), archive));
    }

    OnVirtualThread.Call<DeployResult> deploying(PackageDraft p, String revision) {
        return deploying(p.zip(), revision);
    }

    OnVirtualThread.Call<DeployResult> deploying(byte[] zip, String revision) {
        Path archive = PackageDraft.write(archives, zip);
        return OnVirtualThread.start(() -> deployments.deploy("shop", Optional.of(revision), archive));
    }

    DeployRefusedException refused(PackageDraft p, String revision) throws Exception {
        return joinRefused(deploying(p, revision), "the deploy of shop " + revision + " is refused");
    }

    static DeployedFlow failed(DeployedFlow f) {
        return new DeployedFlow(f.id(), f.pkg(), f.packageVersion(), f.revision(), f.flowVersion(), f.delivery(), f.hash(), DeployedFlow.State.FAILED);
    }

    /** The flows the error names as FAILED ({@code "flows [<id>, <id>] FAILED"}), sorted; fails when it names none. */
    static List<String> failedIn(String error) {
        Matcher m = FAILED_FLOWS.matcher(error);
        if (!m.find()) fail("the error names no 'flows [..] FAILED': " + error);
        return Arrays.stream(m.group(1).split(",")).map(String::trim).sorted().toList();
    }

    static void assertProblem(DeployRefusedException refused, String... words) {
        assertTrue(refused.problems().stream().anyMatch(l -> Arrays.stream(words).allMatch(l::contains)),
                "a problem line names " + List.of(words) + ": " + refused.problems() + " (error: " + refused.error() + ")");
    }

    // --- fixture: messages

    record Sent(Envelope e, Future<Ack> answer) {
        String id() { return e.id(); }
    }

    /** A message at the partner, held until {@code release} is counted down (or the case ends). */
    record Held(Sent sent, CountDownLatch release) {}

    Envelope envelope(String flow) {
        return rt.ingress(flow, Headers.empty(), Buffer.buffer("<order/>"));
    }

    Sent send(Doors.Door door) {
        Envelope e = envelope(door.flow());
        return new Sent(e, door.deliver(e));
    }

    Sent send(String flow) {
        return send(doors.door(flow));
    }

    /** Delivers a message through {@code door} whose send to the partner is held; returns once the partner recorded it. */
    Held heldAtPartner(Doors.Door door) {
        int before = doors.partner().sent().size();
        CountDownLatch release = doors.partner().holdNext(1);
        holds.add(release);
        Sent s = send(door);
        doors.partner().awaitSent(before + 1);
        return new Held(s, release);
    }

    Doors.Out producerOf(Sent s) {
        until(() -> doors.producers().stream().anyMatch(o -> o.sentIds().contains(s.id())), "message " + s.id() + " reaches a producer");
        return doors.producers().stream().filter(o -> o.sentIds().contains(s.id())).findFirst().orElseThrow();
    }

    Map<String, String> versions(String id) {
        until(() -> !messages.timeline(id).await().isEmpty(), "fixture: message " + id + " is recorded");
        Set<Map<String, String>> all = messages.timeline(id).await().stream().map(Snapshot::versions).collect(Collectors.toSet());
        assertEquals(1, all.size(), "every snapshot of " + id + " carries the same versions: " + all);
        return all.iterator().next();
    }

    static void assertRefusesRetryable(Doors.Door door, Ack a) {
        assertTrue(a instanceof Ack.Nack n && n.retryable(), door + " runs no more: its delivery refuses a new message, was " + a);
    }

    void assertNothingClosedTwice() {
        assertEquals(List.of(), doors.producersClosedTwice(), "each producer is closed once: " + doors.producers());
    }

    /** The doors of {@code flow} that started and were not stopped since. */
    List<Doors.Door> live(String flow) {
        return doors.doors().stream().filter(d -> d.flow().equals(flow) && d.started() && !d.stopped()).toList();
    }

    long claimsOf(String flow) {
        return queue.claims().stream().filter((NODE + " " + flow)::equals).count();
    }

    /**
     * Waits until the one message sent since {@code before} (the flow's claims then) completed and the worker polled once after
     * it finished — a worker polls once after an accepted message and once after each finished item (FlowWorker), so that is
     * {@code before + 2} — and returns the claims then: the claims counted next are the tick's alone, not that post-finish poll.
     */
    long completedAndPolledAfter(String flow, long before) {
        until(() -> queue.size() == 0 && claimsOf(flow) == before + 2,
                "the message completed and " + flow + "'s worker polled after it was accepted and after it finished (claims before: " + before + ")");
        return claimsOf(flow);
    }

    // --- fixture: the log and the timer

    int mark() {
        return log.size();
    }

    List<String> since(int mark) {
        List<String> all = List.copyOf(log);
        return all.subList(mark, all.size());
    }

    /** The live timer tasks a delivery scheduled with {@code delay}: a delivery stopped with that deadline while a message was in flight. */
    long deliveryDeadlines(Duration delay) {
        return timer.live().stream().filter(t -> t.owner().equals("Delivery") && t.delay().equals(delay)).count();
    }

    long liveTasks(Duration delay) {
        return timer.live().stream().filter(t -> t.delay().equals(delay)).count();
    }

    /** The live tasks of {@code delay} among those scheduled after the first {@code from}: a bound left live by an earlier deploy is not counted. */
    long liveTasksSince(int from, Duration delay) {
        List<TracingTimer.Task> all = timer.all();
        return all.subList(from, all.size()).stream().filter(t -> t.live() && t.delay().equals(delay)).count();
    }

    List<LogRecord> warningsNaming(String flow) {
        return logged.stream().filter(r -> r.getLevel() == Level.WARNING && DeploymentsProducersTest.texts(r).stream().anyMatch(t -> t.contains(flow))).toList();
    }

    // --- a failed start rolls back

    /**
     * v1 (r1) runs orders (sync), audit (at-least-once, to {@code archive}) and ping (at-most-once); v2 (1.1.0, r2) changes only
     * ping, which sends to {@code archive} from then on — orders and audit run on from v1's directory with v1's stamps, ping from
     * v2's with v2's. v3 (2.0.0, r3) changes orders and audit, removes ping and adds extra (sync). Extra's start is held while a
     * message of v3's orders (started) and one of extra are in flight at the partner, then fails. v3's orders, which started,
     * stops its delivery with the node's stop deadline (its message is cancelled at that deadline, not a millisecond earlier, and
     * nothing starts again meanwhile); extra, which did not start, with a zero deadline; every v3 consumer stops and every producer
     * v3 built closes. Only once all of that settled are orders, audit and ping rebuilt — each from the directory it ran from, with
     * its stamps — and started. The deploy is FAILED (500): "version 2.0.0 did not start; 1.1.0 runs again", a problem naming
     * extra and the cause. Every flow is listed as before v3 (stamps, hash, RUNNING); its messages carry the old stamps; ping sends
     * to {@code archive} (v2's build); audit's new worker sends and polls once per tick; the package record is v2's; v3's directory
     * is deleted, v1's and v2's are the same directories with the same files. d2c r3: each flow phase A stopped (orders, audit,
     * ping) is listed after the 500 RUNNING with the hash and stamps it ran with before v3.
     */
    @Test
    void aFailedStartRollsBackFromTheKeptDirAnd500s() throws Exception {
        String sha1 = deploy(shop(), "r1").pkg().archiveSha256();
        PackageDraft v2 = shop();
        v2.flowDoc("ping").put("steps", to("archive"));
        v2.manifest().put("version", "1.1.0");
        DeployResult second = deploy(v2, "r2");
        assertResult(List.of(), List.of("shop/ping"), List.of(), second);
        String sha2 = second.pkg().archiveSha256();
        Map<String, DeployedFlow> flowsBefore = byId(deployments.flows());
        List<DeployedPackage> packagesBefore = deployments.packages();
        Object key1 = fileKey(packageDir(sha1));
        Object key2 = fileKey(packageDir(sha2));
        assertNotNull(key1, "fixture: the file system names its directories (Linux: device and inode)");
        List<String> files1 = regularFiles(packageDir(sha1));
        List<String> files2 = regularFiles(packageDir(sha2));
        List<Doors.Out> running = doors.openProducers();
        assertEquals(3, running.size(), "fixture: one producer per running flow: " + doors.producers());
        PackageDraft v3 = changed(changed(removed(shop(), "ping"), "orders", "v3"), "audit", "v3")
                .endpoint("extra-in", "in", new JsonObject().put("path", "/extra")).flow("extra", "1.0.0", "sync", "extra-in", 1024, to("partner"));
        v3.manifest().put("version", "2.0.0");
        byte[] zip3 = v3.zip();
        int doorsBefore = doors.doors().size();
        int producersBefore = doors.producers().size();
        Doors.Gate extraStart = doors.holdStartOf("shop/extra");
        int mark = mark();

        OnVirtualThread.Call<DeployResult> d = deploying(zip3, "r3");

        reachedWhile(d, extraStart.reached(), "phase B calls v3 extra's start");
        Doors.Door ordersV3 = doors.door("shop/orders");
        Doors.Door auditV3 = doors.door("shop/audit");
        Doors.Door extraV3 = extraStart.door();
        untilWhile(d, () -> ordersV3.started() && auditV3.started(), "v3's orders and audit started while extra's start is held");
        List<Doors.Door> v3Doors = doors.doors().subList(doorsBefore, doors.doors().size());
        assertEquals(Set.of("shop/orders", "shop/audit", "shop/extra"), v3Doors.stream().map(Doors.Door::flow).collect(Collectors.toSet()), "fixture: v3's consumers");
        List<Doors.Out> v3Producers = doors.producers().subList(producersBefore, doors.producers().size());
        Held toOrders = heldAtPartner(ordersV3);
        Held toExtra = heldAtPartner(extraV3);

        extraStart.fail("address in use");

        untilWhile(d, () -> deliveryDeadlines(STOP_DEADLINE) == 1,
                "v3's orders started: its delivery is stopped with the node's stop deadline " + STOP_DEADLINE + ", its message still in flight: " + timer.all());
        rt.clock().advance(STOP_DEADLINE.minusMillis(1));
        assertFalse(toOrders.sent().answer().isComplete(), "orders' message is not cancelled a millisecond before the stop deadline");
        assertFalse(d.isDone(), "the rollback waits for v3's orders to stop");
        assertEquals(1, count(since(mark), "start shop/orders"), "nothing is started again while v3's orders drains: " + since(mark));
        assertFalse(since(mark).contains("start shop/ping"), "nor ping: " + since(mark));
        rt.clock().advance(Duration.ofMillis(1));
        Ack atDeadline = within(toOrders.sent().answer(), "orders' message at the stop deadline");
        assertTrue(atDeadline instanceof Ack.Nack n && n.retryable() && n.reason().contains(STOP_DEADLINE.toString()),
                "v3's orders' message is cancelled by the node's stop deadline, retryable: " + atDeadline);
        Ack cancelled = within(toExtra.sent().answer(), "extra's message");
        assertTrue(cancelled instanceof Ack.Nack n && n.retryable() && n.reason().contains(Duration.ZERO.toString()),
                "extra did not start: its delivery is stopped with a zero deadline: " + cancelled);
        DeployRefusedException refused = joinRefused(d, "v3, whose extra did not start");

        assertEquals(Kind.FAILED, refused.kind(), refused.error() + " " + refused.problems());
        assertTrue(refused.error().contains("version 2.0.0 did not start") && refused.error().contains("1.1.0 runs again"),
                "the error names the version that did not start and the one that runs again: " + refused.error());
        assertFalse(refused.error().contains("FAILED"), "no flow is FAILED: " + refused.error());
        assertProblem(refused, "extra", "address in use");

        List<Doors.Door> rollback = doors.doors().subList(doorsBefore + v3Doors.size(), doors.doors().size());
        assertEquals(List.of("shop/audit", "shop/orders", "shop/ping"), rollback.stream().map(Doors.Door::flow).sorted().toList(),
                "the changed flows and the removed one are rebuilt, the added one is not: " + doors.events());
        for (Doors.Door r : rollback) assertTrue(r.started() && !r.stopped(), r + " runs: " + r.history());
        for (Doors.Door v : v3Doors) assertTrue(v.stopped(), v + " is stopped: " + v.history());
        until(() -> v3Producers.stream().allMatch(o -> o.closes() == 1), "every producer v3 built is closed: " + v3Producers);
        long v3Settled = 0;
        for (Doors.Door v : v3Doors) v3Settled = Math.max(v3Settled, v.first("stopped"));
        for (Doors.Out o : v3Producers) if (o.endpoint().equals("partner")) v3Settled = Math.max(v3Settled, o.closedAt());   // the deliveries' producers: awaited
        long rollbackStarts = rollback.stream().mapToLong(r -> r.first("start")).min().orElseThrow();
        assertTrue(v3Settled > 0 && v3Settled < rollbackStarts,
                "the rebuilt flows start only once every v3 flow stopped and its delivery's producers closed (seq " + v3Settled + " < " + rollbackStarts + "): "
                        + v3Doors.stream().map(v -> v + " " + v.history()).toList());

        assertEquals(flowsBefore, byId(deployments.flows()), "every flow is listed as it ran before v3: its stamps, its hash, RUNNING");
        Map<String, DeployedFlow> afterRollback = byId(deployments.flows());
        for (String retired : List.of("shop/orders", "shop/audit", "shop/ping")) {   // phase A stopped them: changed, changed, removed
            DeployedFlow was = flowsBefore.get(retired);
            DeployedFlow is = afterRollback.get(retired);
            assertNotNull(is, retired + ", which phase A stopped, is listed after the 500: " + afterRollback.keySet());
            assertEquals(DeployedFlow.State.RUNNING, is.state(), retired + " runs again after the 500, not FAILED: " + is);
            assertEquals(was.hash(), is.hash(), retired + " is listed with the hash it ran with before v3: " + is);
            assertEquals(was.packageVersion(), is.packageVersion(), retired + " is listed with the package version it ran with before v3: " + is);
            assertEquals(was.revision(), is.revision(), retired + " is listed with the revision it ran with before v3: " + is);
            assertEquals(was.flowVersion(), is.flowVersion(), retired + " is listed with the flow version it ran with before v3: " + is);
        }
        assertEquals(packagesBefore, deployments.packages(), "the package record is v2's");
        assertEquals(sorted(sha1, sha2), children(shopDir()), "v3's directory is deleted, v1's and v2's stay");
        assertEquals(key1, fileKey(packageDir(sha1)), "v1's directory is the same directory");
        assertEquals(key2, fileKey(packageDir(sha2)), "v2's directory is the same directory");
        assertEquals(files1, regularFiles(packageDir(sha1)));
        assertEquals(files2, regularFiles(packageDir(sha2)));
        assertEquals(List.of(), children(deployments.incomingDir()), "nothing is left in .incoming");

        Sent sync = send("shop/orders");
        assertInstanceOf(Ack.Reply.class, within(sync.answer(), "orders' answer"), "orders serves again");
        assertEquals(Map.of("flow", "shop/orders@2.1.0", "package", "shop@1.0.0", "revision", "r1"), versions(sync.id()), "orders runs with v1's stamps");
        Sent ping = send("shop/ping");
        assertInstanceOf(Ack.Accepted.class, within(ping.answer(), "ping's answer"));
        assertEquals(Map.of("flow", "shop/ping@0.0.1", "package", "shop@1.1.0", "revision", "r2"), versions(ping.id()), "ping runs with v2's stamps");
        assertEquals("archive", producerOf(ping).endpoint(), "ping runs v2's build, rebuilt from v2's directory: it sends to archive");
        long claimsBefore = claimsOf("shop/audit");
        Sent alo = send("shop/audit");
        assertEquals(new Ack.Accepted(alo.id()), within(alo.answer(), "audit's answer"));
        Doors.Out auditOut = producerOf(alo);
        assertTrue(auditOut.ordinal() > producersBefore + v3Producers.size(), "audit's rebuilt worker sends it: " + auditOut);
        assertEquals(Map.of("flow", "shop/audit@1.0.3", "package", "shop@1.0.0", "revision", "r1"), versions(alo.id()), "audit runs with v1's stamps");
        long polls = completedAndPolledAfter("shop/audit", claimsBefore);
        rt.clock().advance(TICK);
        assertEquals(polls + 1, claimsOf("shop/audit"), "one worker polls audit per tick, the rebuilt one: " + queue.claims());
        assertRefusesRetryable(extraV3, within(extraV3.deliver(envelope("shop/extra")), "a message on v3's extra"));
        for (Doors.Out o : running) assertEquals(1, o.closes(), "the producers running before v3 are closed once: " + o);
        assertEquals(3, doors.openProducers().size(), "open: one producer per rebuilt flow: " + doors.producers());
        assertTrue(doors.openProducers().stream().allMatch(o -> o.ordinal() > producersBefore + v3Producers.size()), "each built by the rollback: " + doors.producers());
        assertNothingClosedTwice();
    }

    /**
     * v1 runs {@code a}; v2 changes it; v1's archive deployed again — its directory is kept as the previous one and reused — fails
     * to start {@code a}: v2's {@code a} runs again, and v1's directory, a kept one, stays as it is.
     */
    @Test
    void aFailedStartOfAKeptArchiveLeavesItsDirectory() throws Exception {
        byte[] zip1 = changed(syncFlows("a"), "a", "v1").zip();
        String sha1 = deploy(zip1, "r1").pkg().archiveSha256();
        PackageDraft v2 = changed(syncFlows("a"), "a", "v2");
        v2.manifest().put("version", "2.0.0");
        String sha2 = deploy(v2, "r2").pkg().archiveSha256();
        Object key1 = fileKey(packageDir(sha1));
        List<String> files1 = regularFiles(packageDir(sha1));
        assertEquals(sorted(sha1, sha2), children(shopDir()), "fixture: v1's directory is kept as the previous one");
        List<DeployedFlow> flowsBefore = deployments.flows();
        List<DeployedPackage> packagesBefore = deployments.packages();
        doors.failStartOf("shop/a", "address in use");

        DeployRefusedException refused = joinRefused(deploying(zip1, "r3"), "v1 again, whose a does not start");

        assertEquals(Kind.FAILED, refused.kind(), refused.error() + " " + refused.problems());
        assertTrue(refused.error().contains("version 1.0.0 did not start") && refused.error().contains("2.0.0 runs again"), refused.error());
        assertEquals(sorted(sha1, sha2), children(shopDir()), "the new directory is a kept one: it stays");
        assertEquals(key1, fileKey(packageDir(sha1)), "the same directory");
        assertEquals(files1, regularFiles(packageDir(sha1)), "with its files");
        assertEquals(flowsBefore, deployments.flows());
        assertEquals(packagesBefore, deployments.packages(), "the package record is v2's");
        Sent s = send("shop/a");
        assertInstanceOf(Ack.Reply.class, within(s.answer(), "a's answer"));
        assertEquals("r2", versions(s.id()).get("revision"), "v2's a runs again");
    }

    // --- FAILED flows

    /**
     * v2 (2.0.0) changes orders and audit and removes ping; v2's orders does not start. The rollback restarts orders, but audit's
     * start fails again and ping's consumer cannot be built again: audit and ping are FAILED. The deploy is FAILED (500), its
     * error naming the version and "flows [shop/audit, shop/ping] FAILED", its problems naming each failure. audit and ping are
     * listed as before but FAILED, orders as before; the package record is v1's and still names all three. Nothing of a FAILED
     * flow runs: every consumer of it is stopped and refuses, none of its producers is open, audit's worker never polls; orders
     * serves with v1's stamps; v2's directory is deleted.
     */
    @Test
    void aFailedRollbackListsFailedAnd500sNamingIt() throws Exception {
        String sha1 = deploy(shop(), "r1").pkg().archiveSha256();
        long v1Polls = claimsOf("shop/audit");
        rt.clock().advance(TICK);
        assertEquals(v1Polls + 1, claimsOf("shop/audit"), "fixture: a running audit worker shows as one claim per tick");
        Map<String, DeployedFlow> before = byId(deployments.flows());
        List<DeployedPackage> packagesBefore = deployments.packages();
        PackageDraft v2 = changed(changed(removed(shop(), "ping"), "orders", "v2"), "audit", "v2");
        v2.manifest().put("version", "2.0.0");
        byte[] zip2 = v2.zip();
        doors.failStartOf("shop/orders", "address in use");          // v2's orders
        doors.holdStartOf("shop/audit").release();                   // v2's audit starts
        doors.failStartOf("shop/audit", "still in use");             // the rollback's audit
        AtomicInteger pingBuilds = new AtomicInteger();
        doors.onConsumer(cfg -> {
            if ("shop/ping".equals(cfg.options().get("flow")) && pingBuilds.incrementAndGet() == 1) throw new IllegalArgumentException("ping cannot be built again");
        });

        DeployRefusedException refused = joinRefused(deploying(zip2, "r2"), "v2, whose orders does not start");

        assertEquals(Kind.FAILED, refused.kind(), refused.error() + " " + refused.problems());
        assertTrue(refused.error().contains("version 2.0.0 did not start"), refused.error());
        assertEquals(List.of("shop/audit", "shop/ping"), failedIn(refused.error()), "the error names the FAILED flows: " + refused.error());
        assertProblem(refused, "orders", "address in use");
        assertProblem(refused, "audit", "still in use");
        assertProblem(refused, "ping", "ping cannot be built again");
        assertEquals(1, pingBuilds.get(), "fixture: the rollback tried to build ping's consumer once");

        Map<String, DeployedFlow> after = byId(deployments.flows());
        assertEquals(failed(before.get("shop/audit")), after.get("shop/audit"), "audit is listed under the running record, FAILED");
        assertEquals(failed(before.get("shop/ping")), after.get("shop/ping"), "ping is listed under the running record, FAILED");
        assertEquals(before.get("shop/orders"), after.get("shop/orders"), "orders runs again, listed as before");
        assertEquals(3, after.size(), "nothing else is listed: " + after.keySet());
        assertEquals(packagesBefore, deployments.packages(), "the package record is v1's, naming all three flows");
        assertEquals(List.of(sha1), children(shopDir()), "v2's directory is deleted");

        for (String flow : List.of("shop/audit", "shop/ping")) {
            List<Doors.Door> of = doors.doors().stream().filter(x -> x.flow().equals(flow)).toList();
            for (Doors.Door x : of) assertTrue(x.stopped(), "no consumer of the FAILED " + flow + " runs: " + x + " " + x.history());
            Doors.Door last = doors.door(flow);
            assertRefusesRetryable(last, within(last.deliver(envelope(flow)), "a message on " + flow + "'s last consumer"));
        }
        until(() -> doors.openProducers().size() == 1, "only the rolled-back orders keeps a producer open: " + doors.producers());
        assertEquals("partner", doors.openProducers().get(0).endpoint(), "orders' delivery producer; no producer of audit (archive) is open");
        long polls = claimsOf("shop/audit");
        rt.clock().advance(TICK.multipliedBy(3));
        assertEquals(polls, claimsOf("shop/audit"), "no worker of the FAILED audit polls: " + queue.claims());
        Sent sync = send("shop/orders");
        assertInstanceOf(Ack.Reply.class, within(sync.answer(), "orders' answer"));
        assertEquals("r1", versions(sync.id()).get("revision"), "orders runs v1's build again");
        assertNothingClosedTwice();
    }

    /**
     * v2 changes orders, whose start fails in v2 and again in the rollback: orders is FAILED. v1's archive deployed again (r3)
     * treats it as added: only orders is built and started — nothing else is built, stopped or started — from v1's kept
     * directory, reused; the package record carries r3 and the new time; orders runs RUNNING with r3, audit and ping as they
     * were.
     */
    @Test
    void theNextDeployStartsAFailedFlow() throws Exception {
        byte[] zip1 = shop().zip();
        String sha1 = deploy(zip1, "r1").pkg().archiveSha256();
        Object key1 = fileKey(packageDir(sha1));
        Map<String, DeployedFlow> before = byId(deployments.flows());
        Doors.Door auditDoor = doors.door("shop/audit");
        Doors.Door pingDoor = doors.door("shop/ping");
        PackageDraft v2 = changed(shop(), "orders", "v2");
        v2.manifest().put("version", "2.0.0");
        doors.failStartOf("shop/orders", "address in use");
        doors.failStartOf("shop/orders", "still in use");
        DeployRefusedException refused = refused(v2, "r2");
        assertEquals(List.of("shop/orders"), failedIn(refused.error()), "fixture: orders is FAILED: " + refused.error());
        assertEquals(DeployedFlow.State.FAILED, byId(deployments.flows()).get("shop/orders").state(), "fixture");
        assertTrue(log.contains("stop shop/orders"), "fixture: the log records a stop (v2's phase A stopped orders): " + log);
        rt.clock().advance(Duration.ofSeconds(30));
        int mark = mark();

        DeployResult r = deploy(zip1, "r3");

        assertResult(List.of("shop/orders"), List.of(), List.of(), r);
        assertEquals(List.of("consumer shop/orders", "start shop/orders"), consumerEvents(since(mark)), "only orders is built and started; nothing stops");
        assertEquals(key1, fileKey(packageDir(sha1)), "v1's kept directory is reused");
        assertEquals(List.of(sha1), children(shopDir()));
        assertEquals(sha1, r.pkg().archiveSha256());
        assertEquals("1.0.0", r.pkg().version());
        assertEquals(Optional.of("r3"), r.pkg().revision(), "the package record carries the new revision");
        assertEquals(rt.clock().instant(), r.pkg().deployedAt(), "and the new time");
        assertEquals(List.of(r.pkg()), deployments.packages());
        Map<String, DeployedFlow> after = byId(deployments.flows());
        DeployedFlow orders = after.get("shop/orders");
        assertEquals(DeployedFlow.State.RUNNING, orders.state(), "orders runs: " + orders);
        assertEquals(Optional.of("r3"), orders.revision(), "with the deploy's stamps: " + orders);
        assertEquals("1.0.0", orders.packageVersion());
        assertEquals(before.get("shop/orders").hash(), orders.hash(), "v1's build of it");
        assertEquals(before.get("shop/audit"), after.get("shop/audit"), "audit runs on as it was");
        assertEquals(before.get("shop/ping"), after.get("shop/ping"), "ping runs on as it was");
        Sent sync = send("shop/orders");
        assertInstanceOf(Ack.Reply.class, within(sync.answer(), "orders' answer"), "orders serves");
        assertEquals(Map.of("flow", "shop/orders@2.1.0", "package", "shop@1.0.0", "revision", "r3"), versions(sync.id()));
        assertTrue(auditDoor == doors.door("shop/audit") && !auditDoor.stopped(), "audit's consumer runs on, untouched");
        assertTrue(pingDoor == doors.door("shop/ping") && !pingDoor.stopped(), "ping's consumer runs on, untouched");
    }

    /**
     * v2 (2.0.0) changes orders and audit and removes ping; v2's orders does not start. {@code rolled back}: the rollback runs v1's
     * three flows again. {@code FAILED}: audit's restart fails and ping's consumer cannot be built again. Either way, the result of
     * the failed v2 is that nothing is half-done: before close, a node has every flow either running or FAILED.
     */
    void failV2(String outcome) throws Exception {
        PackageDraft v2 = changed(changed(removed(shop(), "ping"), "orders", "v2"), "audit", "v2");
        v2.manifest().put("version", "2.0.0");
        doors.failStartOf("shop/orders", "address in use");
        if (outcome.equals("FAILED")) {
            doors.holdStartOf("shop/audit").release();
            doors.failStartOf("shop/audit", "still in use");
            AtomicInteger pingBuilds = new AtomicInteger();
            doors.onConsumer(cfg -> {
                if ("shop/ping".equals(cfg.options().get("flow")) && pingBuilds.incrementAndGet() == 1) throw new IllegalArgumentException("ping cannot be built again");
            });
        }
        DeployRefusedException refused = refused(v2, "r2");
        assertEquals(Kind.FAILED, refused.kind(), "fixture: " + refused.error() + " " + refused.problems());
        List<String> failedFlows = deployments.flows().stream().filter(f -> f.state() == DeployedFlow.State.FAILED).map(DeployedFlow::id).toList();
        assertEquals(outcome.equals("FAILED") ? List.of("shop/audit", "shop/ping") : List.of(), failedFlows, "fixture: " + refused.error());
    }

    /**
     * After a failed v2 (rolled back, or with flows FAILED), v1's archive deployed again leaves every v1 flow running: one consumer
     * of each started and not stopped, each serving — orders replies, ping accepts, audit accepts and its worker sends it on — and
     * every flow listed RUNNING. The FAILED ones are added; a rolled-back one runs v1's build already and is not touched.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"rolled back", "FAILED"})
    void aRePutOfV1AfterAFailedV2RestartsItsStoppedFlows(String outcome) throws Exception {
        byte[] zip1 = shop().zip();
        deploy(zip1, "r1");
        failV2(outcome);

        DeployResult r = deploy(zip1, "r3");

        assertResult(outcome.equals("FAILED") ? List.of("shop/audit", "shop/ping") : List.of(), List.of(), List.of(), r);
        for (String flow : List.of("shop/orders", "shop/audit", "shop/ping")) {
            assertEquals(1, live(flow).size(), "one consumer of " + flow + " runs: " + doors.doors().stream().filter(x -> x.flow().equals(flow)).map(x -> x + " " + x.history()).toList());
        }
        assertEquals(Set.of(DeployedFlow.State.RUNNING), deployments.flows().stream().map(DeployedFlow::state).collect(Collectors.toSet()), "every flow runs: " + deployments.flows());
        assertInstanceOf(Ack.Reply.class, within(send(live("shop/orders").get(0)).answer(), "orders' answer"), "orders serves");
        assertInstanceOf(Ack.Accepted.class, within(send(live("shop/ping").get(0)).answer(), "ping's answer"), "ping serves");
        long claimsBefore = claimsOf("shop/audit");
        Sent alo = send(live("shop/audit").get(0));
        assertEquals(new Ack.Accepted(alo.id()), within(alo.answer(), "audit's answer"), "audit serves");
        assertEquals(0, producerOf(alo).closes(), "and a running worker sends it on");
        long polls = completedAndPolledAfter("shop/audit", claimsBefore);
        rt.clock().advance(TICK);
        assertEquals(polls + 1, claimsOf("shop/audit"), "one worker polls audit per tick: " + queue.claims());
    }

    /**
     * After a failed v2 (rolled back, or with flows FAILED), close stops every consumer the node ever built exactly once: those v2
     * replaced (phase A), v2's own (its failure), the rollback's (close, or the rollback's own failure) — a FAILED flow has nothing
     * left to stop. Every producer ends closed, each once.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"rolled back", "FAILED"})
    void closeAfterAFailedDeployStopsEachConsumerOnce(String outcome) throws Exception {
        deploy(shop(), "r1");
        failV2(outcome);

        OnVirtualThread.call(() -> {
            deployments.close();
            return null;
        });

        for (Doors.Door x : doors.doors()) assertEquals(1, x.stops(), x + " is stopped once: " + x.history());
        until(() -> doors.openProducers().isEmpty(), "every producer is closed: " + doors.producers());
        assertNothingClosedTwice();
    }

    // --- the listing while a deploy runs (D-063 "Listing", d2c r3): atomic to readers

    /**
     * v1 runs a and b; v2 (2.0.0, r2) changes b. While phase A stops v1's b (its stop held), and again while phase B starts v2's
     * b (its start held) — after phase A settled — {@code flows()} and {@code packages()} answer what they answered before v2:
     * b RUNNING under v1's record and hash, not FAILED, and v1's package record. {@code release}: v2's b starts, and the listing
     * changes once the deploy returns — b RUNNING with v2's hash and stamps, a as it was, the new package record.
     * {@code fail}: v2's b does not start; after the 500 b runs again, and the listing is the one before v2.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"release", "fail"})
    void theListingDuringPhaseBIsTheVersionBefore(String outcome) throws Exception {
        deploy(syncFlows("a", "b"), "r1");
        List<DeployedFlow> flowsBefore = deployments.flows();
        List<DeployedPackage> packagesBefore = deployments.packages();
        DeployedFlow bBefore = byId(flowsBefore).get("shop/b");
        PackageDraft v2 = changed(syncFlows("a", "b"), "b", "v2");
        v2.manifest().put("version", "2.0.0");
        Doors.Gate stopOfB = doors.holdStopOf("shop/b");     // phase A: v1's b
        Doors.Gate startOfB = doors.holdStartOf("shop/b");   // phase B: v2's b

        OnVirtualThread.Call<DeployResult> d = deploying(v2, "r2");

        reachedWhile(d, stopOfB.reached(), "phase A calls v1 b's stop");
        assertEquals(flowsBefore, deployments.flows(), "during phase A the flows are listed as before v2");
        assertEquals(packagesBefore, deployments.packages(), "during phase A the package record is v1's");
        stopOfB.release();
        reachedWhile(d, startOfB.reached(), "phase B calls v2 b's start");
        assertTrue(stopOfB.door().stopped() && startOfB.door() != stopOfB.door(), "fixture: phase A stopped v1's b, phase B starts v2's");
        assertEquals(flowsBefore, deployments.flows(), "during phase B the flows are listed as before v2: b RUNNING under v1's record and hash, not FAILED");
        assertEquals(packagesBefore, deployments.packages(), "during phase B the package record is v1's");

        if (outcome.equals("release")) {
            startOfB.release();
            DeployResult r = OnVirtualThread.join(d);
            assertResult(List.of(), List.of("shop/b"), List.of(), r);
            Map<String, DeployedFlow> after = byId(deployments.flows());
            assertEquals(byId(flowsBefore).get("shop/a"), after.get("shop/a"), "a runs on as it was");
            DeployedFlow b = after.get("shop/b");
            assertEquals(DeployedFlow.State.RUNNING, b.state(), "b runs: " + b);
            assertFalse(bBefore.hash().equals(b.hash()), "b is listed with v2's hash once the deploy returned: " + b + " (v1: " + bBefore + ")");
            assertEquals("2.0.0", b.packageVersion(), "with v2's stamps: " + b);
            assertEquals(Optional.of("r2"), b.revision(), "with v2's stamps: " + b);
            assertEquals(List.of(r.pkg()), deployments.packages(), "the package record is v2's once the deploy returned");
        } else {
            startOfB.fail("address in use");
            DeployRefusedException refused = joinRefused(d, "v2, whose b does not start");
            assertEquals(Kind.FAILED, refused.kind(), refused.error() + " " + refused.problems());
            assertEquals("version 2.0.0 did not start; 1.0.0 runs again", refused.error(), "no flow is FAILED");
            assertEquals(flowsBefore, deployments.flows(), "after the 500 b runs again: RUNNING under v1's record and hash, as before v2");
            assertEquals(packagesBefore, deployments.packages(), "the package record is v1's");
            assertEquals(1, live("shop/b").size(), "one consumer of b runs, the rollback's: " + doors.events());
        }
    }

    /**
     * v1 runs a and b; v2 (2.0.0, r2) changes b, whose start fails; the rollback's start of b, built again from v1's directory, is
     * held. While it is held — v2's b stopped, v1's b built again — {@code flows()} and {@code packages()} answer what they
     * answered before v2: b RUNNING under v1's record and hash, not FAILED, and v1's package record. Released, b runs again: the
     * deploy is refused (500) naming no FAILED flow, the listing is the one before v2, b's running consumer is the one the
     * rollback started and a's runs on untouched; close then stops every consumer once — the listing holds a's object and b's
     * restarted one.
     */
    @Test
    void theListingDuringTheRollbackIsTheVersionBefore() throws Exception {
        deploy(syncFlows("a", "b"), "r1");
        List<DeployedFlow> flowsBefore = deployments.flows();
        List<DeployedPackage> packagesBefore = deployments.packages();
        Doors.Door aDoor = doors.door("shop/a");
        PackageDraft v2 = changed(syncFlows("a", "b"), "b", "v2");
        v2.manifest().put("version", "2.0.0");
        Doors.Gate startOfV2 = doors.failStartOf("shop/b", "address in use");   // v2's b
        Doors.Gate restart = doors.holdStartOf("shop/b");                       // the rollback's b

        OnVirtualThread.Call<DeployResult> d = deploying(v2, "r2");

        reachedWhile(d, restart.reached(), "the rollback calls the start of v1's b, built again");
        assertTrue(startOfV2.door() != null && startOfV2.door().stopped() && restart.door() != startOfV2.door(),
                "fixture: the rollback stopped v2's b before it started v1's b again: " + doors.events());
        assertEquals(flowsBefore, deployments.flows(), "during the rollback the flows are listed as before v2: b RUNNING under v1's record and hash, not FAILED");
        assertEquals(packagesBefore, deployments.packages(), "during the rollback the package record is v1's");

        restart.release();
        DeployRefusedException refused = joinRefused(d, "v2, whose b does not start");

        assertEquals(Kind.FAILED, refused.kind(), refused.error() + " " + refused.problems());
        assertEquals("version 2.0.0 did not start; 1.0.0 runs again", refused.error(), "no flow is FAILED");
        assertEquals(flowsBefore, deployments.flows(), "after the 500 b runs again: RUNNING under v1's record and hash, as before v2");
        assertEquals(packagesBefore, deployments.packages(), "the package record is v1's");
        assertEquals(List.of(restart.door()), live("shop/b"), "b's running consumer is the one the rollback started: " + doors.events());
        assertTrue(aDoor == doors.door("shop/a") && !aDoor.stopped(), "a's consumer runs on, untouched: " + aDoor.history());

        OnVirtualThread.call(() -> {
            deployments.close();
            return null;
        });

        for (Doors.Door x : doors.doors()) assertEquals(1, x.stops(), x + " is stopped once — the listing holds a's object and the one the rollback restarted: " + x.history());
        until(() -> doors.openProducers().isEmpty(), "every producer is closed: " + doors.producers());
        assertNothingClosedTwice();
    }

    /**
     * v1 runs a and b; v2 changes b, whose start fails in v2 and again in the rollback: b is FAILED. v3 (3.0.0) changes a and
     * still has b, which it adds (nothing of the FAILED b runs); v3's b starts, v3's a does not. The rollback stops v3's a and b
     * and starts v1's a again: the 500 names b — FAILED before v3 — among the FAILED flows of the package, and the listing is the
     * one before v3: a RUNNING under v1's record, b still FAILED under its v1 record, the package record v1's. Nothing of b runs;
     * close then stops every consumer once.
     */
    @Test
    void anEarlierFAILEDFlowStaysFAILEDThroughARefusedRollback() throws Exception {
        deploy(syncFlows("a", "b"), "r1");
        PackageDraft v2 = changed(syncFlows("a", "b"), "b", "v2");
        v2.manifest().put("version", "2.0.0");
        doors.failStartOf("shop/b", "address in use");   // v2's b
        doors.failStartOf("shop/b", "still in use");     // the rollback's b
        DeployRefusedException first = refused(v2, "r2");
        assertEquals(List.of("shop/b"), failedIn(first.error()), "fixture: b is FAILED: " + first.error());
        List<DeployedFlow> flowsBefore = deployments.flows();
        List<DeployedPackage> packagesBefore = deployments.packages();
        PackageDraft v3 = changed(syncFlows("a", "b"), "a", "v3");
        v3.manifest().put("version", "3.0.0");
        Doors.Gate startOfA = doors.failStartOf("shop/a", "a's address in use");   // v3's a; v3's b starts

        DeployRefusedException refused = refused(v3, "r3");

        Doors.Door bV3 = doors.door("shop/b");
        assertTrue(bV3.started() && startOfA.door() != null, "fixture: v3 started b and called a's start: " + doors.events());
        assertEquals(Kind.FAILED, refused.kind(), refused.error() + " " + refused.problems());
        assertEquals("version 3.0.0 did not start; 1.0.0 runs again; flows [shop/b] FAILED", refused.error(),
                "the 500 names every FAILED flow of the package, b's from before v3 too");
        assertEquals(flowsBefore, deployments.flows(), "the listing is the one before v3: a RUNNING under v1's record, b FAILED under its v1 record");
        assertEquals(DeployedFlow.State.FAILED, byId(deployments.flows()).get("shop/b").state(), "b stays FAILED");
        assertEquals(packagesBefore, deployments.packages(), "the package record is v1's");
        assertTrue(bV3.stopped(), "v3's b is stopped: " + bV3.history());
        assertEquals(List.of(), live("shop/b"), "nothing of b runs: " + doors.events());
        assertEquals(1, live("shop/a").size(), "a runs again: " + doors.events());

        OnVirtualThread.call(() -> {
            deployments.close();
            return null;
        });

        for (Doors.Door x : doors.doors()) assertEquals(1, x.stops(), x + " is stopped once: " + x.history());
        until(() -> doors.openProducers().isEmpty(), "every producer is closed: " + doors.producers());
        assertNothingClosedTwice();
    }

    // --- bounds: a start or a stop that never completes

    /**
     * A consumer start that never completes, on a first deploy (one flow, {@code b}) and on a redeploy that adds {@code b} beside
     * a running {@code a}: the start is bounded by the node's stop deadline on the node's timer — not failed a millisecond before
     * it, a failed start at it (500). When the held start then succeeds late, the deploy stops that consumer after it started.
     * On the redeploy {@code a} runs on untouched.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"first deploy", "redeploy"})
    void aStartThatNeverCompletesIsAFailedStartAndALateSuccessIsStopped(String when) throws Exception {
        PackageDraft target = syncFlows("b");
        Doors.Door a = null;
        if (when.equals("redeploy")) {
            deploy(syncFlows("a"), "r1");
            a = doors.door("shop/a");
            target = syncFlows("a", "b");
            target.manifest().put("version", "2.0.0");
        }
        Doors.Gate stuck = doors.holdStartOf("shop/b");
        int tasks = timer.all().size();

        OnVirtualThread.Call<DeployResult> d = deploying(target, "r2");

        reachedWhile(d, stuck.reached(), "the deploy calls b's start");
        Doors.Door b = stuck.door();
        untilWhile(d, () -> liveTasksSince(tasks, STOP_DEADLINE) == 1, "b's start is bounded by the node's stop deadline " + STOP_DEADLINE + " on its timer: " + timer.all());
        rt.clock().advance(STOP_DEADLINE.minusMillis(1));
        assertFalse(d.isDone(), "not failed a millisecond before the stop deadline");
        assertEquals(List.of("start"), b.history(), "b's start is still pending and nothing else happened to it");
        rt.clock().advance(Duration.ofMillis(1));
        DeployRefusedException refused = joinRefused(d, "the start that never completes, at the stop deadline");

        assertEquals(Kind.FAILED, refused.kind(), refused.error() + " " + refused.problems());
        String said = refused.error() + " " + refused.problems();
        assertTrue(said.contains("shop/b") || said.contains("flow 'b'"), "the refusal names b: " + said);
        if (a != null) {
            assertTrue(refused.error().contains("version 2.0.0 did not start") && refused.error().contains("1.0.0 runs again"), refused.error());
            assertTrue(a == doors.door("shop/a") && !a.stopped(), "a runs on untouched");
            assertEquals(List.of("shop/a"), deployments.flows().stream().map(DeployedFlow::id).toList());
        } else {
            assertEquals(List.of(), deployments.flows());
        }
        assertEquals(List.of("start"), b.history(), "the refusal and its cleanup leave b's pending start alone: nothing stops a consumer still starting");

        stuck.release();

        until(() -> b.first("started") > 0 && b.last("stop") > b.first("started"), "b's late start is followed by a stop: " + b.history());
        assertEquals(1, b.stops(), "b is stopped once, after its late start: " + b.history());
        assertTrue(b.first("stop") > b.first("started"), "b's first stop follows its started: " + b.history());
    }

    /**
     * Phase A's consumer stop is bounded by the node's stop deadline too: v1's {@code a} consumer stop never completes; at the
     * deadline (not a millisecond before) the swap goes on with a WARNING naming the flow — v2's {@code a} starts and serves.
     */
    @Test
    void aStopThatNeverCompletesWarnsAndTheSwapContinues() throws Exception {
        deploy(syncFlows("a"), "r1");
        Doors.Door v1 = doors.door("shop/a");
        Doors.Gate stuck = doors.holdStopOf("shop/a");
        int tasks = timer.all().size();
        int mark = mark();

        OnVirtualThread.Call<DeployResult> d = deploying(changed(syncFlows("a"), "a", "v2"), "r2");

        reachedWhile(d, stuck.reached(), "phase A stops v1's a");
        assertEquals(v1, stuck.door(), "fixture: the held stop is v1's");
        untilWhile(d, () -> liveTasksSince(tasks, STOP_DEADLINE) == 1, "the stop is bounded by the node's stop deadline " + STOP_DEADLINE + " on its timer: " + timer.all());
        rt.clock().advance(STOP_DEADLINE.minusMillis(1));
        assertFalse(d.isDone(), "the swap waits a millisecond before the deadline");
        assertFalse(since(mark).contains("start shop/a"), "v2's a has not started: " + since(mark));
        rt.clock().advance(Duration.ofMillis(1));
        DeployResult r = OnVirtualThread.join(d);

        assertResult(List.of(), List.of("shop/a"), List.of(), r);
        assertFalse(warningsNaming("shop/a").isEmpty(), "a WARNING names the flow whose stop did not complete: "
                + logged.stream().map(DeploymentsProducersTest::texts).toList());
        Doors.Door v2 = doors.door("shop/a");
        assertTrue(v2 != v1 && v2.started(), "v2's a started");
        assertInstanceOf(Ack.Reply.class, within(send(v2).answer(), "v2's a"), "and serves");
    }

    /**
     * A failed start's cleanup stop is bounded the same way (D-063, decision 9): v2 (2.0.0) adds {@code b}; b's start fails and
     * b's consumer stop then never completes. The deploy waits for that stop until the node's stop deadline on its timer — not
     * answered a millisecond before — and at the deadline goes on: FAILED (500), "1.0.0 runs again", the flows and the package
     * record as before v2, {@code a} running untouched and serving.
     */
    @Test
    void aCleanupStopThatNeverCompletesIsBoundedAndTheRollbackGoesOn() throws Exception {
        deploy(syncFlows("a"), "r1");
        Doors.Door a = doors.door("shop/a");
        List<DeployedFlow> flowsBefore = deployments.flows();
        List<DeployedPackage> packagesBefore = deployments.packages();
        PackageDraft v2 = syncFlows("a", "b");
        v2.manifest().put("version", "2.0.0");
        Doors.Gate starting = doors.holdStartOf("shop/b");
        int tasks = timer.all().size();

        OnVirtualThread.Call<DeployResult> d = deploying(v2, "r2");

        reachedWhile(d, starting.reached(), "phase B starts b");
        Doors.Door b = starting.door();
        untilWhile(d, () -> liveTasksSince(tasks, STOP_DEADLINE) == 1, "fixture: b's start bound is armed before b's start fails: " + timer.all());
        Doors.Gate stuck = doors.holdStopOf("shop/b");
        int cleanup = timer.all().size();
        starting.fail("address in use");

        reachedWhile(d, stuck.reached(), "the failed start's cleanup stops b");
        assertTrue(stuck.door() == b, "fixture: the held stop is b's");
        untilWhile(d, () -> liveTasksSince(cleanup, STOP_DEADLINE) == 1,
                "b's cleanup stop is bounded by the node's stop deadline " + STOP_DEADLINE + " on its timer: " + timer.all());
        rt.clock().advance(STOP_DEADLINE.minusMillis(1));
        assertFalse(d.isDone(), "the deploy waits for b's stop a millisecond before the deadline");
        rt.clock().advance(Duration.ofMillis(1));
        DeployRefusedException refused = joinRefused(d, "b's cleanup stop that never completes, at the stop deadline");

        assertEquals(Kind.FAILED, refused.kind(), refused.error() + " " + refused.problems());
        assertTrue(refused.error().contains("version 2.0.0 did not start") && refused.error().contains("1.0.0 runs again"), refused.error());
        assertEquals(flowsBefore, deployments.flows(), "a is listed as before v2, b is not");
        assertEquals(packagesBefore, deployments.packages(), "the package record is v1's");
        assertTrue(a == doors.door("shop/a") && !a.stopped(), "a runs on untouched");
        assertInstanceOf(Ack.Reply.class, within(send(a).answer(), "a"), "and serves");
    }

    // --- stopping many flows tries every one

    /**
     * Audit's worker stop throws synchronously (its poll tick cannot be cancelled); audit is the first flow stopped. {@code close}:
     * close stops orders and ping all the same and closes their producers, then rethrows the failure. {@code phase A}: v2 changes
     * all three; the swap stops and closes orders and ping all the same, a WARNING names audit, and v2 runs. {@code a failed start's
     * cleanup}: v2 changes audit and orders, v2's audit started and v2's orders does not start: v2's orders is stopped and its
     * producer closed all the same, and the deploy is FAILED.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"close", "phase A", "a failed start's cleanup"})
    void oneThrowingStopDoesNotSkipTheOthers(String where) throws Exception {
        deploy(shop(), "r1");
        Doors.Door orders = doors.door("shop/orders");
        Doors.Door ping = doors.door("shop/ping");
        Doors.Out ordersOut = producerOf(within(sendAndAnswer("shop/orders"), "orders"));
        Doors.Out pingOut = producerOf(within(sendAndAnswer("shop/ping"), "ping"));
        assertTrue(ordersOut != pingOut, "fixture: orders and ping each have a producer");
        switch (where) {
            case "close" -> {
                tickCancelsThrow = true;
                Exception thrown = assertThrows(Exception.class, () -> OnVirtualThread.call(() -> {
                    deployments.close();
                    return null;
                }), "close rethrows audit's stop failure");
                for (Doors.Door x : List.of(orders, ping)) assertTrue(x.stopped(), x + " is stopped all the same: " + doors.events());
                for (Doors.Out o : List.of(ordersOut, pingOut)) assertEquals(1, o.closes(), "its producer is closed all the same: " + o);
                List<String> chain = new ArrayList<>();
                for (Throwable t = thrown; t != null; t = t.getCause()) chain.add(String.valueOf(t.getMessage()));
                assertTrue(chain.stream().anyMatch(m -> m.contains(TICK_STUCK)), "the failure rethrown is audit's: " + chain);
            }
            case "phase A" -> {
                tickCancelsThrow = true;
                OnVirtualThread.Call<DeployResult> d = deploying(changed(changed(changed(shop(), "audit", "v2"), "orders", "v2"), "ping", "v2"), "r2");
                until(d::isDone, "the redeploy answers");
                tickCancelsThrow = false;
                for (Doors.Door x : List.of(orders, ping)) assertTrue(x.stopped(), x + " is stopped all the same: " + doors.events());
                until(() -> ordersOut.closes() == 1 && pingOut.closes() == 1, "their producers are closed all the same: " + doors.producers());
                assertResult(List.of(), List.of("shop/audit", "shop/orders", "shop/ping"), List.of(), OnVirtualThread.join(d));
                assertFalse(warningsNaming("shop/audit").isEmpty(), "a WARNING names audit, whose stop threw: " + logged.stream().map(DeploymentsProducersTest::texts).toList());
                assertInstanceOf(Ack.Reply.class, within(send("shop/orders").answer(), "v2's orders"), "v2 runs");
            }
            case "a failed start's cleanup" -> {
                int producersBefore = doors.producers().size();
                Doors.Gate ordersStart = doors.holdStartOf("shop/orders");
                OnVirtualThread.Call<DeployResult> d = deploying(changed(changed(shop(), "audit", "v2"), "orders", "v2"), "r2");
                reachedWhile(d, ordersStart.reached(), "phase B calls v2 orders' start");
                List<Doors.Out> v2Partner = doors.producers().subList(producersBefore, doors.producers().size()).stream()
                        .filter(o -> o.endpoint().equals("partner")).toList();   // v2's build is done: orders' delivery producer, ping's spare
                Doors.Door ordersV2 = ordersStart.door();
                Doors.Door auditV2 = doors.door("shop/audit");
                untilWhile(d, () -> auditV2.started() && liveTasks(TICK) == 1, "v2's audit and its worker started: " + timer.all());
                tickCancelsThrow = true;
                ordersStart.fail("address in use");
                DeployRefusedException refused = joinRefused(d, "v2, whose orders does not start");
                tickCancelsThrow = false;
                assertTrue(ordersV2.stopped(), "v2's orders is stopped all the same: " + ordersV2.history());
                assertEquals(2, v2Partner.size(), "fixture: v2's build opened orders' and ping's partner producers: " + doors.producers());
                until(() -> v2Partner.stream().allMatch(o -> o.closes() == 1), "every partner producer v2's build opened is closed all the same, orders' among them: "
                        + doors.producers());
                assertEquals(Kind.FAILED, refused.kind(), refused.error());
            }
            default -> throw new IllegalArgumentException(where);
        }
        assertNothingClosedTwice();
    }

    /** A message through {@code flow}'s consumer, whose answer is not a Nack; returns it as sent. */
    Future<Sent> sendAndAnswer(String flow) {
        Sent s = send(flow);
        return s.answer().map(a -> {
            if (a instanceof Ack.Nack) throw new IllegalStateException(flow + " answered " + a);
            return s;
        });
    }

    // --- disk

    /**
     * A file of a kept directory the deploy cannot read (replaced by a link to {@code /proc/self/mem}: read, it fails with EIO
     * whoever runs the test) is a mismatch, not the node's failure: the deploy of that directory's archive unpacks it afresh over
     * the directory and stands. The directory is the running version's, or the previous one.
     */
    @ParameterizedTest(name = "{0} dir")
    @ValueSource(strings = {"running", "previous"})
    void anUnreadableKeptFileIsAMismatch(String kept) throws Exception {
        PackageDraft v1 = shop();
        byte[] zip1 = v1.zip();
        String sha1 = deploy(zip1, "r1").pkg().archiveSha256();
        List<String> swapped = List.of();
        if (kept.equals("previous")) {
            assertResult(List.of(), List.of("shop/orders"), List.of(), deploy(changed(shop(), "orders", "v2"), "r2"));
            swapped = List.of("shop/orders");
        }
        Path dir = packageDir(sha1);
        Path yaml = dir.resolve("flows/orders.yaml");
        Files.delete(yaml);
        Files.createSymbolicLink(yaml, Path.of("/proc/self/mem"));
        assertThrows(IOException.class, () -> Files.readAllBytes(yaml), "fixture: the kept file cannot be read");

        DeployResult again = deploy(zip1, "r3");

        assertResult(List.of(), swapped, List.of(), again);
        assertFalse(Files.isSymbolicLink(yaml), "the archive was unpacked afresh over the kept directory");
        Map<String, String> onDisk = new TreeMap<>();
        for (String f : regularFiles(dir)) onDisk.put(f, PackageDraft.sha256(dir.resolve(f)));
        Map<String, String> archived = new TreeMap<>();
        v1.files().forEach((path, bytes) -> archived.put(path, PackageDraft.sha256(bytes)));
        assertEquals(archived, onDisk, "the directory holds exactly the archive's files");
        assertEquals(List.of(), children(deployments.incomingDir()));
        assertInstanceOf(Ack.Reply.class, within(send("shop/orders").answer(), "orders' answer"), "orders serves");
    }

    /**
     * v1 runs {@code a}; v2 changes it: v1's directory is the previous one. v2's archive deployed again (the running archive) keeps
     * v1's as the previous one — the same directory, still recorded as unpacked: v1's archive deployed next reuses it as it is.
     */
    @Test
    void aRePutOfTheSameArchiveKeepsTheRealPreviousDir() throws Exception {
        byte[] zip1 = changed(syncFlows("a"), "a", "v1").zip();
        byte[] zip2 = changed(syncFlows("a"), "a", "v2").zip();
        String sha1 = deploy(zip1, "r1").pkg().archiveSha256();
        String sha2 = deploy(zip2, "r2").pkg().archiveSha256();
        Object key1 = fileKey(packageDir(sha1));
        List<String> files1 = regularFiles(packageDir(sha1));
        assertEquals(sorted(sha1, sha2), children(shopDir()), "fixture: current and previous");

        DeployResult again = deploy(zip2, "r3");

        assertResult(List.of(), List.of(), List.of(), again);
        assertEquals(sorted(sha1, sha2), children(shopDir()), "v1's directory stays the previous one: the current one is not its own previous");
        assertEquals(key1, fileKey(packageDir(sha1)), "the same directory");
        assertEquals(files1, regularFiles(packageDir(sha1)), "whole");

        DeployResult back = deploy(zip1, "r4");

        assertResult(List.of(), List.of("shop/a"), List.of(), back);
        assertEquals(key1, fileKey(packageDir(sha1)), "v1's archive reuses its kept directory, as unpacked");
        assertEquals(sorted(sha1, sha2), children(shopDir()), "current v1, previous v2");
    }

    /**
     * v1 runs {@code a}; v2 changes it: v1's directory is the previous one. v3 changes it again; v3's a does not start, nor does
     * v2's a in the rollback: a is FAILED. The record the rollback leaves keeps v2's previous directory, so v2's archive deployed
     * again (the running one) adds a and still keeps v1's as the previous one — the same directory, not pruned — and v1's archive
     * deployed next reuses it as it is.
     */
    @Test
    void aRePutAfterAFailedDeployKeepsTheRealPreviousDir() throws Exception {
        byte[] zip1 = changed(syncFlows("a"), "a", "v1").zip();
        PackageDraft v2 = changed(syncFlows("a"), "a", "v2");
        v2.manifest().put("version", "2.0.0");
        byte[] zip2 = v2.zip();
        String sha1 = deploy(zip1, "r1").pkg().archiveSha256();
        String sha2 = deploy(zip2, "r2").pkg().archiveSha256();
        Object key1 = fileKey(packageDir(sha1));
        List<String> files1 = regularFiles(packageDir(sha1));
        assertEquals(sorted(sha1, sha2), children(shopDir()), "fixture: current and previous");
        PackageDraft v3 = changed(syncFlows("a"), "a", "v3");
        v3.manifest().put("version", "3.0.0");
        doors.failStartOf("shop/a", "address in use");   // v3's a
        doors.failStartOf("shop/a", "still in use");     // v2's a, in the rollback
        DeployRefusedException refused = refused(v3, "r3");
        assertEquals(List.of("shop/a"), failedIn(refused.error()), "fixture: a is FAILED: " + refused.error());
        assertEquals(sorted(sha1, sha2), children(shopDir()), "fixture: v3's directory is deleted");

        DeployResult again = deploy(zip2, "r4");

        assertResult(List.of("shop/a"), List.of(), List.of(), again);
        assertEquals(sha2, again.pkg().archiveSha256(), "fixture: the running archive again");
        assertEquals(sorted(sha1, sha2), children(shopDir()), "v1's directory stays the previous one: the failed deploy did not lose it");
        assertEquals(key1, fileKey(packageDir(sha1)), "the same directory");
        assertEquals(files1, regularFiles(packageDir(sha1)), "whole");

        DeployResult back = deploy(zip1, "r5");

        assertResult(List.of(), List.of("shop/a"), List.of(), back);
        assertEquals(key1, fileKey(packageDir(sha1)), "v1's archive reuses its kept directory, as unpacked");
        assertEquals(sorted(sha1, sha2), children(shopDir()), "current v1, previous v2");
    }
}
