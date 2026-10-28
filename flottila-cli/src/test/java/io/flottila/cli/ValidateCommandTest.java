package io.flottila.cli;

import io.flottila.cli.CliRuns.Ran;
import io.flottila.schemas.Package;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.flottila.cli.CliRuns.DEMO;
import static io.flottila.cli.CliRuns.FIXTURES;
import static io.flottila.cli.CliRuns.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-03a (D-079, F-16): {@code flottila validate <dir> [--env <env>]} — {@code Package.problems()}, then the node's dry build
 * ({@code Deployments.validate}) over the shipped components, steps and languages, nothing started — prints the package's tree
 * and its problems and exits 0 when it is valid, 1 on any problem (an unreadable YAML included), 2 on a usage error or a missing
 * directory. {@code io.flottila.cli.Cli} dispatches {@code validate | deploy | help}; anything else is usage on stderr and 2,
 * {@code test} is not there yet (P2-03b).
 *
 * <p>The output format these tests pin (P2-03a test-author, within the packet): the first line {@code package <name> <version>};
 * then every folder of {@code Package.folders()} in that (path) order as {@code <path>/  <namespace|->  <description|->}, each
 * followed by its own artifacts ({@code Package.artifacts()} whose parent is that folder, in path order) as
 * {@code "  <file name>  <kind>  <id|->"}; artifacts at the package root come first, under a line {@code ./  -  -} that is printed
 * only when the root holds an artifact; then one line {@code problem: <problem>} per problem, each problem on one line, each
 * once. Columns are separated by two spaces. Everything goes to stdout; stderr carries only usage and failures of the tool.
 */
class ValidateCommandTest {

    /** {@code packages/demo} as validate prints it. */
    static final List<String> DEMO_TREE = List.of(
            "package demo 0.1.0",
            "endpoints/  -  -",
            "  http-in.yaml  endpoint  http-in",
            "  partner-domestic.yaml  endpoint  partner-domestic",
            "  partner-foreign.yaml  endpoint  partner-foreign",
            "fixtures/  -  -",
            "flows/  -  -",
            "  invoice-sync.test.yaml  test  -",
            "  invoice-sync.yaml  flow  invoice-sync");

    /** The {@code tree} fixture: a root endpoint, folder.yaml with both fields and with a description only, a nested folder. */
    static final List<String> TREE = List.of(
            "package tree 2.3.4",
            "./  -  -",
            "  in.yaml  endpoint  in",
            "notes/  -  Nothing but notes",
            "orders/  urn:example:orders  Orders as the shop sends them",
            "  partner.yaml  endpoint  partner",
            "orders/v1/  -  -",
            "  orders.yaml  flow  orders");

    @TempDir Path tmp;

    // --- valid packages: the tree, exit 0

    @Test
    void demoPrintsTheTreeAndExits0() {
        Ran ran = run("validate", DEMO.toString());

        assertEquals(0, ran.exit(), ran.toString());
        assertEquals(DEMO_TREE, ran.outLines(), ran.toString());
        assertEquals("", ran.err(), "a valid package prints nothing on stderr");
    }

    /** Folder lines carry the folder.yaml's namespace and description (else {@code -}); root artifacts sit under {@code ./}; a nested folder is its own line. */
    @Test
    void theTreeShowsFoldersNamespacesDescriptionsAndRootArtifacts() {
        Ran ran = run("validate", FIXTURES.resolve("tree").toString());

        assertEquals(0, ran.exit(), ran.toString());
        assertEquals(TREE, ran.outLines(), ran.toString());
    }

