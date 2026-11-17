package dev.rail8.core.flow;

import dev.rail8.api.Ack;
import dev.rail8.api.EndpointConfig;
import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.api.Runtime;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.dlq.Parked;
import dev.rail8.core.queue.InMemoryWorkQueue;
import dev.rail8.core.queue.WorkQueue;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.core.record.MessageStore;
import dev.rail8.core.record.Meter;
import dev.rail8.core.record.Page;
import dev.rail8.testkit.FakeRuntime;
import dev.rail8.testkit.RecordingComponent;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static dev.rail8.core.flow.FlowWorkerTest.NO_FILES;
import static dev.rail8.core.flow.FlowWorkerTest.until;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CONSIDER-24 step 1 (the staged death), cases 1 to 5: these pin today's behaviour, they do not ask for a change. A run's rows —
 * its final step's included — are written when its trail closes; only then is its work row completed, by another call. A node that
 * dies between the two leaves the row leased; at the lease's end another node takes it as the next attempt and runs the message
 * again from its ingress: the partner gets the same id twice, the store keeps a final row per attempt. A later decision (a run
 * skipped when an earlier attempt recorded its final step; one transaction for the rows and the settle) would turn cases 1 to 3 red.
 *
 * <p>One in-memory database — a message store, a work queue, a DLQ — and a {@link RecordingComponent} partner; nodes are workers
 * built over it, each through a {@link NodeQueue} (its connection to the queue, the store boundary) and a {@link HoldingMessageStore}
 * (its connection to the store), each on a {@link FlowWorkerTest.KillableTimer}. A node's death is staged at the seam each case
 * names: its timers silenced and nothing it asks reaching the queue from then on — no settle, no renewal, no claim; a call it made
 * is never answered. The fake clock ends the lease; nothing sleeps. The flow is at-least-once, one partner step (case 2: a key step
 * before it, for the partition key).
 */
@Timeout(30)
class FlowWorkerRerunWindowTest {

    static final Duration LEASE = Duration.ofSeconds(30);
    static final Duration TICK = Duration.ofSeconds(1);
    static final Buffer ORDER = Buffer.buffer("<order id=\"1\"/>");
    /** The flow's final step: the partner's {@code to}. */
    static final String FINAL = "partner";

    final World w = new World();

    @AfterEach
    void tearDown() {
        w.close();
    }

    // --- fixtures

    /** Where a node dies: at the call that would settle its item, or once the queue answered that call. */
    enum Death {
        /** The unkeyed complete ({@code completeUnkeyed}) never reaches the queue. */
        AT_THE_UNKEYED_COMPLETE,
        /** The keyed fast path ({@code completeAndTakeNext}) never reaches the queue. */
        AT_THE_FAST_PATH,
        /** The unkeyed complete reaches the queue and completes the row; its answer never reaches the node. */
        AFTER_THE_UNKEYED_COMPLETE,
        /** Not at a queue call: the test stages the death itself ({@link Node#die}). */
        STAGED_BY_THE_TEST
    }

    /** What the one database held, and what the partner had received, when a node died. */
    record AtDeath(String where, List<String> finalRows, int workRows, int sends) {}

    static <T> Future<T> unanswered() {
        return Promise.<T>promise().future();
    }

    /**
     * A node's connection to the one work queue: every call goes to {@code rows} until the node dies; its {@link Death} seam kills it
     * there; from then on nothing it asks reaches {@code rows} and nothing is answered. Records the items handed out to it.
     */
    static final class NodeQueue implements WorkQueue {
        final InMemoryWorkQueue rows;
        final Death death;
        final Consumer<String> dies;
        volatile boolean dead;
        final List<Item> handedOut = new CopyOnWriteArrayList<>();

        NodeQueue(InMemoryWorkQueue rows, Death death, Consumer<String> dies) {
            this.rows = rows;
            this.death = death;
            this.dies = dies;
        }

