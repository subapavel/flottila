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

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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

    // FIX-6 (D-052): every stylesheet module read at build time resolves inside the flow's directory. The flow's
    // directory is <tmp>/flow; <tmp>/outside.xsl is a readable, valid module that would compile if it were read.

    static final String XSL = "<xsl:stylesheet xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\" version=\"3.0\">";

    /** {@code <dir>/flow} with the readable outside module one level up. */
    static Path flowWithOutside(Path dir) throws IOException {
        Files.writeString(dir.resolve("outside.xsl"), XSL + "<xsl:output method=\"text\"/>"
                + "<xsl:template name=\"outside\">outside:<xsl:value-of select=\"/Invoice/Id\"/></xsl:template>"
                + "<xsl:template match=\"/\"><xsl:call-template name=\"outside\"/></xsl:template></xsl:stylesheet>");
        return Files.createDirectories(dir.resolve("flow"));
    }

    /** Refused; the error names the step and {@code reference} as the stylesheet wrote it, not Saxon's absolute form. */
    void refused(StepConfig step, Path flow, String reference) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> factory.create(step, resources(flow, 65536)),
                "a stylesheet reaching outside the flow's directory must not compile");
        String m = e.getMessage();
        assertTrue(m.contains(step.toString()) && m.contains(reference + " leaves the flow's directory"),
                "the build error names the step and the offending reference as written (" + reference + "): " + m);
        assertEquals(0, factory.compiledStylesheets(), "a refused stylesheet is not cached");
    }

    @Test
    void aSiblingIncludeAndASubdirectoryImportCompileAndTheirTemplatesTransform(@TempDir Path dir) throws IOException {
        Path flow = flowWithOutside(dir);
        Files.createDirectory(flow.resolve("sub"));
        Files.writeString(flow.resolve("lib.xsl"), XSL + "<xsl:template name=\"lib\">lib:<xsl:value-of select=\"/Invoice/Id\"/></xsl:template></xsl:stylesheet>");
        Files.writeString(flow.resolve("sub/base.xsl"), XSL + "<xsl:include href=\"../common.xsl\"/>"
                + "<xsl:template name=\"base\">base:<xsl:value-of select=\"/Invoice/Currency\"/>|<xsl:call-template name=\"common\"/></xsl:template></xsl:stylesheet>");
        Files.writeString(flow.resolve("common.xsl"), XSL + "<xsl:template name=\"common\">common:<xsl:value-of select=\"count(//Line)\"/></xsl:template></xsl:stylesheet>");
        Files.writeString(flow.resolve("main.xsl"), XSL + "<xsl:import href=\"sub/base.xsl\"/><xsl:include href=\"lib.xsl\"/><xsl:output method=\"text\"/>"
                + "<xsl:template match=\"/\"><xsl:call-template name=\"lib\"/>|<xsl:call-template name=\"base\"/></xsl:template></xsl:stylesheet>");
        Stage stage = factory.create(step("main.xsl"), resources(flow, 65536));
        Envelope out = stage.apply(rt.ingress("f", Headers.empty(), Buffer.buffer(INVOICE)), e -> e);
        assertEquals("lib:4711|base:CZK|common:2", out.body().whole().toString(),
                "the sibling include's, the subdirectory import's and that module's own include (back up, still inside) templates ran");
    }

    @Test
    void anIncludeThatLeavesTheFlowsDirectoryIsABuildErrorNamingTheStepAndTheReference(@TempDir Path dir) throws IOException {
        Path flow = flowWithOutside(dir);
        Files.writeString(flow.resolve("up.xsl"), XSL + "<xsl:include href=\"../outside.xsl\"/></xsl:stylesheet>");
        refused(step("up.xsl"), flow, "../outside.xsl");
        Files.writeString(flow.resolve("absolute.xsl"), XSL + "<xsl:include href=\"" + dir.resolve("outside.xsl").toUri() + "\"/></xsl:stylesheet>");
        refused(step("absolute.xsl"), flow, dir.resolve("outside.xsl").toUri().toString());
        Files.createDirectory(flow.resolve("sub"));
        Files.writeString(flow.resolve("sub/deep.xsl"), XSL + "<xsl:include href=\"../../outside.xsl\"/></xsl:stylesheet>");
        Files.writeString(flow.resolve("nested.xsl"), XSL + "<xsl:include href=\"sub/deep.xsl\"/></xsl:stylesheet>");
        refused(step("nested.xsl"), flow, "../../outside.xsl");   // an included module's own include resolves against it, and is confined the same way
    }

    @Test
    void anImportThatLeavesTheFlowsDirectoryIsRefusedTheSameWay(@TempDir Path dir) throws IOException {
        Path flow = flowWithOutside(dir);
        Files.writeString(flow.resolve("up.xsl"), XSL + "<xsl:import href=\"../outside.xsl\"/></xsl:stylesheet>");
        refused(step("up.xsl"), flow, "../outside.xsl");
        Files.writeString(flow.resolve("absolute.xsl"), XSL + "<xsl:import href=\"" + dir.resolve("outside.xsl").toUri() + "\"/></xsl:stylesheet>");
        refused(step("absolute.xsl"), flow, dir.resolve("outside.xsl").toUri().toString());
    }

    @Test
    void aStylesheetTheFlowNamesOutsideItsDirectoryIsRefusedNamingTheStep(@TempDir Path dir) throws IOException {
        Path flow = flowWithOutside(dir);
        refused(step("../outside.xsl"), flow, "../outside.xsl");
        refused(step(dir.resolve("outside.xsl").toAbsolutePath().toString()), flow, dir.resolve("outside.xsl").toAbsolutePath().toString());
        refused(step("sub/../../outside.xsl"), flow, "sub/../../outside.xsl");
    }

    @Test
    void aRemoteIncludeIsRefusedAndNeverRequestedNamingTheStepAndTheUrl(@TempDir Path dir) throws IOException {
        Path flow = flowWithOutside(dir);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {   // a valid module: a fetch would compile
            requests.incrementAndGet();
            byte[] module = (XSL + "<xsl:template name=\"x\"/></xsl:stylesheet>").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, module.length);
            exchange.getResponseBody().write(module);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/x.xsl";
            Files.writeString(flow.resolve("remote.xsl"), XSL + "<xsl:include href=\"" + url + "\"/></xsl:stylesheet>");
            StepConfig step = step("remote.xsl");
            IllegalArgumentException remote = assertThrows(IllegalArgumentException.class, () -> factory.create(step, resources(flow, 65536)));
            String m = remote.getMessage();
            assertTrue(m.contains(step.toString()) && m.contains("does not compile") && m.contains(url),
                    "a module over the network is refused and the error names the step and the whole URL: " + m);
            assertEquals(0, factory.compiledStylesheets(), "a refused stylesheet is not cached");
            assertEquals(0, requests.get(), "the module over the network was never requested, not merely refused after the fetch");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aMissingIncludeInsideTheFlowsDirectoryIsABuildErrorNamingTheIncludeAndTheReason(@TempDir Path dir) throws IOException {
        Path flow = flowWithOutside(dir);
        Files.writeString(flow.resolve("main.xsl"), XSL + "<xsl:include href=\"missing.xsl\"/></xsl:stylesheet>");
        StepConfig step = step("main.xsl");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> factory.create(step, resources(flow, 65536)));
        String m = e.getMessage();
        assertTrue(m.contains(step.toString()) && m.contains("does not compile") && m.contains("missing.xsl"), "the error names the step and the include: " + m);
        assertTrue(m.contains("cannot be read"), "the reason is that the module cannot be read: " + m);
        assertTrue(!m.contains("leaves the flow's directory") && !m.contains("not a file"),
                "a module inside the directory is allowed; blaming the confinement misleads: " + m);
        assertEquals(0, factory.compiledStylesheets(), "a stylesheet that does not compile is not cached");
    }

    // FIX-6 (D-052): a stylesheet reads files at compile time beyond xsl:include / xsl:import — an external entity, a
    // static variable, a use-when condition. Each is a read at build time and stays inside the flow's directory.
    // <tmp>/outside.txt is readable; a build that succeeds shows in its transform what it read.

    static final String SECRET = "SECRET-OUTSIDE";

    /**
     * The stylesheet must not compile; if it does, the transform's output shows what was read outside. The error names
     * {@code reference}: as written where Saxon hands it over (an entity, {@code doc()}); its file name where Saxon hands
     * over only the absolute URI (a static {@code unparsed-text()}, {@code use-when}).
     */
    void refusedWithoutReading(StepConfig step, Path flow, String reference) {
        Stage stage;
        try {
            stage = factory.create(step, resources(flow, 65536));
        } catch (IllegalArgumentException e) {
            String m = e.getMessage();
            assertTrue(m.contains(step.toString()) && m.contains(reference), "the build error names the step and the file (" + reference + "): " + m);
            assertTrue(!m.contains(SECRET), "the build error carries nothing read from the file: " + m);
            assertEquals(0, factory.compiledStylesheets(), "a refused stylesheet is not cached");
            return;
        }
        String out = stage.apply(rt.ingress("f", Headers.empty(), Buffer.buffer(INVOICE)), e -> e).body().whole().toString();
        fail("the stylesheet compiled although it reads ../outside.txt at build time; its transform gives: " + out);
    }

    @Test
    void anExternalEntityOutsideTheFlowsDirectoryIsABuildErrorNamingTheStepAndTheFile(@TempDir Path dir) throws IOException {
        Path flow = flowWithOutside(dir);
        Files.writeString(flow.resolve("entity.xsl"), "<!DOCTYPE xsl:stylesheet [<!ENTITY e SYSTEM \"../outside.txt\">]>" + XSL
                + "<xsl:output method=\"text\"/><xsl:template match=\"/\">&e;</xsl:template></xsl:stylesheet>");
        Files.writeString(dir.resolve("outside.txt"), SECRET);
        refusedWithoutReading(step("entity.xsl"), flow, "../outside.txt");
    }

    @Test
    void aStaticVariableReadingTextOutsideTheFlowsDirectoryIsABuildErrorNamingTheStepAndTheFile(@TempDir Path dir) throws IOException {
        Path flow = flowWithOutside(dir);
        Files.writeString(flow.resolve("static.xsl"), XSL + "<xsl:variable name=\"x\" static=\"yes\" select=\"unparsed-text('../outside.txt')\"/>"
                + "<xsl:output method=\"text\"/><xsl:template match=\"/\"><xsl:value-of select=\"$x\"/></xsl:template></xsl:stylesheet>");
        Files.writeString(dir.resolve("outside.txt"), SECRET);
        refusedWithoutReading(step("static.xsl"), flow, "outside.txt");
    }

    @Test
    void aUseWhenProbingTextOutsideTheFlowsDirectoryIsABuildErrorNamingTheStepAndTheFile(@TempDir Path dir) throws IOException {
        Path flow = flowWithOutside(dir);
        Files.writeString(flow.resolve("usewhen.xsl"), XSL + "<xsl:output method=\"text\"/>"
                + "<xsl:template match=\"/\" use-when=\"unparsed-text-available('../outside.txt')\">outside.txt was read</xsl:template>"
                + "<xsl:template match=\"/\" use-when=\"not(unparsed-text-available('../outside.txt'))\">outside.txt was probed</xsl:template></xsl:stylesheet>");
        Files.writeString(dir.resolve("outside.txt"), SECRET);
        refusedWithoutReading(step("usewhen.xsl"), flow, "outside.txt");
    }

    @Test
    void aStaticVariableReadingADocumentOutsideTheFlowsDirectoryIsABuildErrorNamingTheStepAndTheFile(@TempDir Path dir) throws IOException {
        Path flow = flowWithOutside(dir);
        Files.writeString(dir.resolve("outside.txt"), "<secret>" + SECRET + "</secret>");
        Files.writeString(flow.resolve("staticdoc.xsl"), XSL + "<xsl:variable name=\"x\" static=\"yes\" select=\"string(doc('../outside.txt'))\"/>"
                + "<xsl:output method=\"text\"/><xsl:template match=\"/\"><xsl:value-of select=\"$x\"/></xsl:template></xsl:stylesheet>");
        refusedWithoutReading(step("staticdoc.xsl"), flow, "../outside.txt");
    }

    // FIX-6 (D-052): a static collection() / uri-collection() reads the flow's surroundings at compile time too.
    // <tmp> holds outside.txt (the secret), other.txt and outside.xsl; '..' from <tmp>/flow/x.xsl is <tmp>/.

    /** The stylesheet must not compile; if it does, the transform's output shows the listing of {@code <tmp>/}. */
    void refusedWithoutListing(StepConfig step, Path flow, Path dir) {
        Stage stage;
        try {
            stage = factory.create(step, resources(flow, 65536));
        } catch (IllegalArgumentException e) {
            String m = e.getMessage();
            // the reference as written ('..') or as Saxon hands it to a collection finder: <tmp>'s file: URI (not <tmp>/flow's)
            boolean namesReference = m.contains("..") || Pattern.compile("file:(//)?" + Pattern.quote(dir.toString()) + "/?(?![/\\w])").matcher(m).find();
            assertTrue(m.contains(step.toString()) && namesReference, "the build error names the step and the directory '..': " + m);
            assertFalse(m.contains("other.txt") || m.contains(SECRET), "the build error carries nothing listed or read: " + m);
            assertEquals(0, factory.compiledStylesheets(), "a refused stylesheet is not cached");
            return;
        }
        String out = stage.apply(rt.ingress("f", Headers.empty(), Buffer.buffer(INVOICE)), e -> e).body().whole().toString();
        fail("the stylesheet compiled although it lists '..' at build time; its transform gives: " + out);
    }

    /** Saxon cannot evaluate this at compile time: still a build error naming the step and the stylesheet, nothing read. */
    void aBuildErrorWithoutReading(StepConfig step, Path flow, String stylesheet) {
        Stage stage;
        try {
            stage = factory.create(step, resources(flow, 65536));
        } catch (RuntimeException e) {
            IllegalArgumentException build = assertInstanceOf(IllegalArgumentException.class, e,
                    "a stylesheet the flow cannot use is a build error naming the step, not an exception from inside Saxon");
            String m = build.getMessage();
            assertTrue(m.contains(step.toString()) && m.contains("does not compile") && m.contains(stylesheet),
                    "the build error names the step and the stylesheet: " + m);
            assertFalse(m.contains(SECRET), "the build error carries nothing read from outside: " + m);
            assertEquals(0, factory.compiledStylesheets(), "a stylesheet that does not compile is not cached");
            return;
        }
        String out = stage.apply(rt.ingress("f", Headers.empty(), Buffer.buffer(INVOICE)), e -> e).body().whole().toString();
        fail("the stylesheet compiled although it reads a collection outside the flow's directory at build time; its transform gives: " + out);
    }

    static Path flowWithSurroundings(Path dir) throws IOException {
        Path flow = flowWithOutside(dir);
        Files.writeString(dir.resolve("outside.txt"), SECRET);
        Files.writeString(dir.resolve("other.txt"), "OTHER");
        return flow;
    }

    @Test
    void aStaticVariableListingAUriCollectionOutsideTheFlowsDirectoryIsABuildErrorNamingTheStepAndTheReference(@TempDir Path dir) throws IOException {
        Path flow = flowWithSurroundings(dir);
        Files.writeString(flow.resolve("uris.xsl"), XSL + "<xsl:variable name=\"x\" static=\"yes\" select=\"string-join(uri-collection('..'), ' ')\"/>"
                + "<xsl:output method=\"text\"/><xsl:template match=\"/\"><xsl:value-of select=\"$x\"/></xsl:template></xsl:stylesheet>");
        refusedWithoutListing(step("uris.xsl"), flow, dir);
    }

    @Test
    void aUseWhenListingAUriCollectionOutsideTheFlowsDirectoryIsABuildErrorNamingTheStepAndTheReference(@TempDir Path dir) throws IOException {
        Path flow = flowWithSurroundings(dir);
        Files.writeString(flow.resolve("uriswhen.xsl"), XSL + "<xsl:output method=\"text\"/>"
                + "<xsl:template match=\"/\" use-when=\"exists(uri-collection('..')[ends-with(., 'outside.txt')])\">outside.txt was listed</xsl:template>"
                + "<xsl:template match=\"/\" use-when=\"not(exists(uri-collection('..')[ends-with(., 'outside.txt')]))\">not listed</xsl:template></xsl:stylesheet>");
        refusedWithoutListing(step("uriswhen.xsl"), flow, dir);
    }

    @Test
    void aStaticVariableReadingACollectionOutsideTheFlowsDirectoryIsABuildErrorNamingTheStepWithoutReading(@TempDir Path dir) throws IOException {
        Path flow = flowWithSurroundings(dir);
        Files.writeString(flow.resolve("coll.xsl"), XSL
                + "<xsl:variable name=\"x\" static=\"yes\" select=\"string-join(collection('..?select=outside.txt;unparsed=yes') ! string(.), ' ')\"/>"
                + "<xsl:output method=\"text\"/><xsl:template match=\"/\"><xsl:value-of select=\"$x\"/></xsl:template></xsl:stylesheet>");
        aBuildErrorWithoutReading(step("coll.xsl"), flow, "coll.xsl");
    }

    @Test
    void aUseWhenReadingACollectionOutsideTheFlowsDirectoryIsABuildErrorNamingTheStepWithoutReading(@TempDir Path dir) throws IOException {
        Path flow = flowWithSurroundings(dir);
        String read = "contains(string-join(collection('..?select=outside.txt;unparsed=yes') ! string(.)), '" + SECRET + "')";
        Files.writeString(flow.resolve("collwhen.xsl"), XSL + "<xsl:output method=\"text\"/>"
                + "<xsl:template match=\"/\" use-when=\"" + read + "\">outside.txt was read</xsl:template>"
                + "<xsl:template match=\"/\" use-when=\"not(" + read + ")\">not read</xsl:template></xsl:stylesheet>");
        aBuildErrorWithoutReading(step("collwhen.xsl"), flow, "collwhen.xsl");
    }

    @Test
    void ofTwoStaticReadsOutsideTheFlowsDirectoryTheFirstIsReported(@TempDir Path dir) throws IOException {
        Path flow = flowWithSurroundings(dir);
        // unparsed-text() fails its variable; unparsed-text-available() answers false and the compilation goes on to the next
        Files.writeString(flow.resolve("texts.xsl"), XSL + "<xsl:variable name=\"a\" static=\"yes\" select=\"unparsed-text('../outside.txt')\"/>"
                + "<xsl:variable name=\"b\" static=\"yes\" select=\"unparsed-text('../other.txt')\"/>"
                + "<xsl:output method=\"text\"/><xsl:template match=\"/\"><xsl:value-of select=\"$a, $b\"/></xsl:template></xsl:stylesheet>");
        Files.writeString(flow.resolve("probes.xsl"), XSL + "<xsl:variable name=\"a\" static=\"yes\" select=\"unparsed-text-available('../outside.txt')\"/>"
                + "<xsl:variable name=\"b\" static=\"yes\" select=\"unparsed-text-available('../other.txt')\"/>"
                + "<xsl:output method=\"text\"/><xsl:template match=\"/\"><xsl:value-of select=\"$a, $b\"/></xsl:template></xsl:stylesheet>");
        for (String stylesheet : new String[] {"texts.xsl", "probes.xsl"}) {
            StepConfig step = step(stylesheet);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> factory.create(step, resources(flow, 65536)), stylesheet);
            String m = e.getMessage();
            assertTrue(m.contains(step.toString()) && m.contains("outside.txt") && !m.contains("other.txt"),
                    stylesheet + ": the build error names the first read outside the directory (outside.txt), not a later one: " + m);
        }
        assertEquals(0, factory.compiledStylesheets(), "a refused stylesheet is not cached");
    }

    @Test
    void aStaticUriCollectionWithAQueryInsideTheFlowsDirectoryListsItsFiles(@TempDir Path dir) throws IOException {
        Path flow = flowWithSurroundings(dir);
        Files.createDirectory(flow.resolve("sub"));
        Files.writeString(flow.resolve("sub/base.xsl"), XSL + "</xsl:stylesheet>");
        Files.writeString(flow.resolve("sub/deep.xsl"), XSL + "</xsl:stylesheet>");
        Files.writeString(flow.resolve("sub/notes.txt"), "not selected");
        Files.writeString(flow.resolve("listing.xsl"), XSL
                + "<xsl:variable name=\"x\" static=\"yes\" select=\"string-join(uri-collection('sub?select=*.xsl') ! tokenize(., '/')[last()], ' ')\"/>"
                + "<xsl:output method=\"text\"/><xsl:template match=\"/\"><xsl:value-of select=\"$x\"/></xsl:template></xsl:stylesheet>");
        Stage stage = factory.create(step("listing.xsl"), resources(flow, 65536));
        String out = stage.apply(rt.ingress("f", Headers.empty(), Buffer.buffer(INVOICE)), e -> e).body().whole().toString();
        assertEquals(java.util.Set.of("base.xsl", "deep.xsl"), java.util.Set.of(out.split(" ")),
                "a directory collection inside the flow's directory, with Saxon's ?select= query, lists its matching files: " + out);
    }

    @Test
    void aStaticDefaultCollectionIsABuildErrorNamingTheStepNotANullPointer(@TempDir Path dir) throws IOException {
        Path flow = flowWithSurroundings(dir);
        Files.writeString(flow.resolve("default.xsl"), XSL + "<xsl:variable name=\"x\" static=\"yes\" select=\"count(collection())\"/>"
                + "<xsl:output method=\"text\"/><xsl:template match=\"/\"><xsl:value-of select=\"$x\"/></xsl:template></xsl:stylesheet>");
        StepConfig step = step("default.xsl");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> factory.create(step, resources(flow, 65536)));
        String m = e.getMessage();
        assertTrue(m.contains(step.toString()) && m.contains("does not compile") && m.contains("No default collection has been defined"),
                "the build error names the step and Saxon's reason (no default collection): " + m);
        assertFalse(m.contains("NullPointerException"), "the default collection's null URI is not reported as a null pointer: " + m);
        assertEquals(0, factory.compiledStylesheets(), "a stylesheet that does not compile is not cached");
    }
}
