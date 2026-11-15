package io.flottila.runtime.node;

import io.flottila.runtime.node.NodeConfig.Pg.Durability;
import io.vertx.core.json.JsonArray;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The node's Postgres durability profile, checked once at its start before it migrates (FIX-136, D-127 draft, D-114, D-115;
 * D-061 amended). The node pins every session to {@code synchronous_commit=on} and {@code statement_timeout=0}
 * ({@link Node#connectOptions}); this check reads what a session of the pool actually runs with and what the server is, in one
 * statement, and refuses a server the profile's promise does not hold on. Settings are read at the start only: a standby lost
 * later makes commits wait in Postgres, bounded for the caller by the save timeout (503); the node never cancels a commit.
 */
final class PgDurability {

    /** The local profile's promise, stated at every start under it. */
    static final String LOCAL_PROMISE = "FLOTTILA_PG_DURABILITY=local: an accepted message survives a Postgres crash, not a promotion or the "
            + "loss of its host (D-127)";

    /**
     * The one read: the session's own settings, the server's recovery state and standby names, whether the role sees every
     * statistic, the {@code pg_stat_replication} entries as one array of {@code [name, state, sync_state]} (empty when none), and
     * whether the role may run {@code pg_logical_emit_message}, the duplicate's commit barrier (FIX-137, D-128). That function is
     * looked up with {@code to_regprocedure} by its PostgreSQL 17 signature, so a server without it answers false, never an error.
     */
    static final String READ = "select current_user::text, current_setting('synchronous_commit'), pg_is_in_recovery(), "
            + "current_setting('synchronous_standby_names'), pg_has_role(current_user, 'pg_read_all_stats', 'USAGE'), "
            + "coalesce((select jsonb_agg(jsonb_build_array(application_name, state, sync_state) order by pid) from pg_stat_replication), '[]'::jsonb), "
            + "coalesce(has_function_privilege(current_user, to_regprocedure('pg_catalog.pg_logical_emit_message(boolean,text,text,boolean)'), "
            + "'EXECUTE'), false)";

    private PgDurability() {
    }

    /**
     * A standby as {@code pg_stat_replication} shows it to the node's role.
     *
     * @param name its {@code application_name}
     * @param state its {@code state} ({@code streaming}, {@code catchup}, ...); null when the role cannot read all statistics
     * @param syncState its {@code sync_state} ({@code sync}, {@code quorum}, {@code async}, {@code potential}); null as {@code state}
     */
    record Standby(String name, String state, String syncState) {

        boolean streaming() {
            return "streaming".equals(state);
        }

        /** A commit under {@code synchronous_commit=on} waits for it: it streams, as a {@code sync} or a {@code quorum} standby. */
        boolean waitedFor() {
            return streaming() && ("sync".equals(syncState) || "quorum".equals(syncState));
        }
    }

    /**
     * What one read of the server found.
     *
     * @param user the node's role ({@code current_user})
     * @param synchronousCommit the session's {@code synchronous_commit}
     * @param inRecovery {@code pg_is_in_recovery()}: the host is a standby
     * @param synchronousStandbyNames the server's {@code synchronous_standby_names}
     * @param readsAllStats the role has the privileges of {@code pg_read_all_stats} (it sees a standby's state)
     * @param standbys the {@code pg_stat_replication} rows
     * @param emitsLogicalMessages the role may run {@code pg_logical_emit_message(boolean, text, text, boolean)} (FIX-137, D-128)
     */
    record Server(String user, String synchronousCommit, boolean inRecovery, String synchronousStandbyNames, boolean readsAllStats,
                  List<Standby> standbys, boolean emitsLogicalMessages) {

        Server {
            standbys = List.copyOf(standbys);
        }
    }

    /**
     * The verdict on a server.
     *
     * @param refusal why the node does not start on it, or empty
     * @param notices what the node states beside its health when it starts on it
     */
    record Verdict(Optional<String> refusal, List<String> notices) {

        static Verdict refused(String why) {
            return new Verdict(Optional.of(why), List.of());
        }
    }

    /**
     * The pure verdict of {@code profile} on {@code server}. Both profiles refuse a session that does not commit {@code on} (a
     * pooler dropped the node's startup parameter) and a server in recovery (the host names a standby). {@code ha} also refuses an
     * empty {@code synchronous_standby_names}, a role that cannot read every statistic, then a role that may not run
     * {@code pg_logical_emit_message} (the duplicate's commit barrier, D-128), and a server with no sync or quorum standby
     * streaming. Accepted under {@code local}, the notices state its promise and, with standbys streaming, name them and {@code ha};
     * accepted under {@code ha}, they name D-115 for each standby a commit does not wait for.
     */
    static Verdict verdict(Durability profile, Server server) {
        if (!server.synchronousCommit().equals("on")) {
            return Verdict.refused("the node's Postgres session runs with synchronous_commit " + server.synchronousCommit() + ", not the on it "
                    + "asked for at connect: a pooler between the node and Postgres dropped the startup parameter; connect the node to Postgres "
                    + "directly or to a pooler that passes it (D-127)");
        }
        if (server.inRecovery()) {
            return Verdict.refused("the Postgres server of FLOTTILA_PG_HOST is in recovery (a standby): the node writes to the primary only (D-127)");
        }
        if (profile == Durability.LOCAL) return new Verdict(Optional.empty(), localNotices(server));
        if (server.synchronousStandbyNames().isBlank()) {
            return Verdict.refused("FLOTTILA_PG_DURABILITY=ha and the Postgres synchronous_standby_names is empty: no commit waits for a standby; "
                    + "name the synchronous standbys on the primary, or set FLOTTILA_PG_DURABILITY=local (D-127)");
        }
        if (!server.readsAllStats()) {
            return Verdict.refused("FLOTTILA_PG_DURABILITY=ha and the role " + server.user() + " cannot read pg_stat_replication's state, so the node "
                    + "cannot see whether a synchronous standby streams: GRANT pg_monitor TO " + server.user() + " (D-127)");
        }
        if (!server.emitsLogicalMessages()) {
            return Verdict.refused("FLOTTILA_PG_DURABILITY=ha and the role " + server.user() + " may not run pg_catalog.pg_logical_emit_message, "
                    + "the commit barrier that holds a duplicate's answer until the earlier save is on a synchronous standby (ha needs "
                    + "PostgreSQL 17 or later): GRANT EXECUTE ON FUNCTION pg_catalog.pg_logical_emit_message(boolean, text, text, boolean) TO "
                    + server.user() + " (D-128)");
        }
        if (server.standbys().stream().noneMatch(Standby::waitedFor)) {
            return Verdict.refused("FLOTTILA_PG_DURABILITY=ha and no sync or quorum standby is streaming (synchronous_standby_names '"
                    + server.synchronousStandbyNames() + "', standbys " + describe(server.standbys()) + "): a commit would wait for a "
                    + "standby that is not there (D-127)");
        }
        List<Standby> notWaited = server.standbys().stream().filter(s -> !s.waitedFor()).toList();
        List<String> notices = notWaited.isEmpty() ? List.of()
                : List.of("standbys " + describe(notWaited) + " are not synchronous: a commit does not wait for them, so a failover must promote a "
                        + "synchronous standby, never one of these (D-115)");
        return new Verdict(Optional.empty(), notices);
    }

    private static List<String> localNotices(Server server) {
        List<String> notices = new ArrayList<>();
        notices.add(LOCAL_PROMISE);
        List<Standby> streaming = server.standbys().stream().filter(Standby::streaming).toList();
        if (!streaming.isEmpty()) {
            notices.add("Postgres streams to standbys " + describe(streaming) + ", which a commit under local does not wait for: their promotion "
                    + "loses accepted messages; FLOTTILA_PG_DURABILITY=ha waits for a synchronous one (D-127)");
        }
        return notices;
    }

    private static String describe(List<Standby> standbys) {
        if (standbys.isEmpty()) return "none";
        return standbys.stream().map(s -> s.name() + " (" + s.state() + ", " + s.syncState() + ")").collect(Collectors.joining(", "));
    }

    /**
     * Reads the server through {@code pool} (one statement, on a session that carries the node's startup parameters) and answers
     * the verdict's notices, or throws the refusal.
     *
     * @throws IllegalStateException the profile's refusal
     */
    static List<String> check(Pool pool, Durability profile) {
        Row row = pool.query(READ).execute().await().iterator().next();   // one row: no FROM
        List<Standby> standbys = new ArrayList<>();
        for (Object entry : row.getJsonArray(5)) {
            JsonArray standby = (JsonArray) entry;
            standbys.add(new Standby(standby.getString(0), standby.getString(1), standby.getString(2)));
        }
        Verdict verdict = verdict(profile, new Server(row.getString(0), row.getString(1), row.getBoolean(2), row.getString(3), row.getBoolean(4), standbys,
                row.getBoolean(6)));
        if (verdict.refusal().isPresent()) throw new IllegalStateException(verdict.refusal().get());
        return verdict.notices();
    }
}
