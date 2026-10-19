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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-08 delivery modes at the {@link Ack} level (ARCHITECTURE §4 lifecycle table, admission line 112, D-049): the
 * {@link Delivery} handler and the {@link FlowWorker} over a {@link RecordingComponent} partner, the in-memory store,
 * queue and DLQ and the fake clock. Sync replies or fails with the step's exception; at-least-once accepts only once the
 * ingress snapshot and the queue item are durable and the worker runs the pipeline later; at-most-once accepts before
 * anything runs and drops a failure; a delivery over the flow's in-flight limits is a retryable Nack.
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
        rt.clock().advance(FlowWorker.LEASE);
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
        rt.clock().advance(FlowWorker.LEASE);
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
}
