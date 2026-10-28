package io.flottila.runtime.deploy;

import io.vertx.core.Future;

import java.util.List;

/**
 * Where the {@link DeploymentRecord}s live (D-070): Postgres on a node ({@link PgDeploymentStore}), memory in dev and tests
 * ({@link InMemoryDeploymentStore}).
 *
 * @since 0.1
 */
public interface DeploymentStore {

    /** Writes {@code record} as its package's one row: a row the package had is replaced whole. */
    Future<Void> save(DeploymentRecord record);

    /** Every row, sorted by name as {@link String#compareTo} sorts ({@code Deployments} lists its packages so), whatever the database's collation. */
    Future<List<DeploymentRecord>> list();
}
