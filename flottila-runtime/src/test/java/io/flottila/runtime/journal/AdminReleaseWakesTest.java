package io.flottila.runtime.journal;

import io.flottila.api.Runtime;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.flow.FlowBuilder;
import io.flottila.core.flow.FlowWorker;
import io.flottila.core.flow.WorkSignals;
import io.flottila.core.flow.WorkerPolicy;
import io.flottila.core.flow.Workers;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.runtime.admin.AdminServer;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P2-16b (D-121 (b) B4 (ii)): an operator's release over the admin API wakes the released row's flow in the node's own
 * {@link Workers} and announces it through the node's {@link WorkSignals} — to the row's body node, {@code *} when the queue does
 * not tell it; a queue that cannot tell the row at all ({@link WorkQueue.Released#UNTOLD}) wakes every flow and announces
 * {@code * *}. A seq not held wakes and announces nothing. The node's workers are those of flows {@code orders} and
 * {@code invoices} over in-memory queues of their own that count claims ({@link PgWorkSignalsIT.Counting}); the held rows lie in
 * the admin's queue; the signals record what they are told (the SPI boundary).
 */
@Timeout(60)
class AdminReleaseWakesTest {

    static Vertx vertx;
    final FakeRuntime rt = FakeRuntime.create();
    final Workers workers = new Workers();
    final Map<String, PgWorkSignalsIT.Counting> claims = new HashMap<>();
    final List<FlowWorker> started = new ArrayList<>();
    final List<String> announced = new CopyOnWriteArrayList<>();
    final WorkSignals signals = (target, flow) -> announced.add(target + " " + flow);
    final InMemoryWorkQueue rows = new InMemoryWorkQueue(rt.clock());
    AdminServer admin;

    @BeforeAll
    static void up() { vertx = Vertx.vertx(); }

    @AfterAll
    static void down() { vertx.close().await(); }

    @AfterEach
    void stop() throws Exception {
        started.forEach(FlowWorker::stop);
        if (admin != null) admin.stop().await(10, TimeUnit.SECONDS);
    }

    /** The node's two started workers, each having made its start claim. */
    void startWorkers() {
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), Map.of());
        for (String f : List.of("orders", "invoices")) {
            PgWorkSignalsIT.Counting q = new PgWorkSignalsIT.Counting(new InMemoryWorkQueue(rt.clock()));
            FlowWorker w = new FlowBuilder(runtime, List.of(PgWorkSignalsIT.PASS), List.of(), Map.of()).recording(new InMemoryMessageStore(), Meter.system())
                    .queue(q).dlq(new InMemoryDlq()).workers(workers).worker(PgWorkSignalsIT.flow(f), Path.of(""), "n-a",
                            new WorkerPolicy(Duration.ofSeconds(30), 5, Duration.ofMinutes(10)));
            claims.put(f, q);
            w.start();
            started.add(w);
        }
        assertEquals(Map.of("orders", 1, "invoices", 1), counts(), "fixture: the start claims");
    }

    Map<String, Integer> counts() {
        Map<String, Integer> m = new HashMap<>();
        claims.forEach((f, q) -> m.put(f, q.claims.get()));
        return m;
    }

    /** {@code id} of {@code flow} queued, claimed and held in the admin's queue: its seq. */
    long held(String id, String flow) {
        rows.enqueue(id, flow, 1, null).await();
        WorkQueue.Item it = rows.claim("n-b", flow, 1, Duration.ofSeconds(30)).await().get(0);
        rows.hold(it.seq(), it.attempts()).await();
        return it.seq();
    }

    int release(WorkQueue queue, long seq) throws Exception {
        return release(queue, seq, signals);
    }

    int release(WorkQueue queue, long seq, WorkSignals through) throws Exception {
        admin = new AdminServer(new AdminServer.Config("127.0.0.1", 0, Optional.empty()), new InMemoryMessageStore(), new InMemoryDlq(), rt.blobs(),
                new AdminServer.HealthProbe(Map.of(), () -> 0L), Optional.empty(), queue, workers, through);
        int port = admin.start(vertx).await(10, TimeUnit.SECONDS);
        return HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/admin/work/" + seq + "/release")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    /** {@code rows} behind a queue that answers only {@code release}: {@code releaseRow} left to the interface's default. */
    record Untold(InMemoryWorkQueue rows) implements WorkQueue {
        @Override public io.vertx.core.Future<Void> enqueue(String messageId, String flow, int step, String partitionKey) { return rows.enqueue(messageId, flow, step, partitionKey); }
        @Override public io.vertx.core.Future<List<Item>> claim(String workerId, String flow, int max, Duration lease) { return rows.claim(workerId, flow, max, lease); }
        @Override public io.vertx.core.Future<Boolean> complete(long seq, int attempts) { return rows.complete(seq, attempts); }
        @Override public io.vertx.core.Future<Completion> completeAndTakeNext(long seq, int attempts, String workerId, Duration lease) {
            return rows.completeAndTakeNext(seq, attempts, workerId, lease);
        }
        @Override public io.vertx.core.Future<Boolean> renew(long seq, int attempts, Duration lease) { return rows.renew(seq, attempts, lease); }
        @Override public io.vertx.core.Future<Boolean> hold(long seq, int attempts) { return rows.hold(seq, attempts); }
        @Override public io.vertx.core.Future<io.flottila.core.record.Page<Held>> held(Optional<String> after, int limit) { return rows.held(after, limit); }
        @Override public io.vertx.core.Future<Boolean> release(long seq) { return rows.release(seq); }
        @Override public io.vertx.core.Future<Boolean> hasItems(String flow) { return rows.hasItems(flow); }
    }

    @Test
    void aReleaseWakesItsFlowHereAndAnnouncesItToEveryNodeWhenItsBodyNodeIsUntold() throws Exception {
        startWorkers();
        long seq = held("m-1", "invoices");
        assertEquals(200, release(rows, seq));
        assertEquals(Map.of("orders", 1, "invoices", 2), counts(), "the released row's flow woken here, the other not");
        assertEquals(List.of("null invoices"), announced, "announced for its flow to every node: the in-memory queue tells no body node");
    }

    @Test
    void aReleaseTheQueueCannotTellWakesEveryFlowAndAnnouncesEveryFlowToEveryNode() throws Exception {
        startWorkers();
        long seq = held("m-1", "invoices");
        assertEquals(200, release(new Untold(rows), seq));
        assertEquals(Map.of("orders", 2, "invoices", 2), counts(), "every flow woken here");
        assertEquals(List.of("null null"), announced, "every flow, every node");
    }

    /**
     * Review r1 V4: the node's signals throw on announce (their window's timer refused) after the release committed — the release
     * still answers 200, the row is released (claimable again), and its flow woken here.
     */
    @Test
    void aReleaseWhoseAnnounceThrowsStillAnswersItsSuccessAndStaysReleased() throws Exception {
        startWorkers();
        long seq = held("m-1", "invoices");
        java.util.concurrent.atomic.AtomicInteger tried = new java.util.concurrent.atomic.AtomicInteger();
        WorkSignals refusing = (target, flow) -> {
            tried.incrementAndGet();
            throw new IllegalStateException("the work events' timer refused the window");
        };
        assertEquals(200, release(rows, seq, refusing), "the committed release answers its success");
        assertEquals(1, tried.get(), "witness: the announce was made and threw");
        assertEquals(Map.of("orders", 1, "invoices", 2), counts(), "its flow woken here all the same");
        List<WorkQueue.Item> again = rows.claim("n-c", "invoices", 1, Duration.ofSeconds(30)).await();
        assertEquals(1, again.size(), "released: claimable again");
        assertEquals("m-1", again.get(0).messageId());
    }

    @Test
    void aSeqNotHeldWakesAndAnnouncesNothing() throws Exception {
        startWorkers();
        rows.enqueue("m-1", "orders", 1, null).await();
        assertEquals(404, release(rows, 1));
        assertEquals(Map.of("orders", 1, "invoices", 1), counts());
        assertEquals(List.of(), announced);
    }
}
