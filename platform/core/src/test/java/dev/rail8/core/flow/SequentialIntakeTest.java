package dev.rail8.core.flow;

import dev.rail8.api.BlobKey;
import dev.rail8.api.Body;
import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.core.queue.InMemoryWorkQueue;
import dev.rail8.core.queue.WorkQueue;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.core.record.Meter;
import dev.rail8.core.record.Recorder;
import dev.rail8.core.record.RecordingPolicy;
import dev.rail8.core.record.Snapshot;
import dev.rail8.testkit.FakeRuntime;
import dev.rail8.testkit.InMemoryBlobStore;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-08a (D-082): {@link SequentialIntake} is today's at-least-once save moved out of {@link Delivery} as it is (D-058): the
 * ingress body's persist in its own store and the ingress snapshot's record are asked side by side; once both succeeded,
 * {@code abandoned} is asked — once, just before the enqueue — and false enqueues the item under its partition key; the save
 * succeeds once the item is queued. Abandoned, a failed or throwing persist, record or enqueue: the save's future fails and
 * nothing is queued; the first failure decides at once, without waiting for the other save.
 * The save answers the arrived envelope's own id. This intake keeps no body references, so every case passes
 * {@code keepInput} true.
 */
@Timeout(10)
class SequentialIntakeTest {

    static final Buffer ORDER = Buffer.buffer("<order id=\"1\"/>");

    final FakeRuntime rt = FakeRuntime.create();
    final HoldingBlobStore own = new HoldingBlobStore(new InMemoryBlobStore(rt.hasher(), FakeRuntime.DEFAULT_TREE_LIMIT));
    final HoldingMessageStore store = new HoldingMessageStore(new InMemoryMessageStore());
    final InMemoryWorkQueue items = new InMemoryWorkQueue(rt.clock());
    final Recorder recorder = new Recorder(store, rt.clock(), Meter.system(), Map.of());

