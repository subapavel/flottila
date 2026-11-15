package io.flottila.bench;

import io.flottila.api.Timer;
import io.flottila.core.admission.Admission;
import io.flottila.core.scope.RequestScope;
import io.vertx.core.json.JsonObject;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Spike 2 (ARCHITECTURE §13 Phase 0; P0-11): the cost of a {@link RequestScope} per message (fork / join /
 * close with N children) when thousands run at once, and the acquire latency of {@link Admission} when
 * thousands of virtual threads contend for one flow's budget. Numbers are recorded in bench/RESULTS.md
 * only when measured natively.
 *
 * @since 0.1
 */
public final class ScopeBench {

    private ScopeBench() {}

    /** @since 0.1 */
    public record AdmittedReport(int messages, int children, long maxRequests, long maxBytes, long bytesPerMessage, int completed, int refused,
                                 long maxInFlightRequests, long maxInFlightBytes, long elapsedNanos,
                                 double p50Millis, double p95Millis, double p99Millis, double maxMillis, double meanMillis) {
        public double messagesPerSecond() { return completed / (elapsedNanos / 1_000_000_000.0); }

        public JsonObject toJson() {
            return new JsonObject().put("name", "admitted-message-path").put("messages", messages).put("children", children)
                    .put("maxRequests", maxRequests).put("maxBytes", maxBytes).put("bytesPerMessage", bytesPerMessage)
                    .put("completed", completed).put("refused", refused).put("maxInFlightRequests", maxInFlightRequests).put("maxInFlightBytes", maxInFlightBytes)
                    .put("elapsedMs", elapsedNanos / 1_000_000.0).put("messagesPerSecond", messagesPerSecond())
                    .put("p50Ms", p50Millis).put("p95Ms", p95Millis).put("p99Ms", p99Millis).put("maxMs", maxMillis).put("meanMs", meanMillis);
        }
    }

