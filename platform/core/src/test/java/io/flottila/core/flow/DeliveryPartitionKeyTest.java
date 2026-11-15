package io.flottila.core.flow;

import io.flottila.api.Ack;
import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.Envelope;
import io.flottila.api.Fault;
import io.flottila.api.Headers;
import io.flottila.core.admission.Admission;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.core.record.Recorder;
import io.flottila.core.record.RecordingPolicy;
import io.flottila.core.record.Snapshot;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.InMemoryBlobStore;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P2-01 (D-075, supersedes D-049 b): an at-least-once {@link Delivery} evaluates the flow's partition key at the durable save —
 * on the caller's virtual thread, on the envelope as it arrived, inside the save-timeout window, before the body's persist and
 * the ingress record start — and enqueues the message with it. No value or {@code ""}: no key (unordered). More than
 * {@link Delivery#MAX_PARTITION_KEY_CHARS} (256) characters: a final Nack (the http consumer's 400), nothing saved. A
 * {@link Fault} of kind MALFORMED or INVALID while evaluating it: saved and enqueued without a key, Accepted (the pipeline parks
 * it). Any other throwable: a retryable Nack, nothing saved.
 *
 * <p>The shape these tests assume (P2-01 test-author): {@code static Delivery atLeastOnce(Admission admission, String flow,
 * Function<Envelope, Snapshot> ingress, Function<Envelope, Optional<String>> partitionKey, MessageStore store, WorkQueue queue,
 * Timer timer, Duration saveTimeout)} and {@code static final int MAX_PARTITION_KEY_CHARS}, both visible in the package.
 */
@Timeout(10)
class DeliveryPartitionKeyTest {

    static final Buffer ORDER = Buffer.buffer("<order id=\"1\"/>");
    static final Duration SAVE = Duration.ofSeconds(2);

    final FakeRuntime rt = FakeRuntime.create();
    final HoldingMessageStore store = new HoldingMessageStore(new InMemoryMessageStore());
    final InMemoryWorkQueue queue = new InMemoryWorkQueue(rt.clock());
    /** The ingress body's own store: what was persisted, and when. */
    final HoldingBlobStore own = new HoldingBlobStore(new InMemoryBlobStore(rt.hasher(), FakeRuntime.DEFAULT_TREE_LIMIT));
    final Admission admission = new Admission(4, 1 << 20);
    final List<CountDownLatch> latches = new CopyOnWriteArrayList<>();

    @AfterEach
    void openEveryLatch() {
        latches.forEach(CountDownLatch::countDown);   // no key evaluation stays parked past its case
    }

    // --- fixtures

    /** At-least-once for flow {@code orders} over the holding store, the in-memory queue and the fake timer, keyed by {@code key}. */
    Delivery keyedBy(Function<Envelope, Optional<String>> key) {
        Recorder recorder = new Recorder(store, rt.clock(), Meter.system(), Map.of());
        return Delivery.atLeastOnce(admission, "orders", e -> recorder.startSnapshot(e, Recorder.INGRESS, RecordingPolicy.DEFAULT),
                key, store, queue, rt.timer(), SAVE);
    }

    /** A stage-0 envelope of flow {@code orders}, its body in {@link #own} under {@code body(id, 0)}. */
    Envelope ingress() {
        String id = rt.ids().next();
        Body body = own.write(BlobKey.body(id, 0), ORDER, own.treeLimit()).await();
        return Envelope.ingress(id, "orders", Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), body);
    }

    /** {@code delivery.apply(in)} on a virtual thread of its own, as a consumer calls it; its answer, or what escaped apply. */
    static CompletableFuture<Future<Ack>> applyOnVirtualThread(Delivery delivery, Envelope in) {
        CompletableFuture<Future<Ack>> applied = new CompletableFuture<>();
        Thread.ofVirtual().name("consumer-" + in.id()).start(() -> {
            try {
                applied.complete(delivery.apply(in));
            } catch (Throwable escaped) {
                applied.completeExceptionally(escaped);
            }
        });
        return applied;
    }

    static Future<Ack> returned(CompletableFuture<Future<Ack>> applied) {
        try {
            return applied.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException escaped) {
            return fail("apply threw instead of answering: " + escaped.getCause(), escaped.getCause());
        } catch (TimeoutException | InterruptedException stuck) {
            return fail("apply did not return within 5 s");
        }
    }

    /** The one queued item; it is the message's, for flow {@code orders}, at step 1. */
    WorkQueue.Item theItemOf(Envelope in) {
        List<WorkQueue.Item> items = queue.snapshot();
        assertEquals(1, items.size(), "one item queued: " + items);
        WorkQueue.Item item = items.get(0);
        assertEquals(in.id(), item.messageId());
        assertEquals("orders", item.flow());
        assertEquals(1, item.step(), "enqueued for step 1");
        return item;
    }

    /** Saved as an accepted message is: the body persisted, the ingress snapshot recorded. */
    void saved(Envelope in) {
        assertTrue(own.persisted(in.body().key()), "the body is durable");
        assertEquals(List.of("ingress"), store.timeline(in.id()).await().stream().map(Snapshot::stepName).toList(), "the ingress snapshot is recorded");
    }

    /** Nothing of the message was saved or queued, and its permit is back. */
    void nothingSaved(Envelope in) {
        assertEquals(List.of(), own.persists(), "no persist was asked for the body");
        assertEquals(0, store.recorded(Recorder.INGRESS), "no ingress snapshot was recorded");
        assertEquals(List.of(), store.timeline(in.id()).await(), "nothing of the message is in the store");
        assertEquals(List.of(), queue.snapshot(), "nothing is queued");
        assertEquals(0, admission.inFlightRequests(), "the permit is back");
        assertEquals(0, admission.inFlightBytes(), "its bytes are back");
    }

    static String chars(int n) {
        return "k".repeat(n);
    }

    // --- the key, evaluated at the save

    /**
     * The key function sees the envelope exactly as it arrived (the same instance), on the thread that called {@code apply}, once,
     * after the save timeout was scheduled on the timer and before the body's persist or the ingress record were asked; the item is
     * enqueued for step 1 with the value it returned.
     */
    @Test
    void keyEvaluatedOnIngressEnvelopeIsEnqueued() {
        AtomicReference<Envelope> seen = new AtomicReference<>();
        AtomicReference<Thread> on = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger timeoutsPending = new AtomicInteger(-1);
        AtomicInteger persistsBefore = new AtomicInteger(-1);
        AtomicInteger recordsBefore = new AtomicInteger(-1);
        Delivery delivery = keyedBy(e -> {
            calls.incrementAndGet();
            seen.set(e);
            on.set(Thread.currentThread());
            timeoutsPending.set(rt.timer().pending());
            persistsBefore.set(own.persists().size());
            recordsBefore.set(store.recorded(Recorder.INGRESS));
            return Optional.of("C-4711");
        });
        Envelope in = ingress();
        AtomicReference<Thread> caller = new AtomicReference<>();
        CompletableFuture<Future<Ack>> applied = new CompletableFuture<>();
        Thread.ofVirtual().name("consumer").start(() -> {
            caller.set(Thread.currentThread());
            try {
                applied.complete(delivery.apply(in));
            } catch (Throwable escaped) {
                applied.completeExceptionally(escaped);
            }
        });

        assertEquals(new Ack.Accepted(in.id()), returned(applied).await());
        assertEquals("C-4711", theItemOf(in).partitionKey(), "the item carries the key");
        saved(in);
        assertEquals(1, calls.get(), "the key is evaluated once per message");
        assertSame(in, seen.get(), "on the envelope as it arrived, not a copy or a later one");
        assertSame(caller.get(), on.get(), "on the caller's thread");
        assertTrue(on.get().isVirtual(), "a virtual thread: " + on.get());
        assertEquals(1, timeoutsPending.get(), "inside the save-timeout window: the timeout was scheduled when the key was evaluated");
        assertEquals(0, persistsBefore.get(), "before the body's persist was asked");
        assertEquals(0, recordsBefore.get(), "before the ingress record was asked");
    }

    /**
     * The key's evaluation counts against the save timeout: still evaluating when the timeout runs out, the sender gets the
     * retryable timeout Nack, and the message is never enqueued, whatever the key then returns.
     */
    @Test
    void keyStillEvaluatingAtTheSaveTimeoutIsARetryableNackAndEnqueuesNothing() throws Exception {
        CountDownLatch evaluating = new CountDownLatch(1);
        CountDownLatch mayReturn = new CountDownLatch(1);
        latches.add(mayReturn);
        Delivery delivery = keyedBy(e -> {
            evaluating.countDown();
            try {
                if (!mayReturn.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture: the key was never let go");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("fixture: interrupted", interrupted);
            }
            return Optional.of("late");
        });
        Envelope in = ingress();
        CompletableFuture<Future<Ack>> applied = applyOnVirtualThread(delivery, in);
        assertTrue(evaluating.await(5, TimeUnit.SECONDS), "the key is being evaluated");
        assertEquals(SAVE, rt.timer().nextDelay(), "the save timeout is scheduled while the key evaluates");

        rt.clock().advance(SAVE);
        mayReturn.countDown();

        Ack.Nack nack = assertInstanceOf(Ack.Nack.class, returned(applied).await(), "the timeout answers");
        assertTrue(nack.retryable(), "a retryable Nack: " + nack.reason());
        assertTrue(nack.reason().contains("timed out"), "the save timed out: " + nack.reason());
        assertEquals(List.of(), queue.snapshot(), "a save that ends after its timeout enqueues nothing");
    }

    @Test
    void noValueOrEmptyIsUnordered() {
        for (Optional<String> none : List.of(Optional.<String>empty(), Optional.of(""))) {
            Delivery delivery = keyedBy(e -> none);
            Envelope in = ingress();
            assertEquals(new Ack.Accepted(in.id()), assertDoesNotThrow(() -> delivery.apply(in)).await(), "accepted with " + none);
            List<WorkQueue.Item> items = queue.snapshot().stream().filter(i -> i.messageId().equals(in.id())).toList();
            assertEquals(1, items.size(), "queued with " + none + ": " + queue.snapshot());
            assertEquals(null, items.get(0).partitionKey(), "no key, so no order: " + none + " → null, never \"\"");
            saved(in);
        }
    }

    /**
     * 256 characters is a key, kept whole; 257 is a final Nack naming the bound — nothing persisted, recorded or queued, the permit
     * back. The bound is {@link Delivery#MAX_PARTITION_KEY_CHARS}.
     */
    @Test
    void key256AcceptedKey257FinalNackNothingSaved() {
        assertEquals(256, Delivery.MAX_PARTITION_KEY_CHARS, "D-075: 256 characters");
        String longest = chars(256);
        Delivery atBound = keyedBy(e -> Optional.of(longest));
        Envelope kept = ingress();
        assertEquals(new Ack.Accepted(kept.id()), atBound.apply(kept).await(), "256 characters is a key");
        assertEquals(longest, theItemOf(kept).partitionKey(), "kept whole");

        int queuedBefore = queue.size();
        int persistsBefore = own.persists().size();
        int recordsBefore = store.recorded(Recorder.INGRESS);
        Delivery over = keyedBy(e -> Optional.of(chars(257)));
        Envelope refused = ingress();

        Future<Ack> ack = assertDoesNotThrow(() -> over.apply(refused), "an over-long key is an answer, not an exception");

        assertTrue(ack.isComplete(), "answered at once: " + ack);
        Ack.Nack nack = assertInstanceOf(Ack.Nack.class, ack.result(), "a key over the bound is refused: " + ack);
        assertFalse(nack.retryable(), "a final Nack: the same message is refused again (http: 400): " + nack.reason());
        assertTrue(nack.reason().contains("256"), "names the bound: " + nack.reason());
        assertEquals(persistsBefore, own.persists().size(), "no persist was asked for the refused body: " + own.persists());
        assertEquals(recordsBefore, store.recorded(Recorder.INGRESS), "no ingress snapshot was recorded for it");
        assertEquals(List.of(), store.timeline(refused.id()).await(), "nothing of it is in the store");
        assertEquals(queuedBefore, queue.size(), "nothing queued for it");
        assertEquals(0, admission.inFlightRequests(), "the permit is back");
        assertEquals(0, admission.inFlightBytes(), "its bytes are back");
    }

    /**
     * The body cannot be read (MALFORMED) or breaks a rule (INVALID) — the key function throws such a {@link Fault}: the message is
     * saved and queued without a key and Accepted; the worker's pipeline parks it, not the sender's retry loop.
     */
    @ParameterizedTest
    @EnumSource(value = Fault.Kind.class, names = {"MALFORMED", "INVALID"})
    void malformedBodyAcceptedUnkeyed(Fault.Kind kind) {
        Delivery delivery = keyedBy(e -> { throw new DeliveryTest.KindedFault(kind, "not an order: " + kind); });
        Envelope in = ingress();

        Future<Ack> ack = assertDoesNotThrow(() -> delivery.apply(in), "the key's " + kind + " fault is no exception out of apply");

        assertEquals(new Ack.Accepted(in.id()), ack.await(), kind + ": accepted");
        assertEquals(null, theItemOf(in).partitionKey(), kind + ": queued without a key");
        saved(in);
        assertEquals(0, admission.inFlightRequests(), "the permit is back");
    }

    static Stream<Arguments> otherFailures() {
        return Stream.of(
                Arguments.of("a RuntimeException", (Supplier<Throwable>) () -> new IllegalStateException("key store down")),
                Arguments.of("a DOWNSTREAM fault", (Supplier<Throwable>) () -> new DeliveryTest.KindedFault(Fault.Kind.DOWNSTREAM, "key store down")),
                Arguments.of("an INTERNAL fault", (Supplier<Throwable>) () -> new DeliveryTest.KindedFault(Fault.Kind.INTERNAL, "key store down")),
                Arguments.of("an Error", (Supplier<Throwable>) () -> new KeyCrash("key store down")));
    }

    /** What a key function that crashes throws: an {@link Error}, past every exception handler. */
    static final class KeyCrash extends Error {
        KeyCrash(String message) { super(message); }
    }

    /** Any other failure of the key: a retryable Nack naming it, nothing saved or queued, the permit back. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("otherFailures")
    void otherKeyFailureRetryableNackNothingSaved(String what, Supplier<Throwable> failure) {
        Delivery delivery = keyedBy(e -> { throw sneaky(failure.get()); });
        Envelope in = ingress();

        Future<Ack> ack = assertDoesNotThrow(() -> delivery.apply(in), what + " of the key is an answer, not an exception out of apply");

        assertTrue(ack.isComplete(), "answered at once: " + ack);
        Ack.Nack nack = assertInstanceOf(Ack.Nack.class, ack.result(), what + ": refused for now: " + ack);
        assertTrue(nack.retryable(), what + ": the sender may try again: " + nack.reason());
        assertTrue(nack.reason().contains("key store down"), "names the failure: " + nack.reason());
        nothingSaved(in);
    }

    @SuppressWarnings("unchecked")
    static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {
        throw (T) t;
    }
}
