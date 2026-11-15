package io.flottila.core.batch;

import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Records batches; in manual mode every write stays open until the test completes its gate (ported from proto-m1). */
final class FakeSink implements BatchSink<String> {

    final List<List<String>> batches = new CopyOnWriteArrayList<>();
    final Deque<Promise<Void>> gates = new ArrayDeque<>();
    volatile boolean manual;

    @Override public Future<Void> write(List<String> batch) {
        batches.add(List.copyOf(batch));
        if (!manual) return Future.succeededFuture();
        Promise<Void> p = Promise.promise();
        synchronized (gates) { gates.add(p); }
        return p.future();
    }

    /** The oldest write still open. */
    Promise<Void> gate() {
        Promise<Void> g;
        synchronized (gates) { g = gates.poll(); }
        assertNotNull(g, "expected a write in flight");
        return g;
    }

    int open() { synchronized (gates) { return gates.size(); } }
}
