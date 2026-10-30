package io.flottila.cli;

import io.flottila.cli.CliRuns.Ran;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * P2-03b1 (D-080): {@code flottila test <dir>} — the package's problems and the dry build under env {@code ci} first (any problem:
 * printed, no case runs, exit 1); then every {@code kind: test} document in path order, its cases in order, each on a fresh fake
 * runtime with one mock per shipped scheme, the case's {@code mocks} replacing the document's entry of an endpoint whole, the
 * {@code send} ingested on the flow's from endpoint, settled on virtual time (a 10 min flow-time horizon) and evaluated.
 *
 * <p>The report these tests pin (P2-03b1 test-author, within the packet), stdout only, nothing else on it:
 * <ul>
 *   <li>per case {@code PASS <flow>: <case> (flow time <d>)} or {@code FAIL <flow>: <case> (flow time <d>)}; {@code <d>} is the
 *       virtual time from the case's start to its end in the schema's duration notation — the largest of {@code h}, {@code m},
 *       {@code s}, {@code ms} that divides it exactly, {@code 0ms} for none ({@code 20ms}, {@code 1250ms}, {@code 9m}, {@code 10m});</li>
 *   <li>after a FAIL, one line per failed key in the order the case's {@code expect} lists them, {@code   expect.<key>: expected X,
 *       was Y}, where a {@code keys} entry is {@code keys.<name>} and a {@code sent} entry {@code sent.<endpoint>.count},
 *       {@code sent.<endpoint>.equals} or {@code sent.<endpoint>.contains} (count first; the value of the first send that fails);
 *       a case that did not settle prints {@code   did not settle: <what>} instead, and no key;</li>
 *   <li>last, {@code N cases: P passed, F failed}, literally (also {@code 1 cases}).</li>
 * </ul>
 * No wall time anywhere: the same package gives the same bytes on every run. Exit 0 when every case passed; 1 on a failed case, a
 * package problem ({@code problem: <text>} lines, as validate prints them, and no case line) or no test document; 2 on usage.
 *
 * <p>Flow times follow from the fixtures: mock delays and the retry backoff 100 + 200 + 400 ms (attempts 3, multiplier 2); the
 * at-least-once flow's worker polls once after each {@code Accepted} ({@code FlowWorker.pollingAfterAccept}), so its message
 * runs at once, not at the worker's first tick.
 */
class TestCommandTest {

    /** {@code bin/flottila test packages/demo} — CI's step. */
    static final List<String> DEMO_REPORT = List.of(
            "PASS invoice-async: domestic invoice (flow time 20ms)",
            "PASS invoice-async: partner down (flow time 700ms)",
            "PASS invoice-sync: domestic invoice (flow time 20ms)",
            "PASS invoice-sync: partner down (flow time 700ms)",
            "PASS sftp-invoice: invoice file (flow time 20ms)",
            "PASS sftp-invoice: partner down (flow time 700ms)",
            "6 cases: 6 passed, 0 failed");

    /** The {@code flowtest} fixture: test documents in path order (async-order before sync-order), cases in order. */
    static final List<String> FLOWTEST_REPORT = List.of(
            "PASS async-order: partner down (flow time 700ms)",
            "PASS async-order: happy path (flow time 250ms)",
            "PASS sync-order: happy path (flow time 300ms)",
            "PASS sync-order: partner down (flow time 750ms)",
            "4 cases: 4 passed, 0 failed");

    static final Path FLOWTEST = FIXTURES.resolve("flowtest");

    @TempDir Path tmp;

    /** A copy of {@code flowtest} under {@code tmp} without its test documents, with {@code docs} (file name under flows/ → text) instead. */
    static Path flowtestWith(Path tmp, Map<String, String> docs) throws IOException {
        Path pkg = CliRuns.copy(FLOWTEST, tmp.resolve("flowtest-" + System.nanoTime()));
        try (Stream<Path> flows = Files.list(pkg.resolve("flows"))) {
            for (Path doc : flows.filter(f -> f.getFileName().toString().endsWith(".test.yaml")).toList()) Files.delete(doc);
        }
        for (Map.Entry<String, String> doc : docs.entrySet()) Files.writeString(pkg.resolve("flows").resolve(doc.getKey()), doc.getValue());
        return pkg;
    }

    static boolean caseLine(String line) {
        return line.startsWith("PASS ") || line.startsWith("FAIL ") || line.matches("\\d+ cases: .*");
    }

