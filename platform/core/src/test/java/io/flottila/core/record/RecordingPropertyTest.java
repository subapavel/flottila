package io.flottila.core.record;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobStore;
import io.flottila.api.Body;
import io.flottila.api.BodyTooLargeException;
import io.flottila.api.Hasher;
import io.flottila.api.Headers;
import io.flottila.api.Keys;
import io.flottila.api.Props;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.InMemoryBlobStore;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P1-09k2 (D-064, D-066): whatever a run's snapshots are — rows sharing keys, metadata-only rows, keys the run started from —
 * and however its persists answer — in any order, succeeding, failing, above the bound, throwing — a row reaches the message
 * store with a body key only once that key is durable, every row reaches it once, a row keeps its key exactly when its key was
 * the seed or its persist succeeded, and each kept key that was not the seed is persisted once.
 */
class RecordingPropertyTest {

    /** How a key's persist answers. */
    enum Outcome { OK, FAILS, TOO_LARGE, THROWS }

    /** One snapshot of the run: its step and the key it shows (index into the run's keys), or none for a metadata-only row. */
    record Row(int key, boolean keepsKey) {}

    /** A run: its distinct keys' outcomes, which keys are its seed, its rows in order, the order the persists are answered in. */
    record Run(List<Outcome> outcomes, Set<Integer> seed, List<Row> rows, long answerOrder) {}

    @Provide
    Arbitrary<Run> runs() {
        Arbitrary<List<Outcome>> outcomes = Arbitraries.frequency(
                net.jqwik.api.Tuple.of(5, Outcome.OK), net.jqwik.api.Tuple.of(1, Outcome.FAILS),
                net.jqwik.api.Tuple.of(1, Outcome.TOO_LARGE), net.jqwik.api.Tuple.of(1, Outcome.THROWS)).list().ofMinSize(1).ofMaxSize(6);
        return outcomes.flatMap(o -> {
            int n = o.size();
            Arbitrary<Set<Integer>> seed = Arbitraries.integers().between(0, n - 1).set().ofMaxSize(Math.min(2, n));
            Arbitrary<Row> row = Combinators.combine(Arbitraries.integers().between(0, n - 1), Arbitraries.frequency(
                    net.jqwik.api.Tuple.of(4, true), net.jqwik.api.Tuple.of(1, false))).as(Row::new);
            return Combinators.combine(seed, row.list().ofMaxSize(60), Arbitraries.longs()).as((s, r, order) -> new Run(o, s, r, order));
        });
    }

    @Property(tries = 300)
    void everyKeptBodyKeyWasPersistedBeforeItsRow(@ForAll("runs") Run run) {
        Deferred blobs = new Deferred(run.outcomes);
        List<Body> bodies = new ArrayList<>();
        for (int k = 0; k < run.outcomes.size(); k++) bodies.add(blobs.write(new BlobKey("m-p", k + 1, "body"), Buffer.buffer("<k" + k + "/>"), 4096).await());
        Set<BlobKey> seed = new HashSet<>();
        for (int k : run.seed) {
            seed.add(bodies.get(k).key());
            blobs.durable.add(bodies.get(k).key());   // the seed is durable before the run
        }
        Watching store = new Watching(blobs);
        Trail trail = new Trail(seed);
        for (int i = 0; i < run.rows.size(); i++) {
            Row r = run.rows.get(i);
            Body b = bodies.get(r.key);
            trail.add(store, new Snapshot("m-p", "f", i + 1, "s" + (i + 1), Snapshot.Outcome.OK, Optional.empty(), FakeRuntime.T0, 0, 0,
                    Headers.empty(), Props.empty(), Keys.empty(), b.hash(), b.size(), r.keepsKey ? Optional.of(b.key()) : Optional.empty(), Map.of()), b);
        }

        Future<Void> closed = trail.close();
        Random order = new Random(run.answerOrder);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!closed.isComplete()) {   // answer the persists as they are asked for, in the run's order
            if (!blobs.answerOne(order)) Thread.onSpinWait();
            if (System.nanoTime() - deadline > 0) fail("the close did not complete within 10 s; pending persists " + blobs.pending.size() + ": " + run);
        }
        assertTrue(closed.succeeded(), "a close never fails: " + closed.cause());

