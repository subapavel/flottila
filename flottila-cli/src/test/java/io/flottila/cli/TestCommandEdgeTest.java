package io.flottila.cli;

import io.flottila.cli.CliRuns.Ran;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.flottila.cli.CliRuns.run;
import static io.flottila.cli.TestCommandTest.flowtestWith;
import static io.flottila.cli.TestCommandTest.withSftpFlow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-03b1 fix: what {@code flottila test} does where the packet's cases left a branch open — {@code retries} per message and
 * step (two {@code to} steps to one endpoint are no retry), {@code sent} with a value check and no send (fails: was no send, [A]),
 * an {@code xpath} that does not compile (a problem), a deploy refused inside a case, {@code error} against a final Nack and an
 * Accepted answer, {@code sent} xpath over a body that is no XML.
 */
class TestCommandEdgeTest {

    @TempDir Path tmp;

    /**
     * {@code twice} sends the one message to the partner from two steps (first, second): two sends, no retry. The failing twin
     * shows the count the runner computed (0), not a check that was skipped.
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void twoToStepsToOneEndpointAreNoRetry() throws IOException {
        Path pkg = flowtestWith(tmp, Map.of("twice.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: twice
                mocks:
                  archive: { reply: fixtures/ack.xml }
                cases:
                  - name: no retry
                    send: fixtures/order.xml
                    expect: { retries: 0, sent: { partner: { count: 2 } } }
                  - name: one retry
                    send: fixtures/order.xml
                    expect: { retries: 1 }
                """));

        Ran ran = run("test", pkg.toString());

        assertEquals(List.of(
                "PASS twice: no retry (flow time 0ms)",
                "FAIL twice: one retry (flow time 0ms)",
                "  expect.retries: expected 1, was 0",
                "2 cases: 1 passed, 1 failed"), ran.outLines(), ran.toString());
        assertEquals(1, ran.exit(), ran.toString());
    }

    /**
     * The archive fails (not retryable): the partner is never sent to. {@code count: 0} holds; {@code equals}, {@code contains}
     * and {@code xpath} with {@code equals} on the partner's sends fail — {@code was no send} — never pass on no send at all.
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aValueCheckOnNoSendFails() throws IOException {
        Path pkg = flowtestWith(tmp, Map.of("sync-order.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: sync-order
                mocks:
                  archive: { fail: 503 }
                cases:
                  - name: count zero
                    send: fixtures/order.xml
                    expect: { sent: { partner: { count: 0 }, archive: { count: 1 } } }
                  - name: equals
                    send: fixtures/order.xml
                    expect: { sent: { partner: { equals: "<Order/>" } } }
                  - name: contains
                    send: fixtures/order.xml
                    expect: { sent: { partner: { contains: "<Id>42</Id>" } } }
                  - name: xpath equals
                    send: fixtures/order.xml
                    expect: { sent: { partner: { xpath: /Order/Id, equals: "42" } } }
                """));

        Ran ran = run("test", pkg.toString());

        assertEquals(List.of(
                "PASS sync-order: count zero (flow time 0ms)",
                "FAIL sync-order: equals (flow time 0ms)",
                "  expect.sent.partner.equals: expected <Order/>, was no send",
                "FAIL sync-order: contains (flow time 0ms)",
                "  expect.sent.partner.contains: expected <Id>42</Id>, was no send",
                "FAIL sync-order: xpath equals (flow time 0ms)",
                "  expect.sent.partner.equals: expected 42, was no send",
                "4 cases: 1 passed, 3 failed"), ran.outLines(), ran.toString());
        assertEquals(1, ran.exit(), ran.toString());
    }

    /** An {@code xpath} of {@code sent} that does not compile is a problem naming the document, case and endpoint: no case runs, exit 1. */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void anXpathThatDoesNotCompileIsAProblem() throws IOException {
        Path pkg = flowtestWith(tmp, Map.of("sync-order.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: sync-order
                cases:
                  - name: fine
                    send: fixtures/order.xml
                    expect: { steps: 4 }
                  - name: broken
                    send: fixtures/order.xml
                    expect: { sent: { partner: { xpath: "/Order[", equals: "42" } } }
                """));
        assertEquals(0, run("validate", pkg.toString()).exit(), "the package itself is valid");

        Ran ran = run("test", pkg.toString());

        assertEquals(1, ran.exit(), ran.toString());
        assertEquals(1, ran.problems().size(), ran.toString());
        assertTrue(ran.problems().getFirst().startsWith("problem: flows/sync-order.test.yaml: case 'broken' sent.partner.xpath does not compile: "),
                ran.toString());
        assertTrue(ran.outLines().stream().noneMatch(TestCommandTest::caseLine), "no case ran, the fine one neither: " + ran);
    }

    /**
     * A case whose node refuses the deploy — its sftp door's {@code files} fixtures lie in a folder that does not exist, so the
     * mock consumer cannot start — fails with the refusal's reason; the case after it, on a world of its own, still runs and passes.
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aDeployRefusedInsideACaseFailsTheCaseWithItsReason() throws IOException {
        Path pkg = withSftpFlow(flowtestWith(tmp, Map.of("from-sftp.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: from-sftp
                cases:
                  - name: missing fixtures
                    send: fixtures/order.xml
                    mocks:
                      drop: { files: missing/*.xml }
                    expect: { sent: { partner: { count: 1 } } }
                  - name: dropped order
                    send: fixtures/order.xml
                    expect: { sent: { partner: { count: 1 } } }
                """)));
        assertTrue(Files.notExists(pkg.resolve("missing")), "fixture: the folder does not exist");

