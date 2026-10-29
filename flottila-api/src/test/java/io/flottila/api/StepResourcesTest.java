package io.flottila.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every file a step reads at build time resolves inside the flow's directory (FIX-6, D-052): the file the flow names
 * ({@link StepResources#file}) and every file that one includes or imports ({@link StepResources#resolve}). A reference
 * that leaves the directory after normalisation — {@code ..}, an absolute path, a {@code file:} URI elsewhere — or is
 * not a file at all is refused with a message naming the reference and the directory. Paths are compared, not real
 * paths; the files need not exist.
 *
 * <p>P1-09d2a (D-063): the 8th component {@code reads}, a {@code Consumer<Path>}, receives every confined path {@link
 * StepResources#file} and {@link StepResources#resolve} return — the effective hash of a flow is taken over the files its build
 * read — and never a path either refuses; the 7-argument constructor reports to a no-op.
 *
 * <p>P2-02b (D-087): the 9th component {@code dependencies}, a {@code Map<String, Path>} from a declared dependency's name to the
 * directory its version is unpacked in; the 7- and 8-argument constructors pass {@code Map.of()}. {@code file("<dep>:<path>")} —
 * the prefix {@code ^[a-z][a-z0-9-]{0,63}:} before the first {@code /} — resolves {@code <path>} in that dependency's directory and
 * is confined to it; a name not in the map is refused. Any other reference is a plain path from the package root, a root file whose
 * name has a colon included (named as {@code ./<name>}). An absolute path is confined to whichever root holds it; an include
 * ({@link StepResources#resolve}) to the root that holds its including file, so an include in a dependency stays in it, and a
 * {@code <dep>:} inside XML is a URI of another scheme, refused as not a file. Every confined path is reported as before.
 */
class StepResourcesTest {

    @TempDir Path tmp;

    StepResources resources(Path baseDir) {
        return new StepResources(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), (delay, task) -> () -> false, () -> "id",
                new InMemoryBlobStore(1024), Hasher.sha256(), 1024, baseDir);
    }

    StepResources reporting(Path baseDir, List<Path> reads) {
        return new StepResources(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), (delay, task) -> () -> false, () -> "id",
                new InMemoryBlobStore(1024), Hasher.sha256(), 1024, baseDir, reads::add);
    }

    Path flow() { return tmp.resolve("flow").toAbsolutePath().normalize(); }

    /** The package {@code invoice}'s directory, under the data directory's {@code packages/invoice/<sha>/}. */
    Path pkg() { return tmp.resolve("packages/invoice/" + "1".repeat(64)).toAbsolutePath().normalize(); }

    /** The directory common's pinned version is unpacked in. */
    Path common() { return tmp.resolve("packages/common/" + "c".repeat(64)).toAbsolutePath().normalize(); }

    /** The directory util's pinned version is unpacked in. */
    Path util() { return tmp.resolve("packages/util/" + "u".repeat(64)).toAbsolutePath().normalize(); }

    StepResources withDependencies(Path baseDir, Map<String, Path> dependencies, List<Path> reads) {
        return new StepResources(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), (delay, task) -> () -> false, () -> "id",
                new InMemoryBlobStore(1024), Hasher.sha256(), 1024, baseDir, reads::add, dependencies);
    }

    /** invoice's resources: common and util declared. */
    StepResources invoice(List<Path> reads) {
        return withDependencies(pkg(), Map.of("common", common(), "util", util()), reads);
    }

    static IllegalArgumentException refusedNaming(String reference, Runnable call) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, call::run, reference + " must be refused");
        assertTrue(e.getMessage().contains(reference), "the message names the reference: " + e.getMessage());
        return e;
    }

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

    // --- P1-09d2a (D-063): the reads sink

    @Test
    void fileAndResolveReportEveryConfinedPath() {
        List<Path> reads = new ArrayList<>();
        StepResources r = reporting(flow(), reads);
        String including = flow().resolve("sub/a.xsd").toUri().toString();
        List<Path> returned = new ArrayList<>();
        returned.add(r.file("sub/a.xsd"));
        returned.add(r.file("x/../b.xsd"));
        returned.add(r.resolve(including, "../c.xsd"));
        returned.add(r.resolve(null, "d.xsd"));
        returned.add(r.resolve(including, flow().resolve("e.xsd").toUri().toString()));
        returned.add(r.file("sub/a.xsd"));
        assertEquals(List.of(flow().resolve("sub/a.xsd"), flow().resolve("b.xsd"), flow().resolve("c.xsd"), flow().resolve("d.xsd"),
                flow().resolve("e.xsd"), flow().resolve("sub/a.xsd")), returned, "fixture: each call answers the confined, normalised path");
        assertEquals(returned, reads, "every path file() and resolve() return reaches the sink, as returned (absolute, normalised), once per call, in call order");

        StepResources silent = resources(flow());
        assertNotNull(silent.reads(), "the 7-argument constructor reports to a no-op, not to null");
        assertEquals(flow().resolve("a.xsd"), silent.file("a.xsd"), "and still confines and resolves");
        silent.reads().accept(flow().resolve("a.xsd"));   // the no-op takes a path without failing
    }

    @Test
    void aRefusedPathIsNotReported() {
        List<Path> reads = new ArrayList<>();
        StepResources r = reporting(flow(), reads);
        String including = flow().resolve("sub/a.xsd").toUri().toString();
        refused(flow(), "../x", () -> r.file("../x"));
        refused(flow(), "/etc/x", () -> r.file("/etc/x"));
        refused(flow(), "../../x", () -> r.resolve(including, "../../x"));
        String outside = tmp.resolve("outside.xsd").toUri().toString();
        refused(flow(), outside, () -> r.resolve(including, outside));
        refused(flow(), "http://example.com/x.xsd", () -> r.resolve(including, "http://example.com/x.xsd"));
        refused(flow(), "a b.xsd", () -> r.resolve(including, "a b.xsd"));
        assertEquals(List.of(), reads, "a path that leaves the directory, is not a file or is no URI is refused and never reported");

        Path inside = r.resolve(including, "ok.xsd");
        assertEquals(List.of(inside), reads, "witness: the same resources report the next confined path");
    }

    // --- P2-02b (D-087): dependencies

    /**
     * {@code common:xslt/to-ubl.xsl} is {@code xslt/to-ubl.xsl} in common's directory, absolute and normalised (a detour that stays
     * inside included); each dependency resolves in its own directory, a dependency directory given relative or with {@code ..} is
     * taken absolute and normalised, a 64-character name is a name; an absolute path inside a dependency's directory lies inside
     * it. The map is the component; the 7- and 8-argument constructors give an empty one, and a null map is refused.
     */
    @Test
    void aDependencyReferenceResolvesInItsDir() {
        List<Path> reads = new ArrayList<>();
        StepResources r = invoice(reads);

        assertEquals(common().resolve("xslt/to-ubl.xsl"), r.file("common:xslt/to-ubl.xsl"));
        assertEquals(common().resolve("b.xsl"), r.file("common:xslt/../b.xsl"), "a detour inside the dependency is normalised away");
        assertEquals(util().resolve("xslt/to-ubl.xsl"), r.file("util:xslt/to-ubl.xsl"), "the same path in another dependency: its own directory");
        assertEquals(common().resolve("xslt/a.xsl"), r.file(common().resolve("xslt/a.xsl").toString()), "an absolute path inside a dependency's directory");
        assertEquals(Map.of("common", common(), "util", util()), r.dependencies());

        String longest = "a" + "b".repeat(63);
        Path relative = Path.of("").toAbsolutePath().relativize(common());
        StepResources odd = withDependencies(pkg(), Map.of(longest, relative, "lib", tmp.resolve("x/../packages/lib/1")), reads);
        assertEquals(common().resolve("x.xsl"), odd.file(longest + ":x.xsl"), "a 64-character name; a relative directory taken absolute");
        assertEquals(tmp.resolve("packages/lib/1/x.xsl").toAbsolutePath().normalize(), odd.file("lib:x.xsl"), "the directory normalised");

        assertEquals(Map.of(), resources(flow()).dependencies(), "the 7-argument constructor: no dependencies");
        assertEquals(Map.of(), reporting(flow(), reads).dependencies(), "the 8-argument constructor: no dependencies");
        assertThrows(NullPointerException.class, () -> withDependencies(pkg(), null, reads));
    }

    /** A {@code <name>:} the map does not hold is refused naming the reference — and so is any, when the map is empty. */
    @Test
    void anUnknownDependencyIsRefused() {
        List<Path> reads = new ArrayList<>();
        StepResources r = invoice(reads);

        refusedNaming("nope:xslt/to-ubl.xsl", () -> r.file("nope:xslt/to-ubl.xsl"));
        refusedNaming("commons:x.xsl", () -> r.file("commons:x.xsl"));
        StepResources none = reporting(pkg(), reads);
        refusedNaming("common:xslt/to-ubl.xsl", () -> none.file("common:xslt/to-ubl.xsl"));
        assertEquals(List.of(), reads, "a refused reference is not reported");
        assertEquals(common().resolve("x.xsl"), r.file("common:x.xsl"), "witness: a declared name resolves");
    }

    /**
     * A dependency reference that normalises outside its dependency's directory — up, into the package or a sibling version, an
     * absolute path behind the prefix — is refused naming the reference; so is an absolute path in a directory no root holds (another
     * version of common). None is reported.
     */
    @Test
    void aDependencyPathLeavingItIsRefused() {
        List<Path> reads = new ArrayList<>();
        StepResources r = invoice(reads);
        String sibling = tmp.resolve("packages/common/" + "d".repeat(64) + "/x.xsl").toAbsolutePath().normalize().toString();

        for (String reference : List.of("common:../x.xsl", "common:xslt/../../x.xsl", "common:../../invoice/" + "1".repeat(64) + "/x.xsl",
                "common:../" + "d".repeat(64) + "/x.xsl", "common:/etc/passwd", "common:" + pkg().resolve("x.xsl"), sibling)) {
            refusedNaming(reference, () -> r.file(reference));
        }
        assertEquals(List.of(), reads, "nothing refused is reported");
        assertEquals(common().resolve("x.xsl"), r.file("common:sub/../x.xsl"), "witness: a detour that stays inside");
    }

    /**
     * An include resolves against its including file and stays in the root that holds that file: in common it reaches common's
     * files only — not the package, not util — and an include in the package does not reach into common. {@code common:lib.xsl}
     * inside XML is a URI of the scheme {@code common}: refused as not a file, wherever it is written.
     */
    @Test
    void anIncludeInADependencyIsConfinedToIt() {
        List<Path> reads = new ArrayList<>();
        StepResources r = invoice(reads);
        String inCommon = common().resolve("xslt/to-ubl.xsl").toUri().toString();
        String inPackage = pkg().resolve("flows/main.xsl").toUri().toString();

        assertEquals(common().resolve("xslt/lib.xsl"), r.resolve(inCommon, "lib.xsl"), "next to the including file, in common");
        assertEquals(common().resolve("xsd/x.xsd"), r.resolve(inCommon, "../xsd/x.xsd"), "up within common");
        assertEquals(common().resolve("y.xsl"), r.resolve(inCommon, common().resolve("y.xsl").toUri().toString()), "an absolute file: URI in common");

        refusedNaming("../../x.xsl", () -> r.resolve(inCommon, "../../x.xsl"));
        String intoPackage = pkg().resolve("flows/main.xsl").toUri().toString();
        refusedNaming(intoPackage, () -> r.resolve(inCommon, intoPackage));
        String intoUtil = util().resolve("xslt/to-ubl.xsl").toUri().toString();
        refusedNaming(intoUtil, () -> r.resolve(inCommon, intoUtil));
        String intoCommon = common().resolve("xslt/lib.xsl").toUri().toString();
        refusedNaming(intoCommon, () -> r.resolve(inPackage, intoCommon));
        refusedNaming(intoCommon, () -> r.resolve(null, intoCommon));
        for (String base : java.util.Arrays.asList(inCommon, inPackage, null)) {
            IllegalArgumentException e = refusedNaming("common:lib.xsl", () -> r.resolve(base, "common:lib.xsl"));
            assertTrue(e.getMessage().contains("not a file"), "a <dep>: inside XML is no file (base " + base + "): " + e.getMessage());
        }
        assertEquals(pkg().resolve("flows/lib.xsl"), r.resolve(inPackage, "lib.xsl"), "witness: an include in the package stays in the package");
    }

    /**
     * Beside dependencies, a reference without a package-name prefix is a path from the package root: a plain path, a name with
     * an upper-case letter, a leading digit, dash or underscore inside the name, more than 64 characters, a colon after the first
     * {@code /}, and {@code ./common:x.xsl} — the way to name a root file called {@code common:x.xsl}.
     */
    @Test
    void aPlainPathStillResolvesInThePackage() {
        List<Path> reads = new ArrayList<>();
        StepResources r = invoice(reads);
        Map<String, String> plain = new HashMap<>();
        plain.put("xslt/to-ubl.xsl", "xslt/to-ubl.xsl");
        plain.put("Common:x.xsl", "Common:x.xsl");
        plain.put("1common:x.xsl", "1common:x.xsl");
        plain.put("-common:x.xsl", "-common:x.xsl");
        plain.put("co_mmon:x.xsl", "co_mmon:x.xsl");
        plain.put("x.common:x.xsl", "x.common:x.xsl");
        plain.put("a".repeat(65) + ":x.xsl", "a".repeat(65) + ":x.xsl");
        plain.put("sub/common:x.xsl", "sub/common:x.xsl");
        plain.put("./common:x.xsl", "common:x.xsl");
        plain.forEach((reference, path) -> assertEquals(pkg().resolve(path), r.file(reference), reference + " is a path from the package root"));
        assertEquals(pkg().resolve("a.xsd"), r.resolve(null, "a.xsd"), "no base: the package's directory");
        refusedNaming("../x.xsl", () -> r.file("../x.xsl"));   // the package's own confinement holds beside dependencies
        assertFalse(reads.isEmpty(), "fixture: the plain paths were reported");
    }

    /**
     * A relative path is a path from the package root, confined to the package: one that walks up into a dependency's directory —
     * {@code ../../common/<sha>/x.xsl}, which normalises to a file of common — is refused and never reported as a read, with or
     * without a base of the package. Only an absolute path, or {@code common:}, reaches common.
     */
    @Test
    void aRelativePathCannotWalkIntoADependencysDir() {
        List<Path> reads = new ArrayList<>();
        StepResources r = invoice(reads);
        String intoCommon = "../../common/" + "c".repeat(64) + "/x.xsl";
        String intoUtil = "../../util/" + "u".repeat(64) + "/y.xsl";
        assertEquals(common().resolve("x.xsl"), pkg().resolve(intoCommon).normalize(), "fixture: the path lands in common's directory");

        refusedNaming(intoCommon, () -> r.file(intoCommon));
        refusedNaming(intoUtil, () -> r.file(intoUtil));
        refusedNaming(intoCommon, () -> r.resolve(null, intoCommon));
        assertEquals(List.of(), reads, "a refused path is never reported");

        assertEquals(common().resolve("x.xsl"), r.file("common:x.xsl"), "witness: common: reaches it");
        assertEquals(common().resolve("x.xsl"), r.file(common().resolve("x.xsl").toString()), "witness: so does its absolute path");
    }

    /** Every confined path in a dependency reaches the reads sink as returned, once per call, in call order; a refused one never. */
    @Test
    void dependencyReadsAreReported() {
        List<Path> reads = new ArrayList<>();
        StepResources r = invoice(reads);
        String inCommon = common().resolve("xslt/to-ubl.xsl").toUri().toString();

        List<Path> returned = new ArrayList<>();
        returned.add(r.file("common:xslt/to-ubl.xsl"));
        returned.add(r.resolve(inCommon, "lib.xsl"));
        returned.add(r.file("util:x/../y.xsl"));
        returned.add(r.file("main.xsl"));
        returned.add(r.file("common:xslt/to-ubl.xsl"));
        assertThrows(IllegalArgumentException.class, () -> r.file("nope:a.xsl"));
        assertThrows(IllegalArgumentException.class, () -> r.file("common:../a.xsl"));
        assertThrows(IllegalArgumentException.class, () -> r.resolve(inCommon, "../../a.xsl"));

        assertEquals(List.of(common().resolve("xslt/to-ubl.xsl"), common().resolve("xslt/lib.xsl"), util().resolve("y.xsl"), pkg().resolve("main.xsl"),
                common().resolve("xslt/to-ubl.xsl")), returned, "fixture: each call answers the confined, normalised path");
        assertEquals(returned, reads, "every path returned reaches the sink, absolute and normalised, in call order; a refused one never");
    }
}
