package io.flottila.core.flow;

import io.flottila.api.Ack;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.NackException;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * D-056 as a property: whatever the order of the answers (a reply or a failure, a save done or failed), new arrivals, the
 * stop and the clock reaching its deadline, every delivery's answer is decided once — by its own outcome before the deadline,
 * by the cancel (a retryable Nack) at it, by the stop's refusal after the stop — a late answer changes nothing and a late
 * at-least-once save enqueues nothing; a delivery holds its permit exactly while it is undecided (sizes differ per message,
 * so the bytes in flight say which permits are held); {@code stop()} completes exactly when nothing is in flight; at the end
 * no permit and no timer is left. One mode per try; the deliveries are the core's, over the fake clock and timer.
 */
class DeliveryStopPropertyTest {

    enum Mode { SYNC, AT_LEAST_ONCE, AT_MOST_ONCE }

    /** A step of a script. */
    sealed interface Op permits Answer, Arrive, Stop, Advance {}

    /** Answers message {@code which} (modulo the messages so far): {@code ok} = reply / save done, else a failure. */
    record Answer(int which, boolean ok) implements Op {}

    /** A new message arrives. */
    record Arrive() implements Op {}

    /** {@code stop(deadline)}; a second one must return the same future. */
    record Stop() implements Op {}

    /** The fake clock moves on. */
    record Advance(long millis) implements Op {}

    @Provide
    Arbitrary<List<Op>> scripts() {
        Arbitrary<Op> answer = Combinators.combine(Arbitraries.integers().between(0, 15), Arbitraries.of(true, false)).as((w, ok) -> (Op) new Answer(w, ok));
        Arbitrary<Op> arrive = Arbitraries.<Op>just(new Arrive());
        Arbitrary<Op> stop = Arbitraries.<Op>just(new Stop());
        Arbitrary<Op> advance = Arbitraries.of(1L, 499L, 500L, 999L, 1000L).map(ms -> (Op) new Advance(ms));
        return Arbitraries.frequencyOf(Tuple.of(5, answer), Tuple.of(2, arrive), Tuple.of(2, stop), Tuple.of(3, advance)).list().ofMinSize(1).ofMaxSize(16);
    }

    @Provide
    Arbitrary<Duration> deadlines() {
        return Arbitraries.of(0L, 500L, 1000L, 2000L).map(Duration::ofMillis);
    }

    @Property(tries = 150)
    void anyInterleavingOfAnswersStopAndDeadlineDecidesEachAnswerOnceAndReleasesEachPermitOnce(
            @ForAll Mode mode, @ForAll @IntRange(min = 1, max = 3) int initial, @ForAll("deadlines") Duration deadline, @ForAll("scripts") List<Op> script) {
        Logger deliveryLog = Logger.getLogger(Delivery.class.getName());   // at-most-once failures are logged by design: keep the run quiet
        Level was = deliveryLog.getLevel();
        deliveryLog.setLevel(Level.OFF);
        World w = new World(mode, deadline);
        try {
            for (int i = 0; i < initial; i++) w.arrive();
            w.check();
            for (Op op : script) {
                w.trace.add(op.toString());
                switch (op) {
                    case Answer a -> w.answer(a.which(), a.ok());
                    case Arrive a -> w.arrive();
                    case Stop s -> w.stop();
                    case Advance a -> w.advance(a.millis());
                }
                w.check();
            }
            w.finish();
        } finally {
            w.releaseAll();
            deliveryLog.setLevel(was);
        }
    }

    /** Where a message stands in the model. */
    enum State { OPEN, REPLIED, FAILED, CANCELLED, REFUSED }

    static final class Message {
        final Envelope in;
        State state;
        Future<Ack> ack;                          // at-least-once / at-most-once: what apply returned
        CompletableFuture<Future<Ack>> applied;   // sync: apply on its own thread
        Throwable failure;                        // what a FAILED sync run threw

        Message(Envelope in) { this.in = in; }

        @Override public String toString() { return in.id() + "=" + state; }
    }

    /** A message store whose ingress saves the test completes one by one; reads answer nothing. */
    static final class GatedStore implements MessageStore {
        final Map<String, Promise<Void>> saves = new ConcurrentHashMap<>();

        @Override public Future<Void> record(Snapshot snapshot) {
            return saves.computeIfAbsent(snapshot.messageId(), id -> Promise.promise()).future();
        }
        @Override public Future<List<Snapshot>> timeline(String messageId) { return Future.succeededFuture(List.of()); }
        @Override public Future<Page<String>> findByKey(String name, String value, Optional<String> after, int limit) { return Future.succeededFuture(new Page<>(List.of(), Optional.empty())); }

        void answer(String messageId, boolean ok) {
            Promise<Void> save = saves.get(messageId);
            if (save == null) return;
            if (ok) save.tryComplete();
            else save.tryFail(new IllegalStateException("save failed for " + messageId));
        }
    }