    // --- passing packages: exit 0

    /**
     * CI's step (P2-06, GATE checks 3-5): the demo's three flows pass every case — invoice-sync (a sync flow: {@code dlq: 0,
     * status: 503, retries: 3}), invoice-async over its http door (202, one send, 503 and the DLQ) and sftp-invoice fed an SFTP
     * fixture file (one send of the transformed body, 503 and the DLQ). The partner-down cases end after 100 + 200 + 400 ms of retries.
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void demoPassesEveryCaseOfTheThreeFlowsExit0() {
        Ran ran = run("test", DEMO.toString());

        assertEquals(DEMO_REPORT, ran.outLines(), ran.toString());
        assertEquals(0, ran.exit(), ran.toString());
    }

    /**
     * The witness that the two at-least-once flows of the demo really park one message when the partner fails 503: the same demo
     * with {@code dlq: 1} written {@code dlq: 0} in the two new test documents fails exactly those two cases, each saying it was 1
     * (the passing run above would also pass if the documents never asked).
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void demoPartnerDownCasesOfTheAtLeastOnceFlowsReallyParkOneMessage() throws IOException {
        Path pkg = CliRuns.copy(DEMO, tmp.resolve("demo-no-dlq"));
        for (String doc : List.of("invoice-async.test.yaml", "sftp-invoice.test.yaml")) {
            CliRuns.rewrite(pkg.resolve("flows").resolve(doc), Map.of("dlq: 1", "dlq: 0"));
        }

        Ran ran = run("test", pkg.toString());

        assertEquals(List.of(
                "PASS invoice-async: domestic invoice (flow time 20ms)",
                "FAIL invoice-async: partner down (flow time 700ms)",
                "  expect.dlq: expected 0, was 1",
                "PASS invoice-sync: domestic invoice (flow time 20ms)",
                "PASS invoice-sync: partner down (flow time 700ms)",
                "PASS sftp-invoice: invoice file (flow time 20ms)",
                "FAIL sftp-invoice: partner down (flow time 700ms)",
                "  expect.dlq: expected 0, was 1",
                "6 cases: 4 passed, 2 failed"), ran.outLines(), ran.toString());
        assertEquals(1, ran.exit(), ran.toString());
    }

    /** A sync and an at-least-once flow, a happy path and a partner down each: every expectation of the fixture holds. */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void flowtestPassesEveryCaseExit0() {
        Ran ran = run("test", FLOWTEST.toString());

        assertEquals(FLOWTEST_REPORT, ran.outLines(), ran.toString());
        assertEquals(0, ran.exit(), ran.toString());
    }

    // --- failing expectations: expected X, was Y; exit 1

    /** Each failed key once, with what the case expected and what it was; a case whose expectations hold still passes; the summary counts both. */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void failedExpectationPrintsExpectedAndWasExit1() {
        Ran ran = run("test", FIXTURES.resolve("failing").toString());

        assertEquals(List.of(
                "FAIL invoice-sync: domestic invoice (flow time 20ms)",
                "  expect.sent.partner-domestic.equals: expected 4712, was 4711",
                "  expect.steps: expected 5, was 6",
                "PASS invoice-sync: foreign invoice (flow time 0ms)",
                "FAIL invoice-sync: partner down (flow time 700ms)",
                "  expect.status: expected 502, was 503",
                "  expect.retries: expected 2, was 3",
                "3 cases: 1 passed, 2 failed"), ran.outLines(), ran.toString());
        assertEquals(1, ran.exit(), ran.toString());
    }

