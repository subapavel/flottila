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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-09d1a (D-059): {@link Package#problems()} is everything a deploy refuses a package for before any component is asked:
 * each document against its kind's schema ({@code <file> at '<pointer>': <message>}), then the endpoint and flow ids that
 * two files declare (load keeps only one of them), then {@link Package#crossCheck()}.
 */
class PackageTest {

    static final String PACKAGE = "schemaVersion: 1\nkind: package\nname: shop\nversion: 1.0.0\n";
    static final String IN = "schemaVersion: 1\nkind: endpoint\nid: in\nscheme: http\ndirection: in\nconfig: { port: 8080 }\n";
    static final String PARTNER = "schemaVersion: 1\nkind: endpoint\nid: partner\nscheme: http\ndirection: out\nconfig: { url: http://localhost:8090/echo }\n";

    static String flow(String id, long maxBodyBytes, String toEndpoint) {
        return """
                schemaVersion: 1
                kind: flow
                id: %s
                version: 1.0.0
                from: { endpoint: in }
                delivery: { mode: sync }
                limits: { maxBodyBytes: %d }
                steps: [ { type: to, name: send, endpoint: %s } ]
                """.formatted(id, maxBodyBytes, toEndpoint);
    }

    static Path write(Path dir, String relative, String text) throws IOException {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    /** What the validator says about {@code file}, in the form problems() reports it: {@code <file name> at '<pointer>': <message>}. */
    static List<String> schemaLines(Path file) {
        Schemas.Report r = Schemas.forKind(Documents.read(file).getString("kind")).validate(Documents.read(file));
        assertFalse(r.valid(), "fixture: " + file.getFileName() + " violates its schema");
        List<String> lines = new ArrayList<>();
        for (Schemas.Problem p : r.problems()) lines.add(file.getFileName() + " at '" + p.path() + "': " + p.message());
        return lines;
    }

    /**
     * One file per kind breaks its schema, each at its own pointer (the flow's two levels deep), an endpoint id is declared twice
     * and a second, schema-valid flow sends to an endpoint that is not there (crossCheck() skips documents their schema refuses):
     * the schema problems come first, each naming its file and pointer, then the duplicate, then exactly what crossCheck() says.
     */
    @Test
    void problemsNameFileAndPointerOfASchemaViolation(@TempDir Path dir) throws IOException {
        List<Path> broken = List.of(
                write(dir, "package.yaml", "schemaVersion: 1\nkind: package\nname: shop\nversion: one\n"),
                write(dir, "endpoints/partner.yaml", PARTNER.replace("direction: out", "direction: sideways")),
                write(dir, "flows/orders.yaml", flow("orders", 0, "partner")),
                write(dir, "flows/orders.test.yaml", """
                        schemaVersion: 1
                        kind: test
                        flow: orders
                        cases: [ { name: c, send: fixtures/in.xml, expect: { steps: many } } ]
                        """));
        write(dir, "endpoints/in.yaml", IN);
        write(dir, "endpoints/partner-copy.yaml", PARTNER);
        write(dir, "fixtures/in.xml", "<in/>");
        write(dir, "flows/audit.yaml", flow("audit", 1024, "nowhere"));   // schema-valid: crossCheck() looks at it

        List<String> expected = new ArrayList<>();
        for (Path f : broken) expected.addAll(schemaLines(f));
        assertTrue(expected.contains("orders.yaml at '/limits/maxBodyBytes': 0 is less than 1"), "fixture: the flow's violation is two levels deep: " + expected);
        assertTrue(expected.stream().anyMatch(l -> l.startsWith("package.yaml at '/version'")), "fixture: " + expected);
        assertTrue(expected.stream().anyMatch(l -> l.startsWith("partner.yaml at '/direction'")), "fixture: " + expected);
        assertTrue(expected.stream().anyMatch(l -> l.startsWith("orders.test.yaml at '/cases/0/expect/steps'")), "fixture: " + expected);

        Package pkg = Package.load(dir);
        List<String> crossCheck = pkg.crossCheck();
        assertTrue(crossCheck.stream().anyMatch(p -> p.contains("nowhere")), "fixture: the dangling endpoint is a cross-check problem: " + crossCheck);

        List<String> problems = pkg.problems();
        String all = String.join("\n", problems);
        assertEquals(expected.size() + 1 + crossCheck.size(), problems.size(), "every schema problem, one duplicate, the cross-check:\n" + all);
        List<String> unmatched = new ArrayList<>(expected);
        for (String p : problems.subList(0, expected.size())) {
            String suffix = unmatched.stream().filter(p::endsWith).findFirst().orElse(null);
            assertTrue(suffix != null, "a schema problem '<file> at '<pointer>': <message>' first, was '" + p + "'; expected one of " + unmatched + "\n" + all);
            unmatched.remove(suffix);
        }
        assertEquals(List.of(), unmatched, "every schema problem of every document is reported:\n" + all);
        assertDuplicate(problems.get(expected.size()), "partner", "partner.yaml", "partner-copy.yaml");
        assertEquals(crossCheck, problems.subList(expected.size() + 1, problems.size()), "crossCheck() comes last, as it is");
    }

    @Test
    void aDuplicateFlowIdIsAProblem(@TempDir Path dir) throws IOException {
        write(dir, "package.yaml", PACKAGE);
        write(dir, "endpoints/in.yaml", IN);
        write(dir, "endpoints/partner.yaml", PARTNER);
        write(dir, "flows/a.yaml", flow("orders", 1024, "partner"));
        write(dir, "flows/b.yaml", flow("orders", 2048, "partner"));
        Package pkg = Package.load(dir);
        assertEquals(List.of(), pkg.crossCheck(), "fixture: every file is valid and consistent but for the duplicate");
        List<String> problems = pkg.problems();
        assertEquals(1, problems.size(), "the duplicate and nothing else: " + problems);
        assertDuplicate(problems.get(0), "orders", "a.yaml", "b.yaml");
    }

    @Test
    void aDuplicateEndpointIdIsAProblem(@TempDir Path dir) throws IOException {
        write(dir, "package.yaml", PACKAGE);
        write(dir, "endpoints/in.yaml", IN);
        write(dir, "endpoints/a.yaml", PARTNER);
        write(dir, "endpoints/b.yaml", PARTNER.replace("/echo", "/echo-foreign"));
        write(dir, "flows/orders.yaml", flow("orders", 1024, "partner"));
        Package pkg = Package.load(dir);
        assertEquals(List.of(), pkg.crossCheck(), "fixture: every file is valid and consistent but for the duplicate");
        List<String> problems = pkg.problems();
        assertEquals(1, problems.size(), "the duplicate and nothing else: " + problems);
        assertDuplicate(problems.get(0), "partner", "a.yaml", "b.yaml");
    }

    /**
     * Round 2 (decided by the architect): crossCheck() runs only over documents that passed their schema. A flow whose {@code from}
     * is no object is a schema problem naming its file — never a ClassCastException — and its steps are not cross-checked: the
     * step's endpoint that is not in the package (the witness that the skip happened) adds nothing.
     */
    @Test
    void aFlowTheSchemaRefusesGivesProblemsNotAnException(@TempDir Path dir) throws IOException {
        write(dir, "package.yaml", PACKAGE);
        write(dir, "endpoints/in.yaml", IN);
        write(dir, "endpoints/partner.yaml", PARTNER);
        Path refused = write(dir, "flows/orders.yaml", flow("orders", 1024, "nowhere").replace("from: { endpoint: in }", "from: x"));
        List<String> expected = schemaLines(refused);
        assertTrue(expected.stream().anyMatch(l -> l.startsWith("orders.yaml at '/from'")), "fixture: the schema refuses 'from: x': " + expected);
        Package pkg = Package.load(dir);

        List<String> problems = assertDoesNotThrow(pkg::problems, "a document the schema refuses is a problem, not an exception");

        String all = String.join("\n", problems);
        assertEquals(expected.size(), problems.size(), "the flow's schema problems and nothing else, no cross-check of the refused flow:\n" + all);
        List<String> unmatched = new ArrayList<>(expected);
        for (String p : problems) {
            String suffix = unmatched.stream().filter(p::endsWith).findFirst().orElse(null);
            assertTrue(suffix != null, "a schema problem '<file> at '<pointer>': <message>', was '" + p + "'; expected one of " + unmatched + "\n" + all);
            unmatched.remove(suffix);
        }
    }

    /** A schema-valid flow {@code orders} from {@code in} with this one step (YAML flow mapping). */
    static String flowWithStep(String step) {
        return """
                schemaVersion: 1
                kind: flow
                id: orders
                version: 1.0.0
                from: { endpoint: in }
                delivery: { mode: sync }
                limits: { maxBodyBytes: 1024 }
                steps: [ %s ]
                """.formatted(step);
    }

    /** Every field crossCheck() reads from a step that the flow schema leaves untyped, with a value of the wrong type. */
    static Stream<Arguments> mistypedStepFields() {
        return Stream.of(
                Arguments.of("endpoints", "{ type: to, name: send, endpoint: partner, endpoints: x }"),
                Arguments.of("endpoints", "{ type: to, name: send, endpoint: partner, endpoints: { domestic: 42 } }"),
                Arguments.of("endpoint", "{ type: to, name: send, byProp: PARTNER, endpoints: { domestic: partner }, endpoint: 42 }"),
                Arguments.of("stylesheet", "{ type: to, name: send, endpoint: partner, stylesheet: 42 }"),
                Arguments.of("schema", "{ type: to, name: send, endpoint: partner, schema: 42 }"));
    }

    /**
     * Round 3 (d1a review round 1): a step's fields beyond its type's requirements are untyped in the flow schema
     * ({@code additionalProperties: true}; a {@code to} step needs only one of {@code endpoint} and {@code byProp} + {@code endpoints}).
     * Each field crossCheck() reads, given a value of the wrong type, is a problem line naming the file by its package-relative path
     * and the field: never an exception (a non-object {@code endpoints} threw ClassCastException), never a line that does not say
     * where (vert.x {@code getString} turns 42 into "42" without a word).
     */
    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("mistypedStepFields")
    void aMistypedStepFieldIsAProblemNotAnException(String field, String step, @TempDir Path dir) throws IOException {
        write(dir, "package.yaml", PACKAGE);
        write(dir, "endpoints/in.yaml", IN);
        write(dir, "endpoints/partner.yaml", PARTNER);
        String flow = flowWithStep(step);
        assertTrue(Schemas.FLOW.validate(Documents.yaml(flow)).valid(), "fixture: the schema accepts " + step + ": " + Schemas.FLOW.validate(Documents.yaml(flow)));
        write(dir, "flows/orders.yaml", flow);
        Package pkg = Package.load(dir);

        List<String> problems = assertDoesNotThrow(pkg::problems, "a mistyped " + field + " is a problem, not an exception");

        assertTrue(problems.stream().anyMatch(l -> l.contains("flows/orders.yaml") && l.contains(field)),
                "a line names flows/orders.yaml and the field '" + field + "': " + problems);
    }

    /**
     * Round 3 (d1a review round 1): every problem line names its file by the path relative to the package directory, never by an
     * absolute path: a schema problem, and the files that do not exist (a flow's stylesheet, a test's fixture and mock reply).
     */
    @Test
    void problemLinesNameThePackageRelativePath(@TempDir Path dir) throws IOException {
        write(dir, "package.yaml", PACKAGE);
        write(dir, "endpoints/in.yaml", IN);
        write(dir, "endpoints/partner.yaml", PARTNER);
        write(dir, "flows/bad.yaml", flow("bad", 0, "partner"));
        write(dir, "flows/orders.yaml", flowWithStep("{ type: xslt, name: map, stylesheet: missing.xsl }"));
        write(dir, "flows/orders.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: orders
                mocks: { partner: { reply: fixtures/gone.xml } }
                cases: [ { name: c, send: fixtures/missing.xml, expect: { steps: 1 } } ]
                """);

        List<String> problems = Package.load(dir).problems();

        String all = String.join("\n", problems);
        assertTrue(problems.stream().anyMatch(l -> l.startsWith("flows/bad.yaml at '/limits/maxBodyBytes'")), "the schema problem, package-relative:\n" + all);
        assertTrue(problems.stream().anyMatch(l -> l.contains("flows/orders.yaml") && l.contains("missing.xsl")), "the missing stylesheet:\n" + all);
        assertTrue(problems.stream().anyMatch(l -> l.contains("flows/orders.test.yaml") && l.contains("missing.xml")), "the missing fixture:\n" + all);
        assertTrue(problems.stream().anyMatch(l -> l.contains("flows/orders.test.yaml") && l.contains("gone.xml")), "the missing mock reply:\n" + all);
        for (String absolute : List.of(dir.toAbsolutePath().toString(), dir.toRealPath().toString())) {
            for (String l : problems) assertFalse(l.contains(absolute), "no absolute path (" + absolute + ") in '" + l + "'");
        }
    }

    @Test
    void theDemoPackageHasNoProblems() throws IOException {
        Package demo = Package.load(DemoPackageTest.DEMO);
        assertEquals("demo", demo.name(), "fixture: the demo package was read");
        assertEquals(List.of(), demo.problems());
    }

    // --- P1-09d1b (F1): the documents by id, as the deploy reads them

    /**
     * {@code manifest()} is package.yaml as a JSON object; {@code endpoints()} and {@code flows()} map each id to its document;
     * {@code flowFile(id)} is the file a flow was read from, by its id, whatever the file is called.
     */
    @org.junit.jupiter.api.Test
    void manifestEndpointsAndFlowsAreTheDocumentsById(@TempDir Path dir) throws IOException {
        write(dir, "package.yaml", PACKAGE);
        write(dir, "endpoints/in.yaml", IN);
        write(dir, "endpoints/partner.yaml", PARTNER);
        write(dir, "flows/orders.yaml", flow("orders", 1024, "partner"));
        Path renamed = write(dir, "flows/refund-flow.yaml", flow("refunds", 2048, "partner"));   // flows/ itself: sub-folders come with P2-02a (D-060 d)
        Package pkg = Package.load(dir);

        io.vertx.core.json.JsonObject manifest = pkg.manifest();
        assertEquals("shop", manifest.getString("name"));
        assertEquals("1.0.0", manifest.getString("version"));
        assertEquals("package", manifest.getString("kind"));

        assertEquals(java.util.Set.of("in", "partner"), pkg.endpoints().keySet());
        assertEquals("in", pkg.endpoints().get("in").getString("direction"));
        assertEquals(8080, pkg.endpoints().get("in").getJsonObject("config").getInteger("port"));
        assertEquals("http://localhost:8090/echo", pkg.endpoints().get("partner").getJsonObject("config").getString("url"));

        assertEquals(java.util.Set.of("orders", "refunds"), pkg.flows().keySet());
        assertEquals(1024L, pkg.flows().get("orders").getJsonObject("limits").getLong("maxBodyBytes"));
        assertEquals(2048L, pkg.flows().get("refunds").getJsonObject("limits").getLong("maxBodyBytes"));
        assertEquals("partner", pkg.flows().get("refunds").getJsonArray("steps").getJsonObject(0).getString("endpoint"));

        assertTrue(Files.isSameFile(dir.resolve("flows/orders.yaml"), pkg.flowFile("orders")), "orders was read from flows/orders.yaml: " + pkg.flowFile("orders"));
        assertTrue(Files.isSameFile(renamed, pkg.flowFile("refunds")), "refunds was read from a file named otherwise: " + pkg.flowFile("refunds"));
    }

    /** What the accessors return is the caller's own copy: changing it changes neither the next answer nor what problems() judges. */
    @org.junit.jupiter.api.Test
    void theAccessorsReturnDeepCopies(@TempDir Path dir) throws IOException {
        write(dir, "package.yaml", PACKAGE);
        write(dir, "endpoints/in.yaml", IN);
        write(dir, "endpoints/partner.yaml", PARTNER);
        write(dir, "flows/orders.yaml", flow("orders", 1024, "partner"));
        Package pkg = Package.load(dir);
        assertEquals(List.of(), pkg.problems(), "fixture: a valid package");

        pkg.manifest().put("name", "Not A Name").put("dependencies", "x");
        pkg.endpoints().get("in").put("direction", "sideways").getJsonObject("config").put("port", "x");
        pkg.flows().get("orders").getJsonObject("limits").put("maxBodyBytes", -1);
        pkg.flows().get("orders").getJsonArray("steps").getJsonObject(0).put("endpoint", "nowhere");

        assertEquals("shop", pkg.manifest().getString("name"));
        assertFalse(pkg.manifest().containsKey("dependencies"));
        assertEquals("in", pkg.endpoints().get("in").getString("direction"));
        assertEquals(8080, pkg.endpoints().get("in").getJsonObject("config").getInteger("port"));
        assertEquals(java.util.Set.of("in", "partner"), pkg.endpoints().keySet());
        assertEquals(java.util.Set.of("orders"), pkg.flows().keySet());
        assertEquals(1024L, pkg.flows().get("orders").getJsonObject("limits").getLong("maxBodyBytes"));
        assertEquals("partner", pkg.flows().get("orders").getJsonArray("steps").getJsonObject(0).getString("endpoint"));
        assertEquals(List.of(), pkg.problems(), "problems() judges the documents as loaded");
    }

    /** Carried into d1b: a {@code stylesheet} or {@code schema} that is not a string is a line saying so, naming the file, flow and step. */
    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"stylesheet", "schema"})
    void aNonStringStylesheetOrSchemaSaysItMustBeAString(String field, @TempDir Path dir) throws IOException {
        write(dir, "package.yaml", PACKAGE);
        write(dir, "endpoints/in.yaml", IN);
        write(dir, "endpoints/partner.yaml", PARTNER);
        write(dir, "flows/orders.yaml", flowWithStep("{ type: to, name: send, endpoint: partner, " + field + ": 42 }"));

        List<String> problems = Package.load(dir).problems();

        assertTrue(problems.contains("flows/orders.yaml: flow 'orders' step 'send' " + field + " must be a string, was 42"), "the line: " + problems);
    }

    // --- P1-09d1b (implementer): what the deploy's 422 says when the load itself stops

    /** A file that does not parse stops the load; the refusal starts with that file's package-relative path and says why. */
    @org.junit.jupiter.api.Test
    void aFileThatDoesNotParseStopsTheLoadNamingItsRelativePath(@TempDir Path dir) throws IOException {
        write(dir, "package.yaml", PACKAGE);
        write(dir, "endpoints/in.yaml", IN);
        write(dir, "flows/broken.yaml", "id: [unclosed\n");

        IllegalArgumentException refused = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Package.load(dir));

        assertTrue(refused.getMessage().startsWith("flows/broken.yaml: not valid YAML: "), "the file, relative, then the parser's reason: " + refused.getMessage());
        assertFalse(refused.getMessage().contains(dir.toString()), "no absolute path: " + refused.getMessage());
    }

    /** A package without package.yaml stops the load naming the file and the kind of failure, never the absolute path the JDK names. */
    @org.junit.jupiter.api.Test
    void aMissingManifestStopsTheLoadNamingPackageYaml(@TempDir Path dir) throws IOException {
        write(dir, "endpoints/in.yaml", IN);

        IllegalArgumentException refused = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Package.load(dir));

        assertEquals("package.yaml: cannot be read (NoSuchFileException)", refused.getMessage());
    }

    @org.junit.jupiter.api.Test
    void flowFileOfAnUnknownIdIsRefusedNamingIt(@TempDir Path dir) throws IOException {
        write(dir, "package.yaml", PACKAGE);
        write(dir, "endpoints/in.yaml", IN);
        write(dir, "endpoints/partner.yaml", PARTNER);
        write(dir, "flows/orders.yaml", flow("orders", 1024, "partner"));

        IllegalArgumentException refused = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Package.load(dir).flowFile("refunds"));

        assertTrue(refused.getMessage().contains("'refunds'"), refused.getMessage());
    }

    /** A duplicate names the id and both files that declare it. */
    static void assertDuplicate(String problem, String id, String firstFile, String secondFile) {
        assertTrue(problem.contains(id), "names the id '" + id + "': " + problem);
        assertTrue(problem.contains(firstFile), "names " + firstFile + ": " + problem);
        assertTrue(problem.contains(secondFile), "names " + secondFile + ": " + problem);
    }
}
