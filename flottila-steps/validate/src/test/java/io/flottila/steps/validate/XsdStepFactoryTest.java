package io.flottila.steps.validate;

import io.flottila.api.BlobKey;
import io.flottila.api.BodyKind;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code xsd} step (P1-07): {@code schema} relative to the flow file, compiled at build time by content hash,
 * applied to the body's bytes on the JDK's validator; a valid message passes through untouched, an invalid one
 * fails naming the step and the schema's message. No network: a schema includes files next to it only, a
 * document's external DTD or schema hint is refused — each case with a file that would load if access were allowed.
 */
class XsdStepFactoryTest {

    static final Path DEMO_FLOWS = Path.of("../../packages/demo/flows");
    static final String INVOICE = "<Invoice><Id>4711</Id><Currency>CZK</Currency><Lines>"
            + "<Line><Sku>A-1</Sku><Qty>2</Qty><Price>100</Price></Line><Line><Sku>B-7</Sku><Qty>1</Qty><Price>350.5</Price></Line></Lines></Invoice>";

    final FakeRuntime rt = FakeRuntime.create();
    final XsdStepFactory factory = (XsdStepFactory) new XsdValidate().steps().get(0);

    StepResources resources(Path baseDir, long maxBodyBytes) {
        return new StepResources(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), maxBodyBytes, baseDir);
    }

    StepConfig step(String schema) { return new StepConfig(1, "xsd", "validate", false, Map.of("schema", schema)); }

    Envelope invoice(String xml) { return rt.ingress("invoice-sync", Headers.empty().with("x-request-id", "r1"), Buffer.buffer(xml)); }

    @Test
    void aValidMessagePassesThroughUntouchedAndAnInvalidOneFailsNamingTheStepAndTheReason() {
        assertEquals("xsd", factory.type());
        assertTrue(factory.configSchema().contains("\"schema\""));
        assertEquals(BodyKind.WHOLE, factory.bodyKind());
        Stage stage = factory.create(step("invoice.xsd"), resources(DEMO_FLOWS, 65536));
        Envelope in = invoice(INVOICE);
        assertSame(in, stage.apply(in, e -> e), "validation changes nothing: the same envelope goes on");
        Envelope noId = invoice(INVOICE.replace("<Id>4711</Id>", ""));
        XsdValidationException e = assertThrows(XsdValidationException.class, () -> stage.apply(noId, x -> x));
        assertEquals("validate", e.step());
        assertEquals(noId.id(), e.messageId());
        assertTrue(e.getMessage().startsWith("validation failed at step 'validate': ") && e.getMessage().contains("Id"), e.getMessage());
        Envelope badQty = invoice(INVOICE.replace("<Qty>2</Qty>", "<Qty>two</Qty>"));
        assertTrue(assertThrows(XsdValidationException.class, () -> stage.apply(badQty, x -> x)).getMessage().contains("two"));
    }

    @Test
    void anInvalidInvoiceIsAnInvalidFaultNamingTheStep() {
        Stage stage = factory.create(step("invoice.xsd"), resources(DEMO_FLOWS, 65536));
        Envelope noId = invoice(INVOICE.replace("<Id>4711</Id>", ""));
        XsdValidationException e = assertThrows(XsdValidationException.class, () -> stage.apply(noId, x -> x));
        Fault fault = assertInstanceOf(Fault.class, e, "the http component answers by kind without knowing this type");
        assertEquals(Fault.Kind.INVALID, fault.kind(), "the sender's input broke the schema: 422, not 500");
        assertEquals("validate", e.step());
        assertTrue(e.getMessage().contains("'validate'"), e.getMessage());
    }

    @Test
    void aBodyThatIsNotXmlIsAMalformedFault() {
        Stage stage = factory.create(step("invoice.xsd"), resources(DEMO_FLOWS, 65536));
        XsdValidationException e = assertThrows(XsdValidationException.class, () -> stage.apply(invoice("not xml"), x -> x));
        assertEquals(Fault.Kind.MALFORMED, assertInstanceOf(Fault.class, e).kind(),
                "a body that cannot be parsed is malformed input, 400, whichever step meets it first (D-049 a)");
        assertEquals("validate", e.step());
        Envelope noId = invoice(INVOICE.replace("<Id>4711</Id>", ""));
        assertEquals(Fault.Kind.INVALID, assertThrows(XsdValidationException.class, () -> stage.apply(noId, x -> x)).kind(),
                "well-formed but against the schema stays INVALID");
    }

    @Test
    void aBodyThatIsNotXmlOrReachesOutsideFailsTheMessageAndABodyOverTheTreeLimitIsRefusedBeforeParsing(@TempDir Path dir) throws IOException {
        Stage stage = factory.create(step("invoice.xsd"), resources(DEMO_FLOWS, 65536));
        Envelope notXml = invoice("<open>");
        XsdValidationException e = assertThrows(XsdValidationException.class, () -> stage.apply(notXml, x -> x));
        assertEquals(notXml.id(), e.messageId());
        Path dtd = dir.resolve("invoice.dtd");
        Files.writeString(dtd, "<!ELEMENT Invoice ANY>");   // readable, would load if access were allowed
        Envelope doctype = invoice("<!DOCTYPE Invoice SYSTEM \"" + dtd.toUri() + "\">" + INVOICE);
        XsdValidationException external = assertThrows(XsdValidationException.class, () -> stage.apply(doctype, x -> x));
        assertTrue(external.getMessage().contains("accessExternalDTD"), "the DTD is refused, not fetched: " + external.getMessage());
        Path other = dir.resolve("other.xsd");
        Files.writeString(other, "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"><xs:element name=\"Other\"/></xs:schema>");
        Envelope hint = invoice("<Other xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:noNamespaceSchemaLocation=\"" + other.toUri() + "\"/>");
        XsdValidationException hinted = assertThrows(XsdValidationException.class, () -> stage.apply(hint, x -> x));
        assertTrue(hinted.getMessage().contains("Other"), "a document's schema hint never replaces the configured schema: " + hinted.getMessage());
        FakeRuntime small = FakeRuntime.create().withTreeLimit(16);
        Stage tight = factory.create(step("invoice.xsd"), new StepResources(small.clock(), small.timer(), small.ids(), small.blobs(), small.hasher(), 65536, DEMO_FLOWS));
        Envelope big = Envelope.ingress("m-big", "f", Headers.empty(), small.blobs().write(BlobKey.body("m-big", 0), Buffer.buffer(INVOICE), 65536).await());
        assertThrows(BodyTooLargeException.class, () -> tight.apply(big, x -> x), "validation reads the whole body: under the tree limit only");
    }

    @Test
    void theSameSchemaContentCompilesOnceAndADifferentOneAgain(@TempDir Path dir) throws IOException {
        Files.copy(DEMO_FLOWS.resolve("invoice.xsd"), dir.resolve("copy.xsd"));
        Files.writeString(dir.resolve("any.xsd"), "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"><xs:element name=\"Any\"/></xs:schema>");
        factory.create(step("invoice.xsd"), resources(DEMO_FLOWS, 65536));
        Stage copy = factory.create(step("copy.xsd"), resources(dir, 65536));
        assertEquals(1, factory.compiledSchemas(), "same bytes, one compilation");
        Envelope viaCopy = invoice(INVOICE);
        assertSame(viaCopy, copy.apply(viaCopy, e -> e), "the cached schema validates");
        Stage any = factory.create(step("any.xsd"), resources(dir, 65536));
        assertEquals(2, factory.compiledSchemas());
        Envelope in = invoice("<Any/>");
        assertSame(in, any.apply(in, e -> e));
        assertThrows(XsdValidationException.class, () -> any.apply(invoice(INVOICE), e -> e), "the other schema knows no Invoice");
    }

    @Test
    void aMissingOrBrokenSchemaIsABuildErrorNamingTheStepAndTheFile(@TempDir Path dir) throws IOException {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () -> factory.create(step("nowhere.xsd"), resources(dir, 1024)));
        assertTrue(missing.getMessage().contains("validate") && missing.getMessage().contains("nowhere.xsd"), missing.getMessage());
        Files.writeString(dir.resolve("broken.xsd"), "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"><xs:element name=\"A\" type=\"xs:nothing\"/></xs:schema>");
        IllegalArgumentException broken = assertThrows(IllegalArgumentException.class, () -> factory.create(step("broken.xsd"), resources(dir, 1024)));
        assertTrue(broken.getMessage().contains("does not compile") && broken.getMessage().contains("broken.xsd"), broken.getMessage());
        Files.copy(DEMO_FLOWS.resolve("invoice.xsd"), dir.resolve("invoice.xsd"));
        Files.writeString(dir.resolve("wrapper.xsd"), "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"><xs:include schemaLocation=\"invoice.xsd\"/></xs:schema>");
        Stage wrapper = factory.create(step("wrapper.xsd"), resources(dir, 65536));
        Envelope in = invoice(INVOICE);
        assertSame(in, wrapper.apply(in, e -> e), "a schema next to the flow file includes its neighbours");
        Files.createDirectory(dir.resolve("adir.xsd"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> factory.create(step("adir.xsd"), resources(dir, 1024))).getMessage().contains("cannot be read"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> factory.create(new StepConfig(1, "xsd", "x", false, Map.of()), resources(dir, 1024)))
                .getMessage().contains("'schema'"));
    }

    // FIX-6 (D-052): every file the schema reaches at build time resolves inside the flow's directory. The flow's
    // directory is <tmp>/flow; <tmp>/outside.xsd and <tmp>/outside-ns.xsd are readable, valid schemas that would compile
    // if they were read.

    static final String XS = "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">";

    /** {@code <dir>/flow} with the readable outside schemas one level up. */
    static Path flowWithOutside(Path dir) throws IOException {
        Files.writeString(dir.resolve("outside.xsd"), XS + "<xs:element name=\"Outside\" type=\"xs:string\"/></xs:schema>");
        Files.writeString(dir.resolve("outside-ns.xsd"), "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" targetNamespace=\"urn:outside\">"
                + "<xs:element name=\"Outside\" type=\"xs:string\"/>"
                + "<xs:simpleType name=\"Code\"><xs:restriction base=\"xs:string\"><xs:length value=\"3\"/></xs:restriction></xs:simpleType></xs:schema>");
        return Files.createDirectories(dir.resolve("flow"));
    }

    void refused(StepConfig step, Path flow, String reference) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> factory.create(step, resources(flow, 65536)),
                "a schema reaching outside the flow's directory must not compile");
        String m = e.getMessage();
        assertTrue(m.contains(step.toString()) && m.contains(reference) && m.contains("leaves the flow's directory"),
                "the build error names the step and the offending reference: " + m);
        assertEquals(0, factory.compiledSchemas(), "a refused schema is not cached");
    }

    @Test
    void anIncludeThatLeavesTheFlowsDirectoryIsABuildErrorNamingTheStepAndTheReference(@TempDir Path dir) throws IOException {
        Path flow = flowWithOutside(dir);
        Files.writeString(flow.resolve("up.xsd"), XS + "<xs:include schemaLocation=\"../outside.xsd\"/><xs:element name=\"Doc\" type=\"xs:string\"/></xs:schema>");
        refused(step("up.xsd"), flow, "outside.xsd");
        Files.createDirectory(flow.resolve("sub"));
        Files.writeString(flow.resolve("sub/deep.xsd"), XS + "<xs:include schemaLocation=\"../../outside.xsd\"/></xs:schema>");
        Files.writeString(flow.resolve("nested.xsd"), XS + "<xs:include schemaLocation=\"sub/deep.xsd\"/><xs:element name=\"Doc\" type=\"xs:string\"/></xs:schema>");
        refused(step("nested.xsd"), flow, "outside.xsd");   // an included file's own include resolves against it, and is confined the same way
    }

    @Test
    void anAbsoluteFileUriIncludeAndAnImportOutsideTheFlowsDirectoryAreRefusedTheSameWay(@TempDir Path dir) throws IOException {
        Path flow = flowWithOutside(dir);
        Files.writeString(flow.resolve("absolute.xsd"), XS + "<xs:include schemaLocation=\"" + dir.resolve("outside.xsd").toUri()
                + "\"/><xs:element name=\"Doc\" type=\"xs:string\"/></xs:schema>");
        refused(step("absolute.xsd"), flow, "outside.xsd");
        Files.writeString(flow.resolve("import.xsd"), XS + "<xs:import namespace=\"urn:outside\" schemaLocation=\"../outside-ns.xsd\"/>"
                + "<xs:element name=\"Doc\" type=\"xs:string\"/></xs:schema>");
        refused(step("import.xsd"), flow, "outside-ns.xsd");
    }

    @Test
    void aSiblingAndASubdirectoryIncludeInsideTheFlowsDirectoryStillCompileAndValidate(@TempDir Path dir) throws IOException {
        Path flow = flowWithOutside(dir);
        Files.createDirectory(flow.resolve("sub"));
        Files.writeString(flow.resolve("common.xsd"), XS + "<xs:simpleType name=\"Qty\"><xs:restriction base=\"xs:int\"/></xs:simpleType></xs:schema>");
        Files.writeString(flow.resolve("sub/line.xsd"), XS + "<xs:include schemaLocation=\"../common.xsd\"/>"
                + "<xs:complexType name=\"Line\"><xs:sequence><xs:element name=\"Qty\" type=\"Qty\"/></xs:sequence></xs:complexType></xs:schema>");
        Files.writeString(flow.resolve("order.xsd"), XS + "<xs:include schemaLocation=\"common.xsd\"/><xs:include schemaLocation=\"sub/line.xsd\"/>"
                + "<xs:element name=\"Order\"><xs:complexType><xs:sequence><xs:element name=\"Count\" type=\"Qty\"/><xs:element name=\"Line\" type=\"Line\"/>"
                + "</xs:sequence></xs:complexType></xs:element></xs:schema>");
        Stage stage = factory.create(step("order.xsd"), resources(flow, 65536));
        Envelope valid = invoice("<Order><Count>2</Count><Line><Qty>3</Qty></Line></Order>");
        assertSame(valid, stage.apply(valid, e -> e), "a sibling include and an include in a subdirectory (which includes back up, still inside) compile");
        XsdValidationException e = assertThrows(XsdValidationException.class,
                () -> stage.apply(invoice("<Order><Count>2</Count><Line><Qty>three</Qty></Line></Order>"), x -> x));
        assertTrue(e.getMessage().contains("three"), "the type from the subdirectory's include applies: " + e.getMessage());
        assertThrows(XsdValidationException.class, () -> stage.apply(invoice("<Order><Count>two</Count><Line><Qty>3</Qty></Line></Order>"), x -> x),
                "the type from the sibling include applies");
    }

    @Test
    void aMissingIncludeInsideTheFlowsDirectoryIsABuildErrorNamingTheIncludeAndTheReason(@TempDir Path dir) throws IOException {
        Path flow = flowWithOutside(dir);
        Files.writeString(flow.resolve("main.xsd"), XS + "<xs:include schemaLocation=\"missing.xsd\"/><xs:element name=\"Doc\" type=\"xs:string\"/></xs:schema>");
        StepConfig step = step("main.xsd");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> factory.create(step, resources(flow, 65536)));
        String m = e.getMessage();
        assertTrue(m.contains(step.toString()) && m.contains("does not compile") && m.contains("missing.xsd"), "the error names the step and the include: " + m);
        assertTrue(m.contains("NoSuchFile") || m.contains("No such file"), "the reason is that the file does not exist: " + m);
        assertTrue(!m.contains("access is not allowed"), "a file inside the directory is allowed; blaming the access restriction misleads: " + m);
        assertEquals(0, factory.compiledSchemas(), "a schema that does not compile is not cached");
    }

    @Test
    void aRemoteImportIsRefusedAndNeverRequestedNamingTheStepAndTheUrl(@TempDir Path dir) throws IOException {
        Path flow = flowWithOutside(dir);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {   // a valid schema: a fetch would compile
            requests.incrementAndGet();
            byte[] schema = "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" targetNamespace=\"urn:x\"><xs:element name=\"X\"/></xs:schema>"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, schema.length);
            exchange.getResponseBody().write(schema);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/x.xsd";
            Files.writeString(flow.resolve("remote.xsd"), XS + "<xs:import namespace=\"urn:x\" schemaLocation=\"" + url + "\"/><xs:element name=\"A\"/></xs:schema>");
            StepConfig step = step("remote.xsd");
            IllegalArgumentException remote = assertThrows(IllegalArgumentException.class, () -> factory.create(step, resources(flow, 1024)));
            String m = remote.getMessage();
            assertTrue(m.contains(step.toString()) && m.contains("does not compile") && m.contains(url),
                    "a schema over the network is refused and the error names the step and the whole URL: " + m);
            assertEquals(0, factory.compiledSchemas(), "a refused schema is not cached");
            assertEquals(0, requests.get(), "the schema over the network was never requested, not merely refused after the fetch");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aNamespaceOnlyImportReadsNothingAndTheSchemaCompilesAndValidates(@TempDir Path dir) throws IOException {
        Path flow = flowWithOutside(dir);
        Files.writeString(flow.resolve("nsonly.xsd"), "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" xmlns:x=\"urn:x\">"
                + "<xs:import namespace=\"urn:x\"/><xs:element name=\"Doc\" type=\"xs:int\"/></xs:schema>");
        Stage stage = factory.create(step("nsonly.xsd"), resources(flow, 65536));
        assertEquals(1, factory.compiledSchemas(), "an import without a schemaLocation names no file: nothing to confine, the schema compiles");
        Envelope valid = invoice("<Doc>5</Doc>");
        assertSame(valid, stage.apply(valid, e -> e));
        XsdValidationException e = assertThrows(XsdValidationException.class, () -> stage.apply(invoice("<Doc>five</Doc>"), x -> x));
        assertTrue(e.getMessage().contains("five"), "the schema applies: " + e.getMessage());
    }

    @Test
    void anImportAloneThatLeavesTheFlowsDirectoryIsABuildErrorNamingTheStepAndTheReference(@TempDir Path dir) throws IOException {
        Path flow = flowWithOutside(dir);
        Files.writeString(flow.resolve("main.xsd"), "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" xmlns:o=\"urn:outside\">"
                + "<xs:import namespace=\"urn:outside\" schemaLocation=\"../outside-ns.xsd\"/><xs:element name=\"Doc\" type=\"o:Code\"/></xs:schema>");
        refused(step("main.xsd"), flow, "../outside-ns.xsd");
    }

    @Test
    void ofTwoReferencesThatLeaveTheFlowsDirectoryTheFirstIsReported(@TempDir Path dir) throws IOException {
        Path flow = flowWithOutside(dir);
        Files.writeString(flow.resolve("two.xsd"), XS + "<xs:import namespace=\"urn:outside\" schemaLocation=\"../outside-ns.xsd\"/>"
                + "<xs:import namespace=\"urn:other\" schemaLocation=\"/etc/x.xsd\"/><xs:element name=\"Doc\" type=\"xs:string\"/></xs:schema>");
        StepConfig step = step("two.xsd");
        refused(step, flow, "../outside-ns.xsd");
        String m = assertThrows(IllegalArgumentException.class, () -> factory.create(step, resources(flow, 65536))).getMessage();
        assertTrue(!m.contains("/etc/x.xsd"), "the build error names the first offending reference, not the second: " + m);
    }

    @Test
    void anExternalEntityOutsideTheFlowsDirectoryIsABuildErrorNamingTheStepAndTheFile(@TempDir Path dir) throws IOException {
        Path flow = flowWithOutside(dir);
        Files.writeString(dir.resolve("outside.txt"), "SECRET-OUTSIDE");
        Files.writeString(flow.resolve("entity.xsd"), "<!DOCTYPE xs:schema [<!ENTITY e SYSTEM \"../outside.txt\">]>" + XS
                + "<xs:annotation><xs:documentation>&e;</xs:documentation></xs:annotation><xs:element name=\"Doc\" type=\"xs:string\"/></xs:schema>");
        refused(step("entity.xsd"), flow, "outside.txt");
    }

    @Test
    void aSchemaTheFlowNamesOutsideItsDirectoryIsRefusedNamingTheStep(@TempDir Path dir) throws IOException {
        Path flow = flowWithOutside(dir);
        refused(step("../outside.xsd"), flow, "outside.xsd");
        refused(step(dir.resolve("outside.xsd").toAbsolutePath().toString()), flow, "outside.xsd");
        refused(step("sub/../../outside.xsd"), flow, "outside.xsd");
    }

    // --- P1-09d2a (D-063): every file the compile read reaches the StepResources reads sink (the flow's effective hash)

    @Test
    void anIncludedFileIsReported(@TempDir Path dir) throws IOException {
        Path flow = flowWithOutside(dir);
        Files.createDirectory(flow.resolve("sub"));
        Files.writeString(flow.resolve("common.xsd"), XS + "<xs:simpleType name=\"Qty\"><xs:restriction base=\"xs:int\"/></xs:simpleType></xs:schema>");
        Files.writeString(flow.resolve("sub/line.xsd"), XS + "<xs:include schemaLocation=\"../common.xsd\"/>"
                + "<xs:complexType name=\"Line\"><xs:sequence><xs:element name=\"Qty\" type=\"Qty\"/></xs:sequence></xs:complexType></xs:schema>");
        Files.writeString(flow.resolve("sub/codes.xsd"), "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" targetNamespace=\"urn:codes\">"
                + "<xs:simpleType name=\"Code\"><xs:restriction base=\"xs:string\"><xs:length value=\"3\"/></xs:restriction></xs:simpleType></xs:schema>");
        Files.writeString(flow.resolve("unused.xsd"), XS + "<xs:element name=\"Unused\" type=\"xs:string\"/></xs:schema>");
        Files.writeString(flow.resolve("order.xsd"), "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" xmlns:c=\"urn:codes\">"
                + "<xs:include schemaLocation=\"common.xsd\"/><xs:include schemaLocation=\"sub/line.xsd\"/>"
                + "<xs:import namespace=\"urn:codes\" schemaLocation=\"sub/codes.xsd\"/>"
                + "<xs:element name=\"Order\"><xs:complexType><xs:sequence><xs:element name=\"Count\" type=\"Qty\"/><xs:element name=\"Line\" type=\"Line\"/>"
                + "<xs:element name=\"Code\" type=\"c:Code\"/></xs:sequence></xs:complexType></xs:element></xs:schema>");
        List<Path> reads = new ArrayList<>();
        StepResources resources = new StepResources(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), 65536, flow, reads::add);

        Stage stage = factory.create(step("order.xsd"), resources);

        Envelope valid = invoice("<Order><Count>2</Count><Line><Qty>3</Qty></Line><Code>ABC</Code></Order>");
        assertSame(valid, stage.apply(valid, e -> e), "fixture: the schema and everything it includes and imports compiled");
        assertThrows(XsdValidationException.class, () -> stage.apply(invoice("<Order><Count>2</Count><Line><Qty>3</Qty></Line><Code>ABCD</Code></Order>"), x -> x),
                "fixture: the imported type applies");
        Path root = flow.toAbsolutePath().normalize();
        Set<Path> expected = Set.of(root.resolve("order.xsd"), root.resolve("common.xsd"), root.resolve("sub/line.xsd"), root.resolve("sub/codes.xsd"));
        assertEquals(expected, new HashSet<>(reads),
                "the schema the flow names, its xs:includes, the include's own include and its xs:import are reported, as absolute paths in the"
                        + " flow's directory; the unused schema beside them is not: " + reads);
    }
}