    /**
     * Async, partner down: the sender has its 202 at once, the worker's send fails the first try and 3 retries (4 sends of the one
     * message), then the message is parked once. The wrong expectations make the report show what the case observed.
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void asyncPartnerDownParksOnce() throws IOException {
        Path pkg = flowtestWith(tmp, Map.of("async-order.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: async-order
                mocks:
                  partner: { reply: fixtures/ack.xml, delay: 250ms }
                cases:
                  - name: partner down
                    send: fixtures/order.xml
                    mocks:
                      partner: { fail: 503 }
                    expect:
                      status: 200
                      dlq: 0
                      retries: 0
                      sent:
                        partner: { count: 1 }
                """));

        Ran ran = run("test", pkg.toString());

        assertEquals(List.of(
                "FAIL async-order: partner down (flow time 700ms)",
                "  expect.status: expected 200, was 202",
                "  expect.dlq: expected 0, was 1",
                "  expect.retries: expected 0, was 3",
                "  expect.sent.partner.count: expected 1, was 4",
                "1 cases: 0 passed, 1 failed"), ran.outLines(), ran.toString());
        assertEquals(1, ran.exit(), ran.toString());
    }

    /** Async, the partner answers after 250 ms: 202, three steps recorded (ingress, extractKey, partner), one send, nothing parked. */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void asyncHappyPath() throws IOException {
        Path pkg = flowtestWith(tmp, Map.of("async-order.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: async-order
                mocks:
                  partner: { reply: fixtures/ack.xml, delay: 250ms }
                cases:
                  - name: happy path
                    send: fixtures/order.xml
                    expect:
                      status: 201
                      steps: 2
                      dlq: 1
                      sent:
                        partner: { count: 2, xpath: /Order/Id, equals: "41" }
                """));

        Ran ran = run("test", pkg.toString());

        assertEquals(List.of(
                "FAIL async-order: happy path (flow time 250ms)",
                "  expect.status: expected 201, was 202",
                "  expect.steps: expected 2, was 3",
                "  expect.dlq: expected 1, was 0",
                "  expect.sent.partner.count: expected 2, was 1",
                "  expect.sent.partner.equals: expected 41, was 42",
                "1 cases: 0 passed, 1 failed"), ran.outLines(), ran.toString());
        assertEquals(1, ran.exit(), ran.toString());
    }

    /**
     * A case starts from nothing: after a partner-down case (4 sends of message m-1, one DLQ row) the next case's mocks, stores,
     * ids and clock are fresh — it sees one send, no retry, no DLQ row, and its flow time starts at zero again.
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void casesAreIsolated() throws IOException {
        Path pkg = flowtestWith(tmp, Map.of("async-order.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: async-order
                mocks:
                  partner: { reply: fixtures/ack.xml, delay: 250ms }
                cases:
                  - name: partner down
                    send: fixtures/order.xml
                    mocks:
                      partner: { fail: 503 }
                    expect: { dlq: 1, retries: 3 }
                  - name: after it
                    send: fixtures/order.xml
                    expect:
                      dlq: 9
                      retries: 9
                      sent:
                        partner: { count: 9 }
                """));

        Ran ran = run("test", pkg.toString());

        assertEquals(List.of(
                "PASS async-order: partner down (flow time 700ms)",
                "FAIL async-order: after it (flow time 250ms)",
                "  expect.dlq: expected 9, was 0",
                "  expect.retries: expected 9, was 0",
                "  expect.sent.partner.count: expected 9, was 1",
                "2 cases: 1 passed, 1 failed"), ran.outLines(), ran.toString());
        assertEquals(1, ran.exit(), ran.toString());
    }

