package io.flottila.core.flow;

import io.flottila.api.Ack;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.flow.ClosingPartner.CloseFailure;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * FIX-30: what {@link FlowBuilder} returns for a flow — its {@link Delivery} (sync, at-most-once: the pipeline with its
 * producers) and its {@link FlowWorker} (at-least-once: the pipeline with its producers) — has a {@code close()} that closes
 * every producer the builder created for it, once each, and a delivery's close only once its stop has completed. A producer
 * whose close fails is logged at WARNING and the others close all the same. A flow that does not build closes the producers
 * it created before it failed: nothing it built is left open.
 *
 * <p>The shape these tests assume (FIX-30 test-author): {@code Future<Void> Delivery.close()} and {@code Future<Void>
 * FlowWorker.close()}. The producers are a {@link ClosingPartner}'s, which counts every close and runs a hook inside it.
 */
class FlowCloseTest {

    static final Path NO_FILES = Path.of("");
    static final Duration BOUND = Duration.ofSeconds(10);

    final FakeRuntime rt = FakeRuntime.create();
    final ClosingPartner partner = new ClosingPartner();
    final InMemoryMessageStore store = new InMemoryMessageStore();
    final InMemoryWorkQueue queue = new InMemoryWorkQueue(rt.clock());
    final InMemoryDlq dlq = new InMemoryDlq();
    final List<CountDownLatch> holds = new CopyOnWriteArrayList<>();
    final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    /** Strongly held: JUL keeps loggers weakly, and a collected logger loses its handler. */
    final Logger flottila = Logger.getLogger("io.flottila");
    final Handler capture = new Handler() {
        @Override public void publish(LogRecord r) { logged.add(r); }
        @Override public void flush() {}
        @Override public void close() {}
    };

    { rt.withComponent(partner); }

    @BeforeEach
    void listen() {
        capture.setLevel(Level.ALL);
        flottila.addHandler(capture);
    }

    @AfterEach
    void releaseAndUnlisten() {
        holds.forEach(CountDownLatch::countDown);
        flottila.removeHandler(capture);
    }

    // --- fixtures

    /** Four out endpoints of the scheme {@code mock}. */
    static final Map<String, EndpointConfig> ENDPOINTS = Map.of(
            "partner", new EndpointConfig("partner", URI.create("mock://partner"), Map.of()),
            "audit", new EndpointConfig("audit", URI.create("mock://audit"), Map.of()),
            "a", new EndpointConfig("a", URI.create("mock://a"), Map.of()),
            "b", new EndpointConfig("b", URI.create("mock://b"), Map.of()));

    /** route (sets PARTNER), to partner, to audit, to byProp PARTNER → a | b: four producers per pipeline built. */
    static final List<Map<String, Object>> FOUR_PRODUCERS = List.of(
            Map.of("type", "route", "name", "route", "language", "header", "prop", "PARTNER",
                    "routes", List.of(Map.of("when", "id=4711", "set", "a")), "otherwise", "b"),
            Map.of("type", "to", "name", "toPartner", "endpoint", "partner"),
            Map.of("type", "to", "name", "toAudit", "endpoint", "audit"),
            Map.of("type", "to", "name", "toChosen", "byProp", "PARTNER", "endpoints", Map.of("a", "a", "b", "b")));

