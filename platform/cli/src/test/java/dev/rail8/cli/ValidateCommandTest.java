package dev.rail8.cli;

import dev.rail8.cli.CliRuns.Ran;
import dev.rail8.schemas.Package;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static dev.rail8.cli.CliRuns.DEMO;
import static dev.rail8.cli.CliRuns.FIXTURES;
import static dev.rail8.cli.CliRuns.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-03a (D-079, F-16): {@code rail8 validate <dir> [--env <env>]} — {@code Package.problems()}, then the node's dry build
 * ({@code Deployments.validate}) over the shipped components, steps and languages, nothing started — prints the package's tree
 * and its problems and exits 0 when it is valid, 1 on any problem (an unreadable YAML included), 2 on a usage error or a missing
 * directory. {@code dev.rail8.cli.Cli} dispatches {@code validate | deploy | help}; anything else is usage on stderr and 2,
 * {@code test} is TestCommandTest's (P2-03b1).
 *
 * <p>The output format these tests pin (P2-03a test-author, within the packet): the first line {@code package <name> <version>};
 * then every folder of {@code Package.folders()} in that (path) order as {@code <path>/  <namespace|->  <description|->}, each
 * followed by its own artifacts ({@code Package.artifacts()} whose parent is that folder, in path order) as
 * {@code "  <file name>  <kind>  <id|->"}; artifacts at the package root come first, under a line {@code ./  -  -} that is printed
 * only when the root holds an artifact; then one line {@code problem: <problem>} per problem, each problem on one line, each
 * once. Columns are separated by two spaces. Everything goes to stdout; stderr carries only usage and failures of the tool.
 */
class ValidateCommandTest {

    /** The invoice-sync-only demo as validate printed it before P2-06: the tree of the fixtures that are copies of it (broken-xslt, bad-config, bad-schema, env-overlay). */
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