        @Override public Future<Void> enqueue(String messageId, String flow, int step, String partitionKey) {
            return dead ? unanswered() : rows.enqueue(messageId, flow, step, partitionKey);
        }
        @Override public Future<Optional<Item>> enqueueLeased(String messageId, String flow, int step, String partitionKey, String workerId, Duration lease) {
            return dead ? unanswered() : rows.enqueueLeased(messageId, flow, step, partitionKey, workerId, lease);
        }
        @Override public Future<List<Item>> claim(String workerId, String flow, int max, Duration lease) {
            return dead ? unanswered() : rows.claim(workerId, flow, max, lease).onSuccess(handedOut::addAll);
        }
        @Override public Future<Boolean> complete(long seq, int attempts) {
            return dead ? unanswered() : rows.complete(seq, attempts);
        }
        @Override public Future<Completion> completeRow(long seq, int attempts) {
            return dead ? unanswered() : rows.completeRow(seq, attempts);
        }
        @Override public Future<Completion> completeUnkeyed(long seq, int attempts) {
            if (dead) return unanswered();
            if (death == Death.AT_THE_UNKEYED_COMPLETE) {
                dies.accept("completeUnkeyed@" + attempts + ", not sent");
                return unanswered();
            }
            Future<Completion> done = rows.completeUnkeyed(seq, attempts);
            if (death == Death.AFTER_THE_UNKEYED_COMPLETE) {
                assertTrue(done.isComplete(), "fixture: the in-memory queue answers at once");
                dies.accept("completeUnkeyed@" + attempts + ", completed " + done.result().completed() + ", the answer lost");
                return unanswered();
            }
            return done;
        }
        @Override public Future<Completion> completeAndTakeNext(long seq, int attempts, String workerId, Duration lease) {
            if (dead) return unanswered();
            if (death == Death.AT_THE_FAST_PATH) {
                dies.accept("completeAndTakeNext@" + attempts + ", not sent");
                return unanswered();
            }
            return rows.completeAndTakeNext(seq, attempts, workerId, lease);
        }
        @Override public Future<Boolean> renew(long seq, int attempts, Duration lease) {
            return dead ? unanswered() : rows.renew(seq, attempts, lease);
        }
        @Override public Future<Boolean> defer(long seq, int attempts, Duration delay) {
            return dead ? unanswered() : rows.defer(seq, attempts, delay);
        }
        @Override public Future<Boolean> stop(long seq, int attempts, Parked failure) {
            return dead ? unanswered() : rows.stop(seq, attempts, failure);
        }
        @Override public Future<Page<Stopped>> stopped(Optional<String> after, int limit) {
            return dead ? unanswered() : rows.stopped(after, limit);
        }
        @Override public Future<Boolean> hold(long seq, int attempts) {
            return dead ? unanswered() : rows.hold(seq, attempts);
        }
        @Override public Future<Page<Held>> held(Optional<String> after, int limit) {
            return dead ? unanswered() : rows.held(after, limit);
        }
        @Override public Future<Boolean> release(long seq) {
            return dead ? unanswered() : rows.release(seq);
        }
        @Override public Future<Optional<Released>> releaseRow(long seq) {
            return dead ? unanswered() : rows.releaseRow(seq);
        }
        @Override public Future<Boolean> hasItems(String flow) {
            return dead ? unanswered() : rows.hasItems(flow);
        }
    }

    /** The one database (messages, work, DLQ), the partner, and the nodes built over them. */
    static final class World {
        final FakeRuntime rt = FakeRuntime.create();
        final RecordingComponent partner = new RecordingComponent("mock");
        final InMemoryMessageStore messages = new InMemoryMessageStore();
        final InMemoryWorkQueue work = new InMemoryWorkQueue(rt.clock());
        final InMemoryDlq dlq = new InMemoryDlq();
        final Map<String, EndpointConfig> endpoints = Map.of("partner", new EndpointConfig("partner", URI.create("mock://partner"), Map.of()));
        final List<Node> nodes = new CopyOnWriteArrayList<>();
        /** The message the case runs: what {@link AtDeath} reads the rows of. */
        volatile String id;

        World() {
            rt.withComponent(partner);
        }

        FlowBuilder builder(Runtime runtime, MessageStore store, WorkQueue queue) {
            return new FlowBuilder(runtime, List.of(), List.of(BuiltInStepsTest.HEADERS), endpoints)
                    .recording(store, Meter.system()).queue(queue).dlq(dlq);
        }

        /** A fresh message through the flow's at-least-once delivery (its intake straight on the database): saved and queued, nothing run. */
        Envelope accept(FlowDefinition def, Headers headers) throws Exception {
            Envelope in = rt.ingress("orders", headers.with(Envelope.H_CONTENT_TYPE, "application/xml"), ORDER);
            Delivery delivery = builder(rt.runtime(), messages, work).delivery(def, NO_FILES);
            assertEquals(new Ack.Accepted(in.id()), delivery.apply(in).await(5, SECONDS));
            assertEquals(1, work.size(), "fixture: one work row queued");
            id = in.id();
            return in;
        }

        Node node(String name, FlowDefinition def, Death death) {
            Node n = new Node(name, def, death);
            nodes.add(n);
            return n;
        }

