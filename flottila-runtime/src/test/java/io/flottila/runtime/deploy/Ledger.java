package io.flottila.runtime.deploy;

import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/**
 * A {@link DeploymentStore} at the SPI boundary of {@code Deployments} (P1-09c3a test fixture): an {@link InMemoryDeploymentStore}
 * that records every save call, traces it ({@code "save <name> <version>"} at the call, on the calling thread), and can be told to
 * fail or to hold the next save. A failed save writes nothing; a held one writes once it is released.
 */
final class Ledger implements DeploymentStore {

    private final InMemoryDeploymentStore rows = new InMemoryDeploymentStore();
    private final List<DeploymentRecord> saves = new CopyOnWriteArrayList<>();
    private final List<Hold> holds = new CopyOnWriteArrayList<>();
    private final Consumer<String> trace;
    private volatile String failNext;
    private volatile Hold holdNext;

    Ledger(Consumer<String> trace) {
        this.trace = Objects.requireNonNull(trace, "trace");
    }

    /** A save held at its call until {@link #release} or {@link #fail}; {@link #reached} counts down at the call. */
    static final class Hold {
        private final Promise<Void> gate = Promise.promise();
        private final CountDownLatch reached = new CountDownLatch(1);

        CountDownLatch reached() { return reached; }

        void release() { gate.tryComplete(); }

        void fail(String message) { gate.tryFail(new IllegalStateException(message)); }
    }

    /** The next save fails with an {@link IllegalStateException} carrying {@code message}, and writes nothing. */
    void failNextSave(String message) {
        failNext = Objects.requireNonNull(message, "message");
    }

    /** The next save waits for the hold; released, it writes and succeeds. */
    Hold holdNextSave() {
        Hold h = new Hold();
        holds.add(h);
        holdNext = h;
        return h;
    }

    /** Every save call, in order, failed or held ones included. */
    List<DeploymentRecord> saves() {
        return List.copyOf(saves);
    }

    /** What the store holds now, as {@link #list()} answers it, awaited. */
    List<DeploymentRecord> rows() {
        return rows.list().await();
    }

    /** Releases every hold: nothing a case held outlives it. */
    void releaseAll() {
        holds.forEach(Hold::release);
    }

    @Override
    public Future<Void> save(DeploymentRecord record) {
        saves.add(record);
        trace.accept("save " + record.name() + " " + record.version());
        String failure = failNext;
        failNext = null;
        if (failure != null) return Future.failedFuture(new IllegalStateException(failure));
        Hold held = holdNext;
        holdNext = null;
        if (held == null) return rows.save(record);
        held.reached.countDown();
        return held.gate.future().compose(v -> rows.save(record));
    }

    @Override
    public Future<List<DeploymentRecord>> list() {
        return rows.list();
    }
}
