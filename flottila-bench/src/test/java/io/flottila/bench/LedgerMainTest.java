package io.flottila.bench;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The kill -9 ledger's command line and verdict (P1-10b, GATE 9): {@code --messages} (2000), {@code --kill-after}
 * ({@code messages / 2}, at least 1 and below {@code --messages}: node 1 acks some, node 2 takes the rest), the node bench's
 * database options ({@code --db} must name a bench database: the run truncates it), {@code --out}; the exit code is 0 only when
 * every message was ACKed ({@code acked == messages}: the driver re-sends a message whose 202 did not arrive until it is ACKed),
 * no ACKed id is missing a final-step row, the DLQ and the work queue are empty, and the run was not vacuous: node 1 exited 137
 * (SIGKILL), at least one ACKed message was unfinished at the kill and node 2 ACKed at least one ({@code acked > killedAfter}).
 * Duplicates, re-sends ({@code resent}), saved-but-unACKed ids ({@code savedUnacked}) and {@code heldToKillMs} are reported,
 * never a failure.
 */
class LedgerMainTest {

    @Test
    void optionsAreParsedWithTheNativeDefaults() {
        LedgerMain.Options defaults = LedgerMain.options(new String[] {});
        assertEquals(2000, defaults.messages(), "the native ledger sends 2000 (GATE 9: 2000/2000)");
        assertEquals(1000, defaults.killAfter(), "node 1 is killed after half of the messages were ACKed");

        LedgerMain.Options o = LedgerMain.options(new String[] {"--messages", "200", "--kill-after", "60", "--host", "db.local",
                "--port", "6543", "--db", "ledger_bench", "--user", "u", "--password", "p", "--out", "x/ledger.json", "--commit", "abc"});
        assertEquals(200, o.messages());
        assertEquals(60, o.killAfter(), "--kill-after as given");
        assertEquals("db.local", o.host());
        assertEquals(6543, o.port());
        assertEquals("ledger_bench", o.db());
        assertEquals("u", o.user());
        assertEquals("p", o.password());
        assertEquals(Path.of("x/ledger.json"), o.out());
        assertEquals("abc", o.commit());

        // the kill-after default follows --messages: half of them, rounded down
        assertEquals(150, LedgerMain.options(new String[] {"--messages", "300"}).killAfter());
        assertEquals(3, LedgerMain.options(new String[] {"--messages", "7"}).killAfter());
        // the bounds themselves are taken
        assertEquals(1, LedgerMain.options(new String[] {"--messages", "200", "--kill-after", "1"}).killAfter());
        assertEquals(199, LedgerMain.options(new String[] {"--messages", "200", "--kill-after", "199"}).killAfter());
    }

