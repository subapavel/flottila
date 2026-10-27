package io.flottila.runtime.deploy;

import io.flottila.api.Ack;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Runtime;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.flow.Replay;
import io.flottila.core.flow.ReplayRefusedException;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static io.flottila.runtime.deploy.DeploymentsTest.flows;
import static io.flottila.runtime.deploy.DeploymentsTest.shop;
import static io.flottila.runtime.deploy.DeploymentsTest.until;
import static io.flottila.runtime.deploy.DeploymentsTest.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * FIX-30: the producers a deploy builds (through {@code FlowBuilder}: a sync or at-most-once flow's delivery, an at-least-once
 * flow's worker) are closed — once each — whenever the flows that own them go: a deploy refused at the build (a later flow
 * that does not build, a consumer the component refuses, a worker that does not build) or at a failed consumer start closes
 * every producer built for that attempt and no other; {@link Deployments#close} closes every producer, each only after its
 * flow's delivery stopped (no send of it unanswered when it is closed). A producer whose close fails is logged at WARNING
 * and the others close all the same. The producers are {@link Doors.Out}s, which count their closes.
 *
 * <p>P1-09c2 (D-067): every flow also has a {@link Replay} built with it, whose producers are a set of their own — one more
 * per flow, at-most-once included. A swap closes the replaced replay without waiting for it: its producers close once the run
 * in flight on them ended; {@link Deployments#close} closes every replay's producers, each only after its runs ended.
 */
class DeploymentsProducersTest {

    static final Duration STOP_DEADLINE = Duration.ofSeconds(37);

    @TempDir Path tmp;
    Path archives;

    final FakeRuntime rt = FakeRuntime.create();
    final Doors doors = new Doors();
    final InMemoryMessageStore messages = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();
    final CountingQueue queue = new CountingQueue(rt.clock());
    final List<CountDownLatch> holds = new CopyOnWriteArrayList<>();
    final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    /** Strongly held: JUL keeps loggers weakly, and a collected logger loses its handler. */
    final Logger flottila = Logger.getLogger("io.flottila");
    final Handler capture = new Handler() {
        @Override public void publish(LogRecord r) { logged.add(r); }
        @Override public void flush() {}
        @Override public void close() {}
    };
    Deployments deployments;

    @BeforeEach
    void wire() {
        archives = tmp.resolve("archives");
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "dev");
        env.put("FLOTTILA_NODE_ID", "node-1");
        env.put("FLOTTILA_DATA_DIR", tmp.resolve("data").toString());
        env.put("FLOTTILA_STOP_DEADLINE", STOP_DEADLINE.toString());
        env.put("FLOTTILA_BLOB_DURABLE_MAX", "4096");
        env.put("FLOTTILA_MAX_PACKAGE_BYTES", Long.toString(2L * 1024 * 1024));
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
        deployments = new Deployments(NodeConfig.from(env), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), List.of(), List.of(), messages, dlq,
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

    // --- fixture

    DeployedPackage deploy(PackageDraft p) throws Exception {
        return OnVirtualThread.call(() -> deployments.deploy(p.manifest().getString("name"), Optional.empty(), p.zipTo(archives))).pkg();
    }

    DeployRefusedException refused(PackageDraft p) {
        return assertThrows(DeployRefusedException.class,
                () -> OnVirtualThread.call(() -> deployments.deploy(p.manifest().getString("name"), Optional.empty(), p.zipTo(archives))),
                "the deploy is refused");
    }

    void assertNothingClosedTwice() {
        assertEquals(List.of(), doors.producersClosedTwice(), "each producer is closed once: " + doors.producers());
    }

    /** Every text a log record carries: its message, and the message of every throwable in its thrown chain and their suppressed. */
    static List<String> texts(LogRecord r) {
        List<String> texts = new ArrayList<>();
        texts.add(String.valueOf(r.getMessage()));
        List<Throwable> todo = new ArrayList<>();
        if (r.getThrown() != null) todo.add(r.getThrown());
        while (!todo.isEmpty()) {
            Throwable t = todo.removeFirst();
            texts.add(String.valueOf(t.getMessage()));
            if (t.getCause() != null) todo.add(t.getCause());
            todo.addAll(List.of(t.getSuppressed()));
        }
        return texts;
    }

    // --- a refused deploy

    /**
     * The package {@code shop} (orders sync, audit at-least-once, ping at-most-once, each one producer) broken at the build: a
     * flow beside them whose pipeline fails after its {@code to} step, the sync flow's consumer refused by the component after
     * its delivery was built, or the at-least-once flow's worker failing after its {@code to} step. The deploy is 422 and every
     * producer built for it — those of the flows that built and those of the flow that failed half-way — is closed, once.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"a flow that does not build beside three that do", "a sync flow whose consumer is refused",
            "an at-least-once flow whose worker does not build"})
    void aDeployRefusedAtTheBuildLeavesNoOpenProducer(String why) {
        PackageDraft p = shop("shop");
        int built;
        switch (why) {
            case "a flow that does not build beside three that do" -> {
                p.endpoint("zz-in", "in", new JsonObject().put("path", "/zz"))
                        .flow("zz", "1.0.0", "sync", "zz-in", 1024, PackageDraft.toPartner().add(new JsonObject().put("type", "frobnicate").put("name", "f")));
                built = 7;   // D-067: audit's, orders' and ping's two each (delivery or worker, and replay), zz's one before it failed
            }
            case "a sync flow whose consumer is refused" -> {
                p.endpointDoc("orders-in").getJsonObject("config").put("refuse", "no door today");
                built = 6;   // D-067: each flow's delivery or worker producer, and its replay's
            }
            case "an at-least-once flow whose worker does not build" -> {
                p.flowDoc("audit").put("steps", PackageDraft.toPartner().add(new JsonObject().put("type", "stamp").put("name", "s")));
                built = 5;   // D-067: orders' and ping's two each (delivery and replay), audit's one before it failed
            }
            default -> throw new IllegalArgumentException(why);
        }

        DeployRefusedException refused = refused(p);

        assertEquals(Kind.INVALID, refused.kind(), why + ": " + refused.error() + " " + refused.problems());
        assertEquals(built, doors.producers().size(), "fixture: the build created " + built + " producers before it was refused: " + doors.producers());
        assertEquals(List.of(), doors.openProducers(), why + ": no producer of the refused deploy is left open: " + doors.producers());
        assertNothingClosedTwice();
    }

    /**
     * Another package runs (its one producer, the first built); {@code shop}'s second consumer start fails. Every producer built
     * for {@code shop} — its sync and at-most-once deliveries', its at-least-once worker's — is closed once; the other package's
     * producer stays open.
     */
    @Test
    void aFailedStartClosesEveryProducerOfTheAttemptAndNoOther() throws Exception {
        deploy(flows("store", Map.of("orders", "sync")));
        assertEquals(2, doors.producers().size(), "fixture: store built two producers, its delivery's and its replay's (D-067)");
        List<Doors.Out> store = doors.producers();
        doors.failStart(2, "address in use");

        DeployRefusedException refused = refused(shop("shop"));

        assertEquals(Kind.FAILED, refused.kind(), refused.error());
        assertEquals(8, doors.producers().size(), "fixture: shop built six producers, three flows' and their replays': " + doors.producers());
        assertEquals(store, doors.openProducers(), "every producer of the failed deploy is closed, the running package's is not: " + doors.producers());
        assertNothingClosedTwice();
        Ack still = within(doors.door("store/orders").deliver(envelope("store/orders")), "store's answer after shop's failed start");
        assertInstanceOf(Ack.Reply.class, still, "store's flow still sends through its open producer: " + still);
    }

    Envelope envelope(String flow) {
        return rt.ingress(flow, Headers.empty(), Buffer.buffer("<order/>"));
    }

    // --- close

    /**
     * Two packages run (eight producers, D-067: each flow's and its replay's); a sync message is held at the partner. Close stops every flow; while that message
     * drains, the producer it was sent through is not closed. Once it answered, close returns with every producer closed once,
     * and none was closed with a send of its own unanswered: each after its flow's delivery stopped.
     */
    @Test
    void closeClosesEveryProducerOnceEachAfterItsDeliveryStopped() throws Exception {
        deploy(shop("shop"));
        deploy(flows("store", Map.of("orders", "sync")));
        assertEquals(8, doors.producers().size(), "fixture: shop's six producers and store's two, each flow's and its replay's: " + doors.producers());
        CountDownLatch release = doors.partner().holdNext(1);
        holds.add(release);
        Envelope held = envelope("shop/orders");
        Future<Ack> answer = doors.door("shop/orders").deliver(held);
        doors.partner().awaitSent(1);
        Doors.Out heldBy = doors.producers().stream().filter(o -> o.sentIds().contains(held.id())).findFirst().orElseThrow();

        OnVirtualThread.Call<Void> closing = OnVirtualThread.start(deployments::close);

        until(() -> doors.doors().stream().allMatch(Doors.Door::stopped), "close stops every consumer");
        assertFalse(answer.isComplete(), "fixture: the held message still drains");
        assertEquals(0, heldBy.closes(), "the producer of the message in flight is not closed while its delivery drains: " + doors.producers());
        assertFalse(closing.isDone(), "close waits for the message in flight");
        release.countDown();
        assertInstanceOf(Ack.Reply.class, within(answer, "the drained answer"));
        OnVirtualThread.join(closing);

        assertEquals(List.of(), doors.openProducers(), "close leaves no producer open: " + doors.producers());
        assertNothingClosedTwice();
        for (Doors.Out o : doors.producers()) {
            assertEquals(List.of(0), o.unansweredAtClose(), o + " was closed after its flow's delivery stopped: no send of it unanswered");
        }
    }

    /** Every producer's close fails: close still calls each, once, and logs each failure at WARNING with its cause's text. */
    @Test
    void aProducerCloseFailureIsLoggedAndTheOthersStillClose() throws Exception {
        deploy(shop("shop"));
        assertEquals(6, doors.producers().size(), "fixture: shop's six producers, each flow's and its replay's");
        for (int n = 1; n <= 6; n++) doors.failProducerClose(n, "lid stuck #" + n);

        OnVirtualThread.Call<Void> closing = OnVirtualThread.start(deployments::close);
        until(closing::isDone, "close returns, whatever its outcome (FIX-30 pins the log and the other closes, not the outcome)");

        assertEquals(List.of(), doors.openProducers(), "one failed close stops no other: " + doors.producers());
        assertNothingClosedTwice();
        for (int n = 1; n <= 6; n++) {
            String text = "lid stuck #" + n;
            assertTrue(logged.stream().anyMatch(r -> r.getLevel() == Level.WARNING && texts(r).stream().anyMatch(t -> t.contains(text))),
                    "the failed close of producer #" + n + " is logged at WARNING with its cause (" + text + "): "
                            + logged.stream().map(r -> r.getLevel() + " " + texts(r)).toList());
        }
    }

    // --- a worker item still running when its flow goes (FIX-30 round 2)

    /** The producer a worker item's send is held at, and the latch that releases it. */
    record Held(Doors.Out by, CountDownLatch release) {}

    /** Accepts {@code in} through the at-least-once {@code door}; its worker's run sends it to the partner, where it is held. */
    Held acceptAndHoldAtThePartner(Doors.Door door, Envelope in) throws Exception {
        doors.closeFailsSends();
        CountDownLatch release = doors.partner().holdNext(1);
        holds.add(release);
        assertInstanceOf(Ack.Accepted.class, within(door.deliver(in), "the at-least-once accept"), "fixture: the message is accepted");
        doors.partner().awaitSent(1);
        return new Held(doors.producers().stream().filter(o -> o.sentIds().contains(in.id())).findFirst()
                .orElseThrow(() -> new AssertionError("fixture: the worker's run sent " + in.id() + " through a producer: " + doors.producers())), release);
    }

    /**
     * After the release: the held item runs to its success through its open producer — completed, no DLQ row (a closed
     * producer fails its send, and the step's failure would park it) — and only then is that producer closed, once, with no
     * send of its own unanswered.
     */
    void assertTheItemFinishesThenItsProducerCloses(Doors.Out heldBy, CountDownLatch release) {
        release.countDown();
        until(() -> queue.size() == 0, "the worker's item is completed (by its success or by a park)");
        assertEquals(0, dlq.size(), "the item was not parked: no DLQ row");
        until(() -> heldBy.closes() > 0, "the worker's producer is closed once its item finished");
        assertEquals(List.of(0), heldBy.unansweredAtClose(), heldBy + " was closed with no send of its own unanswered");
        assertNothingClosedTwice();
    }

    /**
     * {@code shop} runs; an at-least-once message's worker item is held at the partner. Deployments.close returns without
     * waiting for it, having closed the deliveries' producers; the worker's producer is not closed while the item runs and no
     * DLQ row appears. Released, the item completes by its own success and then its producer is closed, once.
     */
    @Test
    void closeLeavesARunningWorkerItemItsProducerUntilTheItemFinishes() throws Exception {
        deploy(shop("shop"));
        assertEquals(6, doors.producers().size(), "fixture: shop's six producers, each flow's and its replay's: " + doors.producers());
        Held held = acceptAndHoldAtThePartner(doors.door("shop/audit"), envelope("shop/audit"));
        Doors.Out heldBy = held.by();

        OnVirtualThread.join(OnVirtualThread.start(deployments::close));   // returns while the item is held: never waits for it

        assertEquals(0, heldBy.closes(), "the worker's producer is not closed while its item runs: " + doors.producers());
        assertEquals(0, dlq.size(), "no DLQ row while the item runs");
        assertEquals(List.of(heldBy), doors.openProducers(), "the deliveries' producers are closed by close, as before: " + doors.producers());
        assertTheItemFinishesThenItsProducerCloses(heldBy, held.release());
    }

    /**
     * A package (audit at-least-once, zz sync) whose second consumer start is held: audit's consumer runs, and one of its
     * messages' worker item is held at the partner. The start then fails: the deploy is refused without waiting for the
     * item, the worker's producer is not closed while the item runs, and no DLQ row appears. Released, the item completes by
     * its own success and then its producer is closed, once.
     */
    @Test
    void aFailedStartLeavesARunningWorkerItemItsProducerUntilTheItemFinishes() throws Exception {
        Map<String, String> modes = new LinkedHashMap<>();
        modes.put("audit", "at-least-once");
        modes.put("zz", "sync");
        Path archive = flows("shop", modes).zipTo(archives);
        Doors.Gate second = doors.holdStart(2);
        OnVirtualThread.Call<DeployResult> deploying = OnVirtualThread.start(() -> deployments.deploy("shop", Optional.empty(), archive));
        if (!second.reached().await(10, TimeUnit.SECONDS)) {
            fail("the second consumer start was not reached within 10 s; the deploy is at " + List.of(deploying.thread().getStackTrace()));
        }
        Doors.Door audit = doors.door("shop/audit");
        assertTrue(audit.started(), "fixture: audit's consumer started before the held second start: " + doors.events());
        Held held = acceptAndHoldAtThePartner(audit, envelope("shop/audit"));
        Doors.Out heldBy = held.by();

        second.fail("address in use");
        DeployRefusedException refused = assertThrows(DeployRefusedException.class, () -> OnVirtualThread.join(deploying),
                "the failed start is refused while the item is held: it never waits for it");

        assertEquals(Kind.FAILED, refused.kind(), refused.error());
        assertEquals(0, heldBy.closes(), "the worker's producer is not closed while its item runs: " + doors.producers());
        assertEquals(0, dlq.size(), "no DLQ row while the item runs");
        assertEquals(List.of(heldBy), doors.openProducers(), "the sync flow's producer is closed by the refusal, as before: " + doors.producers());
        assertTheItemFinishesThenItsProducerCloses(heldBy, held.release());
    }

    // --- P1-09c2 (D-067): a flow's replay and its producers

    /** A sync message of {@code shop/orders} delivered and answered; its rows (ingress and send) written: what a replay starts from. */
    Envelope recordedOrder() throws Exception {
        Envelope e = envelope("shop/orders");
        assertInstanceOf(Ack.Reply.class, within(doors.door("shop/orders").deliver(e), "orders' answer"), "fixture: orders answers");
        until(() -> messages.timeline(e.id()).result().size() == 2, "fixture: the rows of " + e.id());
        return e;
    }

    /** The producer {@code messageId} was sent through. */
    Doors.Out sentThrough(String messageId) {
        return doors.producers().stream().filter(o -> o.sentIds().contains(messageId)).findFirst()
                .orElseThrow(() -> new AssertionError("fixture: " + messageId + " was sent through a producer: " + doors.producers()));
    }

    /** The step names of {@code messageId}'s rows as the store holds them now (the in-memory store answers at once). */
    List<String> rowsOf(String messageId) {
        return messages.timeline(messageId).result().stream().map(io.flottila.core.record.Snapshot::stepName).toList();
    }

    /**
     * {@code shop} runs; a replay of {@code orders} (v1's) is held at the partner. A redeploy that changes orders returns while
     * that run is held — the swap never waits for the replaced replay — and from then on v1's replay refuses a new run (CLOSED)
     * while {@code replay(flowId)} answers the new version's; the producer under the held run is not closed. Released, the run
     * ends through its open producer, and only then is that producer closed, once, with no send of its own unanswered.
     */
    @Test
    void aSwapClosesTheReplacedReplayOnceItsRunEnded() throws Exception {
        deploy(shop("shop"));
        assertEquals(6, doors.producers().size(), "fixture: shop's three flows, each with its delivery's or worker's producer and its replay's: " + doors.producers());
        Envelope original = recordedOrder();
        Doors.Out deliveryV1 = sentThrough(original.id());
        Replay v1 = deployments.replay("shop/orders").orElseThrow(() -> new AssertionError("a RUNNING flow has its replay"));
        CountDownLatch release = doors.partner().holdNext(1);
        holds.add(release);
        OnVirtualThread.Call<Envelope> replaying = OnVirtualThread.start(() -> v1.from(original.id(), 1, "replay-1"));
        doors.partner().awaitSent(2);
        Doors.Out replayOut = sentThrough("replay-1");
        assertNotSame(deliveryV1, replayOut, "fixture: the replay sends through producers of its own");

        OnVirtualThread.Call<DeployResult> swapping = OnVirtualThread.start(() -> deployments.deploy("shop", Optional.empty(),
                DeploymentsRedeployTest.changed(shop("shop"), "orders", "v2").zipTo(archives)));
        until(swapping::isDone, "the redeploy returns while v1's replay run is held at the partner: the swap never waits for it");

        assertEquals(List.of("shop/orders"), OnVirtualThread.join(swapping).swapped(), "fixture: orders swapped");
        assertEquals(1, deliveryV1.closes(), "fixture: the swap closed v1 orders' delivery producer");
        assertFalse(replaying.isDone(), "fixture: the replay run is still held");
        assertEquals(0, replayOut.closes(), "the replaced replay's producer is not closed under its run in flight: " + doors.producers());
        ReplayRefusedException refused = assertThrows(ReplayRefusedException.class, () -> OnVirtualThread.call(() -> v1.from(original.id(), 1, "replay-2")),
                "the replaced replay takes no new run");
        assertEquals(ReplayRefusedException.Reason.CLOSED, refused.reason(), refused.getMessage());
        assertEquals(List.of(), rowsOf("replay-2"), "the refused run left no row");
        assertNotSame(v1, deployments.replay("shop/orders").orElseThrow(), "replay(flowId) answers the version deployed now");

        release.countDown();
        assertEquals("replay-1", OnVirtualThread.join(replaying).id(), "the held run ends through its open producer");
        until(() -> replayOut.closes() > 0, "the replaced replay's producer is closed once its run ended");
        assertEquals(1, replayOut.closes(), "once");
        assertEquals(List.of(0), replayOut.unansweredAtClose(), replayOut + " was closed with no send of its own unanswered");
        assertNothingClosedTwice();
    }

    /**
     * {@code shop} runs; a replay of {@code orders} is held at the partner. Close stops every consumer and does not return while
     * the run is held; the producer under it is closed only once that run ended — when it closes, the run's rows (its input and
     * its send) are written — and close then returns with every producer of every flow and every replay closed, once.
     */
    @Test
    void closeClosesEveryReplaysProducers() throws Exception {
        deploy(shop("shop"));
        assertEquals(6, doors.producers().size(), "fixture: shop's three flows, each with its delivery's or worker's producer and its replay's: " + doors.producers());
        Envelope original = recordedOrder();
        Replay replay = deployments.replay("shop/orders").orElseThrow(() -> new AssertionError("a RUNNING flow has its replay"));
        CountDownLatch release = doors.partner().holdNext(1);
        holds.add(release);
        OnVirtualThread.Call<Envelope> replaying = OnVirtualThread.start(() -> replay.from(original.id(), 1, "replay-1"));
        doors.partner().awaitSent(2);
        Doors.Out replayOut = sentThrough("replay-1");
        List<List<String>> rowsAtClose = new CopyOnWriteArrayList<>();
        doors.onProducerClose(o -> {
            if (o == replayOut) rowsAtClose.add(rowsOf("replay-1"));
        });

        OnVirtualThread.Call<Void> closing = OnVirtualThread.start(deployments::close);

        until(() -> doors.doors().stream().allMatch(Doors.Door::stopped), "close stops every consumer");
        assertEquals(0, replayOut.closes(), "the replay's producer is not closed under its held run: " + doors.producers());
        assertFalse(closing.isDone(), "close waits for the replay's run in flight (up to the stop deadline, which the fake clock never reaches)");
        release.countDown();
        assertEquals("replay-1", OnVirtualThread.join(replaying).id(), "the held run ends through its open producer");
        OnVirtualThread.join(closing);

        assertEquals(List.of(List.of(Replay.REPLAY_INPUT, "send")), rowsAtClose,
                "the replay's producer was closed once, after its run ended: its input's and its send's rows were written by then");
        assertEquals(List.of(0), replayOut.unansweredAtClose(), replayOut + " was closed with no send of its own unanswered");
        assertEquals(List.of(), doors.openProducers(), "close leaves no producer open, every replay's included: " + doors.producers());
        assertNothingClosedTwice();
    }
}
