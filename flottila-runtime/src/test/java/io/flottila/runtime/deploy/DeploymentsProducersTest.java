package io.flottila.runtime.deploy;

import io.flottila.api.Ack;
import io.flottila.api.Envelope;
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
        deployments = new Deployments(NodeConfig.from(env), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), List.of(), List.of(), messages, dlq,
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
                built = 4;
            }
            case "a sync flow whose consumer is refused" -> {
                p.endpointDoc("orders-in").getJsonObject("config").put("refuse", "no door today");
                built = 3;
            }
            case "an at-least-once flow whose worker does not build" -> {
                p.flowDoc("audit").put("steps", PackageDraft.toPartner().add(new JsonObject().put("type", "stamp").put("name", "s")));
                built = 3;
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
        assertEquals(1, doors.producers().size(), "fixture: store built one producer");
        Doors.Out store = doors.producers().get(0);
        doors.failStart(2, "address in use");

        DeployRefusedException refused = refused(shop("shop"));

        assertEquals(Kind.FAILED, refused.kind(), refused.error());
        assertEquals(4, doors.producers().size(), "fixture: shop built three producers: " + doors.producers());
        assertEquals(List.of(store), doors.openProducers(), "every producer of the failed deploy is closed, the running package's is not: " + doors.producers());
        assertNothingClosedTwice();
        Ack still = within(doors.door("store/orders").deliver(envelope("store/orders")), "store's answer after shop's failed start");
        assertInstanceOf(Ack.Reply.class, still, "store's flow still sends through its open producer: " + still);
    }

    Envelope envelope(String flow) {
        return rt.ingress(flow, Headers.empty(), Buffer.buffer("<order/>"));
    }

    // --- close

    /**
     * Two packages run (four producers); a sync message is held at the partner. Close stops every flow; while that message
     * drains, the producer it was sent through is not closed. Once it answered, close returns with every producer closed once,
     * and none was closed with a send of its own unanswered: each after its flow's delivery stopped.
     */
    @Test
    void closeClosesEveryProducerOnceEachAfterItsDeliveryStopped() throws Exception {
        deploy(shop("shop"));
        deploy(flows("store", Map.of("orders", "sync")));
        assertEquals(4, doors.producers().size(), "fixture: shop's three producers and store's one: " + doors.producers());
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
        assertEquals(3, doors.producers().size(), "fixture: shop's three producers");
        for (int n = 1; n <= 3; n++) doors.failProducerClose(n, "lid stuck #" + n);

        OnVirtualThread.Call<Void> closing = OnVirtualThread.start(deployments::close);
        until(closing::isDone, "close returns, whatever its outcome (FIX-30 pins the log and the other closes, not the outcome)");

        assertEquals(List.of(), doors.openProducers(), "one failed close stops no other: " + doors.producers());
        assertNothingClosedTwice();
        for (int n = 1; n <= 3; n++) {
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
        assertEquals(3, doors.producers().size(), "fixture: shop's three producers: " + doors.producers());
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
}