    /**
     * A case's {@code mocks} entry replaces the document's entry of that endpoint whole (partner: no 250 ms delay left, 50 + 100 +
     * 200 + 400 = 750 ms; merged it would be 1750 ms), keeps the document's entries of the other endpoints (archive's 50 ms;
     * without it 700 ms), and changes nothing for the next case (300 ms = 50 + 250, status 200).
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void caseMocksReplaceTheDocumentEntry() throws IOException {
        Path pkg = flowtestWith(tmp, Map.of("sync-order.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: sync-order
                mocks:
                  archive: { delay: 50ms }
                  partner: { reply: fixtures/ack.xml, delay: 250ms }
                cases:
                  - name: partner entry replaced
                    send: fixtures/order.xml
                    mocks:
                      partner: { fail: 503 }
                    expect: { status: 503 }
                  - name: document entries again
                    send: fixtures/order.xml
                    expect: { status: 200 }
                """));

        Ran ran = run("test", pkg.toString());

        assertEquals(List.of(
                "PASS sync-order: partner entry replaced (flow time 750ms)",
                "PASS sync-order: document entries again (flow time 300ms)",
                "2 cases: 2 passed, 0 failed"), ran.outLines(), ran.toString());
        assertEquals(0, ran.exit(), ran.toString());
    }

    /**
     * Virtual time jumps 9 minutes for the partner that answers then (PASS); the one that answers after 11 minutes is beyond the
     * 10 min horizon: FAIL at flow time 10m, "did not settle", no expectation evaluated, and the next line is the summary.
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void beyondTheHorizonFails() {
        Ran ran = run("test", FIXTURES.resolve("never-settles").toString());

        List<String> lines = ran.outLines();
        assertEquals(4, lines.size(), ran.toString());
        assertEquals("PASS hang: answers after 9 minutes (flow time 9m)", lines.get(0), ran.toString());
        assertEquals("FAIL hang: answers after 11 minutes (flow time 10m)", lines.get(1), ran.toString());
        assertTrue(lines.get(2).startsWith("  did not settle: "), ran.toString());
        assertEquals("2 cases: 1 passed, 1 failed", lines.get(3), ran.toString());
        assertEquals(1, ran.exit(), ran.toString());
    }

    /**
     * The flow time is printed in the largest of {@code h}, {@code m}, {@code s}, {@code ms} that divides it exactly: the slow
     * partner of the never-settles fixture (a sync flow, so the flow time is its delay) answering after 2 s, 60 s, 1500 ms, 0 ms.
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void flowTimeIsPrintedInTheLargestExactUnit() throws IOException {
        Path pkg = CliRuns.copy(FIXTURES.resolve("never-settles"), tmp.resolve("units"));
        Files.writeString(pkg.resolve("flows/hang.test.yaml"), """
                schemaVersion: 1
                kind: test
                flow: hang
                cases:
                  - name: whole seconds
                    send: fixtures/ping.xml
                    mocks: { slow: { delay: 2s } }
                    expect: { status: 200 }
                  - name: sixty seconds
                    send: fixtures/ping.xml
                    mocks: { slow: { delay: 60s } }
                    expect: { status: 200 }
                  - name: milliseconds
                    send: fixtures/ping.xml
                    mocks: { slow: { delay: 1500ms } }
                    expect: { status: 200 }
                  - name: no time
                    send: fixtures/ping.xml
                    mocks: { slow: { delay: 0ms } }
                    expect: { status: 200 }
                """);

        Ran ran = run("test", pkg.toString());

        assertEquals(List.of(
                "PASS hang: whole seconds (flow time 2s)",
                "PASS hang: sixty seconds (flow time 1m)",
                "PASS hang: milliseconds (flow time 1500ms)",
                "PASS hang: no time (flow time 0ms)",
                "4 cases: 4 passed, 0 failed"), ran.outLines(), ran.toString());
        assertEquals(0, ran.exit(), ran.toString());
    }

    /**
     * {@code sent.<endpoint>}: {@code xpath} with {@code equals} or {@code contains} on its value, without xpath on the UTF-8
     * body; it must hold for every send — {@code twice} sends the order, then the archive's reply {@code <Ack/>}, to the partner,
     * so a check that holds for one of the two sends only fails, whichever of them it is.
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void sentXpathEqualsAndContains() throws IOException {
        Map<String, String> docs = new LinkedHashMap<>();
        docs.put("sync-order.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: sync-order
                cases:
                  - name: xpath equals
                    send: fixtures/order.xml
                    expect: { sent: { partner: { xpath: /Order/Customer, equals: Acme Corp } } }
                  - name: xpath contains
                    send: fixtures/order.xml
                    expect: { sent: { partner: { xpath: /Order/Customer, contains: Acme } } }
                  - name: body equals
                    send: fixtures/order.xml
                    expect: { sent: { partner: { equals: "<Order><Id>42</Id><Customer>Acme Corp</Customer></Order>" } } }
                  - name: body contains
                    send: fixtures/order.xml
                    expect: { sent: { partner: { contains: "<Customer>Acme" } } }
                  - name: xpath equals is not contains
                    send: fixtures/order.xml
                    expect: { sent: { partner: { xpath: /Order/Customer, equals: Acme } } }
                  - name: xpath contains fails
                    send: fixtures/order.xml
                    expect: { sent: { partner: { xpath: /Order/Customer, contains: Zeta } } }
                  - name: body equals fails
                    send: fixtures/order.xml
                    expect: { sent: { partner: { equals: "<Order/>" } } }
                  - name: body contains fails
                    send: fixtures/order.xml
                    expect: { sent: { partner: { contains: "<Id>41</Id>" } } }
                """);
        docs.put("twice.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: twice
                mocks:
                  archive: { reply: fixtures/ack.xml }
                cases:
                  - name: both sends hold
                    send: fixtures/order.xml
                    expect: { sent: { partner: { count: 2, xpath: "count(/*)", equals: "1" } } }
                  - name: the second send differs
                    send: fixtures/order.xml
                    expect: { sent: { partner: { xpath: "name(/*)", equals: Order } } }
                  - name: the first send differs
                    send: fixtures/order.xml
                    expect: { sent: { partner: { xpath: "name(/*)", equals: Ack } } }
                """);
        Path pkg = flowtestWith(tmp, docs);

        Ran ran = run("test", pkg.toString());

        List<String> lines = ran.outLines();
        assertEquals(List.of(
                "PASS sync-order: xpath equals (flow time 0ms)",
                "PASS sync-order: xpath contains (flow time 0ms)",
                "PASS sync-order: body equals (flow time 0ms)",
                "PASS sync-order: body contains (flow time 0ms)",
                "FAIL sync-order: xpath equals is not contains (flow time 0ms)",
                "  expect.sent.partner.equals: expected Acme, was Acme Corp",
                "FAIL sync-order: xpath contains fails (flow time 0ms)",
                "  expect.sent.partner.contains: expected Zeta, was Acme Corp",
                "FAIL sync-order: body equals fails (flow time 0ms)",
                "  expect.sent.partner.equals: expected <Order/>, was <Order><Id>42</Id><Customer>Acme Corp</Customer></Order>",
                "FAIL sync-order: body contains fails (flow time 0ms)",
                "  expect.sent.partner.contains: expected <Id>41</Id>, was <Order><Id>42</Id><Customer>Acme Corp</Customer></Order>",
                "PASS twice: both sends hold (flow time 0ms)",
                "FAIL twice: the second send differs (flow time 0ms)",
                "  expect.sent.partner.equals: expected Order, was Ack",
                "FAIL twice: the first send differs (flow time 0ms)",
                "  expect.sent.partner.equals: expected Ack, was Order",
                "11 cases: 5 passed, 6 failed"), lines, ran.toString());
        assertEquals(1, ran.exit(), ran.toString());
    }

    // --- problems: printed, no case runs, exit 1

    /**
     * A package problem stops the run before any case: broken-xslt's flow does not build; env-overlay with its overlay moved to
     * {@code ci} is valid in dev (validate exits 0) and refused by the dry build under {@code ci}, the environment test runs in.
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void packageProblemRunsNoCaseExit1() throws IOException {
        Ran broken = run("test", FIXTURES.resolve("broken-xslt").toString());

        assertEquals(1, broken.exit(), broken.toString());
        assertTrue(broken.problems().stream().anyMatch(l -> l.startsWith("problem: flow 'invoice-sync' does not build: ")), broken.toString());
        assertTrue(broken.outLines().stream().noneMatch(TestCommandTest::caseLine), "no case ran: " + broken);

        Path ciOnly = CliRuns.copy(FIXTURES.resolve("env-overlay"), tmp.resolve("ci-only"));
        CliRuns.rewrite(ciOnly.resolve("endpoints/partner-foreign.yaml"), Map.of("  prod:", "  ci:"));
        assertEquals(0, run("validate", ciOnly.toString()).exit(), "valid in dev: the problem below is ci's");

        Ran ci = run("test", ciOnly.toString());

        assertEquals(1, ci.exit(), ci.toString());
        assertFalse(ci.problems().isEmpty(), ci.toString());
        assertTrue(ci.problems().stream().allMatch(l -> l.startsWith("problem: endpoint 'partner-foreign' config (environment ci) at '/poolSize': ")), ci.toString());
        assertTrue(ci.outLines().stream().noneMatch(TestCommandTest::caseLine), "no case ran: " + ci);
    }

    /**
     * {@code recorded} and {@code script} behaviours are not implemented yet: a problem naming the behaviour, no case runs, exit 1 —
     * in the document's {@code mocks} and in a case's alike.
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void recordedOrScriptIsAProblem() throws IOException {
        record Variant(String name, String behaviour, String from, String to) {}
        List<Variant> variants = List.of(
                new Variant("document-recorded", "recorded", "partner-foreign: { reply: fixtures/partner-ack.xml }", "partner-foreign: { recorded: m-1 }"),
                new Variant("document-script", "script", "partner-foreign: { reply: fixtures/partner-ack.xml }", "partner-foreign: { script: fixtures/partner.groovy }"),
                new Variant("case-recorded", "recorded", "partner-domestic: { fail: 503 }", "partner-domestic: { recorded: m-1 }"));
        List<String> seen = new ArrayList<>();
        for (Variant v : variants) {
            Path pkg = CliRuns.copy(DEMO, tmp.resolve(v.name()));
            CliRuns.rewrite(pkg.resolve("flows/invoice-sync.test.yaml"), Map.of(v.from(), v.to()));

            Ran ran = run("test", pkg.toString());

            assertEquals(1, ran.exit(), v + ": " + ran);
            assertTrue(ran.problems().stream().anyMatch(l -> l.contains(v.behaviour()) && l.contains("not implemented yet")), v + ": " + ran);
            assertTrue(ran.outLines().stream().noneMatch(TestCommandTest::caseLine), "no case ran: " + v + ": " + ran);
            seen.add(v.name());
        }
        assertEquals(3, seen.size());
    }

    // --- the send

    /**
     * A {@code send} above the flow's {@code maxBodyBytes} (the order is 56 bytes, the limit 16) is refused by the mock's ingest as
     * the door refuses it: the case fails at once — the case line, at least one indented line saying why, the summary — and the
     * expectations, which would hold for a message that never ran ({@code dlq: 0}), do not make it pass.
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void sendAboveMaxBodyBytesFailsTheCase() throws IOException {
        Path pkg = flowtestWith(tmp, Map.of("sync-order.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: sync-order
                cases:
                  - name: too large
                    send: fixtures/order.xml
                    expect: { dlq: 0 }
                """));
        CliRuns.rewrite(pkg.resolve("flows/sync-order.yaml"), Map.of("maxBodyBytes: 1048576", "maxBodyBytes: 16"));
        assertEquals(56, Files.size(pkg.resolve("fixtures/order.xml")), "fixture: the order is above the limit");

        Ran ran = run("test", pkg.toString());

        List<String> lines = ran.outLines();
        assertTrue(lines.size() >= 3, ran.toString());
        assertEquals("FAIL sync-order: too large (flow time 0ms)", lines.getFirst(), ran.toString());
        assertTrue(lines.subList(1, lines.size() - 1).stream().allMatch(l -> l.startsWith("  ")), "why, indented: " + ran);
        assertEquals("1 cases: 0 passed, 1 failed", lines.getLast(), ran.toString());
        assertEquals(1, ran.exit(), ran.toString());
    }

    /**
     * One mock per shipped scheme: a flow whose from endpoint is an sftp consumer runs on the sftp mock — the send ingested on it,
     * the partner (http) mocked beside it — and passes.
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aFlowFromSftpRunsOnTheSftpMock() throws IOException {
        Path pkg = withSftpFlow(flowtestWith(tmp, Map.of("from-sftp.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: from-sftp
                cases:
                  - name: dropped order
                    send: fixtures/order.xml
                    expect:
                      steps: 2
                      sent:
                        partner: { count: 1, contains: "<Id>42</Id>" }
                """)));

        Ran ran = run("test", pkg.toString());

        assertEquals(List.of(
                "PASS from-sftp: dropped order (flow time 0ms)",
                "1 cases: 1 passed, 0 failed"), ran.outLines(), ran.toString());
        assertEquals(0, ran.exit(), ran.toString());
    }

    /** {@code pkg} with an sftp in-endpoint {@code drop} and a sync flow {@code from-sftp} from it to the partner. */
    static Path withSftpFlow(Path pkg) throws IOException {
        Files.writeString(pkg.resolve("endpoints/drop.yaml"), """
                schemaVersion: 1
                kind: endpoint
                id: drop
                scheme: sftp
                direction: in
                config:
                  user: flottila
                  password: secret
                  hostKey: "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
                """);
        Files.writeString(pkg.resolve("flows/from-sftp.yaml"), """
                schemaVersion: 1
                kind: flow
                id: from-sftp
                version: 0.1.0
                from:
                  endpoint: drop
                delivery:
                  mode: sync
                limits:
                  maxBodyBytes: 1048576
                  maxInFlight: 4
                  maxInFlightBytes: 4194304
                steps:
                  - type: to
                    name: partner
                    endpoint: partner
                """);
        return pkg;
    }

