package io.flottila.core.flow;

import io.flottila.api.Ack;
import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.core.admission.Admission;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.MessageStore;
import io.flottila.core.record.Meter;
import io.flottila.core.record.Page;
import io.flottila.core.record.Recorder;
import io.flottila.core.record.RecordingPolicy;
import io.flottila.core.record.Snapshot;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.InMemoryBlobStore;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.constraints.IntRange;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * D-058 / D-061 as a property: an at-least-once delivery saves two things at once — the ingress body's persist in the body's
 * own store and the ingress snapshot's record — and whatever order they succeed, fail or throw in, whenever the save timeout
 * passes, the stop comes and its deadline passes, a message is {@link Ack.Accepted} exactly when both saves succeeded before
 * anything else decided it, and then its item is queued, its body persisted and its snapshot recorded. A failed or throwing
 * save is a retryable Nack naming it at once (it does not wait for the other); the timeout and the deadline are retryable
 * Nacks; a save that completes after the answer enqueues nothing; a message refused by the stop saves nothing. A delivery
 * holds its permit exactly while it is undecided. Over the fake clock and timer; every completion is synchronous.
 */
class DeliverySavePropertyTest {

    static final Duration SAVE_TIMEOUT = Duration.ofSeconds(1);

    /** A step of a script. */
    sealed interface Op permits Arrive, Persist, Record, Advance, Stop {}

    /** A new message; its persist (or its record) throws instead of returning a future when asked. */
    record Arrive(boolean persistThrows, boolean recordThrows) implements Op {}

    /** Answers the persist of message {@code which} (modulo the messages so far): {@code ok} = durable, else failed. */
    record Persist(int which, boolean ok) implements Op {}

    /** Answers the record of message {@code which}: {@code ok} = saved, else failed. */
    record Record(int which, boolean ok) implements Op {}

    /** The fake clock moves on. */
    record Advance(long millis) implements Op {}

    /** {@code stop(deadline)}. */
    record Stop() implements Op {}

    @Provide
    Arbitrary<List<Op>> scripts() {
        Arbitrary<Boolean> rarely = Arbitraries.frequency(Tuple.of(5, false), Tuple.of(1, true));
        Arbitrary<Boolean> mostly = Arbitraries.frequency(Tuple.of(3, true), Tuple.of(1, false));
        Arbitrary<Op> arrive = Combinators.combine(rarely, rarely).as((p, r) -> (Op) new Arrive(p, r));
        Arbitrary<Op> persist = Combinators.combine(Arbitraries.integers().between(0, 15), mostly).as((w, ok) -> (Op) new Persist(w, ok));
        Arbitrary<Op> record = Combinators.combine(Arbitraries.integers().between(0, 15), mostly).as((w, ok) -> (Op) new Record(w, ok));
        Arbitrary<Op> advance = Arbitraries.of(1L, 499L, 500L, 999L, 1000L).map(ms -> (Op) new Advance(ms));
        Arbitrary<Op> stop = Arbitraries.<Op>just(new Stop());
        return Arbitraries.frequencyOf(Tuple.of(3, arrive), Tuple.of(4, persist), Tuple.of(4, record), Tuple.of(2, advance), Tuple.of(1, stop))
                .list().ofMinSize(1).ofMaxSize(24);
    }

    @Provide
    Arbitrary<Duration> deadlines() {
        return Arbitraries.of(0L, 500L, 1500L).map(Duration::ofMillis);
    }

    @Property(tries = 300)
    void acceptedImpliesPersistedRecordedAndEnqueued(@ForAll @IntRange(min = 1, max = 3) int initial, @ForAll("deadlines") Duration deadline,
                                                     @ForAll("scripts") List<Op> script) {
        World w = new World(deadline);
        for (int i = 0; i < initial; i++) w.arrive(false, false);
        w.check();
        for (Op op : script) {
            w.trace.add(op.toString());
            switch (op) {
                case Arrive a -> w.arrive(a.persistThrows(), a.recordThrows());
                case Persist p -> w.persist(p.which(), p.ok());
                case Record r -> w.record(r.which(), r.ok());
                case Advance a -> w.advance(a.millis());
                case Stop s -> w.stop();
            }
            w.check();
        }
        w.finish();
    }

    enum Save { PENDING, OK, FAILED, THREW }

    /** Where a message stands in the model: undecided, accepted, a Nack for a failed save, a Nack for the timeout or the deadline, refused by the stop. */
    enum State { OPEN, ACCEPTED, FAILED, LATE, REFUSED }

    static final class Message {
        final Envelope in;
        final Instant arrived;
        Save persist = Save.PENDING;
        Save record = Save.PENDING;
        State state;
        Future<Ack> ack;
        final List<String> failures = new ArrayList<>();   // what a failed or throwing save of this message said

        Message(Envelope in, Instant arrived) {
            this.in = in;
            this.arrived = arrived;
        }

        @Override public String toString() { return in.id() + "=" + state + "(persist " + persist + ", record " + record + ")"; }
    }

