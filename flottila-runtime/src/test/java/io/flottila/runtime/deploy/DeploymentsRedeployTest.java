package io.flottila.runtime.deploy;

import io.flottila.api.Ack;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Runtime;
import io.flottila.api.StepFactory;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.flow.WorkerPolicy;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Snapshot;
import io.flottila.runtime.deploy.DeployRefusedException.Kind;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.steps.validate.XsdValidate;
import io.flottila.steps.xslt.SaxonXml;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.flottila.runtime.deploy.DeploymentsTest.children;
import static io.flottila.runtime.deploy.DeploymentsTest.joinRefused;
import static io.flottila.runtime.deploy.DeploymentsTest.regularFiles;
import static io.flottila.runtime.deploy.DeploymentsTest.until;
import static io.flottila.runtime.deploy.DeploymentsTest.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P1-09d2b (D-063, F-5 amended, FIX-32, P1-09e): a deploy of a package already running is a redeploy. Every flow of the new
 * version is built and hashed before anything stops; each flow is added (a new id), unchanged (the same effective hash: its
 * running objects and its {@code DeployedFlow} stamps are not touched, the new build is closed and no consumer is built for
 * it), changed, or removed. Under the node-wide deploy lock phase A stops every changed and removed flow at once — worker stop;
 * delivery stop with the node's stop deadline and consumer stop; delivery close awaited; worker close asked — and only once all
 * of A settled, phase B starts every changed and added flow: consumer start, then worker start. An invalid version, or one that
 * removes an at-least-once flow (or makes it non-at-least-once) while the queue holds items of it, is 422 before anything stops.
 * A removed at-least-once flow is checked again once its worker drained: items left are a WARNING, never a delay. The kept
 * directory of the running archive is reused; the data directory keeps the current, the previous, and every directory a
 * running flow was built from.
 *
 * <p>One log at the SPI and timer boundary orders it all ({@link #log}): the {@link Doors} events ({@code consumer|start|stop
 * <flow>}, {@code stopped <flow>} when a stop() future completed, {@code close <endpoint> #<n>} at a producer close) and the
 * {@link TracingTimer}'s {@code schedule|cancel <delay>} — a worker's start schedules its poll tick ({@link #TICK}), its stop
 * cancels it. The {@link CountingQueue} records the items each claim handed out and every {@code hasItems} call with the rows
 * of that flow at the call. Everything runs in memory on a fake clock: nothing waits on wall time.
 *
 * <p>The shape assumed (P1-09d2b test-author): {@code Deployments.deploy(String, Optional<String>, Path)} returns
 * {@code DeployResult(DeployedPackage pkg, List<String> added, List<String> swapped, List<String> removed)}, a record in this
 * package, each list sorted by qualified flow id; a refusal is {@link Kind#INVALID} (422) for the new problems and keeps a
 * cleanup failure as a suppressed exception ({@code DeployRefusedException} enables suppression, FIX-32).
 */
class DeploymentsRedeployTest {

    static final String NODE = "node-1";
    static final Duration STOP_DEADLINE = Duration.ofSeconds(37);
    static final Duration TICK = WorkerPolicy.DEFAULT.pollInterval();
    static final Duration LEASE = WorkerPolicy.DEFAULT.lease();
    static final String XSL = "<xsl:stylesheet xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\" version=\"3.0\">";

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
        SaxonXml xml = new SaxonXml();
        List<StepFactory> steps = Stream.concat(xml.steps().stream(), new XsdValidate().steps().stream()).toList();
        Runtime runtime = new Runtime(rt.clock(), timer, rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
        deployments = new Deployments(NodeConfig.from(env), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), timer, steps, xml.languages(), messages, dlq,
                queue, () -> 0L);
        capture.setLevel(Level.ALL);
        flottila.addHandler(capture);
    }

    @AfterEach
    void releaseWhatACaseHeld() {
        for (CountDownLatch l : holds) l.countDown();
        doors.releaseAll();
        flottila.removeHandler(capture);
    }

    // --- fixture: packages

    /**
     * {@code shop} 1.0.0 as {@link DeploymentsTest#shop}: {@code orders} (sync, 2.1.0) and {@code ping} (at-most-once) send to
     * {@code partner}; {@code audit} (at-least-once, 1.0.3) sends to its own out endpoint {@code archive}, so each kind of flow
     * has a producer of its own endpoint.
     */
    static PackageDraft shop() {
        PackageDraft p = DeploymentsTest.shop("shop").endpoint("archive", "out", new JsonObject().put("url", "rec://archive"));
        p.flowDoc("audit").put("steps", to("archive"));
        return p;
    }

    static JsonArray to(String endpoint) {
        return new JsonArray().add(new JsonObject().put("type", "to").put("name", "send").put("endpoint", endpoint));
    }

    /** {@code p} with flow {@code id}'s yaml changed (its description): that flow's hash moves, no other's. */
    static PackageDraft changed(PackageDraft p, String id, String text) {
        p.flowDoc(id).put("description", text);
        return p;
    }

    /** {@code p} without flow {@code id} and its in endpoint {@code <id>-in}. */
    static PackageDraft removed(PackageDraft p, String id) {
        return p.without("flows/" + id + ".yaml").without("endpoints/" + id + "-in.yaml");
    }

    static String name(PackageDraft p) {
        return p.manifest().getString("name");
    }

    Path packageDir(String sha256) {
        return data.resolve("packages").resolve("shop").resolve(sha256);
    }

    // --- fixture: deploys

    DeployResult deploy(PackageDraft p) throws Exception {
        return deploy(name(p), Optional.empty(), p.zipTo(archives));
    }

    DeployResult deploy(PackageDraft p, String revision) throws Exception {
        return deploy(name(p), Optional.of(revision), p.zipTo(archives));
    }

    DeployResult deploy(String name, Optional<String> revision, Path archive) throws Exception {
        return OnVirtualThread.call(() -> deployments.deploy(name, revision, archive));
    }

    OnVirtualThread.Call<DeployResult> deploying(PackageDraft p) {
        Path archive = p.zipTo(archives);
        return OnVirtualThread.start(() -> deployments.deploy(name(p), Optional.empty(), archive));
    }

    /**
     * {@link DeploymentsTest#until} while the deploy {@code d} runs: a deploy that ends before {@code condition} holds fails the case
     * at once, naming how it ended.
     */
    static void untilWhile(OnVirtualThread.Call<?> d, java.util.function.BooleanSupplier condition, String what) {
        until(() -> condition.getAsBoolean() || d.isDone(), what);
        if (!condition.getAsBoolean()) fail(what + ": the deploy ended first, " + ended(d));
    }

    /** {@code latch} reached while the deploy {@code d} runs; a deploy that ends first fails the case at once. */
    static void reachedWhile(OnVirtualThread.Call<?> d, CountDownLatch latch, String what) {
        untilWhile(d, () -> latch.getCount() == 0, what);
    }

    static String ended(OnVirtualThread.Call<?> d) {
        try {
            return "answering " + d.outcome().getNow(null);
        } catch (java.util.concurrent.CompletionException | java.util.concurrent.CancellationException e) {
            return "failing with " + (e.getCause() == null ? e : e.getCause());
        }
    }

    DeployRefusedException refused(String name, Path archive) {
        return assertThrows(DeployRefusedException.class, () -> OnVirtualThread.call(() -> deployments.deploy(name, Optional.empty(), archive)),
                "the deploy of " + name + " is refused");
    }

    static void assertResult(List<String> added, List<String> swapped, List<String> removed, DeployResult r) {
        assertEquals(added, r.added(), "added, sorted by qualified id: " + r);
        assertEquals(swapped, r.swapped(), "swapped, sorted by qualified id: " + r);
        assertEquals(removed, r.removed(), "removed, sorted by qualified id: " + r);
    }

    static Map<String, DeployedFlow> byId(List<DeployedFlow> flows) {
        Map<String, DeployedFlow> m = new TreeMap<>();
        flows.forEach(f -> m.put(f.id(), f));
        return m;
    }

    // --- fixture: messages

    /** A message delivered through a consumer, and its answer. */
    record Sent(Envelope e, Future<Ack> answer) {
        String id() { return e.id(); }
    }

    Envelope envelope(String flow) {
        return rt.ingress(flow, Headers.empty(), Buffer.buffer("<order/>"));
    }

    Sent send(Doors.Door door) {
        Envelope e = envelope(door.flow());
        return new Sent(e, door.deliver(e));
    }

    /** Through the consumer built last for {@code flow}. */
    Sent send(String flow) {
        return send(doors.door(flow));
    }

    /** The next send to the partner is held until the case ends or counts the latch down. */
    CountDownLatch hold() {
        CountDownLatch l = doors.partner().holdNext(1);
        holds.add(l);
        return l;
    }

    /** The producer that sent {@code s}: waits for it (an at-least-once message is sent by its worker after the accept). */
    Doors.Out producerOf(Sent s) {
        until(() -> doors.producers().stream().anyMatch(o -> o.sentIds().contains(s.id())), "message " + s.id() + " reaches a producer");
        return doors.producers().stream().filter(o -> o.sentIds().contains(s.id())).findFirst().orElseThrow();
    }

    /** The producer {@code flow}'s running consumer sends through: one message delivered, answered without a Nack, and sent. */
    Doors.Out producerOf(String flow) throws Exception {
        Sent s = send(flow);
        Ack a = within(s.answer(), flow + "'s answer");
        assertFalse(a instanceof Ack.Nack, flow + " serves: " + a);
        return producerOf(s);
    }

    /** {@code flow}'s reply body as text, through the consumer built last for it. */
    String reply(String flow) throws Exception {
        return reply(doors.door(flow));
    }

    String reply(Doors.Door door) throws Exception {
        Ack a = within(send(door).answer(), door + "'s answer");
        return assertInstanceOf(Ack.Reply.class, a, door + " replies: " + a).reply().body().whole().toString();
    }

    /** The versions on every snapshot of message {@code id}; fails when it has none or they differ. */
    Map<String, String> versions(String id) {
        until(() -> !messages.timeline(id).await().isEmpty(), "fixture: message " + id + " is recorded");
        List<Snapshot> timeline = messages.timeline(id).await();
        Set<Map<String, String>> all = timeline.stream().map(Snapshot::versions).collect(Collectors.toSet());
        assertEquals(1, all.size(), "every snapshot of " + id + " carries the same versions: " + all);
        return all.iterator().next();
    }

    static List<String> ids(List<Envelope> sent) {
        return sent.stream().map(Envelope::id).toList();
    }

    void assertNothingClosedTwice() {
        assertEquals(List.of(), doors.producersClosedTwice(), "each producer is closed once: " + doors.producers());
    }

    // --- fixture: the log

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

    static void inOrder(List<String> events, String first, String then) {
        assertTrue(at(events, first) < at(events, then), "'" + first + "' comes before '" + then + "': " + events);
    }

    /** The consumer events only: {@code consumer|start|stop <flow>}. */
    static List<String> consumerEvents(List<String> events) {
        return events.stream().filter(e -> e.startsWith("consumer ") || e.startsWith("start ") || e.startsWith("stop ")).toList();
    }

    static long count(List<String> events, String event) {
        return events.stream().filter(event::equals).count();
    }

    /** Every text a log record carries: its message, and the message of every throwable in its thrown chain and their suppressed. */
    static List<String> texts(LogRecord r) {
        return DeploymentsProducersTest.texts(r);
    }

    List<LogRecord> warningsNaming(String flow) {
        return logged.stream().filter(r -> r.getLevel() == Level.WARNING && texts(r).stream().anyMatch(t -> t.contains(flow))).toList();
    }

    List<String> hasItemsCalls(String flow) {
        return queue.hasItemsCalls().stream().filter(c -> c.startsWith(flow + " ")).toList();
    }

    // --- an invalid new version

    /**
     * v2 changes {@code audit} (it would swap) and is invalid besides — a document its schema refuses (refused at the check), a
     * flow whose pipeline does not build (refused after the build opened producers), or a consumer the component refuses. The
     * deploy is one 422 naming the problem; no running flow stops and nothing starts; flows and package are listed as before —
     * after the 422 and, d2c r3, while the deploy builds v2's consumers; v1's directory is untouched, v2's deleted; every producer
     * v2's build opened is closed, v1's stay open; v1 keeps serving.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"a document its schema refuses", "a flow whose pipeline does not build", "a consumer the component refuses"})
    void anInvalidV2Is422AndV1KeepsServing(String why) throws Exception {
        DeployedPackage v1 = deploy(shop(), "r1").pkg();
        Doors.Door orders = doors.door("shop/orders");
        Doors.Door audit = doors.door("shop/audit");
        Path v1Dir = packageDir(v1.archiveSha256());
        List<String> v1Files = regularFiles(v1Dir);
        List<DeployedFlow> flowsBefore = deployments.flows();
        List<DeployedPackage> packagesBefore = deployments.packages();
        List<Doors.Out> v1Producers = doors.producers();
        assertEquals(6, v1Producers.size(), "fixture: v1 opened a producer per flow and one per its replay (D-067): " + v1Producers);
        PackageDraft v2 = changed(shop(), "audit", "v2");
        v2.manifest().put("version", "2.0.0");
        List<String> words = switch (why) {
            case "a document its schema refuses" -> {
                v2.endpoint("spare", "sideways", new JsonObject());
                yield List.of("spare", "direction");
            }
            case "a flow whose pipeline does not build" -> {
                v2.flowDoc("orders").put("steps", to("partner").add(new JsonObject().put("type", "frobnicate").put("name", "f")));
                yield List.of("orders", "frobnicate");
            }
            case "a consumer the component refuses" -> {
                v2.endpointDoc("orders-in").getJsonObject("config").put("refuse", "no door today");
                yield List.of("orders", "no door today");
            }
            default -> throw new IllegalArgumentException(why);
        };
        Path archive = v2.zipTo(archives);
        // d2c r3 (D-063 "Listing"): what flows() and packages() answer each time v2's deploy, holding the lock, builds a consumer
        List<String> listedWhileBuilding = new CopyOnWriteArrayList<>();
        AtomicBoolean listingMoved = new AtomicBoolean();
        doors.onConsumer(cfg -> {
            List<DeployedFlow> flows = deployments.flows();
            List<DeployedPackage> packages = deployments.packages();
            listedWhileBuilding.add(cfg.name() + ": " + flows + " " + packages);
            if (!flowsBefore.equals(flows) || !packagesBefore.equals(packages)) listingMoved.set(true);
        });
        int mark = mark();

        DeployRefusedException refused = refused("shop", archive);

        assertEquals(Kind.INVALID, refused.kind(), why + ": " + refused.error() + " " + refused.problems());
        assertTrue(refused.problems().stream().anyMatch(l -> words.stream().allMatch(l::contains)), why + ": a problem names " + words + ": " + refused.problems());
        List<String> during = since(mark);
        assertEquals(List.of(), during.stream().filter(e -> e.startsWith("stop ") || e.startsWith("start ")).toList(),
                "nothing of v1 stops and nothing of v2 starts: " + during);
        if (!why.equals("a document its schema refuses")) {   // refused at the check: v2 builds nothing
            assertFalse(listedWhileBuilding.isEmpty(), "fixture: v2 builds a consumer (audit's) before it is refused: " + during);
        }
        assertFalse(listingMoved.get(), "while v2's deploy runs, the flows and the package record are listed as before it: " + listedWhileBuilding);
        assertEquals(flowsBefore, deployments.flows(), "v1's flows are listed as they were: versions, revision, hash, state");
        assertEquals(packagesBefore, deployments.packages(), "the package record is v1's");
        assertEquals(v1Files, regularFiles(v1Dir), "v1's directory is untouched");
        assertFalse(Files.exists(packageDir(PackageDraft.sha256(archive))), "v2's directory is deleted");
        assertEquals(List.of(), children(deployments.incomingDir()), "nothing is left in .incoming");
        assertEquals(v1Producers, doors.openProducers(), "every producer v2's build opened is closed, v1's are not: " + doors.producers());
        assertNothingClosedTwice();

        Sent sync = send(orders);
        assertInstanceOf(Ack.Reply.class, within(sync.answer(), "v1's sync answer"), "v1's sync flow still serves");
        assertEquals(Map.of("flow", "shop/orders@2.1.0", "package", "shop@1.0.0", "revision", "r1"), versions(sync.id()), "on v1");
        Sent alo = send(audit);
        assertEquals(new Ack.Accepted(alo.id()), within(alo.answer(), "v1's at-least-once answer"), "v1's at-least-once flow still accepts");
        assertTrue(v1Producers.contains(producerOf(alo)), "and its v1 worker sends it on");
        int claims = queue.claims().size();
        rt.clock().advance(TICK);
        assertTrue(queue.claims().size() > claims, "v1's worker still polls on its tick: " + queue.claims());
    }

    // --- the swap

    /**
     * v2 changes {@code orders} (sync) and {@code audit} (at-least-once), not {@code ping}. Both are built before anything
     * stops (no consumer for {@code ping}); audit's worker stops before its consumer; orders' delivery producer closes after its
     * consumer's stop completed; every start comes after every stop of phase A settled; audit's new worker starts after its
     * consumer; the old worker's producer is closed. {@code ping} is not touched. The new flows serve on new producers.
     */
    @Test
    void aChangedFlowSwapsInOrder() throws Exception {
        deploy(shop());
        Doors.Out ordersV1 = producerOf("shop/orders");
        Doors.Out auditV1 = producerOf("shop/audit");
        Doors.Out pingV1 = producerOf("shop/ping");
        until(() -> queue.size() == 0, "fixture: audit's message completed");
        Doors.Door ordersDoorV1 = doors.door("shop/orders");
        Doors.Door pingDoorV1 = doors.door("shop/ping");
        int built = doors.producers().size();
        PackageDraft v2 = changed(changed(shop(), "orders", "v2"), "audit", "v2");
        int mark = mark();

        DeployResult r = deploy(v2);

        assertResult(List.of(), List.of("shop/audit", "shop/orders"), List.of(), r);
        List<String> l = since(mark);
        String closeOrders = "close partner #" + ordersV1.ordinal();
        int firstStop = Math.min(at(l, "stop shop/orders"), at(l, "stop shop/audit"));
        assertTrue(at(l, "consumer shop/orders") < firstStop && at(l, "consumer shop/audit") < firstStop,
                "v2's consumers are built before anything stops: " + l);
        assertEquals(1, count(l, "cancel " + TICK), "one poll tick cancelled, v1 audit's worker's: " + l);
        inOrder(l, "cancel " + TICK, "stop shop/audit");
        inOrder(l, "stopped shop/orders", closeOrders);
        int lastOfA = Collections.max(List.of(at(l, "stopped shop/orders"), at(l, "stopped shop/audit"), at(l, closeOrders)));
        int firstOfB = Math.min(at(l, "start shop/orders"), at(l, "start shop/audit"));
        assertTrue(lastOfA < firstOfB, "phase B starts only after every stop of phase A settled, the delivery's producer close included: " + l);
        assertEquals(1, count(l, "schedule " + TICK), "one poll tick scheduled, v2 audit's worker's: " + l);
        inOrder(l, "start shop/audit", "schedule " + TICK);
        List<String> consumers = consumerEvents(l);
        assertEquals(Set.of("consumer shop/orders", "consumer shop/audit", "stop shop/orders", "stop shop/audit", "start shop/orders", "start shop/audit"),
                Set.copyOf(consumers), "the two changed flows are rebuilt, stopped and started; ping is not touched: " + l);
        assertEquals(6, consumers.size(), "each once: " + consumers);

        until(() -> auditV1.closes() > 0, "v1 audit's worker closes its producer (nothing ran on it)");
        inOrder(since(mark), "stop shop/audit", "close archive #" + auditV1.ordinal());
        assertEquals(1, ordersV1.closes(), "v1 orders' producer is closed once");
        assertEquals(0, pingV1.closes(), "ping's producer stays open");
        assertTrue(ordersDoorV1.stopped(), "v1's orders consumer is stopped");
        assertFalse(pingDoorV1.stopped(), "ping's consumer runs on");
        assertEquals(pingDoorV1, doors.door("shop/ping"), "no consumer was built for ping");

        Doors.Out ordersV2 = producerOf("shop/orders");
        Doors.Out auditV2 = producerOf("shop/audit");
        assertTrue(ordersV2.ordinal() > built && auditV2.ordinal() > built, "the swapped flows send through v2's producers: " + doors.producers());
        List<Doors.Out> open = doors.openProducers();
        assertTrue(open.containsAll(Set.of(pingV1, ordersV2, auditV2)), "open: ping's v1 producer and the swapped flows' v2 ones: " + doors.producers());
        assertEquals(List.of(2L, 4L), List.of(open.stream().filter(o -> o.ordinal() <= built).count(), open.stream().filter(o -> o.ordinal() > built).count()),
                "open: ping's v1 producers and the swapped flows' v2 ones, each flow's with its replay's (D-067); v2's build of the unchanged ping was closed: "
                        + doors.producers());
        assertNothingClosedTwice();
    }

    /**
     * v1 runs {@code a} and {@code c}; v2 renames {@code a} to {@code b} on the same in endpoint (the same port: two consumers
     * on it would share it) and changes {@code c}. A message of each is held at the partner. Phase A stops both at once: both
     * stops are called while both messages are still held, so neither stop waits for the other to settle. Once c's message is
     * answered c's stop settles while {@code a} still drains; neither {@code b} nor the new {@code c} starts before {@code a}'s
     * stop settled — after a's held message was answered by v1.
     */
    @Test
    void everyStartWaitsForEveryStop() throws Exception {
        deploy(syncFlows("a", "c"));
        Doors.Out aOut = producerOf("shop/a");
        Doors.Out cOut = producerOf("shop/c");
        CountDownLatch heldA = hold();
        Sent ma = send("shop/a");
        doors.partner().awaitSent(3);
        CountDownLatch heldC = hold();
        Sent mc = send("shop/c");
        doors.partner().awaitSent(4);
        PackageDraft v2 = syncFlows("a", "c").without("flows/a.yaml").flow("b", "1.0.0", "sync", "a-in", 1024, PackageDraft.toPartner());   // b from a's in endpoint
        changed(v2, "c", "v2");
        int mark = mark();

        OnVirtualThread.Call<DeployResult> d = deploying(v2);

        untilWhile(d, () -> since(mark).contains("stop shop/a") && since(mark).contains("stop shop/c"),
                "phase A calls a's and c's stops at once, while both of their messages are still held: " + since(mark));
        assertFalse(ma.answer().isComplete() || mc.answer().isComplete(), "fixture: a's and c's messages are both still held");
        heldC.countDown();
        assertInstanceOf(Ack.Reply.class, within(mc.answer(), "c's held message"), "c's message in flight is answered by v1");
        untilWhile(d, () -> since(mark).contains("stopped shop/c") && cOut.closes() > 0,
                "c's stop settles (its producer closed) while a still drains: " + since(mark));
        assertFalse(ma.answer().isComplete(), "fixture: a's message is still held");
        assertFalse(d.isDone(), "the deploy waits for a's stop");
        assertEquals(List.of(), since(mark).stream().filter(e -> e.startsWith("start ")).toList(), "nothing starts while a drains: " + since(mark));
        heldA.countDown();
        assertInstanceOf(Ack.Reply.class, within(ma.answer(), "a's held message"), "a's message in flight is answered by v1");
        DeployResult r = OnVirtualThread.join(d);

        assertResult(List.of("shop/b"), List.of("shop/c"), List.of("shop/a"), r);
        List<String> l = since(mark);
        int aSettled = Math.max(at(l, "stopped shop/a"), at(l, "close partner #" + aOut.ordinal()));
        assertTrue(at(l, "stopped shop/c") < at(l, "stopped shop/a"), "c's stop settled while a drained: phase A runs in parallel: " + l);
        for (String start : List.of("start shop/b", "start shop/c")) {
            assertTrue(at(l, start) > aSettled, "'" + start + "' waits until a's stop settled (its producer closed), c's own stop long done: " + l);
        }
        Doors.Door b = doors.door("shop/b");
        assertEquals("/a", b.cfg().options().get("path"), "b took a's in endpoint");
        assertTrue(b.started() && !doors.door("shop/c").stopped(), "b and the new c run");
        assertInstanceOf(Ack.Reply.class, within(send(b).answer(), "b's answer"), "b serves");
    }

    /** {@code shop} 1.0.0 with a sync flow per id, each from its own in endpoint {@code <id>-in} (path {@code /<id>}) to {@code partner}. */
    static PackageDraft syncFlows(String... ids) {
        PackageDraft p = PackageDraft.of("shop", "1.0.0").endpoint("partner", "out", new JsonObject().put("url", "rec://partner"));
        for (String id : ids) {
            p.endpoint(id + "-in", "in", new JsonObject().put("path", "/" + id)).flow(id, "1.0.0", "sync", id + "-in", 1024, PackageDraft.toPartner());
        }
        return p;
    }

    /** The one of {@code a} and {@code b} that is not {@code held}; fails the case when {@code held} is neither. */
    static String theOther(String held, String a, String b) {
        assertTrue(held.equals(a) || held.equals(b), "fixture: the held start is " + a + "'s or " + b + "'s: " + held);
        return held.equals(a) ? b : a;
    }

    /**
     * Phase B starts every changed and added flow at once: v2 changes {@code a}, adds {@code b} and keeps {@code c}. While the
     * first start phase B calls is held, the other flow's start is called too; the deploy answers once both started.
     */
    @Test
    void phaseBStartsEveryFlowAtOnce() throws Exception {
        deploy(syncFlows("a", "c"));
        PackageDraft v2 = changed(syncFlows("a", "b", "c"), "a", "v2");
        int mark = mark();
        Doors.Gate first = doors.holdStart(1);

        OnVirtualThread.Call<DeployResult> d = deploying(v2);

        reachedWhile(d, first.reached(), "phase B calls its first start");
        String held = first.door().flow();
        String other = theOther(held, "shop/a", "shop/b");
        untilWhile(d, () -> since(mark).contains("start " + other), "phase B calls " + other + "'s start while " + held + "'s is still held: " + since(mark));
        assertFalse(d.isDone(), "the deploy waits for the held start");
        first.release();
        DeployResult r = OnVirtualThread.join(d);

        assertResult(List.of("shop/b"), List.of("shop/a"), List.of(), r);
        assertTrue(doors.door("shop/a").started() && doors.door("shop/b").started(), "the new a and b run");
        assertFalse(since(mark).contains("start shop/c"), "the unchanged c is not started again: " + since(mark));
    }

    /** A first deploy starts every flow at once too: while one flow's start is held, the other's is called. */
    @Test
    void aFirstDeployStartsEveryFlowAtOnce() throws Exception {
        Doors.Gate first = doors.holdStart(1);

        OnVirtualThread.Call<DeployResult> d = deploying(syncFlows("a", "b"));

        reachedWhile(d, first.reached(), "the first deploy calls its first start");
        String held = first.door().flow();
        String other = theOther(held, "shop/a", "shop/b");
        untilWhile(d, () -> log.contains("start " + other), "the deploy calls " + other + "'s start while " + held + "'s is still held: " + log);
        assertFalse(d.isDone(), "the deploy waits for the held start");
        first.release();

        assertResult(List.of("shop/a", "shop/b"), List.of(), List.of(), OnVirtualThread.join(d));
        assertTrue(doors.door("shop/a").started() && doors.door("shop/b").started(), "both run");
    }

    /**
     * v2 bumps the version, carries another revision and changes {@code orders} only. {@code audit} and {@code ping} keep their
     * consumer, worker and producers and their {@code DeployedFlow} as it was — package version 1.0.0, revision r1, hash — and
     * their messages keep carrying those; v2's build of them is closed and its audit worker never polls. {@code orders} and the
     * package record carry v2's.
     */
    @Test
    void anUnchangedFlowIsNotTouchedAndKeepsItsStamps() throws Exception {
        deploy(shop(), "r1");
        Doors.Out ordersV1 = producerOf("shop/orders");
        Doors.Out auditV1 = producerOf("shop/audit");
        Doors.Out pingV1 = producerOf("shop/ping");
        until(() -> queue.size() == 0, "fixture: audit's message completed");
        int v1Built = doors.producers().size();
        Doors.Door pingDoor = doors.door("shop/ping");
        Doors.Door auditDoor = doors.door("shop/audit");
        Map<String, DeployedFlow> before = byId(deployments.flows());
        PackageDraft v2 = changed(shop(), "orders", "v2");
        v2.manifest().put("version", "1.0.1");
        rt.clock().advance(Duration.ofSeconds(30));
        int mark = mark();

        DeployResult r = deploy(v2, "r2");

        assertResult(List.of(), List.of("shop/orders"), List.of(), r);
        Map<String, DeployedFlow> after = byId(deployments.flows());
        assertEquals(before.get("shop/ping"), after.get("shop/ping"), "ping is listed as it was: shop@1.0.0, revision r1, its hash");
        assertEquals(before.get("shop/audit"), after.get("shop/audit"), "audit is listed as it was");
        DeployedFlow orders = after.get("shop/orders");
        assertEquals("1.0.1", orders.packageVersion(), "the swapped flow carries v2's version");
        assertEquals(Optional.of("r2"), orders.revision(), "and v2's revision");
        assertNotEquals(before.get("shop/orders").hash(), orders.hash());
        assertEquals("1.0.1", r.pkg().version());
        assertEquals(Optional.of("r2"), r.pkg().revision());
        assertEquals(rt.clock().instant(), r.pkg().deployedAt());
        assertEquals(List.of(r.pkg()), deployments.packages(), "the package record is v2's");
        assertEquals(List.of("consumer shop/orders", "stop shop/orders", "start shop/orders"), consumerEvents(since(mark)),
                "only orders is rebuilt, stopped and started");

        Sent ping = send(pingDoor);
        assertInstanceOf(Ack.Accepted.class, within(ping.answer(), "ping's answer"));
        assertEquals(pingV1, producerOf(ping), "ping sends through its v1 producer");
        assertEquals(Map.of("flow", "shop/ping@0.0.1", "package", "shop@1.0.0", "revision", "r1"), versions(ping.id()),
                "an unchanged flow's messages keep the stamps it was built with");
        Sent sync = send("shop/orders");
        assertInstanceOf(Ack.Reply.class, within(sync.answer(), "orders' answer"));
        assertEquals(Map.of("flow", "shop/orders@2.1.0", "package", "shop@1.0.1", "revision", "r2"), versions(sync.id()), "the swapped flow's carry v2's");
        Doors.Out ordersV2 = producerOf(sync);
        Sent alo = send(auditDoor);
        assertInstanceOf(Ack.Accepted.class, within(alo.answer(), "audit's answer"));
        assertEquals(auditV1, producerOf(alo), "audit's v1 worker runs it");
        until(() -> queue.size() == 0, "audit's message completed");
        List<Doors.Out> open = doors.openProducers();
        assertTrue(open.containsAll(Set.of(pingV1, auditV1, ordersV2)), "open: the unchanged flows' v1 producers and orders' v2 one: " + doors.producers());
        assertEquals(List.of(4L, 2L), List.of(open.stream().filter(o -> o.ordinal() <= v1Built).count(), open.stream().filter(o -> o.ordinal() > v1Built).count()),
                "open: the unchanged flows' v1 producers and orders' v2 one, each flow's with its replay's (D-067); v2's build of the unchanged flows was closed: "
                        + doors.producers());
        assertEquals(1, ordersV1.closes());
        assertNothingClosedTwice();

        for (int tick = 1; tick <= 3; tick++) {
            long polls = queue.claims().stream().filter((NODE + " shop/audit")::equals).count();
            rt.clock().advance(TICK);
            assertEquals(polls + 1, queue.claims().stream().filter((NODE + " shop/audit")::equals).count(),
                    "one worker polls audit per tick: v1's; v2's build of it was never started: " + queue.claims());
        }
    }

    // --- what changes a flow

    static String includingLib(String name) {
        return XSL + "<xsl:include href=\"xsl/lib.xsl\"/><xsl:output method=\"text\"/><xsl:template match=\"/\">" + name
                + ":<xsl:call-template name=\"lib\"/></xsl:template></xsl:stylesheet>";
    }

    static String standalone(String text) {
        return XSL + "<xsl:output method=\"text\"/><xsl:template match=\"/\">" + text + "</xsl:template></xsl:stylesheet>";
    }

    /**
     * {@code shop}: {@code orders} and {@code credits} map with their own stylesheets, both including {@code flows/xsl/lib.xsl}
     * (which writes {@code lib}); {@code refunds} maps with a stylesheet of its own; {@code ping} reads no file. Each is sync but
     * ping (at-most-once), each sends to {@code partner}, whose reply is what was sent.
     */
    static PackageDraft xmlShop(String lib) {
        PackageDraft p = PackageDraft.of("shop", "1.0.0").endpoint("partner", "out", new JsonObject().put("url", "rec://partner"));
        for (String id : List.of("orders", "credits", "refunds")) {
            p.endpoint(id + "-in", "in", new JsonObject().put("path", "/" + id)).flow(id, "1.0.0", "sync", id + "-in", 4096,
                    new JsonArray().add(new JsonObject().put("type", "xslt").put("name", "map").put("stylesheet", "flows/" + id + ".xsl")).addAll(to("partner")));
        }
        return p.endpoint("ping-in", "in", new JsonObject().put("path", "/ping")).flow("ping", "1.0.0", "at-most-once", "ping-in", 4096, to("partner"))
                .file("flows/orders.xsl", includingLib("orders")).file("flows/credits.xsl", includingLib("credits"))
                .file("flows/refunds.xsl", standalone("refunds v1")).file("flows/xsl/lib.xsl", XSL + "<xsl:template name=\"lib\">" + lib + "</xsl:template></xsl:stylesheet>");
    }

    /** v2 changes only the included {@code lib.xsl}: its two readers swap and map with it; refunds and ping are not touched. */
    @Test
    void changingOneXsltSwapsOnlyItsReaders() throws Exception {
        deploy(xmlShop("lib v1"));
        assertEquals("orders:lib v1", reply("shop/orders"), "fixture: orders maps with lib v1");
        assertEquals("credits:lib v1", reply("shop/credits"), "fixture: credits maps with lib v1");
        Doors.Door refunds = doors.door("shop/refunds");
        Doors.Door ping = doors.door("shop/ping");
        int mark = mark();

        DeployResult r = deploy(xmlShop("lib v2"));

        assertResult(List.of(), List.of("shop/credits", "shop/orders"), List.of(), r);
        List<String> events = consumerEvents(since(mark));
        assertEquals(Set.of("consumer shop/credits", "consumer shop/orders", "stop shop/credits", "stop shop/orders", "start shop/credits", "start shop/orders"),
                Set.copyOf(events), "only the readers of lib.xsl are rebuilt, stopped and started: " + events);
        assertEquals(6, events.size(), "each once: " + events);
        assertEquals("orders:lib v2", reply("shop/orders"), "the next message maps with the changed include");
        assertEquals("credits:lib v2", reply("shop/credits"), "in both of its readers");
        assertEquals("refunds v1", reply(refunds), "refunds runs on, its consumer never stopped");
        assertEquals(refunds, doors.door("shop/refunds"));
        assertFalse(refunds.stopped() || ping.stopped(), "refunds and ping run on");
    }

    /**
     * v2 changes the out endpoint {@code partner}: the two flows that send to it swap and the new producers carry the new
     * config; audit (to archive) is not touched. v3 then changes audit's in endpoint: audit alone swaps, its consumer gets the
     * new config.
     */
    @Test
    void changingAnEndpointSwapsEveryFlowNamingIt() throws Exception {
        deploy(shop());
        Doors.Door auditV1 = doors.door("shop/audit");
        PackageDraft v2 = shop();
        v2.endpointDoc("partner").getJsonObject("config").put("url", "rec://partner-v2");
        int mark = mark();

        DeployResult r2 = deploy(v2);

        assertResult(List.of(), List.of("shop/orders", "shop/ping"), List.of(), r2);
        assertFalse(consumerEvents(since(mark)).stream().anyMatch(e -> e.endsWith("shop/audit")), "audit is not touched: " + since(mark));
        assertFalse(auditV1.stopped());
        Doors.Out ordersV2 = producerOf("shop/orders");
        Doors.Out pingV2 = producerOf("shop/ping");
        for (Doors.Out o : List.of(ordersV2, pingV2)) {
            assertEquals("rec://partner-v2", configOf(o).options().get("url"), o + " was built with partner's new config");
        }

        PackageDraft v3 = shop();
        v3.endpointDoc("partner").getJsonObject("config").put("url", "rec://partner-v2");
        v3.endpointDoc("audit-in").getJsonObject("config").put("path", "/audit-v3");
        int mark3 = mark();

        DeployResult r3 = deploy(v3);

        assertResult(List.of(), List.of("shop/audit"), List.of(), r3);
        assertEquals(List.of("consumer shop/audit", "stop shop/audit", "start shop/audit"), consumerEvents(since(mark3)), "audit alone swaps");
        assertEquals("/audit-v3", doors.door("shop/audit").cfg().options().get("path"), "its new consumer has the new config");
        assertEquals(0, ordersV2.closes() + pingV2.closes(), "orders and ping run on v2's producers");
    }

    /** The configuration {@code out} was built with: its endpoint's producers and their configs are both in build order. */
    io.flottila.api.EndpointConfig configOf(Doors.Out out) {
        List<Doors.Out> same = doors.producers().stream().filter(o -> o.endpoint().equals(out.endpoint())).toList();
        List<io.flottila.api.EndpointConfig> configs = doors.producerConfigs(out.endpoint());
        assertEquals(same.size(), configs.size(), "fixture: one config per producer of " + out.endpoint());
        return configs.get(same.indexOf(out));
    }

    // --- added and removed flows

    /**
     * v2 keeps {@code orders} as it was, removes {@code audit} (at-least-once, nothing queued) and {@code ping}, and adds
     * {@code refunds}. The removed flows stop — consumers, audit's worker, their producers — and are no longer listed; refunds
     * starts and serves; orders is not touched. The removed at-least-once flow is checked again once its worker drained: nothing
     * is left, so nothing is logged.
     */
    @Test
    void addedStartsRemovedStops() throws Exception {
        deploy(shop());
        Doors.Out ordersV1 = producerOf("shop/orders");
        Doors.Out auditV1 = producerOf("shop/audit");
        Doors.Out pingV1 = producerOf("shop/ping");
        until(() -> queue.size() == 0, "fixture: audit's message completed");
        Doors.Door auditDoor = doors.door("shop/audit");
        Doors.Door pingDoor = doors.door("shop/ping");
        Doors.Door ordersDoor = doors.door("shop/orders");
        PackageDraft v2 = removed(removed(shop(), "audit"), "ping").without("endpoints/archive.yaml")
                .endpoint("refunds-in", "in", new JsonObject().put("path", "/refunds")).flow("refunds", "1.0.0", "sync", "refunds-in", 1024, to("partner"));
        int mark = mark();

        DeployResult r = deploy(v2);

        assertResult(List.of("shop/refunds"), List.of(), List.of("shop/audit", "shop/ping"), r);
        assertEquals(List.of("shop/orders", "shop/refunds"), deployments.flows().stream().map(DeployedFlow::id).toList(), "the removed flows are not listed");
        assertEquals(List.of("shop/orders", "shop/refunds"), r.pkg().flows());
        List<String> l = since(mark);
        assertTrue(auditDoor.stopped() && pingDoor.stopped(), "the removed flows' consumers are stopped: " + l);
        assertFalse(ordersDoor.stopped(), "orders is not touched");
        List<String> consumers = consumerEvents(l);
        assertEquals(Set.of("consumer shop/refunds", "stop shop/audit", "stop shop/ping", "start shop/refunds"), Set.copyOf(consumers),
                "refunds is built and started, audit and ping stopped, nothing else: " + l);
        assertEquals(4, consumers.size(), "each once: " + consumers);
        until(() -> auditV1.closes() > 0 && pingV1.closes() > 0, "the removed flows' producers are closed: " + doors.producers());
        assertEquals(0, ordersV1.closes(), "orders' producer stays open");
        assertNothingClosedTwice();
        assertInstanceOf(Ack.Reply.class, within(send("shop/refunds").answer(), "refunds' answer"), "the added flow serves");
        Ack gone = within(send(auditDoor).answer(), "an answer through the removed audit's consumer");
        assertTrue(gone instanceof Ack.Nack n && n.retryable(), "the removed flow's delivery is stopped: " + gone);
        long polls = queue.claims().stream().filter((NODE + " shop/audit")::equals).count();
        for (int i = 0; i < 3; i++) rt.clock().advance(TICK);
        assertEquals(polls, queue.claims().stream().filter((NODE + " shop/audit")::equals).count(), "the removed worker no longer polls: " + queue.claims());

        until(() -> hasItemsCalls("shop/audit").size() == 2, "the removed at-least-once flow is checked before the swap and again once its worker drained: "
                + queue.hasItemsCalls());
        assertEquals(List.of("shop/audit rows=0", "shop/audit rows=0"), hasItemsCalls("shop/audit"));
        assertEquals(List.of(), warningsNaming("shop/audit"), "nothing left: no warning");
    }

    /** One row of {@code shop/audit} in the queue, as {@code state}: queued, leased by another worker, or held. Returns its seq. */
    long rowOfAudit(String state) {
        queue.enqueue("m-left", "shop/audit", 1, null).await();
        long seq = queue.rows().get(0).seq();
        switch (state) {
            case "queued" -> { }
            case "leased" -> assertEquals(1, queue.claim("node-2", "shop/audit", 1, LEASE).await().size(), "fixture: node-2 leases the row");
            case "held" -> {
                List<WorkQueue.Item> claimed = queue.claim("node-2", "shop/audit", 1, LEASE).await();
                assertEquals(1, claimed.size(), "fixture: node-2 claims the row");
                assertTrue(queue.hold(seq, claimed.get(0).attempts()).await(), "fixture: the row is held");
            }
            default -> throw new IllegalArgumentException(state);
        }
        return seq;
    }

    /**
     * A row of the at-least-once {@code audit} is in the queue — queued, leased or held — and v2 removes the flow: 422 naming it,
     * before anything stops; v1 keeps serving. Witness: once the row is gone the same archive deploys and removes audit.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"queued", "leased", "held"})
    void removingAnAtLeastOnceFlowWithItemsIs422(String state) throws Exception {
        deploy(shop());
        Doors.Door orders = doors.door("shop/orders");
        Doors.Door audit = doors.door("shop/audit");
        List<DeployedFlow> flowsBefore = deployments.flows();
        List<DeployedPackage> packagesBefore = deployments.packages();
        long seq = rowOfAudit(state);
        Path archive = removed(shop(), "audit").without("endpoints/archive.yaml").zipTo(archives);
        int mark = mark();

        DeployRefusedException refused = refused("shop", archive);

        assertEquals(Kind.INVALID, refused.kind(), state + ": " + refused.error() + " " + refused.problems());
        assertTrue(refused.problems().stream().anyMatch(l -> l.contains("audit")), state + ": a problem names the flow: " + refused.problems());
        assertEquals(List.of(), since(mark).stream().filter(e -> e.startsWith("stop ") || e.startsWith("start ")).toList(), "nothing stops or starts: " + since(mark));
        assertFalse(audit.stopped());
        assertEquals(flowsBefore, deployments.flows());
        assertEquals(packagesBefore, deployments.packages());
        assertInstanceOf(Ack.Reply.class, within(send(orders).answer(), "v1's sync answer"), "v1 keeps serving");
        assertEquals(1, queue.rows().size(), "the row is still there");

        queue.drop(seq);
        assertResult(List.of(), List.of(), List.of("shop/audit"), deploy("shop", Optional.empty(), archive));
    }

    /** A row of {@code audit} is queued and v2 makes the flow sync or at-most-once: 422 naming it; witness: without the row it swaps. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"sync", "at-most-once"})
    void atLeastOnceTurnedSyncWithItemsIs422(String mode) throws Exception {
        deploy(shop());
        Doors.Door audit = doors.door("shop/audit");
        List<DeployedFlow> flowsBefore = deployments.flows();
        long seq = rowOfAudit("queued");
        PackageDraft v2 = shop();
        v2.flowDoc("audit").getJsonObject("delivery").put("mode", mode);
        Path archive = v2.zipTo(archives);
        int mark = mark();

        DeployRefusedException refused = refused("shop", archive);

        assertEquals(Kind.INVALID, refused.kind(), mode + ": " + refused.error() + " " + refused.problems());
        assertTrue(refused.problems().stream().anyMatch(l -> l.contains("audit")), mode + ": a problem names the flow: " + refused.problems());
        assertEquals(List.of(), since(mark).stream().filter(e -> e.startsWith("stop ") || e.startsWith("start ")).toList(), "nothing stops or starts: " + since(mark));
        assertFalse(audit.stopped());
        assertEquals(flowsBefore, deployments.flows());

        queue.drop(seq);
        assertResult(List.of(), List.of("shop/audit"), List.of(), deploy("shop", Optional.empty(), archive));
        assertEquals(mode, byId(deployments.flows()).get("shop/audit").delivery());
    }

    /**
     * v2 still names the running at-least-once {@code audit}, but its yaml breaks the flow schema (a key the schema does not know)
     * while a row of audit is queued: the 422 lists the schema's problems and nothing else — no line saying v2 does not run audit
     * at-least-once. Witness: once the row is gone the same archive gives the same problems.
     */
    @Test
    void aRunningAtLeastOnceFlowWhoseV2YamlIsInvalidGetsOnlyItsSchemaProblem() throws Exception {
        deploy(shop());
        long seq = rowOfAudit("queued");
        PackageDraft v2 = shop();
        v2.flowDoc("audit").put("colour", "red");
        Path archive = v2.zipTo(archives);

        DeployRefusedException withRow = refused("shop", archive);
        queue.drop(seq);
        DeployRefusedException withoutRow = refused("shop", archive);

        assertEquals(Kind.INVALID, withoutRow.kind(), "fixture: " + withoutRow.error() + " " + withoutRow.problems());
        assertTrue(withoutRow.problems().stream().anyMatch(l -> l.contains("audit")), "fixture: a schema problem names audit's yaml: " + withoutRow.problems());
        assertEquals(Kind.INVALID, withRow.kind(), withRow.error() + " " + withRow.problems());
        assertEquals(withoutRow.problems(), withRow.problems(), "with a row of audit queued the 422 lists the same problems, its schema's only: " + withRow.problems());
    }

    /**
     * v2 removes the at-least-once {@code audit} and is invalid besides (an endpoint its schema refuses), and the queue cannot say
     * whether items of audit are left: the deploy is the 422 of the package's problems, not a failure of the node (500), and
     * nothing stops. Witness: with the queue answering, the same archive is that 422.
     */
    @Test
    void aHasItemsFailureOnAnInvalidPackageIs422() throws Exception {
        deploy(shop());
        Doors.Door audit = doors.door("shop/audit");
        List<DeployedFlow> flowsBefore = deployments.flows();
        PackageDraft v2 = removed(shop(), "audit").without("endpoints/archive.yaml");
        v2.endpoint("spare", "sideways", new JsonObject());
        Path archive = v2.zipTo(archives);
        DeployRefusedException answering = refused("shop", archive);
        assertEquals(Kind.INVALID, answering.kind(), "fixture: " + answering.error() + " " + answering.problems());
        assertTrue(answering.problems().stream().anyMatch(l -> l.contains("spare") && l.contains("direction")),
                "fixture: a problem names the endpoint its schema refuses: " + answering.problems());
        queue.failHasItems(new IllegalStateException("the work queue is down"));
        int mark = mark();

        DeployRefusedException refused = refused("shop", archive);

        assertEquals(Kind.INVALID, refused.kind(), "a queue that cannot answer does not turn an invalid package into a 500: " + refused.error() + " " + refused.problems());
        assertTrue(refused.problems().containsAll(answering.problems()), "the 422 lists the package's problems: " + refused.problems());
        assertEquals(List.of(), since(mark).stream().filter(e -> e.startsWith("stop ") || e.startsWith("start ")).toList(), "nothing stops or starts: " + since(mark));
        assertFalse(audit.stopped());
        assertEquals(flowsBefore, deployments.flows());
    }

    /**
     * v1 runs two at-least-once flows, {@code audit} and {@code relay}, each running one item at most; v2 removes both. Right after
     * each one's check found nothing queued, messages arrive: audit's A runs held at the partner with B queued behind it, relay's
     * C runs held. The deploy answers without waiting for them. Once audit's worker drained (A finished) it is checked again: B is
     * still there, and a WARNING names audit; relay's check finds nothing and names nothing.
     */
    @Test
    void itemsLeftAfterTheRemovedWorkerDrainedWarn() throws Exception {
        PackageDraft v1 = shop().endpoint("relay-in", "in", new JsonObject().put("path", "/relay")).flow("relay", "1.0.0", "at-least-once", "relay-in", 1024, to("archive"));
        v1.flowDoc("audit").getJsonObject("limits").put("maxInFlight", 1);
        v1.flowDoc("relay").getJsonObject("limits").put("maxInFlight", 1);
        deploy(v1);
        Doors.Door audit = doors.door("shop/audit");
        Doors.Door relay = doors.door("shop/relay");
        List<CountDownLatch> releases = new CopyOnWriteArrayList<>();
        Map<String, Sent> arrived = new java.util.concurrent.ConcurrentHashMap<>();
        queue.afterFirstHasItems("shop/audit", () -> {
            try {
                int sent = doors.partner().sent().size();
                releases.add(hold());
                Sent a = send(audit);
                assertInstanceOf(Ack.Accepted.class, within(a.answer(), "A's accept"), "fixture: A is accepted after audit's check");
                doors.partner().awaitSent(sent + 1);
                Sent b = send(audit);
                assertInstanceOf(Ack.Accepted.class, within(b.answer(), "B's accept"), "fixture: B is accepted after audit's check");
                arrived.put("A", a);
                arrived.put("B", b);
            } catch (Exception e) {
                throw new AssertionError("fixture: messages arriving after audit's check", e);
            }
        });
        queue.afterFirstHasItems("shop/relay", () -> {
            try {
                int sent = doors.partner().sent().size();
                releases.add(hold());
                Sent c = send(relay);
                assertInstanceOf(Ack.Accepted.class, within(c.answer(), "C's accept"), "fixture: C is accepted after relay's check");
                doors.partner().awaitSent(sent + 1);
                arrived.put("C", c);
            } catch (Exception e) {
                throw new AssertionError("fixture: a message arriving after relay's check", e);
            }
        });
        PackageDraft v2 = removed(shop(), "audit"); // shop() never had relay: v2 drops it with its relay-in
        v2.without("endpoints/archive.yaml");

        DeployResult r = deploy(v2);

        assertResult(List.of(), List.of(), List.of("shop/audit", "shop/relay"), r);
        assertEquals(Set.of("A", "B", "C"), arrived.keySet(), "fixture: A, B and C arrived after the checks: " + queue.hasItemsCalls());
        Sent b = arrived.get("B");
        assertEquals(Set.of(arrived.get("A").id(), arrived.get("C").id()), Set.copyOf(queue.claimedIds()), "fixture: A and C run, B waits behind A (audit runs one at most)");
        assertEquals(2, queue.claimedIds().size(), "fixture: each claimed once: " + queue.claimedIds());
        assertEquals(List.of(), warningsNaming("shop/audit"), "no warning while A still runs: the check comes after the drain");
        assertEquals(List.of("shop/audit rows=0"), hasItemsCalls("shop/audit"), "audit is not checked again before its worker drained");

        releases.forEach(CountDownLatch::countDown);

        until(() -> hasItemsCalls("shop/audit").size() == 2 && hasItemsCalls("shop/relay").size() == 2,
                "each removed flow is checked again once its worker drained: " + queue.hasItemsCalls());
        assertEquals(List.of("shop/audit rows=0", "shop/audit rows=1"), hasItemsCalls("shop/audit"),
                "audit's second check sees B alone: A finished before it, the worker drained");
        assertEquals(List.of("shop/relay rows=0", "shop/relay rows=0"), hasItemsCalls("shop/relay"));
        until(() -> !warningsNaming("shop/audit").isEmpty(), "a WARNING names the flow whose items are left: "
                + logged.stream().map(x -> x.getLevel() + " " + texts(x)).toList());
        assertEquals(List.of(b.id()), queue.rows().stream().map(WorkQueue.Item::messageId).toList(), "B is the item left");
        assertEquals(0, dlq.size(), "A and C finished on v1's producers: nothing parked");
        assertEquals(List.of(), warningsNaming("shop/relay"), "relay left nothing: no warning names it");
    }

    // --- disk

    /**
     * v1 runs {@code a} and {@code b}. v2 and v3 change {@code a} only, so {@code b} still runs from v1's directory: after v3 the
     * data directory keeps v3 (current), v2 (previous) and v1 (in use by b), each whole. v4 changes both: v1 and v2 go.
     */
    @Test
    void prunesToCurrentPreviousAndDirsInUse() throws Exception {
        Map<String, String> modes = new LinkedHashMap<>();
        modes.put("a", "sync");
        modes.put("b", "sync");
        Path shopDir = data.resolve("packages").resolve("shop");
        PackageDraft v1 = DeploymentsTest.flows("shop", modes);
        String sha1 = deploy(v1).pkg().archiveSha256();
        String sha2 = deploy(changed(DeploymentsTest.flows("shop", modes), "a", "v2")).pkg().archiveSha256();
        assertEquals(sorted(sha1, sha2), children(shopDir), "after v2: current and previous");

        DeployResult r3 = deploy(changed(DeploymentsTest.flows("shop", modes), "a", "v3"));
        String sha3 = r3.pkg().archiveSha256();

        assertResult(List.of(), List.of("shop/a"), List.of(), r3);
        assertEquals(sorted(sha1, sha2, sha3), children(shopDir), "after v3: current (v3), previous (v2) and v1, which b still runs from");
        assertEquals(new ArrayList<>(v1.files().keySet()), regularFiles(packageDir(sha1)), "the directory in use is kept whole");
        assertInstanceOf(Ack.Reply.class, within(send("shop/b").answer(), "b's answer"), "b runs on");

        DeployResult r4 = deploy(changed(changed(DeploymentsTest.flows("shop", modes), "a", "v4"), "b", "v4"));
        String sha4 = r4.pkg().archiveSha256();

        assertResult(List.of(), List.of("shop/a", "shop/b"), List.of(), r4);
        assertEquals(sorted(sha3, sha4), children(shopDir), "after v4: current and previous; v1 and v2 are no longer in use");
        assertEquals(List.of(), children(deployments.incomingDir()));
    }

    static List<String> sorted(String... shas) {
        return Stream.of(shas).sorted().toList();
    }

    static Object fileKey(Path p) throws IOException {
        return Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
    }

    /**
     * The running archive deployed again (another revision): the kept directory is reused — the same directory and files, not a
     * fresh unpack moved over them — nothing is built into a consumer, stopped or started, the flows keep their stamps, and the
     * package record carries the new revision and time.
     */
    @Test
    void theRunningArchiveAgainReusesItsDirAndSwapsNothing() throws Exception {
        PackageDraft p = shop();
        byte[] zip = p.zip();
        String sha = deploy("shop", Optional.of("r1"), PackageDraft.write(archives, zip)).pkg().archiveSha256();
        Path dir = packageDir(sha);
        Object dirKey = fileKey(dir);
        Object yamlKey = fileKey(dir.resolve("package.yaml"));
        assertNotNull(dirKey, "fixture: the file system names its files (Linux: device and inode)");
        List<DeployedFlow> flowsBefore = deployments.flows();
        List<Doors.Out> open = doors.openProducers();
        rt.clock().advance(Duration.ofSeconds(30));
        int mark = mark();

        DeployResult again = deploy("shop", Optional.of("r2"), PackageDraft.write(archives, zip));

        assertResult(List.of(), List.of(), List.of(), again);
        assertEquals(List.of(), consumerEvents(since(mark)), "no consumer built, stopped or started: " + since(mark));
        assertEquals(dirKey, fileKey(dir), "the kept directory is reused, not replaced");
        assertEquals(yamlKey, fileKey(dir.resolve("package.yaml")), "its files are the ones unpacked the first time");
        assertEquals(new ArrayList<>(p.files().keySet()), regularFiles(dir));
        assertEquals(List.of(sha), children(data.resolve("packages").resolve("shop")));
        assertEquals(List.of(), children(deployments.incomingDir()), "the fresh unpack is dropped");
        assertEquals(flowsBefore, deployments.flows(), "the flows keep their stamps (revision r1)");
        assertEquals(sha, again.pkg().archiveSha256());
        assertEquals(Optional.of("r2"), again.pkg().revision(), "the package record carries the new revision");
        assertEquals(rt.clock().instant(), again.pkg().deployedAt(), "and the new time");
        assertEquals(List.of(again.pkg()), deployments.packages());
        assertEquals(open, doors.openProducers(), "the new build was closed, the running producers stay open: " + doors.producers());
    }

    /**
     * A kept directory whose files no longer match the record of what was unpacked into it — a step wrote a file into it, changed
     * one, or deleted one — is not trusted: the deploy of its archive unpacks the archive afresh over it (no refusal: the archive,
     * not what was written after the record, is what runs). The directory is kept as the running version's or as the previous
     * one (still in use). The new record and the directory hold exactly the archive's files; the flows are classified against
     * the archive's content — nothing written after the record moves a hash — and map with the archive's lib.xsl.
     */
    @ParameterizedTest(name = "{0} dir, a file {1}")
    @CsvSource({"running, added", "running, changed", "running, removed", "previous, added", "previous, changed", "previous, removed"})
    void aReusedKeptDirWhoseFilesChangedIsNotTrusted(String kept, String tamper) throws Exception {
        PackageDraft v1 = xmlShop("lib v1");
        byte[] zip = v1.zip();
        DeployedPackage first = deploy("shop", Optional.of("r1"), PackageDraft.write(archives, zip)).pkg();
        String sha = first.archiveSha256();
        Path dir = packageDir(sha);
        List<String> swapped = List.of();
        if (kept.equals("previous")) {
            assertResult(List.of(), List.of("shop/refunds"), List.of(), deploy(xmlShop("lib v1").file("flows/refunds.xsl", standalone("refunds v2")), "r2"));
            assertEquals("refunds v2", reply("shop/refunds"), "fixture: v2 runs refunds v2, v1's directory is the previous one");
            swapped = List.of("shop/refunds");   // v1 again maps refunds with v1's stylesheet
        }
        Path lib = dir.resolve("flows/xsl/lib.xsl");
        switch (tamper) {
            case "added" -> Files.writeString(dir.resolve("flows/written.txt"), "a step wrote this after the deploy");
            case "changed" -> Files.writeString(lib, XSL + "<xsl:template name=\"lib\">tampered</xsl:template></xsl:stylesheet>");
            case "removed" -> Files.delete(lib);
            default -> throw new IllegalArgumentException(tamper);
        }

        DeployResult again = deploy("shop", Optional.of("r3"), PackageDraft.write(archives, zip));

        assertResult(List.of(), swapped, List.of(), again);
        assertEquals(sha, again.pkg().archiveSha256());
        assertEquals(first.files(), again.pkg().files(), "the record lists the archive's files as the first unpack did, nothing written after it");
        Map<String, String> onDisk = new TreeMap<>();
        for (String f : regularFiles(dir)) onDisk.put(f, PackageDraft.sha256(dir.resolve(f)));
        Map<String, String> archived = new TreeMap<>();
        v1.files().forEach((path, bytes) -> archived.put(path, PackageDraft.sha256(bytes)));
        assertEquals(archived, onDisk, "the directory holds exactly the archive's files, fresh from it");
        assertEquals(List.of(), children(deployments.incomingDir()), "no unpack is left behind");
        assertEquals("orders:lib v1", reply("shop/orders"), "orders maps with the archive's lib.xsl");
        assertEquals("credits:lib v1", reply("shop/credits"), "and credits");
        assertEquals("refunds v1", reply("shop/refunds"), "refunds maps with the archive's stylesheet");
    }

    // --- in flight during a swap

    /**
     * A sync message of {@code orders} is in flight at the partner when v2 swaps it: v1's delivery stops with the node's stop
     * deadline, a new message on v1's consumer is refused (retryable), and v2 starts only once v1's stop settled — the message
     * answered by v1 ({@code answered}), or cancelled with a retryable Nack exactly at the deadline on the fake clock
     * ({@code deadline}). The next message runs on v2.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"answered", "deadline"})
    void aSyncRequestInFlightIsAnsweredByV1OrCancelled503AtTheDeadline(String outcome) throws Exception {
        deploy(shop());
        Doors.Door v1 = doors.door("shop/orders");
        CountDownLatch held = hold();
        Sent m = send(v1);
        doors.partner().awaitSent(1);
        PackageDraft v2 = shop();
        v2.flowDoc("orders").put("version", "3.0.0");
        int mark = mark();

        OnVirtualThread.Call<DeployResult> d = deploying(v2);

        untilWhile(d, () -> log.contains("stop shop/orders") && timer.all().stream().anyMatch(t -> t.owner().equals("Delivery") && t.delay().equals(STOP_DEADLINE) && t.live()),
                "phase A stops v1's orders, its delivery with the node's stop deadline " + STOP_DEADLINE + ": " + timer.all());
        Ack late = within(v1.deliver(envelope("shop/orders")), "a new message on v1's consumer during the swap");
        assertTrue(late instanceof Ack.Nack n && n.retryable(), "a new message in the window is refused, retryable: " + late);
        assertFalse(d.isDone(), "the swap waits for the message in flight");
        if (outcome.equals("answered")) {
            held.countDown();
            Ack answered = within(m.answer(), "the message in flight");
            assertInstanceOf(Ack.Reply.class, answered, "answered in time: v1's reply");
            assertEquals("shop/orders@2.1.0", versions(m.id()).get("flow"), "it ran on v1");
        } else {
            rt.clock().advance(STOP_DEADLINE.minusMillis(1));
            assertFalse(m.answer().isComplete(), "not cancelled a millisecond before the deadline");
            assertFalse(d.isDone(), "the swap still waits");
            assertFalse(since(mark).contains("start shop/orders"), "v2 has not started");
            rt.clock().advance(Duration.ofMillis(1));
            Ack cancelled = within(m.answer(), "the answer at the deadline");
            assertTrue(cancelled instanceof Ack.Nack n && n.retryable() && n.reason().contains(STOP_DEADLINE.toString()),
                    "cancelled at the node's stop deadline, retryable (the consumer's 503): " + cancelled);
        }
        DeployResult r = OnVirtualThread.join(d);

        assertResult(List.of(), List.of("shop/orders"), List.of(), r);
        inOrder(since(mark), "stopped shop/orders", "start shop/orders");
        Sent next = send("shop/orders");
        assertInstanceOf(Ack.Reply.class, within(next.answer(), "the next message"));
        assertEquals("shop/orders@3.0.0", versions(next.id()).get("flow"), "the next message runs on v2");
    }

    /**
     * v1 audit's worker runs message A, held at the partner, when v2 swaps audit. The deploy answers without waiting; A runs on
     * through v1's producer, its lease renewed past the lease time while v2's worker polls (A is never claimed again); v1's
     * producer is not closed meanwhile and A is not parked. Released, A completes by its own success, then that producer closes.
     */
    @Test
    void oldWorkerItemsFinishOnOldProducersThenClose() throws Exception {
        deploy(shop());
        doors.closeFailsSends();
        CountDownLatch release = hold();
        Sent a = send("shop/audit");
        assertEquals(new Ack.Accepted(a.id()), within(a.answer(), "A's accept"));
        doors.partner().awaitSent(1);
        Doors.Out heldBy = producerOf(a);

        DeployResult r = deploy(changed(shop(), "audit", "v2"));

        assertResult(List.of(), List.of("shop/audit"), List.of(), r);
        assertEquals(0, heldBy.closes(), "v1's worker producer is not closed while A runs on it: " + doors.producers());
        int renews = queue.renews();
        for (long s = 0; s < LEASE.toSeconds() + 10; s++) rt.clock().advance(TICK);
        assertTrue(queue.renews() >= renews + 3, "A's lease was renewed while it ran: " + queue.renews());
        assertEquals(1, Collections.frequency(queue.claimedIds(), a.id()), "A was claimed once: v2's worker polled and never took it: " + queue.claimedIds());
        assertEquals(0, heldBy.closes());
        assertEquals(0, dlq.size(), "A is not parked");

        release.countDown();

        until(() -> queue.size() == 0, "A completes");
        assertEquals(0, dlq.size(), "by its own success: no DLQ row");
        until(() -> heldBy.closes() > 0, "v1's worker producer closes once A finished");
        assertEquals(List.of(0), heldBy.unansweredAtClose(), "with no send of its own unanswered");
        assertEquals(List.of(a.id()), ids(doors.partner().sentTo("archive")), "A was sent once");
        assertNothingClosedTwice();
    }

    /**
     * v1 audit runs one item at most: A runs held at the partner, B waits in the queue. v2 sends audit to another endpoint. On
     * v2's first tick its worker claims B and sends it to v2's endpoint; A finishes on v1's.
     */
    @Test
    void queuedItemsRunOnV2() throws Exception {
        PackageDraft v1 = shop();
        v1.flowDoc("audit").getJsonObject("limits").put("maxInFlight", 1);
        deploy(v1);
        CountDownLatch release = hold();
        Sent a = send("shop/audit");
        assertEquals(new Ack.Accepted(a.id()), within(a.answer(), "A's accept"));
        doors.partner().awaitSent(1);
        Sent b = send("shop/audit");
        assertEquals(new Ack.Accepted(b.id()), within(b.answer(), "B's accept"));
        assertEquals(List.of(a.id()), queue.claimedIds(), "fixture: v1's worker runs its one item, B waits in the queue");
        PackageDraft v2 = shop().endpoint("archive-v2", "out", new JsonObject().put("url", "rec://archive-v2"));
        v2.flowDoc("audit").put("steps", to("archive-v2")).getJsonObject("limits").put("maxInFlight", 1);

        assertResult(List.of(), List.of("shop/audit"), List.of(), deploy(v2));
        rt.clock().advance(TICK);

        doors.partner().awaitSent(2);
        assertEquals(List.of(b.id()), ids(doors.partner().sentTo("archive-v2")), "B ran on v2: sent to v2's endpoint");
        release.countDown();
        until(() -> queue.size() == 0, "A and B complete");
        assertEquals(List.of(a.id()), ids(doors.partner().sentTo("archive")), "A ran on v1 alone");
        assertEquals(0, dlq.size());
    }

    // --- refusals

    /** A chain of 20 directories with 250-character names, built by moves so that no single call names its full depth. */
    static Path deepChain(Path work) throws IOException {
        Path top = Files.createDirectories(work.resolve("t0"));
        Files.writeString(top.resolve("leaf.txt"), "deep");
        for (int i = 1; i <= 20; i++) {
            Path parent = Files.createDirectory(work.resolve("t" + i));
            Files.move(top, parent.resolve(String.valueOf((char) ('a' + i)).repeat(250)));
            top = parent;
        }
        return top;
    }

    /** Takes {@link #deepChain} apart one level at a time, each by a short path. */
    static void dismantle(Path top, Path work) throws IOException {
        int n = 0;
        while (top != null) {
            Path next = null;
            try (Stream<Path> children = Files.list(top)) {
                for (Path child : children.toList()) {
                    if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) next = Files.move(child, work.resolve("d" + n++));
                    else Files.delete(child);
                }
            }
            Files.delete(top);
            top = next;
        }
    }

    static boolean hasIOException(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) if (c instanceof IOException) return true;
        return false;
    }

    /**
     * FIX-32: a first deploy refused at the build (the component refuses {@code zz}'s consumer) whose cleanup cannot delete the
     * package's directory (a directory chain below it longer than PATH_MAX, put there while the consumer is built: no tree walk
     * gets through it, whoever runs the test) answers the same refusal — its kind, error and problems — as the same deploy whose
     * cleanup works, with the cleanup's failure added as suppressed.
     */
    @Test
    void aRefusalWhoseCleanupFailsKeepsItsKindAndProblems() throws Exception {
        PackageDraft p = shop().endpoint("zz-in", "in", new JsonObject().put("path", "/zz").put("refuse", "no door today"))
                .flow("zz", "1.0.0", "sync", "zz-in", 1024, to("partner"));
        Path archive = p.zipTo(archives);
        Path dir = packageDir(PackageDraft.sha256(archive));
        DeployRefusedException clean = refused("shop", archive);
        assertEquals(Kind.INVALID, clean.kind(), "fixture: the refusal with a cleanup that works: " + clean.error() + " " + clean.problems());
        assertTrue(clean.problems().stream().anyMatch(l -> l.contains("zz") && l.contains("no door today")), "fixture: " + clean.problems());
        assertEquals(0, clean.getSuppressed().length, "fixture: nothing suppressed when the cleanup works");
        assertFalse(Files.exists(dir), "fixture: that cleanup deleted the directory");
        Path work = Files.createDirectories(tmp.resolve("w"));
        Path chain = deepChain(work);
        AtomicBoolean planted = new AtomicBoolean();
        doors.onConsumer(cfg -> {
            if (!cfg.options().containsKey("refuse")) return;
            try {
                Files.move(chain, dir.resolve("deep"));
                planted.set(true);
            } catch (IOException e) {
                throw new AssertionError("fixture: the chain could not be put below " + dir, e);
            }
        });
        try {
            DeployRefusedException refused = refused("shop", archive);

            assertTrue(planted.get(), "fixture: the chain was put below the package's directory while zz's consumer was built");
            assertTrue(Files.exists(dir.resolve("deep")), "fixture: the cleanup could not delete it");
            assertEquals(Kind.INVALID, refused.kind(), "a cleanup that fails keeps the refusal's kind (422, not 500): " + refused.error());
            assertEquals(clean.error(), refused.error(), "and its error line");
            assertEquals(clean.problems(), refused.problems(), "and every problem");
            List<Throwable> suppressed = Arrays.asList(refused.getSuppressed());
            assertTrue(suppressed.stream().anyMatch(DeploymentsRedeployTest::hasIOException),
                    "the cleanup's failure is added as suppressed: " + suppressed.stream().map(t -> t.getClass().getName()).toList());
        } finally {
            doors.onConsumer(cfg -> {});
            Path deep = dir.resolve("deep");
            if (Files.exists(deep, LinkOption.NOFOLLOW_LINKS)) dismantle(deep, work);
            if (Files.exists(chain, LinkOption.NOFOLLOW_LINKS)) dismantle(chain, work);
        }
    }

    /**
     * While a redeploy of {@code shop} holds the node's deploy lock (v2's first start is held), another redeploy of {@code shop}
     * and a first deploy of {@code store} are both 409 at once, neither unpacked: one deploy per node, not per package.
     */
    @Test
    void aSecondDeployWhileOneRunsIs409() throws Exception {
        deploy(shop());
        Doors.Gate gate = doors.holdStart(1);
        OnVirtualThread.Call<DeployResult> running = deploying(changed(shop(), "orders", "v2"));
        reachedWhile(running, gate.reached(), "the redeploy starts v2's orders");
        Path v3 = changed(changed(shop(), "orders", "v2"), "ping", "v3").zipTo(archives);
        Path store = DeploymentsTest.flows("store", Map.of("orders", "sync")).zipTo(archives);

        DeployRefusedException same = joinRefused(OnVirtualThread.start(() -> deployments.deploy("shop", Optional.empty(), v3)),
                "a redeploy of the same package while one runs answers at once");
        DeployRefusedException other = joinRefused(OnVirtualThread.start(() -> deployments.deploy("store", Optional.empty(), store)),
                "a deploy of another package while a redeploy runs answers at once");

        assertEquals(Kind.CONFLICT, same.kind(), same.error());
        assertEquals(Kind.CONFLICT, other.kind(), other.error());
        assertFalse(Files.exists(packageDir(PackageDraft.sha256(v3))), "the refused redeploy was never unpacked");
        assertFalse(Files.exists(data.resolve("packages").resolve("store")), "nor the refused deploy");
        assertFalse(running.isDone(), "the running redeploy still waits on its start");
        gate.release();
        assertResult(List.of(), List.of("shop/orders"), List.of(), OnVirtualThread.join(running));
        assertResult(List.of(), List.of("shop/ping"), List.of(), deploy("shop", Optional.empty(), v3));
        assertResult(List.of("store/orders"), List.of(), List.of(), deploy("store", Optional.empty(), store));
    }
}