    /**
     * The real message path (P1-02): {@code messages} virtual threads at once, each {@code Admitted.run} — admit, bind the
     * context, open the scope, fork {@code children} trivial tasks, join, release. Latency = the whole run; a refusal (the
     * non-blocking admission said no) is counted, not retried.
     */
    public static AdmittedReport admitted(int messages, int children, io.flottila.core.admission.Admission admission, long bytesPerMessage,
                                          Clock clock, Timer timer) throws InterruptedException {
        LatencyStats stats = new LatencyStats(messages);
        AtomicInteger refused = new AtomicInteger();
        AtomicLong maxRequests = new AtomicLong();
        AtomicLong maxBytes = new AtomicLong();
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(messages);
        for (int m = 0; m < messages; m++) {
            String id = "m-" + m;
            Thread.ofVirtual().start(() -> {
                try {
                    go.await();
                    long t0 = System.nanoTime();
                    var ctx = new io.flottila.core.scope.MessageContext(id, "bench", "bench", "trace-" + id, clock.instant().plusSeconds(30));
                    try {
                        io.flottila.core.admission.Admitted.run(admission, ctx, bytesPerMessage, clock, timer, scope -> {
                            maxRequests.accumulateAndGet(admission.inFlightRequests(), Math::max);
                            maxBytes.accumulateAndGet(admission.inFlightBytes(), Math::max);
                            @SuppressWarnings("unchecked")
                            RequestScope.Subtask<Integer>[] forks = new RequestScope.Subtask[children];
                            for (int c = 0; c < children; c++) { int n = c + 1; forks[c] = scope.fork(() -> n); }
                            scope.join();
                            for (RequestScope.Subtask<Integer> f : forks) f.get();
                            return null;
                        });
                        stats.record(System.nanoTime() - t0);
                    } catch (io.flottila.core.admission.AdmissionRefusedException e) {
                        refused.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    throw new IllegalStateException("interrupted", e);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                } finally {
                    done.countDown();
                }
            });
        }
        long started = System.nanoTime();
        go.countDown();
        done.await();
        long elapsed = System.nanoTime() - started;
        return new AdmittedReport(messages, children, admission.maxRequests(), admission.maxBytes(), bytesPerMessage, stats.count(), refused.get(),
                maxRequests.get(), maxBytes.get(), elapsed, stats.percentileMillis(50), stats.percentileMillis(95), stats.percentileMillis(99),
                stats.maxMillis(), stats.meanMillis());
    }

    /** @since 0.1 */
    public record ScopeReport(int scopes, int children, int concurrency, int failed, long elapsedNanos, double p50Millis, double p95Millis,
                              double p99Millis, double maxMillis, double meanMillis) {
        public double scopesPerSecond() { return scopes / (elapsedNanos / 1_000_000_000.0); }

        public JsonObject toJson() {
            return new JsonObject().put("name", "scope-fork-join").put("scopes", scopes).put("children", children).put("concurrency", concurrency)
                    .put("childrenRun", (long) scopes * children).put("failed", failed)
                    .put("elapsedMs", elapsedNanos / 1_000_000.0).put("scopesPerSecond", scopesPerSecond())
                    .put("p50Ms", p50Millis).put("p95Ms", p95Millis).put("p99Ms", p99Millis).put("maxMs", maxMillis).put("meanMs", meanMillis);
        }
    }

    /** @since 0.1 */
    public record AdmissionReport(int threads, long maxRequests, long maxBytes, long bytesPerRequest, long holdNanos, int failed,
                                  long maxInFlightRequests, long maxInFlightBytes, long elapsedNanos,
                                  double p50Millis, double p95Millis, double p99Millis, double maxMillis, double meanMillis) {
        public double acquiresPerSecond() { return threads / (elapsedNanos / 1_000_000_000.0); }

        public JsonObject toJson() {
            return new JsonObject().put("name", "admission-acquire").put("threads", threads).put("maxRequests", maxRequests)
                    .put("maxBytes", maxBytes).put("bytesPerRequest", bytesPerRequest).put("holdMs", holdNanos / 1_000_000.0)
                    .put("failed", failed).put("maxInFlightRequests", maxInFlightRequests).put("maxInFlightBytes", maxInFlightBytes)
                    .put("elapsedMs", elapsedNanos / 1_000_000.0).put("acquiresPerSecond", acquiresPerSecond())
                    .put("p50Ms", p50Millis).put("p95Ms", p95Millis).put("p99Ms", p99Millis).put("maxMs", maxMillis).put("meanMs", meanMillis);
        }
    }

    /**
     * {@code scopes} virtual threads at once, each: open a scope, fork {@code children} trivial tasks, join, read every
     * result, close; wall time per scope. A scope whose thread dies (a child not SUCCESS makes {@code get()} throw) is not
     * recorded and counts as failed; the failure itself goes to the thread's uncaught-exception handler, not under a rug.
     */
    public static ScopeReport scopes(int scopes, int children, int concurrency, Clock clock, Timer timer) throws InterruptedException {
        LatencyStats stats = new LatencyStats(scopes);
        AtomicInteger next = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);
        for (int w = 0; w < concurrency; w++) {
            Thread.ofVirtual().start(() -> {
                try {
                    go.await();
                    while (next.getAndIncrement() < scopes) {
                        long t0 = System.nanoTime();
                        try (RequestScope scope = RequestScope.open(clock, timer, Duration.ofSeconds(30))) {
                            @SuppressWarnings("unchecked")
                            RequestScope.Subtask<Integer>[] forks = new RequestScope.Subtask[children];
                            for (int c = 0; c < children; c++) { int n = c + 1; forks[c] = scope.fork(() -> n); }
                            scope.join();
                            for (RequestScope.Subtask<Integer> f : forks) f.get();
                        }
                        stats.record(System.nanoTime() - t0);
                    }
                } catch (InterruptedException e) {
                    throw new IllegalStateException("interrupted", e);
                } finally {
                    done.countDown();
                }
            });
        }
        long started = System.nanoTime();
        go.countDown();
        done.await();
        long elapsed = System.nanoTime() - started;
        return new ScopeReport(scopes, children, concurrency, scopes - stats.count(), elapsed, stats.percentileMillis(50), stats.percentileMillis(95),
                stats.percentileMillis(99), stats.maxMillis(), stats.meanMillis());
    }

    /** {@code threads} virtual threads at once, each: acquire {@code bytesPerRequest}, hold, release; acquire latency and the observed in-flight maxima. */
    public static AdmissionReport admission(int threads, Admission admission, long bytesPerRequest, Duration hold) throws InterruptedException {
        LatencyStats stats = new LatencyStats(threads);
        AtomicLong maxRequests = new AtomicLong();
        AtomicLong maxBytes = new AtomicLong();
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    go.await();
                    long t0 = System.nanoTime();
                    try (Admission.Permit p = admission.acquire(bytesPerRequest)) {
                        stats.record(System.nanoTime() - t0);
                        maxRequests.accumulateAndGet(admission.inFlightRequests(), Math::max);
                        maxBytes.accumulateAndGet(admission.inFlightBytes(), Math::max);
                        Thread.sleep(hold);   // the hold models the message's processing time; ZERO just yields
                    }
                } catch (InterruptedException e) {
                    throw new IllegalStateException("interrupted", e);
                } finally {
                    done.countDown();
                }
            });
        }
        long started = System.nanoTime();
        go.countDown();
        done.await();
        long elapsed = System.nanoTime() - started;
        return new AdmissionReport(threads, admission.maxRequests(), admission.maxBytes(), bytesPerRequest, hold.toNanos(), threads - stats.count(),
                maxRequests.get(), maxBytes.get(), elapsed, stats.percentileMillis(50), stats.percentileMillis(95),
                stats.percentileMillis(99), stats.maxMillis(), stats.meanMillis());
    }

}
