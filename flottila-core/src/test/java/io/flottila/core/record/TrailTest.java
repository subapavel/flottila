package io.flottila.core.record;

import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.BodyTooLargeException;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Keys;
import io.flottila.api.Pipeline;
import io.flottila.api.Props;
import io.flottila.api.Runtime;
import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.api.StepFactory;
import io.flottila.api.StepResources;
import io.flottila.core.flow.FlowBuilder;
import io.flottila.core.flow.FlowDefinition;
import io.flottila.core.flow.HoldingBlobStore;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.InMemoryBlobStore;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P1-09k2 (D-064, D-066, F-10): the run trail. A run's snapshots wait in its {@link Trail}; at the run's end each distinct
 * kept body key is persisted once, in the body's own store, then each snapshot is handed to its message store, keeping its
 * body key only where that key's persist succeeded — a row's body key always names a durable body. Keys the run started from
 * (its seed) and keys another path made durable (a park) are not persisted again. A failed persist (above the durable bound
 * or any other) leaves the row metadata-only, counted and logged at WARNING. The 257th buffered snapshot waits for a flush.
 * Without a trail bound the recorder persists, then records, and nothing waits.
 *
 * <p>Assumed API (new class, compile-red): {@code new Trail(Collection<BlobKey> durable)}, {@code static Optional<Trail>
 * current()}, {@code <T> T call(Callable<T>) throws Exception} (binds it like {@code MessageContext.call}),
 * {@code add(MessageStore, Snapshot, Body)}, {@code durable(BlobKey)}, {@code Future<Void> close()}, {@code long metadataOnly()}.
 */
@Timeout(30)
class TrailTest {

    static final long MAX = 4096;

    final FakeRuntime rt = FakeRuntime.create();
    final HoldingBlobStore bodies = new HoldingBlobStore(new InMemoryBlobStore(rt.hasher(), FakeRuntime.DEFAULT_TREE_LIMIT));
    final List<HoldingBlobStore> stores = new CopyOnWriteArrayList<>(List.of(bodies));
    final Watching store = new Watching(k -> stores.stream().anyMatch(s -> s.persisted(k)));
    final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    private static final Logger CORE = Logger.getLogger("io.flottila.core");   // a strong reference: JUL holds loggers weakly
    final Handler logs = new Handler() {
        @Override public void publish(LogRecord r) { logged.add(r); }
        @Override public void flush() { }
        @Override public void close() { }
    };

    { CORE.addHandler(logs); }

    final HeldRows heldRows = new HeldRows();

    @AfterEach
    void detach() {
        CORE.removeHandler(logs);
        stores.forEach(HoldingBlobStore::release);
        heldRows.releaseAll();
    }

    /** The WARNING records {@link Trail} logged, as "message + thrown". */
    List<String> trailWarnings() {
        return logged.stream().filter(r -> r.getLevel() == Level.WARNING && Trail.class.getName().equals(r.getLoggerName()))
                .map(r -> r.getMessage() + " " + r.getThrown()).toList();
    }

    /**
     * A message store whose {@code record} calls are each held until the test releases them, in call order, from its own
     * thread: the row write is handed over at once and answers only then.
     */
    static final class HeldRows implements MessageStore {
        final InMemoryMessageStore rows = new InMemoryMessageStore();
        private final List<Promise<Void>> held = new ArrayList<>();   // guarded by this
        private int released;                                          // guarded by this

        @Override public Future<Void> record(Snapshot s) {
            Promise<Void> answer = Promise.promise();
            synchronized (this) {
                held.add(answer);
            }
            return rows.record(s).compose(v -> answer.future());
        }

        /** Row writes handed over so far, answered or not. */
        synchronized int handed() { return held.size(); }

        /** Answers the oldest row write not answered yet (succeeded). */
        void releaseNext() {
            Promise<Void> next;
            synchronized (this) {
                next = held.get(released++);
            }
            next.complete();
        }

