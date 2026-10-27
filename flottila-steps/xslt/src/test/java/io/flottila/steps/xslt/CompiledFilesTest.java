package io.flottila.steps.xslt;

import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static io.flottila.steps.xslt.XsltCompileCacheTest.XSL;
import static io.flottila.steps.xslt.XsltCompileCacheTest.module;
import static io.flottila.steps.xslt.XsltCompileCacheTest.step;
import static io.flottila.steps.xslt.XsltCompileCacheTest.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-17, beyond the packet's cases: where a read lies for the next flow when it was reached through an absolute
 * reference, and a file that cannot be read.
 */
class CompiledFilesTest {

    final XsltCompileCacheTest cache = new XsltCompileCacheTest();
    final XsltStepFactory factory = cache.factory;

    /** A main stylesheet that includes {@code href} and outputs what its template {@code x} writes. */
    static String including(String href) {
        return XSL + "<xsl:include href=\"" + href + "\"/><xsl:output method=\"text\"/>"
                + "<xsl:template match=\"/\"><xsl:call-template name=\"x\"/></xsl:template></xsl:stylesheet>";
    }

    @Test
    void anAbsolutePathIncludeIsRefusedForTheNextFlow(@TempDir Path a, @TempDir Path b) throws IOException {
        Path first = a.resolve("flow");
        String reference = first.toAbsolutePath().normalize().resolve("sub/x.xsl").toString();   // a path, not a file: URI
        write(first.resolve("main.xsl"), including(reference));
        write(first.resolve("sub/x.xsl"), module("include A"));
        Path second = b.resolve("flow");
        write(second.resolve("main.xsl"), including(reference));
        write(second.resolve("sub/x.xsl"), module("include A"));
        assertEquals("include A", cache.transform(factory.create(step("main.xsl"), cache.resources(first))),
                "fixture: the absolute path lies inside the first flow's directory");
        StepConfig step = step("main.xsl");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> factory.create(step, cache.resources(second)),
                "an include written as an absolute path stays where it is: the next flow's build reads it there and is refused");
        assertTrue(e.getMessage().contains(reference + " leaves the flow's directory"), e.getMessage());
        assertEquals(1, factory.compiledStylesheets());
    }

    @Test
    void aModuleReachedThroughAnAbsoluteIncludeReadsItsOwnIncludesWhereItLies(@TempDir Path root) throws IOException {
        Path inner = root.resolve("inner");   // the first flow's directory, inside the second flow's
        String absolute = inner.toAbsolutePath().normalize().resolve("sub/x.xsl").toUri().toString();
        write(inner.resolve("main.xsl"), including(absolute));
        write(inner.resolve("sub/x.xsl"), XSL + "<xsl:include href=\"y.xsl\"/></xsl:stylesheet>");
        write(inner.resolve("sub/y.xsl"), module("inner y"));
        write(root.resolve("main.xsl"), including(absolute));
        write(root.resolve("sub/y.xsl"), module("outer y"));   // where y.xsl would lie had it moved with the main file: never read
        assertEquals("inner y", cache.transform(factory.create(step("main.xsl"), cache.resources(inner))), "fixture");
        Set<Path> fresh = new HashSet<>();
        new SaxonXml().steps().get(0).create(step("main.xsl"), cache.resources(root, fresh));

        Set<Path> hit = new HashSet<>();
        String out = cache.transform(factory.create(step("main.xsl"), cache.resources(root, hit)));

        assertEquals("inner y", out);
        assertEquals(1, factory.compiledStylesheets(),
                "the second flow's compile reads the same files, inner/sub/x.xsl and the y.xsl next to it: served from the cache");
        assertEquals(fresh, hit, "the hit reports inner/sub/y.xsl, as the compile does, not sub/y.xsl");
    }

    @Test
    void aFileThatCannotBeReadIsNeverTheSame(@TempDir Path dir) throws IOException {
        Path flow = probing(dir);
        assertEquals("false", cache.transform(factory.create(step("main.xsl"), cache.resources(flow))),
                "fixture: Saxon cannot read data/x.txt, the probe says false");

        Stage rebuilt = factory.create(step("main.xsl"), cache.resources(flow));

        assertEquals(2, factory.compiledStylesheets(),
                "a file that cannot be read matches nothing, not even itself: a rebuild of the same flow over the same files compiles again");
        assertEquals("false", cache.transform(rebuilt));
    }

    @Test
    void aReadWithoutABaseStaysWhereItWasRead(@TempDir Path a, @TempDir Path b) throws IOException {
        Path first = a.resolve("flow").toAbsolutePath().normalize();
        Path second = b.resolve("flow").toAbsolutePath().normalize();
        write(first.resolve("data/x.txt"), "x");
        write(second.resolve("data/x.txt"), "x");
        CompiledFiles.Recorder reads = new CompiledFiles.Recorder(first.resolve("main.xsl"));
        reads.read(null, "data/x.txt", first.resolve("data/x.txt"));   // relative, and no base it was resolved against
        CompiledFiles files = reads.held(cache.rt.hasher());
        Set<Path> reported = new HashSet<>();

        assertFalse(files.sameIn(second.resolve("main.xsl"), cache.resources(second, reported)),
                "a read without a base is kept where it was read: in another flow's directory it is outside, not the same");
        assertEquals(Set.of(), reported, "nothing reported on a miss");
        assertTrue(files.sameIn(first.resolve("main.xsl"), cache.resources(first, reported)), "fixture: the same flow, the same file");
        assertEquals(Set.of(first.resolve("data/x.txt")), reported);
    }

    /** {@code <dir>/flow/main.xsl} writing whether {@code data/x.txt} — a link to {@code /proc/self/mem}, a file whose read fails — is available. */
    static Path probing(Path dir) throws IOException {
        Path flow = dir.resolve("flow");
        write(flow.resolve("main.xsl"), XSL + "<xsl:output method=\"text\"/>"
                + "<xsl:variable name=\"probed\" static=\"yes\" select=\"unparsed-text-available('data/x.txt')\"/>"
                + "<xsl:template match=\"/\"><xsl:value-of select=\"$probed\"/></xsl:template></xsl:stylesheet>");
        Files.createDirectories(flow.resolve("data"));
        Files.createSymbolicLink(flow.resolve("data/x.txt"), Path.of("/proc/self/mem"));
        return flow;
    }
}
