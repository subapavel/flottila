package io.flottila.core.flow;

import io.flottila.api.Ack;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.dlq.Parked;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.core.record.Page;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.RecordingComponent;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static io.flottila.core.flow.FlowWorkerTest.NO_FILES;
import static io.flottila.core.flow.FlowWorkerTest.TO_PARTNER;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P2-15a, architect decision (2) on the implementer's finding: parking an item past maxAttempts is a settle, so the worker
 * checks the node's fence before it writes the DLQ row. Fenced, it parks nothing and holds nothing — the item is left
 * unsettled for whoever runs it next, so a later holder's park is the only DLQ row; with the fence open it parks once. A
 * closed fence claims nothing (review r1 decision 4), so the fence here closes after the claim read it open.
 */
@Timeout(60)
class FlowWorkerFencedParkTest {

    static final Duration LEASE = Duration.ofSeconds(30);

    /** One message accepted at-least-once and handed out once to a node that is gone; a worker of maxAttempts 1 over the same queue. */
    static final class World {
        final FakeRuntime rt = FakeRuntime.create();
        final RecordingComponent partner = new RecordingComponent("mock");
        final InMemoryWorkQueue rows = new InMemoryWorkQueue(rt.clock());
        final RecordingWorkQueue queue = new RecordingWorkQueue(rows, rt.clock());
        final InMemoryDlq parked = new InMemoryDlq();
        final FlowWorker worker;
        final Envelope in;

        World(BooleanSupplier fence) throws Exception {
            rt.withComponent(partner);
            Map<String, EndpointConfig> endpoints = Map.of("partner", new EndpointConfig("partner", URI.create("mock://partner"), Map.of()));
            FlowBuilder b = new FlowBuilder(rt.runtime(), List.of(), List.of(BuiltInStepsTest.HEADERS), endpoints)
                    .recording(new InMemoryMessageStore(), Meter.system()).queue(queue).dlq(parked).fence(fence);
            FlowDefinition def = FlowDefinition.from(FlowWorkerTest.flow(4, List.of(TO_PARTNER)));
            in = rt.ingress("orders", Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), Buffer.buffer("<order id=\"1\"/>"));
            assertEquals(new Ack.Accepted(in.id()), b.delivery(def, NO_FILES).apply(in).await(5, SECONDS));
            assertEquals(1, rows.claim("crashed", "orders", 1, LEASE).await().size(), "fixture: a first hand-out to a node that is gone");
            rt.clock().advance(LEASE.plusSeconds(1));
            worker = FlowWorkerTest.policy(LEASE, 1).worker(b, def, "n1");
        }

        List<String> dlq() {
            return parked.list(Optional.empty(), Page.MAX_LIMIT).await().items().stream().map(Parked::messageId).toList();
        }

        /**
         * Past the item's lease, its next hand-out: the claim of the worker's own lease-end wake (D-121 A6), not a poll of the
         * test; returns once that claim answered and its run is settled.
         */
        void nextHandOut() {
            int before = queue.claimsBy("n1").size();
            rt.clock().advance(LEASE.plusSeconds(1));
            FlowWorkerTest.until(() -> queue.claimsBy("n1").size() > before && queue.claimsInFlight() == 0 && worker.unsettled() == 0,
                    () -> "the lease-end wake's claim answered and its run settled: claims " + queue.claims() + ", running " + worker.unsettled());
        }

        /** Past another lease, a poll of the test: nothing is left to its lease, so no wake claims and only this poll could. */
        void pollAfterAnotherLease() throws Exception {
            rt.clock().advance(LEASE.plusSeconds(1));
            worker.poll().await(10, SECONDS);
        }
    }

    @Test
    void aFencedWorkerPastMaxAttemptsParksNothingAndWithTheFenceOpenParksOnce() throws Exception {
        AtomicBoolean open = new AtomicBoolean(true);
        AtomicBoolean closeAfterNextReading = new AtomicBoolean();
        World w = new World(() -> {
            boolean answer = open.get();
            if (closeAfterNextReading.getAndSet(false)) open.set(false);
            return answer;
        });
        closeAfterNextReading.set(true);   // the claim reads the fence open; it stays closed from the next reading on

        assertEquals(1, w.worker.poll().await(10, SECONDS), "the second hand-out, past maxAttempts 1, reached the worker");
        assertEquals(List.of(), w.dlq(), "no DLQ row from a closed fence");
        assertEquals(List.of(), w.queue.holds(), "nor a hold");
        assertEquals(List.of(), w.queue.completeCalls(), "nor a complete");
        assertEquals(1, w.rows.size(), "the item left unsettled for whoever runs it next");
        assertEquals(List.of(), w.partner.sent(), "an exhausted item never runs");

        open.set(true);   // witness: the fence open, the next hand-out is parked
        w.nextHandOut();
        assertEquals(List.of(w.in.id()), w.dlq(), "parked once, past maxAttempts");
        assertEquals(0, w.rows.size(), "and completed");
        w.pollAfterAnotherLease();
        assertEquals(List.of(w.in.id()), w.dlq(), "parked once only");
    }

    @Test
    void aFenceThatReopensBeforeTheSettleLeavesTheExhaustedItemToItsLeaseNotHeld() throws Exception {
        AtomicInteger readings = new AtomicInteger();
        AtomicInteger closedReading = new AtomicInteger(-1);   // the one reading the fence answers closed
        World w = new World(() -> readings.incrementAndGet() != closedReading.get());

        closedReading.set(readings.get() + 2);   // the claim's reading open, the park's closed, the settle's after it open again
        assertEquals(1, w.worker.poll().await(10, SECONDS));
        assertEquals(List.of(), w.dlq(), "not parked while fenced");
        assertEquals(List.of(), w.queue.holds(), "a fenced park is no failed park: never held");
        assertEquals(List.of(), w.rows.held(Optional.empty(), 10).await().items());
        assertEquals(List.of(), w.queue.completeCalls());
        assertEquals(1, w.rows.size(), "left to its lease");

        w.nextHandOut();
        assertEquals(List.of(w.in.id()), w.dlq(), "its next hand-out, the fence open, is parked");
        assertEquals(0, w.rows.size());
    }
}
