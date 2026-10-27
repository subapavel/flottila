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
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-30 round 2, what {@link FlowCloseInFlightTest} leaves open about a {@link FlowWorker}'s close: a claim in flight that
 * returns nothing, or fails, lets the pending close run at once; with an item running and a claim in flight both, the close
 * waits for the last of them — the item's end alone does not close a producer the claim's item still needs; and every
 * call returns the one close, which completes.
 */
class FlowCloseClaimTest {

    final FakeRuntime rt = FakeRuntime.create();
    final ClosingPartner partner = new ClosingPartner();
    { rt.withComponent(partner); }   // before the builds below
    final InMemoryWorkQueue rows = new InMemoryWorkQueue(rt.clock());
    final RecordingWorkQueue queue = new RecordingWorkQueue(rows, rt.clock());
    final InMemoryDlq dlq = new InMemoryDlq();
    final List<CountDownLatch> holds = new CopyOnWriteArrayList<>();
    final FlowBuilder builder = new FlowBuilder(rt.runtime(), List.of(), List.of(BuiltInStepsTest.HEADERS), ENDPOINTS)
            .recording(new InMemoryMessageStore(), Meter.system()).queue(queue).dlq(dlq);
    final FlowDefinition def = flow("at-least-once", FOUR_PRODUCERS);
    final Delivery delivery = builder.delivery(def, NO_FILES);
    final FlowWorker worker = builder.worker(def, NO_FILES, "w1");

    @AfterEach
    void releaseWhatACaseHeld() {
        holds.forEach(CountDownLatch::countDown);
        queue.releaseHeld();
    }

    /** id 4711: the route step picks endpoint {@code a}; saved and queued, nothing run. */
    Envelope accept() throws Exception {
        Envelope in = rt.ingress("orders", Headers.empty().with("id", "4711").with(Envelope.H_CONTENT_TYPE, "application/xml"), Buffer.buffer("<order/>"));
        assertEquals(new Ack.Accepted(in.id()), within(delivery.apply(in), "the at-least-once accept"), "fixture: the message is accepted");
        return in;
    }

    /** The worker's next claim is held at the queue and in flight; the worker is then stopped and closed. */
    Future<Integer> claimInFlightThenStop() {
        queue.holdNext();
        Future<Integer> round = worker.poll();
        until(() -> queue.claimsInFlight() == 1, "the worker's claim is in flight");
        worker.stop();
        return round;
    }

    void assertEveryProducerClosedOnce() {
        assertEquals(4, partner.built().size(), "fixture: the worker's pipeline has four producers: " + partner.built());
        assertEquals(List.of(), partner.open(), "every producer is closed: " + partner.built());
        assertEquals(List.of(), partner.closedTwice(), "each once: " + partner.built());
    }

    @Test
    void aClaimInFlightThatReturnsNothingLetsThePendingCloseRun() throws Exception {
        Future<Integer> round = claimInFlightThenStop();
        Future<Void> close = worker.close();
        assertEquals(4, partner.open().size(), "no producer is closed while the claim is in flight: " + partner.built());
        assertFalse(close.isComplete(), "the close stays pending while the claim is in flight");

        queue.releaseHeld();

        assertEquals(0, within(round, "the round of the empty claim"), "fixture: the claim returned nothing");
        within(close, "the close once the empty claim returned");
        assertTrue(close.succeeded(), "the close succeeds: " + close.cause());
        assertEveryProducerClosedOnce();
    }

    @Test
    void aClaimInFlightThatFailsLetsThePendingCloseRun() throws Exception {
        Future<Integer> round = claimInFlightThenStop();
        Future<Void> close = worker.close();
        assertFalse(close.isComplete(), "the close stays pending while the claim is in flight");
        queue.failNextClaim(new IllegalStateException("store down"));

        queue.releaseHeld();

        Throwable failed = assertThrowsWithin(round);
        assertEquals("store down", failed.getMessage(), "fixture: the round fails with the claim");
        within(close, "the close once the failed claim returned");
        assertTrue(close.succeeded(), "the close succeeds: " + close.cause());
        assertEveryProducerClosedOnce();
    }

    /**
     * Item A is held at the partner and a second claim, for item B, is in flight when the worker is stopped and closed twice.
     * A finishes first: the close stays pending and closes nothing (the claim is still out). The claim returns B, B runs to its
     * success through open producers, and only then is every producer closed, once; both calls returned the one close.
     */
    @Test
    void anItemEndingWhileAClaimIsInFlightClosesNothingUntilTheClaimsItemFinished() throws Exception {
        Envelope a = accept();
        CountDownLatch release = partner.partner().holdNext(1);
        holds.add(release);
        Future<Integer> first = worker.poll();
        partner.partner().awaitSent(1);
        Envelope b = accept();
        Future<Integer> second = claimInFlightThenStop();

        Future<Void> close = worker.close();
        assertSame(close, worker.close(), "every call returns the one close");
        release.countDown();
        assertEquals(1, within(first, "the round of the released item A"), "fixture: the first round ran A");

        assertEquals(4, partner.open().size(), "no producer is closed while the claim is in flight, A finished: " + partner.built());
        assertFalse(close.isComplete(), "the close stays pending while the claim is in flight");
        queue.releaseHeld();
        assertEquals(1, within(second, "the round of the released claim"), "fixture: the claim handed out B");
        within(close, "the close once B finished");

        assertEquals(List.of(a.id(), b.id()), partner.partner().sentTo("a").stream().map(Envelope::id).toList(), "both runs went through their last send");
        assertEquals(0, dlq.size(), "neither item was parked: no DLQ row");
        assertEquals(0, rows.size(), "both items were completed by their own success");
        assertEveryProducerClosedOnce();
    }

    static Throwable assertThrowsWithin(Future<?> f) throws Exception {
        try {
            within(f, "the failing round");
        } catch (Exception failed) {
            return failed;
        }
        throw new AssertionError("the round did not fail: " + f.result());
    }
}