    /** A flow file as core reads it: id {@code orders}, a 64 KiB body limit, the given steps. */
    static FlowDefinition flow(String mode, List<Map<String, Object>> steps) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("schemaVersion", 1);
        doc.put("kind", "flow");
        doc.put("id", "orders");
        doc.put("version", "0.1.0");
        doc.put("from", Map.of("endpoint", "in"));
        doc.put("delivery", Map.of("mode", mode));
        doc.put("limits", Map.of("maxBodyBytes", 65_536));
        doc.put("steps", steps);
        return FlowDefinition.from(doc);
    }

    FlowBuilder builder() {
        return new FlowBuilder(rt.runtime(), List.of(), List.of(BuiltInStepsTest.HEADERS), ENDPOINTS)
                .recording(store, Meter.system()).queue(queue).dlq(dlq);
    }

    Envelope ingress() {
        return rt.ingress("orders", Headers.empty().with("id", "4711").with(Envelope.H_CONTENT_TYPE, "application/xml"), Buffer.buffer("<order/>"));
    }

    /** Applies {@code delivery} on a virtual thread of its own (a sync pipeline runs on the caller's thread and parks there). */
    static CompletableFuture<Future<Ack>> applyOnVirtualThread(Delivery delivery, Envelope in) {
        CompletableFuture<Future<Ack>> applied = new CompletableFuture<>();
        Thread.ofVirtual().name("handler").start(() -> {
            try {
                applied.complete(delivery.apply(in));
            } catch (Throwable failure) {
                applied.completeExceptionally(failure);
            }
        });
        return applied;
    }

    static <T> T within(Future<T> f, String what) throws Exception {
        try {
            return f.await(BOUND.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException stuck) {
            return fail(what + ": not within " + BOUND);
        }
    }

    /** Spins (never sleeps) until {@code condition} holds; fails after 20 s naming what never happened. */
    static void until(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("not within 20 s: " + what);
            Thread.onSpinWait();
            Thread.yield();
        }
    }

    void assertEveryProducerClosedOnce(int built, String what) {
        assertEquals(built, partner.built().size(), "fixture: " + what + " built " + built + " producers: " + partner.built());
        assertEquals(List.of(), partner.open(), what + ": every producer the builder created is closed: " + partner.built());
        assertEquals(List.of(), partner.closedTwice(), what + ": each one once: " + partner.built());
    }

    /** Every text a log record carries: its message, and the message of every throwable in its thrown chain and their suppressed. */
    static List<String> texts(LogRecord r) {
        List<String> texts = new ArrayList<>();
        texts.add(String.valueOf(r.getMessage()));
        List<Throwable> todo = new ArrayList<>();
        if (r.getThrown() != null) todo.add(r.getThrown());
        while (!todo.isEmpty()) {
            Throwable t = todo.removeFirst();
            texts.add(String.valueOf(t.getMessage()));
            if (t.getCause() != null) todo.add(t.getCause());
            todo.addAll(List.of(t.getSuppressed()));
        }
        return texts;
    }

    // --- a delivery's close

    /** A stopped sync or at-most-once delivery's close closes each of the four producers its pipeline was built with, once; a second close closes nothing again. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"sync", "at-most-once"})
    void closeAfterStopClosesEveryProducerOfTheDeliveryOnce(String mode) throws Exception {
        Delivery delivery = builder().delivery(flow(mode, FOUR_PRODUCERS), NO_FILES);
        assertEquals(4, partner.built().size(), "fixture: the delivery's pipeline has four producers");
        within(delivery.stop(Duration.ZERO), "the idle delivery's stop");
        assertEquals(List.of(), partner.built().stream().filter(o -> o.closes() > 0).toList(), "stopping a delivery closes no producer");

        within(delivery.close(), "the close");
        assertEveryProducerClosedOnce(4, mode + " delivery");

        within(delivery.close(), "a second close");
        assertEquals(List.of(), partner.closedTwice(), "a second close closes no producer again: " + partner.built());
    }

    /**
     * A delivery's close called while its stop still waits for a message in flight (held at the partner) closes nothing yet:
     * every producer is closed only after the stop completed — the hook inside each producer's close sees the stop complete —
     * and the close completes after that.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"sync", "at-most-once"})
    void closeWaitsForTheDeliveryStopBeforeClosingAnyProducer(String mode) throws Exception {
        Delivery delivery = builder().delivery(flow(mode, FOUR_PRODUCERS), NO_FILES);
        CountDownLatch release = partner.partner().holdNext(1);
        holds.add(release);
        CompletableFuture<Future<Ack>> applied = applyOnVirtualThread(delivery, ingress());
        partner.partner().awaitSent(1);
        Future<Void> stop = delivery.stop(Duration.ofSeconds(5));   // on the fake timer, never advanced: only the flight's end completes it
        List<Boolean> stopCompleteAtClose = new CopyOnWriteArrayList<>();
        partner.onClose(o -> stopCompleteAtClose.add(stop.isComplete()));
        assertFalse(stop.isComplete(), "fixture: the stop waits for the message held at the partner");

        Future<Void> close = delivery.close();

        assertEquals(List.of(), partner.built().stream().filter(o -> o.closes() > 0).toList(), "no producer is closed while the delivery's stop waits");
        assertFalse(close.isComplete(), "the close waits for the stop");
        release.countDown();
        Ack answer = within(applied.get(BOUND.toMillis(), TimeUnit.MILLISECONDS), "the held message's answer");
        if (mode.equals("sync")) assertInstanceOf(Ack.Reply.class, answer, "the message in flight drained: " + answer);
        within(stop, "the stop once the message drained");
        within(close, "the close once the stop completed");
        assertEveryProducerClosedOnce(4, mode + " delivery");
        assertEquals(List.of(true, true, true, true), stopCompleteAtClose, "every producer was closed after the delivery's stop completed");
    }

    // --- a worker's close

    /**
     * An at-least-once flow's producers are its worker's: the stopped worker's close closes each once, a second closes nothing
     * again; its delivery builds no producer, and its close (after its stop) completes closing none.
     */
    @Test
    void closeAfterStopClosesEveryProducerOfTheWorkerOnce() throws Exception {
        FlowBuilder b = builder();
        FlowDefinition def = flow("at-least-once", FOUR_PRODUCERS);
        Delivery delivery = b.delivery(def, NO_FILES);
        assertEquals(0, partner.built().size(), "fixture: an at-least-once delivery only saves: no producer");
        FlowWorker worker = b.worker(def, NO_FILES, "w1");
        assertEquals(4, partner.built().size(), "fixture: the worker's pipeline has four producers");

        within(delivery.stop(Duration.ZERO), "the delivery's stop");
        within(delivery.close(), "the delivery's close");
        assertEquals(List.of(), partner.built().stream().filter(o -> o.closes() > 0).toList(), "the delivery's close closes none of the worker's producers");
        worker.stop();
        within(worker.close(), "the worker's close");
        assertEveryProducerClosedOnce(4, "at-least-once worker");

        within(worker.close(), "a second close");
        assertEquals(List.of(), partner.closedTwice(), "a second close closes no producer again: " + partner.built());
    }

    // --- a close that fails

    static Stream<Arguments> owners() {
        List<Arguments> rows = new ArrayList<>();
        for (String owner : List.of("sync delivery", "at-most-once delivery", "at-least-once worker")) {
            for (CloseFailure how : CloseFailure.values()) rows.add(Arguments.of(owner, how));
        }
        return rows.stream();
    }

    /**
     * Every producer's close fails (a failed future, or a throw from close itself): each one is still called, once — one
     * failure stops no other, whatever the order — and each failure is logged at WARNING with its cause's text.
     */
    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("owners")
    void aProducerCloseFailureIsLoggedAndTheOthersStillClose(String owner, CloseFailure how) throws Exception {
        for (int n = 1; n <= 4; n++) partner.failClose(n, how, "lid stuck #" + n);
        FlowBuilder b = builder();
        Future<Void> close;
        if (owner.endsWith("worker")) {
            FlowWorker worker = b.worker(flow("at-least-once", FOUR_PRODUCERS), NO_FILES, "w1");
            worker.stop();
            close = worker.close();
        } else {
            Delivery delivery = b.delivery(flow(owner.replace(" delivery", ""), FOUR_PRODUCERS), NO_FILES);
            within(delivery.stop(Duration.ZERO), "the idle delivery's stop");
            close = delivery.close();
        }

        until(close::isComplete, "the close completes, whatever its outcome (FIX-30 pins the log, not the outcome)");
        assertEveryProducerClosedOnce(4, owner);
        for (int n = 1; n <= 4; n++) {
            String text = "lid stuck #" + n;
            assertTrue(logged.stream().anyMatch(r -> r.getLevel() == Level.WARNING && texts(r).stream().anyMatch(t -> t.contains(text))),
                    "the failed close of producer #" + n + " is logged at WARNING with its cause (" + text + "): "
                            + logged.stream().map(r -> r.getLevel() + " " + texts(r)).toList());
        }
    }

    // --- a flow that does not build

    /**
     * A flow whose second step does not build (an unknown step type) after its {@code to} step created a producer: the build
     * fails, and the producer it created is closed — nothing the failed build made is left open.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Built.class)
    void aFlowThatDoesNotBuildClosesTheProducersItCreated(Built what) {
        List<Map<String, Object>> steps = List.of(
                Map.of("type", "to", "name", "toPartner", "endpoint", "partner"),
                Map.of("type", "frobnicate", "name", "f"));
        FlowBuilder b = builder();
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> {
            switch (what) {
                case SYNC_DELIVERY -> b.delivery(flow("sync", steps), NO_FILES);
                case AT_MOST_ONCE_DELIVERY -> b.delivery(flow("at-most-once", steps), NO_FILES);
                case AT_LEAST_ONCE_WORKER -> b.worker(flow("at-least-once", steps), NO_FILES, "w1");
            }
        }, "the flow does not build");
        assertTrue(refused.getMessage().contains("frobnicate"), "fixture: it fails at the unknown step: " + refused.getMessage());
        assertEveryProducerClosedOnce(1, what.name() + " whose build failed (by the time the failure is thrown)");
    }

    enum Built { SYNC_DELIVERY, AT_MOST_ONCE_DELIVERY, AT_LEAST_ONCE_WORKER }
}