    /**
     * {@code expect.status} is the http door's answer: on a flow whose from endpoint is not http (an sftp consumer here) it is a
     * problem — the package is valid (validate exits 0) — and no case runs, the sync-order case beside it neither.
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void statusOnANonHttpFromIsAProblem() throws IOException {
        Path pkg = withSftpFlow(flowtestWith(tmp, Map.of(
                "from-sftp.test.yaml", """
                        schemaVersion: 1
                        kind: test
                        flow: from-sftp
                        cases:
                          - name: dropped order
                            send: fixtures/order.xml
                            expect: { status: 200 }
                        """,
                "sync-order.test.yaml", """
                        schemaVersion: 1
                        kind: test
                        flow: sync-order
                        cases:
                          - name: happy path
                            send: fixtures/order.xml
                            expect: { steps: 4 }
                        """)));
        Ran valid = run("validate", pkg.toString());
        assertEquals(0, valid.exit(), "the package itself is valid: " + valid);

        Ran ran = run("test", pkg.toString());

        assertEquals(1, ran.exit(), ran.toString());
        assertTrue(ran.problems().stream().anyMatch(l -> l.contains("status")), ran.toString());
        assertTrue(ran.outLines().stream().noneMatch(TestCommandTest::caseLine), "no case ran: " + ran);
    }

    /** A valid package without a test document has nothing to pass: exit 1, said on stdout. */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void noTestDocumentExit1() {
        assertEquals(0, run("validate", FIXTURES.resolve("tree").toString()).exit(), "tree is a valid package");

        Ran ran = run("test", FIXTURES.resolve("tree").toString());

        assertEquals(1, ran.exit(), ran.toString());
        assertTrue(ran.outLines().stream().anyMatch(l -> l.contains("no test document")), ran.toString());
        assertTrue(ran.outLines().stream().noneMatch(TestCommandTest::caseLine), ran.toString());
    }

