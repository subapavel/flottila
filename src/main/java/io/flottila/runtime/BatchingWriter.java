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
import java.util.concurrent.atomic.AtomicReference;

/**
 * The real bottleneck is store-and-forward fsync: per-message insert is 1-2 ms, so all saves go
 * through one stream grouped by (batchSize, batchMs) into one multi-row INSERT per batch.
 * save() returns a Uni that completes when the batch holding this envelope has committed —
 * the ingress stage awaits it (ACK after durable), later stages subscribe and forget.
 */
public final class BatchingWriter {
    private static final Logger log = LoggerFactory.getLogger(BatchingWriter.class);

    private final MessageStore store;
    private final int batchSize;
    private final AtomicReference<MultiEmitter<? super Pending>> emitter = new AtomicReference<>();

    private record Pending(Envelope e, CompletableFuture<Void> done) {}

    public BatchingWriter(MessageStore store, int batchSize, int batchMs) {
        this.store = store;
        this.batchSize = batchSize;
        if (batchSize > 1) {
            Multi.createFrom().<Pending>emitter(emitter::set, BackPressureStrategy.BUFFER)
                    .group().intoLists().of(batchSize, Duration.ofMillis(Math.max(1, batchMs)))
                    // the timed flush emits regardless of downstream demand and would kill the
                    // stream with a BackPressureFailure while a batch commit is in flight —
                    // buffer the flushed batches and drop the empty ticks
                    .onOverflow().buffer(8192)
                    .select().where(batch -> !batch.isEmpty())
                    .onItem().transformToUniAndConcatenate(batch ->
                            store.saveAll(batch.stream().map(Pending::e).toList())
                                    .onItemOrFailure().invoke((v, t) -> batch.forEach(p -> {
                                        if (t == null) p.done().complete(null); else p.done().completeExceptionally(t);
                                    }))
                                    .onFailure().invoke(t -> log.error("batch save failed ({} messages)", batch.size(), t))
                                    .onFailure().recoverWithNull())
                    .subscribe().with(x -> {}, t -> log.error("writer stream died", t));
        }
    }

    public Uni<Void> save(Envelope e) {
        if (batchSize <= 1) return store.save(e);          // per-message insert, no grouping
        CompletableFuture<Void> f = new CompletableFuture<>();
        emitter.get().emit(new Pending(e, f));
        return Uni.createFrom().completionStage(f);
    }
}
