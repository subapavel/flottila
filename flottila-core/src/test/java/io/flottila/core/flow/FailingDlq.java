package io.flottila.core.flow;

import io.flottila.core.dlq.Dlq;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.dlq.Parked;
import io.vertx.core.Future;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@link Dlq} decorator over an {@link InMemoryDlq} whose parks the test can refuse: after {@link #failParks(Throwable)}
 * every {@code park} fails with that cause and stores nothing, until {@link #parksOk()}. Everything else delegates.
 */
final class FailingDlq implements Dlq {

    private final InMemoryDlq delegate;
    private volatile Throwable parkFailure;

    FailingDlq(InMemoryDlq delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** From now on every park fails with {@code cause}. */
    FailingDlq failParks(Throwable cause) {
        parkFailure = Objects.requireNonNull(cause, "cause");
        return this;
    }

    /** Parks reach the delegate again. */
    FailingDlq parksOk() {
        parkFailure = null;
        return this;
    }

    @Override
    public Future<Void> park(Parked parked) {
        Throwable failure = parkFailure;
        return failure != null ? Future.failedFuture(failure) : delegate.park(parked);
    }

    @Override public Future<List<Parked>> list() { return delegate.list(); }

    @Override public Future<Optional<Parked>> get(String messageId) { return delegate.get(messageId); }

    @Override public Future<Void> remove(String messageId) { return delegate.remove(messageId); }
}
