package dev.rail8.schemas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.rail8.schemas.PackageTest.IN;
import static dev.rail8.schemas.PackageTest.PARTNER;
import static dev.rail8.schemas.PackageTest.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-02b (D-087, D-060 e): a package names its dependencies in {@code package.yaml} ({@code dependencies: [{name, version}]}, an
 * exact version, each name once, never the package itself) and pins each in {@code package.lock} at the package root ({@code kind:
 * lock}, {@code dependencies: [{name, version, content, repository, commit}]}, the direct dependencies only). A flow step reads a dependency's file as
 * {@code <dep>:<path from its root>} — the prefix {@code ^[a-z][a-z0-9-]{0,63}:} before the first {@code /} — and only a declared
 * dependency may be named; such a reference stays inside its dependency. {@code Package.problems()} says what is wrong with any of
 * it, by the package-relative file, never an absolute path. {@code Package.content(dir)} is the content digest a lock pins: the
 * SHA-256 over the lines {@code rail8-package-content 1} and {@code file <path> <sha256>} per regular file, sorted by its
 * {@code /}-path — the files, not the archive they came in.
 *
 * <p>The shape assumed (P2-02b test-author, within the packet): {@code public static String Package.content(Path dir) throws
 * IOException} answering {@code sha256:} and 64 lower-case hex digits over those lines, each ended by {@code \n} (as FlowHash ends
 * its lines); {@code Package.load} reads {@code package.lock} when it exists and {@code problems()} reports its schema problems as
 * {@code package.lock at '<pointer>': <message>} (as {@code package.yaml}'s); {@code package.lock} is no document of the walk (not
 * an artifact, no {@code /kind} problem).
 */
class PackageDependenciesTest {

    static final String CONTENT_A = "sha256:" + "a".repeat(64);
    static final String CONTENT_B = "sha256:" + "0123456789abcdef".repeat(4);
    /** The commit {@link #lock} pins every entry at. */
    static final String COMMIT_A = "0123456789abcdef0123456789abcdef01234567";
    /** The line that says a lock without each entry's repository and commit must be written again. */
    static final String WRITE_AGAIN = "package.lock does not pin each dependency's repository and commit: write it again with rail8 lock";

    // --- fixture

    /** {@code package.yaml} of {@code name} 1.0.0; each of {@code deps} is {@code "<name> <version>"}; none: no {@code dependencies} key. */
    static String manifest(String name, String... deps) {
        StringBuilder s = new StringBuilder("schemaVersion: 1\nkind: package\nname: " + name + "\nversion: 1.0.0\n");
        if (deps.length > 0) s.append("dependencies:\n");
        for (String d : deps) {
            String[] nv = d.split(" ", 2);
            s.append("  - name: ").append(nv[0]).append("\n    version: '").append(nv[1]).append("'\n");
        }
        return s.toString();
    }

    /**
     * {@code package.lock} as a person writes it, block style, the content digest unquoted; each of {@code entries} is
     * {@code "<name> <version> <content>"} (the version quoted, so {@code 1.2} stays a string), pinned at the repository of its
     * name and {@link #COMMIT_A}.
     */
    static String lock(String... entries) {
        StringBuilder s = new StringBuilder("schemaVersion: 1\nkind: lock\ndependencies:\n");
        for (String e : entries) {
            String[] nvc = e.split(" ");
            if (nvc.length != 3) throw new IllegalArgumentException("fixture: '<name> <version> <content>', was " + e);
            s.append(entry(nvc[0], nvc[1], nvc[2]));
        }
        return s.toString();
    }

    /** One lock entry, its version as given (single-quoted: any text, spaces included), at the repository of its name and {@link #COMMIT_A}. */
    static String entry(String name, String version, String content) {
        return entry(name, version, content, name, COMMIT_A);
    }

