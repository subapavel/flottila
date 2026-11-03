package io.flottila.core.flow;

import io.flottila.api.Ack;
import io.flottila.api.Component;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Fault;
import io.flottila.api.Headers;
import io.flottila.api.Health;
import io.flottila.api.Key;
import io.flottila.api.NackException;
import io.flottila.api.Pipeline;
import io.flottila.api.Producer;
import io.flottila.api.Stage;
import io.flottila.core.admission.Targets;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.dlq.Parked;
import io.flottila.core.dlq.ParkedException;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static io.flottila.core.flow.DeliveryTest.until;
import static io.flottila.core.flow.FlowBuilderTest.NO_FILES;
import static io.flottila.core.flow.FlowBuilderTest.factory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The {@code to} step's send under the per-target limit (D-099): the builder resolves each producer's limit at build time from
 * {@link Component#target} — the {@link Targets}' limit of that key, or an unshared one with the defaults when the component
 * names none — and wraps only the producer's send, inside Retry (no permit across a backoff) and inside Recorder; a replay runs
 * the same stage. A permit not granted within maxWait on the Runtime's timer is a retryable NackException. The partner is a
 * component at the SPI boundary that counts the sends it has not answered yet.
 */
class FlowBuilderTargetTest {

    static final String SHARED = "partner://shared:1";
    static final Duration WAIT = Duration.ofSeconds(5);

    final FakeRuntime rt = FakeRuntime.create();
    final CountingPartner partner = new CountingPartner("partner", true);
    final CountingPartner plain = new CountingPartner("plain", false);

    @AfterEach
    void answerEverythingHeld() {
        partner.releaseAll();
        plain.releaseAll();
    }

    /**
     * A partner component: {@link #target} is {@code scheme://authority} of the endpoint URI (or none when built without), every
     * send recorded; the next {@link #holdNext} sends stay unanswered until the test releases them, the next {@link #failNext}
     * fail at once, the rest are answered at once. In flight = sent and not yet answered, with its peak.
     */
    static final class CountingPartner implements Component {
        final String scheme;
        final boolean answersTarget;
        final List<Envelope> sent = new CopyOnWriteArrayList<>();
        final List<Promise<Envelope>> held = new CopyOnWriteArrayList<>();
        final AtomicInteger holds = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();

        CountingPartner(String scheme, boolean answersTarget) { this.scheme = scheme; this.answersTarget = answersTarget; }

        CountingPartner holdNext(int n) { holds.addAndGet(n); return this; }
        CountingPartner failNext(int n) { failures.addAndGet(n); return this; }

        /** Answers the {@code i}-th held send (in the order they arrived). */
        void release(int i) { held.get(i).tryComplete(null); }
        void releaseAll() { held.forEach(p -> p.tryComplete(null)); }

        List<String> sentIds() { return sent.stream().map(Envelope::id).toList(); }

        @Override public String scheme() { return scheme; }
        @Override public String configSchema() { return "{}"; }
        @Override public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) { throw new UnsupportedOperationException(); }
        @Override public Future<Health> health() { return Future.succeededFuture(Health.up()); }

        @Override public Optional<String> target(EndpointConfig cfg) {
            return answersTarget ? Optional.of(cfg.uri().getScheme() + "://" + cfg.uri().getAuthority()) : Optional.empty();
        }

        @Override public Producer producer(EndpointConfig cfg) {
            return new Producer() {
                @Override public Future<Envelope> send(Envelope e) {
                    sent.add(e);
                    if (failures.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) return Future.failedFuture(new IllegalStateException("partner refused " + e.id()));
                    peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    Envelope reply = e.withHeader("replied-by", cfg.name());
                    if (holds.getAndUpdate(n -> n > 0 ? n - 1 : 0) == 0) {
                        inFlight.decrementAndGet();
                        return Future.succeededFuture(reply);
                    }
                    Promise<Envelope> answer = Promise.promise();
                    Future<Envelope> replied = answer.future().map(ignored -> reply);
                    replied.onComplete(done -> inFlight.decrementAndGet());
                    held.add(answer);
                    return replied;
                }
                @Override public Future<Void> close() { return Future.succeededFuture(); }
            };
        }
    }

    static EndpointConfig endpoint(String name, String uri) {
        return new EndpointConfig(name, URI.create(uri), Map.of());
    }

    /** Endpoints a, b on the shared target (two paths of one partner), fast on another target, p on a component without one. */
    static final Map<String, EndpointConfig> ENDPOINTS = Map.of(
            "a", endpoint("a", SHARED + "/a"),
            "b", endpoint("b", SHARED + "/b?x=1"),
            "slow", endpoint("slow", "partner://slow:1/s"),
            "fast", endpoint("fast", "partner://fast:1/f"),
            "p", endpoint("p", "plain://x/p"));

    FlowBuilder builder() {
        return new FlowBuilder(rt.withComponent(partner).withComponent(plain).runtime(),
                List.of(factory("mark", s -> Stage.of(e -> e.withHeader("seen-" + s.name(), "yes"))),
                        factory("choose", s -> Stage.of(e -> e.with(new Key<>("partner"), "shared")))),
                List.of(), ENDPOINTS);
    }

    static Map<String, Object> sendStep(String endpoint, boolean retryable) {
        return Map.of("type", "to", "name", "send", "endpoint", endpoint, "retryable", retryable);
    }

    /** A flow {@code id} in {@code mode} with the given steps; retry 1 attempt after 1 s; room for 32 messages in flight. */
    static FlowDefinition flow(String id, String mode, List<Map<String, Object>> steps) {
        Map<String, Object> doc = new HashMap<>(Map.of("schemaVersion", 1, "kind", "flow", "id", id, "version", "1.0.0", "from", Map.of("endpoint", "in"),
                "delivery", Map.of("mode", mode), "limits", Map.of("maxBodyBytes", 4096, "maxInFlight", 32, "maxInFlightBytes", 32 * 4096),
                "retry", Map.of("attempts", 1, "initial", "1s", "max", "1s"), "steps", steps));
        return FlowDefinition.from(doc);
    }

    static Targets targets(FakeRuntime rt, int maxCalls) {
        return new Targets(new Targets.Limit(maxCalls, WAIT), Map.of(), rt.timer());
    }

    Envelope message(String flow) {
        return rt.ingress(flow, Headers.empty(), Buffer.buffer("<x/>"));
    }

    static <T> CompletableFuture<T> onVirtualThread(Callable<T> work) {
        CompletableFuture<T> done = new CompletableFuture<>();
        Thread.ofVirtual().name("message").start(() -> {
            try {
                done.complete(work.call());
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        return done;
    }

    static CompletableFuture<Envelope> run(Pipeline p, Envelope in) {
        return onVirtualThread(() -> p.apply(in));
    }

    static Throwable failureOf(CompletableFuture<?> f) throws Exception {
        try {
            Object value = f.get(5, TimeUnit.SECONDS);
            throw new AssertionError("expected a failure, got " + value);
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    static NackException busy(Throwable failure) {
        NackException busy = assertInstanceOf(NackException.class, failure, "busy past maxWait is a NackException: " + failure);
        assertTrue(busy.retryable(), "busy is retryable: " + busy.reason());
        assertTrue(busy.reason().contains("busy"), busy.reason());
        return busy;
    }

    /**
     * Red today: two flows naming one target each run two messages; with a limit of 2 for that target at most 2 sends are out
     * at the partner at once (today 4). The two others wait and are sent as the first ones are answered; one gauge is listed.
     */
    @Test
    void twoFlowsNamingOneTargetShareItsLimit() throws Exception {
        Targets targets = targets(rt, 2);
        FlowBuilder b = builder().targets(targets);
        Pipeline one = b.build(flow("one", "sync", List.of(sendStep("a", false))), NO_FILES);
        Pipeline two = b.build(flow("two", "sync", List.of(sendStep("b", false))), NO_FILES);
        partner.holdNext(4);

        List<CompletableFuture<Envelope>> runs = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            runs.add(run(one, message("one")));
            runs.add(run(two, message("two")));
        }
        until(() -> partner.sent.size() + targets.of(SHARED).gauge().waiting() == 4,
                () -> "every message sent or waiting: sent " + partner.sentIds() + ", " + targets.of(SHARED).gauge());

        assertEquals(2, partner.peak.get(), "sends in flight to the one target across both flows");
        assertEquals(2, partner.sent.size(), "two sent, two waiting");
        partner.release(0);
        partner.release(1);
        until(() -> partner.sent.size() == 4, () -> "the waiting two were sent once the first two were answered: " + partner.sentIds());
        partner.releaseAll();
        for (CompletableFuture<Envelope> r : runs) assertTrue(r.get(5, TimeUnit.SECONDS).header("replied-by").isPresent());
        assertEquals(2, partner.peak.get(), "never more than the limit in flight");
        assertEquals(List.of(SHARED), targets.gauges().stream().map(g -> g.target()).toList(), "one limit for the key both endpoints name");
        assertEquals(0, targets.gauges().get(0).inFlight());
    }

    /**
     * A sync flow whose target is busy past maxWait (the Runtime's timer, not the wall clock) answers a failed future with a
     * retryable downstream NackException naming the target; nothing of it reached the partner.
     */
    @Test
    void busyTargetInSyncFlowFailsRetryableDownstream() throws Exception {
        Targets targets = targets(rt, 1);
        FlowBuilder b = builder().targets(targets);
        Pipeline holder = b.build(flow("holder", "sync", List.of(sendStep("a", false))), NO_FILES);
        Delivery sync = b.delivery(flow("sync", "sync", List.of(sendStep("b", false))), NO_FILES);
        partner.holdNext(1);
        CompletableFuture<Envelope> holding = run(holder, message("holder"));
        until(() -> partner.inFlight.get() == 1, () -> "the holder's send is out: " + partner.sentIds());

        Envelope in = message("sync");
        CompletableFuture<Future<Ack>> answered = DeliveryTest.applyOnVirtualThread(sync, in);
        until(() -> targets.of(SHARED).gauge().waiting() == 1 && rt.timer().pending() > 0,
                () -> "the sync message waits for the target, its maxWait armed: " + targets.of(SHARED).gauge() + ", pending " + rt.timer().pending());
        rt.clock().advance(WAIT.minusMillis(1));
        // a due refusal runs on this thread, inside advance: checked synchronously, not through the message's thread
        assertEquals(0, targets.of(SHARED).gauge().refused(), "not refused before maxWait");
        assertEquals(1, targets.of(SHARED).gauge().waiting(), "still waiting at maxWait - 1 ms");
        rt.clock().advance(Duration.ofMillis(1));
        assertEquals(1, targets.of(SHARED).gauge().refused(), "refused at exactly maxWait");

        Future<Ack> answer = answered.get(5, TimeUnit.SECONDS);
        assertTrue(answer.failed(), "a sync flow's failed step fails the answer: " + answer);
        NackException busy = busy(answer.cause());
        assertEquals(Fault.Kind.DOWNSTREAM, busy.kind());
        assertTrue(busy.reason().startsWith("target " + SHARED + " busy: 1 calls in flight, waited "), busy.reason());
        assertTrue(busy.reason().endsWith("waited PT5S"), busy.reason());
        assertFalse(partner.sentIds().contains(in.id()), "nothing of the refused message was sent");
        assertEquals(1, targets.of(SHARED).gauge().refused());
        partner.releaseAll();
        assertTrue(holding.get(5, TimeUnit.SECONDS).header("replied-by").isPresent());
    }

    /**
     * At-least-once: busy past maxWait is a failed attempt of the retryable {@code to} — retried after the backoff, during which
     * the message waits for nothing — and once the retries are spent the message is parked at {@code to} with the busy Nack.
     */
    @Test
    void busyTargetAtLeastOnceRetriedThenParkedAtTo() throws Exception {
        Targets targets = targets(rt, 1);
        InMemoryDlq dlq = new InMemoryDlq();
        FlowBuilder b = builder().targets(targets).dlq(dlq);
        Pipeline holder = b.build(flow("holder", "sync", List.of(sendStep("a", false))), NO_FILES);
        Pipeline queued = b.build(flow("queued", "at-least-once", List.of(Map.of("type", "mark", "name", "m"), sendStep("b", true))), NO_FILES);
        partner.holdNext(1);
        CompletableFuture<Envelope> holding = run(holder, message("holder"));
        until(() -> partner.inFlight.get() == 1, () -> "the holder's send is out: " + partner.sentIds());

        Envelope in = message("queued");
        CompletableFuture<Envelope> running = run(queued, in);
        var gauge = (java.util.function.Supplier<String>) () -> targets.of(SHARED).gauge().toString();
        until(() -> targets.of(SHARED).gauge().waiting() == 1 && rt.timer().pending() > 0, () -> "attempt 1 waits, its maxWait armed: " + gauge.get());
        rt.clock().advance(WAIT);
        until(() -> targets.of(SHARED).gauge().refused() == 1 && rt.timer().pending() > 0 && rt.timer().nextDelay().equals(Duration.ofSeconds(1)),
                () -> "attempt 1 refused, the retry's backoff scheduled: " + gauge.get());
        assertEquals(0, targets.of(SHARED).gauge().waiting(), "the backoff waits for no permit");
        assertFalse(running.isDone(), "one retry left: not parked yet");
        assertEquals(0, dlq.size());

        rt.clock().advance(Duration.ofSeconds(1));
        until(() -> targets.of(SHARED).gauge().waiting() == 1 && rt.timer().pending() > 0, () -> "attempt 2 waits, its maxWait armed: " + gauge.get());
        rt.clock().advance(WAIT);

        assertInstanceOf(ParkedException.class, failureOf(running), "retries spent: parked");
        Parked parked = dlq.get(in.id()).await().orElseThrow(() -> new AssertionError("no DLQ row for " + in.id()));
        assertEquals("send", parked.stepName(), "parked at the to step");
        assertEquals(2, parked.step());
        assertTrue(parked.error().contains(NackException.class.getName()) && parked.error().contains("busy"), parked.error());
        assertEquals(2, targets.of(SHARED).gauge().refused(), "both attempts refused");
        assertFalse(partner.sentIds().contains(in.id()), "nothing of the parked message was sent");
        partner.releaseAll();
        holding.get(5, TimeUnit.SECONDS);
    }

    /**
     * The limit wraps the send inside Retry: a send that failed gives its permit back before the backoff, so another flow's send
     * to the same target (limit 1) goes through while the first message waits for its retry.
     */
    @Test
    void backoffHoldsNoPermit() throws Exception {
        Targets targets = targets(rt, 1);
        FlowBuilder b = builder().targets(targets);
        Pipeline retrying = b.build(flow("retrying", "sync", List.of(sendStep("a", true))), NO_FILES);
        Pipeline other = b.build(flow("other", "sync", List.of(sendStep("b", false))), NO_FILES);
        partner.failNext(1);

        Envelope first = message("retrying");
        CompletableFuture<Envelope> retried = run(retrying, first);
        until(() -> partner.sent.size() == 1 && rt.timer().pending() > 0 && rt.timer().nextDelay().equals(Duration.ofSeconds(1)),
                () -> "the first send failed and the retry's backoff is scheduled: " + partner.sentIds() + ", pending " + rt.timer().pending());
        assertEquals(0, targets.of(SHARED).gauge().inFlight(), "no permit is held across the backoff");

        Envelope meanwhile = message("other");
        assertEquals("b", run(other, meanwhile).get(5, TimeUnit.SECONDS).header("replied-by").orElseThrow(),
                "the other flow's send went through during the backoff, without the clock moving");

        rt.clock().advance(Duration.ofSeconds(1));
        assertEquals("a", retried.get(5, TimeUnit.SECONDS).header("replied-by").orElseThrow(), "the retry succeeded");
        assertEquals(List.of(first.id(), meanwhile.id(), first.id()), partner.sentIds());
    }

    /**
     * A target saturated by a slow partner (its one send held, one more waiting) leaves a flow to another target alone: that
     * flow's send is answered at once.
     */
    @Test
    void slowTargetLeavesAnotherTargetsFlowUnaffected() throws Exception {
        Targets targets = targets(rt, 1);
        FlowBuilder b = builder().targets(targets);
        Pipeline slow = b.build(flow("slow", "sync", List.of(sendStep("slow", false))), NO_FILES);
        Pipeline fast = b.build(flow("fast", "sync", List.of(sendStep("fast", false))), NO_FILES);
        partner.holdNext(1);
        CompletableFuture<Envelope> held = run(slow, message("slow"));
        until(() -> partner.inFlight.get() == 1, () -> "the slow send is out: " + partner.sentIds());
        CompletableFuture<Envelope> queuedBehind = run(slow, message("slow"));
        until(() -> targets.of("partner://slow:1").gauge().waiting() == 1, () -> "a second slow message waits: " + targets.of("partner://slow:1").gauge());

        Envelope in = message("fast");
        assertEquals("fast", run(fast, in).get(5, TimeUnit.SECONDS).header("replied-by").orElseThrow(), "the other target's flow is answered at once");
        assertEquals(0, targets.of("partner://fast:1").gauge().waiting());
        assertEquals(1, targets.of("partner://slow:1").gauge().waiting(), "the slow target is still saturated");
        partner.releaseAll();
        held.get(5, TimeUnit.SECONDS);
        partner.releaseAll();
        queuedBehind.get(5, TimeUnit.SECONDS);
    }

    /**
     * A component that names no target: each {@code to} on it gets a limit of its own with the defaults — not shared with
     * another flow's {@code to} on the same endpoint, but finite (the second message waits and is refused past maxWait) —
     * and not listed among the gauges.
     */
    @Test
    void componentWithoutTargetGetsOwnFiniteLimit() throws Exception {
        Targets targets = targets(rt, 1);
        FlowBuilder b = builder().targets(targets);
        Pipeline p = b.build(flow("p", "sync", List.of(sendStep("p", false))), NO_FILES);
        Pipeline q = b.build(flow("q", "sync", List.of(sendStep("p", false))), NO_FILES);
        plain.holdNext(1);
        CompletableFuture<Envelope> held = run(p, message("p"));
        until(() -> plain.inFlight.get() == 1, () -> "p's send is out: " + plain.sentIds());

        Envelope other = message("q");
        assertEquals("p", run(q, other).get(5, TimeUnit.SECONDS).header("replied-by").orElseThrow(), "q's to has a limit of its own: answered at once");

        Envelope second = message("p");
        CompletableFuture<Envelope> waiting = run(p, second);
        until(() -> rt.timer().pending() > 0, () -> "p's second message waits on its own limit's maxWait: sent " + plain.sentIds());
        rt.clock().advance(WAIT);
        busy(failureOf(waiting));
        assertFalse(plain.sentIds().contains(second.id()), "the refused message was not sent");
        assertEquals(List.of(), targets.gauges(), "unshared limits are not listed");
        plain.releaseAll();
        held.get(5, TimeUnit.SECONDS);
    }

    /** Without {@code targets(...)} the builder limits a target to the defaults: 16 sends in flight, the 17th refused after 5 s on the Runtime's timer. */
    @Test
    void withoutTargetsTheBuilderAllowsSixteenAndWaitsFiveSecondsOnTheRuntimeTimer() throws Exception {
        Pipeline p = builder().build(flow("many", "sync", List.of(sendStep("a", false))), NO_FILES);
        partner.holdNext(17);
        List<CompletableFuture<Envelope>> runs = new ArrayList<>();
        for (int i = 0; i < 17; i++) runs.add(run(p, message("many")));
        until(() -> partner.sent.size() == 16 && rt.timer().pending() > 0, () -> "16 sent, the 17th waits: " + partner.sent.size() + " sent, pending " + rt.timer().pending());
        assertEquals(1, rt.timer().pending(), "fixture: only the 17th's maxWait is armed");
        rt.clock().advance(Duration.ofSeconds(5).minusMillis(1));
        // the timer runs a due refusal on this thread, inside advance: a maxWait shorter than 5 s has fired by now
        assertEquals(1, rt.timer().pending(), "the 17th's maxWait is still armed at 5 s - 1 ms: not refused early");
        assertEquals(16, partner.sent.size());
        rt.clock().advance(Duration.ofMillis(1));
        assertEquals(0, rt.timer().pending(), "the 17th's maxWait fired at exactly 5 s");
        until(() -> runs.stream().anyMatch(CompletableFuture::isDone), () -> "the 17th refused at 5 s");
        CompletableFuture<Envelope> refused = runs.stream().filter(CompletableFuture::isDone).findFirst().orElseThrow();
        NackException busy = busy(failureOf(refused));
        assertTrue(busy.reason().contains(" 16 calls in flight"), busy.reason());
        assertTrue(busy.reason().endsWith("waited PT5S"), "the default maxWait is 5 s: " + busy.reason());
        assertEquals(16, partner.peak.get());
        partner.releaseAll();
        for (CompletableFuture<Envelope> r : runs) if (r != refused) r.get(5, TimeUnit.SECONDS);
    }

    /** A {@code to} by prop resolves the limit of each endpoint it may pick: the chosen endpoint's target bounds the send. */
    @Test
    void toByPropSendsUnderTheChosenEndpointsTarget() throws Exception {
        Targets targets = targets(rt, 1);
        FlowBuilder b = builder().targets(targets);
        Pipeline holder = b.build(flow("holder", "sync", List.of(sendStep("a", false))), NO_FILES);
        Pipeline routed = b.build(flow("routed", "sync", List.of(Map.of("type", "choose", "name", "c"),
                Map.of("type", "to", "name", "send", "byProp", "partner", "endpoints", Map.of("shared", "b", "fast", "fast")))), NO_FILES);
        partner.holdNext(1);
        CompletableFuture<Envelope> holding = run(holder, message("holder"));
        until(() -> partner.inFlight.get() == 1, () -> "the holder's send is out: " + partner.sentIds());

        Envelope in = message("routed");
        CompletableFuture<Envelope> waiting = run(routed, in);
        until(() -> targets.of(SHARED).gauge().waiting() == 1, () -> "the routed message waits for the shared target: " + targets.of(SHARED).gauge());
        assertFalse(partner.sentIds().contains(in.id()));
        partner.release(0);
        assertEquals("b", waiting.get(5, TimeUnit.SECONDS).header("replied-by").orElseThrow());
        holding.get(5, TimeUnit.SECONDS);
    }

    /**
     * A replay runs the flow's {@code to} under the same per-target limit: while another flow holds the target's one permit the
     * replay waits and sends nothing; once that send is answered the replay is sent.
     */
    @Test
    void replayCountsAgainstTarget() throws Exception {
        Targets targets = targets(rt, 1);
        FlowBuilder b = builder().targets(targets).recording(new InMemoryMessageStore(), () -> 0);
        FlowDefinition def = flow("r", "sync", List.of(Map.of("type", "mark", "name", "m"), sendStep("b", false)));
        Envelope original = message("r");
        run(b.build(def, NO_FILES), original).get(5, TimeUnit.SECONDS);
        assertEquals(List.of(original.id()), partner.sentIds(), "fixture: the original was sent and recorded");

        Pipeline holder = b.build(flow("holder", "sync", List.of(sendStep("a", false))), NO_FILES);
        partner.holdNext(1);
        CompletableFuture<Envelope> holding = run(holder, message("holder"));
        until(() -> partner.inFlight.get() == 1, () -> "the holder's send is out: " + partner.sentIds());

        Replay replay = b.replay(def, NO_FILES);
        CompletableFuture<Envelope> replaying = onVirtualThread(() -> replay.from(original.id(), 2, "r-1"));
        until(() -> targets.of(SHARED).gauge().waiting() == 1, () -> "the replay waits for the target: " + targets.of(SHARED).gauge());
        assertFalse(partner.sentIds().contains("r-1"), "nothing of the replay is sent while the target is busy");

        partner.release(0);
        assertEquals("r-1", replaying.get(5, TimeUnit.SECONDS).id());
        assertTrue(partner.sentIds().contains("r-1"), "the replay was sent once the permit was free");
        holding.get(5, TimeUnit.SECONDS);
        replay.close().await(5, TimeUnit.SECONDS);
        if (partner.peak.get() > 1) fail("more than the limit in flight: " + partner.peak.get());
    }
}
