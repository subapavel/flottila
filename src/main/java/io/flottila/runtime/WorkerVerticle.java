package io.flottila.runtime;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.vertx.core.AbstractVerticle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dequeues durable work and runs the pipeline tail (Flow.from(step)). Completion (including a
 * DLQ park — the message was handled) deletes the work row; a crash or stream failure leaves it
 * for lease-expiry re-delivery: at-least-once. Per partition_key the queue serves rows serially.
 */
public class WorkerVerticle extends AbstractVerticle {
    private static final Logger log = LoggerFactory.getLogger(WorkerVerticle.class);

    private final Flow flow;
    private final WorkQueue queue;
    private final MessageStore store;
    private final String workerId = "worker-" + UUID.randomUUID();
    private final int batch = 16;
    private final Duration lease = Duration.ofSeconds(30);
    private volatile boolean polling = false;

    public WorkerVerticle(Flow flow, WorkQueue queue, MessageStore store) {
        this.flow = flow; this.queue = queue; this.store = store;
    }

    @Override public Uni<Void> asyncStart() {
        vertx.setPeriodic(200, t -> poll());
        log.info("worker {} polling flow {}", workerId, flow.name());
        return Uni.createFrom().voidItem();
    }

    private void poll() {
        if (polling) return;
        polling = true;
        queue.claim(workerId, flow.name(), batch, lease).subscribe().with(items -> {
            if (items.isEmpty()) { polling = false; return; }
            AtomicInteger remaining = new AtomicInteger(items.size());
            Runnable done = () -> { if (remaining.decrementAndGet() == 0) { polling = false; poll(); } };
            items.forEach(it -> process(it, done));
        }, t -> { log.error("claim failed", t); polling = false; });
    }

    private void process(WorkQueue.Item it, Runnable done) {
        store.load(it.messageId(), it.step()).subscribe().with(opt -> {
            if (opt.isEmpty()) {        // envelope gone (retention?) — nothing to run, drop the row
                log.warn("work item {} has no envelope at ({}, {})", it.seq(), it.messageId(), it.step());
                queue.complete(it.seq()).subscribe().with(x -> done.run(), e -> { log.error("complete failed", e); done.run(); });
                return;
            }
            flow.from(it.step()).apply(Multi.createFrom().item(opt.get())).subscribe().with(
                    out -> {},
                    t -> { log.error("work item {} stream failed, will be re-delivered", it.seq(), t); done.run(); },
                    () -> queue.complete(it.seq()).subscribe().with(x -> done.run(),
                            e -> { log.error("complete failed for {}", it.seq(), e); done.run(); }));
        }, t -> { log.error("load failed for work item {}", it.seq(), t); done.run(); });
    }
}