    /** One lock entry, its version, repository and commit as given (single-quoted: any text, spaces included). */
    static String entry(String name, String version, String content, String repository, String commit) {
        return "  - name: " + name + "\n    version: '" + version + "'\n    content: " + content + "\n    repository: '" + repository
                + "'\n    commit: '" + commit + "'\n";
    }

    /**
     * The flow {@code invoice} as a person writes it, block style, the dependency references unquoted: it maps with
     * {@code common:xslt/to-ubl.xsl}, checks against {@code common:xsd/ubl.xsd}, then sends to {@code partner}.
     */
    static final String INVOICE_FLOW = """
            schemaVersion: 1
            kind: flow
            id: invoice
            version: 1.0.0
            from: { endpoint: in }
            delivery: { mode: sync }
            limits: { maxBodyBytes: 1024 }
            steps:
              - type: xslt
                name: map
                stylesheet: common:xslt/to-ubl.xsl
              - type: xsd
                name: check
                schema: common:xsd/ubl.xsd
              - { type: to, name: send, endpoint: partner }
            """;

    /** A flow {@code invoice} whose steps are {@code steps} (YAML flow mappings), then the send to {@code partner}. */
    static String flowWith(String... steps) {
        List<String> all = new ArrayList<>(List.of(steps));
        all.add("{ type: to, name: send, endpoint: partner }");
        return """
                schemaVersion: 1
                kind: flow
                id: invoice
                version: 1.0.0
                from: { endpoint: in }
                delivery: { mode: sync }
                limits: { maxBodyBytes: 1024 }
                steps: [ %s ]
                """.formatted(String.join(", ", all));
    }

    /**
     * {@code invoice} 1.0.0 depending on {@code common 1.2.0}, {@code package.lock} pinning it with {@link #CONTENT_A}, the endpoints
     * {@code in} and {@code partner}, and the flow {@link #INVOICE_FLOW}: valid as it stands.
     */
    static Path invoice(Path dir) throws IOException {
        write(dir, "package.yaml", manifest("invoice", "common 1.2.0"));
        write(dir, "package.lock", lock("common 1.2.0 " + CONTENT_A));
        write(dir, "endpoints/in.yaml", IN);
        write(dir, "endpoints/partner.yaml", PARTNER);
        write(dir, "flows/invoice.yaml", INVOICE_FLOW);
        return dir;
    }

