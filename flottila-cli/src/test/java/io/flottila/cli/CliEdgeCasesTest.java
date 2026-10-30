package io.flottila.cli;

import io.flottila.cli.CliRuns.Ran;
import io.flottila.schemas.Package;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.flottila.cli.CliRuns.DEMO;
import static io.flottila.cli.CliRuns.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-03a (implementer): what the packet's cases leave open — an option given blank or twice, a package.yaml without a name
 * (validate checks it, builds nothing as it; deploy asks for {@code --name}), a package directory without package.yaml, an admin
 * URL that is no http(s) URL, a blank token, a trailing slash on the admin URL, a name or revision that needs encoding.
 */
class CliEdgeCasesTest {

    @TempDir Path tmp;
    StubAdmin admin;

    @BeforeEach
    void startTheStub() throws IOException {
        admin = new StubAdmin();
    }

    @AfterEach
    void stopTheStub() {
        admin.close();
    }

    /** The demo copied to {@code tmp/<as>} with package.yaml's {@code name} line rewritten to {@code nameLine}. */
    Path demoNamed(String as, String nameLine) throws IOException {
        Path dir = CliRuns.copy(DEMO, tmp.resolve(as));
        CliRuns.rewrite(dir.resolve("package.yaml"), Map.of("name: demo\n", nameLine));
        return dir;
    }

    static Stream<List<String>> blankOrRepeated() {
        String demo = DEMO.toString();
        return Stream.of(List.of("validate", demo, "--env", ""), List.of("validate", demo, "--env", " "), List.of("validate", demo, "--env", "dev", "--env", "prod"),
                List.of("deploy", demo, "--name", ""), List.of("deploy", demo, "--revision", "r1", "--revision", "r2"));
    }

    /** An option's value may not be blank and an option is given once: a usage error, nothing on stdout, nothing sent. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("blankOrRepeated")
    void aBlankOrRepeatedOptionIsAUsageError(List<String> args) {
        Ran ran = run(Map.of("FLOTTILA_ADMIN", admin.url()), args.toArray(String[]::new));

        assertEquals(2, ran.exit(), ran.toString());
        assertEquals("", ran.out(), ran.toString());
        assertTrue(ran.err().startsWith("flottila: "), ran.toString());
        assertEquals(List.of(), admin.requests(), "nothing sent");
    }

    /**
     * A package.yaml without a name: the tree says {@code -} for it, the problems are Package.problems()'s and nothing else — no
     * dry build as some name, whose check would add a line comparing names.
     */
    @Test
    void aPackageWithoutANameIsCheckedButNotBuilt() throws Exception {
        Path dir = demoNamed("nameless", "");
        List<String> expected = new ArrayList<>();
        Package.load(dir).problems().forEach(p -> expected.add("problem: " + p));
        assertFalse(expected.isEmpty(), "fixture: a package.yaml without a name is a problem");

        Ran ran = run("validate", dir.toString());

        assertEquals(1, ran.exit(), ran.toString());
        assertEquals("package - 0.1.0", ran.outLines().getFirst(), ran.toString());
        assertEquals(expected, ran.problems(), ran.toString());
    }

