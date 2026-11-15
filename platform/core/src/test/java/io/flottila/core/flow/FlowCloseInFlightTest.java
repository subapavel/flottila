package io.flottila.core.flow;

import io.flottila.api.Ack;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static io.flottila.core.flow.FlowCloseTest.ENDPOINTS;
import static io.flottila.core.flow.FlowCloseTest.FOUR_PRODUCERS;
import static io.flottila.core.flow.FlowCloseTest.NO_FILES;
import static io.flottila.core.flow.FlowCloseTest.flow;
import static io.flottila.core.flow.FlowCloseTest.until;
import static io.flottila.core.flow.FlowCloseTest.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-30 round 2: a {@link FlowWorker}'s close closes its producers only once no item it claimed still runs — while one runs
 * the close stays pending and no producer is closed, so the item's sends go through and it is completed by its own success,
 * never parked because its producer was closed under it. And a close (a delivery's, a worker's) completes only once every
 * producer's close has settled: one that stays pending keeps the close pending.
 *
 * <p>The producers are a {@link ClosingPartner}'s: like a real producer each refuses a send made after its close, so a run
 * that outlives the close fails its next step and is parked — the DLQ row this class asserts never appears.
 */
class FlowCloseInFlightTest {

    final FakeRuntime rt = FakeRuntime.create();
    final ClosingPartner partner = new ClosingPartner();
    final InMemoryMessageStore store = new InMemoryMessageStore();
    final InMemoryWorkQueue rows = new InMemoryWorkQueue(rt.clock());
    final RecordingWorkQueue queue = new RecordingWorkQueue(rows, rt.clock());
    final InMemoryDlq dlq = new InMemoryDlq();
    final List<CountDownLatch> holds = new CopyOnWriteArrayList<>();
    final List<Promise<Void>> heldCloses = new CopyOnWriteArrayList<>();

    { rt.withComponent(partner); }

    @AfterEach
    void releaseWhatACaseHeld() {
        holds.forEach(CountDownLatch::countDown);
        queue.releaseHeld();
        heldCloses.forEach(Promise::tryComplete);
    }

    FlowBuilder builder() {
        return new FlowBuilder(rt.runtime(), List.of(), List.of(BuiltInStepsTest.HEADERS), ENDPOINTS)
                .recording(store, Meter.system()).queue(queue).dlq(dlq);
    }

    /** id 4711: the route step picks endpoint {@code a}. */
    Envelope ingress() {
        return rt.ingress("orders", Headers.empty().with("id", "4711").with(Envelope.H_CONTENT_TYPE, "application/xml"), Buffer.buffer("<order/>"));
    }

    /** A message through the flow's at-least-once delivery: saved and queued, nothing run. */
    Envelope accept(Delivery delivery) throws Exception {
        Envelope in = ingress();
        assertEquals(new Ack.Accepted(in.id()), within(delivery.apply(in), "the at-least-once accept"), "fixture: the message is accepted");
        assertEquals(1, rows.size(), "fixture: one work item queued");
        return in;
    }

    List<String> sentIds(String endpoint) {
        return partner.partner().sentTo(endpoint).stream().map(Envelope::id).toList();
    }

    List<ClosingPartner.Out> closed() {
        return partner.built().stream().filter(o -> o.closes() > 0).toList();
    }

    /** The run of {@code id} went through all three of its sends, the item was completed by that success, nothing was parked. */
    void assertCompletedByItsOwnSuccess(Envelope in) {
        assertEquals(List.of(in.id()), sentIds("partner"), "the held send went through");
        assertEquals(List.of(in.id()), sentIds("audit"), "the run went on past the held send: its next send succeeded");
        assertEquals(List.of(in.id()), sentIds("a"), "and its last one");
        assertEquals(0, dlq.size(), "the item was not parked: no DLQ row");
        assertEquals(0, rows.size(), "the item was completed by its own success");
    }

    void assertEveryProducerClosedOnce() {
        assertEquals(4, partner.built().size(), "fixture: the worker's pipeline has four producers: " + partner.built());
        assertEquals(List.of(), partner.open(), "every producer is closed: " + partner.built());
        assertEquals(List.of(), partner.closedTwice(), "each once: " + partner.built());
    }

    // --- a worker's close waits for what it runs