    static final class World {
        final Mode mode;
        final Duration deadline;
        final FakeRuntime rt = FakeRuntime.create();
        final Admission admission = new Admission(64, 1 << 20);
        final HeldPipeline held = HeldPipeline.awaiting();
        final GatedStore store = new GatedStore();
        final InMemoryWorkQueue queue = new InMemoryWorkQueue(rt.clock());
        final Delivery delivery;
        final List<Message> messages = new ArrayList<>();
        final List<String> trace = new ArrayList<>();
        Future<Void> stopped;
        Instant stopAt;
        boolean cancelled;

        World(Mode mode, Duration deadline) {
            this.mode = mode;
            this.deadline = deadline;
            trace.add(mode + " deadline " + deadline);
            Recorder recorder = new Recorder(store, rt.clock(), Meter.system(), Map.of());
            this.delivery = switch (mode) {
                case SYNC -> Delivery.sync(admission, held, rt.timer());
                case AT_MOST_ONCE -> Delivery.atMostOnce(admission, held, rt.timer());
                case AT_LEAST_ONCE -> Delivery.atLeastOnce(admission, "orders", e -> recorder.startSnapshot(e, Recorder.INGRESS, RecordingPolicy.DEFAULT),
                        store, queue, rt.timer(), Duration.ofHours(1));   // the save timeout stays out of every script's way
            };
        }

        String where() { return String.join(" → ", trace) + " | " + messages; }

        void arrive() {
            Envelope in = rt.ingress("orders", Headers.empty(), Buffer.buffer("x".repeat(messages.size() + 1)));   // a size of its own
            Message m = new Message(in);
            messages.add(m);
            if (mode == Mode.SYNC) {
                m.applied = DeliveryTest.applyOnVirtualThread(delivery, in);
                if (stopped != null) {
                    m.ack = joined(m);
                    m.state = State.REFUSED;
                    return;
                }
                held.run(in.id());
            } else {
                m.ack = delivery.apply(in);
                if (stopped != null) {
                    m.state = State.REFUSED;
                    return;
                }
                if (mode == Mode.AT_MOST_ONCE) held.run(in.id());
            }
            m.state = State.OPEN;
        }

        void answer(int which, boolean ok) {
            if (messages.isEmpty()) return;
            Message m = messages.get(which % messages.size());
            if (m.state == State.REFUSED) return;
            trace.add("answer " + m.in.id());
            boolean open = m.state == State.OPEN;
            if (mode == Mode.AT_LEAST_ONCE) {
                store.answer(m.in.id(), ok);
            } else {
                HeldPipeline.Run run = held.run(m.in.id());
                if (ok) {
                    run.reply("ok");
                } else {
                    NackException refused = new NackException("partner refused " + m.in.id(), false);
                    if (open) m.failure = refused;
                    run.fail(refused);
                }
            }
            if (!open) return;   // a late answer: the model says nothing changes
            m.state = ok ? State.REPLIED : State.FAILED;
            settle(m);
        }

        void stop() {
            if (stopped != null) {
                assertSame(stopped, delivery.stop(deadline), "a second stop returns the stop under way: " + where());
                return;
            }
            stopped = delivery.stop(deadline);
            stopAt = rt.clock().instant();
            if (deadline.isZero()) cancelOpen();
        }

        void advance(long millis) {
            rt.clock().advance(Duration.ofMillis(millis));
            if (stopped != null && !cancelled && !rt.clock().instant().isBefore(stopAt.plus(deadline))) cancelOpen();
        }

        void cancelOpen() {
            cancelled = true;
            for (Message m : messages) {
                if (m.state != State.OPEN) continue;
                m.state = State.CANCELLED;
                settle(m);
            }
        }

        /** Waits until the message's decision has played out on its own thread (the test thread decides at-least-once). */
        void settle(Message m) {
            switch (mode) {
                case SYNC -> m.ack = joined(m);
                case AT_MOST_ONCE -> held.run(m.in.id()).joined();
                case AT_LEAST_ONCE -> { }
            }
        }

        Future<Ack> joined(Message m) {
            try {
                return m.applied.get(HeldPipeline.BOUND_SECONDS, TimeUnit.SECONDS);
            } catch (ExecutionException escaped) {
                return fail("apply of " + m.in.id() + " threw: " + escaped.getCause() + " at " + where(), escaped.getCause());
            } catch (TimeoutException | InterruptedException stuck) {
                return fail("apply of " + m.in.id() + " did not return at " + where());
            }
        }

