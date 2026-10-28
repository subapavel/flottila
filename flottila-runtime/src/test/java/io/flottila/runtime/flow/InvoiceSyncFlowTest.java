package io.flottila.runtime.flow;

import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Key;
import io.flottila.api.Pipeline;
import io.flottila.api.StepFactory;
import io.flottila.core.flow.FlowBuilder;
import io.flottila.core.flow.FlowDefinition;
import io.flottila.schemas.Documents;
import io.flottila.schemas.Schemas;
import io.flottila.steps.validate.XsdValidate;
import io.flottila.steps.validate.XsdValidationException;
import io.flottila.steps.xslt.SaxonXml;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.NoBlockedPlatformThreads;
import io.flottila.testkit.RecordingComponent;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The milestone-1 flow end to end (BACKLOG P1-03 acceptance; ARCHITECTURE §15 Phase 1 gate): the demo package's
 * {@code invoice-sync.yaml} through the schema loader, the builder, the Saxon steps and the fake Runtime —
 * validate → extractKey → route → xslt → to, the {@code to} step calling the mock binding of the {@code http} scheme.
 */
@ExtendWith(NoBlockedPlatformThreads.class)
@Timeout(60)
class InvoiceSyncFlowTest {

    static final Path DEMO = Path.of("../packages/demo");
    static final Key<String> PARTNER = new Key<>("PARTNER");

    final FakeRuntime rt = FakeRuntime.create();
    final RecordingComponent http = new RecordingComponent("http");
    final SaxonXml xml = new SaxonXml();

    /** The demo flow's steps: xsd from the validate module, xslt from the Saxon one. */
    List<StepFactory> steps() { return Stream.concat(new XsdValidate().steps().stream(), xml.steps().stream()).toList(); }
    Buffer invoice;
    Buffer ack;
    Pipeline pipeline;

    @BeforeEach
    void buildTheFlowFromThePackage() throws IOException {
        rt.withComponent(http);
        JsonObject flow = Documents.read(DEMO.resolve("flows/invoice-sync.yaml"));
        Schemas.Report report = Schemas.FLOW.validate(flow);
        assertTrue(report.valid(), report.toString());
        FlowDefinition def = FlowDefinition.from(flow.getMap());
        assertEquals("invoice-sync", def.id());
        assertEquals(List.of("partner-domestic", "partner-foreign"), def.referencedEndpoints());
        Map<String, EndpointConfig> endpoints = new HashMap<>();
        try (Stream<Path> files = Files.list(DEMO.resolve("endpoints"))) {
            for (Path file : files.toList()) {
                JsonObject ep = Documents.read(file);
                assertTrue(Schemas.ENDPOINT.validate(ep).valid(), file.toString());
                String id = ep.getString("id");
                endpoints.put(id, new EndpointConfig(id, URI.create(ep.getString("scheme") + "://" + id), ep.getJsonObject("config").getMap()));
            }
        }
        invoice = Buffer.buffer(Files.readAllBytes(DEMO.resolve("fixtures/sample-invoice.xml")));
        ack = Buffer.buffer(Files.readAllBytes(DEMO.resolve("fixtures/partner-ack.xml")));
        http.replyWith(e -> e.next(rt.store(e, ack)));   // the partner answers with the ack fixture (invoice-sync.test.yaml mocks)
        pipeline = new FlowBuilder(rt.runtime(), steps(), xml.languages(), endpoints).build(def, DEMO);
        assertEquals(2, http.producersBuilt(), "both partner endpoints resolved at build time");
    }

