package io.flottila.runtime.flow;

import io.flottila.api.Ack;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.NackException;
import io.flottila.components.http.HttpComponent;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.flow.FlowBuilder;
import io.flottila.core.flow.FlowDefinition;
import io.flottila.core.flow.FlowWorker;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.core.record.Snapshot;
import io.flottila.steps.validate.XsdValidate;
import io.flottila.steps.xslt.SaxonXml;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.NoBlockedPlatformThreads;
import io.flottila.testkit.RecordingComponent;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-08 delivery modes as the sender sees them over http (ARCHITECTURE §4 lifecycle table, admission, D-049): the flow's
 * delivery handler behind the real http consumer (bound to port 0), a {@link RecordingComponent} as the partner, the
 * in-memory store / queue / DLQ and the fake clock. Sync answers with the partner's reply or the failure's status by kind;
 * at-least-once answers 202 and the {@link FlowWorker} runs the pipeline afterwards; at-most-once answers 202 before
 * anything runs; admission answers 503 + retry-after. The Ack-level cases live in core's {@code DeliveryTest}.
 */
@Timeout(10)
@ExtendWith(NoBlockedPlatformThreads.class)
class DeliveryModesTest {

    static final Path FLOWS = Path.of("../packages/demo/flows");
    static final Buffer ORDER = Buffer.buffer("<order id=\"1\"/>");
    static final Map<String, Object> TO_PARTNER = Map.of("type", "to", "name", "partner", "endpoint", "partner");
    static final Map<String, Object> VALIDATE = Map.of("type", "xsd", "name", "validate", "schema", "invoice.xsd");
    static final Map<String, Object> MAP_FOR_PARTNER = Map.of("type", "xslt", "name", "toPartner", "stylesheet", "invoice-to-partner.xsl");

    static Vertx vertx;
    static HttpClientAgent caller;
    static Buffer invoice;

    final FakeRuntime rt = FakeRuntime.create();
    final HttpComponent http = new HttpComponent(vertx, rt.blobs(), rt.ids());
    final RecordingComponent partner = new RecordingComponent("mock");
    final InMemoryMessageStore store = new InMemoryMessageStore();
    final InMemoryWorkQueue queue = new InMemoryWorkQueue(rt.clock());
    final InMemoryDlq dlq = new InMemoryDlq();
    final Map<String, EndpointConfig> endpoints = Map.of("partner", new EndpointConfig("partner", URI.create("mock://partner"), Map.of()));
    final List<Consumer> started = new CopyOnWriteArrayList<>();
    final List<CountDownLatch> holds = new CopyOnWriteArrayList<>();
    int port;

    @BeforeAll
    static void start() throws IOException {
        vertx = Vertx.vertx();
        caller = vertx.createHttpClient();
        invoice = Buffer.buffer(Files.readAllBytes(Path.of("../packages/demo/fixtures/sample-invoice.xml")));
    }

    @AfterAll
    static void stop() {
        caller.close().await();
        vertx.close().await();
    }

    @BeforeEach
    void wire() {
        rt.withComponent(partner);
    }

    @AfterEach
    void down() {
        holds.forEach(CountDownLatch::countDown);   // no send stays parked past its test
        for (Consumer c : started) c.stop().await();
    }

    // --- fixtures