    /** The real demo as validate prints it (P2-06: three flows, the async and the SFTP door, an SFTP fixture folder). */
    static final List<String> DEMO_PACKAGE_TREE = List.of(
            "package demo 0.1.0",
            "endpoints/  -  -",
            "  http-in-async.yaml  endpoint  http-in-async",
            "  http-in-order.yaml  endpoint  http-in-order",
            "  http-in.yaml  endpoint  http-in",
            "  order-partner-call.yaml  endpoint  order-partner-call",
            "  order-partner-in.yaml  endpoint  order-partner-in",
            "  partner-domestic.yaml  endpoint  partner-domestic",
            "  partner-foreign.yaml  endpoint  partner-foreign",
            "  sftp-inbox.yaml  endpoint  sftp-inbox",
            "fixtures/  -  -",
            "fixtures/inbox/  -  -",
            "flows/  -  -",
            "  invoice-async.test.yaml  test  -",
            "  invoice-async.yaml  flow  invoice-async",
            "  invoice-sync.test.yaml  test  -",
            "  invoice-sync.yaml  flow  invoice-sync",
            "  order-jq.test.yaml  test  -",
            "  order-jq.yaml  flow  order-jq",
            "  order-partner.test.yaml  test  -",
            "  order-partner.yaml  flow  order-partner",
            "  sftp-invoice.test.yaml  test  -",
            "  sftp-invoice.yaml  flow  sftp-invoice");

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
        assertEquals(DEMO_PACKAGE_TREE, ran.outLines(), ran.toString());
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
     * Nothing is started: the tree's in endpoint is an http door, served on the node's HTTP port, and this case holds the node's
     * default HTTP port bound, so a validate that opened the port, or started (or deployed) the http consumer, could not listen
     * and would not answer 0.
     */
    @Test
    void validateStartsNothingTheNodesHttpPortMayBeTaken() throws Exception {
        try (ServerSocket taken = CliRuns.holdTheDefaultHttpPort()) {
            Ran ran = run("validate", FIXTURES.resolve("tree").toString());

            assertEquals(0, ran.exit(), "the port is taken, the validate binds nothing: " + ran);
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
        assertTrue(ran.problems().stream().allMatch(l -> l.startsWith("problem: endpoint 'http-in' config (environment dev) at '/address': ")), ran.toString());
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
        assertEquals(DEMO_PACKAGE_TREE.subList(1, DEMO_PACKAGE_TREE.size()), ran.outLines().subList(1, DEMO_PACKAGE_TREE.size()), "the rest of the tree: " + ran);
    }

    /** A YAML that does not parse stops the load: exit 1 and one problem line naming the file from the package root, its parser's message on that one line. */
    @Test
    void unreadableYamlExit1() {
        Ran ran = run("validate", FIXTURES.resolve("unreadable-yaml").toString());

        assertEquals(1, ran.exit(), ran.toString());
        assertEquals(1, ran.outLines().size(), "one line, the problem (the parser's multi-line message made one line): " + ran);
        assertTrue(ran.outLines().getFirst().startsWith("problem: endpoints/partner-foreign.yaml"), ran.toString());
    }

    /** {@code --env} names the environment whose overlay is checked (default dev, whatever RAIL8_ENV says): env-overlay breaks partner-foreign in prod only. */
    @Test
    void envOptionNamesTheEnvironmentDefaultDev() {
        String dir = FIXTURES.resolve("env-overlay").toString();

        Ran byDefault = run("validate", dir);
        Ran despiteTheEnvironment = run(Map.of("RAIL8_ENV", "prod"), "validate", dir);
        Ran prod = run("validate", dir, "--env", "prod");
        Ran ci = run("validate", dir, "--env", "ci");

        assertEquals(0, byDefault.exit(), "dev by default: " + byDefault);
        assertEquals(DEMO_TREE, byDefault.outLines(), byDefault.toString());
        assertEquals(0, despiteTheEnvironment.exit(), "the default is dev, not RAIL8_ENV: " + despiteTheEnvironment);
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
    void helpPrintsTheUsageAndExits0() {
        Ran ran = run("help");

        assertEquals(0, ran.exit(), ran.toString());
        assertTrue(ran.out().contains("validate") && ran.out().contains("deploy"), "the usage on stdout names the commands: " + ran);
        assertEquals("", ran.err(), ran.toString());
    }

    // --- dependencies read off-node from the repositories and commits the lock pins (DepsFixture), held for the dry build

    /** invoice pins common 1.2.0 (the parent dir holds it and common 1.3.0): the closure is deployed into the dry build, exit 0. */
    @Test
    void aPackagePinningCommon120ValidatesOffNode() throws Exception {
        Path invoice = DepsFixture.invoiceWithCandidates(tmp.resolve("deps"));

        Ran ran = run("validate", invoice.toString());

        assertEquals(List.of(), ran.problems(), ran.toString());
        assertEquals("package invoice 1.0.0", ran.outLines().getFirst(), ran.toString());
        assertEquals(0, ran.exit(), ran.toString());
    }

    /**
     * The dry build's data directory ({@code rail8-validate-*} under java.io.tmpdir, the dependency archives in it) and the git
     * cache with the commits read from it ({@code rail8-git-*}) are deleted after the run.
     */
    @Test
    void theDryBuildLeavesNoDataDirInTmp() throws Exception {
        Path invoice = DepsFixture.invoiceWithCandidates(tmp.resolve("deps"));
        List<Path> before = validateDirs();

        Ran ran = run("validate", invoice.toString());

        assertEquals(List.of(), ran.problems(), ran.toString());
        assertEquals(0, ran.exit(), ran.toString());
        List<Path> left = new ArrayList<>(validateDirs());
        left.removeAll(before);
        assertEquals(List.of(), left, "no new rail8-validate-* or rail8-git-* entry in java.io.tmpdir");
    }

    private static List<Path> validateDirs() throws java.io.IOException {
        try (Stream<Path> list = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return list.filter(p -> p.getFileName().toString().startsWith("rail8-validate-") || p.getFileName().toString().startsWith("rail8-git-")).toList();
        }
    }

    /** {@code --deps} (with {@code --env}) names the folder of the repositories; the parent's common 1.2.0 has other content and is not used. */
    @Test
    void theDepsOptionNamesTheCandidatesDir() throws Exception {
        Path vendor = tmp.resolve("vendor");
        Path common = DepsFixture.copy("common", vendor.resolve("shared-xsl"));
        DepsFixture.relabel(DepsFixture.copy("common", tmp.resolve("work/common")), "a decoy");
        Path invoice = DepsFixture.copy("invoice", tmp.resolve("work/invoice"));
        DepsFixture.lock(invoice, common);

        Ran ran = run("validate", invoice.toString(), "--env", "dev", "--deps", vendor.toString());

        assertEquals(List.of(), ran.problems(), ran.toString());
        assertEquals(0, ran.exit(), ran.toString());
    }

    /**
     * The dependency is read from the commit its lock pins, not from its working folder: common committed again after invoice was
     * locked, then edited without a commit, changes nothing — invoice's flow maps with a stylesheet only the pinned commit holds,
     * which both later states have removed; exit 0. Witness: invoice locked again, to common's new HEAD, misses it.
     */
    @Test
    void theDependencyIsReadFromItsPinnedCommit() throws Exception {
        Path deps = tmp.resolve("deps");
        Path common = DepsFixture.copy("common", deps.resolve("common"));
        Files.copy(common.resolve("xslt/to-partner.xsl"), common.resolve("xslt/pinned.xsl"));
        DepsFixture.commit(common);
        Path invoice = DepsFixture.copy("invoice", deps.resolve("invoice"));
        CliRuns.rewrite(invoice.resolve("flows/to-partner.yaml"), Map.of("stylesheet: common:xslt/to-partner.xsl", "stylesheet: common:xslt/pinned.xsl"));
        DepsFixture.lock(invoice, common);
        Files.delete(common.resolve("xslt/pinned.xsl"));
        DepsFixture.commit(common);
        Files.writeString(common.resolve("xslt/lib.xsl"), "not even XSLT");

        Ran ran = run("validate", invoice.toString());

        assertEquals(List.of(), ran.problems(), ran.toString());
        assertEquals(0, ran.exit(), ran.toString());

        Files.delete(common.resolve("xslt/lib.xsl"));
        DepsFixture.commit(common);
        DepsFixture.lock(invoice, common);
        Ran relocked = run("validate", invoice.toString());

        assertTrue(relocked.problems().stream().anyMatch(l -> l.endsWith("stylesheet 'common:xslt/pinned.xsl' does not exist in common@1.2.0")),
                "witness: common's new HEAD lacks the stylesheet: " + relocked);
        assertEquals(1, relocked.exit(), relocked.toString());
    }

    /**
     * A lock whose content is not that of the commit it pins (edited by hand): exit 1 naming the entry, both digests, the repository
     * and the commit; no absolute path.
     */
    @Test
    void aLockDriftIsExit1NamingTheEntry() throws Exception {
        Path deps = tmp.resolve("deps");
        Path invoice = DepsFixture.invoiceWithCandidates(deps);
        DepsFixture.Pin pin = DepsFixture.pinOf(deps.resolve("common"));
        String wrong = "sha256:" + "0".repeat(64);
        DepsFixture.lock(invoice, List.of(new DepsFixture.Pin(pin.name(), pin.version(), wrong, pin.repository(), pin.commit())));

        Ran ran = run("validate", invoice.toString());

        assertEquals(1, ran.exit(), ran.toString());
        assertTrue(ran.problems().contains("problem: package.lock pins common@1.2.0 " + wrong + "; common at " + pin.commit() + " has " + pin.content()),
                ran.toString());
        DepsFixture.assertNoAbsolutePath(ran, tmp);
    }

    /**
     * The lock pins a repository the folder lacks (common is in another folder; the candidates hold common-1.3.0 only), or a commit
     * the repository lacks (common here is another repository with commits of its own): exit 1 naming the repository and the
     * commit, no absolute path.
     */
    @Test
    void aMissingRepositoryOrCommitIsExit1() throws Exception {
        Path pinnedElsewhere = DepsFixture.copy("common", tmp.resolve("elsewhere/common"));
        DepsFixture.relabel(pinnedElsewhere, "elsewhere");
        String commit = DepsFixture.head(pinnedElsewhere);
        Path other = DepsFixture.copy("invoice", tmp.resolve("other/invoice"));
        DepsFixture.copy("common-1.3.0", tmp.resolve("other/common-1.3.0"));
        DepsFixture.lock(other, pinnedElsewhere);
        Path another = DepsFixture.copy("invoice", tmp.resolve("another/invoice"));
        DepsFixture.copy("common", tmp.resolve("another/common"));
        DepsFixture.lock(another, pinnedElsewhere);

        Ran ranOther = run("validate", other.toString());
        Ran ranAnother = run("validate", another.toString());

        for (Ran ran : List.of(ranOther, ranAnother)) {
            assertEquals(1, ran.exit(), ran.toString());
            assertEquals(List.of("problem: dependency common@1.2.0: common at " + commit + ": not found"), ran.problems(), ran.toString());
            DepsFixture.assertNoAbsolutePath(ran, tmp);
        }
    }

    /** a declares b, b declares a: exit 1 naming the cycle by name (a's own dir is no candidate; the name decides). */
    @Test
    void aCycleIsExit1NamingIt() throws Exception {
        Path a = DepsFixture.cycle(tmp.resolve("deps"));

        Ran ran = run("validate", a.toString());

        assertEquals(1, ran.exit(), ran.toString());
        assertTrue(ran.problems().contains("problem: dependency cycle: a -> b -> a"), ran.toString());
    }

    /**
     * invoice's flow {@code orders} calls {@code /charge}, which a flow of its dependency common serves and which calls
     * {@code /orders} back: the dry build counts the dependency's flows, and the cycle is exit 1 naming the path.
     */
    @Test
    void aCycleOfCallsThroughADependencysFlowIsExit1NamingIt() throws Exception {
        Path deps = tmp.resolve("deps");
        Path common = DepsFixture.copy("common", deps.resolve("common"));
        direct(common, "charge-in", "in", "/charge");
        direct(common, "orders", "out", "/orders");
        calling(common, "charge", "charge-in", "orders");
        DepsFixture.commit(common);
        Path invoice = DepsFixture.copy("invoice", deps.resolve("invoice"));
        direct(invoice, "orders-in", "in", "/orders");
        direct(invoice, "charge", "out", "/charge");
        calling(invoice, "orders", "orders-in", "charge");
        DepsFixture.lock(invoice, common);

        Ran ran = run("validate", invoice.toString());

        assertEquals(1, ran.exit(), ran.toString());
        assertTrue(ran.problems().contains("problem: calls between flows would form a cycle: flow 'orders' -> direct /charge -> flow 'common/charge' -> "
                + "direct /orders -> flow 'orders'"), ran.toString());
    }

    /** Writes the direct endpoint {@code id} of {@code direction} on {@code address} into the package {@code dir}. */
    static void direct(Path dir, String id, String direction, String address) throws java.io.IOException {
        Files.createDirectories(dir.resolve("endpoints"));
        Files.writeString(dir.resolve("endpoints/" + id + ".yaml"), "schemaVersion: 1\nkind: endpoint\nid: " + id + "\nscheme: direct\ndirection: " + direction
                + "\nconfig:\n  address: " + address + "\n");
    }

    /** Writes the sync flow {@code id} from {@code from} whose one step sends to {@code to} into the package {@code dir}. */
    static void calling(Path dir, String id, String from, String to) throws java.io.IOException {
        Files.createDirectories(dir.resolve("flows"));
        Files.writeString(dir.resolve("flows/" + id + ".yaml"), "schemaVersion: 1\nkind: flow\nid: " + id + "\nversion: 1.0.0\nfrom:\n  endpoint: " + from
                + "\ndelivery:\n  mode: sync\nlimits:\n  maxBodyBytes: 4096\nsteps:\n  - type: to\n    name: call\n    endpoint: " + to + "\n");
    }

    /** order -> ubl -> common: ubl's own lock, as its pinned commit holds it, is followed and the closure held leaves first (common, then ubl), exit 0. */
    @Test
    void aTransitiveDependencyIsResolvedThroughTheCandidatesLock() throws Exception {
        Path order = DepsFixture.orderOverUblOverCommon(tmp.resolve("deps"));

        Ran ran = run("validate", order.toString());

        assertEquals(List.of(), ran.problems(), ran.toString());
        assertEquals(0, ran.exit(), ran.toString());
    }

    /**
     * ubl's committed lock pins common with a content other than its commit's (order's lock matches ubl): the drift in the
     * dependency's own lock is named, exit 1. common committed again after ubl was locked changes nothing of ubl's pin.
     */
    @Test
    void aDriftInTheCandidatesOwnLockIsExit1() throws Exception {
        Path deps = tmp.resolve("deps");
        Path order = DepsFixture.orderOverUblOverCommon(deps);
        DepsFixture.relabel(deps.resolve("common"), "committed after ubl's lock");
        Ran moved = run("validate", order.toString());
        DepsFixture.Pin pin = DepsFixture.pinOf(deps.resolve("common"));
        String wrong = "sha256:" + "0".repeat(64);
        DepsFixture.lock(deps.resolve("ubl"), List.of(new DepsFixture.Pin(pin.name(), pin.version(), wrong, pin.repository(), pin.commit())));
        DepsFixture.lock(order, deps.resolve("ubl"));

        Ran ran = run("validate", order.toString());

        assertEquals(List.of(), moved.problems(), "ubl's pin of common is its commit's, not common's HEAD: " + moved);
        assertEquals(0, moved.exit(), moved.toString());
        assertEquals(1, ran.exit(), ran.toString());
        assertTrue(ran.problems().contains("problem: package.lock pins common@1.2.0 " + wrong + "; common at " + pin.commit() + " has " + pin.content()),
                ran.toString());
    }

    /**
     * common 1.2.0 in two folders with different content, invoice locked to {@code left}: validate reads the repository the lock
     * names and looks for no candidate by name, so the other is no ambiguity; exit 0.
     */
    @Test
    void theLockedRepositoryIsReadWhateverOtherFoldersHold() throws Exception {
        Path left = DepsFixture.copy("common", tmp.resolve("deps/left"));
        DepsFixture.relabel(DepsFixture.copy("common", tmp.resolve("deps/right")), "built elsewhere");
        Path invoice = DepsFixture.copy("invoice", tmp.resolve("deps/invoice"));
        DepsFixture.lock(invoice, left);

        Ran ran = run("validate", invoice.toString());

        assertEquals(List.of(), ran.problems(), ran.toString());
        assertEquals(0, ran.exit(), ran.toString());
    }

    /**
     * An unresolved closure runs no dry build: a missing repository, a lock drift and a cycle each print exactly their one resolution
     * problem (the node's "not deployed on this node" is not added), exit 1.
     */
    @Test
    void anUnresolvedClosureRunsNoDryBuild() throws Exception {
        Path elsewhere = DepsFixture.copy("common", tmp.resolve("elsewhere/common"));
        String commit = DepsFixture.head(elsewhere);
        Path none = DepsFixture.copy("invoice", tmp.resolve("none/invoice"));
        DepsFixture.lock(none, elsewhere);
        Path drifted = DepsFixture.invoiceWithCandidates(tmp.resolve("drift"));
        DepsFixture.Pin pin = DepsFixture.pinOf(tmp.resolve("drift/common"));
        String wrong = "sha256:" + "0".repeat(64);
        DepsFixture.lock(drifted, List.of(new DepsFixture.Pin(pin.name(), pin.version(), wrong, pin.repository(), pin.commit())));
        Path a = DepsFixture.cycle(tmp.resolve("cycle"));

        Ran ranNone = run("validate", none.toString());
        Ran ranDrift = run("validate", drifted.toString());
        Ran ranCycle = run("validate", a.toString());

        assertEquals(List.of("problem: dependency common@1.2.0: common at " + commit + ": not found"), ranNone.problems(), ranNone.toString());
        assertEquals(1, ranNone.exit(), ranNone.toString());
        assertEquals(List.of("problem: package.lock pins common@1.2.0 " + wrong + "; common at " + pin.commit() + " has " + pin.content()),
                ranDrift.problems(), ranDrift.toString());
        assertEquals(1, ranDrift.exit(), ranDrift.toString());
        assertEquals(List.of("problem: dependency cycle: a -> b -> a"), ranCycle.problems(), ranCycle.toString());
        assertEquals(1, ranCycle.exit(), ranCycle.toString());
    }

    /**
     * FIX-110 (D-089): {@code --env prod} holds the closure for the dry build instead of deploying it — the node of a deployed
     * environment refuses no dependency, so invoice validates there with exit 0, as in dev. The build did run in prod with common
     * held: invoice mapping with a stylesheet common lacks is named (exit 1). Fixture witness: invoice with common's stylesheets
     * copied in (no dependency) passes in prod — prod has no objection of its own to the package.
     */
    @Test
    void validateInADeployedEnvWithDependenciesIsExit0() throws Exception {
        Path invoice = DepsFixture.invoiceWithCandidates(tmp.resolve("deps"));
        Path missing = DepsFixture.invoiceWithCandidates(tmp.resolve("missing"));
        CliRuns.rewrite(missing.resolve("flows/to-partner.yaml"), Map.of("stylesheet: common:xslt/to-partner.xsl", "stylesheet: common:xslt/missing.xsl"));
        Path inline = DepsFixture.copy("invoice", tmp.resolve("inline/invoice"));
        CliRuns.copy(DepsFixture.DEPS.resolve("common/xslt"), inline.resolve("xslt"));
        DepsFixture.manifest(inline, "invoice", "1.0.0");
        CliRuns.rewrite(inline.resolve("flows/to-partner.yaml"), Map.of("stylesheet: common:xslt/to-partner.xsl", "stylesheet: xslt/to-partner.xsl"));

        Ran inlineProd = run("validate", inline.toString(), "--env", "prod");
        Ran prod = run("validate", invoice.toString(), "--env", "prod");
        Ran dev = run("validate", invoice.toString(), "--env", "dev");
        Ran missingProd = run("validate", missing.toString(), "--env", "prod");

        assertEquals(List.of(), inlineProd.problems(), "fixture witness: prod objects to nothing of invoice itself: " + inlineProd);
        assertEquals(0, inlineProd.exit(), inlineProd.toString());
        assertEquals(List.of(), prod.problems(), prod.toString());
        assertEquals("package invoice 1.0.0", prod.outLines().getFirst(), prod.toString());
        assertEquals(0, prod.exit(), prod.toString());
        assertEquals("", prod.err(), prod.toString());
        assertEquals(List.of(), dev.problems(), dev.toString());
        assertEquals(0, dev.exit(), dev.toString());
        assertTrue(missingProd.problems().stream().anyMatch(l -> l.endsWith("stylesheet 'common:xslt/missing.xsl' does not exist in common@1.2.0")),
                "the prod dry build read common's files: " + missingProd);
        assertEquals(1, missingProd.exit(), missingProd.toString());
        DepsFixture.assertNoAbsolutePath(missingProd, tmp);
    }

    /**
     * Common runs an http flow, served on the node's HTTP port, whose default port this case holds bound; validating invoice,
     * which depends on it, answers 0 with no problem — the dry build neither deploys nor starts the dependency and opens no
     * HTTP port, so nothing tries to listen there (a deployed common could not, and its refusal would be a problem).
     */
    @Test
    void aDependencyWithAnHttpFlowBindsNoPort() throws Exception {
        try (ServerSocket taken = CliRuns.holdTheDefaultHttpPort()) {
            Path invoice = DepsFixture.invoiceOverCommonWithAnHttpFlow(tmp.resolve("deps"));

            Ran ran = run("validate", invoice.toString());

            assertEquals(List.of(), ran.problems(), "the port is taken, the dry build binds nothing: " + ran);
            assertEquals(0, ran.exit(), ran.toString());
            assertEquals("", ran.err(), ran.toString());
        }
    }

    /**
     * Validate reads the repositories the lock names and nothing else of the folder: {@code common-copy} declares common 1.2.0 too,
     * but holds a folder chain past PATH_MAX (its content cannot be walked: the fixture witness below), and {@code odd} has a
     * directory named package.yaml; invoice still reads {@code common}: exit 0, nothing on stderr, no path.
     */
    @Test
    void anUnreadableSiblingIsIgnored() throws Exception {
        Path deps = tmp.resolve("deps");
        Path invoice = DepsFixture.invoiceWithCandidates(deps);
        Path copy = DepsFixture.copy("common", deps.resolve("common-copy"));
        Files.createDirectories(deps.resolve("odd/package.yaml"));
        Path top = copy.resolve("deep");
        List<String> below = LongPaths.pastPathMax(top);
        Path stage = tmp.resolve("stage");
        LongPaths.chain(top, below, stage);
        try {
            assertThrows(UncheckedIOException.class, () -> DepsFixture.content(copy), "fixture witness: common-copy's files cannot be walked");

            Ran ran = run("validate", invoice.toString());

            assertEquals(List.of(), ran.problems(), ran.toString());
            assertEquals(0, ran.exit(), ran.toString());
            assertEquals("", ran.err(), ran.toString());
            DepsFixture.assertNoAbsolutePath(ran, tmp);
        } finally {
            LongPaths.unchain(top, below, stage);
        }
    }

    /**
     * A folder of repositories that cannot be read — {@code --deps} names, relative to the working directory, a folder
     * {@code vendor-deps} whose absolute path is PATH_MAX long (reachable relatively, never absolutely: the fixture witness) — is
     * exit 1 with a line naming the pinned repository and commit, and no absolute path on stdout or stderr.
     */
    @Test
    void anUnlistableDepsFolderIsExit1WithoutAbsolutePath() throws Exception {
        Path invoice = DepsFixture.copy("invoice", tmp.resolve("work/invoice"));
        Path common = DepsFixture.copy("common", tmp.resolve("elsewhere/common"));
        DepsFixture.lock(invoice, common);
        String commit = DepsFixture.head(common);
        Path top = Path.of("target", "fix110-deps-" + System.nanoTime());
        List<String> below = LongPaths.exactlyPathMax(top, "vendor-deps");
        Path stage = Path.of("target", "fix110-stage-" + System.nanoTime());
        LongPaths.chain(top, below, stage);
        try {
            Path vendor = top;
            for (String name : below) vendor = vendor.resolve(name);
            assertTrue(Files.isDirectory(vendor), "fixture witness: the folder is reachable relative to the working directory");
            assertFalse(Files.isDirectory(vendor.toAbsolutePath()), "fixture witness: its absolute path is refused");

            Ran ran = run("validate", invoice.toString(), "--deps", vendor.toString());

            assertEquals(1, ran.exit(), ran.toString());
            assertEquals(1, ran.problems().size(), ran.toString());
            assertTrue(ran.problems().getFirst().startsWith("problem: dependency common@1.2.0: common at " + commit + ": "), ran.toString());
            assertEquals("", ran.err(), ran.toString());
            String cwd = Path.of("").toAbsolutePath().toString();
            assertFalse(ran.out().contains(cwd) || ran.err().contains(cwd), "no absolute path (" + cwd + "): " + ran);
            DepsFixture.assertNoAbsolutePath(ran, tmp);
        } finally {
            LongPaths.unchain(top, below, stage);
        }
    }

    /** Without dependencies nothing of the folder of repositories is read: siblings that do not load change nothing of the output. */
    @Test
    void aPackageWithoutDependenciesIgnoresItsSiblings() throws Exception {
        Path alone = DepsFixture.copy("common", tmp.resolve("alone/common"));
        Path crowded = DepsFixture.copy("common", tmp.resolve("crowd/common"));
        Files.createDirectories(tmp.resolve("crowd/broken"));
        Files.writeString(tmp.resolve("crowd/broken/package.yaml"), "name: [common\nversion: {");
        Files.createDirectories(tmp.resolve("crowd/notes"));

        Ran a = run("validate", alone.toString());
        Ran c = run("validate", crowded.toString());

        assertEquals(0, a.exit(), a.toString());
        assertEquals(a.toString(), c.toString());
    }
}