        Ran ran = run("test", pkg.toString());

        List<String> lines = ran.outLines();
        assertEquals(4, lines.size(), ran.toString());
        assertEquals("FAIL from-sftp: missing fixtures (flow time 0ms)", lines.get(0), ran.toString());
        assertTrue(lines.get(1).startsWith("  deploy refused: "), ran.toString());
        assertTrue(lines.get(1).contains("drop"), "the reason names the endpoint: " + ran);
        assertEquals("PASS from-sftp: dropped order (flow time 0ms)", lines.get(2), ran.toString());
        assertEquals("2 cases: 1 passed, 1 failed", lines.get(3), ran.toString());
        assertEquals(1, ran.exit(), ran.toString());
    }

    /**
     * {@code error} reads a final Nack's reason: async-order partitioned by an order id of 300 characters is refused at the door
     * (400, D-075) — the reason contains the text, or the line shows the reason whole.
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void errorReadsAFinalNacksReason() throws IOException {
        Path pkg = flowtestWith(tmp, Map.of("async-order.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: async-order
                cases:
                  - name: reason holds
                    send: fixtures/long-key.xml
                    expect: { status: 400, dlq: 0, sent: { partner: { count: 0 } }, error: "over the bound of 256 characters" }
                  - name: reason differs
                    send: fixtures/long-key.xml
                    expect: { error: "timed out" }
                """));
        CliRuns.rewrite(pkg.resolve("flows/async-order.yaml"), Map.of("recording:", "partitionBy: orderId\nrecording:"));
        Files.writeString(pkg.resolve("fixtures/long-key.xml"), "<Order><Id>" + "k".repeat(300) + "</Id><Customer>Acme Corp</Customer></Order>");

        Ran ran = run("test", pkg.toString());

        assertEquals(List.of(
                "PASS async-order: reason holds (flow time 0ms)",
                "FAIL async-order: reason differs (flow time 0ms)",
                "  expect.error: expected timed out, was partition key of 300 characters is over the bound of 256 characters (D-075)",
                "2 cases: 1 passed, 1 failed"), ran.outLines(), ran.toString());
        assertEquals(1, ran.exit(), ran.toString());
    }

    /** An Accepted answer (202) has no error: {@code error} fails with was (none). */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void errorOnAnAcceptedAnswerFailsWithNone() throws IOException {
        Path pkg = flowtestWith(tmp, Map.of("async-order.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: async-order
                mocks:
                  partner: { reply: fixtures/ack.xml, delay: 250ms }
                cases:
                  - name: accepted
                    send: fixtures/order.xml
                    expect: { status: 202, error: "503" }
                """));

        Ran ran = run("test", pkg.toString());

        assertEquals(List.of(
                "FAIL async-order: accepted (flow time 250ms)",
                "  expect.error: expected 503, was (none)",
                "1 cases: 0 passed, 1 failed"), ran.outLines(), ran.toString());
        assertEquals(1, ran.exit(), ran.toString());
    }

    /**
     * {@code sent} xpath over a send whose body is no XML: the case fails, the line says the send is unreadable — the run goes on
     * to the summary (no exception out of the runner).
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void sentXpathOverABodyThatIsNoXmlIsUnreadable() throws IOException {
        Path pkg = flowtestWith(tmp, Map.of("twice.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: twice
                mocks:
                  archive: { reply: fixtures/ack.xml }
                cases:
                  - name: plain text
                    send: fixtures/plain.txt
                    expect: { sent: { partner: { count: 2, xpath: /Order/Id, equals: "42" } } }
                """));
        Files.writeString(pkg.resolve("fixtures/plain.txt"), "not xml at all");

        Ran ran = run("test", pkg.toString());

        List<String> lines = ran.outLines();
        assertEquals(3, lines.size(), ran.toString());
        assertEquals("FAIL twice: plain text (flow time 0ms)", lines.get(0), ran.toString());
        assertTrue(lines.get(1).startsWith("  expect.sent.partner.equals: expected 42, was unreadable: "), ran.toString());
        assertEquals("1 cases: 0 passed, 1 failed", lines.get(2), ran.toString());
        assertEquals(1, ran.exit(), ran.toString());
    }

    /**
     * A {@code sent} check that checks nothing is a package problem (the test schema): an xpath without equals or contains, an
     * empty entry, an empty {@code sent} — beside equals with contains, refused already. Exit 1, the problem names the test
     * document and the place, no case runs.
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aSentThatChecksNothingIsAPackageProblem() throws IOException {
        record Variant(String name, String sent, String at) {}
        List<Variant> variants = List.of(
                new Variant("equals-and-contains", "{ partner: { xpath: /Order/Id, equals: \"42\", contains: \"4\" } }", "/cases/0/expect/sent/partner"),
                new Variant("xpath-alone", "{ partner: { xpath: /Order/Id } }", "/cases/0/expect/sent/partner"),
                new Variant("empty-entry", "{ partner: {} }", "/cases/0/expect/sent/partner"),
                new Variant("empty-sent", "{}", "/cases/0/expect/sent"));
        List<String> wrong = new ArrayList<>();
        for (Variant v : variants) {
            Path pkg = flowtestWith(tmp, Map.of("sync-order.test.yaml", """
                    schemaVersion: 1
                    kind: test
                    flow: sync-order
                    cases:
                      - name: vacuous
                        send: fixtures/order.xml
                        expect: { sent: %s }
                    """.formatted(v.sent())));

            Ran ran = run("test", pkg.toString());

            boolean refused = ran.exit() == 1
                    && ran.problems().stream().anyMatch(l -> l.contains("flows/sync-order.test.yaml") && l.contains("'" + v.at() + "'"))
                    && ran.outLines().stream().noneMatch(TestCommandTest::caseLine);
            if (!refused) wrong.add(v.name() + ": " + ran);
        }
        assertEquals(List.of(), wrong, "each variant: exit 1, a problem at its place, no case line");
    }

    /**
     * A {@code sent} key that names no endpoint of the package is a problem naming the test document, the case and the key:
     * exit 1, no case runs, no stack trace.
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aSentKeyNamingNoEndpointIsAProblem() throws IOException {
        Path pkg = flowtestWith(tmp, Map.of("sync-order.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: sync-order
                cases:
                  - name: fine
                    send: fixtures/order.xml
                    expect: { steps: 4 }
                  - name: ghost
                    send: fixtures/order.xml
                    expect: { sent: { nowhere: { count: 0 } } }
                """));

        Ran ran = run("test", pkg.toString());

        assertEquals(1, ran.exit(), ran.toString());
        assertEquals(1, ran.problems().size(), ran.toString());
        String problem = ran.problems().getFirst();
        assertTrue(problem.contains("flows/sync-order.test.yaml") && problem.contains("nowhere")
                && (problem.contains("'ghost'") || problem.contains("/cases/1/expect/sent")), "names the document, the case and the key: " + ran);
        assertTrue(ran.outLines().stream().noneMatch(TestCommandTest::caseLine), "no case ran, the fine one neither: " + ran);
        assertTrue(!ran.err().contains("Exception") && !ran.err().contains("\tat "), "no stack trace: " + ran);
    }
}
