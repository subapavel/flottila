package io.flottila.testkit;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * CLAUDE.md: a blocked platform thread in a test is a failure. The detector watches the platform
 * pools it hands out (the JCo/JNI pool is the only legitimate one in production) and records a
 * {@link Violation} when a thread that is running a task stays BLOCKED, WAITING or TIMED_WAITING
 * for at least the threshold. A pool thread waiting for its next task is idle, not blocked, and is
 * never reported. Virtual threads may block and are never reported.
 *
 * @since 0.1
 */
public final class BlockedPlatformThreadDetector implements AutoCloseable {

    /**
     * One task that blocked a platform thread.
     *
     * @param thread     name of the thread
     * @param virtual    whether the thread was virtual
     * @param blockedFor how long the sampler had seen it blocked when it reported
     * @param stackTrace where it was blocked
     * @since 0.1
     */
    public record Violation(String thread, boolean virtual, Duration blockedFor, List<StackTraceElement> stackTrace) { }

    /** What the sampler remembers about the task a watched thread is running. */
    private static final class Watch {
        long blockedSinceNanos = -1;
        boolean reported;
    }

    private final long thresholdNanos;
    private final Map<Thread, Watch> running = new ConcurrentHashMap<>();
    private final List<Violation> violations = new CopyOnWriteArrayList<>();
    private final CompletableFuture<Violation> first = new CompletableFuture<>();
    private final List<ExecutorService> pools = new CopyOnWriteArrayList<>();
    private final CountDownLatch closed = new CountDownLatch(1);
    /** "Now" for {@link #sample()}: {@code System::nanoTime}, or the fake clock's instant as nanos. */
    private final LongSupplier nanos;
    /** The wall-time sampler thread; {@code null} for a detector {@link #driven driven} by a fake clock. */
    private final Thread sampler;

    private BlockedPlatformThreadDetector(Duration threshold, LongSupplier nanos, boolean withSampler) {
        Objects.requireNonNull(threshold, "threshold");
        if (threshold.isNegative() || threshold.isZero()) throw new IllegalArgumentException("threshold must be positive");
        this.thresholdNanos = threshold.toNanos();
        this.nanos = nanos;
        long intervalNanos = Math.max(TimeUnit.MILLISECONDS.toNanos(1), thresholdNanos / 4);
        this.sampler = !withSampler ? null : Thread.ofPlatform().daemon().name("blocked-thread-sampler").unstarted(() -> {
            try {
                while (!closed.await(intervalNanos, TimeUnit.NANOSECONDS)) sample();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** Starts a detector that reports threads blocked for at least {@code threshold}, sampling on wall time. */
    public static BlockedPlatformThreadDetector start(Duration threshold) {
        BlockedPlatformThreadDetector d = new BlockedPlatformThreadDetector(threshold, System::nanoTime, true);
        d.sampler.start();
        return d;
    }

    /**
     * A detector with no sampler thread that samples on every change of {@code clock} ({@link FakeClock#advance}
     * and {@link FakeClock#set}, a zero advance included); "now" is the clock's instant as nanoseconds, so a
     * violation's {@code blockedFor} is the clock time since the first sample that saw the thread blocked. The
     * clock must not stand before the epoch. {@link #close()} makes further changes no-ops — FakeClock keeps
     * the listener.
     *
     * @since 0.1
     */
    public static BlockedPlatformThreadDetector driven(Duration threshold, FakeClock clock) {
        Objects.requireNonNull(clock, "clock");
        BlockedPlatformThreadDetector d = new BlockedPlatformThreadDetector(threshold, () -> {
            Instant i = clock.instant();
            return i.getEpochSecond() * 1_000_000_000L + i.getNano();
        }, false);
        clock.onChange(d::sample);
        return d;
    }

    /** A fixed pool of {@code threads} platform threads named {@code name-0}, {@code name-1}, ... that this detector watches. */
    public ExecutorService watchedPlatformPool(String name, int threads) {
        Thread.Builder.OfPlatform factory = Thread.ofPlatform().daemon().name(name + "-", 0);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), factory.factory()) {
            @Override protected void beforeExecute(Thread t, Runnable r) { running.put(t, new Watch()); }

            @Override protected void afterExecute(Runnable r, Throwable t) { running.remove(Thread.currentThread()); }
        };
        pools.add(pool);
        return pool;
    }

    /** Starts a virtual thread running {@code body}; blocking on it is never a violation. */
    public Thread watchedVirtualThread(String name, Runnable body) {
        return Thread.ofVirtual().name(name).start(body);
    }

    /** Completes with the first violation recorded. */
    public CompletableFuture<Violation> firstViolation() { return first; }

    /** Every violation recorded so far, oldest first. */
    public List<Violation> violations() { return List.copyOf(violations); }

    /** Stops sampling and shuts down the pools this detector handed out (without awaiting their termination). */
    @Override public void close() {
        closed.countDown();
        for (ExecutorService pool : pools) pool.shutdownNow();
        if (sampler == null) return;
        try {
            sampler.join(Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void sample() {
        if (closed.getCount() == 0) return;
        long now = nanos.getAsLong();
        for (Map.Entry<Thread, Watch> entry : running.entrySet()) {
            Thread thread = entry.getKey();
            Watch watch = entry.getValue();
            Thread.State state = thread.getState();
            boolean blocked = state == Thread.State.BLOCKED || state == Thread.State.WAITING
                    || state == Thread.State.TIMED_WAITING;
            if (!blocked) {
                watch.blockedSinceNanos = -1;
            } else if (watch.blockedSinceNanos < 0) {
                watch.blockedSinceNanos = now;
            } else if (!watch.reported && now - watch.blockedSinceNanos >= thresholdNanos) {
                watch.reported = true;
                Violation v = new Violation(thread.getName(), thread.isVirtual(),
                        Duration.ofNanos(now - watch.blockedSinceNanos), new ArrayList<>(List.of(thread.getStackTrace())));
                violations.add(v);
                first.complete(v);
            }
        }
    }
}