    /** Usage: no directory, a directory that does not exist, or a second one: usage on stderr, nothing on stdout, exit 2. */
    @Test
    void missingDirExit2() {
        for (String[] args : List.of(new String[] {"test"}, new String[] {"test", tmp.resolve("absent").toString()},
                new String[] {"test", DEMO.toString(), FLOWTEST.toString()})) {
            Ran ran = run(args);

            assertEquals(2, ran.exit(), ran.toString());
            assertEquals("", ran.out(), ran.toString());
            assertFalse(ran.err().isBlank(), "usage on stderr: " + ran);
        }
    }

    /** {@code test} is a command now: the usage {@code help} prints names it with its argument. */
    @Test
    void helpNamesTheTestCommand() {
        Ran ran = run("help");

        assertEquals(0, ran.exit(), ran.toString());
        assertTrue(ran.outLines().stream().anyMatch(l -> l.strip().startsWith("test <dir>")), "the usage names `test <dir>`: " + ran);
    }

    // --- determinism

    /** Virtual time, fresh runtimes and no wall time in the report: 100 runs of the same package print the same bytes. */
    @Test
    @Timeout(value = 900, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void hundredRunsGiveTheSameReport() {
        List<String> differing = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            Ran ran = run("test", FLOWTEST.toString());
            if (ran.exit() != 0 || !ran.outLines().equals(FLOWTEST_REPORT)) differing.add("run " + i + ": " + ran);
        }
        assertEquals(List.of(), differing, "every run prints " + FLOWTEST_REPORT);
    }