    /**
     * Nothing is started: the tree's in endpoint names a loopback port this case holds bound, so a validate that started (or
     * deployed) the http consumer could not listen and would not answer 0.
     */
    @Test
    void validateStartsNothingItsInPortMayBeTaken() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 0, InetAddress.ofLiteral("127.0.0.1"))) {
            Path tree = CliRuns.copy(FIXTURES.resolve("tree"), tmp.resolve("tree"));
            CliRuns.rewrite(tree.resolve("in.yaml"), Map.of("port: 18081", "port: " + taken.getLocalPort()));

            Ran ran = run("validate", tree.toString());

            assertEquals(0, ran.exit(), "the port is held by the case, the validate binds nothing: " + ran);
            assertEquals(TREE, ran.outLines(), ran.toString());
        }
    }

    // --- problems: the tree, then one line per problem, exit 1

    /** A stylesheet that does not compile is the dry build's problem: the tree, then one line naming the flow and the stylesheet. */
    @Test
    void problemsArePrintedAndExit1() {
        Ran ran = run("validate", FIXTURES.resolve("broken-xslt").toString());

        assertEquals(1, ran.exit(), ran.toString());
        List<String> lines = ran.outLines();
        assertEquals(DEMO_TREE, lines.subList(0, Math.min(DEMO_TREE.size(), lines.size())), "the tree first: " + ran);
        assertEquals(DEMO_TREE.size() + 1, lines.size(), "then one problem line: " + ran);
        String problem = lines.getLast();
        assertTrue(problem.startsWith("problem: flow 'invoice-sync' does not build: "), problem);
        assertTrue(problem.contains("flows/invoice-to-partner.xsl"), "it names the stylesheet from the package root: " + problem);
    }

    /** Package.problems() (a schema violation: three lines for one direction) is printed after the tree, each problem once, in its order. */
    @Test
    void packageProblemsArePrintedOnceEachAfterTheTree() throws Exception {
        Path dir = FIXTURES.resolve("bad-schema");
        List<String> expected = new ArrayList<>(DEMO_TREE);
        List<String> problems = Package.load(dir).problems();
        assertFalse(problems.isEmpty(), "fixture: bad-schema has problems");
        problems.forEach(p -> expected.add("problem: " + p));

        Ran ran = run("validate", dir.toString());

        assertEquals(1, ran.exit(), ran.toString());
        assertEquals(expected, ran.outLines(), ran.toString());
    }

    /** An endpoint config its component's configSchema refuses is the check's problem: every problem line names it, environment dev. */
    @Test
    void anEndpointConfigProblemIsPrintedAndExit1() {
        Ran ran = run("validate", FIXTURES.resolve("bad-config").toString());

        assertEquals(1, ran.exit(), ran.toString());
        assertEquals(DEMO_TREE, ran.outLines().subList(0, Math.min(DEMO_TREE.size(), ran.outLines().size())), "the tree first: " + ran);
        assertFalse(ran.problems().isEmpty(), ran.toString());
        assertEquals(ran.outLines().size(), DEMO_TREE.size() + ran.problems().size(), "nothing but the tree and the problems: " + ran);
        assertTrue(ran.problems().stream().allMatch(l -> l.startsWith("problem: endpoint 'http-in' config (environment dev) at '/port': ")), ran.toString());
    }

    /**
     * A package.yaml whose name is missing or no string: the tree says what it holds, the problems are exactly Package.problems()'s
     * (the schema names the name) — no dry build under some made-up name, whose check would add a line comparing names
     * ("package.yaml names the package …, the deploy …") — and exit 1.
     */
    @ParameterizedTest(name = "name line [{0}]")
    @ValueSource(strings = {"", "name: 42\n"})
    void aPackageWithoutAStringNameGetsItsSchemaProblemsOnly(String nameLine) throws Exception {
        Path dir = CliRuns.copy(DEMO, tmp.resolve("nameless"));
        CliRuns.rewrite(dir.resolve("package.yaml"), Map.of("name: demo\n", nameLine));
        List<String> expected = Package.load(dir).problems().stream().map(p -> "problem: " + p).toList();
        assertFalse(expected.isEmpty(), "fixture: a package.yaml without a string name is a schema problem");

        Ran ran = run("validate", dir.toString());

        assertEquals(1, ran.exit(), ran.toString());
        assertEquals("package " + (nameLine.isEmpty() ? "-" : "42") + " 0.1.0", ran.outLines().getFirst(), ran.toString());
        assertEquals(expected, ran.problems(), "Package.problems() and nothing a dry build would add: " + ran);
        assertEquals(DEMO_TREE.subList(1, DEMO_TREE.size()), ran.outLines().subList(1, DEMO_TREE.size()), "the rest of the tree: " + ran);
    }

    /** A YAML that does not parse stops the load: exit 1 and one problem line naming the file from the package root, its parser's message on that one line. */
    @Test
    void unreadableYamlExit1() {
        Ran ran = run("validate", FIXTURES.resolve("unreadable-yaml").toString());

        assertEquals(1, ran.exit(), ran.toString());
        assertEquals(1, ran.outLines().size(), "one line, the problem (the parser's multi-line message made one line): " + ran);
        assertTrue(ran.outLines().getFirst().startsWith("problem: endpoints/partner-foreign.yaml"), ran.toString());
    }

    /** {@code --env} names the environment whose overlay is checked (default dev, whatever FLOTTILA_ENV says): env-overlay breaks partner-foreign in prod only. */
    @Test
    void envOptionNamesTheEnvironmentDefaultDev() {
        String dir = FIXTURES.resolve("env-overlay").toString();

        Ran byDefault = run("validate", dir);
        Ran despiteTheEnvironment = run(Map.of("FLOTTILA_ENV", "prod"), "validate", dir);
        Ran prod = run("validate", dir, "--env", "prod");
        Ran ci = run("validate", dir, "--env", "ci");

        assertEquals(0, byDefault.exit(), "dev by default: " + byDefault);
        assertEquals(DEMO_TREE, byDefault.outLines(), byDefault.toString());
        assertEquals(0, despiteTheEnvironment.exit(), "the default is dev, not FLOTTILA_ENV: " + despiteTheEnvironment);
        assertEquals(1, prod.exit(), prod.toString());
        assertFalse(prod.problems().isEmpty(), prod.toString());
        assertTrue(prod.problems().stream().allMatch(l -> l.startsWith("problem: endpoint 'partner-foreign' config (environment prod) at '/poolSize': ")), prod.toString());
        assertEquals(0, ci.exit(), "ci has no overlay: " + ci);
    }

    /** No problem line and no stderr line names the package by an absolute path, the directory given absolute (D-078). */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"broken-xslt", "bad-config", "bad-schema", "unreadable-yaml"})
    void noAbsolutePathInOutput(String fixture) throws Exception {
        Path dir = CliRuns.copy(FIXTURES.resolve(fixture), tmp.resolve("pkg-" + fixture));

        Ran ran = run("validate", dir.toAbsolutePath().toString());

        assertEquals(1, ran.exit(), ran.toString());
        for (String abs : List.of(dir.toAbsolutePath().toString(), dir.toRealPath().toString(), tmp.toRealPath().toString())) {
            assertFalse(ran.out().contains(abs), "stdout names " + abs + ": " + ran);
            assertFalse(ran.err().contains(abs), "stderr names " + abs + ": " + ran);
        }
    }

    // --- usage: exit 2, nothing on stdout

    @Test
    void missingDirExit2() {
        Ran ran = run("validate", tmp.resolve("no-such-package").toString());

        assertEquals(2, ran.exit(), ran.toString());
        assertEquals("", ran.out(), ran.toString());
        assertFalse(ran.err().isBlank(), "stderr says why: " + ran);
    }

    static Stream<List<String>> validateUsageErrors() {
        return Stream.of(List.of("validate"), List.of("validate", DEMO.toString(), "--env"), List.of("validate", DEMO.toString(), "--bogus", "x"),
                List.of("validate", DEMO.toString(), DEMO.toString()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validateUsageErrors")
    void validateUsageErrorsExit2(List<String> args) {
        Ran ran = run(args.toArray(String[]::new));

        assertEquals(2, ran.exit(), ran.toString());
        assertEquals("", ran.out(), ran.toString());
        assertFalse(ran.err().isBlank(), "usage on stderr: " + ran);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("noCommand")
    void unknownCommandExit2(List<String> args) {
        Ran ran = run(args.toArray(String[]::new));

        assertEquals(2, ran.exit(), ran.toString());
        assertEquals("", ran.out(), ran.toString());
        assertTrue(ran.err().contains("validate") && ran.err().contains("deploy"), "the usage on stderr names the commands: " + ran);
    }

    static Stream<List<String>> noCommand() {
        return Stream.of(List.of("frobnicate"), List.of("frobnicate", DEMO.toString()), List.of());
    }

    @Test
    void testCommandIsNotYetExit2() {
        Ran ran = run("test", DEMO.toString());

        assertEquals(2, ran.exit(), ran.toString());
        assertTrue(ran.err().contains("not yet (P2-03b)"), ran.toString());
    }

    @Test
    void helpPrintsTheUsageAndExits0() {
        Ran ran = run("help");

        assertEquals(0, ran.exit(), ran.toString());
        assertTrue(ran.out().contains("validate") && ran.out().contains("deploy"), "the usage on stdout names the commands: " + ran);
        assertEquals("", ran.err(), ran.toString());
    }
}
