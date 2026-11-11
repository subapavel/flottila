package io.flottila.core.flow;

import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.core.record.Recorder;
import io.flottila.core.record.RecordingPolicy;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P2-16b (D-121 (b), B1, B3, B5): work events between the nodes of one JVM and the reconciliation claim. Three nodes {@code n-a},
 * {@code n-b}, {@code n-c}, each a {@link Workers} registry with the started workers of flows {@code orders} and {@code invoices},
 * each joined to one {@link LocalWorkSignals.Bus} through its own {@link LocalWorkSignals}; one fake clock and timer for all.
 *
 * <p>The receiving side (B1): a payload {@code w1 <sender> <target|*> <flow|*>} for this node or {@code *} wakes the flow's
 * worker ({@code *}: every worker of the node); a payload of this node's own sender, or for another node, wakes nothing. A worker
 * woken claims once by the wake path: the backlog flag set, so a full worker claims at its chain end. Each worker has its own
 * {@link RecordingWorkQueue} over an empty in-memory queue: a claim answers at once, on the calling thread, so every wake is
 * exactly one claim, and {@link #woken} compares every worker's claim count with the expected set — the workers not named are
 * asserted not woken by the same call.
 *
 * <p>The sending side (B3): the first announce of a 50 ms window is sent at once (leading edge); later announces of the window
 * are folded into one send of their distinct payloads at its end; above 64 distinct payloads the send is the one payload
 * {@code w1 <sender> * *}.
 *
 * <p>The reconciliation (B5): a started worker claims once at its start, then every 30 s, its first reconciliation 30 s plus
 * {@code floorMod(flow.hashCode(), 30 000)} ms after the start — never on a 1 s tick.
 *
 * <p>The API assumed (packet P2-16b B1; constructors chosen by the test-author): core {@code interface WorkSignals { void
 * announce(String targetNode, String flow); }} (a null target is {@code *}); {@code new LocalWorkSignals(String node, Workers
 * workers, LocalWorkSignals.Bus bus, Timer timer)} joins {@code bus}, announces through it on {@code timer}'s windows and wakes
 * {@code workers} on what it receives; {@code new LocalWorkSignals.Bus()}; {@code Bus.publish(List<String> payloads)} is one send:
 * it hands the payloads to every joined node, the sender included, on the publishing thread before it returns (what a
 * {@code NOTIFY} is to a {@code LISTEN}ing node: {@link #bus} is published to directly where a case needs a payload no announce
 * makes). Every other case sends through {@code announce}. Policies: {@link WorkerPolicy#DEFAULT} (the reconciliation case) and
 * a ten-minute interval elsewhere, which no case reaches.
 */
@Timeout(30)
class FlowWorkerEventsTest {

    static final Path NO_FILES = Path.of("");
    static final Buffer ORDER = Buffer.buffer("<order id=\"1\"/>");
    static final Duration LEASE = Duration.ofSeconds(30);
    /** Longer than any signal case advances the clock: no claim there is a reconciliation's. */
    static final Duration QUIET = Duration.ofMinutes(10);
    static final String ORDERS = "orders";
    static final String INVOICES = "invoices";
    static final Duration WINDOW = Duration.ofMillis(50);
    static final Instant T0 = FakeRuntime.T0;

    final FakeRuntime rt = FakeRuntime.create();
    final LocalWorkSignals.Bus bus = new LocalWorkSignals.Bus();
    final InMemoryMessageStore store = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();
    final RunGate gate = new RunGate();
    final List<FlowWorker> started = new CopyOnWriteArrayList<>();
    final List<Node> nodes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        started.forEach(FlowWorker::stop);
        gate.open();
    }

    /** Waits (spinning, never sleeping) until {@code condition} holds; fails after a 5 s bound naming what it waited for. */
    static void until(BooleanSupplier condition, Supplier<String> what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) fail("not within 5 s: " + what.get());
            Thread.onSpinWait();
        }
    }

    static FlowDefinition def(String flow, int maxInFlight) {
        Map<String, Object> doc = FlowWorkerTest.flow(maxInFlight, List.of(RunGate.STEP));
        doc.put("id", flow);
        return FlowDefinition.from(doc);
    }

    /** One node: its registry, its signals on the shared bus, and per flow the queue its started worker claims from. */
    final class Node {
        final String id;
        final Workers workers = new Workers();
        final LocalWorkSignals signals;
        final Map<String, InMemoryWorkQueue> rows = new LinkedHashMap<>();
        final Map<String, RecordingWorkQueue> queues = new LinkedHashMap<>();
        final Map<String, FlowWorker> byFlow = new LinkedHashMap<>();

        Node(String id) {
            this.id = id;
            this.signals = new LocalWorkSignals(id, workers, bus, rt.timer());
            nodes.add(this);
        }

        /** Builds and starts {@code flow}'s worker here ({@code policy} null: the 3-argument form, {@link WorkerPolicy#DEFAULT}). */
        Node with(String flow, int maxInFlight, WorkerPolicy policy) {
            InMemoryWorkQueue r = new InMemoryWorkQueue(rt.clock());
            RecordingWorkQueue q = new RecordingWorkQueue(r, rt.clock());
            FlowBuilder b = new FlowBuilder(rt.runtime(), List.of(gate), List.of(BuiltInStepsTest.HEADERS), Map.of())
                    .recording(store, Meter.system()).queue(q).dlq(dlq).workers(workers);
            FlowWorker w = policy == null ? b.worker(def(flow, maxInFlight), NO_FILES, id) : b.worker(def(flow, maxInFlight), NO_FILES, id, policy);
            rows.put(flow, r);
            queues.put(flow, q);
            byFlow.put(flow, w);
            w.start();
            started.add(w);
            until(() -> q.claims().size() == 1 && q.claimsInFlight() == 0, () -> "the start claim of " + id + "/" + flow + " answered: " + q.claims());
            return this;
        }

        /** Both flows, a quiet interval, room 2 each. */
        Node withBothFlows() {
            return with(ORDERS, 2, new WorkerPolicy(LEASE, 5, QUIET)).with(INVOICES, 2, new WorkerPolicy(LEASE, 5, QUIET));
        }

        int claims(String flow) { return queues.get(flow).claims().size(); }
    }

    /** Every worker's claim count so far, keyed {@code node/flow}. */
    Map<String, Integer> claims() {
        Map<String, Integer> all = new TreeMap<>();
        for (Node n : nodes) for (String flow : n.queues.keySet()) all.put(n.id + "/" + flow, n.claims(flow));
        return all;
    }

    /** Asserts that, since {@code before}, exactly the workers in {@code woken} claimed, each once; every other one did not. */
    void woken(Map<String, Integer> before, Set<String> woken, String why) {
        Map<String, Integer> expected = new TreeMap<>();
        before.forEach((k, v) -> expected.put(k, v + (woken.contains(k) ? 1 : 0)));
        assertEquals(expected, claims(), why);
    }

    /** The three nodes with both flows each. */
    void threeNodes() {
        new Node("n-a").withBothFlows();
        new Node("n-b").withBothFlows();
        new Node("n-c").withBothFlows();
    }

    Node node(String id) {
        return nodes.stream().filter(n -> n.id.equals(id)).findFirst().orElseThrow();
    }

    // --- B5: the reconciliation claim

    /**
     * B5: the 1 s tick is gone — a started worker under {@link WorkerPolicy#DEFAULT} claims at its start, then every 30 s, its
     * first reconciliation offset by its flow's hash so the flows of one node do not claim together. Worked example (the spec's
     * {@code floorMod(hash(flow), 30 s)} in ms): {@code "orders".hashCode() = -1008770331}, {@code floorMod(·, 30000) = 9669};
     * {@code "invoices".hashCode() = 636625638}, {@code floorMod(·, 30000) = 25638}. Orders reconciles at +39.669 s, +69.669 s;
     * invoices at +55.638 s, +85.638 s; each step below lands one millisecond either side of a due instant.
     */
    @Test
    void aWorkerReconcilesEvery30SecondsFromItsFlowsOffsetAndNeverOnAOneSecondTick() {
        Node a = new Node("n-a").with(ORDERS, 2, null).with(INVOICES, 2, null);
        assertEquals(Map.of("n-a/invoices", 1, "n-a/orders", 1), claims(), "start() claims once");

        rt.clock().set(T0.plusSeconds(1));
        assertEquals(Map.of("n-a/invoices", 1, "n-a/orders", 1), claims(), "no claim at 1 s: the tick is gone");
        rt.clock().set(T0.plusSeconds(30));
        assertEquals(Map.of("n-a/invoices", 1, "n-a/orders", 1), claims(), "no claim at 30 s: the first reconciliation is offset");
        rt.clock().set(T0.plusMillis(39_668));
        assertEquals(Map.of("n-a/invoices", 1, "n-a/orders", 1), claims(), "orders: not before 30 s + 9.669 s");
        rt.clock().set(T0.plusMillis(39_669));
        assertEquals(Map.of("n-a/invoices", 1, "n-a/orders", 2), claims(), "orders reconciles at 30 s + floorMod(hash, 30 s) = 39.669 s, invoices not with it");
        rt.clock().set(T0.plusMillis(55_637));
        assertEquals(Map.of("n-a/invoices", 1, "n-a/orders", 2), claims(), "invoices: not before 30 s + 25.638 s");
        rt.clock().set(T0.plusMillis(55_638));
        assertEquals(Map.of("n-a/invoices", 2, "n-a/orders", 2), claims(), "invoices reconciles at 55.638 s");
        rt.clock().set(T0.plusMillis(69_668));
        assertEquals(Map.of("n-a/invoices", 2, "n-a/orders", 2), claims(), "orders: nothing until 30 s after its last");
        rt.clock().set(T0.plusMillis(69_669));
        assertEquals(Map.of("n-a/invoices", 2, "n-a/orders", 3), claims(), "orders again 30 s later");
        rt.clock().set(T0.plusMillis(85_637));
        assertEquals(Map.of("n-a/invoices", 2, "n-a/orders", 3), claims());
        rt.clock().set(T0.plusMillis(85_638));
        assertEquals(Map.of("n-a/invoices", 3, "n-a/orders", 3), claims(), "invoices again 30 s later");
        assertEquals(List.of(), a.queues.get(ORDERS).claims().stream().filter(c -> c.failure != null).toList(), "every claim answered");
    }

    // --- B1: the receiving side

    @Test
    void aSignalForThisNodeWakesOneClaimOfItsFlowOnly() {
        threeNodes();
        Map<String, Integer> before = claims();
        bus.publish(List.of("w1 n-x n-b orders"));
        woken(before, Set.of("n-b/orders"), "n-b's orders worker claims once; its invoices worker and the other nodes do not");
    }

    @Test
    void aSignalForEveryNodeWakesTheFlowOnEachNode() {
        threeNodes();
        Map<String, Integer> before = claims();
        bus.publish(List.of("w1 n-x * orders"));
        woken(before, Set.of("n-a/orders", "n-b/orders", "n-c/orders"), "target *: every node's orders worker, once each");
    }

    @Test
    void aSignalForEveryFlowWakesEveryWorkerOfItsTarget() {
        threeNodes();
        Map<String, Integer> before = claims();
        bus.publish(List.of("w1 n-x n-b *"));
        woken(before, Set.of("n-b/orders", "n-b/invoices"), "flow *: every worker of n-b (wakeAll), no other node's");

        before = claims();
        bus.publish(List.of("w1 n-x * *"));
        woken(before, before.keySet(), "* *: every worker of every node");
    }

    /** The second payload of the same send is the witness that n-a, n-b and n-c received the send at all. */
    @Test
    void aSignalForAnotherNodeWakesNothingHere() {
        threeNodes();
        Map<String, Integer> before = claims();
        bus.publish(List.of("w1 n-x n-z orders", "w1 n-x n-c invoices"));
        woken(before, Set.of("n-c/invoices"), "a payload for n-z wakes no node here; the one for n-c in the same send does");
    }

    /**
     * A node ignores what its own sender id sent (Postgres delivers a node's NOTIFY to its own LISTEN): its own broadcast wakes
     * the others only; the same target from another sender wakes it — it does listen.
     */
    @Test
    void aSignalFromThisNodesOwnSenderIsIgnored() {
        threeNodes();
        Map<String, Integer> before = claims();
        bus.publish(List.of("w1 n-b n-b orders", "w1 n-b * invoices"));
        woken(before, Set.of("n-a/invoices", "n-c/invoices"), "n-b's own payloads wake nothing on n-b; its broadcast wakes n-a and n-c");

        before = claims();
        bus.publish(List.of("w1 n-a n-b orders"));
        woken(before, Set.of("n-b/orders"), "the same target from another sender wakes n-b");
    }

    /**
     * B1 "the existing wake path, backlog flag set": a signal reaching a worker with no room claims nothing then, but its chain end
     * claims the row the signal announced. Without the signal no backlog is known: m1 ran in a slot (no claim filled the room) and
     * m2 was queued behind nobody's back, so the chain end would claim nothing (A6, {@code FlowWorkerHandoffTest}).
     */
    @Test
    void aSignalReachingAFullWorkerIsClaimedAtItsChainEnd() {
        Node b = new Node("n-b").with(ORDERS, 1, new WorkerPolicy(LEASE, 5, QUIET));
        new Node("n-a").with(ORDERS, 1, new WorkerPolicy(LEASE, 5, QUIET));
        RecordingWorkQueue q = b.queues.get(ORDERS);

        Envelope m1 = recorded();
        WorkQueue.Item leased = q.enqueueLeased(m1.id(), ORDERS, 1, null, "n-b", LEASE).await().orElseThrow();
        FlowWorker.Slot slot = b.workers.reserve(ORDERS).orElseThrow(() -> new AssertionError("room 1, no backlog: a slot"));
        slot.run(leased);
        until(() -> gate.runsOf(m1.id()).size() == 1, () -> "m1 runs in its slot; runs " + gate.runs());
        Envelope m2 = recorded();
        b.rows.get(ORDERS).enqueue(m2.id(), ORDERS, 1, null).await();   // a plain row nobody told n-b's worker of

        Map<String, Integer> before = claims();
        node("n-a").signals.announce("n-b", ORDERS);
        assertEquals(before, claims(), "n-b's worker is full: woken, it claims nothing now");

        RunGate.Run run = gate.runsOf(m1.id()).get(0);
        run.release();
        run.joined();
        until(() -> gate.runsOf(m2.id()).size() == 1, () -> "m1's chain end claims m2, the backlog the signal set; claims " + q.claims());
        assertEquals(before.get("n-b/orders") + 1, b.claims(ORDERS), "one claim, at the chain end");
        assertEquals(List.of(m2.id()), q.claims().getLast().items.stream().map(WorkQueue.Item::messageId).toList(), "and it took m2");
    }

    /** A message whose ingress snapshot is recorded and body stored, nothing queued. */
    Envelope recorded() {
        Envelope in = rt.ingress(ORDERS, Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), ORDER);
        store.record(new Recorder(store, rt.clock(), Meter.system(), Map.of()).startSnapshot(in, Recorder.INGRESS, RecordingPolicy.DEFAULT)).await();
        return in;
    }

    // --- B3: the sending side

    @Test
    void theFirstAnnounceOfAWindowIsSentAtOnce() {
        threeNodes();
        Map<String, Integer> before = claims();
        node("n-a").signals.announce("n-b", ORDERS);
        woken(before, Set.of("n-b/orders"), "the leading edge: sent at once, the clock not moved");
    }

    @Test
    void aNullTargetIsAnnouncedToEveryNodeButTheSender() {
        threeNodes();
        Map<String, Integer> before = claims();
        node("n-a").signals.announce(null, ORDERS);
        woken(before, Set.of("n-b/orders", "n-c/orders"), "target null = *: every other node's orders worker; the sender ignores its own");
    }

    @Test
    void laterAnnouncesInTheWindowAreFoldedIntoOneSendAtItsEnd() {
        threeNodes();
        LocalWorkSignals a = node("n-a").signals;
        Map<String, Integer> before = claims();
        a.announce("n-b", ORDERS);
        woken(before, Set.of("n-b/orders"), "the leading edge");

        before = claims();
        a.announce("n-b", ORDERS);
        a.announce("n-b", ORDERS);
        a.announce("n-b", INVOICES);
        a.announce("n-c", ORDERS);
        assertEquals(before, claims(), "later announces of the window are not sent at once");
        rt.clock().advance(WINDOW.minusMillis(1));
        assertEquals(before, claims(), "nor before the window's end");
        rt.clock().advance(Duration.ofMillis(1));
        woken(before, Set.of("n-b/orders", "n-b/invoices", "n-c/orders"), "at the window's end, one send of the distinct payloads: each target woken once");
    }

    @Test
    void anAnnounceAfterAQuietWindowIsSentAtOnce() {
        threeNodes();
        LocalWorkSignals a = node("n-a").signals;
        a.announce("n-b", ORDERS);
        Map<String, Integer> before = claims();
        rt.clock().advance(WINDOW);
        assertEquals(before, claims(), "a window with nothing more announced sends nothing at its end");

        a.announce("n-c", INVOICES);
        woken(before, Set.of("n-c/invoices"), "the window is over: the next announce is a leading edge again");
    }

    /** B3 cap, at the boundary: 64 distinct payloads folded at the window's end are sent as they are. */
    @Test
    void aWindowOf64DistinctAnnouncesIsSentAsTheyAre() {
        threeNodes();
        LocalWorkSignals a = node("n-a").signals;
        a.announce("n-b", ORDERS);   // the leading edge
        Map<String, Integer> before = claims();
        a.announce("n-b", INVOICES);
        for (int i = 1; i <= 63; i++) a.announce("n-b", "ghost-" + i);   // flows no node runs: 64 distinct payloads in all
        rt.clock().advance(WINDOW);
        woken(before, Set.of("n-b/invoices"), "64 distinct payloads: sent as announced, n-b's invoices worker only");
    }

    /** B3 cap, one above the boundary: 65 distinct payloads fold to {@code w1 n-a * *} — every worker of every other node. */
    @Test
    void aWindowOfMoreThan64DistinctAnnouncesFoldsToEveryNodeAndFlow() {
        threeNodes();
        LocalWorkSignals a = node("n-a").signals;
        a.announce("n-b", ORDERS);   // the leading edge
        Map<String, Integer> before = claims();
        a.announce("n-b", INVOICES);
        for (int i = 1; i <= 64; i++) a.announce("n-b", "ghost-" + i);   // 65 distinct payloads
        rt.clock().advance(WINDOW);
        woken(before, Set.of("n-b/orders", "n-b/invoices", "n-c/orders", "n-c/invoices"),
                "65 distinct payloads: one w1 n-a * * — every worker of n-b and n-c, none of the sender's");
    }
}