    static List<String> problems(Path dir) throws IOException {
        List<String> problems = Package.load(dir).problems();
        PackageFoldersTest.assertNoAbsolutePath(dir, problems);
        return problems;
    }

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static String sha256(String text) throws Exception {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    // --- the dependency's spelling in YAML

    /**
     * {@code stylesheet: common:xslt/to-ubl.xsl}, unquoted in block style, reads as the plain string (a colon not followed by a space
     * starts no mapping); with {@code common} declared and locked the package has no problem — a declared dependency's file is not
     * looked for in the package root — and {@code package.lock} is no document: not an artifact, no {@code /kind} problem.
     */
    @Test
    void aColonReferenceParsesAsPlainYaml(@TempDir Path dir) throws IOException {
        Package pkg = Package.load(invoice(dir));

        assertEquals("common:xslt/to-ubl.xsl", pkg.flows().get("invoice").getJsonArray("steps").getJsonObject(0).getString("stylesheet"));
        assertEquals("common:xsd/ubl.xsd", pkg.flows().get("invoice").getJsonArray("steps").getJsonObject(1).getString("schema"));
        assertEquals(List.of(), problems(dir), "a declared and locked dependency: nothing to say, its files are the node's to find");
        assertTrue(pkg.artifacts().stream().noneMatch(a -> a.path().startsWith("package.lock")), "package.lock is no artifact: " + pkg.artifacts());

        write(dir, "package.lock", lock("common 1.2.0 sha256:" + "a".repeat(63)));
        assertTrue(problems(dir).stream().anyMatch(l -> l.startsWith("package.lock at '/dependencies/0/content'")),
                "witness: the lock is read and checked against its schema (a digest one digit short): " + problems(dir));
    }

    // --- package.yaml against package.lock

    /**
     * Declared dependencies without a {@code package.lock} are one problem naming them; a lock that lacks a declared dependency names
     * that one. Otherwise — no dependencies, or an empty list — no lock is needed.
     */
    @Test
    void aDependencyWithoutALockIsAProblem(@TempDir Path dir) throws IOException {
        Files.delete(invoice(dir).resolve("package.lock"));

        assertEquals(List.of("package.yaml declares dependencies [common], package.lock is missing"), problems(dir));

        write(dir, "package.yaml", manifest("invoice", "common 1.2.0", "util 2.0.0"));
        write(dir, "package.lock", lock("common 1.2.0 " + CONTENT_A));
        List<String> unpinned = problems(dir);
        assertEquals(1, unpinned.size(), "one problem: the lock does not pin util: " + unpinned);
        assertTrue(unpinned.getFirst().contains("util") && unpinned.getFirst().contains("package.lock") && !unpinned.getFirst().contains("common"),
                "it names util and the lock, not common, which the lock pins: " + unpinned);

        Files.delete(dir.resolve("package.lock"));
        write(dir, "flows/invoice.yaml", PackageTest.flow("invoice", 1024, "partner"));
        write(dir, "package.yaml", manifest("invoice"));
        assertEquals(List.of(), problems(dir), "witness: no dependencies key, no lock, no problem");
        write(dir, "package.yaml", manifest("invoice") + "dependencies: []\n");
        assertEquals(List.of(), problems(dir), "an empty dependencies list needs no lock either");
    }

    /** Each entry pins the dependency's repository and commit beside its name, version and content, and Package reads all five. */
    @Test
    void aLockPinsEachDependencysRepositoryAndCommit(@TempDir Path dir) throws IOException {
        invoice(dir);
        write(dir, "package.lock", "schemaVersion: 1\nkind: lock\ndependencies:\n" + entry("common", "1.2.0", CONTENT_A, "shared-xsl", COMMIT_A));

        assertEquals(List.of(), problems(dir));
        assertEquals(List.of(new Package.Locked("common", "1.2.0", CONTENT_A, "shared-xsl", COMMIT_A)), Package.load(dir).lock());
    }

    /**
     * A lock whose entry lacks its repository, its commit or both is refused: its schema's line, and one line naming
     * {@code rail8 lock}; no entry of it is read.
     */
    @Test
    void aLockWithoutRepositoryOrCommitIsRefusedNamingRail8Lock(@TempDir Path dir) throws IOException {
        invoice(dir);
        String head = "schemaVersion: 1\nkind: lock\ndependencies:\n  - name: common\n    version: '1.2.0'\n    content: " + CONTENT_A + "\n";
        for (String rest : List.of("", "    repository: common\n", "    commit: '" + COMMIT_A + "'\n")) {
            write(dir, "package.lock", head + rest);

            List<String> problems = problems(dir);

            assertTrue(problems.contains(WRITE_AGAIN), "with '" + rest + "': " + problems);
            assertTrue(problems.stream().anyMatch(l -> l.startsWith("package.lock at '/dependencies/0")), "the schema's line too: " + problems);
            assertEquals(List.of(), Package.load(dir).lock(), "no entry of a lock its schema refuses");
        }
        write(dir, "package.lock", lock("common 1.2.0 sha256:" + "a".repeat(63)));
        assertFalse(problems(dir).contains(WRITE_AGAIN), "witness: a lock refused for another reason does not ask for rail8 lock: " + problems(dir));
    }

    /**
     * The repository is one segment of at most 100 letters, digits, {@code .}, {@code _} and {@code -}, not {@code .} or {@code ..};
     * the commit is 40 lower-case hex digits, never a ref. Anything else is refused at the entry's field.
     */
    @Test
    void aRepositoryOrCommitOutsideItsRuleIsRefused(@TempDir Path dir) throws IOException {
        invoice(dir);
        for (String repository : List.of("", ".", "..", "a/b", "a b", "r\u00e9po", "a".repeat(101))) {
            write(dir, "package.lock", "schemaVersion: 1\nkind: lock\ndependencies:\n" + entry("common", "1.2.0", CONTENT_A, repository, COMMIT_A));
            List<String> problems = problems(dir);
            assertTrue(problems.stream().anyMatch(l -> l.startsWith("package.lock at '/dependencies/0/repository'")), "'" + repository + "': " + problems);
        }
        for (String commit : List.of("a".repeat(39), "a".repeat(41), "A".repeat(40), "g".repeat(40), "HEAD", "main")) {
            write(dir, "package.lock", "schemaVersion: 1\nkind: lock\ndependencies:\n" + entry("common", "1.2.0", CONTENT_A, "common", commit));
            List<String> problems = problems(dir);
            assertTrue(problems.stream().anyMatch(l -> l.startsWith("package.lock at '/dependencies/0/commit'")), "'" + commit + "': " + problems);
        }
        for (String repository : List.of("a".repeat(100), "My.Repo_1-x", "...")) {
            write(dir, "package.lock", "schemaVersion: 1\nkind: lock\ndependencies:\n" + entry("common", "1.2.0", CONTENT_A, repository, COMMIT_A));
            assertEquals(List.of(), problems(dir), "witness: '" + repository + "' is a repository's name");
        }
    }

    /** The lock pins another version than package.yaml declares: one line naming the entry, both versions. */
    @Test
    void aLockThatDiffersFromPackageYamlNamesTheEntry(@TempDir Path dir) throws IOException {
        invoice(dir);
        write(dir, "package.yaml", manifest("invoice", "common 1.3.0"));

        assertEquals(List.of("package.lock pins common 1.2.0, package.yaml declares 1.3.0"), problems(dir));
    }

    /** A lock entry package.yaml does not declare is a problem naming it — beside a declared one, and when nothing is declared. */
    @Test
    void aLockEntryNotDeclaredIsAProblem(@TempDir Path dir) throws IOException {
        invoice(dir);
        write(dir, "package.lock", lock("common 1.2.0 " + CONTENT_A, "util 2.0.0 " + CONTENT_B));

        List<String> extra = problems(dir);
        assertEquals(1, extra.size(), "util only: " + extra);
        assertTrue(extra.getFirst().startsWith("package.lock pins util") && extra.getFirst().endsWith(", which package.yaml does not declare"), extra.getFirst());

        write(dir, "flows/invoice.yaml", PackageTest.flow("invoice", 1024, "partner"));
        write(dir, "package.yaml", manifest("invoice"));
        write(dir, "package.lock", lock("common 1.2.0 " + CONTENT_A));
        List<String> undeclared = problems(dir);
        assertEquals(1, undeclared.size(), "nothing declared, common locked: " + undeclared);
        assertTrue(undeclared.getFirst().startsWith("package.lock pins common") && undeclared.getFirst().endsWith(", which package.yaml does not declare"),
                undeclared.getFirst());
    }

    /** A name declared twice is a problem, whether the versions differ or not. */
    @Test
    void aDependencyDeclaredTwiceIsAProblem(@TempDir Path dir) throws IOException {
        invoice(dir);
        for (String second : List.of("common 1.3.0", "common 1.2.0")) {
            write(dir, "package.yaml", manifest("invoice", "common 1.2.0", second));

            List<String> problems = problems(dir);

            assertEquals(1, problems.stream().filter("dependency 'common' is declared twice"::equals).count(), "with " + second + ": " + problems);
        }
    }

    /** A lock naming common twice — the same entry again, or another version — is a problem naming it once; one entry is none. */
    @Test
    void aLockNamingADependencyTwiceIsAProblem(@TempDir Path dir) throws IOException {
        invoice(dir);
        assertEquals(List.of(), problems(dir), "witness: one entry for common is no problem");

        write(dir, "package.lock", lock("common 1.2.0 " + CONTENT_A, "common 1.2.0 " + CONTENT_A));

        assertEquals(List.of("package.lock pins 'common' twice"), problems(dir), "the same entry twice");

        write(dir, "package.lock", lock("common 1.2.0 " + CONTENT_A, "common 1.3.0 " + CONTENT_A));
        List<String> problems = problems(dir);

        assertEquals(1, problems.stream().filter("package.lock pins 'common' twice"::equals).count(), "another version: " + problems);
    }

    /** A package.lock that is not UTF-8 (a byte 0xFF) stops the load as any unreadable document does: a refusal naming package.lock. */
    @Test
    void aLockThatIsNotUtf8IsAProblem(@TempDir Path dir) throws IOException {
        invoice(dir);
        byte[] text = lock("common 1.2.0 " + CONTENT_A).getBytes(StandardCharsets.UTF_8);
        byte[] bytes = java.util.Arrays.copyOf(text, text.length + 1);
        bytes[text.length] = (byte) 0xFF;
        Files.write(dir.resolve("package.lock"), bytes);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> Package.load(dir), "the load is refused, not an I/O failure");

        assertTrue(refused.getMessage().startsWith("package.lock: "), "the refusal names package.lock: " + refused.getMessage());
        assertFalse(refused.getMessage().contains(dir.toString()), "no absolute path: " + refused.getMessage());
    }