    Envelope ingress(Buffer body) {
        return rt.ingress("invoice-sync", Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml").with("x-request-id", "r-1"), body);
    }

    /** Runs the message on its own virtual thread (a retry parks it) and drives the fake clock through every wait. */
    Envelope run(Envelope in, List<Duration> waits) {
        AtomicReference<Object> outcome = new AtomicReference<>();
        Thread t = Thread.ofVirtual().start(() -> { try { outcome.set(pipeline.apply(in)); } catch (Throwable x) { outcome.set(x); } });
        while (t.isAlive()) {
            if (rt.timer().pending() > 0) { Duration d = rt.timer().nextDelay(); waits.add(d); rt.clock().advance(d); }
            Thread.onSpinWait();
            Thread.yield();
        }
        if (outcome.get() instanceof RuntimeException x) throw x;
        if (outcome.get() instanceof Error x) throw x;
        return (Envelope) outcome.get();
    }

    @Test
    void aDomesticInvoiceIsValidatedKeyedRoutedMappedAndSentToTheDomesticPartner() {
        Envelope out = pipeline.apply(ingress(invoice));
        assertEquals(5, out.step(), "validate, extractKey, route, xslt, to: the reply is step 5's envelope");
        assertEquals(Optional.of("4711"), out.key("invoiceId"), "the key the operator searches by");
        assertEquals(Optional.of("domestic"), out.get(PARTNER));
        assertEquals(ack.toString(), out.body().whole().toString(), "the partner's ack is the reply body");
        assertEquals("r-1", out.header("x-request-id").orElseThrow());
        List<Envelope> sent = http.sentTo("partner-domestic");
        assertEquals(1, sent.size());
        assertEquals(4, sent.get(0).step(), "the producer got the xslt step's output");
        String mapped = sent.get(0).body().whole().toString();
        assertTrue(mapped.contains("<PartnerInvoice number=\"4711\" currency=\"CZK\">") && mapped.contains("<Total>550.5</Total>"), mapped);
        assertEquals("application/xml", sent.get(0).header(Envelope.H_CONTENT_TYPE).orElseThrow());
        assertEquals(List.of(), http.sentTo("partner-foreign"));
        assertEquals(0, rt.timer().pending(), "no retry was armed");
    }

    @Test
    void aForeignInvoiceGoesToTheForeignPartner() {
        Envelope out = pipeline.apply(ingress(Buffer.buffer(invoice.toString().replace("<Currency>CZK</Currency>", "<Currency>EUR</Currency>"))));
        assertEquals(Optional.of("foreign"), out.get(PARTNER));
        assertEquals(1, http.sentTo("partner-foreign").size());
        assertEquals(List.of(), http.sentTo("partner-domestic"));
        assertTrue(http.sentTo("partner-foreign").get(0).body().whole().toString().contains("currency=\"EUR\""));
    }

    @Test
    void anInvoiceWithoutAnIdFailsAtTheValidateStepAndNothingIsSent() {
        XsdValidationException e = assertThrows(XsdValidationException.class,
                () -> pipeline.apply(ingress(Buffer.buffer(invoice.toString().replace("<Id>4711</Id>", "")))));
        assertEquals("validate", e.step());
        assertTrue(e.getMessage().contains("validation failed at step 'validate'") && e.getMessage().contains("Id"), e.getMessage());
        assertEquals(List.of(), http.sent());
    }

    @Test
    void aPartnerThatIsDownIsRetriedWithTheFlowsBackoffAndTheMessageSucceedsWhenItComesBack() {
        http.failNext(3, () -> new IllegalStateException("503 from partner"));
        List<Duration> waits = new ArrayList<>();
        Envelope out = run(ingress(invoice), waits);
        assertEquals(List.of(Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofMillis(400)), waits, "retry: attempts 3, initial 100ms, max 2s");
        assertEquals(4, http.sentTo("partner-domestic").size(), "one try and three retries, every one visible");
        assertEquals(ack.toString(), out.body().whole().toString());
        assertEquals(5, out.step());
    }

    @Test
    void aPartnerThatStaysDownFailsTheMessageAfterTheLastRetryWithEveryAttemptOnRecord() {
        http.failNext(4, () -> new IllegalStateException("503 from partner"));
        List<Duration> waits = new ArrayList<>();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> run(ingress(invoice), waits));
        assertEquals("503 from partner", e.getMessage());
        assertEquals(3, e.getSuppressed().length, "the three earlier failures ride along");
        assertEquals(3, waits.size());
        assertEquals(4, http.sentTo("partner-domestic").size());
        assertEquals(0, rt.timer().pending());
    }
}