    /** A stage-0 envelope of flow {@code orders}, its body in {@link #own} under {@code body(id, 0)}. */
    Envelope arrived() {
        String id = rt.ids().next();
        Body body = own.write(BlobKey.body(id, 0), ORDER, own.treeLimit()).await();
        return Envelope.ingress(id, "orders", Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), body);
    }

    Snapshot ingress(Envelope e) {
        return recorder.startSnapshot(e, Recorder.INGRESS, RecordingPolicy.DEFAULT);
    }

    /** The delivery's answer the save is given (CORE-4d: {@code abandoned} is that future; complete = decided, the save abandoned). */
    static Promise<Void> undecided() {
        return Promise.promise();
    }

    /** {@code f} once complete; still pending fails the case (every completion here is synchronous). */
    static <T> Future<T> done(Future<T> f) {
        assertTrue(f.isComplete(), "complete by now: " + f);
        return f;
    }

    @ParameterizedTest(name = "persist completes first: {0}")
    @ValueSource(booleans = {true, false})
    void enqueuesOnlyAfterPersistAndRecord(boolean persistFirst) {
        SequentialIntake intake = new SequentialIntake(store, items);
        Envelope in = arrived();
        own.hold();
        store.hold();
        Promise<Void> answer = undecided();

        Future<String> saved = intake.save(in, ingress(in), "orders", "K-7", true, answer.future());
        assertEquals(List.of(in.body().key()), own.persists(), "the body's persist is asked in its own store");
        assertEquals(1, own.held(), "and held");
        assertEquals(1, store.held(), "the ingress record is asked beside the persist, not after it");
        assertFalse(saved.isComplete());
        assertEquals(0, items.size(), "nothing queued while both saves are pending");

        if (persistFirst) own.release(); else store.release();
        assertFalse(saved.isComplete(), "one save done is not the save");
        assertEquals(0, items.size(), "nothing queued with one save still pending");

        if (persistFirst) store.release(); else own.release();
        assertTrue(done(saved).succeeded(), "saved: " + saved.cause());
        assertEquals(in.id(), saved.result(), "the save answers the arrived message's own id");
        List<WorkQueue.Item> queued = items.snapshot();
        assertEquals(1, queued.size(), "then the item is queued: " + queued);
        assertEquals(in.id(), queued.get(0).messageId());
        assertEquals("orders", queued.get(0).flow());
        assertEquals(1, queued.get(0).step(), "for step 1: the worker runs the pipeline after the ingress");
        assertEquals("K-7", queued.get(0).partitionKey(), "under the partition key it was given");
        assertTrue(own.persisted(in.body().key()), "the body is durable");
        assertEquals(List.of(Recorder.INGRESS), store.timeline(in.id()).await().stream().map(Snapshot::stepName).toList(), "the ingress is recorded");

        Envelope unkeyed = arrived();
        Future<String> unkeyedSave = done(intake.save(unkeyed, ingress(unkeyed), "orders", null, true, Promise.promise().future()));
        assertTrue(unkeyedSave.succeeded());
        assertEquals(unkeyed.id(), unkeyedSave.result());
        assertEquals(null, items.snapshot().get(1).partitionKey(), "no key: an unordered item");
    }

    /** How a save goes wrong. */
    enum Failure { ABANDONED, PERSIST_FAILS, RECORD_FAILS, PERSIST_THROWS, RECORD_THROWS, ENQUEUE_FAILS }

    /** {@link #items} behind a queue whose {@code enqueue} fails with {@code cause}; every other call reaches {@link #items}. */
    WorkQueue refusingEnqueue(RuntimeException cause, List<String> enqueued) {
        return (WorkQueue) Proxy.newProxyInstance(WorkQueue.class.getClassLoader(), new Class<?>[] {WorkQueue.class}, (proxy, method, args) -> {
            if (method.getName().equals("enqueue")) {
                enqueued.add((String) args[0]);
                return Future.failedFuture(cause);
            }
            try {
                return method.invoke(items, args);
            } catch (InvocationTargetException thrown) {
                throw thrown.getCause();
            }
        });
    }

    @ParameterizedTest
    @EnumSource(Failure.class)
    void anAbandonedOrFailedSaveEnqueuesNothing(Failure failure) {
        RuntimeException cause = new IllegalStateException("refused by the test: " + failure);
        List<String> enqueueCalls = new CopyOnWriteArrayList<>();
        SequentialIntake intake = new SequentialIntake(store, failure == Failure.ENQUEUE_FAILS ? refusingEnqueue(cause, enqueueCalls) : items);
        Envelope in = arrived();
        Promise<Void> answer = undecided();
        switch (failure) {
            case ABANDONED, PERSIST_FAILS, RECORD_FAILS -> {
                own.hold();
                store.hold();
            }
            case PERSIST_THROWS -> own.throwNext(cause);
            case RECORD_THROWS -> store.throwNext(cause);
            default -> { }
        }

        AtomicReference<Future<String>> returned = new AtomicReference<>();
        assertDoesNotThrow(() -> returned.set(intake.save(in, ingress(in), "orders", "K-7", true, answer.future())),
                "a store that throws is a failed save, not an exception escaping save");
        Future<String> saved = returned.get();
        if (failure == Failure.ABANDONED) {
            // the answer is decided while both saves are pending: once they are done, nothing may be queued
            answer.complete();
            assertFalse(saved.isComplete(), "fixture: the saves are still held");
            own.release();
            store.release();
        }
        if (failure == Failure.PERSIST_FAILS) {
            own.fail(cause);
            assertTrue(saved.isComplete(), "a failed persist decides at once, without waiting for the held record: " + saved);
        }
        if (failure == Failure.RECORD_FAILS) {
            store.fail(cause);
            assertTrue(saved.isComplete(), "a failed record decides at once, without waiting for the held persist: " + saved);
        }

        assertTrue(done(saved).failed(), "the save fails: " + failure);
        if (failure != Failure.ABANDONED) {
            assertTrue(String.valueOf(saved.cause()).contains("refused by the test: " + failure), "with the store's failure: " + saved.cause());
        }
        assertEquals(List.of(), items.snapshot(), "nothing queued");
        if (failure == Failure.ABANDONED) {
            assertTrue(String.valueOf(saved.cause()).contains("abandoned"), "failed by its abandonment: " + saved.cause());
            assertTrue(own.persisted(in.body().key()), "fixture: the body's persist completed before the answer was read");
        }
        if (failure == Failure.ENQUEUE_FAILS) assertEquals(List.of(in.id()), enqueueCalls, "the enqueue was asked, once");
    }
}