    // --- P2-02b2 (D-087, D-089): dependencies resolved off-node from a candidates dir (DepsFixture), deployed into each case's node

    /** invoice's report when its one case passes: the partner saw what the expected stylesheet wrote. */
    static final List<String> INVOICE_PASSES = List.of("PASS to-partner: maps with common (flow time 20ms)", "1 cases: 1 passed, 0 failed");

    /**
     * Fixture witness: the invoice package with common's stylesheet copied in (no dependency) passes its case today — the report
     * the dependency cases expect is the package's own, not a guess.
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void theInvoiceFixturePassesWithCommonsStylesheetCopiedIn() throws Exception {
        Path invoice = DepsFixture.copy("invoice", tmp.resolve("inline/invoice"));
        CliRuns.copy(DepsFixture.DEPS.resolve("common/xslt"), invoice.resolve("xslt"));
        DepsFixture.manifest(invoice, "invoice", "1.0.0");
        CliRuns.rewrite(invoice.resolve("flows/to-partner.yaml"), Map.of("stylesheet: common:xslt/to-partner.xsl", "stylesheet: xslt/to-partner.xsl"));

        Ran ran = run("test", invoice.toString());

        assertEquals(INVOICE_PASSES, ran.outLines(), ran.toString());
        assertEquals(0, ran.exit(), ran.toString());
    }

    /** invoice pins common 1.2.0 (the parent holds it and common 1.3.0): its case passes, common 1.2.0's XSLT and include ran. */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aPackagePinningCommon120TestsOffNode() throws Exception {
        Path invoice = DepsFixture.invoiceWithCandidates(tmp.resolve("deps"));

        Ran ran = run("test", invoice.toString());

        assertEquals(INVOICE_PASSES, ran.outLines(), ran.toString());
        assertEquals(0, ran.exit(), ran.toString());
    }

