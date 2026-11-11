package io.flottila.runtime.node;

import io.flottila.runtime.node.NodeConfig.Pg.Durability;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-136 P5 (D-127 draft): under {@code local} the standby notice names the standbys that stream, the ones a promotion could
 * pick with the accepted messages they received; a standby still catching up, or one whose state the role cannot read, is not
 * named as streaming.
 */
class PgDurabilityStreamingNoticeTest {

    @Test
    void localNamesTheStreamingStandbysOnly() {
        PgDurability.Server server = new PgDurability.Server("intake", "on", false, "", true, List.of(
                new PgDurability.Standby("standby-b", "streaming", "async"), new PgDurability.Standby("standby-c", "catchup", "async"),
                new PgDurability.Standby("standby-d", null, null)), true);
        PgDurability.Verdict verdict = PgDurability.verdict(Durability.LOCAL, server);
        assertEquals(Optional.empty(), verdict.refusal());
        List<String> named = verdict.notices().stream().filter(n -> n.contains("standby-b")).toList();
        assertEquals(1, named.size(), "one notice names the streaming standby: " + verdict.notices());
        assertFalse(named.getFirst().contains("standby-c") || named.getFirst().contains("standby-d"),
                "the catching-up and the unreadable standby are not named as streaming: " + named);
    }

    @Test
    void localWithoutAStreamingStandbyStatesOnlyItsPromise() {
        PgDurability.Server server = new PgDurability.Server("intake", "on", false, "", false, List.of(
                new PgDurability.Standby("standby-c", "catchup", "async"), new PgDurability.Standby("standby-d", null, null)), true);
        List<String> notices = PgDurability.verdict(Durability.LOCAL, server).notices();
        assertEquals(1, notices.size(), "the promise alone: " + notices);
        assertTrue(notices.getFirst().contains("survives a Postgres crash"), notices.getFirst());
    }
}
