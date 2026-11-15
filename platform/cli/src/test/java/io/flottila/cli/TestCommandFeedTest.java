package io.flottila.cli;

import io.flottila.cli.CliRuns.Ran;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.flottila.cli.CliRuns.FIXTURES;
import static io.flottila.cli.CliRuns.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-03b2 (D-080): a case of {@code flottila test} may feed its flow instead of sending to it — no {@code send}, and the from
 * endpoint's mock (the document's entry, or the case's that replaces it) given {@code files} or {@code messages}. The mock's
 * polling consumer delivers the fixtures from its round 0 at the consumer's start, one at a time in name order; the case
 * settles once no fed file is left undelivered (removed, or refused finally — a final Nack is settled, not pending), the work
 * queue is empty and the world is quiet. The report line is unchanged.
 *
 * <p>The {@code feed} fixture: two sftp from endpoints (config valid against the real sftp schema), {@code from-drop} a sync flow
 * to the partner, {@code keyed-drop} at least once, partitioned by the order id (an id over 256 characters is a final Nack at the
 * door, D-075); the partner answers after 250 ms, so three orders delivered one after the other end at 750 ms.
 *
 * <p>Choice (packet "choose and say"): {@code expect.error} on a case without {@code send} is a problem — a feed case has no
 * answer, it may deliver several messages, and a door's refusal leaves no timeline to read a failed step's error from.
 */
class TestCommandFeedTest {

    static final Path FEED = FIXTURES.resolve("feed");

    @TempDir Path tmp;

    /** A copy of {@code feed} under {@code tmp} without its test documents, with {@code docs} (file name under flows/ → text) instead. */
    static Path feedWith(Path tmp, Map<String, String> docs) throws IOException {
        Path pkg = CliRuns.copy(FEED, tmp.resolve("feed-" + System.nanoTime()));
        try (Stream<Path> flows = Files.list(pkg.resolve("flows"))) {
            for (Path doc : flows.filter(f -> f.getFileName().toString().endsWith(".test.yaml")).toList()) Files.delete(doc);
        }
        for (Map.Entry<String, String> doc : docs.entrySet()) Files.writeString(pkg.resolve("flows").resolve(doc.getKey()), doc.getValue());
        return pkg;
    }

    /**
     * The package is valid — {@code validate} under {@code ci}, the sftp configs against the real sftp schema, its case without
     * send — exit 0.
     */
    private static void assertValid(Path pkg) {
        Ran valid = run(Map.of(), "validate", pkg.toString(), "--env", "ci");
        assertEquals(0, valid.exit(), "the package, its case without send included, is valid: " + valid);
    }

