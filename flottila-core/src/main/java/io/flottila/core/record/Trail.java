package io.flottila.core.record;

import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.Envelope;
import io.flottila.api.Pipeline;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The snapshots of one run (D-064, D-066, F-10; ARCHITECTURE §8 write path): bound for the run like the
 * {@link io.flottila.core.scope.MessageContext}, the {@link Recorder} hands each snapshot to it with the body it shows. The
 * buffered snapshots are flushed — persists, then rows — when more than {@value #FLUSH_AT} are buffered and at
 * {@link #close()}, never otherwise. A step waits for a persist only in the first case: the step whose snapshot is the one
 * past {@value #FLUSH_AT} buffered ones waits in {@link #add} until that flush settled, and so does whatever waits on that
 * step (a sync reply behind it); every other step hands on without waiting. At each flush each distinct kept body key not
 * asked for yet is persisted once, in the body's own store, all of them in parallel; once every key the flushed snapshots
 * keep settled, each snapshot goes to its message store, keeping its key only if that key is durable: a row's body key
 * always names a durable body. A persist that fails or throws (above the store's durable bound, or any other cause) leaves
 * its rows metadata-only — counted by {@link #metadataOnly()} and logged at WARNING with the message id. Keys that are
 * durable already are not persisted again: the run's seed (what it started from: the ingress, D-058; a replay's input; a
 * retry's parked body, D-062) and those a park made durable during the run ({@link #durable}).
 *
 * @since 0.1
 */
public final class Trail {

    /** Snapshots a run buffers; the one past it flushes the trail and its step waits for that flush. */
    static final int FLUSH_AT = 256;

    private static final ScopedValue<Trail> CURRENT = ScopedValue.newInstance();
    private static final System.Logger LOG = System.getLogger(Trail.class.getName());
    private static final Future<Boolean> DURABLE = Future.succeededFuture(true);

    /** A snapshot waiting for its key: where it goes and the body it shows. */
    private record Entry(MessageStore store, Snapshot snapshot, Body body) {}

    /** A persist to start: the key and the body whose store makes it durable, and the promise its rows wait on. */
    private record Persist(BlobKey key, Body body, Promise<Boolean> durable) {}

    private final Map<BlobKey, Future<Boolean>> keys = new HashMap<>();   // guarded by this: each key's durability, true once durable
    private final List<Future<Void>> flushes = new ArrayList<>();         // guarded by this: every flush started, none ever failing
    private List<Entry> buffered = new ArrayList<>();                     // guarded by this
    private final AtomicLong metadataOnly = new AtomicLong();

    /** A trail whose run started from the {@code durable} keys: they are never persisted again, their rows keep them. */
    public Trail(Collection<BlobKey> durable) {
        for (BlobKey key : durable) keys.put(Objects.requireNonNull(key, "durable key"), DURABLE);
    }

    /** The trail bound on this thread, if any. */
    public static Optional<Trail> current() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }

    /** Runs {@code body} with this trail bound; the binding ends when the call returns. */
    public <T> T call(Callable<T> body) throws Exception {
        return ScopedValue.where(CURRENT, this).call(body::call);
    }

    /** {@code pipeline} applied to {@code e} with this trail bound; what it throws passes through as thrown. */
    public Envelope apply(Pipeline pipeline, Envelope e) {
        return ScopedValue.where(CURRENT, this).call(() -> pipeline.apply(e));
    }

    /**
     * {@code s}, showing {@code body}, for {@code store} at the trail's close; the snapshot past {@value #FLUSH_AT} buffered
     * ones flushes them all — persists, then rows — and returns once that flush settled.
     */
    public void add(MessageStore store, Snapshot s, Body body) {
        Entry entry = new Entry(Objects.requireNonNull(store, "store"), Objects.requireNonNull(s, "snapshot"), Objects.requireNonNull(body, "body"));
        boolean full;
        synchronized (this) {
            buffered.add(entry);
            full = buffered.size() > FLUSH_AT;
        }
        if (full) flush().await();
    }

    /** {@code key} was made durable during the run (a park's persist succeeded, D-062): it is not persisted again. */
    public void durable(BlobKey key) {
        Objects.requireNonNull(key, "key");
        synchronized (this) {
            keys.put(key, DURABLE);
        }
    }

    /**
     * Persists every kept key not yet asked for, then hands each buffered snapshot to its store as above; completes once every
     * flush of the trail settled — every persist and every row write, whatever their outcome. Never fails.
     */
    public Future<Void> close() {
        flush();
        List<Future<Void>> all;
        synchronized (this) {
            all = List.copyOf(flushes);
        }
        return Future.join(all).mapEmpty();   // no flush ever fails
    }

    /** Rows this trail recorded metadata-only because their key could not be made durable. */
    public long metadataOnly() { return metadataOnly.get(); }

    /**
     * The buffered snapshots: the keys they keep that nobody asked for yet persisted, in parallel; once every key they keep
     * has settled, each written. Settles when all are, never fails.
     */
    private Future<Void> flush() {
        List<Entry> rows;
        List<Future<Boolean>> waits = new ArrayList<>();
        List<Persist> persists = new ArrayList<>();
        synchronized (this) {
            rows = buffered;
            buffered = new ArrayList<>();
            for (Entry row : rows) {
                Optional<BlobKey> key = row.snapshot().bodyKey();
                if (key.isEmpty()) {
                    waits.add(DURABLE);   // metadata-only by the flow's choice: nothing to wait for, nothing to count
                    continue;
                }
                Future<Boolean> durable = keys.get(key.get());
                if (durable == null) {
                    Persist p = new Persist(key.get(), row.body(), Promise.promise());
                    persists.add(p);
                    durable = p.durable().future();
                    keys.put(p.key(), durable);
                }
                waits.add(durable);
            }
        }
        for (Persist p : persists) persist(p);   // outside the lock: a store may answer inline
        Future<Void> flushed = Future.join(waits).transform(persisted -> {   // every persist settled (none fails), then the rows
            List<Future<Void>> written = new ArrayList<>(rows.size());
            for (int i = 0; i < rows.size(); i++) written.add(write(rows.get(i), waits.get(i).result()));
            return Future.join(written);
        }).transform(settled -> Future.<Void>succeededFuture());
        synchronized (this) {
            flushes.add(flushed);
        }
        return flushed;
    }

    /** {@code row} to its store: with its key if {@code durable}, else metadata-only (counted). */
    private Future<Void> write(Entry row, boolean durable) {
        Snapshot s = durable ? row.snapshot() : withoutBody(row.snapshot());
        if (!durable) metadataOnly.incrementAndGet();
        return Future.future(done -> row.store().record(s).onComplete(done));   // a store that throws: the same failed write
    }

    /**
     * The key made durable in its body's own store; {@code p}'s promise says whether it is. A failure or a throw is logged,
     * after the promise completed: a logger that throws cannot hold the trail's rows back.
     */
    private static void persist(Persist p) {
        Future.<Void>future(done -> p.body().store().persist(p.key()).onComplete(done)).onComplete(persisted -> {
            p.durable().complete(persisted.succeeded());
            if (persisted.failed()) {
                LOG.log(System.Logger.Level.WARNING, "message " + p.key().messageId() + ": the body " + p.key() + " could not be made durable ("
                        + persisted.cause() + "); its rows are recorded metadata-only", persisted.cause());
            }
        });
    }

    private static Snapshot withoutBody(Snapshot s) {
        return new Snapshot(s.messageId(), s.flow(), s.step(), s.stepName(), s.outcome(), s.error(), s.startedAt(), s.wallNanos(), s.cpuNanos(),
                s.headers(), s.props(), s.keys(), s.bodyHash(), s.bodySize(), Optional.empty(), s.versions());
    }
}
