package dev.rail8.runtime.deploy;

import dev.rail8.api.Ack;
import dev.rail8.api.BlobKey;
import dev.rail8.api.BlobStore;
import dev.rail8.api.Body;
import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.api.Runtime;
import dev.rail8.api.Stage;
import dev.rail8.api.StepConfig;
import dev.rail8.api.StepFactory;
import dev.rail8.api.StepResources;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static dev.rail8.runtime.deploy.DeploymentsTest.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A planned stop of a node (a deploy or a restart: {@link Deployments#close}) never makes a partner see a message twice. Two
 * nodes share one work queue, one message store and one DLQ, all in memory, and one fake clock that drives every timer: the
 * stop deadline, the worker's lease renewals, its poll tick and a run's time limit. Each node has its own {@link Doors}
 * component; its partner records every send, so a message's deliveries are its sends to either node's partner. A sender
 * whose message is answered with a retryable Nack hands it to the other node, as a sender behind a load balancer does.
 *
 * <p>When node 1 begins to stop, it holds one message of each kind: one being received (its body's persist held, so its save
 * is in flight), one being processed (its run held in the {@code gate} step) and one being sent (its send held at the
 * partner). A fourth message arrives once the stop has begun. The clock then runs past a whole work lease, within the stop
 * deadline, while node 2 polls. Each message reaches the partners exactly once: the stop waits for the run and the send it
 * holds, keeps their leases renewed so that node 2 does not take them, hands the saved message to node 2 and refuses the
 * late one, which node 2 then accepts.
 */
@Timeout(60)
class PlannedStopTest {

    static final String FLOW = "relay/orders";
    /** The stop deadline: longer than one work lease, so the drain outlives a lease that is not renewed. */
    static final Duration STOP_DEADLINE = Duration.ofMinutes(2);
    /**
     * A worker's default lease and poll interval ({@code WorkerPolicy.DEFAULT}, 30 s each) plus a second: by then a lease that
     * is not renewed is lost, and node 2 has polled at least once, whatever offset its first poll tick has.
     */
    static final Duration PAST_A_LEASE = Duration.ofSeconds(61);
    static final Duration BOUND = Duration.ofSeconds(10);

    @TempDir Path tmp;

    final FakeRuntime rt = FakeRuntime.create();
    final CountingQueue queue = new CountingQueue(rt.clock());
    final InMemoryMessageStore messages = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();
    final GateStep gate = new GateStep();
    final HeldPersists blobs = new HeldPersists(rt.blobs());
    final Doors doors1 = new Doors();
    final Doors doors2 = new Doors();
    final List<CountDownLatch> holds = new CopyOnWriteArrayList<>();
    Deployments node1;
    Deployments node2;
    OnVirtualThread.Call<Void> stopping;

    @BeforeEach
    void startBothNodes() throws Exception {
        node1 = node("node-1", doors1);
        node2 = node("node-2", doors2);
        for (Deployments node : List.of(node1, node2)) {
            Path folder = relay().folderIn(tmp.resolve("archives-" + System.identityHashCode(node)));
            OnVirtualThread.call(() -> node.deploy("relay", folder));
        }
    }

    @AfterEach
    void releaseAndStop() throws Exception {
        holds.forEach(CountDownLatch::countDown);
        gate.releaseAll();
        blobs.releaseAll();
        doors1.releaseAll();
        doors2.releaseAll();
        if (stopping != null) OnVirtualThread.join(stopping);
        else OnVirtualThread.call(() -> { node1.close(); return null; });
        OnVirtualThread.call(() -> { node2.close(); return null; });
    }

    // --- fixture

    /** The deployments of node {@code id}, on the shared stores and the fake clock, with its own component and data directory. */
    Deployments node(String id, Doors doors) {
        Map<String, String> env = new HashMap<>();
        env.put("RAIL8_ENV", "dev");
        env.put("RAIL8_NODE_ID", id);
        env.put("RAIL8_DATA_DIR", tmp.resolve(id).toString());
        env.put("RAIL8_STOP_DEADLINE", STOP_DEADLINE.toString());
        env.put("RAIL8_BLOB_DURABLE_MAX", Long.toString(DeploymentsTest.DURABLE_MAX));
        env.put("RAIL8_MAX_PACKAGE_BYTES", Long.toString(DeploymentsTest.MAX_PACKAGE));
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
        return new Deployments(NodeConfig.from(env), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), List.of(gate), List.of(),
                messages, dlq, queue, () -> 0L);
    }

    /**
     * Package {@code relay}: the at-least-once flow {@code orders} from its door {@code in} through the step {@code gate} to
     * {@code partner}. A run's time limit (5 min) outlasts the stop deadline, so no run is cut while the case holds it.
     */
    static PackageDraft relay() {
        JsonArray steps = new JsonArray().add(new JsonObject().put("type", GateStep.TYPE).put("name", "hold")).addAll(PackageDraft.toPartner());
        PackageDraft p = PackageDraft.of("relay", "1.0.0")
                .endpoint("in", "in", new JsonObject().put("path", "/orders"))
                .endpoint("partner", "out", new JsonObject().put("url", "rec://partner"))
                .flow("orders", "1.0.0", "at-least-once", "in", 1024, steps);
        p.flowDoc("orders").getJsonObject("delivery").put("timeout", "5m");
        p.flowDoc("orders").getJsonObject("limits").put("maxInFlight", 8);
        return p;
    }

    /** A message of the flow whose body lives in {@code store}. */
    Envelope message(BlobStore store) {
        String id = rt.ids().next();
        Body body = store.write(BlobKey.body(id, 0), Buffer.buffer("<order/>".getBytes(StandardCharsets.UTF_8)), 1024).await();
        return Envelope.ingress(id, FLOW, Headers.empty(), body);
    }

    /** The final answer to {@code e}: node 1's {@code firstAnswer}, or after a retryable Nack its sender's delivery to node 2. */
    Ack sentWithRetry(Envelope e, Future<Ack> firstAnswer) throws Exception {
        Ack ack = within(firstAnswer, e.id() + "'s answer from node 1");
        if (ack instanceof Ack.Nack nack && nack.retryable()) ack = within(doors2.door(FLOW).deliver(e), e.id() + "'s answer from node 2");
        return ack;
    }

    /** How often message {@code id} was sent to the partner, by either node. */
    long deliveries(String id) {
        return sends(doors1, id) + sends(doors2, id);
    }

    static long sends(Doors doors, String id) {
        return doors.partner().sent().stream().filter(e -> e.id().equals(id)).count();
    }

    /** The next send to node 1's partner is held until the latch is counted down. */
    CountDownLatch holdNextSend() {
        CountDownLatch hold = doors1.partner().holdNext(1);
        holds.add(hold);
        return hold;
    }

    /** Advances the fake clock by {@code by} in one-second steps, so every timer due meanwhile fires at its own second. */
    void advance(Duration by) {
        for (long s = 0; s < by.toSeconds(); s++) rt.clock().advance(Duration.ofSeconds(1));
    }

    /** Spins (yielding, never sleeping) until {@code condition} holds; fails after {@link #BOUND} naming what never happened. */
    static void until(BooleanSupplier condition, Supplier<String> what) {
        long deadline = System.nanoTime() + BOUND.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("not within " + BOUND + ": " + what.get());
            Thread.onSpinWait();
            Thread.yield();
        }
    }

    // --- the case

    @Test
    void messagesBeingReceivedProcessedOrSentAreDeliveredOnce() throws Exception {
        // being sent: its run passed the gate, its send is held at node 1's partner
        CountDownLatch send = holdNextSend();
        Envelope sending = message(rt.blobs());
        Future<Ack> sendingAnswer = doors1.door(FLOW).deliver(sending);
        assertEquals(new Ack.Accepted(sending.id()), within(sendingAnswer, "the message being sent is accepted"));
        doors1.partner().awaitSent(1);

        // being processed: its run waits in the gate step
        GateStep.Hold processing = gate.holdNext();
        Envelope inStep = message(rt.blobs());
        assertEquals(new Ack.Accepted(inStep.id()), within(doors1.door(FLOW).deliver(inStep), "the message being processed is accepted"));
        assertTrue(processing.entered().await(BOUND.toMillis(), TimeUnit.MILLISECONDS), "its run reached the gate step");

        // being received: its save is in flight, its body's persist held
        Promise<Void> persist = blobs.holdNextPersist();
        Envelope receiving = message(blobs);
        Future<Ack> receivingAnswer = doors1.door(FLOW).deliver(receiving);
        until(() -> blobs.persistsHeld() == 1, () -> "the save of the message being received waits for its body's persist");
        assertFalse(receivingAnswer.isComplete(), "its save is in flight: no answer yet");

        // the planned stop of node 1 begins: once its door is stopped and the stop waits, every part of the flow was told to stop
        stopping = OnVirtualThread.start(() -> node1.close());
        until(() -> doors1.door(FLOW).stopped() && stopping.thread().getState() == Thread.State.WAITING,
                () -> "node 1's stop has stopped its door and waits: " + stopping.thread().getState());

        // a message that arrives once the stop has begun is refused by node 1 and accepted by node 2
        Envelope late = message(rt.blobs());
        Future<Ack> lateAtNode1 = doors1.door(FLOW).deliver(late);
        Ack lateAck = sentWithRetry(late, lateAtNode1);
        assertTrue(within(lateAtNode1, "the late message's answer from node 1") instanceof Ack.Nack nack && nack.retryable(),
                "node 1 refuses a message once its stop has begun, retryably: " + lateAtNode1.result());
        assertEquals(new Ack.Accepted(late.id()), lateAck, "node 2 accepts the late message");

        // the save in flight finishes: the message is accepted, and the stopped node hands it over to the queue
        persist.complete();
        assertEquals(new Ack.Accepted(receiving.id()), within(receivingAnswer, "the message being received is accepted once its save finished"));

        // past a whole work lease, within the stop deadline: node 2 polls, node 1 still runs what it holds
        advance(PAST_A_LEASE);
        until(() -> sends(doors2, receiving.id()) == 1, () -> "node 2 takes over the message that was saved while node 1 stopped");
        assertEquals(0, sends(doors1, receiving.id()), "node 1 did not send the message it handed over");
        assertFalse(stopping.isDone(), "node 1's stop waits for the run in the gate step and the send at the partner");
        assertEquals(0, sends(doors2, inStep.id()) + sends(doors2, sending.id()),
                "node 2 takes neither the message being processed nor the one being sent: node 1 renews their leases while it stops");

        processing.release().countDown();
        send.countDown();
        OnVirtualThread.join(stopping);
        assertEquals(1, sends(doors1, inStep.id()), "node 1 finished processing its message and sent it");

        // long after the stop: every lease has run out many times, node 2 polls, nothing is sent again
        advance(Duration.ofMinutes(10));
        until(() -> deliveries(late.id()) == 1, () -> "node 2 sent the late message");
        List<String> once = new ArrayList<>();
        for (Envelope e : List.of(sending, inStep, receiving, late)) {
            if (deliveries(e.id()) == 1) once.add(e.id());
        }
        assertEquals(List.of(sending.id(), inStep.id(), receiving.id(), late.id()), once,
                "every message reached the partners exactly once: node 1 sent " + doors1.partner().sent().stream().map(Envelope::id).toList()
                        + ", node 2 sent " + doors2.partner().sent().stream().map(Envelope::id).toList());
        assertEquals(0, queue.size(), "every message settled: nothing is left in the work queue");
    }

    // --- test doubles

    /** Step type {@code gate}: a run waits in it while a hold is armed for it, then hands on; with none it hands on at once. */
    static final class GateStep implements StepFactory {
        static final String TYPE = "gate";

        /** One run held in the step: {@code entered} once it waits, and it hands on once {@code release} is counted down. */
        record Hold(CountDownLatch entered, CountDownLatch release) {}

        private final Queue<Hold> armed = new ConcurrentLinkedQueue<>();
        private final List<Hold> all = new CopyOnWriteArrayList<>();

        /** The next run to enter the step waits there until the hold's release. */
        Hold holdNext() {
            Hold h = new Hold(new CountDownLatch(1), new CountDownLatch(1));
            armed.add(h);
            all.add(h);
            return h;
        }

        void releaseAll() {
            all.forEach(h -> h.release().countDown());
        }

        @Override public String type() { return TYPE; }
        @Override public String configSchema() { return "{\"type\":\"object\"}"; }

        @Override
        public Stage create(StepConfig step, StepResources resources) {
            return (e, rest) -> {
                Hold h = armed.poll();
                if (h != null) {
                    h.entered().countDown();
                    try {
                        h.release().await();
                    } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("the run of " + e.id() + " was interrupted in the gate step", stopped);
                    }
                }
                return rest.apply(e);
            };
        }
    }

    /** A blob store over another that holds the next persist, once armed, until the test completes its promise. */
    static final class HeldPersists implements BlobStore {
        private final BlobStore delegate;
        private final Queue<Promise<Void>> armed = new ConcurrentLinkedQueue<>();
        private final List<Promise<Void>> all = new CopyOnWriteArrayList<>();
        private final List<BlobKey> held = new CopyOnWriteArrayList<>();

        HeldPersists(BlobStore delegate) {
            this.delegate = delegate;
        }

        /** The next persist waits, its bytes stored, until the returned promise completes. */
        Promise<Void> holdNextPersist() {
            Promise<Void> p = Promise.promise();
            armed.add(p);
            all.add(p);
            return p;
        }

        /** How many persists have waited on a hold so far. */
        int persistsHeld() {
            return held.size();
        }

        void releaseAll() {
            all.forEach(Promise::tryComplete);
        }

        @Override public long treeLimit() { return delegate.treeLimit(); }
        @Override public Future<Body> write(BlobKey key, Buffer whole, long maxSize) { return delegate.write(key, whole, maxSize).map(this::rebind); }
        @Override public Future<Body> write(BlobKey key, ReadStream<Buffer> in, long maxSize) { return delegate.write(key, in, maxSize).map(this::rebind); }
        @Override public ReadStream<Buffer> open(BlobKey key) { return delegate.open(key); }
        @Override public Future<Buffer> read(BlobKey key) { return delegate.read(key); }

        @Override
        public Future<Void> persist(BlobKey key) {
            Promise<Void> hold = armed.poll();
            Future<Void> persisted = delegate.persist(key);
            if (hold == null) return persisted;
            held.add(key);
            return persisted.compose(done -> hold.future());
        }

        /** The body as written, over this store: its persist is asked here. */
        private Body rebind(Body written) {
            return Body.of(this, written.key(), written.hash(), written.size());
        }
    }
}