        void check() {
            List<Message> open = messages.stream().filter(m -> m.state == State.OPEN).toList();
            assertEquals(open.size(), admission.inFlightRequests(), "a permit is held exactly while a delivery is undecided: " + where());
            assertEquals(open.stream().mapToLong(m -> m.in.body().size()).sum(), admission.inFlightBytes(), "and it is that delivery's own: " + where());
            for (Message m : messages) checkAnswer(m);
            if (stopped != null) {
                if (open.isEmpty()) assertTrue(DeliveryTest.settled(stopped).succeeded(), "stopped once nothing is in flight: " + where());
                else assertFalse(stopped.isComplete(), "not stopped while " + open + " are in flight: " + where());
            }
            if (mode == Mode.AT_LEAST_ONCE) {
                Set<String> accepted = messages.stream().filter(m -> m.state == State.REPLIED).map(m -> m.in.id()).collect(Collectors.toSet());
                Set<String> queued = queue.snapshot().stream().map(WorkQueue.Item::messageId).collect(Collectors.toSet());
                assertEquals(accepted, queued, "exactly the accepted messages are queued — a late save enqueues nothing: " + where());
                assertEquals(accepted.size(), queue.size(), "each once: " + where());
            }
        }

        void checkAnswer(Message m) {
            String at = m.in.id() + " " + m.state + " at " + where();
            switch (m.state) {
                case OPEN -> {
                    if (mode == Mode.SYNC) assertFalse(m.applied.isDone(), "undecided: apply has not returned: " + at);
                    else if (mode == Mode.AT_LEAST_ONCE) assertFalse(m.ack.isComplete(), "undecided: " + at);
                    else assertEquals(new Ack.Accepted(m.in.id()), m.ack.result(), at);
                }
                case REPLIED -> {
                    switch (mode) {
                        case SYNC -> assertEquals("ok", assertInstanceOf(Ack.Reply.class, m.ack.result(), at).reply().header(HeldPipeline.H_ANSWER).orElse(null), at);
                        case AT_LEAST_ONCE, AT_MOST_ONCE -> assertEquals(new Ack.Accepted(m.in.id()), m.ack.result(), at);
                    }
                }
                case FAILED -> {
                    switch (mode) {
                        case SYNC -> {
                            assertTrue(m.ack.failed(), "the step's failure before the deadline is the answer: " + at);
                            assertSame(m.failure, m.ack.cause(), at);
                        }
                        case AT_LEAST_ONCE -> {
                            Ack.Nack nack = assertInstanceOf(Ack.Nack.class, m.ack.result(), at);
                            assertTrue(nack.retryable() && nack.reason().contains("save failed for " + m.in.id()), nack.reason() + " " + at);
                        }
                        case AT_MOST_ONCE -> assertEquals(new Ack.Accepted(m.in.id()), m.ack.result(), at);
                    }
                }
                case CANCELLED -> {
                    if (mode == Mode.AT_MOST_ONCE) {
                        assertEquals(new Ack.Accepted(m.in.id()), m.ack.result(), "accepted before it ran, whatever came after: " + at);
                        assertTrue(held.run(m.in.id()).interrupted, "the run was interrupted: " + at);
                    } else {
                        Ack.Nack nack = assertInstanceOf(Ack.Nack.class, m.ack.result(), at);
                        assertTrue(nack.retryable() && nack.reason().contains("cancelled"), nack.reason() + " " + at);
                    }
                }
                case REFUSED -> {
                    assertTrue(m.ack.isComplete(), "refused at once: " + at);
                    Ack.Nack nack = assertInstanceOf(Ack.Nack.class, m.ack.result(), at);
                    assertTrue(nack.retryable() && nack.reason().contains("stopping"), nack.reason() + " " + at);
                }
            }
        }

        void finish() {
            trace.add("finish");
            if (stopped == null) stop();
            if (!cancelled && messages.stream().anyMatch(m -> m.state == State.OPEN)) {
                rt.clock().advance(Duration.between(rt.clock().instant(), stopAt.plus(deadline)));
                cancelOpen();
            }
            check();
            for (Message m : messages) {
                if (m.state == State.REFUSED) continue;
                if (mode == Mode.AT_LEAST_ONCE) store.answer(m.in.id(), true);   // every late save completes now
                else held.run(m.in.id()).reply("late");
            }
            check();
            assertEquals(0, admission.inFlightRequests(), "no permit left: " + where());
            assertEquals(0, admission.inFlightBytes(), where());
            assertTrue(stopped.isComplete() && stopped.succeeded(), where());
            assertEquals(0, rt.timer().pending(), "no timer left: the deadline fired or was cancelled, every save timeout went with its save: " + where());
        }

        /** Lets every parked run and held save go, whatever happened (a failed try leaves no thread parked). */
        void releaseAll() {
            for (Message m : messages) {
                store.answer(m.in.id(), false);
                HeldPipeline.Run run = held.runIfHeld(m.in.id());
                if (run != null) run.reply("released");
            }
        }
    }
}
