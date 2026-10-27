package io.flottila.steps.validate;

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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-17: the xsd compile cache serves an entry only to a flow whose directory holds the same files, with the same contents,
 * at the same places relative to it, as the compile that made the entry read — two flows whose main schema has the same text
 * but whose includes differ (or are missing, or are refused by the D-052 confinement for the second flow) each compile on their
 * own; a hit reports to the requesting flow's {@code reads} sink every file the entry read, in the requesting flow's directory,
 * exactly as a fresh compile there would (D-063 effective hash). A hit is observed through {@link XsdStepFactory#compiledSchemas()}:
 * the schemas compiled and kept so far.
 */
class XsdCompileCacheTest {

    static final String XS = XsdStepFactoryTest.XS;
    /** Includes {@code sub/types.xsd} and declares {@code Order} of its type {@code Code}. */
    static final String MAIN = XS + "<xs:include schemaLocation=\"sub/types.xsd\"/><xs:element name=\"Order\" type=\"Code\"/></xs:schema>";

    final FakeRuntime rt = FakeRuntime.create();
    final XsdStepFactory factory = (XsdStepFactory) new XsdValidate().steps().get(0);

    StepResources resources(Path flow, Set<Path> reads) {
        return new StepResources(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), 65536, flow, reads::add);
    }

    StepResources resources(Path flow) {
        return resources(flow, new HashSet<>());
    }

    static StepConfig step(String schema) { return new StepConfig(1, "xsd", "check", false, Map.of("schema", schema)); }

    /** A schema whose type {@code Code} is a string of exactly {@code length} characters. */
    static String types(int length) {
        return XS + "<xs:simpleType name=\"Code\"><xs:restriction base=\"xs:string\"><xs:length value=\"" + length + "\"/></xs:restriction></xs:simpleType></xs:schema>";
    }

    static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /** {@code <dir>/flow} with {@code main.xsd} = {@link #MAIN} and {@code sub/types.xsd} = {@link #types}{@code (length)}. */
    static Path flow(Path dir, int length) throws IOException {
        Path flow = dir.resolve("flow");
        write(flow.resolve("main.xsd"), MAIN);
        write(flow.resolve("sub/types.xsd"), types(length));
        return flow;
    }

    Envelope order(String code) { return rt.ingress("f", Headers.empty(), Buffer.buffer("<Order>" + code + "</Order>")); }

    /** Whether {@code stage} lets {@code <Order>code</Order>} through; any other failure than a validation one propagates. */
    boolean accepts(Stage stage, String code) {
        Envelope in = order(code);
        try {
            assertSame(in, stage.apply(in, e -> e));
            return true;
        } catch (XsdValidationException x) {
            return false;
        }
    }

    @Test
    void twoFlowsWithTheSameMainAndDifferentIncludesEachSeeTheirOwn(@TempDir Path a, @TempDir Path b) throws IOException {
        Stage first = factory.create(step("main.xsd"), resources(flow(a, 3)));
        Stage second = factory.create(step("main.xsd"), resources(flow(b, 4)));

        assertTrue(accepts(first, "ABC") && !accepts(first, "ABCD"), "fixture: the first flow validates against its own include (length 3)");
        assertTrue(accepts(second, "ABCD") && !accepts(second, "ABC"),
                "the second flow's main.xsd has the first's text but its sub/types.xsd differs (length 4): it validates against its own include,"
                        + " not the first flow's compiled copy");
        assertEquals(2, factory.compiledSchemas(), "the same main file over different includes: two compilations");
    }

    @Test
    void theSameMainAtAnotherPathInTheFlowSeesTheIncludeNextToIt(@TempDir Path a, @TempDir Path b) throws IOException {
        Path first = flow(a, 3);
        Path second = b.resolve("flow");
        write(second.resolve("deep/main.xsd"), MAIN);
        write(second.resolve("deep/sub/types.xsd"), types(4));
        write(second.resolve("sub/types.xsd"), types(3));   // the first flow's include at the first flow's flow-relative path: never read here

        assertTrue(accepts(factory.create(step("main.xsd"), resources(first)), "ABC"), "fixture");
        Stage deep = factory.create(step("deep/main.xsd"), resources(second));
        assertTrue(accepts(deep, "ABCD") && !accepts(deep, "ABC"),
                "main.xsd's include resolves next to main.xsd: deep/sub/types.xsd, whatever sits at sub/types.xsd");
    }

    @Test
    void aMissingIncludeInTheSecondFlowFailsItsBuild(@TempDir Path a, @TempDir Path b) throws IOException {
        factory.create(step("main.xsd"), resources(flow(a, 3)));
        Path second = b.resolve("flow");
        write(second.resolve("main.xsd"), MAIN);
        StepConfig step = step("main.xsd");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> factory.create(step, resources(second)),
                "the second flow has the first's main.xsd but no sub/types.xsd: its build fails, it does not get the first flow's compiled copy");
        assertTrue(e.getMessage().contains(step.toString()) && e.getMessage().contains("does not compile") && e.getMessage().contains("types.xsd"),
                e.getMessage());
        assertEquals(1, factory.compiledSchemas(), "the failed build is not cached");
    }

    @Test
    void aConfinementRefusalIsNotServedFromTheCache(@TempDir Path a, @TempDir Path b) throws IOException {
        Path first = a.resolve("flow");
        String reference = first.toAbsolutePath().normalize().resolve("sub/types.xsd").toUri().toString();
        String absolute = XS + "<xs:include schemaLocation=\"" + reference + "\"/><xs:element name=\"Order\" type=\"Code\"/></xs:schema>";
        write(first.resolve("main.xsd"), absolute);
        write(first.resolve("sub/types.xsd"), types(3));
        Path second = b.resolve("flow");
        write(second.resolve("main.xsd"), absolute);
        write(second.resolve("sub/types.xsd"), types(3));   // its own copy: only the confinement tells the two apart
        assertTrue(accepts(factory.create(step("main.xsd"), resources(first)), "ABC"),
                "fixture: the absolute file: include lies inside the first flow's directory and compiles there");
        StepConfig step = step("main.xsd");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> factory.create(step, resources(second)),
                "the same text in another flow includes a file in the first flow's directory: refused (D-052), not served from the cache");
        assertTrue(e.getMessage().contains(step.toString()) && e.getMessage().contains("types.xsd") && e.getMessage().contains("leaves the flow's directory"),
                "the build error names the step and the reference: " + e.getMessage());
        assertEquals(1, factory.compiledSchemas(), "the refused build is not cached");
    }

    /**
     * {@code <dir>/flow/order.xsd} including {@code common.xsd} and {@code sub/line.xsd} (which includes {@code ../common.xsd})
     * and importing {@code sub/codes.xsd}; {@code unused.xsd} lies beside them.
     */
    static Path rich(Path dir) throws IOException {
        Path flow = dir.resolve("flow");
        write(flow.resolve("common.xsd"), XS + "<xs:simpleType name=\"Qty\"><xs:restriction base=\"xs:int\"/></xs:simpleType></xs:schema>");
        write(flow.resolve("sub/line.xsd"), XS + "<xs:include schemaLocation=\"../common.xsd\"/>"
                + "<xs:complexType name=\"Line\"><xs:sequence><xs:element name=\"Qty\" type=\"Qty\"/></xs:sequence></xs:complexType></xs:schema>");
        write(flow.resolve("sub/codes.xsd"), "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" targetNamespace=\"urn:codes\">"
                + "<xs:simpleType name=\"Code\"><xs:restriction base=\"xs:string\"><xs:length value=\"3\"/></xs:restriction></xs:simpleType></xs:schema>");
        write(flow.resolve("unused.xsd"), XS + "<xs:element name=\"Unused\" type=\"xs:string\"/></xs:schema>");
        write(flow.resolve("order.xsd"), "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" xmlns:c=\"urn:codes\">"
                + "<xs:include schemaLocation=\"common.xsd\"/><xs:include schemaLocation=\"sub/line.xsd\"/>"
                + "<xs:import namespace=\"urn:codes\" schemaLocation=\"sub/codes.xsd\"/>"
                + "<xs:element name=\"Order\"><xs:complexType><xs:sequence><xs:element name=\"Count\" type=\"Qty\"/><xs:element name=\"Line\" type=\"Line\"/>"
                + "<xs:element name=\"Code\" type=\"c:Code\"/></xs:sequence></xs:complexType></xs:element></xs:schema>");
        return flow;
    }

    @Test
    void aCacheHitReportsTheSameReadsAsACompile(@TempDir Path a, @TempDir Path b) throws IOException {
        Path second = rich(b);
        Set<Path> fresh = new HashSet<>();
        ((XsdStepFactory) new XsdValidate().steps().get(0)).create(step("order.xsd"), resources(second, fresh));
        Path root = second.toAbsolutePath().normalize();
        assertEquals(Set.of(root.resolve("order.xsd"), root.resolve("common.xsd"), root.resolve("sub/line.xsd"), root.resolve("sub/codes.xsd")), fresh,
                "fixture: a fresh compile in the second flow's directory reports what it read there");

        factory.create(step("order.xsd"), resources(rich(a)));
        Set<Path> hit = new HashSet<>();
        Stage served = factory.create(step("order.xsd"), resources(second, hit));

        assertEquals(1, factory.compiledSchemas(), "fixture: identical files in another directory, the second build is a cache hit");
        Envelope valid = rt.ingress("f", Headers.empty(), Buffer.buffer("<Order><Count>2</Count><Line><Qty>3</Qty></Line><Code>ABC</Code></Order>"));
        assertSame(valid, served.apply(valid, e -> e));
        assertEquals(fresh, hit, "a cache hit reports to the requesting flow's sink every file the compile read, as paths in the requesting"
                + " flow's directory, the same as a fresh compile there");
    }

    @Test
    void aCacheHitStillHappensForIdenticalFilesInAnotherDirectory(@TempDir Path a, @TempDir Path b) throws IOException {
        Stage first = factory.create(step("main.xsd"), resources(flow(a, 3)));
        Stage second = factory.create(step("main.xsd"), resources(flow(b, 3)));

        assertEquals(1, factory.compiledSchemas(), "the same files with the same contents in another flow's directory compile once");
        assertTrue(accepts(first, "ABC") && !accepts(first, "ABCD"));
        assertTrue(accepts(second, "ABC") && !accepts(second, "ABCD"));
    }

    @Test
    void theMainFileNameIsNotPartOfTheKey(@TempDir Path a, @TempDir Path b) throws IOException {
        Stage first = factory.create(step("main.xsd"), resources(flow(a, 3)));
        Path second = b.resolve("flow");
        write(second.resolve("other.xsd"), MAIN);
        write(second.resolve("sub/types.xsd"), types(3));

        Stage other = factory.create(step("other.xsd"), resources(second));

        assertEquals(1, factory.compiledSchemas(), "the same main content under another file name, over the same include: one compilation");
        assertTrue(accepts(first, "ABC") && !accepts(first, "ABCD"));
        assertTrue(accepts(other, "ABC") && !accepts(other, "ABCD"));
    }

    @Test
    void aClimbingIncludeOutsideTheSecondFlowIsRefusedNotServed(@TempDir Path root) throws IOException {
        String climbing = XS + "<xs:include schemaLocation=\"../flows/t.xsd\"/><xs:element name=\"Order\" type=\"Code\"/></xs:schema>";
        Path first = root.resolve("x/flows");
        write(first.resolve("main.xsd"), climbing);
        write(first.resolve("t.xsd"), types(3));
        Path second = root.resolve("y/other");
        write(second.resolve("main.xsd"), climbing);
        write(second.resolve("t.xsd"), types(3));   // the same bytes where the first flow's read lies relative to its main file
        write(root.resolve("y/flows/t.xsd"), types(3));   // and where the second flow's own compile would read it: outside its directory
        assertTrue(accepts(factory.create(step("main.xsd"), resources(first)), "ABC"), "fixture: ../flows/t.xsd climbs out and back into the first flow's directory");
        StepConfig step = step("main.xsd");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> factory.create(step, resources(second)),
                "in the second flow ../flows/t.xsd leaves its directory (other/): refused (D-052), not served from the cache");
        assertTrue(e.getMessage().contains(step.toString()) && e.getMessage().contains("t.xsd") && e.getMessage().contains("leaves the flow's directory"),
                e.getMessage());
        assertEquals(1, factory.compiledSchemas(), "the refused build is not cached");
    }

    @Test
    void aClimbingIncludeIsCheckedWhereTheSecondFlowReadsIt(@TempDir Path a, @TempDir Path b) throws IOException {
        String climbing = XS + "<xs:include schemaLocation=\"../xsd/inc.xsd\"/><xs:element name=\"Order\" type=\"Code\"/></xs:schema>";
        write(a.resolve("xsd/main.xsd"), climbing);
        write(a.resolve("xsd/inc.xsd"), types(3));
        write(b.resolve("templates/main.xsd"), climbing);
        write(b.resolve("templates/inc.xsd"), types(3));   // the same bytes next to the main file, where the first flow's read lay
        write(b.resolve("xsd/inc.xsd"), types(4));   // where the second flow's own compile reads ../xsd/inc.xsd
        assertTrue(accepts(factory.create(step("xsd/main.xsd"), resources(a)), "ABC"), "fixture");
        Set<Path> reads = new HashSet<>();

        Stage second = factory.create(step("templates/main.xsd"), resources(b, reads));

        assertTrue(accepts(second, "ABCD") && !accepts(second, "ABC"),
                "the second flow's main.xsd includes ../xsd/inc.xsd = xsd/inc.xsd in its directory (length 4): its own schema applies");
        assertEquals(2, factory.compiledSchemas(), "compiled fresh, not served from the first flow's entry");
        Path root = b.toAbsolutePath().normalize();
        assertEquals(Set.of(root.resolve("templates/main.xsd"), root.resolve("xsd/inc.xsd")), reads, "the reads report xsd/inc.xsd, what the compile read");
    }
}