        void releaseAll() {
            List<Promise<Void>> rest;
            synchronized (this) {
                rest = List.copyOf(held.subList(released, held.size()));
                released = held.size();
            }
            rest.forEach(Promise::tryComplete);
        }

        @Override public Future<List<Snapshot>> timeline(String messageId) { return rows.timeline(messageId); }

        @Override public Future<Page<String>> findByKey(String name, String value, Optional<String> after, int limit) {
            return rows.findByKey(name, value, after, limit);
        }
    }

    /** One {@code record} call: the snapshot and whether its body key (if it kept one) was durable at that moment. */
    record Recorded(Snapshot snapshot, boolean keyDurableAtCall) {}

    /**
     * A message store over {@link InMemoryMessageStore} that notes, at every {@code record} call, whether the snapshot's body key
     * was durable at that moment: the order "persist completed, then the row" as the store saw it.
     */
    static final class Watching implements MessageStore {
        final InMemoryMessageStore rows = new InMemoryMessageStore();
        final List<Recorded> calls = new CopyOnWriteArrayList<>();
        private final Predicate<BlobKey> durable;

        Watching(Predicate<BlobKey> durable) { this.durable = durable; }

        @Override public Future<Void> record(Snapshot s) {
            calls.add(new Recorded(s, s.bodyKey().map(durable::test).orElse(true)));
            return rows.record(s);
        }

        @Override public Future<List<Snapshot>> timeline(String messageId) { return rows.timeline(messageId); }

        @Override public Future<Page<String>> findByKey(String name, String value, Optional<String> after, int limit) {
            return rows.findByKey(name, value, after, limit);
        }

        List<Snapshot> of(String messageId) { return calls.stream().map(Recorded::snapshot).filter(s -> s.messageId().equals(messageId)).toList(); }
    }

    static StepFactory factory(String type, java.util.function.BiFunction<StepConfig, StepResources, Stage> make) {
        return new StepFactory() {
            @Override public String type() { return type; }
            @Override public String configSchema() { return "{}"; }
            @Override public Stage create(StepConfig step, StepResources res) { return make.apply(step, res); }
        };
    }

    /** {@code rebody}: hands on {@code <name/>} written into the runtime's store under the envelope's next key; {@code mark}: headers only. */
    final List<StepFactory> factories = List.of(
            factory("rebody", (s, res) -> Stage.of(e -> e.next(res.blobs().write(e.nextKey(), Buffer.buffer("<" + s.name() + "/>"), MAX).await()))),
            factory("mark", (s, res) -> Stage.of(e -> e.withHeader("seen-" + s.name(), "yes"))));

    /** A sync flow {@code f} with these steps, recorded at the default level (every step, payload kept), over a runtime whose store is {@link #bodies}. */
    Pipeline pipeline(List<Map<String, Object>> steps) {
        Map<String, Object> doc = new HashMap<>(Map.of("schemaVersion", 1, "kind", "flow", "id", "f", "version", "1.0.0", "from", Map.of("endpoint", "in"),
                "delivery", Map.of("mode", "sync"), "limits", Map.of("maxBodyBytes", MAX), "steps", steps));
        Runtime r = rt.runtime();
        Runtime over = new Runtime(r.clock(), r.timer(), r.ids(), bodies, r.hasher(), r.components());
        return new FlowBuilder(over, factories, List.of(), Map.<String, EndpointConfig>of()).recording(store, () -> 0)
                .build(FlowDefinition.from(doc), Path.of(""));
    }

    static Map<String, Object> step(String type, String name) { return Map.of("type", type, "name", name); }

    /** A stage-0 envelope of flow {@code f}, its body {@code <in/>} in {@link #bodies}. */
    Envelope ingress() {
        String id = rt.ids().next();
        return Envelope.ingress(id, "f", Headers.empty(), bodies.write(BlobKey.body(id, 0), Buffer.buffer("<in/>"), MAX).await());
    }

    /** A body {@code bytes} of message {@code id} in {@code into}, under (id, step, body). */
    static Body body(HoldingBlobStore into, String id, int step, String bytes) {
        return into.write(BlobKey.body(id, step), Buffer.buffer(bytes), MAX).await();
    }

