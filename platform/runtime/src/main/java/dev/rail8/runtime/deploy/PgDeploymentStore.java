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
 * <p>The pool's lock is the session advisory lock {@code (294, 0)} ({@code 0x0126}), taken with {@code pg_try_advisory_lock} on
 * a connection of its own, opened for the one holder and never pooled: its writes run on that connection, and closing it frees
 * the lock, so a node that vanishes frees it once the server sees the connection gone. Reads run on {@code pool}.
 *
 * @since 0.1
 */
public final class PgDeploymentStore implements DeploymentStore {

    private static final String READ = "select p.generation, d.package, d.version, d.repository, d.commit, d.content, d.deployed_at, d.dependencies, "
            + "d.at_least_once from deploy_pool p left join deployments d on true";
    private static final String TRY_LOCK = "select pg_try_advisory_lock(294, 0)";
    private static final String UNLOCK = "select pg_advisory_unlock(294, 0)";
    private static final String RAISE = "update deploy_pool set generation = generation + 1 where generation = $1 returning generation";
    private static final String SAVE = "insert into deployments (package, version, repository, commit, content, deployed_at, dependencies, at_least_once) "
            + "values ($1, $2, $3, $4, $5, $6, $7, $8) "
            + "on conflict (package) do update set version = excluded.version, repository = excluded.repository, commit = excluded.commit, "
            + "content = excluded.content, deployed_at = excluded.deployed_at, dependencies = excluded.dependencies, at_least_once = excluded.at_least_once";
    private static final String DELETE = "delete from deployments where package = $1";
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
    public Future<Void> audit(String actor, String repository, JsonObject detail) {
        return pool.preparedQuery(AUDIT).execute(Tuple.of(actor, "repository/" + repository, detail)).mapEmpty();
    }

    /** The generation of a {@link #READ} and its records, sorted by name; no row means no {@code deploy_pool} row: a failure. */
    private static Snapshot snapshot(RowSet<Row> rows) {
        Long generation = null;
        List<DeploymentRecord> records = new ArrayList<>();
        for (Row r : rows) {
            generation = r.getLong("generation");
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
        return new Snapshot(generation, records);
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
            return write(SAVE, Tuple.of(r.name(), r.version(), r.repository(), r.commit(), r.content(), r.deployedAt().atOffset(ZoneOffset.UTC), pins,
                    r.atLeastOnce().toArray(String[]::new)));
        }

        @Override
        public Future<Long> delete(String name) {
            return write(DELETE, Tuple.of(name));
        }

        /** {@code sql} with the generation raised, in one transaction; a generation that moved writes nothing. */
        private Future<Long> write(String sql, Tuple args) {
            long expected = generation;
            return conn.begin().compose(tx -> conn.preparedQuery(RAISE).execute(Tuple.of(expected)).compose(raised -> {
                if (raised.rowCount() == 0) {
                    return Future.<Long>failedFuture(new IllegalStateException("the pool's generation is no longer " + expected));
                }
                long next = raised.iterator().next().getLong(0);
                return conn.preparedQuery(sql).execute(args).map(next);
            }).compose(next -> tx.commit().map(next), failed -> tx.rollback().transform(rolledBack -> Future.<Long>failedFuture(failed))))
                    .onSuccess(next -> generation = next);
        }

        @Override
        public Future<Void> close() {
            return conn.query(UNLOCK).execute().transform(unlocked -> conn.close()).otherwiseEmpty();
        }
    }
}
