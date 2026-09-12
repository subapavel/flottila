package io.flottila.runtime;

import io.flottila.core.Envelope;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.BackPressureStrategy;
import io.smallrye.mutiny.subscription.MultiEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The real bottleneck is store-and-forward fsync: per-message insert is 1-2 ms, so all saves go
 * through one stream grouped by (batchSize, batchMs) into one multi-row INSERT per batch.
 * save() returns a Uni that completes when the batch holding this envelope has committed —
 * the ingress stage awaits it (ACK after durable), later stages subscribe and forget.
 *
 * Backlog is bounded to MAX_BATCHES batches; when the store can't keep up, save() fails
 * immediately with OverloadedException and ingress answers 503 — overflow surfaces as
 * backpressure at the edge, never as a dead writer stream. (Mutiny's onOverflow().buffer(n)
 * kills the stream when full, so the bound is enforced at admission, and the operator-level
 * buffer below is a safety net the admission gate keeps from filling.)
 */
public final class BatchingWriter {
    private static final Logger log = LoggerFactory.getLogger(BatchingWriter.class);
    private static final int MAX_BATCHES = 64;

    /** The writer backlog is full; the message was not accepted. Client should retry. */
    public static final class OverloadedException extends RuntimeException {
        OverloadedException(String message) { super(message); }
    }

    private final MessageStore store;
    private final int batchSize;
    private final int capacity;
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicReference<MultiEmitter<? super Pending>> emitter = new AtomicReference<>();

    private record Pending(Envelope e, CompletableFuture<Void> done) {}

    public BatchingWriter(MessageStore store, int batchSize, int batchMs) {
        this.store = store;
        this.batchSize = batchSize;
        this.capacity = Math.max(1, batchSize) * MAX_BATCHES;
        if (batchSize > 1) {
            Multi.createFrom().<Pending>emitter(emitter::set, BackPressureStrategy.BUFFER)
                    .group().intoLists().of(batchSize, Duration.ofMillis(Math.max(1, batchMs)))
                    // the timed flush emits on timer regardless of demand — including empty lists,
                    // which would flood the buffer while a commit is stuck; drop them BEFORE buffering
                    .select().where(batch -> !batch.isEmpty())
                    .onOverflow().buffer(MAX_BATCHES * 2)
                    .onItem().transformToUniAndConcatenate(batch -> {
                        if (log.isDebugEnabled()) log.debug("flush n={} t={}us", batch.size(), System.nanoTime() / 1000);
                        return store.saveAll(batch.stream().map(Pending::e).toList())
                                .onItemOrFailure().invoke((v, t) -> {
                                    if (log.isDebugEnabled()) log.debug("commit n={} ok={} t={}us", batch.size(), t == null, System.nanoTime() / 1000);
                                    batch.forEach(p -> {
                                        if (t == null) p.done().complete(null); else p.done().completeExceptionally(t);
                                    });
                                })
                                .onFailure().invoke(t -> log.error("batch save failed ({} messages)", batch.size(), t))
                                .onFailure().recoverWithNull();
                    })
                    .subscribe().with(x -> {}, t -> log.error("writer stream died", t));
        }
    }

    public Uni<Void> save(Envelope e) {
        if (batchSize <= 1) return store.save(e);          // per-message insert, no grouping
        if (pending.get() >= capacity)
            return Uni.createFrom().failure(new OverloadedException(
                    "writer backlog full (" + capacity + " messages pending)"));
        pending.incrementAndGet();
        CompletableFuture<Void> f = new CompletableFuture<>();
        f.whenComplete((v, t) -> pending.decrementAndGet());
        if (log.isDebugEnabled()) log.debug("emit id={} step={} t={}us", e.id(), e.step(), System.nanoTime() / 1000);
        emitter.get().emit(new Pending(e, f));
        return Uni.createFrom().completionStage(f);
    }
}
