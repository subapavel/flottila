package io.flottila.runtime.node;

import io.flottila.runtime.node.NodeConfig.Pg.Durability;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-136 P3–P5 (D-127 draft, D-114, D-115): the pure verdict over one read of the server. Both profiles refuse a session whose
 * {@code synchronous_commit} is not {@code on} (a pooler dropped the node's startup parameter) and a server in recovery (the
 * host names a standby). {@code ha} also refuses an empty {@code synchronous_standby_names}, a role that cannot read every
 * statistic (it cannot see whether a standby streams: {@code GRANT pg_monitor TO <user>}) and a server with no sync or quorum
 * standby streaming. Notices: {@code local} always states its promise, and with streaming standbys names them and {@code ha};
 * {@code ha} with a standby that is not synchronous names D-115.
 *
 * <p>FIX-137 B5 (owner decision, D-128): {@code ha} also refuses a role that may not run {@code pg_logical_emit_message} (the
 * duplicate's commit barrier), naming {@code GRANT EXECUTE ON FUNCTION pg_catalog.pg_logical_emit_message(...) TO <user>} and
 * D-128, listed after the statistics refusal; {@code local} runs no barrier and ignores it. The argument list is not pinned
 * here: postgres:17 has {@code (boolean, text, text, boolean)}, not the three-argument form (observed, 17.11);
 * {@code PgPromotionLedgerIT.haRefusesARoleThatMayNotRunTheDuplicateBarrier} runs the named GRANT verbatim.
 *
 * <p>Shape assumed (FIX-136 test-author): {@code PgDurability.verdict(Durability, Server)} → {@code Verdict(Optional<String>
 * refusal, List<String> notices)}; {@code Server(user, synchronousCommit, inRecovery, synchronousStandbyNames, readsAllStats,
 * standbys, emitsLogicalMessages)}; {@code Standby(name, state, syncState)} as {@code pg_stat_replication} shows them (state and sync_state null when
 * the role cannot read all statistics).
 */
class PgDurabilityTest {

    static final PgDurability.Standby SYNC = new PgDurability.Standby("standby-a", "streaming", "sync");
    static final PgDurability.Standby QUORUM = new PgDurability.Standby("standby-q", "streaming", "quorum");
    static final PgDurability.Standby ASYNC = new PgDurability.Standby("standby-b", "streaming", "async");

    /**
     * A primary whose sessions commit on, which {@code ha} accepts: '*', the role reads all statistics, one sync standby streams,
     * the role may run {@code pg_logical_emit_message}.
     */
    static PgDurability.Server good() {
        return new PgDurability.Server("intake", "on", false, "*", true, List.of(SYNC), true);
    }

    static PgDurability.Server server(String commit, boolean recovery, String names, boolean stats, List<PgDurability.Standby> standbys) {
        return new PgDurability.Server("intake", commit, recovery, names, stats, standbys, true);
    }

    /** {@code GRANT EXECUTE ON FUNCTION pg_catalog.pg_logical_emit_message(<boolean, text, text[, boolean]>) TO <user>}, whole words. */
    static Pattern emitGrant(String user) {
        return Pattern.compile("GRANT EXECUTE ON FUNCTION pg_catalog\\.pg_logical_emit_message\\(boolean, text, text(, boolean)?\\) TO "
                + Pattern.quote(user) + "(?![A-Za-z0-9_-])");
    }

    static String refused(Durability profile, PgDurability.Server server) {
        Optional<String> refusal = PgDurability.verdict(profile, server).refusal();
        assertTrue(refusal.isPresent(), profile + " refuses " + server);
        return refusal.get();
    }

    static List<String> accepted(Durability profile, PgDurability.Server server) {
        PgDurability.Verdict verdict = PgDurability.verdict(profile, server);
        assertEquals(Optional.empty(), verdict.refusal(), profile + " accepts " + server);
        return verdict.notices();
    }

    static boolean word(String text, String word) {
        return Pattern.compile("(?<![A-Za-z_-])" + Pattern.quote(word) + "(?![A-Za-z_-])").matcher(text).find();
    }

    // ------------------------------------------------------------------------------------------------------ refused under both

    @ParameterizedTest(name = "{0}, synchronous_commit={1}")
    @CsvSource({"LOCAL, off", "LOCAL, local", "LOCAL, remote_write", "LOCAL, remote_apply",
            "HA, off", "HA, local", "HA, remote_write", "HA, remote_apply"})
    void aSessionThatDoesNotCommitOnIsRefusedUnderBothProfiles(Durability profile, String value) {
        String refusal = refused(profile, server(value, false, "*", true, List.of(SYNC)));
        assertTrue(refusal.contains("synchronous_commit") && word(refusal, value), "names the setting and the session's value: " + refusal);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Durability.class)
    void aServerInRecoveryIsRefusedUnderBothProfiles(Durability profile) {
        String refusal = refused(profile, server("on", true, "*", true, List.of(SYNC)));
        assertTrue(refusal.contains("recovery"), "names why: the host is a standby in recovery: " + refusal);
    }

    // ----------------------------------------------------------------------------------------------------------- refused by ha

    @Test
    void haRefusesAnEmptySynchronousStandbyNames() {
        String refusal = refused(Durability.HA, server("on", false, "", true, List.of(ASYNC)));
        assertTrue(refusal.contains("synchronous_standby_names"), "names the setting: " + refusal);
    }

    @Test
    void haRefusesARoleThatCannotReadAllStatisticsNamingTheGrant() {
        PgDurability.Standby hidden = new PgDurability.Standby("standby-a", null, null);   // what such a role reads
        String refusal = refused(Durability.HA, server("on", false, "*", false, List.of(hidden)));
        assertTrue(refusal.contains("GRANT pg_monitor TO intake"), "names the grant for the node's user: " + refusal);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"no standby at all,,,", "an async standby streaming, standby-b, streaming, async",
            "a potential standby streaming, standby-b, streaming, potential", "a sync standby catching up, standby-a, catchup, sync",
            "a quorum standby starting, standby-q, startup, quorum"})
    void haRefusesAServerWithoutASyncOrQuorumStandbyStreaming(String what, String name, String state, String sync) {
        List<PgDurability.Standby> standbys = name == null ? List.of() : List.of(new PgDurability.Standby(name, state, sync));
        String refusal = refused(Durability.HA, server("on", false, "*", true, standbys));
        assertTrue(refusal.contains("standby"), what + ": names the missing synchronous standby: " + refusal);
    }

    /** Witness for the refusals above: a streaming sync standby, or a streaming quorum one, is what ha asks for. */
    @Test
    void haAcceptsASyncOrAQuorumStandbyStreaming() {
        accepted(Durability.HA, good());
        accepted(Durability.HA, server("on", false, "ANY 1 (standby-q, standby-b)", true, List.of(QUORUM, ASYNC)));
    }

    /** The ha-only refusals are ha's: local accepts empty names, a role without the statistics grant and no standby at all. */
    @Test
    void localDoesNotAskForAStandby() {
        accepted(Durability.LOCAL, server("on", false, "", false, List.of()));
        accepted(Durability.LOCAL, server("on", false, "", true, List.of(ASYNC)));
    }

    // ------------------------------------------------------------------------------------- FIX-137 B5: the duplicate's barrier

    @ParameterizedTest(name = "user {0}")
    @CsvSource({"intake", "flottila_ha"})
    void haRefusesARoleThatMayNotEmitLogicalMessagesNamingTheGrantAndTheUser(String user) {
        String refusal = refused(Durability.HA, new PgDurability.Server(user, "on", false, "*", true, List.of(SYNC), false));
        assertTrue(emitGrant(user).matcher(refusal).find(), "names GRANT EXECUTE ON FUNCTION pg_catalog.pg_logical_emit_message(...) TO "
                + user + ": " + refusal);
        assertTrue(word(refusal, "D-128"), "names D-128: " + refusal);
    }

    /** Witness for the refusal above: the same server, the role allowed to run the barrier, is accepted with no word of it. */
    @Test
    void haAcceptsARoleThatMayEmitLogicalMessages() {
        List<String> notices = accepted(Durability.HA, new PgDurability.Server("intake", "on", false, "*", true, List.of(SYNC), true));
        assertFalse(notices.stream().anyMatch(n -> n.contains("pg_logical_emit_message")), "nothing to grant: " + notices);
    }

    /** Order among refusals: a role lacking both the statistics and the EXECUTE is refused for the statistics first. */
    @Test
    void haNamesTheStatisticsGrantBeforeTheBarrierGrant() {
        PgDurability.Standby hidden = new PgDurability.Standby("standby-a", null, null);
        String refusal = refused(Durability.HA, new PgDurability.Server("intake", "on", false, "*", false, List.of(hidden), false));
        assertTrue(refusal.contains("GRANT pg_monitor TO intake"), "the pg_read_all_stats refusal comes first: " + refusal);
        assertFalse(refusal.contains("pg_logical_emit_message"), "not the barrier's: " + refusal);
    }

    /** local runs no barrier: a role that may not emit logical messages is accepted, with the notices of one that may. */
    @Test
    void localIgnoresWhetherTheRoleMayEmitLogicalMessages() {
        List<String> without = accepted(Durability.LOCAL, new PgDurability.Server("intake", "on", false, "", true, List.of(ASYNC), false));
        List<String> with = accepted(Durability.LOCAL, new PgDurability.Server("intake", "on", false, "", true, List.of(ASYNC), true));
        assertEquals(with, without, "local's notices do not depend on the EXECUTE");
        assertFalse(without.stream().anyMatch(n -> n.contains("pg_logical_emit_message")), "no word of the barrier under local: " + without);
    }

    // ---------------------------------------------------------------------------------------------------------------- notices

    @Test
    void localStatesItsPromiseWithOrWithoutStandbys() {
        for (List<PgDurability.Standby> standbys : List.of(List.<PgDurability.Standby>of(), List.of(ASYNC))) {
            List<String> notices = accepted(Durability.LOCAL, server("on", false, "", true, standbys));
            assertTrue(notices.stream().anyMatch(n -> n.contains("survives a Postgres crash") && n.contains("not a promotion")
                    && n.contains("loss of its host")), "the local promise, standbys " + standbys + ": " + notices);
        }
    }

    @Test
    void localWithStreamingStandbysNamesThemAndHa() {
        List<String> notices = accepted(Durability.LOCAL, server("on", false, "", true, List.of(ASYNC, new PgDurability.Standby("standby-c", "streaming", "async"))));
        assertTrue(notices.stream().anyMatch(n -> n.contains("standby-b") && n.contains("standby-c") && word(n, "ha")),
                "a notice names both streaming standbys and the ha profile: " + notices);
        List<String> alone = accepted(Durability.LOCAL, server("on", false, "", true, List.of()));
        assertFalse(alone.stream().anyMatch(n -> word(n, "ha")), "witness: without a standby no notice points to ha: " + alone);
    }

    @Test
    void haWithANonSyncStandbyNamesD115() {
        List<String> mixed = accepted(Durability.HA, server("on", false, "standby-a", true, List.of(SYNC, ASYNC)));
        assertTrue(mixed.stream().anyMatch(n -> n.contains("D-115")), "an async standby beside the sync one: D-115 named: " + mixed);
        List<String> allSync = accepted(Durability.HA, good());
        assertFalse(allSync.stream().anyMatch(n -> n.contains("D-115")), "witness: every standby sync, no D-115 notice: " + allSync);
    }

    // --------------------------------------------------------------------------------------------------------------- properties

    /**
     * ha accepts a server exactly when its commits wait for a synchronous standby: the session commits {@code on}, the server is
     * a primary, {@code synchronous_standby_names} is set, the role sees the standbys' state, and one of them streams as sync or
     * quorum, and the role may run {@code pg_logical_emit_message} (FIX-137 B5). Written from P3/P4 and B5, not from the verdict's code.
     */
    @Property(tries = 2000)
    void haAcceptsOnlyAServerWhoseCommitsWaitForASyncStandby(@ForAll("servers") PgDurability.Server server) {
        boolean waits = server.synchronousCommit().equals("on") && !server.inRecovery() && !server.synchronousStandbyNames().isEmpty()
                && server.readsAllStats() && server.standbys().stream().anyMatch(s -> "streaming".equals(s.state())
                && ("sync".equals(s.syncState()) || "quorum".equals(s.syncState()))) && server.emitsLogicalMessages();
        assertEquals(waits, PgDurability.verdict(Durability.HA, server).refusal().isEmpty(), "ha's verdict on " + server);
    }

    /** local accepts a server exactly when the session commits {@code on} on a primary, whatever its standbys and the EXECUTE. */
    @Property(tries = 1000)
    void localAcceptsOnlyAPrimaryWhoseSessionCommitsOn(@ForAll("servers") PgDurability.Server server) {
        boolean ok = server.synchronousCommit().equals("on") && !server.inRecovery();
        assertEquals(ok, PgDurability.verdict(Durability.LOCAL, server).refusal().isEmpty(), "local's verdict on " + server);
    }

    @Provide
    Arbitrary<PgDurability.Server> servers() {
        Arbitrary<PgDurability.Standby> standby = Combinators.combine(
                Arbitraries.of("standby-a", "standby-b", "standby-c"),
                Arbitraries.of("streaming", "catchup", "startup", "backup", "stopping").injectNull(0.1),
                Arbitraries.of("sync", "quorum", "async", "potential").injectNull(0.1)).as(PgDurability.Standby::new);
        return Combinators.combine(
                Arbitraries.of("intake", "flottila"),
                Arbitraries.of("on", "off", "local", "remote_write", "remote_apply"),
                Arbitraries.of(false, true),
                Arbitraries.of("", "*", "standby-a", "FIRST 1 (standby-a, standby-b)", "ANY 2 (standby-a, standby-b, standby-c)"),
                Arbitraries.of(true, false),
                standby.list().ofMaxSize(3),
                Arbitraries.of(true, false)).as(PgDurability.Server::new);
    }
}