    /** A message store whose ingress records the test answers one by one, or that throws for a message it was told to; reads answer nothing. */
    static final class Saves implements MessageStore {
        final Map<String, Promise<Void>> records = new ConcurrentHashMap<>();
        final Map<String, String> throwing = new ConcurrentHashMap<>();
        final Set<String> asked = ConcurrentHashMap.newKeySet();
        final Set<String> saved = ConcurrentHashMap.newKeySet();

        @Override public Future<Void> record(Snapshot snapshot) {
            String id = snapshot.messageId();
            asked.add(id);
            String thrown = throwing.get(id);
            if (thrown != null) throw new IllegalStateException(thrown);
            return records.computeIfAbsent(id, k -> Promise.promise()).future();
        }

        @Override public Future<List<Snapshot>> timeline(String messageId) { return Future.succeededFuture(List.of()); }

        @Override public Future<Page<String>> findByKey(String name, String value, Optional<String> after, int limit) {
            return Future.succeededFuture(new Page<>(List.of(), Optional.empty()));
        }

        void answer(String id, boolean ok) {
            Promise<Void> p = records.get(id);
            if (p == null) return;
            if (ok) {
                saved.add(id);
                p.tryComplete();
            } else {
                p.tryFail(new IllegalStateException("record failed for '" + id + "'"));
            }
        }
    }

    static final class World {
        final Duration deadline;
        final FakeRuntime rt = FakeRuntime.create();
        final Admission admission = new Admission(64, 1 << 20);
        final HoldingBlobStore blobs = new HoldingBlobStore(new InMemoryBlobStore(rt.hasher(), FakeRuntime.DEFAULT_TREE_LIMIT)).hold();
        final Saves store = new Saves();
        final InMemoryWorkQueue queue = new InMemoryWorkQueue(rt.clock());
        final Delivery delivery;
        final List<Message> messages = new ArrayList<>();
        final List<String> trace = new ArrayList<>();
        Future<Void> stopped;
        Instant stopAt;

        World(Duration deadline) {
            this.deadline = deadline;
            trace.add("deadline " + deadline);
            Recorder recorder = new Recorder(store, rt.clock(), Meter.system(), Map.of());
            delivery = Delivery.atLeastOnce(admission, "orders", e -> recorder.startSnapshot(e, Recorder.INGRESS, RecordingPolicy.DEFAULT),
                    store, queue, rt.timer(), SAVE_TIMEOUT);
        }

        String where() { return String.join(" → ", trace) + " | " + messages; }

        void arrive(boolean persistThrows, boolean recordThrows) {
            String id = rt.ids().next();
            Body body = blobs.write(BlobKey.body(id, 0), Buffer.buffer("x".repeat(messages.size() + 1)), blobs.treeLimit()).await();
            Message m = new Message(Envelope.ingress(id, "orders", Headers.empty(), body), rt.clock().instant());
            messages.add(m);
            trace.add("arrive " + id + (persistThrows ? " persist throws" : "") + (recordThrows ? " record throws" : ""));
            if (stopped != null) {
                m.ack = apply(m);
                m.state = State.REFUSED;
                return;
            }
            if (persistThrows) blobs.throwNext(new IllegalStateException("persist threw for '" + id + "'"));
            if (recordThrows) store.throwing.put(id, "record threw for '" + id + "'");
            m.ack = apply(m);
            m.state = State.OPEN;
            assertTrue(blobs.persists().contains(body.key()), "the body's persist is asked when the message is admitted: " + where());
            assertTrue(store.asked.contains(id), "and the snapshot's record, beside it: " + where());
            if (persistThrows) {
                m.persist = Save.THREW;
                m.failures.add("persist threw for '" + id + "'");
            }
            if (recordThrows) {
                m.record = Save.THREW;
                m.failures.add("record threw for '" + id + "'");
            }
            decide(m);
        }

        Future<Ack> apply(Message m) {
            try {
                return delivery.apply(m.in);
            } catch (RuntimeException escaped) {
                return fail("apply threw instead of answering: " + escaped + " at " + where(), escaped);
            }
        }

        void persist(int which, boolean ok) {
            Message m = pick(which);
            if (m == null || m.persist != Save.PENDING) return;
            trace.add((ok ? "persist " : "persist fails ") + m.in.id());
            blobs.answer(m.in.body().key(), ok);
            m.persist = ok ? Save.OK : Save.FAILED;
            if (!ok) m.failures.add("persist failed for '" + m.in.id() + "'");
            decide(m);
        }

        void record(int which, boolean ok) {
            Message m = pick(which);
            if (m == null || m.record != Save.PENDING) return;
            trace.add((ok ? "record " : "record fails ") + m.in.id());
            store.answer(m.in.id(), ok);
            m.record = ok ? Save.OK : Save.FAILED;
            if (!ok) m.failures.add("record failed for '" + m.in.id() + "'");
            decide(m);
        }

        /** A message whose saves were asked (a refused one has none), or null. */
        Message pick(int which) {
            if (messages.isEmpty()) return null;
            Message m = messages.get(which % messages.size());
            return m.state == State.REFUSED ? null : m;
        }

