package io.flottila.cli;

import io.flottila.cli.CliRuns.Ran;
import io.flottila.cli.DepsFixture.Pin;
import io.flottila.schemas.Documents;
import io.flottila.schemas.Package;
import io.flottila.schemas.Schemas;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.flottila.cli.CliRuns.run;
import static io.flottila.cli.DepsFixture.assertNoAbsolutePath;
import static io.flottila.cli.DepsFixture.copy;
import static io.flottila.cli.DepsFixture.pinOf;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-02b2 (D-087, D-089): {@code flottila lock <dir> [--deps <dir>]} writes {@code package.lock} off-node from a candidates
 * directory — the immediate subdirectories of {@code --deps} (default: the package directory's parent) holding a
 * {@code package.yaml} that loads, the package's own directory excluded. Every dependency package.yaml declares is matched by
 * name and version (from the candidate's package.yaml, not its directory name) and pinned with the candidate's content digest;
 * the entries sorted by name; one stdout line {@code name@version sha256:<hex>} per entry; exit 0. A missing candidate or two
 * matching candidates with different content: exit 1, nothing written, an existing lock untouched. Usage errors are
 * CliEdgeCasesTest's.
 *
 * <p>Format assumed where the packet quotes no prefix (P2-02b2 test-author): a lock refusal is a stdout line, either the message
 * itself or {@code problem: } and the message; {@code <deps>} in {@code no candidate under <deps>} is free text that is no
 * absolute path. Digests are {@link DepsFixture#content}'s.
 */
class LockCommandTest {

    @TempDir Path tmp;

    /** The stdout line that is {@code message}, or {@code problem: } and it; fails naming the run otherwise. */
    static String reported(Ran ran, java.util.function.Predicate<String> message) {
        return ran.outLines().stream().map(l -> l.startsWith("problem: ") ? l.substring("problem: ".length()) : l).filter(message)
                .findFirst().orElseThrow(() -> new AssertionError("no such line on stdout: " + ran));
    }

    static void assertNoLock(Path pkg, Ran ran) {
        assertFalse(Files.exists(pkg.resolve("package.lock")), "nothing written: " + ran);
    }

    // --- writing the lock

    /**
     * The default candidates dir is the package's parent: common 1.2.0 is pinned, common 1.3.0 next to it does not match (version),
     * the package itself is no candidate; the lock overwrites a stale one, round-trips through Package and passes its schema.
     */
    @Test
    void writesTheLockFromTheParentDir() throws Exception {
        Path deps = tmp.resolve("deps");
        Path invoice = DepsFixture.invoiceWithCandidates(deps);
        DepsFixture.lock(invoice, List.of(new Pin("common", "1.2.0", "sha256:" + "0".repeat(64))));   // stale: overwritten
        Pin common = pinOf(deps.resolve("common"));

        Ran ran = run("lock", invoice.toString());

        assertEquals(0, ran.exit(), ran.toString());
        assertEquals(List.of(common.line()), ran.outLines(), ran.toString());
        assertEquals(List.of(new Package.Locked("common", "1.2.0", common.content())), Package.load(invoice).lock(), "the lock as Package reads it");
        assertLockSchema(invoice, 1);
    }

    /** {@code --deps} names the candidates dir instead of the parent, whose own common 1.2.0 (other content) is not used; matched by package.yaml's name, not the dir name. */
    @Test
    void writesTheLockFromDepsOption() throws Exception {
        Path invoice = copy("invoice", tmp.resolve("work/invoice"));
        Path decoy = copy("common", tmp.resolve("work/common"));
        DepsFixture.relabel(decoy, "a decoy");
        Path shared = copy("common", tmp.resolve("vendor/shared-xsl"));
        Pin pinned = pinOf(shared);
        assertFalse(pinned.content().equals(pinOf(decoy).content()), "fixture: the decoy has other content");

        Ran ran = run("lock", invoice.toString(), "--deps", tmp.resolve("vendor").toString());

        assertEquals(0, ran.exit(), ran.toString());
        assertEquals(List.of(pinned.line()), ran.outLines(), ran.toString());
        assertEquals(List.of(new Package.Locked("common", "1.2.0", pinned.content())), Package.load(invoice).lock());
    }

    /** What lock writes, validate accepts: invoice validates off-node from the same candidates (no problem line, exit 0). */
    @Test
    void theWrittenLockRoundTripsAndValidates() throws Exception {
        Path deps = tmp.resolve("deps");
        Path invoice = DepsFixture.invoiceWithCandidates(deps);
        Files.delete(invoice.resolve("package.lock"));

        Ran locked = run("lock", invoice.toString());
        Ran validated = run("validate", invoice.toString());

        assertEquals(0, locked.exit(), locked.toString());
        assertEquals(List.of(), Package.load(invoice).problems(), "Package accepts the written lock");
        assertEquals(List.of(new Package.Locked("common", "1.2.0", pinOf(deps.resolve("common")).content())), Package.load(invoice).lock());
        assertEquals(List.of(), validated.problems(), validated.toString());
        assertEquals(0, validated.exit(), validated.toString());
    }

    /** Direct dependencies sorted by name in the file and in the printed lines, whatever package.yaml's order. */
    @Test
    void entriesAreSortedByName() throws Exception {
        Path deps = tmp.resolve("deps");
        Path invoice = DepsFixture.invoiceWithCandidates(deps);
        Path zeta = copy("common", deps.resolve("zeta"));
        DepsFixture.manifest(zeta, "zeta", "1.0.0");
        Path alpha = copy("common", deps.resolve("alpha"));
        DepsFixture.manifest(alpha, "alpha", "2.0.0");
        DepsFixture.relabel(alpha, "alpha");
        DepsFixture.manifest(invoice, "invoice", "1.0.0", "zeta@1.0.0", "common@1.2.0", "alpha@2.0.0");
        Pin a = pinOf(alpha);
        Pin c = pinOf(deps.resolve("common"));
        Pin z = pinOf(zeta);

        Ran ran = run("lock", invoice.toString());

        assertEquals(0, ran.exit(), ran.toString());
        assertEquals(List.of(a.line(), c.line(), z.line()), ran.outLines(), ran.toString());
        assertEquals(List.of(new Package.Locked("alpha", "2.0.0", a.content()), new Package.Locked("common", "1.2.0", c.content()),
                new Package.Locked("zeta", "1.0.0", z.content())), Package.load(invoice).lock(), "sorted by name in the file");
        assertLockSchema(invoice, 3);
    }

    /**
     * Package names the package schema lets through that are YAML keywords ({@code null}, {@code true}; quoted in their
     * package.yaml): the written lock passes its schema and Package reads both names back as the strings, sorted by name.
     */
    @Test
    void namesThatAreYamlKeywordsRoundTripAsStrings() throws Exception {
        Path deps = tmp.resolve("deps");
        Path nul = copy("common", deps.resolve("null-pkg"));
        Files.writeString(nul.resolve("package.yaml"), "schemaVersion: 1\nkind: package\nname: \"null\"\nversion: 1.0.0\ndependencies: []\n");
        Path tru = copy("common", deps.resolve("true-pkg"));
        Files.writeString(tru.resolve("package.yaml"), "schemaVersion: 1\nkind: package\nname: \"true\"\nversion: 1.0.0\ndependencies: []\n");
        DepsFixture.relabel(tru, "true 1.0.0");
        Path app = copy("common", deps.resolve("app"));
        Files.writeString(app.resolve("package.yaml"), "schemaVersion: 1\nkind: package\nname: app\nversion: 1.0.0\ndependencies:\n"
                + "  - { name: \"true\", version: 1.0.0 }\n  - { name: \"null\", version: 1.0.0 }\n");
        List<String> before = Package.load(app).problems();   // the lock is missing, nothing else
        assertTrue(before.stream().noneMatch(p -> p.startsWith("package.yaml at ")), "fixture: the quoted keyword names pass package.yaml's schema: " + before);
        Pin n = pinOf(nul);
        Pin t = pinOf(tru);
        assertEquals(List.of("null", "true"), List.of(n.name(), t.name()), "fixture: the names are strings");

        Ran ran = run("lock", app.toString());

        assertEquals(0, ran.exit(), ran.toString());
        assertEquals(List.of(n.line(), t.line()), ran.outLines(), ran.toString());
        assertLockSchema(app, 2);
        assertEquals(List.of(), Package.load(app).problems(), "Package accepts the written lock: " + Files.readString(app.resolve("package.lock")));
        assertEquals(List.of(new Package.Locked("null", "1.0.0", n.content()), new Package.Locked("true", "1.0.0", t.content())),
                Package.load(app).lock(), "the names read back as the strings");
    }

    /** No dependencies: a lock with an empty list, no line printed, exit 0. */
    @Test
    void noDependenciesWritesAnEmptyLock() throws Exception {
        Path common = copy("common", tmp.resolve("deps/common"));

        Ran ran = run("lock", common.toString());

        assertEquals(0, ran.exit(), ran.toString());
        assertEquals(List.of(), ran.outLines(), ran.toString());
        assertLockSchema(common, 0);
        assertEquals(List.of(), Package.load(common).problems());
    }

    /** Subdirectories whose package.yaml does not load, or that hold none, a plain file, a package one level deeper: none is a candidate, none stops the lock. */
    @Test
    void onlyImmediateSubdirectoriesWithALoadablePackageYamlAreCandidates() throws Exception {
        Path deps = tmp.resolve("deps");
        Path invoice = DepsFixture.invoiceWithCandidates(deps);
        Files.createDirectories(deps.resolve("broken"));
        Files.writeString(deps.resolve("broken/package.yaml"), "name: [common\nversion: {");
        Files.createDirectories(deps.resolve("notes"));
        Files.writeString(deps.resolve("notes/readme.txt"), "not a package");
        Files.writeString(deps.resolve("package.yaml.txt"), "a file, not a directory");
        Path nested = copy("common", deps.resolve("vendor/common"));
        DepsFixture.relabel(nested, "nested");   // were it a candidate: two common 1.2.0 with different content
        Pin common = pinOf(deps.resolve("common"));

        Ran ran = run("lock", invoice.toString());

        assertEquals(0, ran.exit(), ran.toString());
        assertEquals(List.of(common.line()), ran.outLines(), ran.toString());
    }

    /** Two candidates that match with the same content are one: the lock pins it (only different content is ambiguous). */
    @Test
    void twoCandidatesWithTheSameContentAreOne() throws Exception {
        Path invoice = copy("invoice", tmp.resolve("deps/invoice"));
        Pin left = pinOf(copy("common", tmp.resolve("deps/left")));
        copy("common", tmp.resolve("deps/right"));

        Ran ran = run("lock", invoice.toString());

        assertEquals(0, ran.exit(), ran.toString());
        assertEquals(List.of(left.line()), ran.outLines(), ran.toString());
    }

    // --- refused: exit 1, nothing written

    /** common 1.3.0 only, or no common at all: exit 1 naming what the candidates hold, no lock written, no absolute path. */
    @Test
    void aMissingCandidateIsExit1NamingWhatExistsAndWritesNothing() throws Exception {
        Path other = copy("invoice", tmp.resolve("other/invoice"));
        copy("common-1.3.0", tmp.resolve("other/common-1.3.0"));
        Path none = copy("invoice", tmp.resolve("none/invoice"));

        Ran ranOther = run("lock", other.toString());
        Ran ranNone = run("lock", none.toString());

        assertEquals(1, ranOther.exit(), ranOther.toString());
        reported(ranOther, l -> l.startsWith("dependency common@1.2.0: no candidate under ") && l.endsWith(" (it has common 1.3.0)"));
        assertNoLock(other, ranOther);
        assertEquals(1, ranNone.exit(), ranNone.toString());
        reported(ranNone, l -> l.startsWith("dependency common@1.2.0: no candidate under ") && l.endsWith(" (no package common)"));
        assertNoLock(none, ranNone);
        assertNoAbsolutePath(ranOther, tmp);
        assertNoAbsolutePath(ranNone, tmp);
    }

    /** One of two dependencies resolves, the last (by name) does not: the existing lock is untouched, byte for byte. */
    @Test
    void aLockIsWrittenOnlyWhenEveryEntryResolves() throws Exception {
        Path deps = tmp.resolve("deps");
        Path invoice = DepsFixture.invoiceWithCandidates(deps);
        DepsFixture.manifest(invoice, "invoice", "1.0.0", "common@1.2.0", "zeta@1.0.0");
        byte[] before = Files.readAllBytes(invoice.resolve("package.lock"));

        Ran ran = run("lock", invoice.toString());

        assertEquals(1, ran.exit(), ran.toString());
        reported(ran, l -> l.startsWith("dependency zeta@1.0.0: no candidate under ") && l.endsWith(" (no package zeta)"));
        assertArrayEquals(before, Files.readAllBytes(invoice.resolve("package.lock")), "the previous lock untouched: " + ran);
    }

    /** common 1.2.0 in two directories with different content: exit 1 naming both directories relative to the deps dir; nothing written. */
    @Test
    void twoCandidatesWithDifferentContentIsExit1NamingBoth() throws Exception {
        Path invoice = copy("invoice", tmp.resolve("deps/invoice"));
        copy("common", tmp.resolve("deps/left"));
        DepsFixture.relabel(copy("common", tmp.resolve("deps/right")), "built elsewhere");

        Ran ran = run("lock", invoice.toString());

        assertEquals(1, ran.exit(), ran.toString());
        reported(ran, l -> l.contains("common@1.2.0") && l.contains("left") && l.contains("right"));
        assertNoLock(invoice, ran);
        assertNoAbsolutePath(ran, tmp);
    }

    /** The package's own directory is no candidate: a package declaring itself finds nothing to pin, writes nothing. */
    @Test
    void thePackageItselfIsNoCandidate() throws Exception {
        Path self = copy("common", tmp.resolve("deps/common"));
        DepsFixture.manifest(self, "common", "1.2.0", "common@1.2.0");

        Ran ran = run("lock", self.toString());

        assertEquals(1, ran.exit(), ran.toString());
        assertNoLock(self, ran);
    }

    /**
     * A file of the package that does not parse (its dependency would resolve): exit 1, one stdout line, the problem naming the file
     * by its path from the package root, no pin printed, the existing lock untouched byte for byte, no absolute path.
     */
    @Test
    void aFileThatDoesNotParseIsExit1AndLeavesTheLockUntouched() throws Exception {
        Path invoice = DepsFixture.invoiceWithCandidates(tmp.resolve("deps"));
        Files.writeString(invoice.resolve("flows/broken.yaml"), "kind: [flow\n");
        byte[] before = Files.readAllBytes(invoice.resolve("package.lock"));

        Ran ran = run("lock", invoice.toString());

        assertEquals(1, ran.exit(), ran.toString());
        assertEquals(1, ran.outLines().size(), "the problem is the one stdout line: " + ran);
        assertTrue(ran.outLines().getFirst().startsWith("problem: flows/broken.yaml"), ran.toString());
        assertArrayEquals(before, Files.readAllBytes(invoice.resolve("package.lock")), "the previous lock untouched: " + ran);
        assertNoAbsolutePath(ran, tmp);
    }

    /**
     * A package.yaml that fails its schema (version {@code 1.0}; its dependency common 1.2.0 would resolve): exit 1, every stdout
     * line a {@code problem: package.yaml at '/version'} line, no pin printed, the existing lock untouched byte for byte.
     */
    @Test
    void aManifestFailingItsSchemaIsExit1AndLeavesTheLockUntouched() throws Exception {
        Path invoice = DepsFixture.invoiceWithCandidates(tmp.resolve("deps"));
        DepsFixture.manifest(invoice, "invoice", "1.0", "common@1.2.0");
        byte[] before = Files.readAllBytes(invoice.resolve("package.lock"));

        Ran ran = run("lock", invoice.toString());

        assertEquals(1, ran.exit(), ran.toString());
        assertFalse(ran.outLines().isEmpty(), ran.toString());
        assertTrue(ran.outLines().stream().allMatch(l -> l.startsWith("problem: package.yaml at '/version': ")), ran.toString());
        assertArrayEquals(before, Files.readAllBytes(invoice.resolve("package.lock")), "the previous lock untouched: " + ran);
    }

    /**
     * FIX-110: a {@code --deps} folder that cannot be listed ({@link LongPaths#exactlyPathMax}: reachable relatively, never by its
     * absolute path) is the one line {@code problem: cannot list vendor-deps} however many dependencies are looked up — invoice
     * declares two; exit 1, nothing written. Witness that both are looked up: the same invoice against an empty, listable folder is
     * one {@code no candidate} line for each.
     */
    @Test
    void anUnlistableDepsFolderIsOneLineForTwoDependencies() throws Exception {
        Path invoice = copy("invoice", tmp.resolve("work/invoice"));
        DepsFixture.manifest(invoice, "invoice", "1.0.0", "common@1.2.0", "zeta@1.0.0");
        Path empty = Files.createDirectories(tmp.resolve("empty-deps"));

        Ran listable = run("lock", invoice.toString(), "--deps", empty.toString());

        assertEquals(1, listable.exit(), listable.toString());
        reported(listable, l -> l.startsWith("dependency common@1.2.0: no candidate under "));
        reported(listable, l -> l.startsWith("dependency zeta@1.0.0: no candidate under "));
        assertNoLock(invoice, listable);

        Path top = Path.of("target", "fix110-lock-deps-" + System.nanoTime());
        List<String> below = LongPaths.exactlyPathMax(top, "vendor-deps");
        Path stage = Path.of("target", "fix110-lock-stage-" + System.nanoTime());
        LongPaths.chain(top, below, stage);
        try {
            Path vendor = top;
            for (String name : below) vendor = vendor.resolve(name);
            assertTrue(Files.isDirectory(vendor), "fixture witness: the folder is reachable relative to the working directory");
            assertFalse(Files.isDirectory(vendor.toAbsolutePath()), "fixture witness: its absolute path is refused");

            Ran ran = run("lock", invoice.toString(), "--deps", vendor.toString());

            assertEquals(List.of("problem: cannot list vendor-deps"), ran.outLines(), ran.toString());
            assertEquals(1, ran.exit(), ran.toString());
            assertNoLock(invoice, ran);
        } finally {
            LongPaths.unchain(top, below, stage);
        }
    }

    /** {@code --deps} that is not a directory: usage, exit 2, nothing on stdout, nothing written. */
    @Test
    void depsNotADirIsUsage2() throws Exception {
        Path invoice = DepsFixture.invoiceWithCandidates(tmp.resolve("deps"));
        Files.delete(invoice.resolve("package.lock"));
        Files.writeString(tmp.resolve("plain-file"), "x");

        for (Path notADir : List.of(tmp.resolve("no-such-dir"), tmp.resolve("plain-file"))) {
            Ran ran = run("lock", invoice.toString(), "--deps", notADir.toString());

            assertEquals(2, ran.exit(), ran.toString());
            assertEquals("", ran.out(), ran.toString());
            assertTrue(ran.err().startsWith("flottila: "), ran.toString());
            assertNoLock(invoice, ran);
        }
    }

    /** {@code dir/package.lock} parses, passes Schemas.LOCK ({@code kind: lock}) and holds {@code entries} entries. */
    static void assertLockSchema(Path dir, int entries) throws IOException {
        Path lock = dir.resolve("package.lock");
        assertTrue(Files.isRegularFile(lock), "package.lock written in " + dir);
        JsonObject doc = Documents.yaml(Files.readString(lock));
        Schemas.Report report = Schemas.LOCK.validate(doc);
        assertTrue(report.valid(), "the written lock passes its schema: " + report + "\n" + doc.encodePrettily());
        assertEquals("lock", doc.getString("kind"));
        assertEquals(entries, doc.getJsonArray("dependencies").size(), doc.encodePrettily());
    }
}
