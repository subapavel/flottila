package dev.rail8.runtime.deploy;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The {@link DeploymentStore} over the {@code deployments} and {@code deploy_pool} tables: one row per package and the pool's
 * generation, shared by the pool's nodes. A save is an upsert that replaces every column, the pins included (a JSON array of
 * {@code {"name", "version", "content", "repository", "commit"}}); a read is sorted here, by {@link String#compareTo}, since the
 * database's collation (en_US ignores a dash) is not the order {@code Deployments} lists its packages in. The audit row of a
 * deploy goes into the {@code audit} table, stamped with the database's {@code now()}.
 *
 * <p>The flows the pool retires are rows of {@code retiring}, one per flow, each with a copy of the record it ran under: a save
 * marks each flow the replaced row ran at-least-once and the new one does not, by an {@code insert ... select} from the old row
 * before the upsert, and deletes the marks of the flows the new row runs at-least-once; a delete marks every flow the row ran
 * at-least-once. {@link #endMarks} runs on {@code pool}, in one transaction: for each mark the flow's exclusive admission lock
 * {@code (295, hashtext(flow))} ({@code 0x0127}), taken without waiting, and in a later statement — its snapshot taken after the
 * lock, under READ COMMITTED — the mark deleted when no {@code work} row of the flow is left. A write of a message holds that lock shared from before its admission check to its commit, so a mark never ends
 * between a message's admission and its commit.
 *
 * <p>The pool's lock is the session advisory lock {@code (294, 0)} ({@code 0x0126}), taken with {@code pg_try_advisory_lock} on
 * a connection of its own, opened for the one holder and never pooled: its writes run on that connection, and closing it frees
 * the lock, so a node that vanishes frees it once the server sees the connection gone. Reads run on {@code pool}.
 *
 * @since 0.1
 */
public final class PgDeploymentStore implements DeploymentStore {

    private static final String READ = "select p.generation, (select coalesce(array_agg(r.flow order by r.flow), '{}') from retiring r) as marked, "
            + "d.package, d.version, d.repository, d.commit, d.content, d.deployed_at, d.dependencies, d.at_least_once "
            + "from deploy_pool p left join deployments d on true";
    private static final String TRY_LOCK = "select pg_try_advisory_lock(294, 0)";
    private static final String UNLOCK = "select pg_advisory_unlock(294, 0)";
    private static final String RAISE = "update deploy_pool set generation = generation + 1 where generation = $1 returning generation";
    private static final String SAVE = "insert into deployments (package, version, repository, commit, content, deployed_at, dependencies, at_least_once) "
            + "values ($1, $2, $3, $4, $5, $6, $7, $8) "
            + "on conflict (package) do update set version = excluded.version, repository = excluded.repository, commit = excluded.commit, "
            + "content = excluded.content, deployed_at = excluded.deployed_at, dependencies = excluded.dependencies, at_least_once = excluded.at_least_once";
    private static final String DELETE = "delete from deployments where package = $1";
    /** Marks each flow package $1's row runs at-least-once that is not in $2, with a copy of the row. */
    private static final String MARK = "insert into retiring (flow, package, version, repository, commit, content, dependencies, deployed_at, marked_at) "
            + "select f, d.package, d.version, d.repository, d.commit, d.content, d.dependencies, d.deployed_at, now() "
            + "from deployments d cross join unnest(d.at_least_once) as u(f) where d.package = $1 and not (f = any($2::text[])) on conflict (flow) do nothing";
    /** Ends the marks of the flows in $1: a new row runs them at-least-once again. */
    private static final String REVIVE = "delete from retiring where flow = any($1::text[])";
    private static final String MARKED = "select flow from retiring order by flow";
    private static final String TRY_FLOW = "select pg_try_advisory_xact_lock(295, hashtext($1))";
    private static final String END = "delete from retiring where flow = $1 and not exists (select 1 from work where flow = $1)";
    private static final String AUDIT = "insert into audit (at, actor, action, subject, detail) values (now(), $1, 'deploy', $2, $3)";

    private final Pool pool;
    private final Supplier<Future<SqlConnection>> connect;

    /**
     * @param pool where the reads and the audit rows go
     * @param connect opens a fresh connection for one holder of the pool's lock, its own to close
     */
    public PgDeploymentStore(Pool pool, Supplier<Future<SqlConnection>> connect) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.connect = Objects.requireNonNull(connect, "connect");
    }

    @Override
    public Future<Snapshot> read() {
        return pool.query(READ).execute().map(PgDeploymentStore::snapshot);
    }

    @Override
    public Future<Optional<PoolLock>> lock() {
        return connect.get().compose(conn -> conn.query(TRY_LOCK).execute().compose(taken -> {
            if (!taken.iterator().next().getBoolean(0)) return conn.close().map(Optional.<PoolLock>empty());
            return conn.query(READ).execute().map(rows -> Optional.<PoolLock>of(new Held(conn, snapshot(rows))));
        }).recover(failed -> conn.close().transform(closed -> Future.failedFuture(failed))));
    }

    @Override
    public Future<Void> endMarks() {
        return pool.withTransaction(tx -> tx.query(MARKED).execute().compose(marks -> {
            Future<Void> ended = Future.succeededFuture();
            for (Row mark : marks) {
                String flow = mark.getString(0);
                ended = ended.compose(n -> tx.preparedQuery(TRY_FLOW).execute(Tuple.of(flow)).compose(locked -> {
                    if (!locked.iterator().next().getBoolean(0)) return Future.succeededFuture();   // a write of a message for it is open
                    return tx.preparedQuery(END).execute(Tuple.of(flow)).mapEmpty();
                }));
            }
            return ended;
        }));
    }

    @Override
    public Future<Void> audit(String actor, String repository, JsonObject detail) {
        return pool.preparedQuery(AUDIT).execute(Tuple.of(actor, "repository/" + repository, detail)).mapEmpty();
    }

    /** The generation of a {@link #READ} and its records, sorted by name; no row means no {@code deploy_pool} row: a failure. */
    private static Snapshot snapshot(RowSet<Row> rows) {
        Long generation = null;
        List<String> marked = List.of();
        List<DeploymentRecord> records = new ArrayList<>();
        for (Row r : rows) {
            generation = r.getLong("generation");
            marked = List.of(r.getArrayOfStrings("marked"));
            if (r.getString("package") == null) continue;   // the pool's row alone: no record
            List<DeploymentRecord.Pin> pins = new ArrayList<>();
            for (Object o : r.getJsonArray("dependencies")) {
                JsonObject p = (JsonObject) o;
                pins.add(new DeploymentRecord.Pin(p.getString("name"), p.getString("version"), p.getString("content"), p.getString("repository"),
                        p.getString("commit")));
            }
            records.add(new DeploymentRecord(r.getString("package"), r.getString("version"), r.getString("repository"), r.getString("commit"),
                    r.getString("content"), r.getOffsetDateTime("deployed_at").toInstant(), pins, List.of(r.getArrayOfStrings("at_least_once"))));
        }
        if (generation == null) throw new IllegalStateException("the table deploy_pool has no row: the database is not migrated to V27");
        records.sort(Comparator.comparing(DeploymentRecord::name));
        return new Snapshot(generation, records, marked);
    }

    /** The pool's lock on its own connection: each write one transaction on it. */
    private static final class Held implements PoolLock {
        private final SqlConnection conn;
        private final Snapshot snapshot;
        /** The generation the next write raises: the one read, then the one the last write committed. */
        private volatile long generation;

        Held(SqlConnection conn, Snapshot snapshot) {
            this.conn = conn;
            this.snapshot = snapshot;
            this.generation = snapshot.generation();
        }

        @Override
        public Snapshot snapshot() {
            return snapshot;
        }

        @Override
        public Future<Long> save(DeploymentRecord r) {
            JsonArray pins = new JsonArray();
            for (DeploymentRecord.Pin p : r.dependencies()) {
                pins.add(new JsonObject().put("name", p.name()).put("version", p.version()).put("content", p.content()).put("repository", p.repository())
                        .put("commit", p.commit()));
            }
            String[] runs = r.atLeastOnce().toArray(String[]::new);
            return write(List.of(Map.entry(MARK, Tuple.of(r.name(), (Object) runs)), Map.entry(REVIVE, Tuple.of((Object) runs)),
                    Map.entry(SAVE, Tuple.of(r.name(), r.version(), r.repository(), r.commit(), r.content(), r.deployedAt().atOffset(ZoneOffset.UTC), pins, runs))));
        }

        @Override
        public Future<Long> delete(String name) {
            return write(List.of(Map.entry(MARK, Tuple.of(name, (Object) new String[0])), Map.entry(DELETE, Tuple.of(name))));
        }

        /**
         * Each of {@code statements}, in order, with the generation raised, in one transaction; a generation that moved writes
         * nothing, and neither does a lock that was freed: its connection is closed, and the client's throw is the write's failure.
         */
        private Future<Long> write(List<Map.Entry<String, Tuple>> statements) {
            long expected = generation;
            return Future.<Void>succeededFuture().compose(start -> conn.begin()).compose(tx -> conn.preparedQuery(RAISE).execute(Tuple.of(expected)).compose(raised -> {
                if (raised.rowCount() == 0) {
                    return Future.<Long>failedFuture(new IllegalStateException("the pool's generation is no longer " + expected));
                }
                long next = raised.iterator().next().getLong(0);
                Future<Void> done = Future.succeededFuture();
                for (Map.Entry<String, Tuple> st : statements) done = done.compose(previous -> conn.preparedQuery(st.getKey()).execute(st.getValue()).mapEmpty());
                return done.map(next);
            }).compose(next -> tx.commit().map(next), failed -> tx.rollback().transform(rolledBack -> Future.<Long>failedFuture(failed))))
                    .onSuccess(next -> generation = next);
        }

        @Override
        public Future<Void> close() {
            return Future.<Void>succeededFuture().compose(start -> conn.query(UNLOCK).execute()).transform(unlocked -> conn.close()).otherwiseEmpty();
        }
    }
}