        /** An undecided message's saves decide it: a failure at once, both done = accepted; a late save changes nothing. */
        static void decide(Message m) {
            if (m.state != State.OPEN) return;
            if (m.persist == Save.FAILED || m.persist == Save.THREW || m.record == Save.FAILED || m.record == Save.THREW) m.state = State.FAILED;
            else if (m.persist == Save.OK && m.record == Save.OK) m.state = State.ACCEPTED;
        }

        void advance(long millis) {
            rt.clock().advance(Duration.ofMillis(millis));
            Instant now = rt.clock().instant();
            boolean cancelling = stopped != null && !now.isBefore(stopAt.plus(deadline));
            for (Message m : messages) {
                if (m.state != State.OPEN) continue;
                if (cancelling || !now.isBefore(m.arrived.plus(SAVE_TIMEOUT))) m.state = State.LATE;
            }
        }

        void stop() {
            if (stopped != null) return;
            stopped = delivery.stop(deadline);
            stopAt = rt.clock().instant();
            if (deadline.isZero()) for (Message m : messages) if (m.state == State.OPEN) m.state = State.LATE;
        }

        void check() {
            List<Message> open = messages.stream().filter(m -> m.state == State.OPEN).toList();
            assertEquals(open.size(), admission.inFlightRequests(), "a permit is held exactly while a delivery is undecided: " + where());
            for (Message m : messages) checkAnswer(m);
            Set<String> accepted = messages.stream().filter(m -> m.state == State.ACCEPTED).map(m -> m.in.id()).collect(Collectors.toSet());
            Set<String> queued = queue.snapshot().stream().map(WorkQueue.Item::messageId).collect(Collectors.toSet());
            assertEquals(accepted, queued, "exactly the accepted messages are queued — a save done after the answer enqueues nothing: " + where());
            assertEquals(accepted.size(), queue.size(), "each once: " + where());
            for (Message m : messages) {
                if (m.state == State.ACCEPTED) {
                    assertTrue(blobs.persisted(m.in.body().key()), "accepted, so its body is durable: " + m + " at " + where());
                    assertTrue(store.saved.contains(m.in.id()), "accepted, so its snapshot is recorded: " + m + " at " + where());
                }
                if (m.state == State.REFUSED) {
                    assertFalse(blobs.persists().contains(m.in.body().key()), "refused by the stop: no persist asked: " + where());
                    assertFalse(store.asked.contains(m.in.id()), "and no record: " + where());
                }
            }
        }

        void checkAnswer(Message m) {
            String at = m + " at " + where();
            switch (m.state) {
                case OPEN -> assertFalse(m.ack.isComplete(), "undecided: " + m.ack + " " + at);
                case ACCEPTED -> {
                    assertTrue(m.ack.isComplete(), "decided: " + at);
                    assertEquals(new Ack.Accepted(m.in.id()), m.ack.result(), at);
                }
                case FAILED -> {
                    Ack.Nack nack = nack(m, at);
                    assertTrue(m.failures.stream().anyMatch(nack.reason()::contains), "names the failed save " + m.failures + ": " + nack.reason() + " " + at);
                }
                case LATE -> {
                    Ack.Nack nack = nack(m, at);
                    assertTrue(nack.reason().contains("timed out") || nack.reason().contains("cancelled"), "the timeout or the deadline: " + nack.reason() + " " + at);
                }
                case REFUSED -> {
                    Ack.Nack nack = nack(m, at);
                    assertTrue(nack.reason().contains("stopping"), nack.reason() + " " + at);
                }
            }
        }

        static Ack.Nack nack(Message m, String at) {
            assertTrue(m.ack.isComplete(), "decided: " + at);
            assertTrue(m.ack.succeeded(), "an at-least-once answer is an Ack, never a failed future: " + m.ack.cause() + " " + at);
            Ack.Nack nack = assertInstanceOf(Ack.Nack.class, m.ack.result(), at);
            assertTrue(nack.retryable(), "the sender may try again: " + nack.reason() + " " + at);
            return nack;
        }

        /** Every save still pending completes now; then the stop, if none came; nothing is left in flight or on the timer. */
        void finish() {
            trace.add("finish");
            for (int i = 0; i < messages.size(); i++) {
                Message m = messages.get(i);
                if (m.state == State.REFUSED) continue;
                if (m.persist == Save.PENDING) persist(i, true);
                if (m.record == Save.PENDING) record(i, true);
            }
            check();
            stop();
            assertTrue(messages.stream().noneMatch(m -> m.state == State.OPEN), "every save answered: nothing undecided " + where());
            check();
            assertTrue(DeliveryTest.settled(stopped).succeeded(), "the stop completes once nothing is in flight: " + where());
            assertEquals(0, admission.inFlightRequests(), "no permit left: " + where());
            assertEquals(0, admission.inFlightBytes(), where());
            assertEquals(0, rt.timer().pending(), "no timer left: every save timeout went with its save, the deadline fired or was cancelled: " + where());
        }
    }
}
