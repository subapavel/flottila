package io.flottila.cli;

import io.flottila.cli.CliRuns.Ran;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.flottila.cli.CliRuns.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-03b1 (D-080): every {@code expect} key, a case where it holds (a PASS line) and one where it does not (a FAIL line and the
 * key's {@code   expect.<key>: expected X, was Y}), on the flowtest fixture's sync-order flow — through {@code flottila test}, so
 * the report is what is pinned. Keys: {@code status} (the http door's answer), {@code steps} (timeline size, ingress
 * included), {@code keys} (findByKey finds the message), {@code dlq} (DLQ rows), {@code retries} (per endpoint, the sends of the
 * same message id less one, summed), {@code error} (the answer's failure message contains the text), {@code sent.<ep>}
 * {@code count} (sends, retries included), {@code xpath} with {@code equals | contains} and, without xpath, {@code equals |
 * contains} on the UTF-8 body. For {@code keys} and {@code error} only {@code expected X, was } is pinned: what "was" shows
 * for a key that is not found, or for the failure text, is the runner's.
 *
 * <p>Up: archive 50 ms, partner replies after 250 ms (flow time 300 ms, status 200, 4 steps, one send each). Down: the partner
 * fails 503 on every send (100 + 200 + 400 ms backoff after the archive: 750 ms, status 503, 4 sends, retries 3, no DLQ row —
 * a sync flow fails the answer and parks nothing).
 */
class ExpectationsTest {

    static final String UP = "300ms";
    static final String DOWN = "750ms";

    @TempDir Path tmp;

    /**
     * @param key the key, as the case names say it
     * @param down whether the partner fails 503 (else it replies)
     * @param holds the case's {@code expect} that holds
     * @param fails the one that does not
     * @param failLine the line the failing key prints, whole
     * @param failPrefix or, when {@code failLine} is null, how it starts
     * @param failHas and a text the rest of it contains (may be empty)
     */
    record Key(String key, boolean down, String holds, String fails, String failLine, String failPrefix, String failHas) {
        static Key exact(String key, boolean down, String holds, String fails, String failLine) {
            return new Key(key, down, holds, fails, failLine, null, null);
        }

        static Key prefixed(String key, boolean down, String holds, String fails, String failPrefix, String failHas) {
            return new Key(key, down, holds, fails, null, failPrefix, failHas);
        }

        @Override public String toString() { return key; }
    }

    static Stream<Key> keys() {
        return Stream.of(
                Key.exact("status", false, "{ status: 200 }", "{ status: 201 }", "  expect.status: expected 201, was 200"),
                Key.exact("steps", false, "{ steps: 4 }", "{ steps: 3 }", "  expect.steps: expected 3, was 4"),
                Key.prefixed("keys", false, "{ keys: { orderId: \"42\" } }", "{ keys: { orderId: \"43\" } }",
                        "  expect.keys.orderId: expected 43, was ", ""),
                Key.exact("dlq", true, "{ dlq: 0 }", "{ dlq: 1 }", "  expect.dlq: expected 1, was 0"),
                Key.exact("retries", true, "{ retries: 3 }", "{ retries: 0 }", "  expect.retries: expected 0, was 3"),
                Key.prefixed("error", true, "{ error: \"503\" }", "{ error: \"504\" }", "  expect.error: expected 504, was ", "503"),
                Key.exact("sent count", false, "{ sent: { partner: { count: 1 } } }", "{ sent: { partner: { count: 2 } } }",
                        "  expect.sent.partner.count: expected 2, was 1"),
                Key.exact("sent count with retries", true, "{ sent: { partner: { count: 4 } } }", "{ sent: { partner: { count: 1 } } }",
                        "  expect.sent.partner.count: expected 1, was 4"),
                Key.exact("sent xpath equals", false, "{ sent: { partner: { xpath: /Order/Id, equals: \"42\" } } }",
                        "{ sent: { partner: { xpath: /Order/Id, equals: \"43\" } } }", "  expect.sent.partner.equals: expected 43, was 42"),
                Key.exact("sent xpath contains", false, "{ sent: { partner: { xpath: /Order/Customer, contains: Acme } } }",
                        "{ sent: { partner: { xpath: /Order/Customer, contains: Zeta } } }",
                        "  expect.sent.partner.contains: expected Zeta, was Acme Corp"),
                Key.exact("sent body equals", false,
                        "{ sent: { archive: { equals: \"<Order><Id>42</Id><Customer>Acme Corp</Customer></Order>\" } } }",
                        "{ sent: { archive: { equals: \"<Order/>\" } } }",
                        "  expect.sent.archive.equals: expected <Order/>, was <Order><Id>42</Id><Customer>Acme Corp</Customer></Order>"),
                Key.exact("sent body contains", false, "{ sent: { archive: { contains: \"<Id>42</Id>\" } } }",
                        "{ sent: { archive: { contains: \"<Id>43</Id>\" } } }",
                        "  expect.sent.archive.contains: expected <Id>43</Id>, was <Order><Id>42</Id><Customer>Acme Corp</Customer></Order>"));
    }

    /** The flowtest fixture with one sync-order test document: the key's holding case, then its failing one. */
    Path packageFor(Key k) throws IOException {
        String caseMocks = k.down() ? "    mocks: { partner: { fail: 503 } }\n" : "";
        String doc = """
                schemaVersion: 1
                kind: test
                flow: sync-order
                mocks:
                  archive: { delay: 50ms }
                  partner: { reply: fixtures/ack.xml, delay: 250ms }
                cases:
                """
                + "  - name: " + k.key() + " holds\n    send: fixtures/order.xml\n" + caseMocks + "    expect: " + k.holds() + "\n"
                + "  - name: " + k.key() + " fails\n    send: fixtures/order.xml\n" + caseMocks + "    expect: " + k.fails() + "\n";
        return TestCommandTest.flowtestWith(tmp, Map.of("sync-order.test.yaml", doc));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("keys")
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aPassAndAFailLinePerKey(Key k) throws IOException {
        String flowTime = k.down() ? DOWN : UP;

        Ran ran = run("test", packageFor(k).toString());

        List<String> lines = ran.outLines();
        assertEquals(4, lines.size(), ran.toString());
        assertEquals("PASS sync-order: " + k.key() + " holds (flow time " + flowTime + ")", lines.get(0), ran.toString());
        assertEquals("FAIL sync-order: " + k.key() + " fails (flow time " + flowTime + ")", lines.get(1), ran.toString());
        if (k.failLine() != null) {
            assertEquals(k.failLine(), lines.get(2), ran.toString());
        } else {
            assertTrue(lines.get(2).startsWith(k.failPrefix()), ran.toString());
            assertTrue(lines.get(2).substring(k.failPrefix().length()).contains(k.failHas()), ran.toString());
        }
        assertEquals("2 cases: 1 passed, 1 failed", lines.get(3), ran.toString());
        assertEquals(1, ran.exit(), ran.toString());
    }
}
