package dev.rail8.core.flow;

import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.core.dlq.Parked;
import dev.rail8.core.queue.WorkQueue;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dev.rail8.core.flow.FlowWorkerTest.NO_FILES;
import static dev.rail8.core.flow.FlowWorkerTest.TO_PARTNER;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The worker's other park — of an item handed out past its bound, parked unsent — keeps the item's partition key on the DLQ row
 * and stops the key, as the park of a failed attempt does ({@code FlowWorkerRetryTest}): a keyed item of count 0 (bound 1) handed
 * out to a node that is gone, taken over in doubt as attempt 2, is parked with its key, never sent, and the key's next message
 * waits behind it.
 */
@Timeout(60)
class FlowWorkerExhaustedKeyTest {

    final FlowWorkerRetryTest.World w = new FlowWorkerRetryTest.World();

    @AfterEach
    void tearDown() {
        w.close();
    }

    @Test
    void aKeyedItemPastItsBoundIsParkedWithItsKeyAndStopsIt() throws Exception {
        FlowBuilder b = w.builder();
        Map<String, Object> doc = FlowWorkerRetryTest.flow(FlowWorkerRetryTest.retries(0, "1m"), List.of(
                Map.of("type", "extractKey", "name", "key", "language", "header", "key", "customer", "xpath", "x-customer"), FlowWorkerRetryTest.REWRITE, TO_PARTNER));
        doc.put("partitionBy", "customer");
        FlowDefinition def = FlowDefinition.from(doc);
        Envelope in = order("1");
        b.delivery(def, NO_FILES).apply(in).await(5, SECONDS);   // no worker started: a plain row
        List<WorkQueue.Item> lost = w.rows.claim("crashed", "orders", 1, FlowWorkerRetryTest.LEASE).await();
        assertEquals(List.of("c1"), lost.stream().map(WorkQueue.Item::partitionKey).toList(), "fixture: its one hand-out, keyed, to a node that is gone");
        Envelope next = order("2");
        b.delivery(def, NO_FILES).apply(next).await(5, SECONDS);
        w.rt.clock().advance(FlowWorkerRetryTest.LEASE.plus(Duration.ofSeconds(1)));

        FlowWorker worker = w.started(b, def, "n1");
        FlowWorkerTest.until(() -> !w.dlq().isEmpty(), () -> "the takeover parks it: " + w.rows.snapshot());
        w.settled(worker);
        List<Parked> all = w.dlq();
        assertEquals(1, all.size(), "parked: " + all);
        assertEquals(Optional.of("c1"), all.get(0).partitionKey(), "the park past the bound keeps the item's key");
        assertEquals(Optional.of(2), all.get(0).props().get(Envelope.ATTEMPT), "as attempt 2, past its bound 1");
        assertEquals(0, w.partner.sent().size(), "never sent");
        List<WorkQueue.Stopped> stopped = w.rows.stopped(Optional.empty(), 10).await().items();
        assertEquals(List.of(in.id()), stopped.stream().map(x -> x.item().messageId()).toList(), "its row stops the key");
        assertEquals(1, stopped.get(0).blocks(), "the key's next message waits behind it");
        assertEquals(0, worker.poll().await(5, SECONDS), "nothing of c1 runs");
    }

    Envelope order(String id) {
        return w.rt.ingress("orders", Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml").with("x-customer", "c1"),
                Buffer.buffer("<order id=\"" + id + "\"/>"));
    }
}
