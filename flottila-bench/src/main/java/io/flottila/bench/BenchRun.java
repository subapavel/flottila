package io.flottila.bench;

import io.flottila.api.Timer;
import io.flottila.core.batch.BatchSink;
import io.flottila.core.batch.BatchingWriter;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

/**
 * The measurement core: {@code messages} items produced by {@code item(i)}, saved from
 * {@code concurrency} virtual threads (each saving its share sequentially and waiting for the
 * durable completion of every save — the "durable ACK" of an at-least-once ingress) through a
 * {@link BatchingWriter} into a {@link BatchSink}. Latency = save() to completion.
 *
 * @since 0.1
 */
public final class BenchRun {

    /**
     * One configuration.
     *
     * @since 0.1
     */
    public record Config(String name, int messages, int concurrency, BatchingWriter.Options batch) {
        public Config {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(batch, "batch");
            if (messages < 1) throw new IllegalArgumentException("messages < 1");
            if (concurrency < 1) throw new IllegalArgumentException("concurrency < 1");
        }
    }

    /**
     * The numbers of one run.
     *
     * @since 0.1
     */
    public record Report(Config config, int saved, int failed, long elapsedNanos, double p50Millis, double p95Millis,
                         double p99Millis, double maxMillis, double meanMillis, String firstFailure) {

        public double throughputPerSecond() {
            return saved / (elapsedNanos / 1_000_000_000.0);
        }

        public JsonObject toJson() {
            return new JsonObject()
                    .put("name", config.name())
                    .put("messages", config.messages())
                    .put("concurrency", config.concurrency())
                    .put("batch", new JsonObject().put("size", config.batch().batchSize())
                            .put("windowMs", config.batch().window().toMillis())
                            .put("commitConcurrency", config.batch().commitConcurrency())
                            .put("maxBacklog", config.batch().maxBacklog()))
                    .put("saved", saved).put("failed", failed)
                    .put("elapsedMs", elapsedNanos / 1_000_000.0)
                    .put("throughputPerSecond", throughputPerSecond())
                    .put("p50Ms", p50Millis).put("p95Ms", p95Millis).put("p99Ms", p99Millis)
                    .put("maxMs", maxMillis).put("meanMs", meanMillis)
                    .put("firstFailure", firstFailure);
        }
    }

    private BenchRun() {}

    public static <T> Report run(Config cfg, BatchSink<T> sink, Timer timer, IntFunction<T> item) {
        LatencyStats stats = new LatencyStats(cfg.messages());
        AtomicInteger saved = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicReference<String> firstFailure = new AtomicReference<>();
        AtomicInteger next = new AtomicInteger();
        long started = System.nanoTime();
        try (BatchingWriter<T> writer = new BatchingWriter<>(sink, timer, cfg.batch())) {
            CountDownLatch done = new CountDownLatch(cfg.concurrency());
            for (int w = 0; w < cfg.concurrency(); w++) {
                Thread.ofVirtual().name("bench-", w).start(() -> {
                    try {
                        int i;
                        while ((i = next.getAndIncrement()) < cfg.messages()) {
                            long t0 = System.nanoTime();
                            Throwable cause = awaitQuietly(writer.save(item.apply(i)));
                            stats.record(System.nanoTime() - t0);
                            if (cause == null) saved.incrementAndGet();
                            else { failed.incrementAndGet(); firstFailure.compareAndSet(null, String.valueOf(cause)); }
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            try {
                done.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
        }
        long elapsed = System.nanoTime() - started;
        // saved is counted, never derived: a message whose worker died uncounted shows up as a row-count mismatch, not as saved
        return new Report(cfg, saved.get(), failed.get(), elapsed,
                stats.percentileMillis(50), stats.percentileMillis(95), stats.percentileMillis(99), stats.maxMillis(), stats.meanMillis(), firstFailure.get());
    }

    /** Awaits the save; returns null on success, else whatever `await` threw: the failed future's cause (sneaky for checked ones) or an interrupt. */
    private static Throwable awaitQuietly(Future<Void> f) {
        try {
            f.await();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }
}