    @Test
    void aKillAfterOutsideOneToBelowMessagesIsRefused() {
        for (String killAfter : new String[] {"0", "-1", "200", "201"}) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> LedgerMain.options(new String[] {"--messages", "200", "--kill-after", killAfter}), "--kill-after " + killAfter);
            assertTrue(refused.getMessage().contains("--kill-after"), "names the option: " + refused.getMessage());
            assertTrue(refused.getMessage().contains(killAfter), "names the refused value: " + refused.getMessage());
        }
        // one message cannot be split between two nodes: --messages 1 leaves no kill-after (its default is 0)
        IllegalArgumentException one = assertThrows(IllegalArgumentException.class, () -> LedgerMain.options(new String[] {"--messages", "1"}));
        assertTrue(one.getMessage().contains("--messages") || one.getMessage().contains("--kill-after"), one.getMessage());
    }

    @Test
    void aDatabaseThatIsNotABenchDatabaseIsRefusedBeforeAnythingConnects() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> LedgerMain.options(new String[] {"--db", "flottila"}));
        assertTrue(refused.getMessage().contains("flottila") && refused.getMessage().contains("bench"),
                "names the database and the rule: " + refused.getMessage());
        // the default database is a bench one
        assertTrue(LedgerMain.options(new String[] {}).db().contains("bench"), "the default --db names a bench database");

        // the run refuses before it touches a database or starts a child JVM (nothing listens on port 1)
        IllegalArgumentException run = assertThrows(IllegalArgumentException.class, () -> LedgerMain.run(new String[] {
                "--host", "127.0.0.1", "--port", "1", "--db", "production", "--messages", "4"}));
        assertTrue(run.getMessage().contains("production"), run.getMessage());
        IllegalArgumentException killAfter = assertThrows(IllegalArgumentException.class, () -> LedgerMain.run(new String[] {
                "--host", "127.0.0.1", "--port", "1", "--db", "x_bench", "--messages", "4", "--kill-after", "4"}));
        assertTrue(killAfter.getMessage().contains("--kill-after"), killAfter.getMessage());
    }

    @Test
    void theExitCodeIsZeroOnlyWithNothingMissingAnEmptyDlqAndAnEmptyQueueAndDuplicatesNeverFail() {
        assertEquals(0, LedgerMain.exitCode(clean()), "a clean ledger");
        // at the boundaries: one ACKed message unfinished at the kill, node 2 ACKed exactly one more than node 1
        assertEquals(0, LedgerMain.exitCode(clean().put("unfinishedAtKill", 1).put("killedAfter", MESSAGES - 1)),
                "one unfinished at the kill and one ACK by node 2 are enough");

        // at-least-once reports, never failures: partner duplicates, re-sends, saved messages whose 202 the kill cut, the hold-to-kill time
        assertEquals(0, LedgerMain.exitCode(clean().put("duplicates", 7).put("partnerCalls", MESSAGES + 7)), "duplicates are reported, not failures");
        assertEquals(0, LedgerMain.exitCode(clean().put("resent", 5)), "re-sends are reported, not failures");
        assertEquals(0, LedgerMain.exitCode(clean().put("resent", 5).put("savedUnacked", 3)), "saved-but-unACKed ids are reported, not failures");
        assertEquals(0, LedgerMain.exitCode(clean().put("heldToKillMs", -1)), "no partner call held before the kill is reported, not a failure");
        assertEquals(0, LedgerMain.exitCode(clean().put("heldToKillMs", 120_000)), "a long hold before the kill is reported, not a failure");

        assertNotEquals(0, LedgerMain.exitCode(clean().put("missing", new JsonArray().add("m-lost")).put("finalRows", MESSAGES - 1)),
                "an ACKed id without a final-step row fails the ledger");
        assertNotEquals(0, LedgerMain.exitCode(clean().put("dlq", 1)), "a parked message fails the ledger");
        assertNotEquals(0, LedgerMain.exitCode(clean().put("work", 1)), "a work item left fails the ledger");
    }

    @Test
    void theExitCodeIsNonZeroWhenNotEveryMessageWasAcked() {
        // GATE 9 reads N/N over all N messages: a message never ACKed fails the ledger even though every ACKed id has its row
        JsonObject short1 = clean().put("acked", MESSAGES - 1).put("finalRows", MESSAGES - 1).put("ackedIds", ids(MESSAGES - 1));
        assertNotEquals(0, LedgerMain.exitCode(short1), "one message of " + MESSAGES + " never ACKed: " + short1.encode());
        JsonObject half = clean().put("acked", MESSAGES / 2 + 1).put("finalRows", MESSAGES / 2 + 1).put("ackedIds", ids(MESSAGES / 2 + 1))
                .put("savedUnacked", MESSAGES / 2 - 1);
        assertNotEquals(0, LedgerMain.exitCode(half), "saved-but-unACKed ids do not stand in for ACKs: " + half.encode());
    }

    @Test
    void theExitCodeIsNonZeroWhenNodeOneWasNotKilledBySignalNine() {
        for (int exit : new int[] {0, 1, 143}) {
            JsonObject r = clean().put("node1ExitValue", exit);
            assertNotEquals(0, LedgerMain.exitCode(r), "node 1 exited with " + exit + ", not 137 (SIGKILL): the run proves nothing about kill -9");
        }
    }

    @Test
    void theExitCodeIsNonZeroWhenNothingWasUnfinishedAtTheKill() {
        JsonObject r = clean().put("unfinishedAtKill", 0);
        assertNotEquals(0, LedgerMain.exitCode(r), "every ACKed message was finished when node 1 died: the kill tested nothing: " + r.encode());
    }

    @Test
    void theExitCodeIsNonZeroWhenNodeTwoAckedNothing() {
        JsonObject all = clean().put("killedAfter", MESSAGES);
        assertNotEquals(0, LedgerMain.exitCode(all), "node 1 ACKed every message, node 2 none: " + all.encode());
    }

    static final int MESSAGES = 10;

    /**
     * A record of a run that holds, its counts agreeing with each other as a run's would: every message ACKed once (node 1 five
     * before its SIGKILL, node 2 the rest), each ACKed id with its final-step row, three unfinished at the kill, no re-send.
     */
    static JsonObject clean() {
        return new JsonObject().put("messages", MESSAGES).put("sent", MESSAGES).put("acked", MESSAGES).put("ackedIds", ids(MESSAGES))
                .put("finalRows", MESSAGES).put("missing", new JsonArray()).put("dlq", 0).put("work", 0).put("partnerCalls", MESSAGES)
                .put("duplicates", 0).put("resent", 0).put("savedUnacked", 0).put("heldToKillMs", 40).put("killedAfter", 5)
                .put("node1ExitValue", 137).put("workAtKill", 3).put("unfinishedAtKill", 3);
    }

    static JsonArray ids(int n) {
        JsonArray ids = new JsonArray();
        for (int i = 1; i <= n; i++) ids.add("m-" + i);
        return ids;
    }
}