    /** An OK snapshot of message {@code id} at {@code step} showing {@code body}, its key kept. */
    static Snapshot kept(String id, int step, Body body) {
        return snapshot(id, step, body, Optional.of(body.key()));
    }

    static Snapshot snapshot(String id, int step, Body body, Optional<BlobKey> key) {
        return new Snapshot(id, "f", step, "s" + step, Snapshot.Outcome.OK, Optional.empty(), FakeRuntime.T0, 0, 0, Headers.empty(), Props.empty(),
                Keys.empty(), body.hash(), body.size(), key, Map.of());
    }

    HoldingBlobStore another() {
        HoldingBlobStore s = new HoldingBlobStore(new InMemoryBlobStore(rt.hasher(), FakeRuntime.DEFAULT_TREE_LIMIT));
        stores.add(s);
        return s;
    }

    static <T> T settled(Future<T> f) {
        try {
            return f.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return fail("not settled within 10 s, or failed: " + f, e);
        }
    }

    static void until(BooleanSupplier condition, Supplier<String> what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) fail("not within 10 s: " + what.get());
            Thread.onSpinWait();
        }
    }

    /** {@code t} ended, or parked waiting (never a transient state): where a thread that may wait for a flush has got to. */
    static boolean endedOrWaiting(Thread t) {
        Thread.State s = t.getState();
        return s == Thread.State.TERMINATED || s == Thread.State.WAITING || s == Thread.State.TIMED_WAITING;
    }

    static void joined(Thread t) {
        try {
            if (!t.join(Duration.ofSeconds(10))) fail(t + " did not end within 10 s; it sits at " + List.of(t.getStackTrace()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted");
        }
    }

    // --- the cases

    @Test
    void aRowReachesTheStoreOnlyAfterItsBodyPersisted() throws Exception {
        Pipeline p = pipeline(List.of(step("rebody", "a"), step("mark", "m"), step("rebody", "b")));
        Envelope in = ingress();
        Trail trail = new Trail(Set.of());
        bodies.hold();

        Envelope out = trail.call(() -> p.apply(in));   // no step waits for a persist: the run ends with every persist held
        assertEquals(3, out.step(), "fixture: the run went through");
        assertEquals(List.of(), store.calls, "the rows wait in the trail while the run runs");
        assertEquals(List.of(), bodies.persists(), "nothing is persisted before the run's end");

        Future<Void> closed = trail.close();
        Set<BlobKey> keys = Set.of(in.body().key(), BlobKey.body(in.id(), 1), BlobKey.body(in.id(), 3));
        until(() -> bodies.held() == 3, () -> "the trail's persists, asked for in parallel: " + bodies.persists());
        assertEquals(keys, Set.copyOf(bodies.persists()), "the run's distinct kept keys: ingress, step a's and step b's");
        assertEquals(List.of(), store.calls, "no row is handed to the store while its body's persist is pending");
        assertFalse(closed.isComplete(), "the close waits for its persists: " + closed);

        bodies.release();
        settled(closed);
        List<Snapshot> rows = store.of(in.id());
        assertEquals(List.of(0, 1, 2, 3), rows.stream().map(Snapshot::step).sorted().toList(), "every row reached the store once");
        assertTrue(store.calls.stream().allMatch(Recorded::keyDurableAtCall), "every row was handed over with its body already durable: " + store.calls);
        assertTrue(rows.stream().allMatch(s -> s.bodyKey().isPresent()), "and keeps its key: " + rows);
        assertEquals(Optional.of(BlobKey.body(in.id(), 1)), rows.stream().filter(s -> s.step() == 2).findFirst().orElseThrow().bodyKey(),
                "the header-only step's row refers to step a's body");
    }

    @Test
    void eachDistinctBodyKeyPersistsOncePerRun() {
        Body one = body(bodies, "m-x", 1, "<one/>");
        Body two = body(bodies, "m-x", 2, "<two/>");
        Trail trail = new Trail(Set.of());
        trail.add(store, kept("m-x", 1, one), one);
        trail.add(store, kept("m-x", 2, two), two);
        trail.add(store, kept("m-x", 3, two), two);
        trail.add(store, kept("m-x", 4, two), two);

        settled(trail.close());
        List<BlobKey> persists = new ArrayList<>(bodies.persists());
        persists.sort(java.util.Comparator.comparing(BlobKey::step));
        assertEquals(List.of(one.key(), two.key()), persists, "each distinct key once, however many rows show it");
        assertEquals(List.of(1, 2, 3, 4), store.of("m-x").stream().map(Snapshot::step).sorted().toList());
        assertTrue(store.calls.stream().allMatch(c -> c.snapshot().bodyKey().isPresent() && c.keyDurableAtCall()), store.calls.toString());
    }

    @Test
    void theSeedAndAParkedKeyAreNotPersistedAgain() {
        Body seed = body(bodies, "m-s", 0, "<seed/>");
        Body parked = body(bodies, "m-s", 1, "<parked/>");
        Body fresh = body(bodies, "m-s", 2, "<fresh/>");
        settled(bodies.persist(seed.key()));     // durable before the run: the ingress (D-058), a replay's input, a retry's parked body
        settled(bodies.persist(parked.key()));   // made durable during the run by a park (D-062)
        int before = bodies.persists().size();
        Trail trail = new Trail(Set.of(seed.key()));
        trail.add(store, kept("m-s", 1, seed), seed);
        trail.add(store, kept("m-s", 2, parked), parked);
        trail.durable(parked.key());   // as Parking does after its persist, after the recorder added the failed step's row
        trail.add(store, kept("m-s", 3, fresh), fresh);

        settled(trail.close());
        assertEquals(List.of(fresh.key()), bodies.persists().subList(before, bodies.persists().size()), "only the key nobody made durable yet");
        List<Snapshot> rows = store.of("m-s");
        assertEquals(3, rows.size());
        assertTrue(rows.stream().allMatch(s -> s.bodyKey().isPresent()), "the seed's and the parked key's rows keep their keys: they are durable: " + rows);
        assertEquals(0, trail.metadataOnly());
    }

    @Test
    void aFailedOrTooLargePersistRecordsTheRowMetadataOnlyAndCountsIt() {
        HoldingBlobStore failing = another();
        HoldingBlobStore bounded = another();
        HoldingBlobStore throwing = another();
        Body ok = body(bodies, "m-f", 1, "<ok/>");
        Body down = body(failing, "m-f", 2, "<down/>");
        Body large = body(bounded, "m-f", 3, "<large/>");
        Body thrown = body(throwing, "m-f", 4, "<thrown/>");
        failing.refuse(new IllegalStateException("pg down"));
        bounded.refuse(new BodyTooLargeException(large.key(), large.size(), large.size() - 1));
        throwing.throwNext(new IllegalStateException("persist threw"));
        Trail trail = new Trail(Set.of());
        for (Body b : List.of(ok, down, large, thrown)) trail.add(store, kept("m-f", b.key().step(), b), b);

        Future<Void> closed = trail.close();
        settled(closed);
        assertTrue(closed.succeeded(), "a close never fails: " + closed);
        assertEquals(List.of(down.key()), failing.persists(), "persisted in the body's own store");
        assertEquals(List.of(large.key()), bounded.persists());
        assertEquals(List.of(thrown.key()), throwing.persists());
        Map<Integer, Optional<BlobKey>> keyByStep = new HashMap<>();
        for (Snapshot s : store.of("m-f")) keyByStep.put(s.step(), s.bodyKey());
        assertEquals(Map.of(1, Optional.of(ok.key()), 2, Optional.empty(), 3, Optional.empty(), 4, Optional.empty()), keyByStep,
                "a row whose body could not be made durable is recorded metadata-only; the others keep their key");
        Snapshot tooLarge = store.of("m-f").stream().filter(s -> s.step() == 3).findFirst().orElseThrow();
        assertEquals(large.hash(), tooLarge.bodyHash(), "metadata-only keeps the hash");
        assertEquals(large.size(), tooLarge.bodySize(), "and the size");
        assertEquals(3, trail.metadataOnly(), "each row recorded metadata-only is counted");
        List<String> warned = logged.stream().filter(r -> r.getLevel() == Level.WARNING).map(r -> r.getMessage() + " " + r.getThrown())
                .filter(m -> m.contains("m-f")).toList();
        assertFalse(warned.isEmpty(), "a WARNING names the message whose rows were recorded metadata-only");
    }

    @Test
    void aMetadataOnlySnapshotPersistsNothing() {
        Body b = body(bodies, "m-m", 1, "<meta/>");
        Trail trail = new Trail(Set.of());
        trail.add(store, snapshot("m-m", 1, b, Optional.empty()), b);

        settled(trail.close());
        assertEquals(List.of(), bodies.persists(), "a row that keeps no key asks for no persist");
        assertEquals(List.of(Optional.<BlobKey>empty()), store.of("m-m").stream().map(Snapshot::bodyKey).toList(), "recorded as it was given");
        assertEquals(0, trail.metadataOnly(), "metadata-only by the flow's choice is not a failure to count");
    }

    @Test
    void aFailedPersistLogsExactlyOneWarningNamingTheMessageAndTheKey() {
        HoldingBlobStore failing = another().refuse(new IllegalStateException("pg down"));
        Body down = body(failing, "m-w", 2, "<down/>");
        Trail trail = new Trail(Set.of());
        trail.add(store, kept("m-w", 2, down), down);

        settled(trail.close());
        assertEquals(List.of(down.key()), failing.persists(), "fixture: the persist was asked for and refused");
        assertEquals(1, trail.metadataOnly(), "fixture: the row went metadata-only");
        List<String> warned = trailWarnings();
        assertEquals(1, warned.size(), "a persist that fails logs exactly one WARNING: " + warned);
        assertTrue(warned.getFirst().contains("m-w"), "the WARNING names the message id: " + warned);
        assertTrue(warned.getFirst().contains(down.key().toString()), "and the key: " + warned);
    }

    @Test
    void aSucceededPersistLogsNoWarning() {
        Body ok = body(bodies, "m-ok", 1, "<ok/>");
        HoldingBlobStore failing = another().refuse(new IllegalStateException("pg down"));
        Body down = body(failing, "m-down", 1, "<down/>");   // the witness: this case's capture does see a Trail WARNING
        Trail trail = new Trail(Set.of());
        trail.add(store, kept("m-ok", 1, ok), ok);
        trail.add(store, kept("m-down", 1, down), down);

        settled(trail.close());
        assertEquals(List.of(ok.key()), bodies.persists(), "fixture: the persist was asked for");
        assertTrue(bodies.persisted(ok.key()), "fixture: and it succeeded");
        assertEquals(List.of(Optional.of(ok.key())), store.of("m-ok").stream().map(Snapshot::bodyKey).toList(), "fixture: its row keeps the key");
        List<String> warned = trailWarnings();
        assertEquals(List.of(), warned.stream().filter(m -> m.contains("m-ok")).toList(), "a persist that succeeds logs no WARNING");
        assertEquals(1, warned.size(), "the capture sees the failed persist's WARNING, and only it: " + warned);
        assertTrue(warned.getFirst().contains("m-down"), warned.toString());
    }

    @Test
    void closeCompletesOnlyOnceEveryRowWriteAnswered() {
        Body b = body(bodies, "m-r", 1, "<r/>");
        Trail trail = new Trail(Set.of());
        trail.add(heldRows, kept("m-r", 1, b), b);
        trail.add(heldRows, kept("m-r", 2, b), b);

        Future<Void> closed = trail.close();
        until(() -> heldRows.handed() == 2, () -> "both row writes handed to the store: " + heldRows.handed());
        assertTrue(bodies.persisted(b.key()), "fixture: the persist settled before the rows were written");
        assertFalse(closed.isComplete(), "the close waits for the row writes it handed over: " + closed);

        heldRows.releaseNext();
        assertFalse(closed.isComplete(), "and for every one of them, not only the first: " + closed);

        heldRows.releaseNext();
        settled(closed);
        assertTrue(closed.succeeded(), "once the last row write answered, the close completes: " + closed);
        assertEquals(List.of(1, 2), heldRows.rows.timeline("m-r").await().stream().map(Snapshot::step).sorted().toList(), "both rows reached the store");
    }

    @Test
    void the257thSnapshotFlushesBeforeItsStepHandsOn() {
        Body b = body(bodies, "m-257", 1, "<b/>");
        Trail trail = new Trail(Set.of());
        bodies.hold();
        Thread first = Thread.ofVirtual().start(() -> { for (int step = 1; step <= 256; step++) trail.add(store, kept("m-257", step, b), b); });
        until(() -> endedOrWaiting(first), () -> "the first 256 adds: " + first.getState());
        assertEquals(Thread.State.TERMINATED, first.getState(), "256 buffered snapshots: no add waits");
        assertEquals(List.of(), bodies.persists(), "and nothing is flushed yet");
        assertEquals(List.of(), store.calls);

        Thread crossing = Thread.ofVirtual().start(() -> trail.add(store, kept("m-257", 257, b), b));
        until(() -> bodies.held() >= 1 && endedOrWaiting(crossing), () -> "the 257th add: persists " + bodies.persists() + ", " + crossing.getState());
        assertNotEquals(Thread.State.TERMINATED, crossing.getState(), "the step crossing the line waits for the flush");
        assertEquals(List.of(), store.calls, "no row before its body is durable");

        bodies.release();
        joined(crossing);
        assertTrue(store.calls.size() >= 256, "the flush handed the buffered rows to the store before the 257th add returned: " + store.calls.size());
        assertTrue(store.calls.stream().allMatch(Recorded::keyDurableAtCall));
        settled(trail.close());
        assertEquals(257, store.of("m-257").stream().map(Snapshot::step).distinct().count(), "every row reached the store");
        assertEquals(257, store.calls.size(), "each once");
    }

    @Test
    void withoutATrailTheRecorderPersistsThenRecords() throws Exception {
        Pipeline p = pipeline(List.of(step("rebody", "a")));
        Envelope bound = ingress();
        Trail trail = new Trail(Set.of());
        assertEquals(Optional.empty(), Trail.current(), "no trail outside a call");
        Optional<Trail> inside = trail.call(Trail::current);
        assertSame(trail, inside.orElseThrow(), "the trail is bound for the call");
        trail.call(() -> p.apply(bound));
        assertEquals(List.of(), store.calls, "fixture: bound, the rows wait in the trail");
        assertEquals(Optional.empty(), Trail.current(), "the binding ends with the call, on the same thread");

        bodies.hold();
        Envelope in = ingress();
        p.apply(in);   // no trail bound: the recorder persists each kept body, then records; nothing waits
        Set<BlobKey> keys = Set.of(in.body().key(), BlobKey.body(in.id(), 1));
        assertEquals(keys, Set.copyOf(bodies.persists()), "the persists were asked for at once, in the body's own store");
        assertEquals(List.of(), store.of(in.id()), "no row before its body's persist completed");

        bodies.release();
        until(() -> store.of(in.id()).size() == 2, () -> "the rows after the persists: " + store.calls);
        assertTrue(store.calls.stream().allMatch(Recorded::keyDurableAtCall), store.calls.toString());
        assertTrue(store.of(in.id()).stream().allMatch(s -> s.bodyKey().isPresent()));
        assertEquals(List.of(), store.of(bound.id()), "the bound run's rows still wait for its trail's close");
        settled(trail.close());
        assertEquals(2, store.of(bound.id()).size());
    }
}
