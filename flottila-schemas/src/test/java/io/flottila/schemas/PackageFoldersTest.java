package io.flottila.schemas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static io.flottila.schemas.PackageTest.IN;
import static io.flottila.schemas.PackageTest.PACKAGE;
import static io.flottila.schemas.PackageTest.PARTNER;
import static io.flottila.schemas.PackageTest.flow;
import static io.flottila.schemas.PackageTest.write;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-02a (D-078, D-060 d): a package's documents are found by their {@code kind} in any folder — {@code package.yaml} at the root,
 * {@code folder.yaml} in a folder below it, any other {@code *.yaml} / {@code *.yml} an endpoint, a flow or a test by its
 * {@code kind}; a YAML without a known kind is a problem unless a flow or a test names it as a file. Folder names are slugs of at
 * most 64 characters, at most four folders deep; ids are unique per package whatever the folder; one namespace belongs to one
 * folder. A file reference in a flow or test file is a path from the package root and stays inside the package.
 *
 * <p>The shape assumed (P2-02a test-author): {@code Package.folders()} lists every folder below the root as a
 * {@code Package.Folder} with {@code path()} (package-relative, {@code /}-separated: its {@code toString()} is that path),
 * {@code namespace()} and {@code description()} as {@code Optional<String>}; {@code Package.artifacts()} lists every endpoint, flow
 * and test document as a {@code Package.Artifact} with {@code path()}, {@code kind()} (its {@code toString()} the document's kind:
 * {@code endpoint}, {@code flow}, {@code test}) and {@code id()} as {@code Optional<String>} (empty for a test). Both sorted by path;
 * {@code package.yaml} and {@code folder.yaml} are no artifacts.
 */
class PackageFoldersTest {

    static final String XSL = "<xsl:stylesheet xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\" version=\"3.0\"/>";
    static final String XSD = "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"/>";

    // --- fixture

    /** {@code package.yaml} (shop), {@code endpoints/in.yaml} and {@code endpoints/partner.yaml}. */
    static Path base(Path dir) throws IOException {
        write(dir, "package.yaml", PACKAGE);
        write(dir, "endpoints/in.yaml", IN);
        write(dir, "endpoints/partner.yaml", PARTNER);
        return dir;
    }

    /** A schema-valid flow {@code id} from {@code in}: {@code steps} (YAML flow mappings), then the send to {@code partner}. */
    static String flowWith(String id, String... steps) {
        List<String> all = new ArrayList<>(List.of(steps));
        all.add("{ type: to, name: send, endpoint: partner }");
        return """
                schemaVersion: 1
                kind: flow
                id: %s
                version: 1.0.0
                from: { endpoint: in }
                delivery: { mode: sync }
                limits: { maxBodyBytes: 1024 }
                steps: [ %s ]
                """.formatted(id, String.join(", ", all));
    }

    /** A schema-valid test of {@code flow}: the mock {@code partner} replies {@code reply}, one case sends {@code send}. */
    static String testOf(String flow, String send, String reply) {
        return """
                schemaVersion: 1
                kind: test
                flow: %s
                mocks: { partner: { reply: '%s' } }
                cases: [ { name: c, send: '%s', expect: { steps: 1 } } ]
                """.formatted(flow, reply, send);
    }

    static String folderDoc(String namespace) {
        return "schemaVersion: 1\nkind: folder\n" + (namespace == null ? "" : "namespace: \"" + namespace + "\"\n");
    }

    /** Each folder as {@code <path> ns=<namespace|-> desc=<description|->}, in the order listed. */
    static List<String> folders(Package pkg) {
        return pkg.folders().stream().map(f -> f.path() + " ns=" + f.namespace().orElse("-") + " desc=" + f.description().orElse("-")).toList();
    }

    /** Each artifact as {@code <path> <kind> <id|->}, in the order listed. */
    static List<String> artifacts(Package pkg) {
        return pkg.artifacts().stream().map(a -> a.path() + " " + a.kind() + " " + a.id().orElse("-")).toList();
    }

    static void assertNoAbsolutePath(Path dir, List<String> problems) throws IOException {
        for (String absolute : List.of(dir.toAbsolutePath().toString(), dir.toRealPath().toString())) {
            for (String l : problems) assertFalse(l.contains(absolute), "no absolute path (" + absolute + ") in '" + l + "'");
        }
    }

    // --- discovery by kind

