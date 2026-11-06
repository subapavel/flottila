package io.flottila.runtime.deploy;

import io.flottila.api.Ack;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Runtime;
import io.flottila.core.admission.Targets;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.flow.SequentialIntake;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.runtime.lease.InMemoryPollerLeases;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.flottila.runtime.deploy.DeploymentsTest.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P2-15a (D-101 (2)): the fence the node hands its {@link Deployments} fences the worker of every at-least-once flow they deploy —
 * nothing is claimed or sent and the item stays queued while it is closed — and leaves a sync flow alone. In memory, as
 * {@link DeploymentsQueueTest}: the {@link Doors} component, the in-memory store, DLQ and a {@link CountingQueue}, the fake
 * clock driving every timer.
 */
class DeploymentsFenceTest {

    @TempDir Path tmp;
    Path archives;

    final FakeRuntime rt = FakeRuntime.create();
    final Doors doors = new Doors();
    final InMemoryMessageStore messages = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();
    final AtomicBoolean open = new AtomicBoolean(false);
    final AtomicInteger asked = new AtomicInteger();
    TracingTimer timer;
    CountingQueue queue;
    Deployments deployments;

    @BeforeEach
    void wire() {
        archives = tmp.resolve("archives");
        timer = new TracingTimer(rt.timer());
        queue = new CountingQueue(rt.clock());
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "dev");
        env.put("FLOTTILA_NODE_ID", DeploymentsTest.NODE);
        env.put("FLOTTILA_DATA_DIR", tmp.resolve("data").toString());
        env.put("FLOTTILA_STOP_DEADLINE", DeploymentsTest.STOP_DEADLINE.toString());
        env.put("FLOTTILA_BLOB_DURABLE_MAX", Long.toString(DeploymentsTest.DURABLE_MAX));
        env.put("FLOTTILA_MAX_PACKAGE_BYTES", Long.toString(DeploymentsTest.MAX_PACKAGE));
        NodeConfig config = NodeConfig.from(env);
        Runtime runtime = new Runtime(rt.clock(), timer, rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
        deployments = new Deployments(config, runtime, Map.of(Doors.SCHEME, doors), rt.clock(), timer, DeploymentsTest.STEPS, List.of(), messages, dlq,
                queue, () -> 0L, new InMemoryDeploymentStore(), new DirArchiveStore(config.archives().dir()), new SequentialIntake(messages, queue),
                new InMemoryPollerLeases(rt.clock()), new Targets(config.targets().defaults(), config.targets().overrides(), timer), () -> {
                    asked.incrementAndGet();
                    return open.get();
                });
    }

    @AfterEach
    void release() {
        doors.releaseAll();
    }

    Envelope delivered(String flow, Class<? extends Ack> answer) throws Exception {
        Envelope e = rt.ingress(flow, Headers.empty(), Buffer.buffer("<order/>"));
        Ack ack = within(doors.door(flow).deliver(e), flow + "'s answer");
        if (!answer.isInstance(ack)) fail(flow + " answered " + ack);
        return e;
    }

    static void until(java.util.function.BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) fail("not within 10 s: " + what);
            Thread.onSpinWait();
        }
    }

    @Test
    void theNodesFenceFencesTheWorkersOfTheFlowsItDeploysAndNoSyncFlow() throws Exception {
        Map<String, String> modes = new HashMap<>();
        modes.put("audit", "at-least-once");
        modes.put("orders", "sync");
        OnVirtualThread.call(() -> deployments.deploy("shop", Optional.empty(), DeploymentsTest.flows("shop", modes).zipTo(archives)));

        Envelope sync = delivered("shop/orders", Ack.Reply.class);
        assertEquals(List.of(sync.id()), doors.partner().sentTo("partner").stream().map(Envelope::id).toList(), "a sync flow sends under a closed fence");
        assertEquals(0, asked.get(), "and never asks it");

        int claimsBefore = queue.claims().size();
        Envelope alo = delivered("shop/audit", Ack.Accepted.class);
        until(() -> asked.get() >= 1, "the worker's poll after the accept asked the fence (asked " + asked.get() + ")");
        assertEquals(claimsBefore, queue.claims().size(), "a fenced worker claims nothing: " + queue.claims());
        assertEquals(List.of(sync.id()), doors.partner().sentTo("partner").stream().map(Envelope::id).toList(), "nothing sent while fenced");
        assertEquals(1, queue.size(), "the item stays queued: " + queue.rows());

        open.set(true);   // the fence opens: the worker's next tick claims and runs it
        rt.clock().advance(Duration.ofHours(1));
        doors.partner().awaitSent(2);
        until(() -> queue.size() == 0, "the rerun completed its item: " + queue.rows());
        assertEquals(List.of(sync.id(), alo.id()), doors.partner().sentTo("partner").stream().map(Envelope::id).toList());
    }
}
