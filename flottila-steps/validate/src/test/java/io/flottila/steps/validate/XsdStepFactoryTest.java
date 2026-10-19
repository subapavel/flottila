package io.flottila.steps.validate;

import io.flottila.api.BlobKey;
import io.flottila.api.BodyKind;
import io.flottila.api.BodyTooLargeException;
import io.flottila.api.Envelope;
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
    void aMissingBrokenOrOutwardReachingSchemaIsABuildErrorNamingTheStepAndTheFile(@TempDir Path dir) throws IOException {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () -> factory.create(step("nowhere.xsd"), resources(dir, 1024)));
        assertTrue(missing.getMessage().contains("validate") && missing.getMessage().contains("nowhere.xsd"), missing.getMessage());
        Files.writeString(dir.resolve("broken.xsd"), "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"><xs:element name=\"A\" type=\"xs:nothing\"/></xs:schema>");
        IllegalArgumentException broken = assertThrows(IllegalArgumentException.class, () -> factory.create(step("broken.xsd"), resources(dir, 1024)));
        assertTrue(broken.getMessage().contains("does not compile") && broken.getMessage().contains("broken.xsd"), broken.getMessage());
        Files.writeString(dir.resolve("remote.xsd"), "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">"
                + "<xs:import namespace=\"urn:x\" schemaLocation=\"http://example.invalid/x.xsd\"/><xs:element name=\"A\"/></xs:schema>");
        IllegalArgumentException remote = assertThrows(IllegalArgumentException.class, () -> factory.create(step("remote.xsd"), resources(dir, 1024)));
        assertTrue(remote.getMessage().contains("does not compile") && remote.getMessage().contains("accessExternalSchema"), "a schema over the network is refused, not fetched: " + remote.getMessage());
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
}
