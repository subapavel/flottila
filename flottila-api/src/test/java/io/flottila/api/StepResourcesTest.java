package io.flottila.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every file a step reads at build time resolves inside the flow's directory (FIX-6, D-052): the file the flow names
 * ({@link StepResources#file}) and every file that one includes or imports ({@link StepResources#resolve}). A reference
 * that leaves the directory after normalisation — {@code ..}, an absolute path, a {@code file:} URI elsewhere — or is
 * not a file at all is refused with a message naming the reference and the directory. Paths are compared, not real
 * paths; the files need not exist.
 */
class StepResourcesTest {

    @TempDir Path tmp;

    StepResources resources(Path baseDir) {
        return new StepResources(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), (delay, task) -> () -> false, () -> "id",
                new InMemoryBlobStore(1024), Hasher.sha256(), 1024, baseDir);
    }

    Path flow() { return tmp.resolve("flow").toAbsolutePath().normalize(); }

    static void refused(Path root, String reference, Runnable call) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, call::run, reference + " must be refused");
        assertTrue(e.getMessage().contains(reference) && e.getMessage().contains(root.toString()),
                "the message names the reference and the flow's directory: " + e.getMessage());
    }

    @Test
    void aFileInsideTheFlowsDirectoryResolvesNormalisedUnderIt() {
        StepResources r = resources(flow());
        assertEquals(flow().resolve("sub/a.xsd"), r.file("sub/a.xsd"));
        assertEquals(flow().resolve("b.xsd"), r.file("a/../b.xsd"), "a detour that stays inside is normalised away");
        assertEquals(flow().resolve("c.xsd"), r.file(flow().resolve("c.xsd").toString()), "an absolute path inside the directory is inside");
        assertEquals(flow().resolve("a.xsd"), resources(tmp.resolve("x/../flow")).file("a.xsd"),
                "the directory itself is normalised before the comparison");
        assertThrows(NullPointerException.class, () -> r.file(null));
    }

    @Test
    void aFileThatLeavesTheFlowsDirectoryIsRefusedNamingTheReferenceAndTheDirectory() {
        StepResources r = resources(flow());
        for (String reference : new String[] {"../x", "/etc/x", "sub/../../x", "../flow-sibling/a.xsd", tmp.resolve("outside.xsd").toString()}) {
            refused(flow(), reference, () -> r.file(reference));
            assertTrue(assertThrows(IllegalArgumentException.class, () -> r.file(reference)).getMessage().contains("leaves the flow's directory"));
        }
        StepResources viaDotDot = resources(tmp.resolve("x/../flow"));
        refused(flow(), "../x", () -> viaDotDot.file("../x"));
    }

    @Test
    void aRelativeFlowDirectoryIsTakenAbsoluteAndStillConfines() {
        StepResources r = resources(Path.of("flows"));
        Path root = Path.of("flows").toAbsolutePath().normalize();
        assertEquals(root.resolve("a.xsd"), r.file("a.xsd"));
        refused(root, "../a.xsd", () -> r.file("../a.xsd"));
    }

    @Test
    void anIncludedReferenceResolvesAgainstTheIncludingFileAndStaysInside() {
        StepResources r = resources(flow());
        String including = flow().resolve("sub/a.xsd").toUri().toString();
        assertEquals(flow().resolve("b.xsd"), r.resolve(including, "../b.xsd"), "up from a subdirectory, still inside");
        assertEquals(flow().resolve("sub/c.xsd"), r.resolve(including, "c.xsd"), "next to the including file");
        assertEquals(flow().resolve("d.xsd"), r.resolve(including, flow().resolve("d.xsd").toUri().toString()), "an absolute file: URI inside");
        assertEquals(flow().resolve("a.xsd"), r.resolve(null, "a.xsd"), "no base: the flow's directory");
        assertEquals(flow().resolve("a.xsd"), r.resolve("", "a.xsd"), "a blank base: the flow's directory");
        assertEquals(flow().resolve("a.xsd"), r.resolve(" ", "a.xsd"), "a blank base: the flow's directory");
    }

    @Test
    void anExistingFlowDirectoryResolvesAndConfinesWithoutABase() throws IOException {
        Files.createDirectories(flow());   // an existing directory's URI already ends with a slash
        StepResources r = resources(flow());
        assertEquals(flow().resolve("a.xsd"), r.resolve(null, "a.xsd"), "no base: inside the existing flow's directory, not next to it");
        assertEquals(flow().resolve("sub/b.xsd"), r.resolve("", "sub/b.xsd"));
        refused(flow(), "../x", () -> r.resolve(null, "../x"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> r.resolve(null, "../x")).getMessage().contains("leaves the flow's directory"));
    }

    @Test
    void anIncludedReferenceThatLeavesTheDirectoryOrIsNotAFileIsRefused() {
        StepResources r = resources(flow());
        String including = flow().resolve("sub/a.xsd").toUri().toString();
        refused(flow(), "../../x", () -> r.resolve(including, "../../x"));
        refused(flow(), "../x", () -> r.resolve(null, "../x"));
        String outside = tmp.resolve("outside.xsd").toUri().toString();
        refused(flow(), outside, () -> r.resolve(including, outside));
        refused(flow(), "../../flow-sibling/a.xsd", () -> r.resolve(including, "../../flow-sibling/a.xsd"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> r.resolve(including, "../../x")).getMessage().contains("leaves the flow's directory"));
        for (String remote : new String[] {"http://example.com/x.xsd", "https://example.com/x.xsd", "jar:file:/a.jar!/x.xsd"}) {
            refused(flow(), remote, () -> r.resolve(including, remote));
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> r.resolve(including, remote));
            assertTrue(e.getMessage().contains("not a file"), "a reference that is not a file is refused as such: " + e.getMessage());
        }
        refused(flow(), "http://example.com/x.xsd", () -> r.resolve(null, "http://example.com/x.xsd"));
        refused(flow(), "a b.xsd", () -> r.resolve(including, "a b.xsd"));   // not a URI at all: refused, not a URISyntaxException
        refused(flow(), "a b.xsd", () -> r.resolve(null, "a b.xsd"));
    }
}