        /** "attempt k OUTCOME" of each of the message's rows of the final step, in the timeline's order (attempt, step). */
        List<String> finalRows(String messageId) {
            return messages.timeline(messageId).await().stream().filter(s -> FINAL.equals(s.stepName()))
                    .map(s -> "attempt " + s.attempt() + " " + s.outcome()).toList();
        }

        List<String> sentIds() {
            return partner.sentTo("partner").stream().map(Envelope::id).toList();
        }

        void close() {
            for (Node n : nodes) {
                n.worker.stop();
                n.store.fail(new IllegalStateException("the case is over"));   // a run held at its rows ends (its settle reaches nothing)
            }
        }

        /** A node: a worker over its own connections to the one database, on a timer of its own. */
        final class Node {
            final String name;
            final FlowWorkerTest.KillableTimer timer = new FlowWorkerTest.KillableTimer(rt.timer());
            final HoldingMessageStore store = new HoldingMessageStore(messages);
            final NodeQueue queue;
            final FlowWorker worker;
            volatile AtDeath atDeath;

            Node(String name, FlowDefinition def, Death death) {
                this.name = name;
                this.queue = new NodeQueue(work, death, this::die);
                Runtime r = rt.runtime();
                Runtime own = new Runtime(r.clock(), timer, r.ids(), r.blobs(), r.hasher(), r.components());
                this.worker = builder(own, store, queue).worker(def, NO_FILES, name, new WorkerPolicy(LEASE, TICK));
            }

            /** Dies now: its timers silenced, nothing it asks reaches the queue; what the database held then is kept. */
            void die(String where) {
                timer.kill();
                queue.dead = true;
                atDeath = new AtDeath(where, finalRows(id), work.size(), partner.sentTo("partner").size());
            }
        }
    }

    static FlowDefinition unkeyed() {
        return FlowDefinition.from(FlowWorkerTest.flow(4, List.of(FlowWorkerTest.TO_PARTNER)));
    }

    /** Partitioned by {@code orderId}, the header {@code x-order} ({@link DeliveryTest#ORDER_KEY}), then the partner. */
    static FlowDefinition keyed() {
        Map<String, Object> doc = FlowWorkerTest.flow(4, List.of(DeliveryTest.ORDER_KEY, FlowWorkerTest.TO_PARTNER));
        doc.put("partitionBy", "orderId");
        return FlowDefinition.from(doc);
    }

    /** {@code dead}'s first claim of the one row: its run starts, and the case waits for the death the seam stages. */
    static WorkQueue.Item claimedBy(World.Node dead) {
        dead.worker.poll();   // its round never ends: the node dies in it
        until(() -> dead.atDeath != null, () -> dead.name + " reached its death seam; handed out " + dead.queue.handedOut + ", work rows " + dead.queue.rows.size());
        assertEquals(1, dead.queue.handedOut.size(), "fixture: " + dead.name + " was handed the row once");
        WorkQueue.Item first = dead.queue.handedOut.get(0);
        assertEquals(1, first.attempts(), "fixture: its first hand-out");
        return first;
    }

    /**
     * The second worker polls 1 ms before the dead node's lease ends — and takes nothing, the row still the dead node's — and again
     * at its end, when it takes the row and runs it to its settle. Returns the item it was handed.
     */
    WorkQueue.Item takenOverAtTheLeaseEnd(World.Node second, WorkQueue.Item left, String deadName) throws Exception {
        w.rt.clock().advance(LEASE.minusMillis(1));
        assertEquals(0, second.worker.poll().await(5, SECONDS), "1 ms before the lease's end the second worker's poll takes nothing");
        assertEquals(Optional.of(deadName), w.work.leaseHolder(left.seq()), "the row is still leased to the dead node");
        w.rt.clock().advance(Duration.ofMillis(1));
        assertEquals(1, second.worker.poll().await(5, SECONDS), "at the lease's end the second worker takes the row and runs it");
        until(() -> second.worker.unsettled() == 0, () -> "the second worker's run settled");
        assertEquals(1, second.queue.handedOut.size(), "the second worker was handed the row once: " + second.queue.handedOut);
        return second.queue.handedOut.get(0);
    }

    // --- case 1

