package io.flottila.runtime.node;

import io.flottila.api.Timer;
import io.flottila.core.record.StepCpu;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Test fixture, run as a child JVM by {@code CpuSamplerIT}. With no argument ({@code startRefusesWithoutFlightRecorder}): starts a
 * sampler and prints {@code started}, exit 0; or prints {@code refused: <exception>} and exits 3 when the start is refused with an
 * {@link IllegalStateException}. With {@code disk <readEveryMillis> <early> <late> <repository>}
 * ({@code theRecordingsDiskStaysBoundedOverManyReads}): starts a sampler read every {@code readEveryMillis}, keeps a virtual thread
 * busy (CPU-time samples, so every cycle's chunk holds events), and after each of the timer's firings from {@code early} to
 * {@code late} prints {@code disk <firing> <bytes> <chunks> <seen>} — the bytes and the number of {@code *.jfr} chunk files under the
 * JFR {@code repository} (the child's {@code -XX:FlightRecorderOptions:repository}), and how many distinct chunk files it has listed
 * so far — then closes the sampler, exit 0. Anything else escapes main (exit 1, its stack trace on stderr).
 *
 * <p>The sampler's cycles are timed by {@link SchedulerTimer}, a test-side {@link Timer} over a one-thread JDK scheduler (the node
 * passes its Vert.x timer; the child needs no Vert.x instance), each firing released on a semaphore the disk mode counts.
 */
public final class CpuSamplerChild {

    private CpuSamplerChild() {}

    /** A {@link Timer} over a daemon JDK scheduler: the task runs on its thread; each firing is one permit of {@link #fired}. */
    static final class SchedulerTimer implements Timer {
        final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("cpu-child-timer").unstarted(r));
        final Semaphore fired = new Semaphore(0);

        @Override
        public Cancellable schedule(Duration delay, Runnable task) {
            ScheduledFuture<?> scheduled = scheduler.schedule(() -> {
                task.run();
                fired.release();
            }, delay.toNanos(), TimeUnit.NANOSECONDS);
            return () -> scheduled.cancel(false);
        }
    }

    /** Where the busy loop's result goes, so the JIT cannot drop it. */
    static volatile long sink;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            start();
        } else if (args[0].equals("disk") && args.length == 5) {
            disk(Duration.ofMillis(Long.parseLong(args[1])), Integer.parseInt(args[2]), Integer.parseInt(args[3]), Path.of(args[4]));
        } else {
            throw new IllegalArgumentException("usage: [disk <readEveryMillis> <early> <late> <repository>], not " + List.of(args));
        }
    }

    static void start() {
        SchedulerTimer timer = new SchedulerTimer();
        try (CpuSampler sampler = CpuSampler.start(Duration.ofMillis(4), new StepCpu(StepCpu.Limits.DEFAULT), Clock.systemUTC(), timer, CpuSampler.READ_EVERY)) {
            System.out.println("started, period " + sampler.period());
        } catch (IllegalStateException refused) {
            System.out.println("refused: " + refused);
            System.out.flush();
            System.exit(3);
        }
    }

    static void disk(Duration readEvery, int early, int late, Path repository) throws InterruptedException {
        SchedulerTimer timer = new SchedulerTimer();
        Set<Path> seen = new HashSet<>();
        Thread busy = Thread.ofVirtual().name("cpu-child-busy").start(() -> {
            long x = 0x9E3779B97F4A7C15L;
            while (!Thread.currentThread().isInterrupted()) {
                for (int i = 0; i < 1_000_000; i++) {
                    x ^= x << 13;
                    x ^= x >>> 7;
                    x ^= x << 17;
                }
                sink += x;
            }
        });
        try (CpuSampler sampler = CpuSampler.start(Duration.ofMillis(2), new StepCpu(StepCpu.Limits.DEFAULT), Clock.systemUTC(), timer, readEvery)) {
            timer.fired.acquire(early);
            for (int firing = early; firing <= late; firing++) {
                if (firing > early) timer.fired.acquire();
                long[] disk = chunks(repository, seen);
                System.out.println("disk " + firing + " " + disk[0] + " " + disk[1] + " " + seen.size() + (sampler.failed() ? " failed" : ""));
            }
        } finally {
            busy.interrupt();
        }
        busy.join();
        System.out.flush();
    }

    /**
     * The bytes and the number of the {@code *.jfr} files in {@code repository}'s directories (the recorder writes its chunks into
     * one directory per JVM under it), each one listed added to {@code seen}; one deleted while it is listed holds no disk.
     */
    static long[] chunks(Path repository, Set<Path> seen) {
        long bytes = 0;
        long files = 0;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(repository, Files::isDirectory)) {
            for (Path dir : dirs) {
                try (DirectoryStream<Path> jfr = Files.newDirectoryStream(dir, "*.jfr")) {
                    for (Path chunk : jfr) {
                        try {
                            bytes += Files.size(chunk);
                            files++;
                            seen.add(chunk);
                        } catch (NoSuchFileException gone) {
                            // dropped by the recorder between the listing and the size: no longer on disk
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("fixture: the JFR repository " + repository, e);
        }
        return new long[] {bytes, files};
    }
}
