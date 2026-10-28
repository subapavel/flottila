package io.flottila.runtime.deploy;

import io.vertx.core.Future;
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
 * is an upsert that replaces every column; the listing is sorted here, by {@link String#compareTo}, since the database's
 * collation (en_US ignores a dash) is not the order {@code Deployments} lists its packages in.
 *
 * @since 0.1
 */
public final class PgDeploymentStore implements DeploymentStore {

    private static final String SAVE = "insert into deployments (package, version, revision, archive_sha256, deployed_at) values ($1, $2, $3, $4, $5) "
            + "on conflict (package) do update set version = excluded.version, revision = excluded.revision, archive_sha256 = excluded.archive_sha256, "
            + "deployed_at = excluded.deployed_at";
    private static final String LIST = "select package, version, revision, archive_sha256, deployed_at from deployments";

    private final Pool pool;

    public PgDeploymentStore(Pool pool) {
        this.pool = Objects.requireNonNull(pool, "pool");
    }

    @Override
    public Future<Void> save(DeploymentRecord r) {
        return pool.preparedQuery(SAVE).execute(Tuple.of(r.name(), r.version(), r.revision().orElse(null), r.archiveSha256(),
                r.deployedAt().atOffset(ZoneOffset.UTC))).mapEmpty();
    }

    @Override
    public Future<List<DeploymentRecord>> list() {
        return pool.query(LIST).execute().map(rows -> {
            List<DeploymentRecord> records = new ArrayList<>();
            for (Row r : rows) {
                records.add(new DeploymentRecord(r.getString("package"), r.getString("version"), Optional.ofNullable(r.getString("revision")),
                        r.getString("archive_sha256"), r.getOffsetDateTime("deployed_at").toInstant()));
            }
            records.sort(Comparator.comparing(DeploymentRecord::name));
            return List.copyOf(records);
        });
    }
}
