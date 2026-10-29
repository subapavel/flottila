package io.flottila.runtime.deploy;

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
import java.util.Optional;

/**
 * The {@link DeploymentStore} over V7's {@code deployments} table (D-070): one row per package, shared by the pool's nodes. A save
 * is an upsert that replaces every column, V9's {@code dependencies} (the pins as a JSON array, D-087) included; the listing is sorted here, by {@link String#compareTo}, since the database's
 * collation (en_US ignores a dash) is not the order {@code Deployments} lists its packages in.
 *
 * @since 0.1
 */
public final class PgDeploymentStore implements DeploymentStore {

    private static final String SAVE = "insert into deployments (package, version, revision, archive_sha256, deployed_at, dependencies) "
            + "values ($1, $2, $3, $4, $5, $6) "
            + "on conflict (package) do update set version = excluded.version, revision = excluded.revision, archive_sha256 = excluded.archive_sha256, "
            + "deployed_at = excluded.deployed_at, dependencies = excluded.dependencies";
    private static final String LIST = "select package, version, revision, archive_sha256, deployed_at, dependencies from deployments";

    private final Pool pool;

    public PgDeploymentStore(Pool pool) {
        this.pool = Objects.requireNonNull(pool, "pool");
    }

    @Override
    public Future<Void> save(DeploymentRecord r) {
        JsonArray pins = new JsonArray();
        for (DeploymentRecord.Pin p : r.dependencies()) {
            pins.add(new JsonObject().put("name", p.name()).put("version", p.version()).put("archiveSha256", p.archiveSha256()));
        }
        return pool.preparedQuery(SAVE).execute(Tuple.of(r.name(), r.version(), r.revision().orElse(null), r.archiveSha256(),
                r.deployedAt().atOffset(ZoneOffset.UTC), pins)).mapEmpty();
    }

    @Override
    public Future<List<DeploymentRecord>> list() {
        return pool.query(LIST).execute().map(rows -> {
            List<DeploymentRecord> records = new ArrayList<>();
            for (Row r : rows) {
                List<DeploymentRecord.Pin> pins = new ArrayList<>();
                for (Object o : r.getJsonArray("dependencies")) {
                    JsonObject p = (JsonObject) o;
                    pins.add(new DeploymentRecord.Pin(p.getString("name"), p.getString("version"), p.getString("archiveSha256")));
                }
                records.add(new DeploymentRecord(r.getString("package"), r.getString("version"), Optional.ofNullable(r.getString("revision")),
                        r.getString("archive_sha256"), r.getOffsetDateTime("deployed_at").toInstant(), pins));
            }
            records.sort(Comparator.comparing(DeploymentRecord::name));
            return List.copyOf(records);
        });
    }
}
