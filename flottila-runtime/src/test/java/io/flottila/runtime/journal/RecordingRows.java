package io.flottila.runtime.journal;

import io.vertx.core.Future;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The test double of {@link SegmentRows} (CORE-4b; the Postgres implementation is CORE-4c's): records every insert with the
 * thread that made it, runs a case's hook inside the insert of a given segment (before it answers, so the hook sees the
 * world as the journal left it at the insert), and fails the next insert of a given segment once. Keyed by segment number,
 * so an insert for any other segment (a writer's pre-open, should it ever insert one) is recorded and answered, never
 * mistaken for the case's.
 */
final class RecordingRows implements SegmentRows {

    /** One insert: what it named and the thread that called it. */
    record Insert(String node, long segment, Thread thread) { }

    private final List<Insert> inserts = new CopyOnWriteArrayList<>();
    private final Map<Long, Consumer<Insert>> hooks = new ConcurrentHashMap<>();
    private final Map<Long, Throwable> failures = new ConcurrentHashMap<>();
    private final Set<Long> answered = ConcurrentHashMap.newKeySet();

    @Override public Future<Void> insert(String node, long segment) {
        Insert i = new Insert(node, segment, Thread.currentThread());
        inserts.add(i);
        Consumer<Insert> hook = hooks.remove(segment);
        if (hook != null) hook.accept(i);
        Throwable f = failures.remove(segment);
        if (f != null) return Future.failedFuture(f);
        answered.add(segment);
        return Future.succeededFuture();
    }

    /** True once an insert of {@code segment} answered success (CORE-4c1: no segment file before its row is in). */
    boolean inserted(long segment) { return answered.contains(segment); }

    /** Runs {@code hook} inside the next insert of {@code segment}, before it answers. */
    void onInsert(long segment, Consumer<Insert> hook) { hooks.put(segment, hook); }

    /** The next insert of {@code segment} answers a future failed with {@code cause}; the one after succeeds again. */
    void failNext(long segment, Throwable cause) { failures.put(segment, cause); }

    List<Insert> inserts() { return List.copyOf(inserts); }

    /** The inserts of {@code segment}, in call order. */
    List<Insert> insertsOf(long segment) { return inserts.stream().filter(i -> i.segment() == segment).toList(); }
}
