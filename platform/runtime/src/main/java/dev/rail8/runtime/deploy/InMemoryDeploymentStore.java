package dev.rail8.runtime.deploy;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The {@link DeploymentStore} of a node without Postgres records (dev, tests): lost with the process. The {@code Deployments} that
 * share one such store are one pool: they share its records, its marks, its generation and its lock. It sees no work rows, so
 * {@link #endMarks} ends every mark.
 */
public final class InMemoryDeploymentStore implements DeploymentStore {

    /**
     * One audit row of a deploy, as {@link DeploymentStore#audit} was given it.
     *
     * @param actor who deployed
     * @param subject {@code repository/<repository>}
     * @param detail the commit and the outcome
     */
    public record Audited(String actor, String subject, JsonObject detail) {}

    /** By name: sorted as {@link String#compareTo} sorts. Guarded by {@code this}, with the generation and the lock's holder. */
    private final Map<String, DeploymentRecord> rows = new TreeMap<>();
    private final Set<String> marked = new TreeSet<>();
    private long generation;
    private Held holder;
    private final List<Audited> audited = new CopyOnWriteArrayList<>();

    @Override
    public synchronized Future<Snapshot> read() {
        return Future.succeededFuture(snapshot());
    }

    private Snapshot snapshot() {
        return new Snapshot(generation, new ArrayList<>(rows.values()), new ArrayList<>(marked));
    }

    @Override
    public synchronized Future<Optional<PoolLock>> lock() {
        if (holder != null) return Future.succeededFuture(Optional.empty());
        holder = new Held(snapshot());
        return Future.succeededFuture(Optional.of(holder));
    }

    @Override
    public synchronized Future<Void> endMarks() {
        marked.clear();
        return Future.succeededFuture();
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

    /** The lock as one holder holds it: its writes fail once it is closed. */
    private final class Held implements PoolLock {
        private final Snapshot pool;
        private long read;

        Held(Snapshot pool) {
            this.pool = pool;
            this.read = pool.generation();
        }

        @Override
        public Snapshot snapshot() {
            return pool;
        }

        @Override
        public Future<Long> save(DeploymentRecord record) {
            return write(record.name(), record);
        }

        @Override
        public Future<Long> delete(String name) {
            return write(name, null);
        }

        private Future<Long> write(String name, DeploymentRecord record) {
            synchronized (InMemoryDeploymentStore.this) {
                if (holder != this) return Future.failedFuture(new IllegalStateException("the pool's lock is no longer held"));
                if (generation != read) return Future.failedFuture(new IllegalStateException("the pool's generation moved from " + read + " to " + generation));
                DeploymentRecord replaced = record == null ? rows.remove(name) : rows.put(name, record);
                List<String> runs = record == null ? List.of() : record.atLeastOnce();
                if (replaced != null) replaced.atLeastOnce().stream().filter(flow -> !runs.contains(flow)).forEach(marked::add);
                runs.forEach(marked::remove);
                read = ++generation;
                return Future.succeededFuture(generation);
            }
        }

        @Override
        public Future<Void> close() {
            synchronized (InMemoryDeploymentStore.this) {
                if (holder == this) holder = null;
            }
            return Future.succeededFuture();
        }
    }
}