    /** A package.lock that does not parse as YAML stops the load: a refusal naming package.lock and the parser's reason. */
    @Test
    void aLockThatIsNotYamlIsAProblem(@TempDir Path dir) throws IOException {
        invoice(dir);
        write(dir, "package.lock", "schemaVersion: 1\nkind: lock\ndependencies: [unclosed\n");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> Package.load(dir));

        assertTrue(refused.getMessage().startsWith("package.lock: not valid YAML: "), "the refusal names package.lock: " + refused.getMessage());
        assertFalse(refused.getMessage().contains(dir.toString()), "no absolute path: " + refused.getMessage());
    }

    /** A package that depends on itself is the shortest cycle, named as the node names one. */
    @Test
    void aPackageDependingOnItselfIsACycle(@TempDir Path dir) throws IOException {
        write(dir, "package.yaml", manifest("invoice", "invoice 1.0.0"));
        write(dir, "package.lock", lock("invoice 1.0.0 " + CONTENT_A));
        write(dir, "endpoints/in.yaml", IN);
        write(dir, "endpoints/partner.yaml", PARTNER);
        write(dir, "flows/invoice.yaml", PackageTest.flow("invoice", 1024, "partner"));

        assertEquals(List.of("dependency cycle: invoice -> invoice"), problems(dir));
    }

    // --- references

    /**
     * A {@code stylesheet} or {@code schema} naming a package package.yaml does not declare is a problem naming the file, the flow,
     * the step, the reference and the package. Otherwise the reference is a plain path from the root: a prefix that is no package
     * name — upper case, a leading digit or dash, an underscore, more than 64 characters, a colon after the first {@code /}, a
     * leading {@code ./} (the way to name a root file whose name has a colon) — is looked for in the package, and is fine when the
     * file is there and "does not exist" when it is not.
     */
    @Test
    void aReferenceToAnUndeclaredPackageIsAProblem(@TempDir Path dir) throws IOException {
        invoice(dir);
        write(dir, "flows/invoice.yaml", flowWith("{ type: xslt, name: map, stylesheet: 'nope:a.xsl' }", "{ type: xsd, name: check, schema: 'nope:x.xsd' }"));

        assertEquals(List.of("flows/invoice.yaml: flow 'invoice' step 'map' stylesheet 'nope:a.xsl' names package 'nope', which package.yaml does not declare",
                "flows/invoice.yaml: flow 'invoice' step 'check' schema 'nope:x.xsd' names package 'nope', which package.yaml does not declare"), problems(dir));

        String tooLong = "a".repeat(65);
        Map<String, String> plain = new LinkedHashMap<>();   // step name -> a reference that is a plain path, its file at that path
        plain.put("upper", "Nope:a.xsl");
        plain.put("digit", "1common:a.xsl");
        plain.put("dash", "-common:a.xsl");
        plain.put("underscore", "co_mmon:a.xsl");
        plain.put("long", tooLong + ":a.xsl");
        plain.put("nested", "sub/common:a.xsl");
        plain.put("dot", "./common:a.xsl");
        List<String> steps = new ArrayList<>();
        for (Map.Entry<String, String> e : plain.entrySet()) {
            steps.add("{ type: xslt, name: " + e.getKey() + ", stylesheet: '" + e.getValue() + "' }");
            write(dir, e.getValue(), "<xsl:stylesheet/>");
        }
        steps.add("{ type: xslt, name: absent, stylesheet: 'Nope:b.xsl' }");
        write(dir, "flows/invoice.yaml", flowWith(steps.toArray(String[]::new)));

        assertEquals(List.of("flows/invoice.yaml: flow 'invoice' step 'absent' stylesheet 'Nope:b.xsl' does not exist"), problems(dir),
                "each of " + plain.values() + " is a file in the package root's tree; Nope:b.xsl is a plain path whose file is missing");
    }

    /**
     * A dependency reference that normalises outside its dependency leaves it, one line each; one that normalises inside it is
     * fine.
     */
    @Test
    void aDependencyReferenceLeavingItIsAProblem(@TempDir Path dir) throws IOException {
        invoice(dir);
        write(dir, "flows/invoice.yaml", flowWith("{ type: xslt, name: up, stylesheet: 'common:../x.xsl' }",
                "{ type: xsd, name: deep, schema: 'common:xsd/../../x.xsd' }", "{ type: xslt, name: root, stylesheet: 'common:/etc/x.xsl' }",
                "{ type: xslt, name: inside, stylesheet: 'common:xslt/../to-ubl.xsl' }"));

        assertEquals(List.of("flows/invoice.yaml: flow 'invoice' step 'up' stylesheet 'common:../x.xsl' leaves package common",
                "flows/invoice.yaml: flow 'invoice' step 'deep' schema 'common:xsd/../../x.xsd' leaves package common",
                "flows/invoice.yaml: flow 'invoice' step 'root' stylesheet 'common:/etc/x.xsl' leaves package common"), problems(dir),
                "common:xslt/../to-ubl.xsl stays inside common: no line");
    }

    // --- exact versions only

    /**
     * A version range is refused in package.yaml and in package.lock, at the entry's version; an exact version with a pre-release
     * part is an exact version.
     */
    @Test
    void aVersionRangeIsRefused(@TempDir Path dir) throws IOException {
        invoice(dir);
        for (String range : List.of("^1.2.0", "~1.2.0", ">=1.2.0", "1.2.x", "1.2", "*", "1.2.0 - 1.3.0", "latest")) {
            write(dir, "package.yaml", manifest("invoice", "common " + range));
            write(dir, "package.lock", lock("common 1.2.0 " + CONTENT_A));
            List<String> declared = problems(dir);
            assertTrue(declared.stream().anyMatch(l -> l.startsWith("package.yaml at '/dependencies/0/version'")), range + " in package.yaml: " + declared);

            write(dir, "package.yaml", manifest("invoice", "common 1.2.0"));
            write(dir, "package.lock", "schemaVersion: 1\nkind: lock\ndependencies:\n" + entry("common", range, CONTENT_A));
            List<String> locked = problems(dir);
            assertTrue(locked.stream().anyMatch(l -> l.startsWith("package.lock at '/dependencies/0/version'")), range + " in package.lock: " + locked);
        }

        write(dir, "package.yaml", manifest("invoice", "common 1.2.0-rc.1"));
        write(dir, "package.lock", lock("common 1.2.0-rc.1 " + CONTENT_A));
        assertEquals(List.of(), problems(dir), "witness: an exact pre-release version is exact");
    }

    // --- the content digest

    /**
     * {@code Package.content(dir)}: {@code sha256:} over {@code rail8-package-content 1} and one {@code file <path> <sha256>} line
     * per regular file, sorted by the whole {@code /}-path ({@code a-b.txt} before {@code a.txt} before {@code a/b.txt}: not a walk
     * that sorts each folder), package.yaml and package.lock included. Two directories holding the same files — created in opposite
     * orders, with other modification times, one with an extra empty folder — have the same digest: the files decide, not how
     * they came.
     */
    @Test
    void theContentDigestIsSortedPathShaLines(@TempDir Path tmp) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();   // in sorted path order
        files.put("a-b.txt", "dash");
        files.put("a.txt", "dot");
        files.put("a/b.txt", "nested");
        files.put("package.lock", lock("util 2.0.0 " + CONTENT_B));
        files.put("package.yaml", manifest("common", "util 2.0.0"));
        files.put("xsd/x.xsd", "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"/>");
        StringBuilder text = new StringBuilder("rail8-package-content 1\n");
        for (Map.Entry<String, String> f : files.entrySet()) text.append("file ").append(f.getKey()).append(' ').append(sha256(f.getValue())).append('\n');
        String expected = "sha256:" + sha256(text.toString());

        Path forward = tmp.resolve("forward");
        List<String> paths = new ArrayList<>(files.keySet());
        for (String p : paths) write(forward, p, files.get(p));
        Path backward = tmp.resolve("backward");
        for (String p : paths.reversed()) {
            Files.setLastModifiedTime(write(backward, p, files.get(p)), FileTime.from(Instant.parse("2001-02-03T04:05:06Z")));
        }
        Files.createDirectories(backward.resolve("empty/deeper"));

        assertEquals(expected, Package.content(forward), "the lines, sorted by path, each file's SHA-256");
        assertEquals(expected, Package.content(backward), "the same files written in the other order, other mtimes, an empty folder: the same digest");

        write(backward, "a/b.txt", "changed");
        assertFalse(expected.equals(Package.content(backward)), "witness: other bytes in one file, another digest");
    }

    /**
     * {@code .git} at the package's root — a clone's folder, or a worktree's file — is not part of the package: the digest and the
     * load are those of the same package without it, whatever it holds. A {@code .git} folder below the root is a folder of the
     * package like any other.
     */
    @Test
    void gitAtThePackageRootIsNotPartOfThePackage(@TempDir Path tmp) throws Exception {
        Path plain = invoice(tmp.resolve("plain"));
        Path clone = invoice(tmp.resolve("clone"));
        write(clone, ".git/HEAD", "ref: refs/heads/main\n");
        write(clone, ".git/objects/pack/a/b/c/deep.yaml", "kind: flow\n");   // too deep, a flow its schema refuses, under a name that is no slug
        write(clone, ".git/config.yml", "no: kind\n");
        Path worktree = invoice(tmp.resolve("worktree"));
        write(worktree, ".git", "gitdir: ../elsewhere\n");

        assertEquals(Package.content(plain), Package.content(clone), "a clone's .git counts for nothing");
        assertEquals(Package.content(plain), Package.content(worktree), "a worktree's .git file counts for nothing");
        assertEquals(List.of(), problems(clone));
        assertEquals(List.of(), problems(worktree));
        assertEquals(Package.load(plain).folders(), Package.load(clone).folders(), "no folder of .git is the package's");

        write(clone, "flows/.git/HEAD", "ref: refs/heads/main\n");
        assertTrue(problems(clone).contains("flows/.git: folder name '.git' is not a slug"), "below the root it is a folder: " + problems(clone));
        assertFalse(Package.content(plain).equals(Package.content(clone)), "and its files count");
    }
}