    /**
     * The witness that the case runs the pinned version: invoice pinning common 1.3.0 from {@code --deps} (the parent holds no
     * common) fails the same case, the partner having seen 1.3.0's output.
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aPackagePinningCommon130MapsWithItFromTheDepsOption() throws Exception {
        Path vendor = tmp.resolve("vendor");
        DepsFixture.copy("common", vendor.resolve("common"));
        Path common130 = DepsFixture.copy("common-1.3.0", vendor.resolve("common-1.3.0"));
        Path invoice = DepsFixture.copy("invoice", tmp.resolve("work/invoice"));
        DepsFixture.manifest(invoice, "invoice", "1.0.0", "common@1.3.0");
        DepsFixture.lock(invoice, common130);

        Ran ran = run("test", invoice.toString(), "--deps", vendor.toString());

        assertEquals(List.of(
                "FAIL to-partner: maps with common (flow time 20ms)",
                "  expect.sent.partner.equals: expected common 1.2.0, was common 1.3.0",
                "1 cases: 0 passed, 1 failed"), ran.outLines(), ran.toString());
        assertEquals(1, ran.exit(), ran.toString());
    }

    /** common changed after invoice was locked: the drift is a problem line, no case runs, exit 1. */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aLockDriftFailsBeforeAnyCase() throws Exception {
        Path deps = tmp.resolve("deps");
        Path invoice = DepsFixture.invoiceWithCandidates(deps);
        String pinned = DepsFixture.content(deps.resolve("common"));
        DepsFixture.relabel(deps.resolve("common"), "changed after the lock");
        String now = DepsFixture.content(deps.resolve("common"));

        Ran ran = run("test", invoice.toString());

        assertEquals(1, ran.exit(), ran.toString());
        assertTrue(ran.problems().contains("problem: package.lock pins common@1.2.0 " + pinned + "; candidate common has " + now), ran.toString());
        assertTrue(ran.outLines().stream().noneMatch(TestCommandTest::caseLine), "no case ran: " + ran);
        DepsFixture.assertNoAbsolutePath(ran, tmp);
    }

    /** The candidates hold common 1.3.0 only: a problem line naming what they hold, no case runs, exit 1. */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aMissingCandidateFailsBeforeAnyCase() throws Exception {
        Path pinnedElsewhere = DepsFixture.copy("common", tmp.resolve("elsewhere/common"));
        Path invoice = DepsFixture.copy("invoice", tmp.resolve("deps/invoice"));
        DepsFixture.copy("common-1.3.0", tmp.resolve("deps/common-1.3.0"));
        DepsFixture.lock(invoice, pinnedElsewhere);

        Ran ran = run("test", invoice.toString());

        assertEquals(1, ran.exit(), ran.toString());
        assertTrue(ran.problems().stream().anyMatch(l -> l.startsWith("problem: dependency common@1.2.0: no candidate under ")
                && l.endsWith(" (it has common 1.3.0)")), ran.toString());
        assertTrue(ran.outLines().stream().noneMatch(TestCommandTest::caseLine), "no case ran: " + ran);
    }

    /** order -> ubl -> common: each case's node gets common, then ubl, then order; the case sees ubl's stylesheet output. */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aTransitiveDependencyIsDeployedLeavesFirstInEachCase() throws Exception {
        Path order = DepsFixture.orderOverUblOverCommon(tmp.resolve("deps"));

        Ran ran = run("test", order.toString());

        assertEquals(INVOICE_PASSES, ran.outLines(), ran.toString());
        assertEquals(0, ran.exit(), ran.toString());
    }
}
