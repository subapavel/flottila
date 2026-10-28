package io.flottila.runtime.deploy;

import io.vertx.core.Future;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

/** The {@link DeploymentStore} of a node without Postgres records (dev, tests): lost with the process. */
public final class InMemoryDeploymentStore implements DeploymentStore {

    /** By name: sorted as {@link String#compareTo} sorts. */
    private final Map<String, DeploymentRecord> rows = new ConcurrentSkipListMap<>();

    @Override
    public Future<Void> save(DeploymentRecord record) {
        rows.put(record.name(), record);
        return Future.succeededFuture();
    }

    @Override
    public Future<List<DeploymentRecord>> list() {
        return Future.succeededFuture(List.copyOf(rows.values()));
    }
}
