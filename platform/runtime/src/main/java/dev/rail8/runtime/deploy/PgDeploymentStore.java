package dev.rail8.runtime.deploy;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The {@link DeploymentStore} over the {@code deployments} table: one row per package, shared by the pool's nodes. A save is an
 * upsert that replaces every column, the pins included (a JSON array of {@code {"name", "version", "content", "repository",
 * "commit"}}); the listing is sorted here, by {@link String#compareTo}, since the database's collation (en_US ignores a dash) is
 * not the order {@code Deployments} lists its packages in. A delete removes the package's row (an undeploy). The audit row of a
 * deploy goes into the {@code audit} table, stamped with the database's {@code now()}.
 *
 * @since 0.1
 */
public final class PgDeploymentStore implements DeploymentStore {

    private static final String SAVE = "insert into deployments (package, version, repository, commit, content, deployed_at, dependencies) "
            + "values ($1, $2, $3, $4, $5, $6, $7) "
            + "on conflict (package) do update set version = excluded.version, repository = excluded.repository, commit = excluded.commit, "
            + "content = excluded.content, deployed_at = excluded.deployed_at, dependencies = excluded.dependencies";
    private static final String DELETE = "delete from deployments where package = $1";
    private static final String LIST = "select package, version, repository, commit, content, deployed_at, dependencies from deployments";
    private static final String AUDIT = "insert into audit (at, actor, action, subject, detail) values (now(), $1, 'deploy', $2, $3)";

    private final Pool pool;

    public PgDeploymentStore(Pool pool) {
        this.pool = Objects.requireNonNull(pool, "pool");
    }

    @Override
    public Future<Void> save(DeploymentRecord r) {
        JsonArray pins = new JsonArray();
        for (DeploymentRecord.Pin p : r.dependencies()) {
            pins.add(new JsonObject().put("name", p.name()).put("version", p.version()).put("content", p.content()).put("repository", p.repository())
                    .put("commit", p.commit()));
        }
        return pool.preparedQuery(SAVE).execute(Tuple.of(r.name(), r.version(), r.repository(), r.commit(), r.content(),
                r.deployedAt().atOffset(ZoneOffset.UTC), pins)).mapEmpty();
    }

    @Override
    public Future<Void> delete(String name) {
        return pool.preparedQuery(DELETE).execute(Tuple.of(name)).mapEmpty();
    }

    @Override
    public Future<List<DeploymentRecord>> list() {
        return pool.query(LIST).execute().map(rows -> {
            List<DeploymentRecord> records = new ArrayList<>();
            for (Row r : rows) {
                List<DeploymentRecord.Pin> pins = new ArrayList<>();
                for (Object o : r.getJsonArray("dependencies")) {
                    JsonObject p = (JsonObject) o;
                    pins.add(new DeploymentRecord.Pin(p.getString("name"), p.getString("version"), p.getString("content"), p.getString("repository"),
                            p.getString("commit")));
                }
                records.add(new DeploymentRecord(r.getString("package"), r.getString("version"), r.getString("repository"), r.getString("commit"),
                        r.getString("content"), r.getOffsetDateTime("deployed_at").toInstant(), pins));
            }
            records.sort(Comparator.comparing(DeploymentRecord::name));
            return List.copyOf(records);
        });
    }

    @Override
    public Future<Void> audit(String actor, String repository, JsonObject detail) {
        return pool.preparedQuery(AUDIT).execute(Tuple.of(actor, "repository/" + repository, detail)).mapEmpty();
    }
}