    /**
     * Three orders fed as files and no send: each is delivered and answered (the partner's 250 ms) one after the other and the
     * case settles only once the last is removed — flow time 750 ms, three sends to the partner. The failing twin shows the count
     * the runner observed, not a check that was skipped.
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void filesAreDeliveredAndRemoved() throws IOException {
        Path pkg = feedWith(tmp, Map.of("from-drop.test.yaml", """
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
                        partner: { count: 3, contains: "<Customer>Acme Corp</Customer>" }
                  - name: count two
                    expect: { sent: { partner: { count: 2 } } }
                """));
        assertValid(pkg);

        Ran ran = run("test", pkg.toString());

        assertEquals(List.of(
                "PASS from-drop: three orders (flow time 750ms)",
                "FAIL from-drop: count two (flow time 750ms)",
                "  expect.sent.partner.count: expected 2, was 3",
                "2 cases: 1 passed, 1 failed"), ran.outLines(), ran.toString());
        assertEquals(1, ran.exit(), ran.toString());
    }

    /**
     * The first fed file's order id is 300 characters: the flow Nacks it finally at the door, the mock keeps it and never delivers
     * it again — settled, not pending; the second is accepted and sent by the worker (250 ms). The case settles: one send, nothing
     * parked. The failing twin shows the dlq observed.
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aFinallyRefusedFileSettles() throws IOException {
        Path pkg = feedWith(tmp, Map.of("keyed-drop.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: keyed-drop
                mocks:
                  drop-keyed: { files: fixtures/refusing/*.xml }
                  partner: { reply: fixtures/ack.xml, delay: 250ms }
                cases:
                  - name: one refused one accepted
                    expect:
                      dlq: 0
                      retries: 0
                      sent:
                        partner: { count: 1, xpath: /Order/Id, equals: "7" }
                  - name: expects a parked message
                    expect: { dlq: 1 }
                """));
        assertEquals(300 + "<Order><Id></Id><Customer>Acme Corp</Customer></Order>".length(),
                Files.size(pkg.resolve("fixtures/refusing/1-long-key.xml")), "fixture: the first file's order id is 300 characters");

        Ran ran = run("test", pkg.toString());

        assertEquals(List.of(
                "PASS keyed-drop: one refused one accepted (flow time 250ms)",
                "FAIL keyed-drop: expects a parked message (flow time 250ms)",
                "  expect.dlq: expected 1, was 0",
                "2 cases: 1 passed, 1 failed"), ran.outLines(), ran.toString());
        assertEquals(1, ran.exit(), ran.toString());
    }

    /**
     * {@code send} is optional: a test document whose case has none — its feed in the case's own {@code mocks}, the document
     * binding only the partner — is valid ({@code validate} exits 0) and the case runs on the feed and passes.
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void sendOptionalWithAFeed() throws IOException {
        Path pkg = feedWith(tmp, Map.of("from-drop.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: from-drop
                mocks:
                  partner: { reply: fixtures/ack.xml, delay: 250ms }
                cases:
                  - name: one order
                    mocks:
                      drop: { files: fixtures/inbox/order-2.xml }
                    expect:
                      sent:
                        partner: { count: 1, xpath: /Order/Id, equals: "2" }
                """));

        Ran valid = run("validate", pkg.toString());
        assertEquals(0, valid.exit(), "a case without send is valid: " + valid);
        assertEquals(List.of(), valid.problems(), valid.toString());

        Ran ran = run("test", pkg.toString());

        assertEquals(List.of(
                "PASS from-drop: one order (flow time 250ms)",
                "1 cases: 1 passed, 0 failed"), ran.outLines(), ran.toString());
        assertEquals(0, ran.exit(), ran.toString());
    }

    /** {@code messages} feeds the from endpoint as {@code files} does: the same three orders, the same report. */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void messagesFeedLikeFiles() throws IOException {
        Path pkg = feedWith(tmp, Map.of("from-drop.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: from-drop
                mocks:
                  drop: { messages: fixtures/inbox/*.xml }
                  partner: { reply: fixtures/ack.xml, delay: 250ms }
                cases:
                  - name: three orders
                    expect:
                      sent:
                        partner: { count: 3, contains: "<Customer>Acme Corp</Customer>" }
                """));

        Ran ran = run("test", pkg.toString());

        assertEquals(List.of(
                "PASS from-drop: three orders (flow time 750ms)",
                "1 cases: 1 passed, 0 failed"), ran.outLines(), ran.toString());
        assertEquals(0, ran.exit(), ran.toString());
    }

    /**
     * A case with no send and no feed on its flow's from endpoint sends nothing: a problem naming the document, the case and the
     * endpoint — a feed on another endpoint (drop-keyed) does not count — no case runs, the fine one beside it neither; exit 1.
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void noSendNoFeedIsAProblem() throws IOException {
        Path pkg = feedWith(tmp, Map.of("from-drop.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: from-drop
                mocks:
                  drop-keyed: { files: fixtures/inbox/*.xml }
                  partner: { reply: fixtures/ack.xml }
                cases:
                  - name: fine
                    mocks:
                      drop: { files: fixtures/inbox/*.xml }
                    expect: { sent: { partner: { count: 3 } } }
                  - name: nothing
                    expect: { sent: { partner: { count: 0 } } }
                """));

        Ran ran = run("test", pkg.toString());

        assertEquals(List.of("problem: flows/from-drop.test.yaml: case 'nothing' sends nothing: no send and no files/messages feed on 'drop'"),
                ran.problems(), ran.toString());
        assertTrue(ran.outLines().stream().noneMatch(TestCommandTest::caseLine), "no case ran, the fine one neither: " + ran);
        assertEquals(1, ran.exit(), ran.toString());
    }

    /**
     * {@code expect.status} is the answer to a send: on a case without one (fed by {@code files}) it is a problem naming the
     * document and the case; no case runs, exit 1.
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void statusWithoutSendIsAProblem() throws IOException {
        Path pkg = feedWith(tmp, Map.of("from-drop.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: from-drop
                mocks:
                  drop: { files: fixtures/inbox/*.xml }
                cases:
                  - name: fine
                    expect: { sent: { partner: { count: 3 } } }
                  - name: status
                    expect: { status: 200 }
                """));

        Ran ran = run("test", pkg.toString());

        assertTrue(ran.problems().contains("problem: flows/from-drop.test.yaml: case 'status' expects a status but sends nothing"), ran.toString());
        assertTrue(ran.problems().stream().allMatch(p -> p.contains("case 'status'")), "the fine case is no problem: " + ran);
        assertTrue(ran.outLines().stream().noneMatch(TestCommandTest::caseLine), "no case ran: " + ran);
        assertEquals(1, ran.exit(), ran.toString());
    }

    /**
     * The choice above: {@code expect.error} on a case without send is a problem naming the document, the case and
     * {@code error}, saying it sends nothing; no case runs, exit 1.
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void errorWithoutSendIsAProblem() throws IOException {
        Path pkg = feedWith(tmp, Map.of("from-drop.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: from-drop
                mocks:
                  drop: { files: fixtures/inbox/*.xml }
                  partner: { fail: 400 }
                cases:
                  - name: error
                    expect: { error: "answered 400" }
                """));

        Ran ran = run("test", pkg.toString());

        assertEquals(1, ran.problems().size(), ran.toString());
        String problem = ran.problems().getFirst();
        assertTrue(problem.startsWith("problem: flows/from-drop.test.yaml: case 'error' ") && problem.contains("error")
                && problem.endsWith("sends nothing"), "names the document, the case and error, says it sends nothing: " + ran);
        assertTrue(ran.outLines().stream().noneMatch(TestCommandTest::caseLine), "no case ran: " + ran);
        assertEquals(1, ran.exit(), ran.toString());
    }

    /**
     * A feed glob whose directory is not in the package is a problem naming the glob — before any case runs (not a deploy refused
     * inside the case); exit 1. In the document's mocks and in a case's alike, {@code files} and {@code messages} alike.
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void feedDirMissingIsAProblem() throws IOException {
        record Variant(String name, String documentMocks, String caseMocks, String glob) {}
        List<Variant> variants = List.of(
                new Variant("document files", "drop: { files: missing/*.xml }", "partner: { reply: fixtures/ack.xml }", "missing/*.xml"),
                new Variant("case messages", "drop: { files: fixtures/inbox/*.xml }", "drop: { messages: absent/orders/*.xml }", "absent/orders/*.xml"));
        for (Variant v : variants) {
            Path pkg = feedWith(tmp, Map.of("from-drop.test.yaml", """
                    schemaVersion: 1
                    kind: test
                    flow: from-drop
                    mocks:
                      %s
                    cases:
                      - name: fed
                        mocks:
                          %s
                        expect: { sent: { partner: { count: 1 } } }
                    """.formatted(v.documentMocks(), v.caseMocks())));
            assertTrue(Files.notExists(pkg.resolve(v.glob()).getParent()), "fixture: the glob's folder does not exist: " + v);

            Ran ran = run("test", pkg.toString());

            assertEquals(1, ran.problems().size(), v + ": " + ran);
            assertTrue(ran.problems().getFirst().startsWith("problem: flows/from-drop.test.yaml: ") && ran.problems().getFirst().contains(v.glob()),
                    v + ": names the document and the glob: " + ran);
            assertTrue(ran.outLines().stream().noneMatch(TestCommandTest::caseLine), v + ": no case ran: " + ran);
            assertEquals(1, ran.exit(), v + ": " + ran);
        }
    }

    /**
     * A fed file the flow never takes — the partner refuses every send (400, not retried), so the sync flow's answer fails and
     * the mock keeps the file for its next round, every second — never drains: the case fails at the 10 min horizon, "did not
     * settle", no expectation evaluated (the one given would hold: three sends at least).
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aFeedThatNeverDrainsFailsAtTheHorizon() throws IOException {
        Path pkg = feedWith(tmp, Map.of("from-drop.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: from-drop
                mocks:
                  drop: { files: fixtures/inbox/order-1.xml }
                  partner: { fail: 400 }
                cases:
                  - name: never drains
                    expect: { dlq: 0 }
                """));

        Ran ran = run("test", pkg.toString());

        List<String> lines = ran.outLines();
        assertEquals(3, lines.size(), ran.toString());
        assertEquals("FAIL from-drop: never drains (flow time 10m)", lines.get(0), ran.toString());
        assertTrue(lines.get(1).startsWith("  did not settle: "), ran.toString());
        assertEquals("1 cases: 0 passed, 1 failed", lines.get(2), ran.toString());
        assertEquals(1, ran.exit(), ran.toString());
    }

    /**
     * (P2-03b2, adopted from the implementer's scratch) A feed glob that matches no file delivers nothing: the case settles at
     * once (round 0 at the consumer's start) and is evaluated.
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aFeedThatMatchesNoFileSettlesAtOnce() throws IOException {
        Path pkg = feedWith(tmp, Map.of("from-drop.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: from-drop
                mocks:
                  drop: { files: fixtures/inbox/*.json }
                cases:
                  - name: nothing matched
                    expect: { dlq: 0, sent: { partner: { count: 0 } } }
                """));

        Ran ran = run("test", pkg.toString());

        assertEquals(List.of("PASS from-drop: nothing matched (flow time 0ms)", "1 cases: 1 passed, 0 failed"), ran.outLines(), ran.toString());
        assertEquals(0, ran.exit(), ran.toString());
    }

    /**
     * (P2-03b2 [A]) {@code steps} and {@code keys} read the case's message: a feed case that delivered none fails them with
     * {@code was no message} — never evaluated against a message that is not there ({@code steps: 0} would hold on an empty
     * timeline) — in the order {@code expect} lists them; {@code dlq} beside them still holds.
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void stepsAndKeysOnAFeedThatDeliveredNoMessageFail() throws IOException {
        Path pkg = feedWith(tmp, Map.of("from-drop.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: from-drop
                mocks:
                  drop: { files: fixtures/inbox/*.json }
                cases:
                  - name: nothing delivered
                    expect: { steps: 0, dlq: 0, keys: { orderId: "1" } }
                """));

        Ran ran = run("test", pkg.toString());

        assertEquals(List.of(
                "FAIL from-drop: nothing delivered (flow time 0ms)",
                "  expect.steps: expected 0, was no message",
                "  expect.keys.orderId: expected 1, was no message",
                "1 cases: 0 passed, 1 failed"), ran.outLines(), ran.toString());
        assertEquals(1, ran.exit(), ran.toString());
    }
}
