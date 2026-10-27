package io.flottila.steps.xslt;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-17: the xslt compile cache serves an entry only to a flow whose directory holds the same files, with the same contents,
 * at the same places relative to it, as the compile that made the entry read — two flows whose main stylesheet has the same
 * text but whose includes differ (or are missing, or are refused by the D-052 confinement for the second flow) each compile
 * on their own; a hit reports to the requesting flow's {@code reads} sink every file the entry read, in the requesting flow's
 * directory, exactly as a fresh compile there would (D-063 effective hash). A hit is observed through
 * {@link XsltStepFactory#compiledStylesheets()}: the stylesheets compiled and kept so far.
 */
class XsltCompileCacheTest {

    static final String XSL = XsltStepFactoryTest.XSL;
    static final String INVOICE = XsltStepFactoryTest.INVOICE;
    /** Includes {@code sub/x.xsl} and outputs what its template {@code x} writes. */
    static final String MAIN = XSL + "<xsl:include href=\"sub/x.xsl\"/><xsl:output method=\"text\"/>"
            + "<xsl:template match=\"/\"><xsl:call-template name=\"x\"/></xsl:template></xsl:stylesheet>";

    final FakeRuntime rt = FakeRuntime.create();
    final XsltStepFactory factory = (XsltStepFactory) new SaxonXml().steps().get(0);

    StepResources resources(Path flow, Set<Path> reads) {
        return new StepResources(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), 65536, flow, reads::add);
    }

    StepResources resources(Path flow) {
        return resources(flow, new HashSet<>());
    }

    static StepConfig step(String stylesheet) { return new StepConfig(2, "xslt", "map", false, Map.of("stylesheet", stylesheet)); }

    static String module(String text) {
        return XSL + "<xsl:template name=\"x\">" + text + "</xsl:template></xsl:stylesheet>";
    }

    static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /** {@code <dir>/flow} with {@code main.xsl} = {@link #MAIN} and {@code sub/x.xsl} writing {@code text}. */
    static Path flow(Path dir, String text) throws IOException {
        Path flow = dir.resolve("flow");
        write(flow.resolve("main.xsl"), MAIN);
        write(flow.resolve("sub/x.xsl"), module(text));
        return flow;
    }

    String transform(Stage stage) {
        Envelope out = stage.apply(rt.ingress("f", Headers.empty(), Buffer.buffer(INVOICE)), e -> e);
        return out.body().whole().toString();
    }

    @Test
    void twoFlowsWithTheSameMainAndDifferentIncludesEachSeeTheirOwn(@TempDir Path a, @TempDir Path b) throws IOException {
        Stage first = factory.create(step("main.xsl"), resources(flow(a, "include A")));
        Stage second = factory.create(step("main.xsl"), resources(flow(b, "include B")));

        assertEquals("include A", transform(first), "fixture: the first flow runs its own include");
        assertEquals("include B", transform(second),
                "the second flow's main.xsl has the first's text but its sub/x.xsl differs: it runs its own include, not the first flow's compiled copy");
        assertEquals(2, factory.compiledStylesheets(), "the same main file over different includes: two compilations");
    }

    @Test
    void theSameMainAtAnotherPathInTheFlowSeesTheIncludeNextToIt(@TempDir Path a, @TempDir Path b) throws IOException {
        Path first = flow(a, "include A");
        Path second = b.resolve("flow");
        write(second.resolve("deep/main.xsl"), MAIN);
        write(second.resolve("deep/sub/x.xsl"), module("deep include"));
        write(second.resolve("sub/x.xsl"), module("include A"));   // the first flow's include at the first flow's flow-relative path: never read here

        assertEquals("include A", transform(factory.create(step("main.xsl"), resources(first))), "fixture");
        assertEquals("deep include", transform(factory.create(step("deep/main.xsl"), resources(second))),
                "main.xsl's include resolves next to main.xsl: deep/sub/x.xsl, whatever sits at sub/x.xsl");
    }

    @Test
    void aMissingIncludeInTheSecondFlowFailsItsBuild(@TempDir Path a, @TempDir Path b) throws IOException {
        factory.create(step("main.xsl"), resources(flow(a, "include A")));
        Path second = b.resolve("flow");
        write(second.resolve("main.xsl"), MAIN);
        StepConfig step = step("main.xsl");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> factory.create(step, resources(second)),
                "the second flow has the first's main.xsl but no sub/x.xsl: its build fails, it does not get the first flow's compiled copy");
        assertTrue(e.getMessage().contains(step.toString()) && e.getMessage().contains("does not compile") && e.getMessage().contains("x.xsl"),
                e.getMessage());
        assertEquals(1, factory.compiledStylesheets(), "the failed build is not cached");
    }

    @Test
    void aConfinementRefusalIsNotServedFromTheCache(@TempDir Path a, @TempDir Path b) throws IOException {
        Path first = a.resolve("flow");
        String reference = first.toAbsolutePath().normalize().resolve("sub/x.xsl").toUri().toString();
        String absolute = XSL + "<xsl:include href=\"" + reference + "\"/><xsl:output method=\"text\"/>"
                + "<xsl:template match=\"/\"><xsl:call-template name=\"x\"/></xsl:template></xsl:stylesheet>";
        write(first.resolve("main.xsl"), absolute);
        write(first.resolve("sub/x.xsl"), module("include A"));
        Path second = b.resolve("flow");
        write(second.resolve("main.xsl"), absolute);
        write(second.resolve("sub/x.xsl"), module("include A"));   // its own copy: only the confinement tells the two apart
        assertEquals("include A", transform(factory.create(step("main.xsl"), resources(first))),
                "fixture: the absolute file: include lies inside the first flow's directory and compiles there");
        StepConfig step = step("main.xsl");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> factory.create(step, resources(second)),
                "the same text in another flow includes a file in the first flow's directory: refused (D-052), not served from the cache");
        assertTrue(e.getMessage().contains(step.toString()) && e.getMessage().contains(reference + " leaves the flow's directory"),
                "the build error names the step and the reference: " + e.getMessage());
        assertEquals(1, factory.compiledStylesheets(), "the refused build is not cached");
    }

    /**
     * {@code <dir>/flow/main.xsl} importing {@code sub/base.xsl} (which includes {@code ../common.xsl}) and including
     * {@code lib.xsl}; {@code unused.xsl} lies beside them. No static {@code unparsed-text()} / {@code uri-collection()}: Saxon
     * hands those absolute without a base and the cache pins them where they were read (FIX-17 review r1), so a flow in another
     * directory misses — {@link #aStaticUnparsedTextInAnotherDirectoryIsAMiss}.
     */
    static Path rich(Path dir) throws IOException {
        Path flow = dir.resolve("flow");
        write(flow.resolve("lib.xsl"), XSL + "<xsl:template name=\"lib\">lib:<xsl:value-of select=\"/Invoice/Id\"/></xsl:template></xsl:stylesheet>");
        write(flow.resolve("sub/base.xsl"), XSL + "<xsl:include href=\"../common.xsl\"/>"
                + "<xsl:template name=\"base\">base:<xsl:value-of select=\"/Invoice/Currency\"/>|<xsl:call-template name=\"common\"/></xsl:template></xsl:stylesheet>");
        write(flow.resolve("common.xsl"), XSL + "<xsl:template name=\"common\">common:<xsl:value-of select=\"count(//Line)\"/></xsl:template></xsl:stylesheet>");
        write(flow.resolve("unused.xsl"), module("unused"));
        write(flow.resolve("main.xsl"), XSL + "<xsl:import href=\"sub/base.xsl\"/><xsl:include href=\"lib.xsl\"/><xsl:output method=\"text\"/>"
                + "<xsl:template match=\"/\"><xsl:call-template name=\"lib\"/>|<xsl:call-template name=\"base\"/></xsl:template></xsl:stylesheet>");
        return flow;
    }

    @Test
    void aCacheHitReportsTheSameReadsAsACompile(@TempDir Path a, @TempDir Path b) throws IOException {
        Path second = rich(b);
        Set<Path> fresh = new HashSet<>();
        Stage compiled = ((XsltStepFactory) new SaxonXml().steps().get(0)).create(step("main.xsl"), resources(second, fresh));
        assertEquals("lib:4711|base:CZK|common:2", transform(compiled), "fixture: every module took part in the compile");
        Path root = second.toAbsolutePath().normalize();
        assertEquals(Set.of(root.resolve("main.xsl"), root.resolve("lib.xsl"), root.resolve("sub/base.xsl"), root.resolve("common.xsl")), fresh,
                "fixture: a fresh compile in the second flow's directory reports what it read there");

        factory.create(step("main.xsl"), resources(rich(a)));
        Set<Path> hit = new HashSet<>();
        Stage served = factory.create(step("main.xsl"), resources(second, hit));

        assertEquals(1, factory.compiledStylesheets(), "fixture: identical files in another directory, the second build is a cache hit");
        assertEquals("lib:4711|base:CZK|common:2", transform(served));
        assertEquals(fresh, hit, "a cache hit reports to the requesting flow's sink every file the compile read, as paths in the requesting"
                + " flow's directory, the same as a fresh compile there");
    }

    /** {@code <dir>/flow/main.xsl} writing {@code data/codes.txt}, read by a static {@code unparsed-text()}, which holds {@code codes}. */
    static Path staticText(Path dir, String codes) throws IOException {
        Path flow = dir.resolve("flow");
        write(flow.resolve("data/codes.txt"), codes);
        write(flow.resolve("main.xsl"), XSL + "<xsl:output method=\"text\"/>"
                + "<xsl:variable name=\"codes\" static=\"yes\" select=\"unparsed-text('data/codes.txt')\"/>"
                + "<xsl:template match=\"/\"><xsl:value-of select=\"$codes\"/></xsl:template></xsl:stylesheet>");
        return flow;
    }

    @Test
    void aStaticUnparsedTextInAnotherDirectoryIsAMiss(@TempDir Path a, @TempDir Path b) throws IOException {
        assertEquals("A,B", transform(factory.create(step("main.xsl"), resources(staticText(a, "A,B")))), "fixture");
        Path second = staticText(b, "A,B");
        Set<Path> compiledThere = new HashSet<>();

        Stage own = factory.create(step("main.xsl"), resources(second, compiledThere));

        assertEquals(2, factory.compiledStylesheets(),
                "a static unparsed-text() reaches the cache absolute and without a base: pinned where it was read, so the same files in"
                        + " another directory compile again");
        assertEquals("A,B", transform(own));
        Path root = second.toAbsolutePath().normalize();
        assertEquals(Set.of(root.resolve("main.xsl"), root.resolve("data/codes.txt")), compiledThere, "fixture: the second flow's own compile read its own file");

        Set<Path> rebuilt = new HashSet<>();
        Stage again = factory.create(step("main.xsl"), resources(second, rebuilt));
        assertEquals(2, factory.compiledStylesheets(), "a rebuild in the same directory, the files unchanged, is still a hit");
        assertEquals("A,B", transform(again));
        assertEquals(compiledThere, rebuilt, "the same-directory hit reports the pinned read too, as the compile did");
    }

    @Test
    void theMainFileNameIsNotPartOfTheKey(@TempDir Path a, @TempDir Path b) throws IOException {
        Stage first = factory.create(step("main.xsl"), resources(flow(a, "include A")));
        Path second = b.resolve("flow");
        write(second.resolve("other.xsl"), MAIN);
        write(second.resolve("sub/x.xsl"), module("include A"));

        Stage other = factory.create(step("other.xsl"), resources(second));

        assertEquals(1, factory.compiledStylesheets(), "the same main content under another file name, over the same include: one compilation");
        assertEquals("include A", transform(first));
        assertEquals("include A", transform(other));
    }

    @Test
    void aClimbingIncludeOutsideTheSecondFlowIsRefusedNotServed(@TempDir Path root) throws IOException {
        String climbing = XSL + "<xsl:include href=\"../flows/t.xsl\"/><xsl:output method=\"text\"/>"
                + "<xsl:template match=\"/\"><xsl:call-template name=\"x\"/></xsl:template></xsl:stylesheet>";
        Path first = root.resolve("x/flows");
        write(first.resolve("main.xsl"), climbing);
        write(first.resolve("t.xsl"), module("t"));
        Path second = root.resolve("y/other");
        write(second.resolve("main.xsl"), climbing);
        write(second.resolve("t.xsl"), module("t"));   // the same bytes where the first flow's read lies relative to its main file
        write(root.resolve("y/flows/t.xsl"), module("t"));   // and where the second flow's own compile would read it: outside its directory
        assertEquals("t", transform(factory.create(step("main.xsl"), resources(first))), "fixture: ../flows/t.xsl climbs out and back into the first flow's directory");
        StepConfig step = step("main.xsl");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> factory.create(step, resources(second)),
                "in the second flow ../flows/t.xsl leaves its directory (other/): refused (D-052), not served from the cache");
        assertTrue(e.getMessage().contains(step.toString()) && e.getMessage().contains("../flows/t.xsl leaves the flow's directory"), e.getMessage());
        assertEquals(1, factory.compiledStylesheets(), "the refused build is not cached");
    }

    @Test
    void aClimbingIncludeIsCheckedWhereTheSecondFlowReadsIt(@TempDir Path a, @TempDir Path b) throws IOException {
        String climbing = XSL + "<xsl:include href=\"../xsl/inc.xsl\"/><xsl:output method=\"text\"/>"
                + "<xsl:template match=\"/\"><xsl:call-template name=\"x\"/></xsl:template></xsl:stylesheet>";
        write(a.resolve("xsl/main.xsl"), climbing);
        write(a.resolve("xsl/inc.xsl"), module("first"));
        write(b.resolve("templates/main.xsl"), climbing);
        write(b.resolve("templates/inc.xsl"), module("first"));   // the same bytes next to the main file, where the first flow's read lay
        write(b.resolve("xsl/inc.xsl"), module("second's own"));   // where the second flow's own compile reads ../xsl/inc.xsl
        assertEquals("first", transform(factory.create(step("xsl/main.xsl"), resources(a))), "fixture");
        Set<Path> reads = new HashSet<>();

        Stage second = factory.create(step("templates/main.xsl"), resources(b, reads));

        assertEquals("second's own", transform(second), "the second flow's main.xsl includes ../xsl/inc.xsl = xsl/inc.xsl in its directory: its own module runs");
        assertEquals(2, factory.compiledStylesheets(), "compiled fresh, not served from the first flow's entry");
        Path root = b.toAbsolutePath().normalize();
        assertEquals(Set.of(root.resolve("templates/main.xsl"), root.resolve("xsl/inc.xsl")), reads, "the reads report xsl/inc.xsl, what the compile read");
    }

    @Test
    void aCacheHitStillHappensForIdenticalFilesInAnotherDirectory(@TempDir Path a, @TempDir Path b) throws IOException {
        Stage first = factory.create(step("main.xsl"), resources(flow(a, "include A")));
        Stage second = factory.create(step("main.xsl"), resources(flow(b, "include A")));

        assertEquals(1, factory.compiledStylesheets(), "the same files with the same contents in another flow's directory compile once");
        assertEquals("include A", transform(first));
        assertEquals("include A", transform(second));
    }

    /** {@code <dir>/flow/main.xsl} writing whether {@code data/x.txt} is available and how many entries {@code lookup/} lists. */
    static Path probing(Path dir) throws IOException {
        Path flow = dir.resolve("flow");
        write(flow.resolve("main.xsl"), XSL + "<xsl:output method=\"text\"/>"
                + "<xsl:variable name=\"probed\" static=\"yes\" select=\"unparsed-text-available('data/x.txt')\"/>"
                + "<xsl:variable name=\"listed\" static=\"yes\" select=\"count(uri-collection('lookup'))\"/>"
                + "<xsl:template match=\"/\"><xsl:value-of select=\"$probed\"/>|<xsl:value-of select=\"$listed\"/></xsl:template></xsl:stylesheet>");
        write(flow.resolve("lookup/a.txt"), "a");
        return flow;
    }

    @Test
    void aProbedFileAbsentForOneFlowAndPresentForTheOtherIsNotAHit(@TempDir Path a, @TempDir Path b) throws IOException {
        Path second = probing(b);
        write(second.resolve("data/x.txt"), "here");

        assertEquals("false|1", transform(factory.create(step("main.xsl"), resources(probing(a)))), "fixture: absent in the first flow");
        assertEquals("true|1", transform(factory.create(step("main.xsl"), resources(second))),
                "the file the first compile probed as absent exists in the second flow's directory: compiled again, the probe says true");
    }

    @Test
    void aListedDirectoryWithAnotherEntryIsNotAHit(@TempDir Path a, @TempDir Path b) throws IOException {
        Path second = probing(b);
        write(second.resolve("lookup/b.txt"), "b");

        assertEquals("false|1", transform(factory.create(step("main.xsl"), resources(probing(a)))), "fixture: one entry in the first flow");
        assertEquals("false|2", transform(factory.create(step("main.xsl"), resources(second))),
                "the directory the first compile listed has another entry in the second flow's directory: compiled again, two entries");
    }
}
