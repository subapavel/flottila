package dev.rail8.cli;

import dev.rail8.cli.CliRuns.Ran;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static dev.rail8.cli.CliRuns.FIXTURES;
import static dev.rail8.cli.CliRuns.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code rail8 test} across calls between flows, on the {@code calls} fixture: order (sync, http) calls check (sync, direct
 * {@code /check}), which answers with the partner's reply; order then hands that answer to archive (at-least-once, direct
 * {@code /archive}), whose worker sends it to the archive after the mock's 50 ms.
 *
 * <ul>
 *   <li>The calls run for real: what check and archive send is order's case's {@code sent}, and the case settles only once
 *       archive's work row has run (flow time 50ms).</li>
 *   <li>archive's failed row and its retries are order's case's {@code dlq} and {@code retries}: two runs of 100 + 200 + 400 ms,
 *       1 s apart (2400ms), 3 + 3 retries and 1 rerun.</li>
 *   <li>A case's mock stands in for a direct endpoint: check never runs, the mock's reply goes on to archive.</li>
 *   <li>check's own test sends its fixture through a direct producer.</li>
 * </ul>
 */
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TestCommandCallsTest {

    static final Path CALLS = FIXTURES.resolve("calls");

    @TempDir Path tmp;

    @Test
    void theFlowsAPackageCallsRunInsideTheCase() {
        Ran ran = run("test", CALLS.toString());

        assertEquals(List.of(
                "PASS check: called (flow time 0ms)",
                "PASS order: the chain (flow time 50ms)",
                "PASS order: archive down (flow time 2400ms)",
                "PASS order: check mocked (flow time 50ms)",
                "4 cases: 4 passed, 0 failed"), ran.outLines(), ran.toString());
        assertEquals(0, ran.exit(), ran.toString());
    }

    /** The counts of a chain are the callees' too: a case that expects other ones fails, naming what each flow did. */
    @Test
    void aCalleesSendsAndRowsFailACaseThatExpectsOthers() throws IOException {
        Path pkg = CliRuns.copy(CALLS, tmp.resolve("calls"));
        Files.writeString(pkg.resolve("flows/order.test.yaml"), """
                schemaVersion: 1
                kind: test
                flow: order
                mocks:
                  partner: { reply: fixtures/ack.xml }
                  archive: { fail: 503 }
                cases:
                  - name: wrong counts
                    send: fixtures/order.xml
                    expect:
                      sent: { call-archive: { count: 2 }, archive: { count: 1 } }
                      dlq: 0
                      retries: 0
                """);

        Ran ran = run("test", pkg.toString());

        assertEquals(List.of(
                "PASS check: called (flow time 0ms)",
                "FAIL order: wrong counts (flow time 2400ms)",
                "  expect.sent.call-archive.count: expected 2, was 1",
                "  expect.sent.archive.count: expected 1, was 8",
                "  expect.dlq: expected 0, was 1",
                "  expect.retries: expected 0, was 7",
                "2 cases: 1 passed, 1 failed"), ran.outLines(), ran.toString());
        assertEquals(1, ran.exit(), ran.toString());
    }

    /** A body above the called flow's maxBodyBytes (the order is 56 bytes, the limit 16) is refused by the call: the send is refused. */
    @Test
    void aSendAboveTheCalleesLimitIsRefused() throws IOException {
        Path pkg = CliRuns.copy(CALLS, tmp.resolve("calls"));
        Files.delete(pkg.resolve("flows/order.test.yaml"));
        CliRuns.rewrite(pkg.resolve("flows/check.yaml"), Map.of("maxBodyBytes: 1048576", "maxBodyBytes: 16"));

        Ran ran = run("test", pkg.toString());

        assertEquals(List.of(
                "FAIL check: called (flow time 0ms)",
                "  send refused: not accepted (final): a body of 56 bytes is above the 16 the flow calls/check takes",
                "1 cases: 0 passed, 1 failed"), ran.outLines(), ran.toString());
        assertEquals(1, ran.exit(), ran.toString());
    }

    /** The deploy checks calls as a node does: a flow that calls its own address is refused, naming the path. */
    @Test
    void aCycleOfCallsIsAProblem() throws IOException {
        Path pkg = CliRuns.copy(CALLS, tmp.resolve("calls"));
        CliRuns.rewrite(pkg.resolve("endpoints/partner.yaml"), Map.of("scheme: http", "scheme: direct",
                "  url: http://localhost:8090/partner\n  maxBodyBytes: 1048576", "  address: /check"));

        Ran ran = run("test", pkg.toString());

        assertEquals(1, ran.exit(), ran.toString());
        assertTrue(ran.problems().stream().anyMatch(l -> l.contains("cycle") && l.contains("flow 'check' -> direct /check -> flow 'check'")), ran.toString());
    }
}
