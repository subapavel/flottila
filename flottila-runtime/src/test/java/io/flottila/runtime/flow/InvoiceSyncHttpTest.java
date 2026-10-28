package io.flottila.runtime.flow;

import io.flottila.api.Ack;
import io.flottila.api.Component;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Health;
import io.flottila.api.Pipeline;
import io.flottila.api.Producer;
import io.flottila.components.http.HttpComponent;
import io.flottila.core.flow.FlowBuilder;
import io.flottila.core.flow.FlowDefinition;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.core.record.Page;
import io.flottila.core.record.Snapshot;
import io.flottila.schemas.Documents;
import io.flottila.steps.validate.XsdValidate;
import io.flottila.steps.xslt.SaxonXml;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * GATE check 3 end to end on real sockets: HTTP → validate → extractKey → route → XSLT → HTTP partner → 200 carrying the
 * partner's reply. The demo flow, the real http component on both ends (its consumer at {@code /in}, its producer to a
 * partner served by Vert.x on the loopback interface), the pipeline run by the consumer's handler on the message's virtual
 * thread, snapshots in the in-memory store.
 *
 * <p>The partner step is {@code retryable}: a failed send parks the message thread in the retry's pause on the
 * {@link FakeRuntime} timer, which this test never advances (FIX-21). So every wait here is bounded by {@link #BOUND_SECONDS},
 * a recorded partner-send failure fails the request's wait at once with that failure's message, and the teardown interrupts
 * a message thread still running (the pause gives up and the request is answered) before it stops the consumer.
 */
@Timeout(60)
class InvoiceSyncHttpTest {

    static final Path DEMO = Path.of("../packages/demo");
    /** Every wait in this test: well under the class timeout, so a stuck wait fails with what it waited for. */
    static final long BOUND_SECONDS = 10;
    static Vertx vertx;
    static HttpClientAgent caller;
    static Buffer invoice;
    static Buffer ack;
    static JsonObject flowDoc;

    final FakeRuntime rt = FakeRuntime.create();
    final HttpComponent http = new HttpComponent(vertx, rt.blobs(), rt.ids());
    final InMemoryMessageStore store = new InMemoryMessageStore();
    final List<String> partnerSaw = new CopyOnWriteArrayList<>();
    /** Every failed partner send, in order, and the first one as soon as it is recorded. */
    final List<Throwable> sendFailures = new CopyOnWriteArrayList<>();
    final CompletableFuture<Throwable> firstSendFailure = new CompletableFuture<>();
    /** The message threads inside the consumer's handler right now. */
    final Set<Thread> running = ConcurrentHashMap.newKeySet();
    HttpServer partner;
    Consumer in;
    int port;

    @BeforeAll
    static void start() throws IOException {
        vertx = Vertx.vertx();
        caller = vertx.createHttpClient();
        invoice = Buffer.buffer(Files.readAllBytes(DEMO.resolve("fixtures/sample-invoice.xml")));
        ack = Buffer.buffer(Files.readAllBytes(DEMO.resolve("fixtures/partner-ack.xml")));
        flowDoc = Documents.read(DEMO.resolve("flows/invoice-sync.yaml"));
    }

    @AfterAll
    static void stop() {
        within(caller.close(), "the caller's close");
        within(vertx.close(), "Vert.x's close");
    }

    /** {@code f}'s result within {@link #BOUND_SECONDS}; a failed future throws its cause, a pending one fails naming {@code what}. */
    static <T> T within(Future<T> f, String what) {
        try {
            return f.await(BOUND_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException stuck) {
            return fail(what + " did not complete within " + BOUND_SECONDS + " s");
        }
    }

    /** The http component as it is, except that each failed producer send is recorded before the pipeline sees it. */
    Component recordingSendFailures(HttpComponent real) {
        return new Component() {
            @Override public String scheme() { return real.scheme(); }
            @Override public String configSchema() { return real.configSchema(); }
            @Override public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) { return real.consumer(cfg, handler); }
            @Override public Future<Health> health() { return real.health(); }
            @Override public Producer producer(EndpointConfig cfg) {
                Producer p = real.producer(cfg);
                return new Producer() {
                    @Override public Future<Envelope> send(Envelope e) {
                        return p.send(e).onFailure(failure -> {
                            sendFailures.add(failure);
                            firstSendFailure.complete(failure);
                        });
                    }
                    @Override public Future<Void> close() { return p.close(); }
                };
            }
        };
    }

    /** Where each message thread still in the handler sits: its stack. */
    String where() {
        StringBuilder b = new StringBuilder();
        for (Thread t : running) {
            b.append("\n  ").append(t);
            for (StackTraceElement frame : t.getStackTrace()) b.append("\n    at ").append(frame);
        }
        return running.isEmpty() ? "none in the handler" : b.toString();
    }

    @BeforeEach
    void wire() {
        partner = within(vertx.createHttpServer().requestHandler(req -> req.body().onSuccess(b -> {
            partnerSaw.add(req.path() + " " + req.getHeader("content-type") + " " + b);
            req.response().putHeader("content-type", "application/xml").end(ack);
        })).listen(0, "127.0.0.1"), "the partner's listen");
        rt.withComponent(recordingSendFailures(http));
        String base = "http://127.0.0.1:" + partner.actualPort();
        Map<String, EndpointConfig> endpoints = Map.of(
                "partner-domestic", new EndpointConfig("partner-domestic", URI.create(base + "/echo"), Map.of("url", base + "/echo", "maxBodyBytes", 1_048_576)),
                "partner-foreign", new EndpointConfig("partner-foreign", URI.create(base + "/echo-foreign"), Map.of("url", base + "/echo-foreign", "maxBodyBytes", 1_048_576)));
        FlowDefinition def = FlowDefinition.from(flowDoc.getMap());
        SaxonXml xml = new SaxonXml();
        Pipeline pipeline = new FlowBuilder(rt.runtime(), Stream.concat(new XsdValidate().steps().stream(), xml.steps().stream()).toList(), xml.languages(), endpoints).recording(store, Meter.system()).build(def, DEMO);
        // the consumer's handler: the sync flow's reply is the ACK (ARCHITECTURE §4); the delivery-mode wrapper proper is P1-08
        EndpointConfig httpIn = new EndpointConfig("http-in", URI.create("http://127.0.0.1:0/in"), Map.of("host", "127.0.0.1", "port", 0, "path", "/in", "flow", def.id(), "maxBodyBytes", def.maxBodyBytes(), "shutdownTimeoutMs", 5_000));
        HttpComponent.HttpConsumer consumer = (HttpComponent.HttpConsumer) http.consumer(httpIn, e -> {
            running.add(Thread.currentThread());
            try {
                return Future.succeededFuture(new Ack.Reply(pipeline.apply(e)));
            } catch (Exception failure) {   // an interrupted await (the teardown's) throws InterruptedException, sneakily
                return Future.failedFuture(failure);
            } finally {
                running.remove(Thread.currentThread());
            }
        });
        within(consumer.start(), "the consumer's start");
        in = consumer;
        port = consumer.port();
    }

    @AfterEach
    void down() {
        // a message parked in the retry's pause on the fake timer gives up when interrupted: its request is answered
        running.forEach(Thread::interrupt);
        if (in != null) {
            try {
                in.stop().await(BOUND_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException stuck) {
                fail("the consumer did not stop within " + BOUND_SECONDS + " s; message threads: " + where());
            }
        }
        if (partner != null) within(partner.close(), "the partner's close");
    }

    record Answer(int status, String contentType, String body) {}

    /**
     * Posts {@code body} to the flow and returns the answer; fails at once when a partner send failed first (its retry would
     * wait on the fake timer for good), naming that failure, and fails naming where the message threads sit when there is
     * no answer within {@link #BOUND_SECONDS}.
     */
    Answer post(Buffer body) {
        CompletableFuture<Answer> answer = caller.request(new RequestOptions().setMethod(HttpMethod.POST).setAbsoluteURI("http://127.0.0.1:" + port + "/in"))
                .compose(req -> { req.putHeader("content-type", "application/xml"); req.putHeader("x-request-id", "r-42"); return req.send(body); })
                .compose(res -> res.body().map(b -> new Answer(res.statusCode(), res.getHeader("content-type"), b.toString())))
                .toCompletionStage().toCompletableFuture();
        Throwable requestFailed = null;
        try {
            CompletableFuture.anyOf(answer, firstSendFailure).get(BOUND_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException failed) {
            requestFailed = failed.getCause();
        } catch (TimeoutException stuck) {
            return fail("no answer within " + BOUND_SECONDS + " s; partner-send failures " + sendFailures + "; message threads: " + where());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return fail("interrupted while waiting for the answer", interrupted);
        }
        if (firstSendFailure.isDone()) {
            Throwable first = firstSendFailure.join();
            return fail("the partner send failed: " + first + " (all: " + sendFailures + "); the retry would wait on the fake timer", first);
        }
        if (requestFailed != null) return fail("the request to /in failed: " + requestFailed, requestFailed);
        return answer.join();
    }

    @Test
    void theInvoiceGoesInOverHttpAndThePartnersReplyComesBackAs200() {
        Answer a = post(invoice);
        assertEquals(200, a.status());
        assertEquals(ack.toString(), a.body(), "the partner's reply is the HTTP reply");
        assertEquals("application/xml", a.contentType());
        assertEquals(1, partnerSaw.size());
        assertTrue(partnerSaw.get(0).startsWith("/echo application/xml"), partnerSaw.get(0));
        assertTrue(partnerSaw.get(0).contains("<PartnerInvoice number=\"4711\" currency=\"CZK\">") && partnerSaw.get(0).contains("<Total>550.5</Total>"), "the mapped invoice reached the partner: " + partnerSaw.get(0));
        List<String> ids = within(store.findByKey("invoiceId", "4711", Optional.empty(), Page.MAX_LIMIT), "the key search").items();
        assertEquals(1, ids.size(), "the message is on record by its business key");
        List<Snapshot> t = within(store.timeline(ids.get(0)), "the timeline read");
        assertEquals(List.of("ingress", "validate", "extractKey", "route", "toPartner", "partner"), t.stream().map(Snapshot::stepName).toList());
        assertEquals("r-42", t.get(0).headers().get("x-request-id"), "what arrived is what was recorded");
        assertEquals("200", String.valueOf(t.get(5).props().asMap().get("http.status")), "the partner's status on the reply snapshot");
    }

    @Test
    void anInvoiceWithoutAnIdIsA422NamingTheValidateStepAndNothingReachesThePartner() {
        Answer a = post(Buffer.buffer(invoice.toString().replace("<Id>4711</Id>", "")));
        assertEquals(422, a.status(), "an invalid input is the sender's fault, by kind (D-049)");
        assertTrue(a.body().contains("validation failed at step 'validate'"), a.body());
        assertEquals(List.of(), partnerSaw);
    }

    @Test
    void aForeignInvoiceGoesToTheForeignPartnersUrl() {
        Answer a = post(Buffer.buffer(invoice.toString().replace("<Currency>CZK</Currency>", "<Currency>EUR</Currency>")));
        assertEquals(200, a.status());
        assertTrue(partnerSaw.get(0).startsWith("/echo-foreign "), partnerSaw.get(0));
    }
}
