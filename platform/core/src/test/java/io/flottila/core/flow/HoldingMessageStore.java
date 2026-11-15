package io.flottila.core.flow;

import io.flottila.core.record.MessageStore;
import io.flottila.core.record.Page;
import io.flottila.core.record.Snapshot;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@link MessageStore} decorator whose saves the test decides on: after {@link #hold()} every {@link #record} is kept
 * pending — nothing reaches the delegate — until {@link #release()} writes the held snapshots to the delegate and completes
 * their futures with the delegate's outcome, or {@link #fail(Throwable)} fails them and writes nothing. Not holding, it
 * passes every call straight through. {@link #throwNext} makes the next {@code record} throw instead of returning a future;
 * {@link #failReads} fails every read until {@link #readsOk()}; {@link #recorded} counts the {@code record} calls per step name.
 */
final class HoldingMessageStore implements MessageStore {

    private record Held(Snapshot snapshot, Promise<Void> done) {}

    private final MessageStore delegate;
    private final List<Held> held = new ArrayList<>();   // guarded by this
    private boolean holding;                             // guarded by this
    private RuntimeException throwNext;                  // guarded by this
    private volatile Throwable readFailure;
    private final Map<String, Integer> recorded = new HashMap<>();   // guarded by this

    HoldingMessageStore(MessageStore delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** From now on every save waits for {@link #release()} or {@link #fail(Throwable)}. */
    synchronized HoldingMessageStore hold() {
        holding = true;
        return this;
    }

    /** Saves waiting right now. */
    synchronized int held() { return held.size(); }

    /** Stops holding; the held snapshots go to the delegate, in order, and each future completes as the delegate's does. */
    void release() {
        for (Held h : drain()) delegate.record(h.snapshot()).onComplete(h.done());
    }

    /** Stops holding; every held save fails with {@code cause}, nothing is written. */
    void fail(Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        for (Held h : drain()) h.done().fail(cause);
    }

    private synchronized List<Held> drain() {
        holding = false;
        List<Held> out = List.copyOf(held);
        held.clear();
        return out;
    }

    /** The next {@code record} throws {@code failure} synchronously — no future — once; then saves behave as before. */
    synchronized HoldingMessageStore throwNext(RuntimeException failure) {
        throwNext = Objects.requireNonNull(failure, "failure");
        return this;
    }

    /** Every {@code timeline} and {@code findByKey} fails with {@code cause} until {@link #readsOk()}. */
    HoldingMessageStore failReads(Throwable cause) {
        readFailure = Objects.requireNonNull(cause, "cause");
        return this;
    }

    /** Reads go to the delegate again. */
    HoldingMessageStore readsOk() {
        readFailure = null;
        return this;
    }

    /** How many {@code record} calls carried a snapshot of {@code stepName}: held, thrown, failed or saved alike. */
    synchronized int recorded(String stepName) {
        return recorded.getOrDefault(stepName, 0);
    }

    @Override
    public Future<Void> record(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        synchronized (this) {
            recorded.merge(snapshot.stepName(), 1, Integer::sum);
            if (throwNext != null) {
                RuntimeException failure = throwNext;
                throwNext = null;
                throw failure;
            }
            if (holding) {
                Promise<Void> done = Promise.promise();
                held.add(new Held(snapshot, done));
                return done.future();
            }
        }
        return delegate.record(snapshot);
    }

    @Override
    public Future<List<Snapshot>> timeline(String messageId) {
        Throwable failure = readFailure;
        return failure != null ? Future.failedFuture(failure) : delegate.timeline(messageId);
    }

    @Override
    public Future<Page<String>> findByKey(String name, String value, Optional<String> after, int limit) {
        Throwable failure = readFailure;
        return failure != null ? Future.failedFuture(failure) : delegate.findByKey(name, value, after, limit);
    }
}