    /**
     * An endpoint at the root and one in {@code flows/}, a flow in {@code endpoints/}, a flow in a {@code .yml} file whose name is no
     * slug, two folders deep, a test whose name has no {@code .test.}: each is read by its kind, wherever it lies and whatever it is
     * called (file names are free); a JSON file is no document.
     */
    @Test
    void artifactsInAnyFolderAreFoundByTheirKind(@TempDir Path dir) throws IOException {
        write(dir, "package.yaml", PACKAGE);
        write(dir, "in.yaml", IN);
        write(dir, "flows/partner.yaml", PARTNER);
        write(dir, "endpoints/orders.yaml", flow("orders", 1024, "partner"));
        write(dir, "urn-a/sub/Orders_V1.yml", flow("refunds", 2048, "partner"));
        write(dir, "urn-a/checks.yaml", testOf("orders", "fixtures/Sample Invoice.xml", "fixtures/ack.xml"));
        write(dir, "fixtures/Sample Invoice.xml", "<in/>");
        write(dir, "fixtures/ack.xml", "<ack/>");
        write(dir, "notes/data.json", "{\"kind\":\"flow\"}");
        write(dir, "notes/readme.txt", "notes");

        Package pkg = Package.load(dir);

        assertEquals(List.of(), pkg.problems(), "every document read by the schema of its kind, the test's references found");
        assertEquals(Set.of("in", "partner"), pkg.endpointIds(), "the endpoints at the root and in flows/");
        assertEquals(Set.of("orders", "refunds"), Set.copyOf(pkg.flowIds()), "the flows in endpoints/ and in urn-a/sub/");
        assertTrue(Files.isSameFile(dir.resolve("endpoints/orders.yaml"), pkg.flowFile("orders")), "orders from endpoints/orders.yaml: " + pkg.flowFile("orders"));
        assertTrue(Files.isSameFile(dir.resolve("urn-a/sub/Orders_V1.yml"), pkg.flowFile("refunds")), "refunds from urn-a/sub/Orders_V1.yml: " + pkg.flowFile("refunds"));
        assertEquals(1024L, pkg.flows().get("orders").getJsonObject("limits").getLong("maxBodyBytes"), "the flow document as read");
        assertEquals(List.of("endpoints/orders.yaml flow orders", "flows/partner.yaml endpoint partner", "in.yaml endpoint in",
                "urn-a/checks.yaml test -", "urn-a/sub/Orders_V1.yml flow refunds"), artifacts(pkg),
                "each document by its kind, a test without an id; the JSON file and the fixtures are no artifacts");
    }

    /** The layout of Phase 1 — {@code endpoints/}, {@code flows/} with its test, {@code fixtures/} — loads as it did, its references now from the root. */
    @Test
    void theFlowsAndEndpointsLayoutStillLoads(@TempDir Path dir) throws IOException {
        base(dir);
        write(dir, "flows/orders.yaml", flowWith("orders", "{ type: xslt, name: map, stylesheet: flows/map.xsl }"));
        write(dir, "flows/orders.test.yaml", testOf("orders", "fixtures/in.xml", "fixtures/ack.xml"));
        write(dir, "flows/map.xsl", XSL);
        write(dir, "fixtures/in.xml", "<in/>");
        write(dir, "fixtures/ack.xml", "<ack/>");

        Package pkg = Package.load(dir);

        assertEquals(List.of(), pkg.problems());
        assertEquals("shop", pkg.name());
        assertEquals(Set.of("in", "partner"), pkg.endpointIds());
        assertEquals(List.of("orders"), pkg.flowIds());
        assertTrue(Files.isSameFile(dir.resolve("flows/orders.yaml"), pkg.flowFile("orders")), "orders from flows/orders.yaml: " + pkg.flowFile("orders"));
        assertEquals(List.of("endpoints ns=- desc=-", "fixtures ns=- desc=-", "flows ns=- desc=-"), folders(pkg),
                "every folder below the root, none with a folder.yaml");
        assertEquals(List.of("endpoints/in.yaml endpoint in", "endpoints/partner.yaml endpoint partner", "flows/orders.test.yaml test -",
                "flows/orders.yaml flow orders"), artifacts(pkg));
    }

    // --- layout

    /** Four folders deep is allowed; deeper is one line on the shallowest folder too deep, however deep the chain goes. */
    @Test
    void aFolderFourDeepIsAcceptedAndFiveDeepIsOneProblemNamingIt(@TempDir Path dir) throws IOException {
        Path four = base(dir.resolve("four"));
        write(four, "a/b/c/d/orders.yaml", flow("orders", 1024, "partner"));
        Package fourDeep = Package.load(four);
        assertEquals(List.of(), fourDeep.problems(), "four folders deep");
        assertEquals(List.of("orders"), fourDeep.flowIds(), "the flow four folders deep is read");

        Path five = base(dir.resolve("five"));
        write(five, "a/b/c/d/e/readme.txt", "five");
        assertEquals(List.of("a/b/c/d/e: 5 folders deep, at most 4"), Package.load(five).problems(), "five folders deep");

        Path six = base(dir.resolve("six"));
        write(six, "a/b/c/d/e/f/readme.txt", "six");
        assertEquals(List.of("a/b/c/d/e: 5 folders deep, at most 4"), Package.load(six).problems(),
                "six folders deep: one line, on the shallowest folder too deep");
    }

