package io.flottila.runtime.flow;

import io.flottila.api.Ack;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Pipeline;
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
import java.util.stream.Stream;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GATE check 3 end to end on real sockets: HTTP → validate → extractKey → route → XSLT → HTTP partner → 200 carrying the
 * partner's reply. The demo flow, the real http component on both ends (its consumer at {@code /in}, its producer to a
 * partner served by Vert.x on the loopback interface), the pipeline run by the consumer's handler on the message's virtual
 * thread, snapshots in the in-memory store.
 */
@Timeout(60)
class InvoiceSyncHttpTest {

    static final Path DEMO = Path.of("../packages/demo");
    static Vertx vertx;
    static HttpClientAgent caller;
    static Buffer invoice;
    static Buffer ack;
    static JsonObject flowDoc;

    final FakeRuntime rt = FakeRuntime.create();
    final HttpComponent http = new HttpComponent(vertx, rt.blobs(), rt.ids());
    final InMemoryMessageStore store = new InMemoryMessageStore();
    final List<String> partnerSaw = new CopyOnWriteArrayList<>();
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
        caller.close().await();
        vertx.close().await();
    }

    @BeforeEach
    void wire() {
        partner = vertx.createHttpServer().requestHandler(req -> req.body().onSuccess(b -> {
            partnerSaw.add(req.path() + " " + req.getHeader("content-type") + " " + b);
            req.response().putHeader("content-type", "application/xml").end(ack);
        })).listen(0, "127.0.0.1").await();
        rt.withComponent(http);
        String base = "http://127.0.0.1:" + partner.actualPort();
        Map<String, EndpointConfig> endpoints = Map.of(
                "partner-domestic", new EndpointConfig("partner-domestic", URI.create(base + "/echo"), Map.of("url", base + "/echo", "maxBodyBytes", 1_048_576)),
                "partner-foreign", new EndpointConfig("partner-foreign", URI.create(base + "/echo-foreign"), Map.of("url", base + "/echo-foreign", "maxBodyBytes", 1_048_576)));
        FlowDefinition def = FlowDefinition.from(flowDoc.getMap());
        SaxonXml xml = new SaxonXml();
        Pipeline pipeline = new FlowBuilder(rt.runtime(), Stream.concat(new XsdValidate().steps().stream(), xml.steps().stream()).toList(), xml.languages(), endpoints).recording(store, Meter.system()).build(def, DEMO.resolve("flows"));
        // the consumer's handler: the sync flow's reply is the ACK (ARCHITECTURE §4); the delivery-mode wrapper proper is P1-08
        EndpointConfig httpIn = new EndpointConfig("http-in", URI.create("http://127.0.0.1:0/in"), Map.of("host", "127.0.0.1", "port", 0, "path", "/in", "flow", def.id(), "maxBodyBytes", def.maxBodyBytes()));
        HttpComponent.HttpConsumer consumer = (HttpComponent.HttpConsumer) http.consumer(httpIn, e -> {
            try {
                return Future.succeededFuture(new Ack.Reply(pipeline.apply(e)));
            } catch (RuntimeException failure) {
                return Future.failedFuture(failure);
            }
        });
        consumer.start().await();
        in = consumer;
        port = consumer.port();
    }

    @AfterEach
    void down() {
        in.stop().await();
        partner.close().await();
    }

    record Answer(int status, String contentType, String body) {}

    Answer post(Buffer body) {
        return caller.request(new RequestOptions().setMethod(HttpMethod.POST).setAbsoluteURI("http://127.0.0.1:" + port + "/in"))
                .compose(req -> { req.putHeader("content-type", "application/xml"); req.putHeader("x-request-id", "r-42"); return req.send(body); })
                .compose(res -> res.body().map(b -> new Answer(res.statusCode(), res.getHeader("content-type"), b.toString()))).await();
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
        List<String> ids = store.findByKey("invoiceId", "4711", Optional.empty(), Page.MAX_LIMIT).await().items();
        assertEquals(1, ids.size(), "the message is on record by its business key");
        List<Snapshot> t = store.timeline(ids.get(0)).await();
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
