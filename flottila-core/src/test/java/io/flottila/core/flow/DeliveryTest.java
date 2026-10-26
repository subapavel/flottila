package io.flottila.core.flow;

import io.flottila.api.Ack;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Fault;
import io.flottila.api.Headers;
import io.flottila.api.NackException;
import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.api.StepFactory;
import io.flottila.api.StepResources;
import io.flottila.core.admission.Admission;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.dlq.Parked;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.core.record.Snapshot;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.RecordingComponent;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P1-08 delivery modes at the {@link Ack} level (ARCHITECTURE §4 lifecycle table, admission line 112, D-049): the
 * {@link Delivery} handler and the {@link FlowWorker} over a {@link RecordingComponent} partner, the in-memory store,
 * queue and DLQ and the fake clock. Sync replies or fails with the step's exception; at-least-once accepts only once the
 * ingress snapshot and the queue item are durable and the worker runs the pipeline later; at-most-once accepts before
 * anything runs and drops a failure; a delivery over the flow's in-flight limits is a retryable Nack. P1-08c (D-056):
 * {@link Delivery#stop} refuses new deliveries, lets those in flight finish until the deadline on the fake timer, then
 * cancels — a sync run is interrupted and answers a retryable Nack unless it still replies, an at-least-once save still
 * pending is a retryable Nack that enqueues nothing, an at-most-once run is interrupted and its failure logged.
 */
@Timeout(10)
class DeliveryTest {

    static final Path NO_FILES = Path.of("");
    static final Buffer ORDER = Buffer.buffer("<order id=\"1\"/>");
    static final Map<String, Object> TO_PARTNER = Map.of("type", "to", "name", "partner", "endpoint", "partner");
    /** The built-in {@code validate} over the test header language: the message needs an {@code id: 4711} header. */
    static final Map<String, Object> VALIDATE = Map.of("type", "validate", "name", "validate", "language", "header", "required", "id=4711");

    final FakeRuntime rt = FakeRuntime.create();
    final RecordingComponent partner = new RecordingComponent("mock");
    final HoldingMessageStore store = new HoldingMessageStore(new InMemoryMessageStore());
    final InMemoryWorkQueue queue = new InMemoryWorkQueue(rt.clock());
    final InMemoryDlq dlq = new InMemoryDlq();
    final Map<String, EndpointConfig> endpoints = Map.of("partner", new EndpointConfig("partner", URI.create("mock://partner"), Map.of()));
    final List<CountDownLatch> holds = new CopyOnWriteArrayList<>();

    { rt.withComponent(partner); }

    @AfterEach
    void releaseHolds() {
        holds.forEach(CountDownLatch::countDown);   // no send stays parked past its test
    }

    // --- fixtures

    /** A flow file as core reads it: id {@code orders}, a 64 KiB body limit, the given steps. */
    static Map<String, Object> flow(String mode, List<Map<String, Object>> steps) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("schemaVersion", 1);
        doc.put("kind", "flow");
        doc.put("id", "orders");
        doc.put("version", "0.1.0");
        doc.put("from", Map.of("endpoint", "in"));
        doc.put("delivery", Map.of("mode", mode));
        doc.put("limits", Map.of("maxBodyBytes", 65_536));
        doc.put("steps", steps);
        return doc;
    }

    /** Without recording, queue or DLQ. */
    FlowBuilder bare() {
        return new FlowBuilder(rt.runtime(), List.of(), List.of(BuiltInStepsTest.HEADERS), endpoints);
    }

    FlowBuilder builder() {
        return bare().recording(store, Meter.system()).queue(queue).dlq(dlq);
    }

    Delivery delivery(Map<String, Object> doc) {
        return builder().delivery(FlowDefinition.from(doc), NO_FILES);
    }

    Envelope ingress(Buffer body) {
        return rt.ingress("orders", Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), body);
    }

    CountDownLatch holdAtPartner() {
        CountDownLatch release = partner.holdNext(1);
        holds.add(release);
        return release;
    }

    /** Applies a sync delivery on a virtual thread of its own (the pipeline runs on the caller's thread and may park there). */
    static CompletableFuture<Future<Ack>> applyOnVirtualThread(Delivery delivery, Envelope in) {
        CompletableFuture<Future<Ack>> applied = new CompletableFuture<>();
        Thread.ofVirtual().name("handler").start(() -> {
            try {
                applied.complete(delivery.apply(in));
            } catch (Throwable failure) {
                applied.completeExceptionally(failure);
            }
        });
        return applied;
    }

    static Ack.Nack retryableNack(Future<Ack> ack) {
        assertTrue(ack.isComplete(), "refused at once, before anything runs");
        Ack.Nack nack = assertInstanceOf(Ack.Nack.class, ack.result());
        assertTrue(nack.retryable(), "over the limit is a reason to come back later: " + nack.reason());
        return nack;
    }

    // --- sync

    @Test
    void aSyncDeliveryRepliesWithThePartnersReply() {
        partner.replyWith(e -> e.next(rt.store(e, Buffer.buffer("<ack/>"))));
        Envelope in = ingress(ORDER);
        Ack ack = delivery(flow("sync", List.of(TO_PARTNER))).apply(in).await();
        Ack.Reply reply = assertInstanceOf(Ack.Reply.class, ack);
        assertEquals("<ack/>", reply.reply().body().whole().toString(), "the partner's reply is the reply");
        assertEquals(in.id(), reply.reply().id());
        List<Envelope> got = partner.sentTo("partner");
        assertEquals(1, got.size());
        assertEquals(ORDER.toString(), got.get(0).body().whole().toString());
    }

    @Test
    void aSyncStepFailureFailsTheFutureWithTheStepsException() {
        Future<Ack> ack = delivery(flow("sync", List.of(VALIDATE, TO_PARTNER))).apply(ingress(ORDER));   // no id header
        assertTrue(ack.failed(), "a sync failure is the caller's answer, not an Ack");
        Fault fault = assertInstanceOf(Fault.class, ack.cause(), "the step's exception, classified: " + ack.cause());
        assertEquals(Fault.Kind.INVALID, fault.kind());
        assertTrue(ack.cause().getMessage().contains("'validate'"), "names the step: " + ack.cause().getMessage());
        assertEquals(List.of(), partner.sent());
    }

    // --- forwardHeaders (D-049 e: the filter sits at the producer boundary, the steps see every header)

    @Test
    void theProducerGetsOnlyTheAllowListedHeadersAndTheContentTypeWhateverSetThem() {
        List<Map<String, String>> seenByStep = new CopyOnWriteArrayList<>();
        StepFactory spy = new StepFactory() {
            @Override public String type() { return "spy"; }
            @Override public String configSchema() { return "{}"; }
            @Override public Stage create(StepConfig step, StepResources res) {
                return Stage.of(e -> {
                    seenByStep.add(e.headers().asMap());
                    return e.withHeader("x-step", "s");
                });
            }
        };
        Map<String, Object> doc = flow("sync", List.of(Map.of("type", "spy", "name", "spy"), TO_PARTNER));
        doc.put("forwardHeaders", List.of("X-Request-Id"));
        Delivery delivery = new FlowBuilder(rt.runtime(), List.of(spy), List.of(BuiltInStepsTest.HEADERS), endpoints)
                .delivery(FlowDefinition.from(doc), NO_FILES);
        partner.replyWith(e -> e.next(rt.store(e, Buffer.buffer("<ack/>"))));
        Envelope in = rt.ingress("orders", Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml")
                .with("x-request-id", "r-42").with("x-secret", "s"), ORDER);

        Ack.Reply reply = assertInstanceOf(Ack.Reply.class, delivery.apply(in).await());

        assertEquals("<ack/>", reply.reply().body().whole().toString(), "the partner's reply is still the reply");
        assertEquals(1, seenByStep.size());
        assertEquals("s", seenByStep.get(0).get("x-secret"), "a step sees every ingress header: " + seenByStep.get(0));
        assertEquals("r-42", seenByStep.get(0).get("x-request-id"), "a step sees every ingress header: " + seenByStep.get(0));
        List<Envelope> got = partner.sentTo("partner");
        assertEquals(1, got.size());
        Map<String, String> atPartner = got.get(0).headers().asMap();
        assertEquals("r-42", atPartner.get("x-request-id"), "listed (case-insensitive): forwarded " + atPartner);
        assertEquals("application/xml", atPartner.get(Envelope.H_CONTENT_TYPE), "the body's media type travels with it: " + atPartner);
        assertFalse(atPartner.containsKey("x-secret"), "an ingress header not listed never reaches the producer: " + atPartner);
        assertFalse(atPartner.containsKey("x-step"), "a header a step set is dropped too unless listed: " + atPartner);
        assertEquals(Set.of("x-request-id", Envelope.H_CONTENT_TYPE), atPartner.keySet(), "exactly the list plus content-type");
    }

    // --- admission

    @Test
    void aDeliveryOverItsInFlightRequestLimitIsARetryableNack() throws Exception {
        Map<String, Object> doc = flow("sync", List.of(TO_PARTNER));
        doc.put("limits", Map.of("maxBodyBytes", 65_536, "maxInFlight", 1));
        Delivery delivery = delivery(doc);
        CountDownLatch release = holdAtPartner();
        CompletableFuture<Future<Ack>> first = applyOnVirtualThread(delivery, ingress(ORDER));
        partner.awaitSent(1);
        retryableNack(delivery.apply(ingress(ORDER)));
        assertEquals(1, partner.sent().size(), "a refused message runs nothing");
        release.countDown();
        assertInstanceOf(Ack.Reply.class, first.get(10, TimeUnit.SECONDS).await());
        assertInstanceOf(Ack.Reply.class, delivery.apply(ingress(ORDER)).await(), "the finished message gave its permit back");
        assertEquals(2, partner.sent().size());
    }

    @Test
    void aDeliveryOverItsInFlightByteLimitIsARetryableNack() throws Exception {
        Map<String, Object> doc = flow("sync", List.of(TO_PARTNER));
        doc.put("limits", Map.of("maxBodyBytes", 1000, "maxInFlight", 8, "maxInFlightBytes", 1000));
        Delivery delivery = delivery(doc);
        Buffer body = Buffer.buffer("x".repeat(600));
        CountDownLatch release = holdAtPartner();
        CompletableFuture<Future<Ack>> first = applyOnVirtualThread(delivery, ingress(body));
        partner.awaitSent(1);
        retryableNack(delivery.apply(ingress(body)));   // 600 + 600 bytes exceed 1000 in flight
        release.countDown();
        assertInstanceOf(Ack.Reply.class, first.get(10, TimeUnit.SECONDS).await());
        assertEquals(1, partner.sent().size());
    }

    // --- at-least-once

    @Test
    void anAtLeastOnceAckWaitsForTheDurableSave() {
        Map<String, Object> doc = flow("at-least-once", List.of(TO_PARTNER));
        doc.put("recording", Map.of("level", "off"));
        Delivery delivery = delivery(doc);
        Envelope in = ingress(ORDER);
        store.hold();
        Future<Ack> ack = delivery.apply(in);
        assertFalse(ack.isComplete(), "no ACK before the save is durable");
        assertEquals(1, store.held(), "the ingress snapshot is being saved");
        assertEquals(0, queue.size(), "the record first, then the queue item");
        store.release();
        assertEquals(new Ack.Accepted(in.id()), ack.await());
        List<WorkQueue.Item> items = queue.snapshot();
        assertEquals(1, items.size());
        assertEquals(in.id(), items.get(0).messageId());
        assertEquals("orders", items.get(0).flow());
        List<Snapshot> timeline = store.timeline(in.id()).await();
        assertEquals(List.of("ingress"), timeline.stream().map(Snapshot::stepName).toList());
        assertEquals(Optional.of(in.body().key()), timeline.get(0).bodyKey(), "the payload is kept although the flow records nothing");
        assertEquals(List.of(), partner.sent(), "nothing of the pipeline runs on the handler's thread");
    }

    @Test
    void anAtLeastOnceSaveTimeoutIsARetryableNack() {
        Map<String, Object> doc = flow("at-least-once", List.of(TO_PARTNER));
        doc.put("delivery", Map.of("mode", "at-least-once", "saveTimeout", "2s"));
        FlowDefinition def = FlowDefinition.from(doc);
        FlowBuilder b = builder();
        Delivery delivery = b.delivery(def, NO_FILES);
        FlowWorker worker = b.worker(def, NO_FILES, "w1");
        store.hold();
        Future<Ack> ack = delivery.apply(ingress(ORDER));
        assertEquals(Duration.ofSeconds(2), rt.timer().nextDelay(), "the save timeout runs on the runtime's timer");
        assertFalse(ack.isComplete());
        rt.clock().advance(Duration.ofSeconds(2));
        Ack.Nack nack = assertInstanceOf(Ack.Nack.class, ack.await());
        assertTrue(nack.retryable());
        assertTrue(nack.reason().contains("PT2S") || nack.reason().contains("2s"), nack.reason());
        store.release();
        assertEquals(0, queue.size(), "a save that completes after the timeout enqueues nothing");
        assertEquals(0, worker.poll().await());
        assertEquals(List.of(), partner.sent());
    }

    @Test
    void aQueuedItemWithoutItsIngressSnapshotIsCompletedNotRetried() {
        FlowWorker worker = builder().worker(FlowDefinition.from(flow("at-least-once", List.of(TO_PARTNER))), NO_FILES, "w1");
        queue.enqueue("ghost", "orders", 1, null).await();   // no snapshot in the store, no blob: the worker cannot rebuild it
        assertEquals(1, queue.size());
        assertEquals(1, worker.poll().await(), "the item is claimed and counted as processed");
        assertEquals(0, queue.size(), "completed, never a poison loop: no lease expiry hands it back");
        assertEquals(List.of(), dlq.list().await(), "nothing to park: there is no message to retry from");
        assertEquals(List.of(), partner.sent(), "nothing reached the partner");
    }

    @Test
    void anAtLeastOnceSaveFailureIsARetryableNack() {
        Delivery delivery = delivery(flow("at-least-once", List.of(TO_PARTNER)));
        store.hold();
        Future<Ack> ack = delivery.apply(ingress(ORDER));
        store.fail(new IllegalStateException("disk full"));
        Ack.Nack nack = assertInstanceOf(Ack.Nack.class, ack.await());
        assertTrue(nack.retryable());
        assertTrue(nack.reason().contains("disk full"), nack.reason());
        assertEquals(0, queue.size());
    }

    @Test
    void anAtLeastOnceFailureIsParkedAndTheSenderKeepsItsAck() {
        FlowDefinition def = FlowDefinition.from(flow("at-least-once", List.of(TO_PARTNER)));
        FlowBuilder b = builder();
        Delivery delivery = b.delivery(def, NO_FILES);
        FlowWorker worker = b.worker(def, NO_FILES, "w1");
        partner.failNext(1, () -> new NackException("order refused", false));
        Envelope in = ingress(ORDER);
        assertEquals(new Ack.Accepted(in.id()), delivery.apply(in).await());
        assertEquals(1, worker.poll().await(), "a parked message counts as processed");
        assertEquals(1, partner.sent().size());
        List<Parked> parked = dlq.list().await();
        assertEquals(1, parked.size());
        assertEquals(in.id(), parked.get(0).messageId());
        assertEquals("partner", parked.get(0).stepName());
        assertEquals(0, queue.size());
    }

    @Test
    void aWorkerBuiltAfterTheAckStillProcessesTheMessage() {
        FlowDefinition def = FlowDefinition.from(flow("at-least-once", List.of(TO_PARTNER)));
        Envelope in = ingress(ORDER);
        assertEquals(new Ack.Accepted(in.id()), builder().delivery(def, NO_FILES).apply(in).await());
        FlowWorker restarted = builder().worker(def, NO_FILES, "w2");   // a new builder over the same store, queue and blobs
        assertEquals(1, restarted.poll().await());
        List<Envelope> got = partner.sentTo("partner");
        assertEquals(1, got.size());
        assertEquals(in.id(), got.get(0).id());
        assertEquals(ORDER.toString(), got.get(0).body().whole().toString(), "the body rebuilt from the store by its key");
        assertEquals("application/xml", got.get(0).headers().get(Envelope.H_CONTENT_TYPE), "the ingress headers rebuilt from the store");
        assertEquals(0, queue.size());
        assertEquals(List.of("ingress", "partner"), store.timeline(in.id()).await().stream().map(Snapshot::stepName).toList());
    }

    @Test
    void anAtLeastOnceFlowNeedsAStoreAQueueAndADlq() {
        FlowDefinition def = FlowDefinition.from(flow("at-least-once", List.of(TO_PARTNER)));
        String noStore = assertThrows(IllegalStateException.class, () -> bare().queue(queue).dlq(dlq).delivery(def, NO_FILES)).getMessage();
        String noQueue = assertThrows(IllegalStateException.class, () -> bare().recording(store, Meter.system()).dlq(dlq).delivery(def, NO_FILES)).getMessage();
        String noDlq = assertThrows(IllegalStateException.class, () -> bare().recording(store, Meter.system()).queue(queue).delivery(def, NO_FILES)).getMessage();
        assertTrue(noStore.toLowerCase(Locale.ROOT).contains("recording") || noStore.toLowerCase(Locale.ROOT).contains("store"), noStore);
        assertTrue(noQueue.toLowerCase(Locale.ROOT).contains("queue"), noQueue);
        assertTrue(noDlq.toLowerCase(Locale.ROOT).contains("dlq"), noDlq);
        assertEquals(3, Set.copyOf(List.of(noStore, noQueue, noDlq)).size(), "each names what is missing, not one message for all");
    }

    // --- at-most-once

    @Test
    void anAtMostOnceDeliveryIsAcceptedBeforeThePipelineRuns() {
        Delivery delivery = delivery(flow("at-most-once", List.of(TO_PARTNER)));
        CountDownLatch release = holdAtPartner();
        Envelope in = ingress(ORDER);
        Future<Ack> ack = delivery.apply(in);
        assertTrue(ack.isComplete(), "accepted when apply returns, whatever the partner does");
        assertEquals(new Ack.Accepted(in.id()), ack.result());
        release.countDown();
        partner.awaitSent(1);
        assertEquals(in.id(), partner.sent().get(0).id());
        assertEquals(0, queue.size(), "nothing queued");
    }

    @Test
    void anAtMostOnceFailureIsDroppedNotParked() {
        Delivery delivery = delivery(flow("at-most-once", List.of(TO_PARTNER)));
        partner.failNext(1, () -> new IllegalStateException("partner down"));
        Envelope in = ingress(ORDER);
        assertEquals(new Ack.Accepted(in.id()), delivery.apply(in).await());
        partner.awaitSent(1);
        assertEquals(List.of(), dlq.list().await(), "nothing parked");
        assertEquals(0, queue.size(), "nothing queued");
    }

    // --- review round 1 (D-049 c: the worker completes only on success, a parked failure or a gone snapshot; admission per mode)

    /** At-least-once flows below record every step: the worker's own snapshots are part of what is asserted. */
    static Map<String, Object> recordingAll(Map<String, Object> doc) {
        doc.put("recording", Map.of("level", "all"));
        return doc;
    }

    static Map<String, Object> oneInFlight(Map<String, Object> doc) {
        doc.put("limits", Map.of("maxBodyBytes", 65_536, "maxInFlight", 1));
        return doc;
    }

    @Test
    void aFailureTheDlqCannotParkLeavesTheItemToItsLease() {
        FailingDlq failing = new FailingDlq(dlq);
        FlowDefinition def = FlowDefinition.from(recordingAll(flow("at-least-once", List.of(TO_PARTNER))));
        FlowBuilder b = bare().recording(store, Meter.system()).queue(queue).dlq(failing);
        Delivery delivery = b.delivery(def, NO_FILES);
        FlowWorker worker = b.worker(def, NO_FILES, "w1");
        partner.failNext(2, () -> new NackException("order refused", false));
        failing.failParks(new IllegalStateException("dlq down"));
        Envelope in = ingress(ORDER);
        assertEquals(new Ack.Accepted(in.id()), delivery.apply(in).await());

        worker.poll().otherwiseEmpty().await();   // what poll answers for a left item is the implementer's to document
        assertEquals(1, partner.sent().size());
        List<WorkQueue.Item> left = queue.snapshot();
        assertEquals(1, left.size(), "the sender had its 202: a message the DLQ could not take stays queued");
        assertEquals(in.id(), left.get(0).messageId());
        assertEquals("orders", left.get(0).flow());
        assertEquals(List.of(), failing.list().await(), "nothing parked");
        worker.poll().otherwiseEmpty().await();
        assertEquals(1, partner.sent().size(), "leased: not run again before the lease runs out");

        failing.parksOk();
        rt.clock().advance(WorkerPolicy.DEFAULT.lease());
        worker.poll().await();
        List<Parked> parked = failing.list().await();
        assertEquals(1, parked.size(), "after the lease the item runs again and its failure is parked");
        assertEquals(in.id(), parked.get(0).messageId());
        assertEquals(0, queue.size(), "parked: the item is done");
        assertEquals(2, partner.sentTo("partner").size());
    }

    @Test
    void aStoreReadFailureLeavesTheItemToItsLease() {
        FlowDefinition def = FlowDefinition.from(recordingAll(flow("at-least-once", List.of(TO_PARTNER))));
        FlowBuilder b = builder();
        Delivery delivery = b.delivery(def, NO_FILES);
        FlowWorker worker = b.worker(def, NO_FILES, "w1");
        Envelope in = ingress(ORDER);
        assertEquals(new Ack.Accepted(in.id()), delivery.apply(in).await());

        store.failReads(new IllegalStateException("pg away"));
        worker.poll().otherwiseEmpty().await();
        assertEquals(1, queue.size(), "a store that cannot be read is not a snapshot that is gone: the item stays");
        assertEquals(List.of(), partner.sent());
        assertEquals(List.of(), dlq.list().await());

        store.readsOk();
        rt.clock().advance(WorkerPolicy.DEFAULT.lease());
        worker.poll().await();
        List<Envelope> got = partner.sentTo("partner");
        assertEquals(1, got.size(), "after the lease the message runs");
        assertEquals(in.id(), got.get(0).id());
        assertEquals(0, queue.size());
        assertEquals(List.of("ingress", "partner"), store.timeline(in.id()).await().stream().map(Snapshot::stepName).toList());
    }

    @Test
    void anAtLeastOnceDeliveryReleasesItsPermitWithTheAck() {
        Delivery delivery = delivery(oneInFlight(recordingAll(flow("at-least-once", List.of(TO_PARTNER)))));
        store.hold();
        Envelope first = ingress(ORDER);
        Future<Ack> firstAck = delivery.apply(first);
        assertFalse(firstAck.isComplete());
        retryableNack(delivery.apply(ingress(ORDER)));
        assertEquals(1, store.held(), "the refused message saved nothing");
        store.release();
        assertEquals(new Ack.Accepted(first.id()), firstAck.await());
        Envelope third = ingress(ORDER);
        assertEquals(new Ack.Accepted(third.id()), delivery.apply(third).await(), "the permit came back with the Ack");
        assertEquals(2, queue.size());
    }

    @Test
    void anAtLeastOnceSaveTimeoutReleasesThePermit() {
        Map<String, Object> doc = oneInFlight(recordingAll(flow("at-least-once", List.of(TO_PARTNER))));
        doc.put("delivery", Map.of("mode", "at-least-once", "saveTimeout", "2s"));
        Delivery delivery = delivery(doc);
        store.hold();
        Future<Ack> firstAck = delivery.apply(ingress(ORDER));
        assertFalse(firstAck.isComplete());
        rt.clock().advance(Duration.ofSeconds(2));
        assertTrue(assertInstanceOf(Ack.Nack.class, firstAck.await()).retryable());

        Envelope second = ingress(ORDER);
        Future<Ack> secondAck = delivery.apply(second);
        assertFalse(secondAck.isComplete(), "admitted — its save is held like the first — not refused: the timeout gave the permit back");
        store.release();
        assertEquals(new Ack.Accepted(second.id()), secondAck.await());
        List<WorkQueue.Item> items = queue.snapshot();
        assertEquals(1, items.size(), "the timed-out first enqueued nothing");
        assertEquals(second.id(), items.get(0).messageId());
    }

    @Test
    void anAtMostOnceFailureIsLoggedWithTheMessageIdAndReleasesThePermit() throws InterruptedException {
        Delivery delivery = delivery(oneInFlight(flow("at-most-once", List.of(TO_PARTNER))));
        CountDownLatch release = holdAtPartner();
        partner.failNext(1, () -> new IllegalStateException("partner down"));
        Envelope first = ingress(ORDER);
        Envelope third = ingress(ORDER);
        List<java.util.logging.LogRecord> warnings = new CopyOnWriteArrayList<>();
        AtomicReference<Future<Ack>> admittedAtTheLogLine = new AtomicReference<>();
        CountDownLatch logged = new CountDownLatch(1);
        java.util.logging.Logger jul = java.util.logging.Logger.getLogger(Delivery.class.getName());   // System.Logger delegates to it
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord r) {
                if (r.getLevel().intValue() < java.util.logging.Level.WARNING.intValue()) return;
                warnings.add(r);
                String text = new java.util.logging.SimpleFormatter().formatMessage(r);
                if (text != null && text.contains(first.id()) && admittedAtTheLogLine.get() == null) {
                    // on the pipeline's own thread, while the line is written: the permit must already be back
                    admittedAtTheLogLine.set(delivery.apply(third));
                    logged.countDown();
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        handler.setLevel(java.util.logging.Level.ALL);
        jul.addHandler(handler);
        try {
            Future<Ack> firstAck = delivery.apply(first);
            assertTrue(firstAck.isComplete());
            assertEquals(new Ack.Accepted(first.id()), firstAck.result());
            retryableNack(delivery.apply(ingress(ORDER)));   // the permit is held while the pipeline runs
            release.countDown();
            assertTrue(logged.await(10, TimeUnit.SECONDS), "a WARNING naming the message id " + first.id() + ": " + warnings.stream().map(java.util.logging.LogRecord::getMessage).toList());
            Future<Ack> thirdAck = admittedAtTheLogLine.get();
            assertTrue(thirdAck.isComplete());
            assertEquals(new Ack.Accepted(third.id()), thirdAck.result(), "the permit was released before the log line");
            assertEquals(List.of(), dlq.list().await(), "logged and dropped, not parked");
        } finally {
            jul.removeHandler(handler);
        }
    }

    @Test
    void aSynchronousSaveFailureIsARetryableNackAndReleasesThePermit() {
        Delivery delivery = delivery(oneInFlight(recordingAll(flow("at-least-once", List.of(TO_PARTNER)))));
        store.throwNext(new IllegalStateException("store closed"));
        Future<Ack> ack = assertDoesNotThrow(() -> delivery.apply(ingress(ORDER)), "a store that throws is a failed save, not an escaping exception");
        assertTrue(ack.isComplete(), "answered at once");
        Ack.Nack nack = assertInstanceOf(Ack.Nack.class, ack.result());
        assertTrue(nack.retryable());
        assertTrue(nack.reason().contains("store closed"), nack.reason());
        assertEquals(0, queue.size());
        Envelope second = ingress(ORDER);
        assertEquals(new Ack.Accepted(second.id()), delivery.apply(second).await(), "the permit was released");
    }

    @Test
    void theWorkerRecordsNoSecondIngressSnapshot() {
        FlowDefinition def = FlowDefinition.from(recordingAll(flow("at-least-once", List.of(TO_PARTNER))));
        FlowBuilder b = builder();
        Envelope in = ingress(ORDER);
        assertEquals(new Ack.Accepted(in.id()), b.delivery(def, NO_FILES).apply(in).await());
        assertEquals(1, b.worker(def, NO_FILES, "w1").poll().await());
        assertEquals(1, store.recorded("ingress"), "the save recorded it; the worker runs without stage 0");
        assertEquals(1, store.recorded("partner"));
    }

    @Test
    void theByPropProducerGetsOnlyTheAllowListedHeaders() {
        Map<String, Object> route = Map.of("type", "route", "name", "route", "language", "header", "prop", "PARTNER",
                "routes", List.of(Map.of("when", "x-route=a", "set", "a")), "otherwise", "b");
        Map<String, Object> to = Map.of("type", "to", "name", "send", "byProp", "PARTNER", "endpoints", Map.of("a", "partner", "b", "other"));
        Map<String, Object> doc = flow("sync", List.of(route, to));
        doc.put("forwardHeaders", List.of("X-Request-Id"));
        Map<String, EndpointConfig> two = Map.of(
                "partner", new EndpointConfig("partner", URI.create("mock://partner"), Map.of()),
                "other", new EndpointConfig("other", URI.create("mock://other"), Map.of()));
        Delivery delivery = new FlowBuilder(rt.runtime(), List.of(), List.of(BuiltInStepsTest.HEADERS), two).delivery(FlowDefinition.from(doc), NO_FILES);
        Envelope in = rt.ingress("orders", Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml")
                .with("x-request-id", "r-42").with("x-secret", "s").with("x-route", "a"), ORDER);

        assertInstanceOf(Ack.Reply.class, delivery.apply(in).await());

        List<Envelope> got = partner.sentTo("partner");
        assertEquals(1, got.size(), "the route chose a");
        assertEquals(Set.of("x-request-id", Envelope.H_CONTENT_TYPE), got.get(0).headers().asMap().keySet(),
                "the byProp form filters like the endpoint form: " + got.get(0).headers().asMap());
        assertEquals(List.of(), partner.sentTo("other"));
    }

    @Test
    void anOverflowingDefaultByteBudgetIsABuildError() {
        Map<String, Object> doc = flow("sync", List.of(TO_PARTNER));
        doc.put("limits", Map.of("maxBodyBytes", Long.MAX_VALUE / 2, "maxInFlight", 4));
        FlowDefinition overflowing = FlowDefinition.from(doc);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> builder().delivery(overflowing, NO_FILES),
                "maxInFlight × maxBodyBytes does not fit a long");
        assertTrue(e.getMessage().contains("maxBodyBytes") && e.getMessage().contains("maxInFlight"), e.getMessage());
        doc.put("limits", Map.of("maxBodyBytes", Long.MAX_VALUE / 2, "maxInFlight", 1));
        assertNotNull(builder().delivery(FlowDefinition.from(doc), NO_FILES), "one request of that size fits");
    }

    @Test
    void aWorkerNeedsAStoreAndAQueue() {
        FlowDefinition def = FlowDefinition.from(flow("at-least-once", List.of(TO_PARTNER)));
        String noStore = assertThrows(IllegalStateException.class, () -> bare().queue(queue).dlq(dlq).worker(def, NO_FILES, "w")).getMessage();
        String noQueue = assertThrows(IllegalStateException.class, () -> bare().recording(store, Meter.system()).dlq(dlq).worker(def, NO_FILES, "w")).getMessage();
        assertTrue(noStore.toLowerCase(Locale.ROOT).contains("recording") || noStore.toLowerCase(Locale.ROOT).contains("store"), noStore);
        assertTrue(noQueue.toLowerCase(Locale.ROOT).contains("queue"), noQueue);
    }

    // --- stop and cancel (P1-08c, D-056). Signatures assumed: Delivery.stop(Duration) -> Future<Void>;
    //     Delivery.sync(Admission, Pipeline, Timer) and Delivery.atMostOnce(Admission, Pipeline, Timer): the deadline's timer.

    static final Duration DEADLINE = Duration.ofSeconds(5);
    static final Map<String, Object> PARK = Map.of("type", "park", "name", "park");

    /** Room for every case here: admission never refuses unless a case says so. */
    static Admission roomy() { return new Admission(8, 1 << 20); }

    /** A failure that says what kind it is and nothing else. */
    static final class KindedFault extends RuntimeException implements Fault {
        private final Kind kind;
        KindedFault(Kind kind, String message) { super(message); this.kind = kind; }
        @Override public Kind kind() { return kind; }
    }

    /** What {@code apply} answered and whether the calling thread's interrupt flag was set the moment it returned. */
    record Applied(Future<Ack> ack, boolean interruptedAfter) {}

    /** Applies {@code in} on a virtual thread of its own, as a consumer does, noting the interrupt flag right after apply. */
    static CompletableFuture<Applied> applyOn(Delivery delivery, Envelope in) {
        CompletableFuture<Applied> applied = new CompletableFuture<>();
        Thread.ofVirtual().name("consumer-" + in.id()).start(() -> {
            try {
                Future<Ack> ack = delivery.apply(in);
                applied.complete(new Applied(ack, Thread.currentThread().isInterrupted()));
            } catch (Throwable escaped) {
                applied.completeExceptionally(escaped);
            }
        });
        return applied;
    }

    /** The outcome of {@link #applyOn}; an exception escaping {@code apply} or no return within the bound fails the case. */
    static Applied applied(CompletableFuture<Applied> f) {
        try {
            return f.get(HeldPipeline.BOUND_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException escaped) {
            return fail("apply threw instead of answering: " + escaped.getCause(), escaped.getCause());
        } catch (TimeoutException | InterruptedException stuck) {
            return fail("apply did not return within " + HeldPipeline.BOUND_SECONDS + " s");
        }
    }

    /** {@code f} once complete, either way; still pending after the bound fails the case. */
    static <T> Future<T> settled(Future<T> f) {
        try {
            f.otherwiseEmpty().await(HeldPipeline.BOUND_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException pending) {
            fail("still pending after " + HeldPipeline.BOUND_SECONDS + " s: " + f);
        }
        return f;
    }

    static Ack.Nack cancelled(Future<Ack> ack) {
        assertTrue(ack.isComplete(), "answered: " + ack);
        Ack.Nack nack = assertInstanceOf(Ack.Nack.class, ack.result(), "a cancelled delivery answers a Nack: " + ack);
        assertTrue(nack.retryable(), "the sender may try again once the flow runs again: " + nack.reason());
        assertTrue(nack.reason().contains("cancelled"), "says it was cancelled: " + nack.reason());
        return nack;
    }

    Delivery heldFlow(String mode, HeldPipeline held) {
        return new FlowBuilder(rt.runtime(), List.of(held.step("park")), List.of(BuiltInStepsTest.HEADERS), endpoints)
                .delivery(FlowDefinition.from(flow(mode, List.of(PARK))), NO_FILES);
    }

    /** An at-least-once flow over the holding store whose own save timeout (30 s) stays out of the stop's way. */
    FlowDefinition atLeastOnceSaving30s() {
        Map<String, Object> doc = flow("at-least-once", List.of(TO_PARTNER));
        doc.put("delivery", Map.of("mode", "at-least-once", "saveTimeout", "30s"));
        return FlowDefinition.from(doc);
    }

    @Test
    void stopRefusesNewDeliveriesWithARetryableNackBeforeAdmission() {
        Admission onePermit = new Admission(1, 1 << 20);
        HeldPipeline held = HeldPipeline.awaiting();
        Delivery sync = Delivery.sync(onePermit, held, rt.timer());
        Envelope first = ingress(ORDER);
        CompletableFuture<Applied> firstApplied = applyOn(sync, first);
        HeldPipeline.Run run = held.run(first.id());
        Future<Void> stopped = sync.stop(DEADLINE);

        Ack.Nack refused = retryableNack(applied(applyOn(sync, ingress(ORDER))).ack());
        assertTrue(refused.reason().contains("stopping") && refused.reason().contains("orders"), "names the flow and why: " + refused.reason());
        assertFalse(refused.reason().startsWith("admission refused"), "refused before admission: the only permit is taken, yet the answer is the stop's: " + refused.reason());
        assertEquals(1, held.entered(), "the refused message ran nothing");
        assertEquals(1, onePermit.inFlightRequests(), "only the delivery in flight holds a permit");
        run.reply("in-time");
        assertInstanceOf(Ack.Reply.class, applied(firstApplied).ack().result(), "the delivery in flight finishes normally before the deadline");
        assertTrue(settled(stopped).succeeded());
        assertEquals(0, onePermit.inFlightRequests());

        Admission amoRoom = roomy();
        HeldPipeline amoHeld = HeldPipeline.awaiting();
        Delivery amo = Delivery.atMostOnce(amoRoom, amoHeld, rt.timer());
        assertTrue(amo.stop(DEADLINE).isComplete());
        Ack.Nack amoRefused = retryableNack(amo.apply(ingress(ORDER)));
        assertTrue(amoRefused.reason().contains("stopping"), "at-most-once is refused too, not accepted: " + amoRefused.reason());
        assertEquals(0, amoRoom.inFlightRequests(), "no permit taken");

        Delivery alo = builder().delivery(atLeastOnceSaving30s(), NO_FILES);
        assertTrue(alo.stop(DEADLINE).isComplete());
        Ack.Nack aloRefused = retryableNack(alo.apply(ingress(ORDER)));
        assertTrue(aloRefused.reason().contains("stopping") && aloRefused.reason().contains("orders"), aloRefused.reason());
        assertEquals(0, store.recorded("ingress"), "a refused at-least-once message saves nothing");
        assertEquals(0, queue.size(), "and enqueues nothing");
        assertEquals(0, amoHeld.entered(), "nothing ran");
    }

    @Test
    void aSyncDeliveryIsNotInterruptedOneMillisecondBeforeTheDeadline() {
        HeldPipeline held = HeldPipeline.awaiting();
        Delivery delivery = Delivery.sync(roomy(), held, rt.timer());
        Envelope in = ingress(ORDER);
        CompletableFuture<Applied> applied = applyOn(delivery, in);
        HeldPipeline.Run run = held.run(in.id());
        Future<Void> stopped = delivery.stop(DEADLINE);
        assertEquals(DEADLINE, rt.timer().nextDelay(), "the deadline runs on the injected timer");

        rt.clock().advance(DEADLINE.minusMillis(1));
        assertEquals(Duration.ofMillis(1), rt.timer().nextDelay(), "one millisecond to go: the deadline has not fired");
        assertFalse(stopped.isComplete(), "the delivery is still in flight");
        run.reply("in-time");
        Ack.Reply reply = assertInstanceOf(Ack.Reply.class, applied(applied).ack().result(), "answered before the deadline: its own answer");
        assertEquals("in-time", reply.reply().header(HeldPipeline.H_ANSWER).orElse(null));
        assertFalse(run.interrupted, "the message's thread was never interrupted before the deadline");
        assertTrue(settled(stopped).succeeded(), "the last delivery answered: stopped");
    }

    @Test
    void aSyncDeliveryAwaitingAtTheDeadlineIsInterruptedAndAnswersARetryableNack() {
        Admission admission = roomy();
        HeldPipeline held = HeldPipeline.awaiting();
        Delivery delivery = Delivery.sync(admission, held, rt.timer());
        Envelope in = ingress(ORDER);
        CompletableFuture<Applied> applied = applyOn(delivery, in);
        HeldPipeline.Run run = held.run(in.id());
        Future<Void> stopped = delivery.stop(DEADLINE);

        rt.clock().advance(DEADLINE);
        Ack.Nack nack = cancelled(applied(applied).ack());
        assertTrue(nack.reason().contains("PT5S") || nack.reason().contains("5s"), "names the deadline: " + nack.reason());
        assertTrue(run.interrupted, "the message's thread was interrupted at the deadline: its await woke");
        assertEquals(0, admission.inFlightRequests(), "the permit is back");
        assertEquals(0, admission.inFlightBytes());
        assertTrue(settled(stopped).succeeded(), "the last delivery answered: stopped");
        assertEquals(0, rt.timer().pending(), "nothing left on the timer");
    }

    static Stream<Arguments> failuresAfterTheCancel() {
        return Stream.of(
                Arguments.of("MALFORMED", (Supplier<Throwable>) () -> new KindedFault(Fault.Kind.MALFORMED, "the body is not xml")),
                Arguments.of("INVALID", (Supplier<Throwable>) () -> new KindedFault(Fault.Kind.INVALID, "validation failed at step 'park'")),
                Arguments.of("DOWNSTREAM final", (Supplier<Throwable>) () -> new NackException("partner refused the order", false)),
                Arguments.of("INTERNAL", (Supplier<Throwable>) () -> new KindedFault(Fault.Kind.INTERNAL, "stylesheet raised an error")),
                Arguments.of("no fault", (Supplier<Throwable>) () -> new IllegalStateException("gave up")),
                Arguments.of("checked", (Supplier<Throwable>) () -> new IOException("connection reset while stopping")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failuresAfterTheCancel")
    void aSyncFailureAfterTheCancelIsARetryableNackWhateverItsFaultKind(String kind, Supplier<Throwable> failure) {
        HeldPipeline held = HeldPipeline.awaitingThenThrowing(failure);
        Delivery delivery = heldFlow("sync", held);   // built by the FlowBuilder: the runtime's timer drives the deadline
        Envelope in = ingress(ORDER);
        CompletableFuture<Applied> applied = applyOn(delivery, in);
        HeldPipeline.Run run = held.run(in.id());
        Future<Void> stopped = delivery.stop(DEADLINE);
        rt.clock().advance(DEADLINE);
        cancelled(applied(applied).ack());
        assertTrue(run.interrupted, kind + ": interrupted at the deadline");
        assertTrue(settled(stopped).succeeded(), kind);
    }

    @Test
    void aSyncFailureWhileStoppingButBeforeTheDeadlineKeepsTheStepsException() {
        HeldPipeline held = HeldPipeline.awaiting();
        Delivery delivery = Delivery.sync(roomy(), held, rt.timer());
        Envelope in = ingress(ORDER);
        CompletableFuture<Applied> applied = applyOn(delivery, in);
        HeldPipeline.Run run = held.run(in.id());
        Future<Void> stopped = delivery.stop(DEADLINE);
        rt.clock().advance(DEADLINE.minusMillis(1));
        KindedFault invalid = new KindedFault(Fault.Kind.INVALID, "validation failed at step 'park'");
        run.fail(invalid);
        Future<Ack> ack = applied(applied).ack();
        assertTrue(ack.failed(), "not cancelled — the deadline has not come — so the step's own failure is the answer: " + ack);
        assertSame(invalid, ack.cause());
        assertTrue(settled(stopped).succeeded());
    }

    @Test
    void aSyncPipelineThrowingACheckedExceptionFailsTheFutureNotTheCaller() {
        Admission admission = roomy();
        HeldPipeline held = HeldPipeline.awaiting();
        Delivery delivery = Delivery.sync(admission, held, rt.timer());
        Envelope in = ingress(ORDER);
        CompletableFuture<Applied> applied = applyOn(delivery, in);
        IOException reset = new IOException("partner socket reset");
        held.run(in.id()).fail(reset);   // thrown from await sneakily, as Vert.x does with a checked cause
        Future<Ack> ack = applied(applied).ack();
        assertTrue(ack.failed(), "a checked exception fails the answer like any other: " + ack);
        assertSame(reset, ack.cause());
        assertEquals(0, admission.inFlightRequests(), "the permit is back");
    }

    @Test
    void aSyncPipelineThatRepliesDespiteTheInterruptKeepsItsReply() {
        Admission admission = roomy();
        HeldPipeline held = HeldPipeline.stubborn(true);
        Delivery delivery = Delivery.sync(admission, held, rt.timer());
        Envelope in = ingress(ORDER);
        CompletableFuture<Applied> applied = applyOn(delivery, in);
        HeldPipeline.Run run = held.run(in.id());
        Future<Void> stopped = delivery.stop(DEADLINE);
        rt.clock().advance(DEADLINE);
        Future<Ack> ack = applied(applied).ack();
        Ack.Reply reply = assertInstanceOf(Ack.Reply.class, ack.result(), "a real answer beats a retry: " + ack);
        assertEquals(HeldPipeline.DESPITE_INTERRUPT, reply.reply().header(HeldPipeline.H_ANSWER).orElse(null));
        assertTrue(run.interrupted, "it was interrupted, and replied anyway");
        assertEquals(0, admission.inFlightRequests(), "the permit is back");
        assertTrue(settled(stopped).succeeded());
    }

    @Test
    void aCancelledSyncDeliveryLeavesTheCallersInterruptFlagClear() {
        for (boolean replies : new boolean[] {true, false}) {
            HeldPipeline held = HeldPipeline.stubborn(replies);   // returns or throws with the flag the deadline set still set
            Delivery delivery = Delivery.sync(roomy(), held, rt.timer());
            Envelope in = ingress(ORDER);
            CompletableFuture<Applied> applied = applyOn(delivery, in);
            HeldPipeline.Run run = held.run(in.id());
            Future<Void> stopped = delivery.stop(DEADLINE);
            rt.clock().advance(DEADLINE);
            Applied a = applied(applied);
            String path = replies ? "replied" : "failed";
            assertTrue(run.interrupted, path + ": the pipeline saw the interrupt and left the flag set");
            Class<? extends Ack> expected = replies ? Ack.Reply.class : Ack.Nack.class;
            assertInstanceOf(expected, a.ack().result(), path + ": " + a.ack());
            assertFalse(a.interruptedAfter(), path + ": the interrupt the stop caused does not leak to the consumer's thread");
            assertTrue(settled(stopped).succeeded(), path);
        }
    }

    @Test
    void anAtLeastOnceSaveDoneBeforeTheDeadlineKeepsAccepted() {
        FlowDefinition def = atLeastOnceSaving30s();
        FlowBuilder b = builder();
        Delivery delivery = b.delivery(def, NO_FILES);
        FlowWorker worker = b.worker(def, NO_FILES, "w1");
        store.hold();
        Envelope in = ingress(ORDER);
        Future<Ack> ack = delivery.apply(in);
        Future<Void> stopped = delivery.stop(DEADLINE);
        assertFalse(ack.isComplete(), "the save in flight is not answered by the stop");
        assertFalse(stopped.isComplete(), "a save is in flight");
        rt.clock().advance(DEADLINE.minusMillis(1));
        assertFalse(ack.isComplete(), "nor one millisecond before the deadline");

        store.release();
        assertEquals(new Ack.Accepted(in.id()), settled(ack).result(), "saved before the deadline: the sender keeps its 202");
        assertTrue(settled(stopped).succeeded(), "the last answer is decided: stopped");
        rt.clock().advance(Duration.ofMillis(1));   // the deadline passes
        List<WorkQueue.Item> items = queue.snapshot();
        assertEquals(1, items.size(), "its work item stays");
        assertEquals(in.id(), items.get(0).messageId());
        assertEquals(1, worker.poll().await(), "and runs: the worker is not stopped by the delivery's stop");
        assertEquals(1, partner.sentTo("partner").size());
        assertEquals(0, rt.timer().pending(), "neither the save timeout nor the stop deadline is left");
    }

    @Test
    void anAtLeastOnceSavePendingAtTheDeadlineAnswersARetryableNackAndEnqueuesNothing() {
        FlowDefinition def = atLeastOnceSaving30s();
        FlowBuilder b = builder();
        Delivery delivery = b.delivery(def, NO_FILES);
        FlowWorker worker = b.worker(def, NO_FILES, "w1");
        store.hold();
        Envelope in = ingress(ORDER);
        Future<Ack> ack = delivery.apply(in);
        Future<Void> stopped = delivery.stop(DEADLINE);

        rt.clock().advance(DEADLINE);
        Ack.Nack nack = cancelled(ack);
        assertFalse(nack.reason().contains("timed out"), "the stop's deadline decided it, not the 30 s save timeout: " + nack.reason());
        assertTrue(settled(stopped).succeeded(), "its answer is decided: nothing is in flight");
        store.release();   // the save completes late
        assertEquals(0, queue.size(), "a save completing after the cancel enqueues nothing");
        assertEquals(0, worker.poll().await());
        assertEquals(List.of(), partner.sent());
        assertEquals(0, rt.timer().pending(), "the save timeout went with the late save");
    }

    @Test
    void anAtMostOnceRunAtTheDeadlineIsInterruptedAndLogged() throws InterruptedException {
        HeldPipeline held = HeldPipeline.awaiting();
        Delivery delivery = heldFlow("at-most-once", held);   // built by the FlowBuilder: the runtime's timer drives the deadline
        Envelope in = ingress(ORDER);
        CountDownLatch logged = new CountDownLatch(1);
        java.util.logging.Logger jul = java.util.logging.Logger.getLogger(Delivery.class.getName());   // System.Logger delegates to it
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord r) {
                String text = new java.util.logging.SimpleFormatter().formatMessage(r);
                if (r.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue() && text != null && text.contains(in.id())) logged.countDown();
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        handler.setLevel(java.util.logging.Level.ALL);
        jul.addHandler(handler);
        try {
            Future<Ack> ack = delivery.apply(in);
            assertEquals(new Ack.Accepted(in.id()), ack.result(), "accepted at once, as before");
            HeldPipeline.Run run = held.run(in.id());
            Future<Void> stopped = delivery.stop(DEADLINE);
            assertFalse(stopped.isComplete(), "an at-most-once run is in flight until its pipeline ends");
            rt.clock().advance(DEADLINE);
            assertTrue(logged.await(HeldPipeline.BOUND_SECONDS, TimeUnit.SECONDS), "the interrupted run's failure is logged with the message id " + in.id());
            run.joined();
            assertTrue(run.interrupted, "the run was interrupted at the deadline");
            assertTrue(settled(stopped).succeeded(), "stopped once the interrupted run ended");
            assertEquals(0, rt.timer().pending());
        } finally {
            jul.removeHandler(handler);
        }
    }

    @Test
    void stopCompletesWhenTheLastDeliveryIsAnsweredAndLeavesNoTimerPending() {
        HeldPipeline held = HeldPipeline.awaiting();
        Delivery delivery = Delivery.sync(roomy(), held, rt.timer());
        Envelope a = ingress(ORDER);
        Envelope b = ingress(ORDER);
        CompletableFuture<Applied> appliedA = applyOn(delivery, a);
        CompletableFuture<Applied> appliedB = applyOn(delivery, b);
        HeldPipeline.Run runA = held.run(a.id());
        HeldPipeline.Run runB = held.run(b.id());
        Future<Void> stopped = delivery.stop(DEADLINE);
        assertEquals(1, rt.timer().pending(), "the deadline is scheduled");

        runA.reply("a");
        assertInstanceOf(Ack.Reply.class, applied(appliedA).ack().result());
        assertFalse(stopped.isComplete(), "b is still in flight");
        runB.reply("b");
        assertInstanceOf(Ack.Reply.class, applied(appliedB).ack().result());
        assertTrue(settled(stopped).succeeded(), "the last delivery answered: stopped");
        assertEquals(0, rt.timer().pending(), "the deadline timer is cancelled, not left to fire");
        rt.clock().advance(DEADLINE);
        assertFalse(runA.interrupted || runB.interrupted, "nothing was interrupted");
    }

    @Test
    void stopWithNothingInFlightCompletesAtOnce() {
        Delivery idle = Delivery.sync(roomy(), HeldPipeline.awaiting(), rt.timer());
        Future<Void> stopped = idle.stop(DEADLINE);
        assertTrue(stopped.isComplete() && stopped.succeeded(), "nothing in flight: stopped when stop returns");
        assertEquals(0, rt.timer().pending(), "no deadline left scheduled");

        HeldPipeline held = HeldPipeline.awaiting();
        Delivery answeredBefore = Delivery.sync(roomy(), held, rt.timer());
        Envelope in = ingress(ORDER);
        CompletableFuture<Applied> applied = applyOn(answeredBefore, in);
        held.run(in.id()).reply("done");
        assertInstanceOf(Ack.Reply.class, applied(applied).ack().result());
        assertTrue(answeredBefore.stop(DEADLINE).isComplete(), "a delivery answered before the stop is not in flight any more");

        Delivery saved = builder().delivery(atLeastOnceSaving30s(), NO_FILES);
        Envelope accepted = ingress(ORDER);
        assertEquals(new Ack.Accepted(accepted.id()), saved.apply(accepted).await());
        assertTrue(saved.stop(DEADLINE).isComplete(), "an at-least-once delivery whose save is done is not in flight");
        assertEquals(0, rt.timer().pending());
    }

    @Test
    void stopTwiceReturnsTheSameFuture() {
        HeldPipeline held = HeldPipeline.awaiting();
        Delivery delivery = Delivery.sync(roomy(), held, rt.timer());
        Envelope in = ingress(ORDER);
        CompletableFuture<Applied> applied = applyOn(delivery, in);
        HeldPipeline.Run run = held.run(in.id());
        Future<Void> first = delivery.stop(DEADLINE);
        assertSame(first, delivery.stop(Duration.ofSeconds(1)), "a second call returns the stop under way");
        assertEquals(1, rt.timer().pending(), "one deadline, the first call's");
        rt.clock().advance(Duration.ofSeconds(1));
        assertEquals(Duration.ofSeconds(4), rt.timer().nextDelay(), "the first call's deadline still governs");
        run.reply("in-time");
        assertInstanceOf(Ack.Reply.class, applied(applied).ack().result());
        assertFalse(run.interrupted);
        assertTrue(settled(first).succeeded());
        assertSame(first, delivery.stop(DEADLINE), "also once it has completed");
    }

    @Test
    void stopRefusesANegativeOrNullDeadline() {
        HeldPipeline held = HeldPipeline.awaiting();
        Delivery delivery = Delivery.sync(roomy(), held, rt.timer());
        assertThrows(NullPointerException.class, () -> delivery.stop(null));
        assertThrows(IllegalArgumentException.class, () -> delivery.stop(Duration.ofMillis(-1)));
        assertEquals(0, rt.timer().pending(), "nothing scheduled");
        Envelope in = ingress(ORDER);
        CompletableFuture<Applied> applied = applyOn(delivery, in);
        held.run(in.id()).reply("still-running");
        assertInstanceOf(Ack.Reply.class, applied(applied).ack().result(), "a refused stop stopped nothing: the flow still delivers");
    }

    @Test
    void aZeroDeadlineCancelsAtOnce() {
        Admission admission = roomy();
        HeldPipeline held = HeldPipeline.awaiting();
        Delivery delivery = Delivery.sync(admission, held, rt.timer());
        Envelope in = ingress(ORDER);
        CompletableFuture<Applied> applied = applyOn(delivery, in);
        HeldPipeline.Run run = held.run(in.id());
        Future<Void> stopped = delivery.stop(Duration.ZERO);   // the clock never moves in this case
        cancelled(applied(applied).ack());
        assertTrue(run.interrupted, "interrupted without the clock moving");
        assertTrue(settled(stopped).succeeded());
        assertEquals(0, admission.inFlightRequests(), "the permit is back");
        assertEquals(0, rt.timer().pending(), "nothing left on the timer");
    }
}
