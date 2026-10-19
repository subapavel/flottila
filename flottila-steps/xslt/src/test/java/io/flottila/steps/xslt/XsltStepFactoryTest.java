package io.flottila.steps.xslt;

import io.flottila.api.BlobKey;
import io.flottila.api.BodyTooLargeException;
import io.flottila.api.Envelope;
import io.flottila.api.Fault;
import io.flottila.api.Headers;
import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.api.StepResources;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The xslt step over the demo package's stylesheet: compiled once by content hash, applied to the memoized document view. */
class XsltStepFactoryTest {

    static final Path DEMO_FLOWS = Path.of("../../packages/demo/flows");
    static final String INVOICE = "<Invoice><Id>4711</Id><Currency>CZK</Currency><Lines>"
            + "<Line><Sku>A-1</Sku><Qty>2</Qty><Price>100</Price></Line><Line><Sku>B-7</Sku><Qty>1</Qty><Price>350.5</Price></Line></Lines></Invoice>";

    final FakeRuntime rt = FakeRuntime.create();
    final SaxonXml xml = new SaxonXml();
    final XsltStepFactory factory = (XsltStepFactory) xml.steps().get(0);

    StepResources resources(Path baseDir, long maxBodyBytes) {
        return new StepResources(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), maxBodyBytes, baseDir);
    }

    StepConfig step(String stylesheet) { return new StepConfig(2, "xslt", "toPartner", false, Map.of("stylesheet", stylesheet)); }

    @Test
    void transformsTheBodyIntoTheNextStepsBodyWithTheOutputMethodsContentType() {
        assertEquals("xslt", factory.type());
        assertTrue(factory.configSchema().contains("\"stylesheet\""));
        Stage stage = factory.create(step("invoice-to-partner.xsl"), resources(DEMO_FLOWS, 65536));
        Envelope in = rt.ingress("invoice-sync", Headers.empty().with("x-request-id", "r1"), Buffer.buffer(INVOICE)).advance();
        Envelope out = stage.apply(in, e -> e);
        assertEquals(2, out.step(), "a new body: the step's own number");
        assertEquals(in.id(), out.id());
        assertEquals("r1", out.header("x-request-id").orElseThrow(), "headers carried over");
        assertEquals("application/xml", out.header(Envelope.H_CONTENT_TYPE).orElseThrow());
        String result = out.body().whole().toString();
        assertTrue(result.contains("<PartnerInvoice number=\"4711\" currency=\"CZK\">"), result);
        assertTrue(result.contains("<Total>550.5</Total>"), result);
        assertTrue(result.contains("<Item sku=\"B-7\" amount=\"350.5\"/>"), result);
        assertEquals(2, out.body().key().step());
        assertSame(xml.document(in), xml.document(in), "the document view is parsed once per body");
    }

    @Test
    void theSameStylesheetContentCompilesOnceAndADifferentOneAgain(@TempDir Path dir) throws IOException {
        Files.copy(DEMO_FLOWS.resolve("invoice-to-partner.xsl"), dir.resolve("copy.xsl"));
        Files.writeString(dir.resolve("text.xsl"), "<xsl:stylesheet xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\" version=\"3.0\">"
                + "<xsl:output method=\"text\"/><xsl:template match=\"/\"><xsl:value-of select=\"/Invoice/Id\"/></xsl:template></xsl:stylesheet>");
        factory.create(step("invoice-to-partner.xsl"), resources(DEMO_FLOWS, 65536));
        factory.create(step("copy.xsl"), resources(dir, 65536));
        assertEquals(1, factory.compiledStylesheets(), "same bytes, one compilation");
        Stage text = factory.create(step("text.xsl"), resources(dir, 65536));
        assertEquals(2, factory.compiledStylesheets());
        Envelope out = text.apply(rt.ingress("f", Headers.empty(), Buffer.buffer(INVOICE)), e -> e);
        assertEquals("4711", out.body().whole().toString());
        assertEquals("text/plain", out.header(Envelope.H_CONTENT_TYPE).orElseThrow());
        assertEquals("application/json", XsltStepFactory.contentType("json"));
        assertEquals("text/html", XsltStepFactory.contentType("html"));
        assertEquals("application/xhtml+xml", XsltStepFactory.contentType("xhtml"));
        assertEquals("application/xml", XsltStepFactory.contentType("xml"));
        assertEquals("application/xml", XsltStepFactory.contentType(null));
    }

    @Test
    void aMissingOrBrokenStylesheetIsABuildErrorNamingTheStepAndTheFile(@TempDir Path dir) throws IOException {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () -> factory.create(step("nowhere.xsl"), resources(dir, 1024)));
        assertTrue(missing.getMessage().contains("toPartner") && missing.getMessage().contains("nowhere.xsl"), missing.getMessage());
        Files.writeString(dir.resolve("broken.xsl"), "<xsl:stylesheet xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\" version=\"3.0\"><xsl:template match=\"/\"><xsl:value-of select=\"1 +\"/></xsl:template></xsl:stylesheet>");
        IllegalArgumentException broken = assertThrows(IllegalArgumentException.class, () -> factory.create(step("broken.xsl"), resources(dir, 1024)));
        assertTrue(broken.getMessage().contains("does not compile") && broken.getMessage().contains("broken.xsl"), broken.getMessage());
        Files.createDirectory(dir.resolve("adir.xsl"));
        IllegalArgumentException unreadable = assertThrows(IllegalArgumentException.class, () -> factory.create(step("adir.xsl"), resources(dir, 1024)));
        assertTrue(unreadable.getMessage().contains("cannot be read"), unreadable.getMessage());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> factory.create(new StepConfig(1, "xslt", "x", false, Map.of()), resources(dir, 1024)))
                .getMessage().contains("'stylesheet'"));
    }

    @Test
    void aBodyThatIsNotXmlAndAResultOverTheFlowsLimitAreMessageFailures() {
        Stage stage = factory.create(step("invoice-to-partner.xsl"), resources(DEMO_FLOWS, 65536));
        Envelope notXml = rt.ingress("f", Headers.empty(), Buffer.buffer("{\"not\": \"xml\"}"));
        XmlException e = assertThrows(XmlException.class, () -> stage.apply(notXml, x -> x));
        assertEquals(notXml.id(), e.messageId());
        assertTrue(e.getMessage().contains("not well-formed"), e.getMessage());
        Stage tight = factory.create(step("invoice-to-partner.xsl"), resources(DEMO_FLOWS, 64));
        assertThrows(BodyTooLargeException.class, () -> tight.apply(rt.ingress("f", Headers.empty(), Buffer.buffer(INVOICE)), x -> x),
                "the transformed body is written under the flow's limit like any other");
    }

    @Test
    void aBodyAboveTheTreeLimitHasNoDocumentViewAndFailsBeforeAnyParsing() {
        FakeRuntime small = FakeRuntime.create().withTreeLimit(16);
        SaxonXml xml = new SaxonXml();
        Stage stage = ((XsltStepFactory) xml.steps().get(0)).create(step("invoice-to-partner.xsl"),
                new StepResources(small.clock(), small.timer(), small.ids(), small.blobs(), small.hasher(), 65536, DEMO_FLOWS));
        // the fake ingress refuses such a body at the door; a store writes it under the flow's limit regardless of the tree limit
        Envelope big = Envelope.ingress("m-big", "f", Headers.empty(), small.blobs().write(BlobKey.body("m-big", 0), Buffer.buffer(INVOICE), 65536).await());
        assertThrows(BodyTooLargeException.class, () -> stage.apply(big, x -> x), "a tree view exists under the tree limit only; streaming is not this step's");
        assertThrows(BodyTooLargeException.class, () -> xml.document(big));
    }

    @Test
    void aDynamicErrorInTheTransformationFailsTheMessage(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("err.xsl"), "<xsl:stylesheet xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\" version=\"3.0\">"
                + "<xsl:template match=\"/\"><xsl:sequence select=\"error(xs:QName('err:FLOT'), 'boom')\" xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" xmlns:err=\"urn:x\"/></xsl:template></xsl:stylesheet>");
        Stage stage = factory.create(step("err.xsl"), resources(dir, 1024));
        Envelope in = rt.ingress("f", Headers.empty(), Buffer.buffer(INVOICE));
        XmlException e = assertThrows(XmlException.class, () -> stage.apply(in, x -> x));
        assertTrue(e.getMessage().contains("boom") && e.getMessage().contains("toPartner"), e.getMessage());
    }

    @Test
    void aBodyThatIsNotXmlIsMalformedAndADynamicErrorIsInternal(@TempDir Path dir) throws IOException {
        Stage stage = factory.create(step("invoice-to-partner.xsl"), resources(DEMO_FLOWS, 65536));
        XmlException unreadable = assertThrows(XmlException.class, () -> stage.apply(rt.ingress("f", Headers.empty(), Buffer.buffer("not xml")), x -> x));
        assertEquals(Fault.Kind.MALFORMED, assertInstanceOf(Fault.class, unreadable).kind(), "the input could not be parsed: the sender's fault, 400");
        Files.writeString(dir.resolve("err.xsl"), "<xsl:stylesheet xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\" version=\"3.0\">"
                + "<xsl:template match=\"/\"><xsl:sequence select=\"error(xs:QName('err:FLOT'), 'boom')\" xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" xmlns:err=\"urn:x\"/></xsl:template></xsl:stylesheet>");
        Stage failing = factory.create(step("err.xsl"), resources(dir, 1024));
        XmlException dynamic = assertThrows(XmlException.class, () -> failing.apply(rt.ingress("f", Headers.empty(), Buffer.buffer(INVOICE)), x -> x));
        assertEquals(Fault.Kind.INTERNAL, dynamic.kind(), "a well-formed input the stylesheet fails on is the flow's failure, 500");
    }
}
