package io.flottila.core.flow;

import io.flottila.api.Ack;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Pipeline;
import io.flottila.api.Stage;
import io.flottila.api.StepFactory;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.dlq.ParkedException;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.scope.MessageContext;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.flottila.core.flow.DeliveryTest.until;
import static io.flottila.core.flow.FlowBuilderTest.NO_FILES;
import static io.flottila.core.flow.FlowBuilderTest.factory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * D-100 (FIX-115, F-24): every pipeline the builder builds — sync, at-most-once, the at-least-once worker's, a replay's and a DLQ
 * retry's — starts with one stage that binds the message's {@link MessageContext}: its id, its flow, tenant {@code "default"},
 * trace id = message id, and a deadline = the run's start on the Runtime's clock + the flow's {@code delivery.timeout} (default
 * 30 s), fresh per run (each claim, each replay). The binding ends with the run. A core probe step reads
 * {@link MessageContext#current()} — steps in api cannot.
 */
class FlowBuilderContextTest {

    static final Duration BACKLOG = Duration.ofHours(1);
    static final Duration LEASE = Duration.ofSeconds(30);
    static final Duration BOUND = Duration.ofSeconds(10);

    final FakeRuntime rt = FakeRuntime.create();

    /** What a probe saw: its step's name, the context bound on its thread (null when none), the thread it ran on. */
    record Seen(String step, MessageContext context, Thread thread) {}

    final List<Seen> seen = new CopyOnWriteArrayList<>();
    /** The probe of this name throws (after it looked), while set. */
    final AtomicReference<String> failAt = new AtomicReference<>();
    /** What the failing probe threw, as thrown. */
    final AtomicReference<RuntimeException> thrown = new AtomicReference<>();

    /** {@code probe}: records what {@link MessageContext} its thread has bound, then throws when {@link #failAt} names it. */
    final StepFactory probe = factory("probe", s -> Stage.of(e -> {
        seen.add(new Seen(s.name(), MessageContext.isBound() ? MessageContext.current() : null, Thread.currentThread()));
        if (s.name().equals(failAt.get())) {
            RuntimeException failure = new IllegalStateException("probe " + s.name() + " fails " + e.id());
            thrown.set(failure);
            throw failure;
        }
        return e;
    }));

    /** What a crashed step throws: an {@link Error}, past every handler — the worker leaves the item to its lease. */
    static final class FirstRunCrash extends Error {
        FirstRunCrash(String message) { super(message); }
    }

    /** {@code crash-first}: an {@link Error} on a message's first run; a rerun carries its attempt and passes. */
    final StepFactory crashFirst = factory("crash-first", s -> Stage.of(e -> {
        if (e.get(Envelope.ATTEMPT).isEmpty()) throw new FirstRunCrash("crashed at " + s.name() + " on the first run of " + e.id());
        return e;
    }));

    FlowBuilder builder() {
        return new FlowBuilder(rt.runtime(), List.of(probe, crashFirst), List.of(), Map.of());
    }

    static Map<String, Object> step(String type, String name) {
        return Map.of("type", type, "name", name);
    }

    /** A flow {@code id} in {@code mode}; {@code timeout} is its {@code delivery.timeout}, none when null. */
    static FlowDefinition flow(String id, String mode, String timeout, List<Map<String, Object>> steps) {
        Map<String, Object> delivery = new HashMap<>();
        delivery.put("mode", mode);
        if (timeout != null) delivery.put("timeout", timeout);
        Map<String, Object> doc = new HashMap<>();
        doc.put("schemaVersion", 1);
        doc.put("kind", "flow");
        doc.put("id", id);
        doc.put("version", "1.0.0");
        doc.put("from", Map.of("endpoint", "in"));
        doc.put("delivery", delivery);
        doc.put("limits", Map.of("maxBodyBytes", 4096, "maxInFlight", 4));
        doc.put("steps", steps);
        return FlowDefinition.from(doc);
    }

    Envelope ingress(String flow) {
        return rt.ingress(flow, Headers.empty(), Buffer.buffer("<x/>"));
    }

    /** What ran on a virtual thread of its own: the thread and what the call returned or threw. */
    record Ran(Thread thread, Object outcome) {}

    /** {@code call} on a fresh virtual thread, as a consumer runs a message; waits for it, at most {@link #BOUND}. */
    static Ran onVirtualThread(Callable<Object> call) throws InterruptedException {
        AtomicReference<Object> outcome = new AtomicReference<>();
        Thread t = Thread.ofVirtual().name("context-caller").start(() -> {
            try {
                outcome.set(call.call());
            } catch (Throwable x) {
                outcome.set(x);
            }
        });
        if (!t.join(BOUND)) fail("the caller's thread did not end within " + BOUND + ", at " + Arrays.toString(t.getStackTrace()));
        return new Ran(t, outcome.get());
    }

    static MessageContext context(String id, String flow, Instant deadline) {
        return new MessageContext(id, flow, "default", id, deadline);   // tenant "default"; trace id = message id (D-100)
    }

    /**
     * A sync delivery runs the pipeline on the caller's thread with the context bound for every step: id, flow, tenant
     * "default", trace id = id, deadline = the run's start + {@code delivery.timeout} (7 s) — the clock moved an hour between the
     * build and the message, so a deadline taken at build time is an hour early.
     */
    @Test
    void aSyncDeliveryBindsTheContextWithADeadlineFromTheStartOfItsRun() throws Exception {
        Delivery sync = builder().delivery(flow("ctx-sync", "sync", "7s", List.of(step("probe", "p1"), step("probe", "p2"))), NO_FILES);
        rt.clock().advance(BACKLOG);
        Envelope in = ingress("ctx-sync");
        Instant start = rt.clock().instant();

        Ran ran = onVirtualThread(() -> sync.apply(in).await());

        assertInstanceOf(Ack.Reply.class, ran.outcome(), "fixture: the sync delivery answered");
        MessageContext expected = context(in.id(), "ctx-sync", start.plus(Duration.ofSeconds(7)));
        assertEquals(List.of(new Seen("p1", expected, ran.thread()), new Seen("p2", expected, ran.thread())), seen,
                "both steps see the message's context on the caller's thread");
    }

    /**
     * An at-most-once delivery answers Accepted at once and runs the pipeline on a virtual thread of its own: that thread has the
     * context bound, deadline = its start + {@code delivery.timeout} (1500 ms: a timeout under a second is not truncated).
     */
    @Test
    void anAtMostOnceRunBindsTheContextOnItsOwnThread() throws Exception {
        Delivery atMostOnce = builder().delivery(flow("ctx-amo", "at-most-once", "1500ms", List.of(step("probe", "p"))), NO_FILES);
        rt.clock().advance(BACKLOG);
        Envelope in = ingress("ctx-amo");
        Instant start = rt.clock().instant();

        Ran accepted = onVirtualThread(() -> atMostOnce.apply(in).await());
        assertEquals(new Ack.Accepted(in.id()), accepted.outcome(), "fixture: accepted at once");
        until(() -> seen.size() == 1, () -> "the at-most-once run reached its probe: " + seen);

        Seen run = seen.get(0);
        assertEquals(context(in.id(), "ctx-amo", start.plus(Duration.ofMillis(1500))), run.context(), "the run's own thread has the context bound");
        assertNotSame(accepted.thread(), run.thread(), "fixture: the run is not on the caller's thread");
        assertTrue(run.thread().isVirtual(), "the run's thread is virtual: " + run.thread());
        assertTrue(run.thread().join(BOUND), "fixture: the run ended");
    }

    /**
     * The at-least-once worker binds a fresh context per claim: accepted, then claimed an hour later (a backlog), the run's
     * deadline is the claim + 7 s, not the accept + 7 s; the first run crashes (left to its lease), the item is claimed again
     * after the lease and that run's deadline is the second claim + 7 s. Same id and trace id both times.
     */
    @Test
    void eachWorkerClaimBindsAFreshDeadlineFromTheClaim() throws Exception {
        InMemoryWorkQueue queue = new InMemoryWorkQueue(rt.clock());
        FlowBuilder b = builder().recording(new InMemoryMessageStore(), () -> 0).dlq(new InMemoryDlq()).queue(queue);
        FlowDefinition def = flow("ctx-worker", "at-least-once", "7s", List.of(step("probe", "p"), step("crash-first", "c")));
        Delivery accepting = b.delivery(def, NO_FILES);
        FlowWorker worker = b.worker(def, NO_FILES, "w1", new WorkerPolicy(LEASE, 3, Duration.ofSeconds(1)));
        try {
            Envelope in = ingress("ctx-worker");
            assertEquals(new Ack.Accepted(in.id()), accepting.apply(in).await(5, TimeUnit.SECONDS), "fixture: saved and queued");
            rt.clock().advance(BACKLOG);
            Instant firstClaim = rt.clock().instant();
            assertEquals(1, worker.poll().await(5, TimeUnit.SECONDS), "fixture: the worker claimed the item");
            until(() -> seen.size() == 1, () -> "the first run reached its probe: " + seen);
            Thread firstRun = seen.get(0).thread();
            assertTrue(firstRun.join(BOUND), "fixture: the first run crashed and ended, at " + Arrays.toString(firstRun.getStackTrace()));

            rt.clock().advance(LEASE);   // the crashed run's lease runs out
            Instant secondClaim = rt.clock().instant();
            assertEquals(1, worker.poll().await(5, TimeUnit.SECONDS), "fixture: the item was claimed again after its lease");
            until(() -> seen.size() == 2 && queue.size() == 0, () -> "the second run reached its probe and completed: " + seen + ", " + queue.snapshot());

            assertEquals(context(in.id(), "ctx-worker", firstClaim.plus(Duration.ofSeconds(7))), seen.get(0).context(),
                    "the first claim's run: deadline from the claim, not from the accept an hour earlier");
            assertEquals(context(in.id(), "ctx-worker", secondClaim.plus(Duration.ofSeconds(7))), seen.get(1).context(),
                    "the second claim's run: a fresh deadline from that claim");
            assertTrue(seen.get(0).thread().isVirtual() && seen.get(1).thread().isVirtual(), "worker runs are on virtual threads: " + seen);
        } finally {
            worker.stop();
        }
    }

    /**
     * A replay ({@code from}) and a DLQ retry each bind the context of the new message they run: its new id as id and trace id,
     * and a deadline from the replay's start + 7 s — not the original's. The original run (a built pipeline) bound its own.
     */
    @Test
    void aReplayAndARetryBindTheirNewIdWithAFreshDeadline() throws Exception {
        InMemoryDlq dlq = new InMemoryDlq();
        FlowBuilder b = builder().recording(new InMemoryMessageStore(), () -> 0).dlq(dlq);
        FlowDefinition def = flow("ctx-replay", "at-least-once", "7s", List.of(step("probe", "a"), step("probe", "b")));
        Pipeline pipeline = b.build(def, NO_FILES);
        Replay replay = b.replay(def, NO_FILES);
        Envelope in = ingress("ctx-replay");
        Instant original = rt.clock().instant();

        failAt.set("b");
        Ran parked = onVirtualThread(() -> pipeline.apply(in));
        assertInstanceOf(ParkedException.class, parked.outcome(), "fixture: the original run parked at b");
        assertTrue(dlq.get(in.id()).await().isPresent(), "fixture: the DLQ row for the retry");
        failAt.set(null);

        rt.clock().advance(BACKLOG);
        Instant replayed = rt.clock().instant();
        Ran from = onVirtualThread(() -> replay.from(in.id(), 2, "r-1"));
        assertInstanceOf(Envelope.class, from.outcome(), "fixture: the replay went through");

        rt.clock().advance(Duration.ofMinutes(1));
        Instant retried = rt.clock().instant();
        Ran retry = onVirtualThread(() -> replay.retry(in.id(), "r-2"));
        assertInstanceOf(Envelope.class, retry.outcome(), "fixture: the retry went through");

        MessageContext originalContext = context(in.id(), "ctx-replay", original.plus(Duration.ofSeconds(7)));
        assertEquals(List.of(
                        new Seen("a", originalContext, parked.thread()),
                        new Seen("b", originalContext, parked.thread()),
                        new Seen("b", context("r-1", "ctx-replay", replayed.plus(Duration.ofSeconds(7))), from.thread()),
                        new Seen("b", context("r-2", "ctx-replay", retried.plus(Duration.ofSeconds(7))), retry.thread())),
                seen, "the original, the replay from step 2 and the retry of the parked step each run under their own message's context");
        replay.close().await(5, TimeUnit.SECONDS);
    }

    /**
     * The binding is the run's and ends with it, on the caller's thread: unbound before, bound during each run (the probe saw it
     * on this very thread), unbound after a run that replied and after one whose step threw — that failure reaches the caller as
     * the step threw it.
     */
    @Test
    void nothingIsBoundOnTheCallersThreadAfterASyncRun() throws Exception {
        Delivery sync = builder().delivery(flow("ctx-after", "sync", "7s", List.of(step("probe", "p"))), NO_FILES);
        Envelope ok = ingress("ctx-after");
        Envelope bad = ingress("ctx-after");

        Ran ran = onVirtualThread(() -> {
            List<Object> observed = new ArrayList<>();
            observed.add(MessageContext.isBound());
            observed.add(sync.apply(ok).await());
            observed.add(MessageContext.isBound());
            failAt.set("p");
            observed.add(sync.apply(bad));
            observed.add(MessageContext.isBound());
            return observed;
        });

        assertInstanceOf(List.class, ran.outcome(), "fixture: the caller ran to its end: " + ran.outcome());
        List<?> observed = (List<?>) ran.outcome();
        assertEquals(List.of(ok.id(), bad.id()), seen.stream().map(s -> s.context() == null ? "unbound" : s.context().messageId()).toList(),
                "each run had its message's context bound");
        assertTrue(seen.stream().allMatch(s -> s.thread() == ran.thread()), "on the caller's thread: " + seen);
        assertEquals(false, observed.get(0), "fixture: nothing bound before");
        assertInstanceOf(Ack.Reply.class, observed.get(1), "fixture: the first run replied");
        assertEquals(false, observed.get(2), "nothing bound on the caller's thread after a run that replied");
        Future<?> failed = assertInstanceOf(Future.class, observed.get(3));
        assertTrue(failed.failed(), "a sync flow's failed step fails the answer: " + failed);
        assertSame(thrown.get(), failed.cause(), "the step's failure comes out as it was thrown");
        assertEquals(false, observed.get(4), "nothing bound on the caller's thread after a run whose step threw");
    }

    /** A flow without {@code delivery.timeout} gives each message 30 s from the start of its run. */
    @Test
    void aFlowWithoutDeliveryTimeoutGivesEachRunThirtySeconds() throws Exception {
        Delivery sync = builder().delivery(flow("ctx-default", "sync", null, List.of(step("probe", "p"))), NO_FILES);
        rt.clock().advance(Duration.ofMinutes(3));
        Envelope in = ingress("ctx-default");
        Instant start = rt.clock().instant();

        Ran ran = onVirtualThread(() -> sync.apply(in).await());

        assertInstanceOf(Ack.Reply.class, ran.outcome(), "fixture: the sync delivery answered");
        assertEquals(List.of(new Seen("p", context(in.id(), "ctx-default", start.plus(Duration.ofSeconds(30))), ran.thread())), seen,
                "the default delivery.timeout is 30 s");
    }
}
