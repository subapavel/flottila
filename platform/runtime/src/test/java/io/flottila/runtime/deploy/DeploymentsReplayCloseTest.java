package io.flottila.runtime.deploy;

import io.flottila.api.Ack;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Runtime;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.flow.Replay;
import io.flottila.core.flow.ReplayRefusedException;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static io.flottila.runtime.deploy.DeploymentsTest.shop;
import static io.flottila.runtime.deploy.DeploymentsTest.until;
import static io.flottila.runtime.deploy.DeploymentsTest.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-09c2 (D-067): {@link Deployments#close} waits for a replay's run in flight only up to the node's stop deadline. A run still
 * held then is a WARNING and close returns; the replay took no new run from the start of close, and its producer is closed only
 * once the held run ended. Once close began, {@link Deployments#replay} answers empty.
 */
class DeploymentsReplayCloseTest {

    static final Duration STOP_DEADLINE = Duration.ofSeconds(37);

    @TempDir Path tmp;
    final FakeRuntime rt = FakeRuntime.create();
    final Doors doors = new Doors();
    final InMemoryMessageStore messages = new InMemoryMessageStore();
    final List<CountDownLatch> holds = new CopyOnWriteArrayList<>();
    final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    /** Strongly held: JUL keeps loggers weakly, and a collected logger loses its handler. */
    final Logger flottila = Logger.getLogger("io.flottila");
    final Handler capture = new Handler() {
        @Override public void publish(LogRecord r) { logged.add(r); }
        @Override public void flush() {}
        @Override public void close() {}
    };
    Deployments deployments;

    @BeforeEach
    void wire() {
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "dev");
        env.put("FLOTTILA_NODE_ID", "node-1");
        env.put("FLOTTILA_DATA_DIR", tmp.resolve("data").toString());
        env.put("FLOTTILA_STOP_DEADLINE", STOP_DEADLINE.toString());
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
        deployments = new Deployments(NodeConfig.from(env), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), List.of(), List.of(), messages,
                new InMemoryDlq(), new InMemoryWorkQueue(rt.clock()), () -> 0L);
        capture.setLevel(Level.ALL);
        flottila.addHandler(capture);
    }

    @AfterEach
    void release() {
        for (CountDownLatch l : holds) l.countDown();
        doors.releaseAll();
        flottila.removeHandler(capture);
    }

    @Test
    void closeWaitsForAReplaysRunUpToTheStopDeadlineThenWarnsAndItsProducerClosesOnceTheRunEnded() throws Exception {
        OnVirtualThread.call(() -> deployments.deploy("shop", Optional.empty(), shop("shop").zipTo(tmp.resolve("archives"))));
        Envelope original = rt.ingress("shop/orders", Headers.empty(), Buffer.buffer("<order/>"));
        assertInstanceOf(Ack.Reply.class, within(doors.door("shop/orders").deliver(original), "orders' answer"), "fixture: orders answers");
        until(() -> messages.timeline(original.id()).result().size() == 2, "fixture: the rows of " + original.id());
        Replay replay = deployments.replay("shop/orders").orElseThrow(() -> new AssertionError("a RUNNING flow has its replay"));
        CountDownLatch release = doors.partner().holdNext(1);
        holds.add(release);
        OnVirtualThread.Call<Envelope> replaying = OnVirtualThread.start(() -> replay.from(original.id(), 1, "replay-1"));
        doors.partner().awaitSent(2);
        Doors.Out replayOut = doors.producers().stream().filter(o -> o.sentIds().contains("replay-1")).findFirst().orElseThrow();

        OnVirtualThread.Call<Void> closing = OnVirtualThread.start(deployments::close);

        until(() -> doors.doors().stream().allMatch(Doors.Door::stopped), "close stops every consumer");
        assertEquals(Optional.empty(), deployments.replay("shop/orders"), "no replay is answered once close began");
        ReplayRefusedException refused = assertThrows(ReplayRefusedException.class, () -> OnVirtualThread.call(() -> replay.from(original.id(), 1, "replay-2")));
        assertEquals(ReplayRefusedException.Reason.CLOSED, refused.reason(), "the replay takes no new run once close began");
        until(() -> {
            if (rt.timer().pending() > 0) rt.clock().advance(rt.timer().nextDelay());   // the stop deadline that bounds the wait comes
            return closing.isDone();
        }, "close returns once the stop deadline passed, the replay's run still held");
        OnVirtualThread.join(closing);

        assertTrue(logged.stream().anyMatch(r -> r.getLevel() == Level.WARNING && String.valueOf(r.getMessage()).contains("replay runs still in flight")),
                "a run still in flight at the deadline is a WARNING: " + logged.stream().map(LogRecord::getMessage).toList());
        assertFalse(replaying.isDone(), "fixture: the run is still held");
        assertEquals(0, replayOut.closes(), "the replay's producer stays open under its held run");
        release.countDown();
        assertEquals("replay-1", OnVirtualThread.join(replaying).id(), "the held run ends through its open producer");
        until(() -> replayOut.closes() > 0, "the replay's producer is closed once its run ended");
        assertEquals(1, replayOut.closes());
        assertEquals(List.of(0), replayOut.unansweredAtClose(), "with no send of its own unanswered");
    }
}