        assertEquals(List.of(), store.violations, "a row was handed over before its key was durable: " + run);
        assertEquals(run.rows.size(), store.recorded.size(), "every row reached the store once: " + run);
        for (Snapshot s : store.recorded) {
            Row r = run.rows.get(s.step() - 1);
            BlobKey key = bodies.get(r.key).key();
            boolean durable = run.seed.contains(r.key) || run.outcomes.get(r.key) == Outcome.OK;
            Optional<BlobKey> expected = r.keepsKey && durable ? Optional.of(key) : Optional.empty();
            assertEquals(expected, s.bodyKey(), "row " + s.step() + " (key " + r.key + ", " + run.outcomes.get(r.key) + (run.seed.contains(r.key) ? ", seed" : "") + "): " + run);
        }
        Map<BlobKey, Integer> asked = new HashMap<>();
        for (BlobKey k : blobs.calls) asked.merge(k, 1, Integer::sum);
        Map<BlobKey, Integer> expected = new HashMap<>();
        for (Row r : run.rows) if (r.keepsKey && !run.seed.contains(r.key)) expected.put(bodies.get(r.key).key(), 1);
        assertEquals(expected, asked, "each kept key that is not the seed is persisted once, nothing else: " + run);
    }

    /** A message store that only collects, noting every row whose body key was not durable at its {@code record} call. */
    static final class Watching implements MessageStore {
        final List<Snapshot> recorded = new CopyOnWriteArrayList<>();
        final List<String> violations = new CopyOnWriteArrayList<>();
        private final Deferred blobs;

        Watching(Deferred blobs) { this.blobs = blobs; }

        @Override public Future<Void> record(Snapshot s) {
            s.bodyKey().filter(k -> !blobs.durable.contains(k)).ifPresent(k -> violations.add("row " + s.step() + " with " + k));
            recorded.add(s);
            return Future.succeededFuture();
        }

        @Override public Future<List<Snapshot>> timeline(String messageId) { return Future.succeededFuture(List.copyOf(recorded)); }

        @Override public Future<Page<String>> findByKey(String name, String value, Optional<String> after, int limit) {
            return Future.succeededFuture(new Page<>(List.of(), Optional.empty()));
        }
    }

    /** A blob store whose persists stay pending until the property answers them, each with its key's outcome; {@code THROWS} throws at the call. */
    static final class Deferred implements BlobStore {
        final InMemoryBlobStore inner = new InMemoryBlobStore(Hasher.sha256(), FakeRuntime.DEFAULT_TREE_LIMIT);
        final Set<BlobKey> durable = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final List<BlobKey> calls = new CopyOnWriteArrayList<>();
        final List<Map.Entry<BlobKey, Promise<Void>>> pending = new ArrayList<>();   // guarded by this
        private final List<Outcome> outcomes;

        Deferred(List<Outcome> outcomes) { this.outcomes = outcomes; }

        private Outcome outcome(BlobKey key) { return outcomes.get(key.step() - 1); }

        /** Answers one pending persist, picked by {@code order}; false when none is pending. */
        boolean answerOne(Random order) {
            Map.Entry<BlobKey, Promise<Void>> p;
            synchronized (this) {
                if (pending.isEmpty()) return false;
                p = pending.remove(order.nextInt(pending.size()));
            }
            BlobKey key = p.getKey();
            switch (outcome(key)) {
                case OK -> { durable.add(key); p.getValue().complete(); }
                case TOO_LARGE -> p.getValue().fail(new BodyTooLargeException(key, 5000, 4096));
                default -> p.getValue().fail(new IllegalStateException("pg down for " + key));
            }
            return true;
        }

        @Override public long treeLimit() { return inner.treeLimit(); }

        @Override public Future<Body> write(BlobKey key, Buffer whole, long maxSize) {
            return inner.write(key, whole, maxSize).map(b -> Body.of(this, b.key(), b.hash(), b.size()));
        }

        @Override public Future<Body> write(BlobKey key, ReadStream<Buffer> in, long maxSize) {
            return inner.write(key, in, maxSize).map(b -> Body.of(this, b.key(), b.hash(), b.size()));
        }

        @Override public ReadStream<Buffer> open(BlobKey key) { return inner.open(key); }

        @Override public Future<Buffer> read(BlobKey key) { return inner.read(key); }

        @Override public Future<Void> persist(BlobKey key) {
            calls.add(key);
            if (outcome(key) == Outcome.THROWS) throw new IllegalStateException("persist threw for " + key);
            Promise<Void> p = Promise.promise();
            synchronized (this) {
                pending.add(Map.entry(key, p));
            }
            return p.future();
        }
    }
}
