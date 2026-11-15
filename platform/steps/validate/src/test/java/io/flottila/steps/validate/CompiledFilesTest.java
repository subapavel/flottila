package io.flottila.steps.validate;

import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.api.StepResources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static io.flottila.steps.validate.XsdCompileCacheTest.XS;
import static io.flottila.steps.validate.XsdCompileCacheTest.step;
import static io.flottila.steps.validate.XsdCompileCacheTest.types;
import static io.flottila.steps.validate.XsdCompileCacheTest.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FIX-17, beyond the packet's cases: where a read lies for the next flow when it was reached through an absolute reference. */
class CompiledFilesTest {

    final XsdCompileCacheTest cache = new XsdCompileCacheTest();
    final XsdStepFactory factory = cache.factory;

    /** A main schema that includes {@code location} and declares {@code Order} of its type {@code Code}. */
    static String including(String location) {
        return XS + "<xs:include schemaLocation=\"" + location + "\"/><xs:element name=\"Order\" type=\"Code\"/></xs:schema>";
    }

    @Test
    void anAbsolutePathIncludeIsRefusedForTheNextFlow(@TempDir Path a, @TempDir Path b) throws IOException {
        Path first = a.resolve("flow");
        String reference = first.toAbsolutePath().normalize().resolve("sub/types.xsd").toString();   // a path, not a file: URI
        write(first.resolve("main.xsd"), including(reference));
        write(first.resolve("sub/types.xsd"), types(3));
        Path second = b.resolve("flow");
        write(second.resolve("main.xsd"), including(reference));
        write(second.resolve("sub/types.xsd"), types(3));
        assertTrue(cache.accepts(factory.create(step("main.xsd"), cache.resources(first)), "ABC"),
                "fixture: the absolute path lies inside the first flow's directory");
        StepConfig step = step("main.xsd");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> factory.create(step, cache.resources(second)),
                "an include written as an absolute path stays where it is: the next flow's build reads it there and is refused");
        assertTrue(e.getMessage().contains(reference + " leaves the flow's directory"), e.getMessage());
        assertEquals(1, factory.compiledSchemas());
    }

    @Test
    void aSchemaReachedThroughAnAbsoluteIncludeReadsItsOwnIncludesWhereItLies(@TempDir Path root) throws IOException {
        Path inner = root.resolve("inner");   // the first flow's directory, inside the second flow's
        String absolute = inner.toAbsolutePath().normalize().resolve("sub/x.xsd").toUri().toString();
        write(inner.resolve("main.xsd"), including(absolute));
        write(inner.resolve("sub/x.xsd"), XS + "<xs:include schemaLocation=\"types.xsd\"/></xs:schema>");
        write(inner.resolve("sub/types.xsd"), types(3));
        write(root.resolve("main.xsd"), including(absolute));
        write(root.resolve("sub/types.xsd"), types(4));   // where types.xsd would lie had it moved with the main file: never read
        assertTrue(cache.accepts(factory.create(step("main.xsd"), cache.resources(inner)), "ABC"), "fixture");
        Set<Path> fresh = new HashSet<>();
        new XsdValidate().steps().get(0).create(step("main.xsd"), cache.resources(root, fresh));

        Set<Path> hit = new HashSet<>();
        Stage served = factory.create(step("main.xsd"), cache.resources(root, hit));

        assertTrue(cache.accepts(served, "ABC") && !cache.accepts(served, "ABCD"));
        assertEquals(1, factory.compiledSchemas(),
                "the second flow's compile reads the same files, inner/sub/x.xsd and the types.xsd next to it: served from the cache");
        assertEquals(fresh, hit, "the hit reports inner/sub/types.xsd, as the compile does, not sub/types.xsd");
    }

    @Test
    void aReadFromABaseTheRecorderDidNotSeeStaysWhereItWasRead(@TempDir Path a, @TempDir Path b) throws IOException {
        Path first = a.resolve("flow").toAbsolutePath().normalize();
        Path second = b.resolve("flow").toAbsolutePath().normalize();
        write(first.resolve("types.xsd"), types(3));
        write(second.resolve("types.xsd"), types(3));
        CompiledFiles.Recorder reads = new CompiledFiles.Recorder(first.resolve("main.xsd"));
        reads.read(null, "types.xsd", first.resolve("types.xsd"));   // relative, and no base the recorder knows
        CompiledFiles files = reads.held(cache.rt.hasher());
        Set<Path> reported = new HashSet<>();

        assertFalse(files.sameIn(second.resolve("main.xsd"), cache.resources(second, reported)),
                "a read from an unknown base is kept where it was read: in another flow's directory it is outside, not the same");
        assertEquals(Set.of(), reported, "nothing reported on a miss");
        assertTrue(files.sameIn(first.resolve("main.xsd"), cache.resources(first, reported)), "fixture: the same flow, the same file");
        assertEquals(Set.of(first.resolve("types.xsd")), reported);
    }

    /** {@code pkg}'s resources with {@code common} its one dependency (P2-02b), every read reported to {@code reads}. */
    StepResources withCommon(Path pkg, Path common, Set<Path> reads) {
        return new StepResources(cache.rt.clock(), cache.rt.timer(), cache.rt.ids(), cache.rt.blobs(), cache.rt.hasher(), 65536, pkg, reads::add,
                Map.of("common", common));
    }

    /**
     * P2-02b (D-087): a schema two packages name as {@code common:xsd/order.xsd} lies in the one directory of common's pinned version;
     * its include resolves there, not in either package (which has a file of that name too). The second package's compile is served
     * from the cache — the same files, where they lie — and reports what the compile read, so the cached files' check keeps the
     * dependencies of the resources it was given.
     */
    @Test
    void aDependencySchemaIsServedFromTheCacheForTheNextPackage(@TempDir Path root) throws IOException {
        Path common = root.resolve("packages/common/" + "c".repeat(64)).toAbsolutePath().normalize();
        write(common.resolve("xsd/order.xsd"), including("types.xsd"));
        write(common.resolve("xsd/types.xsd"), types(3));
        Path invoice = root.resolve("packages/invoice/" + "1".repeat(64)).toAbsolutePath().normalize();
        Path billing = root.resolve("packages/billing/" + "2".repeat(64)).toAbsolutePath().normalize();
        write(invoice.resolve("xsd/types.xsd"), types(4));   // the include's name at the package root: never read
        write(billing.resolve("xsd/types.xsd"), types(5));
        Set<Path> compiled = new HashSet<>();
        Stage first = factory.create(step("common:xsd/order.xsd"), withCommon(invoice, common, compiled));
        assertTrue(cache.accepts(first, "ABC") && !cache.accepts(first, "ABCD"), "fixture: the include resolves next to the including file, in common");
        assertEquals(Set.of(common.resolve("xsd/order.xsd"), common.resolve("xsd/types.xsd")), compiled, "fixture: the compile read common's two files");

        Set<Path> hit = new HashSet<>();
        Stage served = factory.create(step("common:xsd/order.xsd"), withCommon(billing, common, hit));

        assertTrue(cache.accepts(served, "ABC") && !cache.accepts(served, "ABCDE"), "common's types: three characters");
        assertEquals(1, factory.compiledSchemas(), "the second package's compile reads the same files in common: served from the cache");
        assertEquals(compiled, hit, "the hit reports common's files, as the compile did");
    }
}
