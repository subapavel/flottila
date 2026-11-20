package dev.rail8.runtime.deploy;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * Where the {@link DeploymentRecord}s and the audit rows of the deploys live: Postgres on a node ({@link PgDeploymentStore}),
 * memory in dev and tests ({@link InMemoryDeploymentStore}).
 *
 * @since 0.1
 */
public interface DeploymentStore {

    /** Writes {@code record} as its package's one row: a row the package had is replaced whole. */
    Future<Void> save(DeploymentRecord record);

    /** Deletes the row of the package {@code name}, and no other; none there is no failure. */
    Future<Void> delete(String name);

    /** Every row, sorted by name as {@link String#compareTo} sorts ({@code Deployments} lists its packages so), whatever the database's collation. */
    Future<List<DeploymentRecord>> list();

    /**
     * Appends the audit row of one deploy: when (the store's clock), {@code actor}, the action {@code deploy}, the subject
     * {@code repository/<repository>} and {@code detail}, which names the commit and the outcome. Never a body or a credential.
     */
    Future<Void> audit(String actor, String repository, JsonObject detail);
}
