package io.flottila.testkit;

import io.flottila.api.Ack;
import io.flottila.api.Body;
import io.flottila.api.Component;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Health;
import io.flottila.api.Key;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The cases every polling consumer passes, real or mock (D-076): a component that fetches files from a place the partner
 * drops them — sftp, and the mock standing in for it — rather than being sent to. A component's test extends this class and
 * supplies the component, an endpoint that selects it, a way to drop a file where the consumer polls, the names still there,
 * and a way to fire the consumer's next round through the fake timer.
 *
 * <p>The rules every case pins: a round takes the files oldest first, one at a time; each delivery calls the handler on a
 * virtual thread of its own (D-057) and the round waits for the answer. An {@link Ack.Accepted} or an {@link Ack.Reply}
 * removes the file — only then, the durable save being the handler's (ARCHITECTURE §4). A retryable {@link Ack.Nack}, a
 * failed answer, a handler that throws or answers null keep the file and end the round: the next round delivers it again.
 * A final Nack keeps the file (this endpoint names no error directory) but never delivers it again to the same consumer.
 * {@link Consumer#stop()} (D-056) takes no new file, waits for the answer in flight and for its removal; a stopped consumer
 * delivers nothing. Health is up only while a consumer runs. A dedupe prop the component names is set on every delivery and
 * stable across redeliveries of the same file (D-084).
 *
 * @since 0.1
 */
@Timeout(60)
public abstract class PollingConsumerContract {

    /** The component under test; each case calls it once and runs its consumers on the instance it gets. */
    protected abstract Component component();

    /**
     * An endpoint whose URI scheme selects {@link #component()} and whose options bind a polling consumer to the place
     * {@link #drop} writes to, with every option at its default but the required ones, and no error directory.
     */
    protected abstract EndpointConfig endpoint();

    /**
     * Places a file named {@code name} holding {@code bytes} where the consumer of {@link #endpoint()} polls, whole (never
     * seen half-written), younger than every file dropped before it and old enough for any minimum age. Returns once it is
     * there. {@link #bytesOf} reads the stream for a fixture that needs the bytes at once.
     */
    protected abstract void drop(String name, ReadStream<Buffer> bytes);

    /** The names of the files still where the consumer polls. */
    protected abstract Set<String> remaining();

    /**
     * Fires the consumer's next round through the fake timer: waits — bounded by {@link #patience()} — until the round is
     * scheduled, the previous one being over, then moves the fake clock to it ({@link #advanceToNextRound}). Returns once
     * the round is fired, not once it is over: the timer only fires a round, a round runs on a virtual thread of the
     * consumer's own. The rounds are the consumer's only use of the timer.
     */
    protected abstract void tick();

    /** How long a case waits for something asynchronous before it fails (a real transport needs a moment). */
    protected Duration patience() { return Duration.ofSeconds(10); }

    // ---- helpers for the runners -------------------------------------------------------------------------------------

    /** Spins (yielding, never sleeping) until {@code condition} holds or {@link #patience()} is spent. */
    protected final boolean soon(BooleanSupplier condition) {
        long deadline = System.nanoTime() + patience().toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) return false;
            Thread.onSpinWait();
            Thread.yield();
        }
        return true;
    }

    /**
     * What a runner's {@link #tick()} does over the testkit fakes: waits until {@code timer} has a task pending — the
     * consumer's next round, scheduled once the previous one ended — then advances {@code clock} to it.
     */
    protected final void advanceToNextRound(FakeClock clock, FakeTimer timer) {
        assertTrue(soon(() -> timer.pending() > 0),
                "no round is scheduled after " + patience() + ": the previous round has not ended, or the consumer stopped scheduling rounds");
        clock.advance(timer.nextDelay());
    }

    /** The whole of {@code in}, read at once (bounded by {@link #patience()}): for a runner whose drop takes the bytes whole. */
    protected final Buffer bytesOf(ReadStream<Buffer> in) {
        Promise<Buffer> read = Promise.promise();
        Buffer all = Buffer.buffer();
        in.exceptionHandler(read::tryFail);
        in.endHandler(v -> { synchronized (all) { read.tryComplete(all); } });
        in.handler(chunk -> { synchronized (all) { all.appendBuffer(chunk); } });
        in.resume();
        return awaited(read.future(), "the dropped bytes were not read");
    }

    // ---- the cases' own helpers --------------------------------------------------------------------------------------

    private static final Function<Envelope, Future<Ack>> ACCEPT = e -> Future.succeededFuture(new Ack.Accepted(e.id()));

    /** A small text file; its body names it in what the handler saw. */
    private static ReadStream<Buffer> text(String content) {
        return new ChunkedReadStream(List.of(Buffer.buffer(content)));
    }

    /** The thread that last fired a round: never a handler's. */
    private volatile Thread ticker;

    /**
     * Fires the next round ({@link #tick()}) on a thread of its own and requires it to return: a consumer that runs its round
     * on the timer's thread holds that thread for as long as the round waits for an answer.
     */
    private void fire() {
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread t = Thread.ofPlatform().daemon().name("contract-tick").start(() -> {
            try {
                tick();
            } catch (Throwable x) {
                failed.set(x);
            }
        });
        ticker = t;
        boolean returned;
        try {
            returned = t.join(patience().multipliedBy(2));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while firing a round", interrupted);
        }
        if (!returned) {
            fail("tick() did not return within twice " + patience() + ": the timer's thread runs the round instead of firing it; it sits at "
                    + Arrays.toString(t.getStackTrace()));
        }
        if (failed.get() instanceof AssertionError a) throw a;
        if (failed.get() != null) throw new AssertionError("tick() failed: " + failed.get(), failed.get());
    }

    private <T> T awaited(Future<T> f, String what) {
        try {
            return f.await(patience().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException pending) {
            throw new AssertionError(what + " within " + patience() + ": " + f);
        }
    }

    /** Waits until {@code f} has completed, either way, for at most {@link #patience()}. */
    private <T> Future<T> settled(Future<T> f) {
        try {
            f.otherwiseEmpty().await(patience().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException pending) {
            fail("still pending after " + patience() + ": " + f);
        }
        return f;
    }

    private Health.Status health(Component component) {
        return awaited(component.health(), "health() did not answer").status();
    }

    /** What a case's handler saw, in delivery order: the envelope, the body as text (small files) and the thread. */
    private static final class Seen {
        final List<Envelope> envelopes = new CopyOnWriteArrayList<>();
        final List<String> files = new CopyOnWriteArrayList<>();
        final List<Thread> threads = new CopyOnWriteArrayList<>();

        Function<Envelope, Future<Ack>> answering(Function<Envelope, Future<Ack>> answer) {
            return e -> {
                envelopes.add(e);
                threads.add(Thread.currentThread());
                files.add(e.body().size() <= e.body().store().treeLimit() ? e.body().whole().toString() : "<" + e.body().size() + " bytes>");
                return answer.apply(e);
            };
        }

        int count() { return files.size(); }
    }

    // ---- the cases ---------------------------------------------------------------------------------------------------

    @Test
    public void schemeMatchesTheEndpointUri() {
        String scheme = component().scheme();
        assertNotNull(scheme, "scheme");
        assertFalse(scheme.isBlank(), "scheme is blank");
        assertEquals(endpoint().scheme(), scheme, "the endpoint's URI scheme selects this component");
    }

    @Test
    public void configSchemaIsAJsonObjectSchema() {
        JsonObject schema = new JsonObject(component().configSchema());
        assertEquals("object", schema.getString("type"), "the endpoint options are validated against an object schema: " + schema);
    }

    @Test
    public void aDroppedFileIsDeliveredIntactAndRemovedAfterAccepted() {
        Component component = component();
        Seen seen = new Seen();
        Consumer consumer = component.consumer(endpoint(), seen.answering(ACCEPT));
        String content = "<invoice number=\"4711\"/>";
        drop("invoice-4711.xml", text(content));
        consumer.start().await();
        try {
            fire();
            assertTrue(soon(() -> seen.count() == 1), "the dropped file was delivered: " + seen.files);
            Envelope delivered = seen.envelopes.get(0);
            assertFalse(delivered.id().isBlank(), "the delivery has a message id of its own");
            assertEquals(content, seen.files.get(0), "the body arrives intact");
            assertEquals(content.length(), delivered.body().size(), "every byte arrived, no more");
            assertTrue(soon(() -> remaining().isEmpty()), "the file is removed once the handler accepted it: " + remaining());
        } finally {
            consumer.stop().await();
        }
        assertEquals(1, seen.count(), "delivered once: " + seen.files);
    }

    @Test
    public void theFileStaysWhileTheAnswerIsPending() {
        Component component = component();
        Seen seen = new Seen();
        Promise<Ack> pending = Promise.promise();
        AtomicInteger calls = new AtomicInteger();
        Consumer consumer = component.consumer(endpoint(), seen.answering(e -> calls.incrementAndGet() == 1 ? pending.future() : ACCEPT.apply(e)));
        drop("held.txt", text("held"));
        consumer.start().await();
        try {
            fire();
            assertTrue(soon(() -> seen.count() == 1), "the dropped file was delivered: " + seen.files);
            assertEquals(Set.of("held.txt"), remaining(), "the file stays while the handler's answer is pending (the save may still fail)");
            pending.complete(new Ack.Nack("not saved yet", true));
            fire();   // the retryable answer ended the round; the next one finds the file it kept all along
            assertTrue(soon(() -> seen.count() == 2),
                    "nothing removed the file while its answer was pending: the next round delivers it again: " + seen.files);
            assertEquals(List.of("held", "held"), seen.files, "the same file, intact both times");
            assertTrue(soon(() -> remaining().isEmpty()), "accepted the second time: removed: " + remaining());
        } finally {
            pending.tryComplete(new Ack.Nack("contract cleanup", true));
            consumer.stop().await();
        }
    }

    @Test
    public void aRetryableNackKeepsTheFileAndTheNextRoundDeliversItAgain() {
        Component component = component();
        Seen seen = new Seen();
        AtomicInteger calls = new AtomicInteger();
        Consumer consumer = component.consumer(endpoint(), seen.answering(e -> calls.incrementAndGet() == 1
                ? Future.succeededFuture(new Ack.Nack("busy-right-now", true)) : ACCEPT.apply(e)));
        drop("retry.txt", text("retry-me"));
        consumer.start().await();
        try {
            fire();
            assertTrue(soon(() -> seen.count() == 1), "the dropped file was delivered: " + seen.files);
            fire();
            assertTrue(soon(() -> seen.count() == 2), "a retryable Nack keeps the file: the next round delivers it again: " + seen.files);
            assertEquals(List.of("retry-me", "retry-me"), seen.files, "intact both times: the first delivery consumed nothing");
            assertTrue(soon(() -> remaining().isEmpty()), "accepted the second time: removed: " + remaining());
        } finally {
            consumer.stop().await();
        }
    }

    @Test
    public void aRetryableNackEndsTheRound() {
        Component component = component();
        Seen seen = new Seen();
        AtomicInteger calls = new AtomicInteger();
        Consumer consumer = component.consumer(endpoint(), seen.answering(e -> calls.incrementAndGet() == 1
                ? Future.succeededFuture(new Ack.Nack("flow full", true)) : ACCEPT.apply(e)));
        drop("a.txt", text("a"));
        drop("b.txt", text("b"));
        consumer.start().await();
        try {
            fire();
            assertTrue(soon(() -> seen.count() >= 1), "the oldest file was delivered: " + seen.files);
            fire();
            assertTrue(soon(() -> seen.count() == 3), "both files delivered by the second round: " + seen.files);
            assertEquals(List.of("a", "a", "b"), seen.files,
                    "the retryable Nack ended the first round: b waited for the next one, which began with a again (the flow is full: the poller pauses)");
            assertTrue(soon(() -> remaining().isEmpty()), "both accepted: removed: " + remaining());
        } finally {
            consumer.stop().await();
        }
    }

    @Test
    public void aHandlerThatThrowsOrAnswersNullKeepsTheFile() {
        Component component = component();
        Seen seen = new Seen();
        AtomicInteger callsForA = new AtomicInteger();
        Consumer consumer = component.consumer(endpoint(), seen.answering(e -> {
            if (!"a".equals(e.body().whole().toString())) return ACCEPT.apply(e);
            return switch (callsForA.incrementAndGet()) {
                case 1 -> throw new IllegalStateException("handler-threw");
                case 2 -> null;
                case 3 -> Future.failedFuture(new IllegalStateException("save failed"));
                default -> ACCEPT.apply(e);
            };
        }));
        drop("a.txt", text("a"));
        drop("b.txt", text("b"));
        consumer.start().await();
        try {
            fire();
            assertTrue(soon(() -> seen.count() == 1), "delivered, the handler threw: " + seen.files);
            fire();
            assertTrue(soon(() -> seen.count() == 2), "kept after the throw and delivered again, the handler answered null: " + seen.files);
            fire();
            assertTrue(soon(() -> seen.count() == 3), "kept after the null answer and delivered again, the answer failed: " + seen.files);
            fire();
            assertTrue(soon(() -> seen.count() == 5), "kept after the failed answer and delivered again, then b: " + seen.files);
            assertEquals(List.of("a", "a", "a", "a", "b"), seen.files,
                    "a throw, a null answer and a failed answer each kept the file and ended the round: b came only after a was accepted");
            assertTrue(soon(() -> remaining().isEmpty()), "both accepted: removed: " + remaining());
        } finally {
            consumer.stop().await();
        }
    }

    @Test
    public void aFinalNackIsNotDeliveredAgain() {
        Component component = component();
        Seen seen = new Seen();
        Consumer consumer = component.consumer(endpoint(), seen.answering(e -> "refused".equals(e.body().whole().toString())
                ? Future.succeededFuture(new Ack.Nack("malformed", false)) : ACCEPT.apply(e)));
        drop("refused.txt", text("refused"));
        consumer.start().await();
        try {
            fire();
            assertTrue(soon(() -> seen.count() == 1), "the dropped file was delivered: " + seen.files);
            drop("later.txt", text("later"));   // younger: the next round lists the refused file first
            fire();
            // At least two, not exactly two: a consumer that delivers the refused file again goes on to the younger one in the same
            // round, and a spin on "two" may never catch the moment in between; the list check names that defect whatever the timing.
            assertTrue(soon(() -> seen.count() >= 2), "the next round delivered the younger file: " + seen.files);
            assertEquals(List.of("refused", "later"), seen.files, "the oldest file of the next round was the refused one: it was not delivered again");
            assertTrue(soon(() -> !remaining().contains("later.txt")), "the younger file was accepted: removed: " + remaining());
            assertTrue(remaining().contains("refused.txt"), "without an error directory a finally refused file is left where it was: " + remaining());
        } finally {
            consumer.stop().await();
        }
    }

    @Test
    public void filesAreDeliveredOldestFirst() {
        Component component = component();
        Seen seen = new Seen();
        Consumer consumer = component.consumer(endpoint(), seen.answering(ACCEPT));
        List<String> dropped = List.of("f5", "f3", "f1", "f6", "f4", "f2");   // neither name order nor its reverse
        for (String name : dropped) drop(name + ".txt", text(name));
        consumer.start().await();
        try {
            fire();
            assertTrue(soon(() -> seen.count() == dropped.size()), "one round delivered every file: " + seen.files);
            assertEquals(dropped, seen.files, "oldest first, whatever the names or the listing order say");
            assertTrue(soon(() -> remaining().isEmpty()), "all accepted: removed: " + remaining());
        } finally {
            consumer.stop().await();
        }
    }

    @Test
    public void aReplyFinishesTheFileAsAcceptedDoes() {
        Component component = component();
        Seen seen = new Seen();
        Consumer consumer = component.consumer(endpoint(), seen.answering(e -> Future.succeededFuture(new Ack.Reply(e.withHeader("x-replied", "yes")))));
        drop("sync.txt", text("sync"));
        consumer.start().await();
        try {
            fire();
            assertTrue(soon(() -> seen.count() == 1), "the dropped file was delivered: " + seen.files);
            assertTrue(soon(() -> remaining().isEmpty()), "a sync flow's Reply finishes the file as an Accepted does: removed: " + remaining());
        } finally {
            consumer.stop().await();
        }
    }

    @Test
    public void aBodyAboveTheTreeLimitStreamsThroughIntact() {
        Component component = component();
        Seen seen = new Seen();
        Consumer consumer = component.consumer(endpoint(), seen.answering(ACCEPT));
        long size = 300_000;
        drop("big.bin", new GeneratedReadStream(size, 8192));
        consumer.start().await();
        try {
            fire();
            assertTrue(soon(() -> seen.count() == 1), "the dropped file was delivered: " + seen.files);
            Body body = seen.envelopes.get(0).body();
            assertTrue(size > body.store().treeLimit(),
                    "the runner's BlobStore must have a tree limit below " + size + " bytes for this case to mean anything: " + body.store().treeLimit());
            assertEquals(size, body.size(), "every byte arrived");
            Buffer bytes = bytesOf(body.open());
            assertEquals(size, bytes.length(), "the stored body holds every byte");
            for (int i = 0; i < size; i++) {
                if (bytes.getByte(i) != GeneratedReadStream.byteAt(i)) fail("byte " + i + " differs: the file arrived altered");
            }
            assertTrue(soon(() -> remaining().isEmpty()), "accepted: removed: " + remaining());
        } finally {
            consumer.stop().await();
        }
    }

    @Test
    public void eachDeliveryRunsOnAVirtualThreadOfItsOwn() {
        Component component = component();
        Seen seen = new Seen();
        Consumer consumer = component.consumer(endpoint(), seen.answering(ACCEPT));
        drop("one.txt", text("one"));
        drop("two.txt", text("two"));
        consumer.start().await();
        try {
            fire();
            assertTrue(soon(() -> seen.count() == 2), "one round delivered both files: " + seen.files);
            Thread first = seen.threads.get(0);
            Thread second = seen.threads.get(1);
            assertTrue(first.isVirtual(), "the handler runs on a virtual thread, not on " + first);
            assertTrue(second.isVirtual(), "the handler runs on a virtual thread, not on " + second);
            assertNotSame(first, second, "each delivery calls the handler on a thread of its own, not on one thread for the round (D-057): " + first);
            assertNotSame(ticker, first, "never on the thread that fired the round");
            assertTrue(soon(() -> remaining().isEmpty()), "both accepted: removed: " + remaining());
        } finally {
            consumer.stop().await();
        }
    }

    @Test
    public void stopWaitsForTheDeliveryInFlightAndCompletesItsRemoval() throws Exception {
        Component component = component();
        Seen seen = new Seen();
        Promise<Ack> answer = Promise.promise();
        Consumer consumer = component.consumer(endpoint(), seen.answering(e -> "first".equals(e.body().whole().toString()) ? answer.future() : ACCEPT.apply(e)));
        drop("first.txt", text("first"));
        drop("second.txt", text("second"));
        consumer.start().await();
        Future<Void> stopped = null;
        try {
            fire();
            assertTrue(soon(() -> seen.count() == 1), "the oldest file was delivered: " + seen.files);
            stopped = consumer.stop();
            assertFalse(stopped.isComplete(), "stop waits for the delivery in flight: " + stopped);
            assertEquals(Set.of("first.txt", "second.txt"), remaining(), "nothing is removed while the answer is pending");
            answer.complete(new Ack.Accepted(seen.envelopes.get(0).id()));
            assertTrue(settled(stopped).succeeded(), "stop completes once the delivery in flight was answered: " + stopped.cause());
            assertEquals(Set.of("second.txt"), remaining(),
                    "stop completed only after the accepted file was removed, and the consumer took no new file once stop began");
            assertEquals(List.of("first"), seen.files, "no delivery after stop began");
        } finally {
            answer.tryComplete(new Ack.Nack("contract cleanup", true));
            (stopped == null ? consumer.stop() : stopped).otherwiseEmpty().await(patience().toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Test
    public void aStoppedConsumerDeliversNothing() {
        Component component = component();
        Seen stoppedOne = new Seen();
        Consumer stopped = component.consumer(endpoint(), stoppedOne.answering(ACCEPT));
        stopped.start().await();
        stopped.stop().await();   // before its first round
        drop("late.txt", text("late"));
        Seen running = new Seen();
        Consumer consumer = component.consumer(endpoint(), running.answering(e -> Future.succeededFuture(new Ack.Nack("keep it there", true))));
        consumer.start().await();
        try {
            fire();
            assertTrue(soon(() -> running.count() == 1), "a running consumer of the same endpoint delivers the file: " + running.files);
            fire();
            assertTrue(soon(() -> running.count() == 2), "and again in its next round (the file is kept by the Nack): " + running.files);
            assertEquals(0, stoppedOne.count(), "the stopped consumer's handler saw nothing in those two rounds: " + stoppedOne.files);
            assertEquals(Set.of("late.txt"), remaining(), "kept by the Nack");
        } finally {
            consumer.stop().await();
        }
    }

    @Test
    public void healthIsUpWhileRunningAndDownAfterStop() {
        Component component = component();
        assertEquals(Health.Status.DOWN, health(component), "no consumer polls yet");
        Seen seen = new Seen();
        Consumer consumer = component.consumer(endpoint(), seen.answering(ACCEPT));
        consumer.start().await();
        try {
            assertEquals(Health.Status.UP, health(component), "a consumer runs and none of its rounds failed");
            drop("ping.txt", text("ping"));
            fire();
            assertTrue(soon(() -> seen.count() == 1), "the dropped file was delivered: " + seen.files);
            assertTrue(soon(() -> remaining().isEmpty()), "accepted: removed: " + remaining());
            assertEquals(Health.Status.UP, health(component), "a round that delivered leaves it up");
        } finally {
            consumer.stop().await();
        }
        assertEquals(Health.Status.DOWN, health(component), "nothing polls any more");
    }

    /**
     * A component that names a dedupe prop ({@link Component#dedupeBy}, D-084) sets it on every delivery, never empty, to the same
     * value each time the same file is delivered again and to another value for another file: the durable save dedupes by it, so
     * a file delivered again — its removal refused after the save, here a retryable Nack standing in — is one message, and two
     * files are never one. A component that names none (the mock) passes trivially.
     */
    @Test
    public void aNamedDedupePropIsSetAndStableAcrossRedeliveries() {
        Component component = component();
        Optional<Key<String>> named = component.dedupeBy(endpoint());
        assertNotNull(named, "dedupeBy answers an Optional, never null");
        if (named.isEmpty()) return;   // nothing named: nothing deduped, nothing to keep stable
        Key<String> key = named.get();
        Seen seen = new Seen();
        AtomicInteger calls = new AtomicInteger();
        Consumer consumer = component.consumer(endpoint(), seen.answering(e -> calls.incrementAndGet() == 1
                ? Future.succeededFuture(new Ack.Nack("saved-but-not-removed", true)) : ACCEPT.apply(e)));
        drop("same.txt", text("same-file"));
        consumer.start().await();
        try {
            fire();
            assertTrue(soon(() -> seen.count() == 1), "the dropped file was delivered: " + seen.files);
            fire();
            assertTrue(soon(() -> seen.count() == 2), "the kept file was delivered again: " + seen.files);
            assertTrue(soon(() -> remaining().isEmpty()), "accepted the second time: removed: " + remaining());
            drop("other.txt", text("other-file"));
            fire();
            assertTrue(soon(() -> seen.count() == 3), "another file was delivered: " + seen.files);
        } finally {
            consumer.stop().await();
        }
        assertEquals(List.of("same-file", "same-file", "other-file"), seen.files, "fixture: the same file twice, then another");
        Optional<String> first = seen.envelopes.get(0).get(key);
        Optional<String> again = seen.envelopes.get(1).get(key);
        Optional<String> other = seen.envelopes.get(2).get(key);
        assertTrue(first.isPresent() && !first.get().isEmpty(), "the named prop " + key.name() + " is set on the delivery, never empty: " + first);
        assertNotEquals(seen.envelopes.get(0).id(), seen.envelopes.get(1).id(), "each delivery is a message of its own: the key, not the id, dedupes");
        assertEquals(first, again, "the same file delivered again carries the same " + key.name());
        assertTrue(other.isPresent() && !other.get().isEmpty(), "set on the other file's delivery too: " + other);
        assertNotEquals(first, other, "another file carries another " + key.name() + ": two files are never one message");
    }
}