    /**
     * A worker item is held at the partner (its first send); the worker is stopped and closed: the close stays pending and no
     * producer is closed while the item runs. Released, the item's sends succeed — no DLQ row, it is not parked, it is
     * completed — and only then is each producer closed, once, after the item's run went through all its sends, and the close
     * completes.
     */
    @Test
    void aWorkerCloseWaitsForItsRunningItemBeforeClosingAnyProducer() throws Exception {
        FlowBuilder b = builder();
        FlowDefinition def = flow("at-least-once", FOUR_PRODUCERS);
        Delivery delivery = b.delivery(def, NO_FILES);
        FlowWorker worker = b.worker(def, NO_FILES, "w1");
        Envelope in = accept(delivery);
        CountDownLatch release = partner.partner().holdNext(1);
        holds.add(release);
        Future<Integer> round = worker.poll();
        partner.partner().awaitSent(1);
        List<Integer> sendsDoneAtClose = new CopyOnWriteArrayList<>();
        partner.onClose(o -> sendsDoneAtClose.add(sentIds("partner").size() + sentIds("audit").size() + sentIds("a").size()));

        worker.stop();
        Future<Void> close = worker.close();

        assertEquals(List.of(), closed(), "no producer is closed while the worker's item runs: " + partner.built());
        assertFalse(close.isComplete(), "the close stays pending while the item runs");
        release.countDown();
        assertEquals(1, within(round, "the round of the released item"), "fixture: the round ran one item");
        within(close, "the close once the item finished");
        assertCompletedByItsOwnSuccess(in);
        assertEveryProducerClosedOnce();
        assertEquals(List.of(3, 3, 3, 3), sendsDoneAtClose, "each producer was closed after the item's run went through all three sends");
    }

    /**
     * The worker's claim is in flight (held at the queue) when it is stopped and closed: its items run all the same
     * ({@link FlowWorker#stop()}), so the close closes nothing while the claim is out; once it returns, the item runs to its
     * success through open producers, then each producer is closed once and the close completes.
     */
    @Test
    void aWorkerCloseWaitsForTheItemsOfAClaimInFlight() throws Exception {
        FlowBuilder b = builder();
        FlowDefinition def = flow("at-least-once", FOUR_PRODUCERS);
        Delivery delivery = b.delivery(def, NO_FILES);
        FlowWorker worker = b.worker(def, NO_FILES, "w1");
        Envelope in = accept(delivery);
        queue.holdNext();
        Future<Integer> round = worker.poll();
        until(() -> queue.claimsInFlight() == 1, "the worker's claim is in flight");

        worker.stop();
        Future<Void> close = worker.close();

        assertEquals(List.of(), closed(), "no producer is closed while the worker's claim is in flight: " + partner.built());
        assertFalse(close.isComplete(), "the close stays pending while the claim is in flight");
        queue.releaseHeld();
        assertEquals(1, within(round, "the round of the released claim"), "fixture: the claim handed out the item");
        within(close, "the close once the claimed item finished");
        assertCompletedByItsOwnSuccess(in);
        assertEveryProducerClosedOnce();
    }

    // --- a close completes once every producer's close settled

    /** A stopped delivery's close calls every producer's close; one of them stays pending: the close stays pending until it settles. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"sync", "at-most-once"})
    void aDeliveryCloseCompletesOnlyOnceEveryProducerCloseSettled(String mode) throws Exception {
        Promise<Void> third = partner.holdClose(3);
        heldCloses.add(third);
        Delivery delivery = builder().delivery(flow(mode, FOUR_PRODUCERS), NO_FILES);
        within(delivery.stop(Duration.ZERO), "the idle delivery's stop");

        Future<Void> close = delivery.close();

        until(() -> partner.open().isEmpty(), "every producer's close is called");
        assertFalse(close.isComplete(), "the close stays pending while producer #3's close has not settled");
        third.complete();
        within(close, "the close once producer #3's close settled");
        assertTrue(close.succeeded(), "the close succeeds: " + close.cause());
    }

    /** A stopped idle worker's close calls every producer's close; one of them stays pending: the close stays pending until it settles. */
    @Test
    void aWorkerCloseCompletesOnlyOnceEveryProducerCloseSettled() throws Exception {
        Promise<Void> third = partner.holdClose(3);
        heldCloses.add(third);
        FlowWorker worker = builder().worker(flow("at-least-once", FOUR_PRODUCERS), NO_FILES, "w1");
        worker.stop();

        Future<Void> close = worker.close();

        until(() -> partner.open().isEmpty(), "every producer's close is called");
        assertFalse(close.isComplete(), "the close stays pending while producer #3's close has not settled");
        third.complete();
        within(close, "the close once producer #3's close settled");
        assertTrue(close.succeeded(), "the close succeeds: " + close.cause());
    }
}
