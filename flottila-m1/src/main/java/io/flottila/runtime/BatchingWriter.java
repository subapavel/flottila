package io.flottila.runtime;

import io.flottila.core.Envelope;
import io.smallrye.mutiny.Uni;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Store-and-forward writer. All saves flow through one queue; batches become one multi-row
 * INSERT each. save() returns a Uni that completes only after the batch holding this envelope
 * has committed — irreversible-ACK ingress awaits it, everything else subscribes and forgets.
 *
 * Adaptive flush: with no batch in flight a save flushes immediately (single-request latency
 * pays no batch window); under load the queue accumulates to batchSize/batchMs. Up to
 * commitConcurrency batch commits run concurrently.
 *
 * Hand-rolled rather than a Mutiny group().intoLists() chain: the operator emits on timer
 * regardless of demand and dies with a BackPressureFailure mid-commit, and onOverflow()
 * bounded buffers fail the stream when full. Admission is bounded instead: a full backlog
 * (MAX_BATCHES batches) fails save() immediately with OverloadedException → 503/NACK at
 * the edge, never a dead writer.
 */
public final class BatchingWriter {
    private static final Logger log = LoggerFactory.getLogger(BatchingWriter.class);
    private static final int MAX_BATCHES = 64;

    /** The writer backlog is full; the message was not accepted. Client should retry. */
    public static final class OverloadedException extends RuntimeException {
        OverloadedException(String message) { super(message); }
    }

    private record Pending(Envelope e, CompletableFuture<Void> done) {}

    private final MessageStore store;
    private final int batchSize, batchMs, commitConcurrency, capacity;
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private final Object lock = new Object();
    private int inFlight = 0;
    private boolean timerArmed = false;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "flottila-writer-timer"); t.setDaemon(true); return t;
    });

    public BatchingWriter(MessageStore store, int batchSize, int batchMs, int commitConcurrency) {
        this.store = store;
        this.batchSize = Math.max(1, batchSize);
        this.batchMs = Math.max(0, batchMs);
        this.commitConcurrency = Math.max(1, commitConcurrency);
        this.capacity = this.batchSize * MAX_BATCHES;
    }

    public Uni<Void> save(Envelope e) {
        Pending p = new Pending(e, new CompletableFuture<>());
        List<List<Pending>> batches;
        synchronized (lock) {
            if (queue.size() >= capacity)
                return Uni.createFrom().failure(new OverloadedException(
                        "writer backlog full (" + capacity + " messages pending)"));
            queue.add(p);
            batches = drainLocked(false);
        }
        if (log.isDebugEnabled()) log.debug("emit id={} step={} t={}us", e.id(), e.step(), System.nanoTime() / 1000);
        batches.forEach(this::commit);
        return Uni.createFrom().completionStage(p.done());
    }

    /**
     * Start commits while a slot is free AND (nothing in flight → adaptive immediate flush,
     * a full batch is waiting, or the flush timer fired). Otherwise arm the timer for the rest.
     */
    private List<List<Pending>> drainLocked(boolean timerFired) {
        List<List<Pending>> out = new ArrayList<>();
        while (!queue.isEmpty() && inFlight < commitConcurrency
                && (inFlight == 0 || queue.size() >= batchSize || timerFired)) {
            int take = Math.min(batchSize, queue.size());
            List<Pending> b = new ArrayList<>(take);
            for (int i = 0; i < take; i++) b.add(queue.poll());
            inFlight++;
            out.add(b);
        }
        if (!queue.isEmpty() && !timerArmed && batchMs > 0) {
            timerArmed = true;
            timer.schedule(this::onTimer, batchMs, TimeUnit.MILLISECONDS);
        }
        return out;
    }

    private void onTimer() {
        List<List<Pending>> batches;
        synchronized (lock) {
            timerArmed = false;
            batches = drainLocked(true);
        }
        batches.forEach(this::commit);
    }

    private void commit(List<Pending> batch) {
        if (log.isDebugEnabled()) log.debug("flush n={} t={}us", batch.size(), System.nanoTime() / 1000);
        store.saveAll(batch.stream().map(Pending::e).toList()).subscribe().with(
                v -> afterCommit(batch, null),
                t -> { log.error("batch save failed ({} messages)", batch.size(), t); afterCommit(batch, t); });
    }

    private void afterCommit(List<Pending> batch, Throwable t) {
        if (log.isDebugEnabled()) log.debug("commit n={} ok={} t={}us", batch.size(), t == null, System.nanoTime() / 1000);
        batch.forEach(p -> { if (t == null) p.done().complete(null); else p.done().completeExceptionally(t); });
        List<List<Pending>> next;
        synchronized (lock) {
            inFlight--;
            next = drainLocked(false);
        }
        next.forEach(this::commit);
    }
}