    /**
     * Case 1: death after the final row is recorded and before the work row is completed, an item without a key. The row is the
     * dead node's until its lease ends; then the second worker takes it as attempt 2 (in doubt) and runs it from the ingress: the
     * partner receives the same id twice, the store keeps an OK final row for attempt 1 and for attempt 2, and the queue is empty.
     */
    @Test
    void aNodeDeadBetweenItsFinalRowAndItsUnkeyedCompleteIsRunAgainAtTheLeaseEndAndThePartnerGetsTheIdTwice() throws Exception {
        FlowDefinition def = unkeyed();
        Envelope in = w.accept(def, Headers.empty());
        World.Node n1 = w.node("n1", def, Death.AT_THE_UNKEYED_COMPLETE);
        World.Node n2 = w.node("n2", def, Death.STAGED_BY_THE_TEST);

        WorkQueue.Item first = claimedBy(n1);
        assertNull(first.partitionKey(), "fixture: an item without a key");
        assertEquals(new AtDeath("completeUnkeyed@1, not sent", List.of("attempt 1 OK"), 1, 1), n1.atDeath,
                "n1 died at its settle: the final row of attempt 1 recorded, the work row still there, the partner called once");

        WorkQueue.Item second = takenOverAtTheLeaseEnd(n2, first, "n1");
        assertEquals(first.seq(), second.seq(), "the same row");
        assertEquals(2, second.attempts(), "taken as attempt 2");
        assertEquals(1, second.doubts(), "in doubt: n1 left it unsettled");
        assertEquals(List.of(in.id(), in.id()), w.sentIds(), "the partner received the same id twice");
        Envelope rerun = w.partner.sentTo("partner").get(1);
        assertEquals(in.body().hash(), rerun.body().hash(), "the second run started from the ingress: the arrived body");
        assertEquals(Optional.of(2), rerun.get(Envelope.ATTEMPT), "as attempt 2");
        assertEquals(List.of("attempt 1 OK", "attempt 2 OK"), w.finalRows(in.id()), "an OK final row for attempt 1 and for attempt 2 under the one id");
        assertEquals(0, w.work.size(), "after the second settle the queue is empty: " + w.work.snapshot());
    }

    // --- case 2

    /** Case 2: case 1 for an item with a partition key, whose settle is the fast path ({@code completeAndTakeNext}). */
    @Test
    void aNodeDeadBetweenItsFinalRowAndItsFastPathCompleteIsRunAgainAtTheLeaseEndAndThePartnerGetsTheIdTwice() throws Exception {
        FlowDefinition def = keyed();
        Envelope in = w.accept(def, Headers.empty().with("x-order", "K-7"));
        World.Node n1 = w.node("n1", def, Death.AT_THE_FAST_PATH);
        World.Node n2 = w.node("n2", def, Death.STAGED_BY_THE_TEST);

        WorkQueue.Item first = claimedBy(n1);
        assertEquals("K-7", first.partitionKey(), "fixture: an item with a partition key");
        assertEquals(new AtDeath("completeAndTakeNext@1, not sent", List.of("attempt 1 OK"), 1, 1), n1.atDeath,
                "n1 died at its fast path: the final row of attempt 1 recorded, the work row still there, the partner called once");

        WorkQueue.Item second = takenOverAtTheLeaseEnd(n2, first, "n1");
        assertEquals(first.seq(), second.seq(), "the same row");
        assertEquals(2, second.attempts(), "taken as attempt 2");
        assertEquals(1, second.doubts(), "in doubt: n1 left it unsettled");
        assertEquals(List.of(in.id(), in.id()), w.sentIds(), "the partner received the same id twice");
        Envelope rerun = w.partner.sentTo("partner").get(1);
        assertEquals(in.body().hash(), rerun.body().hash(), "the second run started from the ingress: the arrived body");
        assertEquals(Optional.of(2), rerun.get(Envelope.ATTEMPT), "as attempt 2");
        assertEquals(List.of("attempt 1 OK", "attempt 2 OK"), w.finalRows(in.id()), "an OK final row for attempt 1 and for attempt 2 under the one id");
        assertEquals(0, w.work.size(), "after the second settle the queue is empty: " + w.work.snapshot());
    }

    // --- case 3