    /** Without --name the name is package.yaml's: one without a name, or none at all, is a usage error and nothing is sent. */
    @Test
    void deployWithoutAManifestNameNeedsTheNameOption() throws Exception {
        Path nameless = demoNamed("nameless", "");
        Path numbered = demoNamed("numbered", "name: 42\n");
        Path noManifest = CliRuns.copy(DEMO, tmp.resolve("no-manifest"));
        Files.delete(noManifest.resolve("package.yaml"));

        Ran a = run(Map.of(), "deploy", nameless.toString(), "--admin", admin.url());
        Ran b = run(Map.of(), "deploy", numbered.toString(), "--admin", admin.url());
        Ran c = run(Map.of(), "deploy", noManifest.toString(), "--admin", admin.url());

        for (Ran ran : List.of(a, b, c)) {
            assertEquals(2, ran.exit(), ran.toString());
            assertEquals("", ran.out(), ran.toString());
            assertTrue(ran.err().contains("--name"), "the usage error names the way out: " + ran);
        }
        assertTrue(c.err().contains("package.yaml cannot be read"), c.toString());
        assertEquals(List.of(), admin.requests(), "nothing sent");

        Ran named = run(Map.of(), "deploy", noManifest.toString(), "--admin", admin.url(), "--name", "given");

        assertEquals(0, named.exit(), named.toString());
        assertEquals("/admin/packages/given", admin.only().path(), "--name needs no package.yaml");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ftp://127.0.0.1/", "not a url", "localhost:8081", "http:///admin"})
    void anAdminUrlThatIsNoHttpUrlIsAUsageError(String url) {
        Ran ran = run(Map.of(), "deploy", DEMO.toString(), "--admin", url);

        assertEquals(2, ran.exit(), ran.toString());
        assertEquals("", ran.out(), ran.toString());
        assertTrue(ran.err().contains("the admin URL '" + url + "'"), ran.toString());
    }

    @Test
    void aBlankTokenSendsNoHeader() {
        Ran ran = run(Map.of("FLOTTILA_ADMIN_TOKEN", "  "), "deploy", DEMO.toString(), "--admin", admin.url());

        assertEquals(0, ran.exit(), ran.toString());
        assertEquals(List.of(), admin.only().header("authorization"), "a blank FLOTTILA_ADMIN_TOKEN is none");
    }

    @Test
    void aTrailingSlashOnTheAdminUrlIsDropped() {
        Ran ran = run(Map.of(), "deploy", DEMO.toString(), "--admin", admin.url() + "//");

        assertEquals(0, ran.exit(), ran.toString());
        assertEquals("/admin/packages/demo", admin.only().path());
    }

    /** A name or revision the URL cannot carry as it is goes percent-encoded (a space as %20): the node judges and refuses it. */
    @Test
    void aNameAndRevisionAreEncoded() {
        Ran ran = run(Map.of(), "deploy", DEMO.toString(), "--admin", admin.url(), "--name", "a b/c", "--revision", "r 1&x=y");

        assertEquals(0, ran.exit(), ran.toString());
        StubAdmin.Request put = admin.only();
        assertEquals("/admin/packages/a%20b%2Fc", put.path());
        assertEquals("revision=r%201%26x%3Dy", put.query());
    }

    /** A 204 has no body: an empty line is printed, exit 0. */
    @Test
    void noContentPrintsAnEmptyAnswer() {
        admin.answer(204, "");

        Ran ran = run(Map.of(), "deploy", DEMO.toString(), "--admin", admin.url());

        assertEquals(0, ran.exit(), ran.toString());
        assertEquals(List.of(""), ran.outLines(), ran.toString());
        assertNull(admin.only().query());
    }

    // --- P2-02b2 (D-089): the lock command and the --deps option

    /** help names lock and the --deps option of lock, validate and test, one usage line each. */
    @Test
    void theUsageNamesLockAndTheDepsOption() {
        Ran ran = run("help");

        List<String> lines = ran.outLines().stream().map(String::strip).toList();
        assertEquals(0, ran.exit(), ran.toString());
        for (String usage : List.of("lock <dir> [--deps <dir>]", "validate <dir> [--env <env>] [--deps <dir>]", "test <dir> [--deps <dir>]")) {
            assertTrue(lines.contains(usage), "a usage line '" + usage + "': " + ran);
        }
    }

    static Stream<List<String>> depsUsageErrors() {
        List<List<String>> cases = new ArrayList<>();
        for (String command : List.of("lock", "validate", "test")) {
            cases.add(List.of(command, "PKG", "--deps"));
            cases.add(List.of(command, "PKG", "--deps", "MISSING"));
            cases.add(List.of(command, "PKG", "--deps", "FILE"));
            cases.add(List.of(command, "PKG", "--deps", "DEPS", "--deps", "DEPS"));
        }
        cases.add(List.of("lock"));
        cases.add(List.of("lock", "MISSING"));
        cases.add(List.of("lock", "PKG", "--env", "dev"));
        cases.add(List.of("lock", "PKG", "--bogus", "x"));
        cases.add(List.of("lock", "PKG", "PKG"));
        return cases.stream();
    }

    /**
     * {@code --deps} without a value, naming no directory (missing or a plain file) or given twice, and lock's own usage errors
     * (no dir, a missing dir, an option it does not take, two dirs): exit 2, nothing on stdout, no lock written.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("depsUsageErrors")
    void aDepsOrLockUsageErrorIsExit2(List<String> args) throws IOException {
        Path deps = tmp.resolve("deps");
        Path pkg = DepsFixture.copy("invoice", deps.resolve("invoice"));
        DepsFixture.copy("common", deps.resolve("common"));
        Files.writeString(tmp.resolve("plain-file"), "not a directory");
        Map<String, String> placeholders = Map.of("PKG", pkg.toString(), "MISSING", tmp.resolve("no-such-dir").toString(),
                "FILE", tmp.resolve("plain-file").toString(), "DEPS", deps.toString());

        Ran ran = run(args.stream().map(a -> placeholders.getOrDefault(a, a)).toArray(String[]::new));

        assertEquals(2, ran.exit(), ran.toString());
        assertEquals("", ran.out(), ran.toString());
        assertTrue(ran.err().startsWith("flottila: "), ran.toString());
        assertFalse(Files.exists(pkg.resolve("package.lock")), "nothing written: " + ran);
    }
}
