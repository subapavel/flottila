package io.flottila.core.flow;

import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.flow.ClosingPartner.CloseFailure;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static io.flottila.core.flow.FlowCloseTest.ENDPOINTS;
import static io.flottila.core.flow.FlowCloseTest.FOUR_PRODUCERS;
import static io.flottila.core.flow.FlowCloseTest.NO_FILES;
import static io.flottila.core.flow.FlowCloseTest.flow;
import static io.flottila.core.flow.FlowCloseTest.texts;
import static io.flottila.core.flow.FlowCloseTest.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-30, what {@link FlowCloseTest} leaves open: a close before the stop is refused and closes nothing; a close whose
 * producers fail still succeeds; a {@code to} step that fails on its second endpoint closes the producer it created for its
 * first; a failed build whose producer's close throws still throws the build's own failure.
 */
class FlowCloseEdgeTest {

    final FakeRuntime rt = FakeRuntime.create();
    final ClosingPartner partner = new ClosingPartner();
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
    void unlisten() {
        flottila.removeHandler(capture);
    }

    FlowBuilder builder() {
        return new FlowBuilder(rt.runtime(), List.of(), List.of(BuiltInStepsTest.HEADERS), ENDPOINTS)
                .recording(new InMemoryMessageStore(), Meter.system()).queue(new InMemoryWorkQueue(rt.clock())).dlq(new InMemoryDlq());
    }

    boolean warned(String text) {
        return logged.stream().anyMatch(r -> r.getLevel() == Level.WARNING && texts(r).stream().anyMatch(t -> t.contains(text)));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"sync", "at-most-once"})
    void aDeliveryClosedBeforeItsStopIsRefusedAndClosesNothing(String mode) throws Exception {
        Delivery delivery = builder().delivery(flow(mode, FOUR_PRODUCERS), NO_FILES);

        IllegalStateException refused = assertThrows(IllegalStateException.class, delivery::close);

        assertTrue(refused.getMessage().contains("stop"), refused.getMessage());
        assertEquals(4, partner.open().size(), "no producer is closed by a refused close: " + partner.built());
        within(delivery.stop(Duration.ZERO), "the stop");
        within(delivery.close(), "the close after the stop");
        assertEquals(List.of(), partner.open(), "the close after the stop closes them: " + partner.built());
    }

    @Test
    void aWorkerClosedBeforeItsStopIsRefusedAndClosesNothing() throws Exception {
        FlowWorker worker = builder().worker(flow("at-least-once", FOUR_PRODUCERS), NO_FILES, "w1");

        IllegalStateException refused = assertThrows(IllegalStateException.class, worker::close);

        assertTrue(refused.getMessage().contains("stop"), refused.getMessage());
        assertEquals(4, partner.open().size(), "no producer is closed by a refused close: " + partner.built());
        worker.stop();
        within(worker.close(), "the close after the stop");
        assertEquals(List.of(), partner.open(), "the close after the stop closes them: " + partner.built());
    }

    /** Every producer's close fails: the close still succeeds — the failures are logged, never the close's outcome — and a second close is that same outcome. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(CloseFailure.class)
    void aCloseWhoseProducersFailStillSucceeds(CloseFailure how) throws Exception {
        for (int n = 1; n <= 4; n++) partner.failClose(n, how, "jammed #" + n);
        FlowWorker worker = builder().worker(flow("at-least-once", FOUR_PRODUCERS), NO_FILES, "w1");
        worker.stop();

        Future<Void> close = worker.close();

        within(close, "the close");
        assertTrue(close.succeeded(), "the close succeeds: " + close.cause());
        assertSame(close, worker.close(), "a second close is the first one");
        assertEquals(List.of(), partner.closedTwice(), "each closed once: " + partner.built());
    }

    /** A byProp {@code to} whose second endpoint is unknown: the producer it created for the first is closed before the failure leaves. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"sync", "at-least-once"})
    void aToStepFailingOnItsSecondEndpointClosesTheProducerOfItsFirst(String mode) {
        Map<String, Object> endpoints = new java.util.LinkedHashMap<>();
        endpoints.put("a", "a");
        endpoints.put("b", "nowhere");
        List<Map<String, Object>> steps = List.of(Map.of("type", "to", "name", "toChosen", "byProp", "PARTNER", "endpoints", endpoints));
        FlowBuilder b = builder();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> {
            if (mode.equals("sync")) b.delivery(flow(mode, steps), NO_FILES);
            else b.worker(flow(mode, steps), NO_FILES, "w1");
        });

        assertTrue(refused.getMessage().contains("nowhere"), "fixture: it fails at the unknown endpoint: " + refused.getMessage());
        assertEquals(1, partner.built().size(), "fixture: one producer was created: " + partner.built());
        assertEquals(List.of(), partner.open(), "the producer of the first endpoint is closed: " + partner.built());
    }

    /** A failed build whose producer's close throws: the build's own failure leaves, the close's is logged at WARNING. */
    @Test
    void aFailedBuildWhoseProducerCloseThrowsStillThrowsTheBuildFailure() {
        partner.failClose(1, CloseFailure.THROWN, "lid welded");
        List<Map<String, Object>> steps = List.of(
                Map.of("type", "to", "name", "toPartner", "endpoint", "partner"),
                Map.of("type", "frobnicate", "name", "f"));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> builder().delivery(flow("sync", steps), NO_FILES));

        assertTrue(refused.getMessage().contains("frobnicate"), "the build's own failure leaves: " + refused.getMessage());
        assertEquals(1, partner.built().get(0).closes(), "its producer was closed: " + partner.built());
        assertTrue(warned("lid welded"), "the throwing close is logged at WARNING: " + logged.stream().map(r -> r.getLevel() + " " + texts(r)).toList());
    }
}
