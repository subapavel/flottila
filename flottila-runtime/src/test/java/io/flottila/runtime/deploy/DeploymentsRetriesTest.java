package io.flottila.runtime.deploy;

import io.flottila.api.Ack;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.NackException;
import io.flottila.api.Runtime;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.dlq.Parked;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Page;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static io.flottila.runtime.deploy.DeploymentsTest.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CORE-14a P1 (D-135 draft): the node's {@code FLOTTILA_RETRIES_COUNT} and {@code FLOTTILA_RETRIES_INTERVAL} reach, from NodeConfig
 * through the deployments, the worker of every at-least-once flow that names no {@code delivery.retries}. Everything in memory, as
 * {@link DeploymentsQueueTest}: the {@link Doors} component (its partner fails every send), the in-memory store and DLQ, a
 * {@link CountingQueue} (the clock at each defer), the fake clock driving every timer; the worker's reconciliation is
 * {@code WorkerPolicy.DEFAULT}'s, every 30 s.
 */
@Timeout(60)
class DeploymentsRetriesTest {

    @TempDir Path tmp;

    final FakeRuntime rt = FakeRuntime.create();
    final Doors doors = new Doors();
    final InMemoryMessageStore messages = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();
    CountingQueue queue;
    Deployments deployments;

    @AfterEach
    void release() {
        doors.releaseAll();
    }

    /** Deployments of a node whose env adds {@code extra}; {@code shop/audit}, at-least-once, to a partner that fails every send. */
    final List<Duration> scheduled = new java.util.concurrent.CopyOnWriteArrayList<>();

    Envelope deployedAndAccepted(Map<String, String> extra) throws Exception {
        queue = new CountingQueue(rt.clock());
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "dev");
        env.put("FLOTTILA_NODE_ID", DeploymentsTest.NODE);
        env.put("FLOTTILA_DATA_DIR", tmp.resolve("data").toString());
        env.put("FLOTTILA_STOP_DEADLINE", DeploymentsTest.STOP_DEADLINE.toString());
        env.put("FLOTTILA_BLOB_DURABLE_MAX", Long.toString(DeploymentsTest.DURABLE_MAX));
        env.put("FLOTTILA_MAX_PACKAGE_BYTES", Long.toString(DeploymentsTest.MAX_PACKAGE));
        env.putAll(extra);
        io.flottila.api.Timer timer = (delay, task) -> {   // the fake timer, each delay noted: when the worker armed its retry wake
            scheduled.add(delay);
            return rt.timer().schedule(delay, task);
        };
        Runtime runtime = new Runtime(rt.clock(), timer, rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
        deployments = new Deployments(NodeConfig.from(env), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), timer, DeploymentsTest.STEPS, List.of(),
                messages, dlq, queue, () -> 0L);
        PackageDraft p = DeploymentsTest.flows("shop", Map.of("audit", "at-least-once"));
        OnVirtualThread.call(() -> deployments.deploy("shop", Optional.empty(), p.zipTo(tmp.resolve("archives"))));
        doors.partner().failNext(100, () -> new NackException("partner down", true));
        Envelope e = rt.ingress("shop/audit", Headers.empty(), Buffer.buffer("<order/>"));
        assertEquals(new Ack.Accepted(e.id()), within(doors.door("shop/audit").deliver(e), "the answer"));
        return e;
    }

    List<Parked> parked() {
        return dlq.list(Optional.empty(), Page.MAX_LIMIT).await().items();
    }

    static void until(BooleanSupplier condition, Supplier<String> what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) fail("not within 10 s: " + what.get());
            Thread.onSpinWait();
        }
    }

    @Test
    void aNodeRetriesCountOfZeroParksTheFirstFailure() throws Exception {
        Envelope e = deployedAndAccepted(Map.of("FLOTTILA_RETRIES_COUNT", "0"));
        until(() -> doors.partner().sent().size() >= 1, () -> "the hand-off's run reached the partner");   // before the clock moves past its deadline
        rt.clock().advance(Duration.ofSeconds(61));
        until(() -> !parked().isEmpty(), () -> "parked: sends " + doors.partner().sent().size() + ", defers " + queue.deferredAt());

        assertEquals(List.of(e.id()), parked().stream().map(Parked::messageId).toList(), "parked at its first failure");
        assertEquals(1, doors.partner().sent().size(), "one send, no retry");
        assertEquals(List.of(), queue.deferredAt(), "never deferred");
        rt.clock().advance(Duration.ofHours(1));
        assertEquals(1, doors.partner().sent().size(), "an hour of reconciliations later: still one send");
    }

    @Test
    void theNodeRetriesIntervalSpacesTheRetriesOfADeployedFlow() throws Exception {
        Envelope e = deployedAndAccepted(Map.of("FLOTTILA_RETRIES_COUNT", "1", "FLOTTILA_RETRIES_INTERVAL", "PT2M"));
        until(() -> doors.partner().sent().size() >= 1, () -> "the hand-off's run reached the partner");   // before the clock moves past its deadline
        rt.clock().advance(Duration.ofSeconds(61));
        until(() -> queue.deferredAt().size() == 1, () -> "the first failure deferred: sends " + doors.partner().sent().size() + ", DLQ " + parked());
        Instant deferred = queue.deferredAt().get(0);
        until(() -> scheduled.contains(Duration.ofMinutes(2)), () -> "the worker armed its retry wake: " + scheduled);   // before the clock moves
        assertEquals(1, doors.partner().sent().size(), "one send so far");
        assertEquals(List.of(), parked(), "count 1: the first failure is not parked");

        rt.clock().set(deferred.plus(Duration.ofMinutes(2)).minusSeconds(1));
        assertEquals(1, doors.partner().sent().size(), "the node's interval, PT2M, has not passed: no retry yet");
        rt.clock().set(deferred.plus(Duration.ofMinutes(2)).plusSeconds(31));   // its wake, or a reconciliation, by now (the default PT5M: not yet)
        until(() -> !parked().isEmpty(), () -> "retried once and parked: sends " + doors.partner().sent().size() + ", defers " + queue.deferredAt());

        assertEquals(2, doors.partner().sent().size(), "count 1: two sends");
        assertEquals(List.of(e.id(), e.id()), doors.partner().sent().stream().map(Envelope::id).toList(), "the same message id");
        assertEquals(List.of(e.id()), parked().stream().map(Parked::messageId).toList());
        assertEquals(1, queue.deferredAt().size(), "the second failure parks, not deferred");
    }
}
