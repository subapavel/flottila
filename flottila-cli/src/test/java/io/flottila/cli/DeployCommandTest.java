package io.flottila.cli;

import io.flottila.cli.CliRuns.Ran;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static io.flottila.cli.CliRuns.DEMO;
import static io.flottila.cli.CliRuns.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-03a (D-079): {@code flottila deploy <dir> [--name N] [--revision R] [--admin URL]} zips the package and PUTs it to
 * {@code <admin>/admin/packages/<name>[?revision=R]} with {@code Content-Type: application/zip}; the name defaults to package.yaml's
 * {@code name} (not the directory's); the bearer token comes from FLOTTILA_ADMIN_TOKEN in the environment only and no header is
 * sent without it; the admin URL is {@code --admin}, else FLOTTILA_ADMIN, else {@code http://localhost:8081}. It prints the node's
 * answer body and exits 0 on a 2xx, 1 on any other status or an unreachable node, 2 on a usage error (nothing sent then).
 * The node is a {@link StubAdmin} on an ephemeral loopback port.
 */
class DeployCommandTest {

    static final String TOKEN = "deploy-test-token-7f3a";
    static final String ANSWER = "{\"package\":\"demo\",\"flows\":[\"demo/invoice-sync\"]}";

    @TempDir Path tmp;
    final List<StubAdmin> stubs = new ArrayList<>();

    @AfterEach
    void stopStubs() {
        stubs.forEach(StubAdmin::close);
    }

    StubAdmin stub() throws IOException {
        StubAdmin s = new StubAdmin();
        stubs.add(s);
        return s;
    }

    /** The demo copied to a directory that is not called demo: a name taken from the directory would show. */
    Path demoElsewhere() throws IOException {
        return CliRuns.copy(DEMO, tmp.resolve("not-the-name"));
    }

    static List<String> entryNames(byte[] zip) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) names.add(e.getName());
        }
        return names;
    }

    static int closedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 0, InetAddress.ofLiteral("127.0.0.1"))) {
            return s.getLocalPort();
        }
    }

    // --- the request

    /**
     * The demo, the token in the environment: one PUT to /admin/packages/demo (package.yaml's name), no query, Content-Type
     * application/zip, {@code Authorization: Bearer <token>}, the package as a zip; the answer's body is printed, the token never.
     */
    @Test
    void putsTheZipWithBearerFromEnvAndPrintsTheAnswer() throws Exception {
        StubAdmin admin = stub().answer(200, ANSWER);

        Ran ran = run(Map.of("FLOTTILA_ADMIN_TOKEN", TOKEN), "deploy", demoElsewhere().toString(), "--admin", admin.url());

        assertEquals(0, ran.exit(), ran.toString());
        StubAdmin.Request put = admin.only();
        assertEquals("PUT", put.method());
        assertEquals("/admin/packages/demo", put.path(), "the name is package.yaml's, not the directory's");
        assertNull(put.query(), "no revision, no query");
        assertEquals(List.of("application/zip"), put.header("content-type"));
        assertEquals(List.of("Bearer " + TOKEN), put.header("authorization"));
        assertTrue(entryNames(put.body()).contains("package.yaml"), "the body is the package's zip: " + entryNames(put.body()));
        assertTrue(ran.out().contains(ANSWER), "the answer's body is printed: " + ran);
        assertFalse(ran.out().contains(TOKEN) || ran.err().contains(TOKEN), "the token is never printed: " + ran);
    }

    @Test
    void noTokenNoHeader() throws Exception {
        StubAdmin admin = stub();

        Ran ran = run(Map.of(), "deploy", DEMO.toString(), "--admin", admin.url());

        assertEquals(0, ran.exit(), ran.toString());
        assertEquals(List.of(), admin.only().header("authorization"), "no FLOTTILA_ADMIN_TOKEN, no Authorization header");
    }

    @Test
    void revisionAndNameInTheUrl() throws Exception {
        StubAdmin admin = stub();

        Ran both = run(Map.of(), "deploy", DEMO.toString(), "--name", "shop", "--revision", "r-7.1_b", "--admin", admin.url());
        Ran nameOnly = run(Map.of(), "deploy", DEMO.toString(), "--admin", admin.url(), "--name", "store");

        assertEquals(0, both.exit(), both.toString());
        assertEquals(0, nameOnly.exit(), nameOnly.toString());
        List<StubAdmin.Request> puts = admin.requests();
        assertEquals(2, puts.size(), "one PUT per run");
        assertEquals("/admin/packages/shop", puts.get(0).path());
        assertEquals("revision=r-7.1_b", puts.get(0).query());
        assertEquals("/admin/packages/store", puts.get(1).path());
        assertNull(puts.get(1).query(), "no --revision, no query");
    }

    /** Without {@code --admin} the URL is FLOTTILA_ADMIN; {@code --admin} wins over it. */
    @Test
    void theAdminUrlIsTheFlagElseFlottilaAdmin() throws Exception {
        StubAdmin fromEnv = stub();
        StubAdmin fromFlag = stub();

        Ran byEnv = run(Map.of("FLOTTILA_ADMIN", fromEnv.url()), "deploy", DEMO.toString());
        Ran byFlag = run(Map.of("FLOTTILA_ADMIN", fromEnv.url()), "deploy", DEMO.toString(), "--admin", fromFlag.url());

        assertEquals(0, byEnv.exit(), byEnv.toString());
        assertEquals(0, byFlag.exit(), byFlag.toString());
        assertEquals(1, fromEnv.requests().size(), "the run without --admin went to FLOTTILA_ADMIN, the other did not");
        assertEquals("/admin/packages/demo", fromEnv.only().path());
        assertEquals("/admin/packages/demo", fromFlag.only().path(), "--admin wins over FLOTTILA_ADMIN");
    }

    // --- the answer

    @ParameterizedTest(name = "{0}")
    @ValueSource(ints = {200, 201, 202, 204})
    void every2xxExits0(int status) throws Exception {
        StubAdmin admin = stub().answer(status, status == 204 ? "" : ANSWER);

        Ran ran = run(Map.of(), "deploy", DEMO.toString(), "--admin", admin.url());

        assertEquals(0, ran.exit(), status + ": " + ran);
        assertEquals(1, admin.requests().size());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(ints = {302, 400, 401, 403, 404, 409, 422, 500, 503})
    void non2xxExit1(int status) throws Exception {
        String body = "{\"error\":\"refused with " + status + "\",\"problems\":[\"flows/x.yaml at '/steps': p\"]}";
        StubAdmin admin = stub().answer(status, body);

        Ran ran = run(Map.of(), "deploy", DEMO.toString(), "--admin", admin.url());

        assertEquals(1, ran.exit(), status + ": " + ran);
        assertTrue(ran.out().contains(body), "the answer's body is printed: " + ran);
        assertEquals(1, admin.requests().size(), "sent once, never retried");
    }

    @Test
    void unreachableExit1() throws Exception {
        Ran ran = run(Map.of(), "deploy", DEMO.toString(), "--admin", "http://127.0.0.1:" + closedPort());

        assertEquals(1, ran.exit(), ran.toString());
        assertFalse(ran.err().isBlank(), "stderr says the node could not be reached: " + ran);
    }

    // --- usage: exit 2, nothing sent

    static Stream<List<String>> deployUsageErrors() {
        return Stream.of(List.of("deploy"), List.of("deploy", "no-such-package-dir"), List.of("deploy", DEMO.toString(), "--revision"),
                List.of("deploy", DEMO.toString(), "--name"), List.of("deploy", DEMO.toString(), "--token", TOKEN),
                List.of("deploy", DEMO.toString(), "--bogus", "x"), List.of("deploy", DEMO.toString(), DEMO.toString()));
    }

    /** A usage error — no dir, a missing dir, an option without its value, an unknown option ({@code --token} among them: the token is env-only), an extra argument — sends nothing. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("deployUsageErrors")
    void deployUsageErrorsExit2AndSendNothing(List<String> args) throws Exception {
        StubAdmin admin = stub();

        Ran ran = run(Map.of("FLOTTILA_ADMIN", admin.url()), args.toArray(String[]::new));

        assertEquals(2, ran.exit(), ran.toString());
        assertEquals("", ran.out(), ran.toString());
        assertFalse(ran.err().isBlank(), "usage on stderr: " + ran);
        assertEquals(List.of(), admin.requests(), "nothing sent");
    }
}