    static Stream<Arguments> folderNames() {
        return Stream.of(
                Arguments.of("Ns-a", true),
                Arguments.of("ns_a", true),
                Arguments.of("1ns", true),
                Arguments.of("ns.a", true),
                Arguments.of("a" + "b".repeat(64), true),    // 65 characters
                Arguments.of("a" + "b".repeat(63), false));  // 64 characters
    }

    /** A folder whose name is no slug of at most 64 characters is the line {@code <dir>: folder name '<seg>' is not a slug}; 64 is accepted. */
    @ParameterizedTest(name = "{0} refused: {1}")
    @MethodSource("folderNames")
    void aFolderNameThatIsNoSlugIsAProblemNamingItsPath(String name, boolean refused, @TempDir Path dir) throws IOException {
        base(dir);
        write(dir, "urn-a/" + name + "/readme.txt", "x");

        List<String> problems = Package.load(dir).problems();

        assertEquals(refused ? List.of("urn-a/" + name + ": folder name '" + name + "' is not a slug") : List.of(), problems, name.length() + " characters");
    }

    /**
     * Every subset of a path's segments made no slug, one to six folders deep: refused iff a segment is no slug or the path is deeper
     * than four. Within four folders, one line per folder that is no slug, in path order; all slugs and deeper, the one depth line.
     */
    static Stream<Arguments> paths() {
        List<String> noSlugs = List.of("Ns-a", "ns_a", "1ns", "ns.a");
        List<Arguments> rows = new ArrayList<>();
        for (int depth = 1; depth <= 6; depth++) {
            for (int bad = 0; bad < (1 << depth); bad++) {
                List<String> segments = new ArrayList<>();
                for (int i = 0; i < depth; i++) segments.add((bad & (1 << i)) != 0 ? noSlugs.get((i + depth) % noSlugs.size()) : "s" + i);
                rows.add(Arguments.of(String.join("/", segments)));
            }
        }
        return rows.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("paths")
    void aPathIsRefusedIffASegmentIsNoSlugOrDeeperThanFour(String path, @TempDir Path dir) throws IOException {
        base(dir);
        write(dir, path + "/readme.txt", "x");
        String[] segments = path.split("/");
        List<String> notSlugs = new ArrayList<>();
        for (int i = 0; i < segments.length; i++) {
            if (!segments[i].matches("[a-z][a-z0-9-]*")) notSlugs.add(String.join("/", List.of(segments).subList(0, i + 1)) + ": folder name '" + segments[i] + "' is not a slug");
        }
        boolean tooDeep = segments.length > 4;

        List<String> problems = Package.load(dir).problems();

        assertEquals(tooDeep || !notSlugs.isEmpty(), !problems.isEmpty(), "refused iff a segment is no slug or deeper than 4: " + problems);
        if (!tooDeep) assertEquals(notSlugs, problems, "one line per folder that is no slug, in path order");
        if (tooDeep && notSlugs.isEmpty()) assertEquals(List.of(String.join("/", List.of(segments).subList(0, 5)) + ": 5 folders deep, at most 4"), problems);
    }

    // --- ids

    /**
     * Ids are unique per package whatever the folder: a flow id and an endpoint id each declared in two folders by files of the same
     * name are one line each, naming the id and both files by their path; the same file name with another id is no duplicate.
     */
    @Test
    void aDuplicateFlowIdInTwoFoldersNamesBothFiles(@TempDir Path dir) throws IOException {
        write(dir, "package.yaml", PACKAGE);
        write(dir, "endpoints/in.yaml", IN);
        write(dir, "urn-a/partner.yaml", PARTNER);
        write(dir, "urn-b/partner.yaml", PARTNER.replace("/echo", "/echo-b"));
        write(dir, "urn-a/orders.yaml", flow("orders", 1024, "partner"));
        write(dir, "urn-b/orders.yaml", flow("orders", 2048, "partner"));
        write(dir, "urn-c/orders.yaml", flow("refunds", 1024, "partner"));

        List<String> problems = Package.load(dir).problems();

        assertEquals(2, problems.size(), "one line per duplicated id, the same file name with another id is none: " + problems);
        assertTrue(problems.stream().anyMatch(l -> l.contains("'orders'") && l.contains("urn-a/orders.yaml") && l.contains("urn-b/orders.yaml")),
                "the flow id and both files: " + problems);
        assertTrue(problems.stream().anyMatch(l -> l.contains("'partner'") && l.contains("urn-a/partner.yaml") && l.contains("urn-b/partner.yaml")),
                "the endpoint id and both files: " + problems);
        assertTrue(problems.stream().noneMatch(l -> l.contains("urn-c")), "refunds in urn-c/orders.yaml duplicates nothing: " + problems);
    }

    // --- folder.yaml

    /** A namespace is kept as written — spaces, case — up to 1024 characters; a description is kept; a folder.yaml may set neither. */
    @Test
    void folderYamlKeepsTheNamespaceVerbatim(@TempDir Path dir) throws IOException {
        base(dir);
        write(dir, "urn-a/folder.yaml", folderDoc("  urn:Example:Orders/V1  ") + "description: Orders, as PO had them\n");
        String longest = "urn:" + "x".repeat(1020);
        write(dir, "urn-b/folder.yaml", folderDoc(longest));
        write(dir, "urn-c/folder.yaml", folderDoc(null));

        Package pkg = Package.load(dir);

        assertEquals(List.of(), pkg.problems());
        assertEquals(1024, longest.length(), "fixture");
        assertEquals(List.of("endpoints ns=- desc=-", "urn-a ns=  urn:Example:Orders/V1   desc=Orders, as PO had them", "urn-b ns=" + longest + " desc=-",
                "urn-c ns=- desc=-"), folders(pkg));
        Package.Folder a = pkg.folders().stream().filter(f -> f.path().toString().equals("urn-a")).findFirst()
                .orElseThrow(() -> new AssertionError("no folder urn-a: " + folders(pkg)));
        assertEquals(java.util.Optional.of("  urn:Example:Orders/V1  "), a.namespace(), "verbatim: no trim, no case fold");
        assertEquals(java.util.Optional.of("Orders, as PO had them"), a.description());
    }

    /** One namespace in two folders is one line naming both folder.yaml files; namespaces that differ in case or a space are two. */
    @Test
    void oneNamespaceInTwoFoldersIsAProblemNamingBoth(@TempDir Path dir) throws IOException {
        base(dir);
        write(dir, "urn-a/folder.yaml", folderDoc("urn:x"));
        write(dir, "urn-b/v2/folder.yaml", folderDoc("urn:x"));
        write(dir, "case-a/folder.yaml", folderDoc("urn:y"));
        write(dir, "case-b/folder.yaml", folderDoc("URN:Y"));
        write(dir, "space-a/folder.yaml", folderDoc("urn:z"));
        write(dir, "space-b/folder.yaml", folderDoc("urn:z "));

        List<String> problems = Package.load(dir).problems();

        assertEquals(1, problems.size(), "only urn:x is in two folders: " + problems);
        assertTrue(problems.get(0).contains("urn-a/folder.yaml") && problems.get(0).contains("urn-b/v2/folder.yaml"), "names both files: " + problems);
    }

    static Stream<Arguments> misplacedFolderYaml() {
        return Stream.of(Arguments.of("folder.yaml"), Arguments.of("urn-a/meta.yaml"));
    }

    /** {@code folder.yaml} at the root, or {@code kind: folder} in a file of another name, is a problem naming the file; urn-b/folder.yaml is fine. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("misplacedFolderYaml")
    void folderYamlAtTheRootOrUnderAnotherNameIsAProblem(String path, @TempDir Path dir) throws IOException {
        base(dir);
        write(dir, "urn-b/folder.yaml", folderDoc("urn:b"));
        write(dir, path, folderDoc("urn:misplaced"));

        List<String> problems = Package.load(dir).problems();

        assertFalse(problems.isEmpty(), path + " is a problem");
        assertTrue(problems.stream().allMatch(l -> l.contains(path)), "every line names " + path + ": " + problems);
        assertTrue(problems.stream().noneMatch(l -> l.contains("urn-b/folder.yaml")), "a folder.yaml in a folder is fine: " + problems);
    }

    static Stream<Arguments> packageBelowTheRoot() {
        return Stream.of(Arguments.of("urn-a/package.yaml"), Arguments.of("urn-a/manifest.yaml"));
    }

    /** A {@code package.yaml} below the root, or {@code kind: package} below it in a file of another name, is a problem naming the file. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("packageBelowTheRoot")
    void aPackageYamlBelowTheRootIsAProblem(String path, @TempDir Path dir) throws IOException {
        base(dir);
        write(dir, path, PACKAGE.replace("name: shop", "name: other"));

        Package pkg = Package.load(dir);
        List<String> problems = pkg.problems();

        assertEquals("shop", pkg.name(), "the package is the root's");
        assertFalse(problems.isEmpty(), path + " is a problem");
        assertTrue(problems.stream().allMatch(l -> l.contains(path)), "every line names " + path + ": " + problems);
    }

    // --- YAML without a known kind

    static Stream<Arguments> kindless() {
        return Stream.of(
                Arguments.of("urn-a/notes.yaml", "title: notes\n"),
                Arguments.of("urn-a/memo.yml", "kind: memo\ntitle: memo\n"),
                Arguments.of("flows/orders.yaml", flow("orders", 1024, "partner").replace("kind: flow\n", "")));   // a flow that lost its kind
    }

    /** A YAML no flow or test names, whose kind is absent or unknown — a flow that lost its kind included — is {@code <path> at '/kind': …}. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("kindless")
    void aYamlWithoutAKnownKindIsAProblem(String path, String text, @TempDir Path dir) throws IOException {
        base(dir);
        write(dir, path, text);

        List<String> problems = Package.load(dir).problems();

        assertTrue(problems.stream().anyMatch(l -> l.startsWith(path + " at '/kind': ")), "the line '" + path + " at '/kind': …': " + problems);
        assertTrue(problems.stream().allMatch(l -> l.contains(path)), "nothing else is wrong: " + problems);
    }

    /**
     * A YAML a test names as a file — a case's {@code send}, a mock's {@code reply}, a consumer mock's {@code files} — is a plain file:
     * without a kind, with an unknown one, or no mapping at all. Its unreferenced twin is the one problem.
     */
    @Test
    void aReferencedYamlWithoutAKindIsAPlainFile(@TempDir Path dir) throws IOException {
        base(dir);
        write(dir, "flows/orders.yaml", flow("orders", 1024, "partner"));
        write(dir, "flows/orders.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: orders
                mocks: { partner: { reply: fixtures/ack.yaml }, in: { files: feed/order.yml } }
                cases: [ { name: c, send: fixtures/order.yaml, expect: { steps: 1 } } ]
                """);
        write(dir, "fixtures/ack.yaml", "status: ok\n");
        write(dir, "fixtures/order.yaml", "- 4711\n- 4712\n");
        write(dir, "feed/order.yml", "kind: order\nid: 4711\n");
        write(dir, "fixtures/unused.yaml", "status: unused\n");

        Package pkg = Package.load(dir);
        List<String> problems = pkg.problems();

        assertEquals(1, problems.size(), "the unreferenced twin only: " + problems);
        assertTrue(problems.get(0).startsWith("fixtures/unused.yaml at '/kind': "), problems.get(0));
        assertEquals(List.of("endpoints/in.yaml endpoint in", "endpoints/partner.yaml endpoint partner", "flows/orders.test.yaml test -",
                "flows/orders.yaml flow orders"), artifacts(pkg), "a plain file is no artifact");
    }

    /** {@code dir}'s problems, after asserting that loading it throws nothing. */
    static List<String> problemsOf(Path dir) {
        Package pkg = assertDoesNotThrow(() -> Package.load(dir), "the package loads and reports, it does not throw");
        return assertDoesNotThrow(pkg::problems, "the problems are reported, not thrown");
    }

    /** The one problem in {@code problems} is {@code <path> at '/kind': …}. */
    static void assertOnlyKindProblem(String path, List<String> problems) {
        assertEquals(1, problems.size(), "only " + path + ": " + problems);
        assertTrue(problems.get(0).startsWith(path + " at '/kind': "), problems.get(0));
    }

    /**
     * A mock's {@code files} glob names the files of its own folder only: a kind-less YAML whose name matches the glob's last
     * segment but which lies in another folder — a sibling or a folder below the glob's — stays a problem.
     */
    @Test
    void aFilesGlobMakesPlainOnlyTheYamlsOfItsOwnFolder(@TempDir Path dir) throws IOException {
        base(dir);
        write(dir, "flows/orders.yaml", flow("orders", 1024, "partner"));
        write(dir, "flows/orders.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: orders
                mocks: { in: { files: 'feed/*.yml' } }
                cases: [ { name: c, send: fixtures/in.xml, expect: { steps: 1 } } ]
                """);
        write(dir, "fixtures/in.xml", "<in/>");
        write(dir, "feed/order.yml", "id: 4711\n");
        write(dir, "other/order.yml", "id: 4711\n");

        assertOnlyKindProblem("other/order.yml", problemsOf(dir));
    }

    /** A case-level mock's {@code reply} and {@code files} name files as a test-level mock's do; the unreferenced twin stays the problem. */
    @Test
    void aCaseLevelMocksReplyAndFilesMakeAYamlPlain(@TempDir Path dir) throws IOException {
        base(dir);
        write(dir, "flows/orders.yaml", flow("orders", 1024, "partner"));
        write(dir, "flows/orders.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: orders
                cases:
                  - name: c
                    send: fixtures/in.xml
                    mocks: { partner: { reply: fixtures/case-ack.yaml }, in: { files: 'feed/*.yaml' } }
                    expect: { steps: 1 }
                """);
        write(dir, "fixtures/in.xml", "<in/>");
        write(dir, "fixtures/case-ack.yaml", "status: ok\n");
        write(dir, "feed/case.yaml", "id: 4711\n");
        write(dir, "fixtures/case-unused.yaml", "status: unused\n");

        assertOnlyKindProblem("fixtures/case-unused.yaml", problemsOf(dir));
    }

    static Stream<Arguments> schemaInvalid() {
        return Stream.of(
                Arguments.of("flows/orders.yaml", flowWith("orders").replaceFirst("steps: \\[.*]", "steps: \"x\""), "/steps"),
                Arguments.of("flows/orders.test.yaml", testOf("orders", "fixtures/in.xml", "fixtures/in.xml")
                        .replaceFirst("cases: \\[.*]", "cases: \"x\""), "/cases"),
                Arguments.of("flows/orders.test.yaml", testOf("orders", "fixtures/in.xml", "fixtures/in.xml")
                        .replaceFirst("mocks: \\{.*}", "mocks: \"x\""), "/mocks"));
    }

    /**
     * A flow or test that fails its schema — {@code steps}, {@code cases} or {@code mocks} not of their type — next to a kind-less
     * YAML: its schema problems and the kind line, and no exception; the names in a document of the wrong shape are not read.
     */
    @ParameterizedTest(name = "{0} {2}")
    @MethodSource("schemaInvalid")
    void aSchemaInvalidDocumentNextToAKindlessYamlGivesBothProblemsAndNoException(String path, String text, String pointer, @TempDir Path dir) throws IOException {
        base(dir);
        write(dir, path, text);
        write(dir, "fixtures/in.xml", "<in/>");
        write(dir, "fixtures/notes.yaml", "title: notes\n");

        List<String> problems = problemsOf(dir);

        assertTrue(problems.stream().anyMatch(l -> l.startsWith(path + " at '" + pointer + "': ")), "the schema line at " + pointer + ": " + problems);
        assertTrue(problems.stream().anyMatch(l -> l.startsWith("fixtures/notes.yaml at '/kind': ")), "the kind line: " + problems);
        assertTrue(problems.stream().allMatch(l -> l.startsWith(path + " at '") || l.startsWith("fixtures/notes.yaml at '/kind': ")),
                "nothing else: " + problems);
    }

    static Stream<Arguments> namingNothing() {
        return Stream.of(
                Arguments.of("[", "notes.yaml"),                    // no glob at all, at the root
                Arguments.of("feed/[", "feed/notes.yaml"),          // no glob at all, in a folder
                Arguments.of("feed/*.json", "feed/notes.yaml"));    // a glob of the YAML's folder that matches nothing there
    }

    /** A {@code files} value that is no valid glob, or a glob that matches nothing in the YAML's folder, names nothing: the YAML stays a problem, and nothing throws. */
    @ParameterizedTest(name = "files: {0}")
    @MethodSource("namingNothing")
    void aFilesValueThatIsNoGlobOrMatchesNothingLeavesTheYamlAProblem(String files, String yaml, @TempDir Path dir) throws IOException {
        base(dir);
        write(dir, "flows/orders.yaml", flow("orders", 1024, "partner"));
        write(dir, "flows/orders.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: orders
                mocks: { in: { files: '%s' } }
                cases: [ { name: c, send: fixtures/in.xml, expect: { steps: 1 } } ]
                """.formatted(files));
        write(dir, "fixtures/in.xml", "<in/>");
        write(dir, yaml, "title: notes\n");

        assertOnlyKindProblem(yaml, problemsOf(dir));
    }

    /**
     * A mock's {@code messages} glob names files as {@code files} does (decision, P2-02a fix round): at the test level and at the case
     * level, each the files of its own folder; the unreferenced twin stays the problem.
     */
    @Test
    void aMessagesGlobMakesAYamlPlainLikeFiles(@TempDir Path dir) throws IOException {
        base(dir);
        write(dir, "flows/orders.yaml", flow("orders", 1024, "partner"));
        write(dir, "flows/orders.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: orders
                mocks: { in: { messages: 'queue/*.yaml' } }
                cases:
                  - name: c
                    send: fixtures/in.xml
                    mocks: { in: { messages: 'replay/*.yml' } }
                    expect: { steps: 1 }
                """);
        write(dir, "fixtures/in.xml", "<in/>");
        write(dir, "queue/m1.yaml", "id: 4711\n");
        write(dir, "replay/r1.yml", "id: 4712\n");
        write(dir, "other/m2.yaml", "id: 4713\n");

        assertOnlyKindProblem("other/m2.yaml", problemsOf(dir));
    }

    /** A flow's xsd {@code schema} and xslt {@code stylesheet} each name a file as a test does: their kind-less YAMLs are plain, the twin stays the problem. */
    @Test
    void aFlowsSchemaAndStylesheetReferenceMakeAYamlPlain(@TempDir Path dir) throws IOException {
        base(dir);
        write(dir, "flows/orders.yaml", flowWith("orders", "{ type: xsd, name: check, schema: shared/types.yml }",
                "{ type: xslt, name: map, stylesheet: shared/map.yaml }"));
        write(dir, "shared/types.yml", "title: types\n");
        write(dir, "shared/map.yaml", "title: map\n");
        write(dir, "shared/unused.yml", "title: unused\n");

        assertOnlyKindProblem("shared/unused.yml", problemsOf(dir));
    }

    /** A reference that is a path from the root only once normalised — {@code ./}, {@code x/../} — names its file all the same, a {@code files} glob too. */
    @Test
    void aNonNormalisedReferenceMakesItsYamlPlain(@TempDir Path dir) throws IOException {
        base(dir);
        write(dir, "flows/orders.yaml", flow("orders", 1024, "partner"));
        write(dir, "flows/orders.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: orders
                mocks: { partner: { reply: ./fixtures/ack.yaml }, in: { files: './feed/*.yml' } }
                cases: [ { name: c, send: 'flows/../fixtures/order.yaml', expect: { steps: 1 } } ]
                """);
        write(dir, "fixtures/ack.yaml", "status: ok\n");
        write(dir, "fixtures/order.yaml", "id: 4711\n");
        write(dir, "feed/order.yml", "id: 4711\n");
        write(dir, "fixtures/unused.yaml", "status: unused\n");

        assertOnlyKindProblem("fixtures/unused.yaml", problemsOf(dir));
    }

    /**
     * The layout comes first: a folder name that is no slug is listed before a missing stylesheet, though its path sorts after the
     * flow's — the order is by kind of problem, not by path.
     */
    @Test
    void aLayoutProblemIsListedBeforeACrossCheckProblem(@TempDir Path dir) throws IOException {
        base(dir);
        write(dir, "flows/orders.yaml", flowWith("orders", "{ type: xslt, name: map, stylesheet: shared/missing.xsl }"));
        write(dir, "zz_bad/readme.txt", "not a document");

        assertEquals(List.of("zz_bad: folder name 'zz_bad' is not a slug",
                "flows/orders.yaml: flow 'orders' step 'map' stylesheet 'shared/missing.xsl' does not exist"), problemsOf(dir));
    }

    // --- references

    /**
     * A flow's {@code schema} and {@code stylesheet}, a case's {@code send} and a mock's {@code reply} are paths from the package
     * root: found when the file is there, and not found when it lies only next to the file that names it.
     */
    @Test
    void referencesResolveFromThePackageRoot(@TempDir Path dir) throws IOException {
        Path root = base(dir.resolve("root"));
        write(root, "urn-a/orders.yaml", flowWith("orders", "{ type: xsd, name: check, schema: shared/order.xsd }", "{ type: xslt, name: map, stylesheet: shared/map.xsl }"));
        write(root, "urn-a/orders.test.yaml", testOf("orders", "fixtures/in.xml", "fixtures/ack.xml"));
        write(root, "shared/order.xsd", XSD);
        write(root, "shared/map.xsl", XSL);
        write(root, "fixtures/in.xml", "<in/>");
        write(root, "fixtures/ack.xml", "<ack/>");
        assertEquals(List.of(), Package.load(root).problems(), "every file at its path from the root");

        Path beside = base(dir.resolve("beside"));
        write(beside, "urn-a/orders.yaml", flowWith("orders", "{ type: xsd, name: check, schema: order.xsd }", "{ type: xslt, name: map, stylesheet: map.xsl }"));
        write(beside, "urn-a/orders.test.yaml", testOf("orders", "in.xml", "ack.xml"));
        write(beside, "urn-a/order.xsd", XSD);
        write(beside, "urn-a/map.xsl", XSL);
        write(beside, "urn-a/in.xml", "<in/>");
        write(beside, "urn-a/ack.xml", "<ack/>");

        List<String> problems = Package.load(beside).problems();

        assertEquals(4, problems.size(), "each reference, whose file lies only next to the file naming it: " + problems);
        assertTrue(problems.stream().allMatch(l -> l.contains("does not exist")), problems.toString());
        for (List<String> names : List.of(List.of("urn-a/orders.yaml", "'order.xsd'"), List.of("urn-a/orders.yaml", "'map.xsl'"),
                List.of("urn-a/orders.test.yaml", "'in.xml'"), List.of("urn-a/orders.test.yaml", "'ack.xml'"))) {
            assertTrue(problems.stream().anyMatch(l -> names.stream().allMatch(l::contains)), "a line names " + names + ": " + problems);
        }
    }

    /**
     * A reference that normalises outside the package — to a file that exists there — is a problem saying it leaves the package,
     * naming the file and the reference and no absolute path; one that normalises inside it is fine.
     */
    @Test
    void aReferenceLeavingThePackageIsAProblem(@TempDir Path dir) throws IOException {
        Path pkg = base(dir.resolve("pkg"));
        write(dir, "outside.xsd", XSD);
        write(dir, "outside.xsl", XSL);
        write(dir, "outside.xml", "<out/>");
        write(pkg, "urn-a/orders.yaml", flowWith("orders", "{ type: xsd, name: check, schema: 'urn-a/../../outside.xsd' }",
                "{ type: xslt, name: map, stylesheet: ../outside.xsl }"));
        write(pkg, "urn-a/orders.test.yaml", testOf("orders", "../outside.xml", "../outside.xml"));
        write(pkg, "urn-b/refunds.yaml", flowWith("refunds", "{ type: xslt, name: map, stylesheet: urn-b/../shared/map.xsl }"));
        write(pkg, "shared/map.xsl", XSL);

        List<String> problems = Package.load(pkg).problems();

        assertEquals(4, problems.size(), "the schema, the stylesheet, the send and the reply: " + problems);
        assertTrue(problems.stream().allMatch(l -> l.contains("leaves the package")), problems.toString());
        for (List<String> names : List.of(List.of("urn-a/orders.yaml", "'urn-a/../../outside.xsd'"), List.of("urn-a/orders.yaml", "'../outside.xsl'"),
                List.of("urn-a/orders.test.yaml", "send", "'../outside.xml'"), List.of("urn-a/orders.test.yaml", "reply", "'../outside.xml'"))) {
            assertTrue(problems.stream().anyMatch(l -> names.stream().allMatch(l::contains)), "a line names " + names + ": " + problems);
        }
        assertTrue(problems.stream().noneMatch(l -> l.contains("refunds")), "urn-b/../shared/map.xsl stays inside: " + problems);
        assertNoAbsolutePath(dir, problems);
    }

    // --- the tree

    /** Folders (every one below the root, with or without folder.yaml or files of its own) and artifacts, each sorted by path. */
    @Test
    void foldersAndArtifactsAreListedByPath(@TempDir Path dir) throws IOException {
        write(dir, "package.yaml", PACKAGE);
        // written neither in path order nor against it, ids against path order, kinds interleaved
        write(dir, "orders/v2/orders.yaml", flow("orders", 1024, "partner"));
        write(dir, "zeta/audit.yaml", flow("audit", 1024, "partner"));
        write(dir, "billing/eu/in.yaml", IN.replace("id: in", "id: zz-in").replace("8080", "8081"));
        write(dir, "orders/orders.test.yaml", testOf("orders", "billing/eu/sample.xml", "billing/eu/sample.xml"));
        write(dir, "billing/partner.yaml", PARTNER);
        write(dir, "billing/eu/sample.xml", "<in/>");
        write(dir, "orders/folder.yaml", folderDoc("urn:orders") + "description: orders\n");
        write(dir, "billing/eu/folder.yaml", folderDoc("urn:billing:eu"));
        write(dir, "endpoints/in.yaml", IN);

        Package pkg = Package.load(dir);

        assertEquals(List.of(), pkg.problems());
        assertEquals(List.of("billing ns=- desc=-", "billing/eu ns=urn:billing:eu desc=-", "endpoints ns=- desc=-", "orders ns=urn:orders desc=orders",
                "orders/v2 ns=- desc=-", "zeta ns=- desc=-"), folders(pkg));
        assertEquals(List.of("billing/eu/in.yaml endpoint zz-in", "billing/partner.yaml endpoint partner", "endpoints/in.yaml endpoint in",
                "orders/orders.test.yaml test -", "orders/v2/orders.yaml flow orders", "zeta/audit.yaml flow audit"), artifacts(pkg));
    }
}