    /**
     * Case 3: death after the partner answered and before any row of the run is recorded — n1's store holds the run's rows. The
     * second worker runs the message again: the partner received the id twice, and the store holds an OK final row for attempt 2
     * only — the history shows one finished run although the partner was called twice.
     */
    @Test
    void aNodeDeadAfterThePartnerAnsweredAndBeforeItsRowsAreRecordedIsRunAgainAndTheHistoryShowsOnlyTheSecondRun() throws Exception {
        FlowDefinition def = unkeyed();
        Envelope in = w.accept(def, Headers.empty());
        World.Node n1 = w.node("n1", def, Death.STAGED_BY_THE_TEST);
        World.Node n2 = w.node("n2", def, Death.STAGED_BY_THE_TEST);

        n1.store.hold();
        n1.worker.poll();   // its round never ends: the node dies in it
        until(() -> n1.store.held() > 0, () -> "n1's run reached its rows' write; held " + n1.store.held());
        assertEquals(List.of(in.id()), w.sentIds(), "fixture: the partner was called, and answered, before the rows' write");
        n1.die("its rows' write, held");
        assertEquals(new AtDeath("its rows' write, held", List.of(), 1, 1), n1.atDeath,
                "n1 died with no row of its run recorded, the work row still there, the partner called once");
        assertEquals(1, n1.queue.handedOut.size(), "fixture: n1 was handed the row once");
        WorkQueue.Item first = n1.queue.handedOut.get(0);

        WorkQueue.Item second = takenOverAtTheLeaseEnd(n2, first, "n1");
        assertEquals(2, second.attempts(), "taken as attempt 2");
        assertEquals(List.of(in.id(), in.id()), w.sentIds(), "the partner received the same id twice");
        assertEquals(List.of("attempt 2 OK"), w.finalRows(in.id()), "a final OK row for attempt 2 only: one finished run in the history");
        assertEquals(0, w.work.size(), "the queue is empty: " + w.work.snapshot());
    }

    // --- case 4

    /** Case 4, the control: death after the work row is completed. Past the lease nobody takes anything; one send, one final row. */
    @Test
    void aNodeDeadAfterItsCompleteIsNotRunAgainAndThePartnerGetsTheIdOnce() throws Exception {
        FlowDefinition def = unkeyed();
        Envelope in = w.accept(def, Headers.empty());
        World.Node n1 = w.node("n1", def, Death.AFTER_THE_UNKEYED_COMPLETE);
        World.Node n2 = w.node("n2", def, Death.STAGED_BY_THE_TEST);

        claimedBy(n1);
        assertEquals(new AtDeath("completeUnkeyed@1, completed true, the answer lost", List.of("attempt 1 OK"), 0, 1),
                n1.atDeath, "n1 died once the queue completed the row: the final row recorded, the work row gone, the partner called once");

        w.rt.clock().advance(LEASE.plusMillis(1));
        assertEquals(0, n2.worker.poll().await(5, SECONDS), "past the lease the second worker takes nothing");
        assertEquals(List.of(), n2.queue.handedOut, "nothing handed out to it");
        assertEquals(List.of(in.id()), w.sentIds(), "the partner received the id once");
        assertEquals(List.of("attempt 1 OK"), w.finalRows(in.id()), "one final row");
        assertEquals(0, w.work.size(), "the queue is empty");
    }

    // --- case 5

    /**
     * Case 5: what a component can tell the second run by. Of the two envelopes the partner's component received in case 1 — id,
     * flow, step, headers, keys, body, attachments, props — only the props differ, by {@link Envelope#ATTEMPT} alone: absent on the
     * first, 2 on the second.
     */
    @Test
    void theRerunsEnvelopeDiffersFromTheFirstRunsOnlyByTheAttemptProp() throws Exception {
        FlowDefinition def = unkeyed();
        Envelope in = w.accept(def, Headers.empty());
        World.Node n1 = w.node("n1", def, Death.AT_THE_UNKEYED_COMPLETE);
        World.Node n2 = w.node("n2", def, Death.STAGED_BY_THE_TEST);
        takenOverAtTheLeaseEnd(n2, claimedBy(n1), "n1");

        List<Envelope> received = w.partner.sentTo("partner");
        assertEquals(2, received.size(), "fixture: two deliveries");
        Envelope first = received.get(0);
        Envelope second = received.get(1);
        assertEquals(in.id(), first.id());
        assertEquals(first.id(), second.id(), "the same id");
        assertEquals(first.flow(), second.flow(), "the same flow");
        assertEquals(first.step(), second.step(), "the same step");
        assertEquals(first.headers(), second.headers(), "the same headers");
        assertEquals(first.keys(), second.keys(), "the same keys");
        assertEquals(first.body().hash(), second.body().hash(), "the same body hash");
        assertEquals(first.body().size(), second.body().size(), "the same body size");
        assertEquals(first.body().key(), second.body().key(), "the same body: the ingress's, read where it arrived");
        assertEquals(first.attachments(), second.attachments(), "the same attachments");
        assertEquals(Optional.empty(), first.get(Envelope.ATTEMPT), "the first run carries no attempt");
        assertEquals(Optional.of(2), second.get(Envelope.ATTEMPT), "the second carries attempt 2");
        assertEquals(first.props().with(Envelope.ATTEMPT, 2), second.props(), "and its props are the first's with the attempt, nothing else");
        assertEquals(first.withOutboundId().headers(), second.withOutboundId().headers(), "what a producer sends as headers is the same: x-message-id included");
    }
}
