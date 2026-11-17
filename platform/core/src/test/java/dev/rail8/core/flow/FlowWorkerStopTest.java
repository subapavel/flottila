package dev.rail8.core.flow;

import dev.rail8.api.Ack;
import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.core.dlq.FailedMessages;
import dev.rail8.core.queue.InMemoryFailedMessages;
import dev.rail8.core.queue.WorkQueue;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static dev.rail8.core.flow.FlowWorkerTest.NO_FILES;
import static dev.rail8.core.flow.FlowWorkerTest.TO_PARTNER;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * An ordered flow keeps its order when a message fails: the message is parked and its key stops behind it, so the key's later
 * messages wait, never run; the stop is listed with the failure. Once an operator retries it, it runs first and the later ones
 * follow in their order; once an operator cancels it, the later ones run in their order and it never runs again. A key that did
 * not fail runs all the while.
 */
@Timeout(60)
class FlowWorkerStopTest {

    static final FailedMessages.Actor BY = new FailedMessages.Actor("ops", "127.0.0.1");

    final FlowWorkerRetryTest.World w = new FlowWorkerRetryTest.World();
    final FailedMessages operator = new InMemoryFailedMessages(w.rows);
    final FlowBuilder b = w.builder();
    final FlowDefinition def = keyed();

    @AfterEach
    void tearDown() {
        w.close();
    }

    @Test
    void aRetriedMessageRunsFirstAndTheKeyGoesOnInOrder() throws Exception {
        List<String> c1 = stoppedBehindItsFirst();
        String first = c1.get(0);

        assertInstanceOf(FailedMessages.Done.class, operator.retry(first, "orders", "c1", 1, BY).await(5, SECONDS), "retried");
        FlowWorker worker = w.started(b, def, "n1");
        w.sent(worker, 5);
        assertEquals(List.of(first, c1.get(1), c1.get(2)), w.sentIds().subList(2, 5), "the retried message first, then the rest of its key in order");
        assertEquals(List.of(), w.dlq(), "nothing parked");
        assertEquals(0, w.rows.size(), "every item done");
    }

    @Test
    void aCancelledMessageNeverRunsAndTheKeyGoesOnInOrder() throws Exception {
        List<String> c1 = stoppedBehindItsFirst();
        String first = c1.get(0);

        FailedMessages.Done done = assertInstanceOf(FailedMessages.Done.class, operator.cancel(first, BY, Optional.of("sent by hand")).await(5, SECONDS));
        assertEquals(Optional.of("sent by hand"), done.event().reason());
        FlowWorker worker = w.started(b, def, "n1");
        w.sent(worker, 4);
        assertEquals(List.of(c1.get(1), c1.get(2)), w.sentIds().subList(2, 4), "the rest of the key in order; the cancelled one is not sent again");
        assertEquals(List.of(), w.dlq(), "its DLQ row is gone");
        assertEquals(0, w.rows.size(), "every item done");
    }

    /**
     * Three orders of c1 and one of c2: the first of c1 fails for good (no retries) and stops c1; c2 runs. Returns c1's ids in order,
     * the worker that ran them stopped.
     */
    List<String> stoppedBehindItsFirst() throws Exception {
        w.partner.failNext(1, FlowWorkerRetryTest::downstream);
        Delivery delivery = b.delivery(def, NO_FILES);
        List<String> c1 = new ArrayList<>();
        for (int i = 0; i < 3; i++) c1.add(accept(delivery, "c1"));
        String c2 = accept(delivery, "c2");
        FlowWorker worker = w.started(b, def, "n1");
        w.sent(worker, 2);

        assertEquals(Set.of(c1.get(0), c2), Set.copyOf(w.sentIds()), "c1's first ran and failed; c2 ran; c1's later ones wait");
        assertEquals(List.of(c1.get(0)), w.dlq().stream().map(dev.rail8.core.dlq.Parked::messageId).toList(), "c1's first is parked");
        List<WorkQueue.Stopped> stopped = w.rows.stopped(Optional.empty(), 10).await().items();
        assertEquals(1, stopped.size(), "one stop: " + stopped);
        assertEquals(List.of(c1.get(0), 2L), List.of(stopped.get(0).item().messageId(), stopped.get(0).blocks()), "c1's first, blocking two");
        assertEquals(Optional.of(w.dlq().get(0).error()), stopped.get(0).error(), "with its failure");
        assertEquals(0, worker.poll().await(5, SECONDS), "nothing of c1 is claimed while it is stopped");
        w.settled(worker);
        assertEquals(2, w.partner.sent().size(), "nothing more sent");
        worker.stop();
        return c1;
    }

    String accept(Delivery delivery, String customer) throws Exception {
        Envelope in = w.rt.ingress("orders", Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml").with("x-customer", customer),
                Buffer.buffer("<order/>"));
        assertEquals(new Ack.Accepted(in.id()), delivery.apply(in).await(5, SECONDS));
        return in.id();
    }

    static FlowDefinition keyed() {
        // one in flight: the items run in their order, so the partner's one failure is c1's first send, never c2's
        Map<String, Object> doc = FlowWorkerRetryTest.flow(FlowWorkerRetryTest.retries(0, "1m"), List.of(
                Map.of("type", "extractKey", "name", "key", "language", "header", "key", "customer", "xpath", "x-customer"), TO_PARTNER), 1);
        doc.put("partitionBy", "customer");
        return FlowDefinition.from(doc);
    }
}
