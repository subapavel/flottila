package dev.rail8.runtime.deploy;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** The {@link DeploymentStore} of a node without Postgres records (dev, tests): lost with the process. */
public final class InMemoryDeploymentStore implements DeploymentStore {

    /**
     * One audit row of a deploy, as {@link DeploymentStore#audit} was given it.
     *
     * @param actor who deployed
     * @param subject {@code repository/<repository>}
     * @param detail the commit and the outcome
     */
    public record Audited(String actor, String subject, JsonObject detail) {}

    /** By name: sorted as {@link String#compareTo} sorts. */
    private final Map<String, DeploymentRecord> rows = new ConcurrentSkipListMap<>();
    private final List<Audited> audited = new CopyOnWriteArrayList<>();

    @Override
    public Future<Void> save(DeploymentRecord record) {
        rows.put(record.name(), record);
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> delete(String name) {
        rows.remove(name);
        return Future.succeededFuture();
    }

    @Override
    public Future<List<DeploymentRecord>> list() {
        return Future.succeededFuture(List.copyOf(rows.values()));
    }

    @Override
    public Future<Void> audit(String actor, String repository, JsonObject detail) {
        audited.add(new Audited(actor, "repository/" + repository, detail.copy()));
        return Future.succeededFuture();
    }

    /** The audit rows written so far, oldest first. */
    public List<Audited> audited() {
        return List.copyOf(audited);
    }
}