    /** A flow file as core reads it: id {@code orders}, from {@code http-in}, a 64 KiB body limit, the given steps. */
    static Map<String, Object> flow(String mode, List<Map<String, Object>> steps) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("schemaVersion", 1);
        doc.put("kind", "flow");
        doc.put("id", "orders");
        doc.put("version", "0.1.0");
        doc.put("from", Map.of("endpoint", "http-in"));
        doc.put("delivery", Map.of("mode", mode));
        doc.put("limits", Map.of("maxBodyBytes", 65_536));
        doc.put("steps", steps);
        return doc;
    }

    FlowBuilder builder() {
        SaxonXml xml = new SaxonXml();
        return new FlowBuilder(rt.runtime(), Stream.concat(new XsdValidate().steps().stream(), xml.steps().stream()).toList(), xml.languages(), endpoints)
                .recording(store, Meter.system()).queue(queue).dlq(dlq);
    }

    /** The http consumer at {@code /in} on an ephemeral port with {@code handler}; {@link #port} is the port it got. */
    void listen(FlowDefinition def, Function<Envelope, Future<Ack>> handler) {
        EndpointConfig in = new EndpointConfig("http-in", URI.create("http://127.0.0.1:0/in"),
                Map.of("host", "127.0.0.1", "port", 0, "path", "/in", "flow", def.id(), "maxBodyBytes", def.maxBodyBytes()));
        HttpComponent.HttpConsumer c = (HttpComponent.HttpConsumer) http.consumer(in, handler);
        c.start().await();
        started.add(c);
        port = c.port();
    }

    /** Builds the flow's delivery handler and listens with it. */
    FlowDefinition listen(Map<String, Object> doc) {
        FlowDefinition def = FlowDefinition.from(doc);
        listen(def, builder().delivery(def, FLOWS));
        return def;
    }

    CountDownLatch holdAtPartner() {
        CountDownLatch release = partner.holdNext(1);
        holds.add(release);
        return release;
    }

    record Answer(int status, MultiMap headers, String body) {}

    Future<Answer> send(Buffer body, Map<String, String> headers) {
        return caller.request(new RequestOptions().setMethod(HttpMethod.POST).setAbsoluteURI("http://127.0.0.1:" + port + "/in"))
                .compose(req -> { headers.forEach(req::putHeader); return req.send(body); })
                .compose(res -> res.body().map(b -> new Answer(res.statusCode(), res.headers(), b.toString())));
    }

    Answer post(Buffer body) {
        return send(body, Map.of("content-type", "application/xml")).await();
    }

    List<String> stepNames(String messageId) {
        return store.timeline(messageId).await().stream().map(Snapshot::stepName).toList();
    }

    static void assertTextPlain(Answer a) {
        String contentType = a.headers().get("content-type");
        assertTrue(contentType != null && contentType.startsWith("text/plain"), "content-type " + contentType);
    }

    // --- sync

    @Test
    void aSyncFlowAnswersThePartnersReplyAs200() {
        partner.replyWith(e -> e.next(rt.store(e, Buffer.buffer("<ack/>"))).withHeader(Envelope.H_CONTENT_TYPE, "application/xml"));
        listen(flow("sync", List.of(TO_PARTNER)));
        Answer a = post(ORDER);
        assertEquals(200, a.status(), a.body());
        assertEquals("<ack/>", a.body(), "the partner's reply is the answer");
        List<Envelope> got = partner.sentTo("partner");
        assertEquals(1, got.size());
        assertEquals(ORDER.toString(), got.get(0).body().whole().toString());
    }

    @Test
    void aSyncFlowMapsAnInvalidInputTo422NamingTheStep() {
        listen(flow("sync", List.of(VALIDATE, TO_PARTNER)));
        Answer a = post(Buffer.buffer(invoice.toString().replace("<Id>4711</Id>", "")));
        assertEquals(422, a.status(), a.body());
        assertTrue(a.body().startsWith("validation failed at step 'validate'"), "the step's message, not its toString(): " + a.body());
        assertTextPlain(a);
        assertEquals(List.of(), partner.sent());
    }

    @Test
    void aSyncFlowMapsAMalformedInputTo400() {
        listen(flow("sync", List.of(MAP_FOR_PARTNER, TO_PARTNER)));
        Answer a = post(Buffer.buffer("not xml"));
        assertEquals(400, a.status(), a.body());
        assertTrue(a.body().contains("not well-formed"), a.body());
        assertTextPlain(a);
        assertEquals(List.of(), partner.sent());
    }

    @Test
    void aSyncFlowMapsAFinalDownstreamNackTo502AndARetryableOneTo503() {
        Map<String, Object> doc = flow("sync", List.of(TO_PARTNER));
        doc.put("retry", Map.of("attempts", 1, "initial", "100ms", "max", "100ms"));   // the step is not retryable: one attempt per request
        listen(doc);
        partner.failNext(1, () -> new NackException("order refused", false));
        Answer refused = post(ORDER);
        assertEquals(502, refused.status(), refused.body());
        assertTrue(refused.body().contains("order refused"), refused.body());
        assertNull(refused.headers().get("retry-after"));
        partner.failNext(1, () -> new NackException("partner busy", true));
        Answer busy = post(ORDER);
        assertEquals(503, busy.status(), busy.body());
        assertTrue(busy.body().contains("partner busy"), busy.body());
        assertEquals("1", busy.headers().get("retry-after"));
        assertTextPlain(busy);
    }

    @Test
    void aSyncFlowMapsAnUnexpectedStepFailureTo500() {
        listen(flow("sync", List.of(TO_PARTNER)));
        partner.failNext(1, () -> new IllegalStateException("boom"));
        Answer a = post(ORDER);
        assertEquals(500, a.status(), a.body());
        assertEquals("boom", a.body());
        assertTextPlain(a);
    }

    // --- admission

    @Test
    void aFlowOverItsInFlightRequestLimitAnswers503UntilAMessageFinishes() {
        Map<String, Object> doc = flow("sync", List.of(TO_PARTNER));
        doc.put("limits", Map.of("maxBodyBytes", 65_536, "maxInFlight", 1));
        listen(doc);
        CountDownLatch release = holdAtPartner();
        Future<Answer> first = send(ORDER, Map.of("content-type", "application/xml"));
        partner.awaitSent(1);
        Answer refused = post(ORDER);
        assertEquals(503, refused.status(), refused.body());
        assertEquals("1", refused.headers().get("retry-after"));
        assertEquals(1, partner.sent().size(), "a refused message runs nothing");
        release.countDown();
        assertEquals(200, first.await().status());
        assertEquals(200, post(ORDER).status(), "the finished message gave its permit back");
        assertEquals(2, partner.sent().size());
    }

    @Test
    void aFlowOverItsInFlightByteLimitAnswers503() {
        Map<String, Object> doc = flow("sync", List.of(TO_PARTNER));
        doc.put("limits", Map.of("maxBodyBytes", 1000, "maxInFlight", 8, "maxInFlightBytes", 1000));
        listen(doc);
        Buffer body = Buffer.buffer("x".repeat(600));
        CountDownLatch release = holdAtPartner();
        Future<Answer> first = send(body, Map.of());
        partner.awaitSent(1);
        Answer refused = send(body, Map.of()).await();
        assertEquals(503, refused.status(), "600 + 600 bytes exceed 1000 in flight: " + refused.body());
        assertEquals("1", refused.headers().get("retry-after"));
        release.countDown();
        assertEquals(200, first.await().status());
        assertEquals(1, partner.sent().size());
    }

    // --- at-least-once

    @Test
    void anAtLeastOnceMessageIsProcessedByTheWorkerAfterThe202() {
        FlowDefinition def = FlowDefinition.from(flow("at-least-once", List.of(TO_PARTNER)));
        FlowBuilder b = builder();
        listen(def, b.delivery(def, FLOWS));
        FlowWorker worker = b.worker(def, FLOWS, "w1");
        Answer a = post(ORDER);
        assertEquals(202, a.status(), a.body());
        String id = a.headers().get(HttpComponent.H_MESSAGE_ID);
        assertNotNull(id);
        assertEquals(List.of(), partner.sent(), "nothing ran before the worker");
        assertEquals(1, queue.size());
        assertEquals(1, worker.poll().await());
        List<Envelope> got = partner.sentTo("partner");
        assertEquals(1, got.size());
        assertEquals(id, got.get(0).id());
        assertEquals(ORDER.toString(), got.get(0).body().whole().toString(), "the body rebuilt from the store by its key");
        assertEquals(0, queue.size(), "the item is completed");
        assertEquals(List.of("ingress", "partner"), stepNames(id));
    }

    // --- at-most-once

    @Test
    void anAtMostOnceFlowAnswers202BeforeTheStepsRun() {
        listen(flow("at-most-once", List.of(TO_PARTNER)));
        CountDownLatch release = holdAtPartner();
        Answer a = post(ORDER);
        assertEquals(202, a.status(), "answered while the partner still holds the message: " + a.body());
        String id = a.headers().get(HttpComponent.H_MESSAGE_ID);
        assertNotNull(id);
        release.countDown();
        partner.awaitSent(1);
        assertEquals(id, partner.sent().get(0).id());
        assertEquals(0, queue.size(), "nothing queued");
    }

    // --- forwardHeaders

    @Test
    void onlyTheAllowListedIngressHeadersReachTheProducer() {
        Map<String, Object> doc = flow("sync", List.of(TO_PARTNER));
        doc.put("forwardHeaders", List.of("Content-Type", "X-Request-Id"));
        listen(doc);
        Answer a = send(ORDER, Map.of("content-type", "application/xml", "x-request-id", "r-42", "x-secret", "s")).await();
        assertEquals(200, a.status(), a.body());
        Envelope got = partner.sentTo("partner").get(0);
        assertEquals("r-42", got.headers().get("x-request-id"));
        assertEquals("application/xml", got.headers().get("content-type"));
        assertNull(got.headers().get("x-secret"), "nothing propagates implicitly: " + got.headers());
    }
}
