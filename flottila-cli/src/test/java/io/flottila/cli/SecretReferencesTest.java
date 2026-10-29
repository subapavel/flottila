package io.flottila.cli;

import io.flottila.cli.CliRuns.Ran;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static io.flottila.cli.CliRuns.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-04 (D-081): off the node no secret is needed — {@code flottila validate} and {@code flottila test} resolve every well-formed
 * {@code secret://<name>} to a placeholder, with an empty environment — but the reference's syntax is checked: a malformed one is
 * a problem. The package is the {@code feed} fixture with its {@code drop} endpoint's sftp password a reference.
 */
class SecretReferencesTest {

    @TempDir Path tmp;

    /** {@code feed} with the {@code drop} endpoint's password replaced by {@code password} (a YAML scalar, quoted as needed). */
    Path feedWithPassword(String password) throws IOException {
        Path pkg = TestCommandFeedTest.feedWith(tmp, Map.of("from-drop.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: from-drop
                mocks:
                  drop: { files: fixtures/inbox/*.xml }
                  partner: { reply: fixtures/ack.xml, delay: 250ms }
                cases:
                  - name: three orders
                    expect:
                      dlq: 0
                      sent:
                        partner: { count: 3 }
                """));
        CliRuns.rewrite(pkg.resolve("endpoints/drop.yaml"), Map.of("password: secret\n", "password: " + password + "\n"));
        return pkg;
    }

    @Test
    void validateNeedsNoSecret() throws IOException {
        Path pkg = feedWithPassword("secret://drop-password");

        Ran ran = run(Map.of(), "validate", pkg.toString());

        assertEquals(0, ran.exit(), "no FLOTTILA_SECRET_DROP_PASSWORD in the environment, the package is valid: " + ran);
        assertEquals(List.of(), ran.problems(), ran.toString());
    }

    /** GATE.md: the partner URL comes through secret:// too — the http producer is built over the placeholder URL, no env. */
    @Test
    void validateNeedsNoSecretForAnHttpUrlAndAnSftpPassword() throws IOException {
        Path pkg = feedWithPassword("secret://p");
        CliRuns.rewrite(pkg.resolve("endpoints/partner.yaml"), Map.of("url: http://localhost:8090/partner", "url: secret://partner-url"));

        Ran ran = run(Map.of(), "validate", pkg.toString());

        assertEquals(0, ran.exit(), "no FLOTTILA_SECRET_* in the environment, the package is valid: " + ran);
        assertEquals(List.of(), ran.problems(), ran.toString());
    }

    @Test
    void validateRefusesAMalformedReference() throws IOException {
        Path pkg = feedWithPassword("\"secret://Bad Name\"");

        Ran ran = run(Map.of("FLOTTILA_SECRET_BAD_NAME", "x"), "validate", pkg.toString());

        assertEquals(1, ran.exit(), ran.toString());
        assertTrue(ran.problems().stream().anyMatch(l -> l.startsWith("problem: endpoint 'drop'") && l.contains("secret://Bad Name")),
                "a problem names the endpoint and the reference: " + ran);
    }

    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void testNeedsNoSecret() throws IOException {
        Path pkg = feedWithPassword("secret://drop-password");

        Ran ran = run(Map.of(), "test", pkg.toString());

        assertEquals(List.of("PASS from-drop: three orders (flow time 750ms)", "1 cases: 1 passed, 0 failed"), ran.outLines(), ran.toString());
        assertEquals(0, ran.exit(), ran.toString());
    }
}
