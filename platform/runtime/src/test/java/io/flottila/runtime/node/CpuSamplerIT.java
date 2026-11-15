package io.flottila.runtime.node;

import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Pipeline;
import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.api.StepFactory;
import io.flottila.api.StepResources;
import io.flottila.api.Timer;
import io.flottila.core.flow.FlowBuilder;
import io.flottila.core.flow.FlowDefinition;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.core.record.Snapshot;
import io.flottila.core.record.StepCpu;
import io.flottila.runtime.time.VertxTimer;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import com.sun.management.HotSpotDiagnosticMXBean;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import jdk.jfr.StackTrace;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * FIX-108 (D-094, F-22), real JFR: the node's {@link CpuSampler} keeps an on-disk recording of {@code jdk.CPUTimeSample}s and
 * {@code io.flottila.Step} events and, every read cycle, dumps it and feeds the window the previous cycle closed into a {@link StepCpu}
 * (F-22: a live stream loses the thread of virtual-thread events). Here a cycle is every {@value #READ_EVERY_MILLIS} ms on a real
 * timer — the runtime's {@link VertxTimer} over a Vert.x instance of the class, counted ({@link CountingTimer}) so a case can tell how
 * many reads ran; {@code drained()} completes once everything committed before the call has reached the matcher (no sleep here: every
 * wait is that future or a count, bounded 30 s). The stated error: a step with at least 1 000 samples, lost at
 * most 5 %, is within ±10 % of its CPU — for the busy step, the CPU its carriers took while the messages ran: {@link ThreadMXBean} on
 * each carrier thread of the virtual-thread scheduler (a virtual thread has no CPU reading on JDK 25, F-21), the reads held meanwhile
 * (FIX-108 r7: the same work on 200 virtual threads took up to 2.5 % more CPU than on platform threads). A fixed-iteration work loop
 * calibrated on platform threads, one per core, run together, before and after the run (the median of every calibrated run, never the
 * process CPU of the run, which holds the sampler's and the JIT's) is a ±25 % witness that the carriers' CPU is the work's; the other
 * cases compare against that calibration.
 *
 * <p>{@code @EnabledOnOs(LINUX)}: {@code jdk.CPUTimeSample} exists on Linux only and the sampler refuses any other OS. On the Linux CI
 * and the VM this is no skip; on another OS the class does not run.
 *
 * <p>Precondition — the kernel's timer frequency: this VM runs a kernel with HZ 250, so the CPU-time timer fires every 4 ms whatever
 * the period below that. The sampling period here is PT0.002S and JFR reports each sample's real {@code samplingPeriod} (mostly 4 ms):
 * CPU taken as samples × the configured period would be half the truth. The sample counts below (≈1 500 for the busy step) assume
 * that 4 ms; on a kernel with HZ 1000 the 2 ms period holds and every count doubles, the ±10 % bounds unchanged. The busy step is ≈30 ms, not the spec's ≈20 ms: 200 × 20 ms at a 4 ms effective period is 1 000 samples
 * nominal, so "at least 1 000" would be a coin flip; 30 ms gives ≈1 500.
 *
 * <p>The shape assumed (FIX-108 test-author, round 4): the package-private {@code static CpuSampler start(Duration period, StepCpu into,
 * Clock clock, Timer timer, Duration readEvery)} — {@code since()} read from the clock, every read cycle scheduled on the timer —,
 * {@code Future<Void> drained()}, {@code Duration period()}, {@code Instant since()}, {@code boolean failed()} (true once a cycle
 * failed or found the recording not running, and for good; also when the clean-up at the owner's {@code close()} throws — a timer
 * cancel that throws —, never after a clean close), {@code close()}
 * (AutoCloseable); one dump file {@code flottila-cpu-*.jfr} in {@code java.io.tmpdir}, deleted at close; failures and unreadable events
 * logged at WARNING with the cause on the logger {@code io.flottila.runtime.node.CpuSampler} (JUL, as {@code System.getLogger} maps it).
 * The child JVMs ({@link CpuSamplerChild}) call the same overload with a test-side timer (round 5: the public 3-arg start, timed by a
 * JDK scheduler, is gone).
 */
@EnabledOnOs(OS.LINUX)
class CpuSamplerIT {

    static final Duration PERIOD = Duration.ofMillis(2);
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-02T10:00:00.250Z"), ZoneOffset.UTC);
    static final int MESSAGES = 200;
    static final Duration BOUND = Duration.ofSeconds(30);
    static final Path NO_FILES = Path.of("");
    static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    static final long READ_EVERY_MILLIS = 200;
    static final Duration READ_EVERY = Duration.ofMillis(READ_EVERY_MILLIS);
    static Vertx vertx;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void closeVertx() {
        vertx.close().await();
    }

    /** The runtime's timer, each firing counted: how many read cycles the sampler's timer ran; and the thread of every scheduling. */
    static final class CountingTimer implements Timer {
        final Timer real;
        final AtomicInteger fired = new AtomicInteger();
        final List<Thread> schedulers = new CopyOnWriteArrayList<>();

        CountingTimer(Timer real) {
            this.real = real;
        }

        @Override
        public Cancellable schedule(Duration delay, Runnable task) {
            schedulers.add(Thread.currentThread());
            return real.schedule(delay, () -> {
                fired.incrementAndGet();
                task.run();
            });
        }

        int fired() {
            return fired.get();
        }
    }

    final CountingTimer timer = new CountingTimer(new VertxTimer(vertx));

    /** A sampler of {@link #PERIOD} into {@code into}, its reads every {@link #READ_EVERY} on {@link #timer}. */
    CpuSampler sampler(StepCpu into) {
        return CpuSampler.start(PERIOD, into, CLOCK, timer, READ_EVERY);
    }

    /** Where the work loop's result goes, so the JIT cannot drop it. */
    static volatile long sink;

    @TempDir Path tmp;
    final FakeRuntime rt = FakeRuntime.create();
    final java.util.concurrent.atomic.AtomicInteger dumps = new java.util.concurrent.atomic.AtomicInteger();

    /** A fixed number of xorshift rounds: the same instructions every run, whatever the clock says. */
    static long work(long iterations) {
        long x = 0x9E3779B97F4A7C15L;
        for (long i = 0; i < iterations; i++) {
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
        }
        return x;
    }

    /** {@code busy}: {@link #work} of its {@code iterations}; {@code pass}: hands the envelope on. */
    static final StepFactory BUSY = factory("busy", s -> {
        long iterations = ((Number) s.config().get("iterations")).longValue();
        return Stage.of(e -> {
            sink += work(iterations);
            return e;
        });
    });
    static final StepFactory PASS = factory("pass", s -> Stage.of(e -> e));
    /** Where a {@code hop} step's wait is answered: a platform thread, as an I/O completion would be. */
    static final java.util.concurrent.ExecutorService ELSEWHERE = java.util.concurrent.Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().daemon().name("cpu-it-elsewhere").unstarted(r));
    /** {@code hop}: {@link #work} of its {@code iterations}, then waits for an answer from another thread (its virtual thread parks and unmounts, as on I/O), then the same work again. */
    static final StepFactory HOP = factory("hop", s -> {
        long iterations = ((Number) s.config().get("iterations")).longValue();
        return Stage.of(e -> {
            sink += work(iterations);
            java.util.concurrent.CompletableFuture.runAsync(() -> { }, ELSEWHERE).join();
            sink += work(iterations);
            return e;
        });
    });

    static StepFactory factory(String type, Function<StepConfig, Stage> make) {
        return new StepFactory() {
            @Override public String type() { return type; }
            @Override public String configSchema() { return "{}"; }
            @Override public Stage create(StepConfig step, StepResources res) { return make.apply(step); }
        };
    }

    static Map<String, Object> busy(String name, long iterations) {
        return Map.of("type", "busy", "name", name, "iterations", iterations);
    }

    static FlowDefinition flow(String id, List<Map<String, Object>> steps) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("schemaVersion", 1);
        doc.put("kind", "flow");
        doc.put("id", id);
        doc.put("version", "1.0.0");
        doc.put("from", Map.of("endpoint", "in"));
        doc.put("delivery", Map.of("mode", "sync"));
        doc.put("limits", Map.of("maxBodyBytes", 4096));
        doc.put("steps", steps);
        return FlowDefinition.from(doc);
    }

    Pipeline pipeline(String id, List<Map<String, Object>> steps) {
        return new FlowBuilder(rt.runtime(), List.of(BUSY, PASS, HOP), List.of(), Map.of()).stepEvents(true).build(flow(id, steps), NO_FILES);
    }

    /** {@code n} messages of {@code flow}, each on a virtual thread of its own, all at once (as a consumer runs them); waits for every one. */
    List<Envelope> run(Pipeline p, String flow, int n) throws InterruptedException {
        List<Envelope> ins = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        List<AtomicReference<Object>> outcomes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Envelope in = rt.ingress(flow, Headers.empty(), Buffer.buffer("<x/>"));
            AtomicReference<Object> outcome = new AtomicReference<>();
            ins.add(in);
            outcomes.add(outcome);
            threads.add(Thread.ofVirtual().name("cpu-it-" + flow + "-" + i).start(() -> {
                try {
                    outcome.set(p.apply(in));
                } catch (Throwable x) {
                    outcome.set(x);
                }
            }));
        }
        for (int i = 0; i < n; i++) {
            Thread t = threads.get(i);
            if (!t.join(Duration.ofSeconds(60))) fail("message " + i + " of " + flow + " did not end within 60 s, at " + Arrays.toString(t.getStackTrace()));
            assertInstanceOf(Envelope.class, outcomes.get(i).get(), "fixture: message " + i + " of " + flow + " went through");
        }
        return ins;
    }

    /**
     * Messages of {@code flow}, each on a virtual thread of its own (as a consumer runs them), at most {@code concurrency} in flight,
     * sent until at least {@code atLeast} went and the sampler's timer fired at least {@code reads} read cycles meanwhile (bounded
     * {@link #BOUND}); waits for every one and returns how many went.
     */
    int load(Pipeline p, String flow, int atLeast, int reads, int concurrency) throws InterruptedException {
        Semaphore inFlight = new Semaphore(concurrency);
        AtomicInteger failed = new AtomicInteger();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        int firedAtStart = timer.fired();
        long deadline = System.nanoTime() + BOUND.toNanos();
        int sent = 0;
        while (sent < atLeast || timer.fired() - firedAtStart < reads) {
            if (System.nanoTime() - deadline > 0) {
                fail("not within " + BOUND + ": " + atLeast + " messages over " + reads + " read cycles (sent " + sent + ", cycles " + (timer.fired() - firedAtStart) + ")");
            }
            inFlight.acquire();
            Envelope in = rt.ingress(flow, Headers.empty(), Buffer.buffer("<x/>"));
            Thread.ofVirtual().name("cpu-it-load-" + sent).start(() -> {
                try {
                    p.apply(in);
                } catch (Throwable x) {
                    failed.incrementAndGet();
                    firstFailure.compareAndSet(null, x);
                } finally {
                    inFlight.release();
                }
            });
            sent++;
        }
        if (!inFlight.tryAcquire(concurrency, 60, TimeUnit.SECONDS)) fail("the last messages of " + flow + " did not end within 60 s");
        assertEquals(0, failed.get(), "fixture: every message of " + flow + " went through; the first failure: " + firstFailure.get());
        return sent;
    }

    /** Everything committed so far has reached the sampler's matcher. */
    static void drain(CpuSampler sampler) throws Exception {
        sampler.drained().await(BOUND.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** The iterations of {@link #work} that take about {@code cpu} on this machine, measured on the calling (platform) thread. */
    static long iterationsFor(Duration cpu) {
        assertTrue(THREADS.isCurrentThreadCpuTimeSupported() && !Thread.currentThread().isVirtual(), "fixture: ThreadMXBean reads this platform thread's CPU");
        for (int i = 0; i < 50; i++) sink += work(1_000_000);   // compiled before it is measured
        long probe = 20_000_000;
        long[] spent = new long[7];   // the median of seven probes: one slowed by a neighbour does not move it (r8: one probe read 20 ms for 30)
        for (int r = 0; r < spent.length; r++) {
            long t0 = THREADS.getCurrentThreadCpuTime();
            sink += work(probe);
            spent[r] = THREADS.getCurrentThreadCpuTime() - t0;
        }
        for (long s : spent) assertTrue(s > 0, "fixture: every probe loop took CPU: " + Arrays.toString(spent));
        return Math.max(1, Math.round(cpu.toNanos() * probe / median(spent)));
    }

    /**
     * The CPU nanos of {@link #work}{@code (iterations)}, one reading per run: six runs on each of one platform thread per core, all
     * started together — the load the messages put on the machine.
     */
    static long[] calibration(long iterations) throws InterruptedException {
        int cores = java.lang.Runtime.getRuntime().availableProcessors();
        int runs = 6;
        long[] spent = new long[cores * runs];
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int c = 0; c < cores; c++) {
            int core = c;
            threads.add(Thread.ofPlatform().name("cpu-it-calibration-" + c).start(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int r = 0; r < runs; r++) {
                    long t0 = THREADS.getCurrentThreadCpuTime();
                    sink += work(iterations);
                    spent[core * runs + r] = THREADS.getCurrentThreadCpuTime() - t0;
                }
            }));
        }
        go.countDown();
        for (Thread t : threads) {
            if (!t.join(Duration.ofSeconds(60))) fail("calibration thread did not end within 60 s, at " + Arrays.toString(t.getStackTrace()));
        }
        for (long s : spent) assertTrue(s > 0, "fixture: every calibration run measured: " + Arrays.toString(spent));
        return spent;
    }

    /** The median of every reading of {@code calibrations}: a run preempted or slowed by a neighbour does not move it. */
    static double median(long[]... calibrations) {
        long[] all = Arrays.stream(calibrations).flatMapToLong(Arrays::stream).sorted().toArray();
        int mid = all.length / 2;
        return all.length % 2 == 1 ? all[mid] : (all[mid - 1] + all[mid]) / 2.0;
    }

    static StepCpu.Row row(List<StepCpu.Row> rows, int step) {
        return rows.stream().filter(r -> r.step() == step).findFirst().orElseThrow(() -> new AssertionError("no row of step " + step + " in " + rows));
    }

    /** The JFR recordings running now that take CPU-time samples: the sampler's stream while it runs. */
    static long cpuStreams() {
        return FlightRecorder.getFlightRecorder().getRecordings().stream()
                .filter(r -> r.getState() == RecordingState.RUNNING && "true".equals(r.getSettings().get("jdk.CPUTimeSample#enabled"))).count();
    }

    @Test
    void startTakesItsPeriodAndSinceFromTheInjectedClock() throws Exception {
        try (CpuSampler sampler = sampler(new StepCpu(StepCpu.Limits.DEFAULT))) {
            assertEquals(PERIOD, sampler.period());
            assertEquals(Instant.parse("2026-11-02T10:00:00.250Z"), sampler.since(), "since: the injected clock's instant at the start");
            drain(sampler);
            assertTrue(timer.fired() >= 2, "the read cycles run on the injected timer: a drain takes two of them, fired " + timer.fired());
            List<String> schedulers = timer.schedulers.stream().map(t -> t.getName() + (t.isVirtual() ? " (virtual)" : " (platform)")).toList();
            assertTrue(schedulers.size() >= 2, "fixture: every read was scheduled through the counting timer: " + schedulers);
            assertTrue(timer.schedulers.stream().allMatch(t -> t.isVirtual() && "flottila-cpu-sampler".equals(t.getName())),
                    "every read is scheduled from the sampler's own thread, the virtual thread flottila-cpu-sampler (F-22), never a platform thread: " + schedulers);
        }
    }

    /**
     * The CPU nanos so far of every carrier thread of the virtual-thread scheduler ({@code jdk.internal.misc.CarrierThread}), by id:
     * what the messages' virtual threads took while mounted on them, the threads the sampler samples them on.
     */
    static Map<Long, Long> carrierCpu() {
        Map<Long, Long> cpu = new HashMap<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if ("jdk.internal.misc.CarrierThread".equals(t.getClass().getName())) {
                long nanos = THREADS.getThreadCpuTime(t.threadId());
                if (nanos >= 0) cpu.put(t.threadId(), nanos);
            }
        }
        return cpu;
    }

    /** Reads by hand until a drain asked now completes (at most five), each as {@link #fire}. */
    static void drainByHand(FakeClock clock, FakeTimer manual, CpuSampler sampler) throws Exception {
        Future<Void> drained = sampler.drained();
        for (int n = 1; n <= 5 && !drained.isComplete(); n++) fire(clock, manual, sampler, n);
        assertTrue(drained.succeeded(), "fixture: the drain completed within five reads by hand: " + drained);
    }

    /**
     * A busy step's estimate is within ±10 % of the CPU it took: 200 messages of a ≈30 ms work loop, each on a virtual thread of its
     * own, all at once. The reference is the CPU the virtual-thread scheduler's carriers took over the run ({@link ThreadMXBean} on
     * each carrier, before and after: the CPU the sampler samples, same threads, same time, same contention). The reads are fired by
     * hand ({@link FakeTimer}), none during the run, so the sampler's own thread — a virtual thread on the same carriers — adds nothing
     * to it. Witness: that CPU is the work's, within ±25 % of 200 calibrated loops (platform threads, one per core, before and after).
     */
    @Test
    void busyStepCpuIsWithinTenPercentOfTheCpuItsCarriersTook() throws Exception {
        long iterations = iterationsFor(Duration.ofMillis(30));
        StepCpu cpu = new StepCpu(StepCpu.Limits.DEFAULT);
        FakeClock clock = FakeClock.at(CLOCK.instant());
        FakeTimer manual = new FakeTimer(clock);
        try (CpuSampler sampler = CpuSampler.start(PERIOD, cpu, clock, manual, READ_EVERY)) {
            until(() -> manual.pending() == 1, "the sampler's thread schedules its first read");
            run(pipeline("cpu-warm", List.of(busy("busy", iterations))), "cpu-warm", 50);   // the JIT warms up
            long[] calibratedBefore = calibration(iterations);
            drainByHand(clock, manual, sampler);
            assertEquals(50, row(cpu.rows("cpu-warm"), 1).executions(), "fixture: the warm-up flow's steps were drained");
            StepCpu.Totals before = cpu.totals();
            assertTrue(before.platform() > 0, "the calibration's samples, taken on platform threads, are counted as platform samples — each sample's thread kind is "
                    + "the recorded thread's own: " + before);

            Pipeline busyFlow = pipeline("cpu-busy", List.of(busy("busy", iterations)));
            Map<Long, Long> carriersBefore = carrierCpu();
            run(busyFlow, "cpu-busy", MESSAGES);
            Map<Long, Long> carriersAfter = carrierCpu();
            assertTrue(carriersAfter.keySet().containsAll(carriersBefore.keySet()), "fixture: no carrier ended during the run (its CPU would be missed): "
                    + carriersBefore.keySet() + " → " + carriersAfter.keySet());
            long carriers = carriersAfter.entrySet().stream().mapToLong(c -> c.getValue() - carriersBefore.getOrDefault(c.getKey(), 0L)).sum();
            drainByHand(clock, manual, sampler);

            StepCpu.Totals after = cpu.totals();
            long[] calibratedAfter = calibration(iterations);   // before and after the run: a drift of the machine between them is halved
            double perRun = median(calibratedBefore, calibratedAfter);
            List<StepCpu.Row> rows = cpu.rows("cpu-busy");
            assertEquals(1, rows.size(), "one step: " + rows);
            StepCpu.Row busy = rows.get(0);
            double calibrated = MESSAGES * perRun;
            String seen = "row " + busy + ", carriers " + carriers + " ns over the run, calibrated " + Math.round(calibrated) + " ns (" + MESSAGES + " × " + Math.round(perRun)
                    + " ns, the median calibrated run; before " + Math.round(median(calibratedBefore)) + ", after " + Math.round(median(calibratedAfter)) + "), totals "
                    + before + " → " + after;
            System.out.printf("FIX-108 busy step: estimate/carriers %.3f, carriers/calibrated %.3f, estimate/calibrated %.3f, %s%n",
                    busy.cpuNanos() / (double) carriers, carriers / calibrated, busy.cpuNanos() / calibrated, seen);
            assertTrue(Math.abs(carriers - calibrated) <= 0.25 * calibrated, "witness: the carriers' CPU over the run is the work's: " + seen);
            assertEquals("busy", busy.name());
            assertEquals(MESSAGES, busy.executions(), "one execution per message: " + seen);
            assertTrue(busy.samples() >= 1_000, "the stated error holds from 1 000 samples: " + seen);
            long lost = after.lost() - before.lost();
            long samples = after.samples() - before.samples();
            assertTrue(lost <= 0.05 * (samples + lost), "at most 5 % lost while the messages ran (" + lost + " of " + (samples + lost) + "): " + seen);
            assertEquals(0, after.unattributed() - before.unattributed(), "no sample of the run is unattributed — none lost its thread (F-22): " + seen);
            assertTrue(Math.abs(busy.cpuNanos() - carriers) <= 0.10 * carriers,
                    "the estimate is within ±10 % of the CPU the carriers took (" + String.format("%+.1f %%", 100.0 * (busy.cpuNanos() - carriers) / carriers) + "): " + seen);
        }
    }

    /**
     * Steps of work W and 3W with an identity step between them (it holds the 3W step: a stage wraps the rest of the pipeline):
     * the 3W step's CPU is three times the W step's, ±10 %, and the identity step — whose event contains the 3W step's — gets
     * under 2 % of the flow's samples: each sample goes to the innermost step.
     */
    @Test
    void cpuSplitsBetweenStepsInTheRatioOfTheirWork() throws Exception {
        long w = iterationsFor(Duration.ofMillis(10));
        List<Map<String, Object>> steps = List.of(busy("light", w), Map.of("type", "pass", "name", "between"), busy("heavy", 3 * w));
        StepCpu cpu = new StepCpu(StepCpu.Limits.DEFAULT);
        try (CpuSampler sampler = sampler(cpu)) {
            run(pipeline("cpu-split-warm", steps), "cpu-split-warm", 50);
            run(pipeline("cpu-split", steps), "cpu-split", MESSAGES);
            drain(sampler);
        }
        List<StepCpu.Row> rows = cpu.rows("cpu-split");
        assertEquals(List.of(3, 1, 2), rows.stream().map(StepCpu.Row::step).toList(), "heavy, light, between: by CPU descending: " + rows);
        for (StepCpu.Row r : rows) assertEquals(MESSAGES, r.executions(), "one execution per message: " + rows);
        StepCpu.Row light = row(rows, 1);
        StepCpu.Row between = row(rows, 2);
        StepCpu.Row heavy = row(rows, 3);
        double ratio = (double) heavy.cpuNanos() / light.cpuNanos();
        assertTrue(ratio >= 2.7 && ratio <= 3.3, "3W over W is 3 ± 10 %, was " + String.format("%.3f", ratio) + ": " + rows);
        long all = rows.stream().mapToLong(StepCpu.Row::samples).sum();
        assertTrue(between.samples() < 0.02 * all, "the identity step gets under 2 % of the flow's samples (" + between.samples() + " of " + all + "): " + rows);
    }

    /**
     * Under load — at least 2 000 messages, 64 in flight, a cheap step that parks once as on I/O, over at least ten read cycles — every
     * step event reaches the matcher exactly once across the windows: each step's executions equal the messages sent, and no sample is
     * unattributed. A live stream handed some of these events without their thread ({@code RecordedEvent.getThread()} null, F-22; here a
     * step whose virtual thread parked and was mounted again): 13 706 of 13 962 executions counted on the code before the fix
     * (agent/work/FIX-108-r3-red-load.log); a step that never parks did not show it in one run.
     */
    @Test
    void underLoadEveryStepCountsEveryMessage() throws Exception {
        long cheap = iterationsFor(Duration.ofNanos(250_000));
        StepCpu cpu = new StepCpu(StepCpu.Limits.DEFAULT);
        int sent;
        StepCpu.Totals before;
        try (CpuSampler sampler = sampler(cpu)) {
            before = cpu.totals();
            sent = load(pipeline("cpu-load", List.of(Map.of("type", "pass", "name", "in"), Map.of("type", "hop", "name", "cheap", "iterations", cheap))), "cpu-load", 2_000,
                    10, 64);
            drain(sampler);
        }
        List<StepCpu.Row> rows = cpu.rows("cpu-load");
        StepCpu.Totals after = cpu.totals();
        assertEquals(List.of(1, 2), rows.stream().map(StepCpu.Row::step).sorted().toList(), "both steps have a row: " + rows);
        for (StepCpu.Row r : rows) {
            assertEquals(sent, r.executions(), "step " + r.step() + " counted every one of the " + sent + " messages: " + rows + ", totals " + after);
        }
        assertEquals(0, after.unattributed() - before.unattributed(), "no sample is unattributed — none lost its thread (F-22): " + before + " → " + after);
    }

    /** One platform thread alone, the caller: the CPU nanos of {@link #work}{@code (iterations)}, five runs, one reading each. */
    static long[] calibrationAlone(long iterations) {
        assertTrue(!Thread.currentThread().isVirtual(), "fixture: ThreadMXBean reads this platform thread's CPU");
        long[] spent = new long[5];
        for (int r = 0; r < spent.length; r++) {
            long t0 = THREADS.getCurrentThreadCpuTime();
            sink += work(iterations);
            spent[r] = THREADS.getCurrentThreadCpuTime() - t0;
        }
        return spent;
    }

    /**
     * A step that runs across several read cycles — half its work, then it waits while two drains complete (each at least two cycles),
     * then the other half — gets the samples taken before it ended, those read in the cycles before its own included: its CPU is both
     * halves' CPU, each sample counted once, and no sample is unattributed. Without the earlier cycles' samples it would show about half,
     * with samples fed again from a later dump (the recording keeps the chunks since the last dump) about double. Each half is ≈600 ms,
     * ≈300 samples at the 4 ms effective period: even counted as Poisson (an upper bound: a CPU-time timer fires every period of the
     * thread's CPU, so the count is nearly exact) σ ≈ 1/√300 ≈ 5.8 %, so ±20 % is ≈3.5 σ (the stated ±10 % holds from 1 000). The
     * calibration, on one platform thread alone as the step runs alone, is taken before and after the step and its median is the
     * reference (as the busy step's): a drift of the machine between them is halved (FIX-108 r6: a calibration after the step alone
     * read 213 ms a half where the probe before it read 300 ms, and the step +23 %).
     */
    @Test
    void aStepSpanningSeveralReadsGetsTheSamplesTakenBeforeItEnded() throws Exception {
        long half = iterationsFor(Duration.ofMillis(600));
        long[] calibratedBefore = calibrationAlone(half);
        CountDownLatch halfway = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        StepFactory spanning = factory("spanning", s -> Stage.of(e -> {
            sink += work(half);
            halfway.countDown();
            try {
                if (!release.await(BOUND.toSeconds(), TimeUnit.SECONDS)) throw new IllegalStateException("fixture: the step was not released within " + BOUND);
            } catch (InterruptedException x) {
                throw new IllegalStateException("fixture: the step's wait was interrupted", x);
            }
            sink += work(half);
            return e;
        }));
        Pipeline p = new FlowBuilder(rt.runtime(), List.of(spanning), List.of(), Map.of()).stepEvents(true)
                .build(flow("cpu-spanning", List.of(Map.of("type", "spanning", "name", "long"))), NO_FILES);
        StepCpu cpu = new StepCpu(StepCpu.Limits.DEFAULT);
        int readsWhileOpen;
        try (CpuSampler sampler = sampler(cpu)) {
            Envelope in = rt.ingress("cpu-spanning", Headers.empty(), Buffer.buffer("<x/>"));
            AtomicReference<Object> outcome = new AtomicReference<>();
            Thread message = Thread.ofVirtual().name("cpu-it-spanning").start(() -> {
                try {
                    outcome.set(p.apply(in));
                } catch (Throwable x) {
                    outcome.set(x);
                }
            });
            if (!halfway.await(BOUND.toSeconds(), TimeUnit.SECONDS)) fail("fixture: the step's first half did not end within " + BOUND);
            int firedAtHalf = timer.fired();
            drain(sampler);
            drain(sampler);
            // the second drain can complete one cycle after the first: the step stays open until four cycles ran (counted, never timed)
            until(() -> timer.fired() - firedAtHalf >= 4, "fixture: four read cycles while the step is open (fired " + (timer.fired() - firedAtHalf) + " at the drains)");
            readsWhileOpen = timer.fired() - firedAtHalf;
            release.countDown();
            if (!message.join(BOUND)) fail("the message did not end within " + BOUND + ", at " + Arrays.toString(message.getStackTrace()));
            assertInstanceOf(Envelope.class, outcome.get(), "fixture: the message went through");
            drain(sampler);
        }
        long[] calibratedAfter = calibrationAlone(half);
        double expected = 2 * median(calibratedBefore, calibratedAfter);
        List<StepCpu.Row> rows = cpu.rows("cpu-spanning");
        String seen = "rows " + rows + ", expected " + Math.round(expected) + " ns (both halves; a half's median calibration before " + Math.round(median(calibratedBefore))
                + " ns, after " + Math.round(median(calibratedAfter)) + " ns), totals " + cpu.totals() + ", read cycles while the step was open " + readsWhileOpen;
        System.out.printf("FIX-108 spanning step: estimate/expected %.3f, %s%n", rows.isEmpty() ? Double.NaN : rows.get(0).cpuNanos() / expected, seen);
        assertEquals(1, rows.size(), seen);
        StepCpu.Row step = rows.get(0);
        assertEquals(1, step.executions(), "one execution: " + seen);
        assertTrue(Math.abs(step.cpuNanos() - expected) <= 0.20 * expected,
                "both halves, each sample once (" + String.format("%+.1f %%", 100 * (step.cpuNanos() - expected) / expected) + "): " + seen);
        assertEquals(0, cpu.totals().unattributed(), "no sample is unattributed: " + seen);
        assertTrue(readsWhileOpen >= 4, "fixture: the step stayed open across at least four read cycles: " + seen);
    }

    /** Sampling on changes nothing on the snapshots: every step of a recorded message, on its virtual thread, keeps CPU unknown (F-21). */
    @Test
    void snapshotCpuStaysUnknownWithSamplingOn() throws Exception {
        long iterations = iterationsFor(Duration.ofMillis(5));
        InMemoryMessageStore store = new InMemoryMessageStore();
        StepCpu cpu = new StepCpu(StepCpu.Limits.DEFAULT);
        List<Envelope> ins;
        try (CpuSampler sampler = sampler(cpu)) {
            Pipeline recorded = new FlowBuilder(rt.runtime(), List.of(BUSY), List.of(), Map.of()).stepEvents(true).recording(store, Meter.system())
                    .build(flow("cpu-recorded", List.of(busy("a", iterations), busy("b", iterations))), NO_FILES);
            ins = run(recorded, "cpu-recorded", 20);
            drain(sampler);
        }
        List<StepCpu.Row> rows = cpu.rows("cpu-recorded");
        assertEquals(List.of(20L, 20L), List.of(row(rows, 1).executions(), row(rows, 2).executions()), "witness: the sampler saw both steps of every message: " + rows);
        for (Envelope in : ins) {
            List<Snapshot> timeline = store.timeline(in.id()).await();
            assertEquals(List.of(0, 1, 2), timeline.stream().map(Snapshot::step).toList(), "fixture: the ingress and both steps recorded");
            assertEquals(Collections.nCopies(3, Snapshot.CPU_UNKNOWN), timeline.stream().map(Snapshot::cpuNanos).toList(),
                    "the snapshot's CPU stays unknown on the message's virtual thread, sampling on or not");
        }
    }

    /** The sampler dump files in {@code java.io.tmpdir} now: {@code flottila-cpu-*.jfr}. */
    static Set<Path> dumpFiles() throws java.io.IOException {
        Set<Path> found = new HashSet<>();
        try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(Path.of(System.getProperty("java.io.tmpdir")), "flottila-cpu-*.jfr")) {
            files.forEach(found::add);
        }
        return found;
    }

    /**
     * {@code close()} ends the sampler's recording and deletes its dump file: while open the sampler keeps one dump,
     * {@code flottila-cpu-*.jfr} in {@code java.io.tmpdir} (overwritten every cycle); once closed its CPU-time recording and that file
     * are gone, a drain fails, and what runs afterwards never reaches its matcher — while a second sampler, started after the close,
     * drains it.
     */
    @Test
    void closeEndsTheRecordingAndRemovesItsDumpFile() throws Exception {
        assertEquals(0, cpuStreams(), "fixture: no CPU-time recording runs before the case");
        Set<Path> before = dumpFiles();
        long iterations = iterationsFor(Duration.ofMillis(2));
        StepCpu first = new StepCpu(StepCpu.Limits.DEFAULT);
        CpuSampler closing = sampler(first);
        Set<Path> whileOpen = new HashSet<>();
        try {
            run(pipeline("cpu-before", List.of(busy("a", iterations))), "cpu-before", 10);
            drain(closing);
            assertEquals(10, row(first.rows("cpu-before"), 1).executions(), "witness: the sampler matched what ran while it was open");
            assertEquals(1, cpuStreams(), "the open sampler's recording takes CPU-time samples");
            whileOpen.addAll(dumpFiles());
            whileOpen.removeAll(before);
            assertEquals(1, whileOpen.size(), "while open the sampler keeps one dump file flottila-cpu-*.jfr in java.io.tmpdir: " + whileOpen);
        } finally {
            closing.close();
        }
        assertEquals(0, cpuStreams(), "closed: its recording is gone");
        for (Path dump : whileOpen) assertFalse(Files.exists(dump), "closed: its dump file is deleted: " + dump);
        assertThrows(IllegalStateException.class, () -> closing.drained().await(BOUND.toMillis(), TimeUnit.MILLISECONDS),
                "a drain asked of a closed sampler fails, it never waits for a read that cannot come");

        StepCpu second = new StepCpu(StepCpu.Limits.DEFAULT);
        try (CpuSampler next = sampler(second)) {
            run(pipeline("cpu-after", List.of(busy("a", iterations))), "cpu-after", 10);
            drain(next);
        }
        assertEquals(10, row(second.rows("cpu-after"), 1).executions(), "witness: what ran after the close was committed and drained");
        assertEquals(List.of(), first.rows("cpu-after"), "the closed sampler got nothing of what ran after its close");
    }

    /**
     * The recording's time reaches the matcher: at each read the sampler advances it to the window's end, so a sample no step holds —
     * CPU spent on a virtual thread outside every flow — is unattributed once older than the max pending age (1 ms here).
     */
    @Test
    void aSampleOutsideEveryStepAgesOutAtARead() throws Exception {
        long iterations = iterationsFor(Duration.ofMillis(60));
        StepCpu cpu = new StepCpu(new StepCpu.Limits(Duration.ofSeconds(2), Duration.ofMillis(1), 65_536, 131_072, 4_096));
        try (CpuSampler sampler = sampler(cpu)) {
            Thread outside = Thread.ofVirtual().name("cpu-it-no-step").start(() -> sink += work(iterations));
            if (!outside.join(Duration.ofSeconds(60))) fail("the work did not end within 60 s, at " + Arrays.toString(outside.getStackTrace()));
            drain(sampler);
        }
        StepCpu.Totals t = cpu.totals();
        assertTrue(t.samples() >= 5, "witness: 60 ms of CPU on a virtual thread were sampled: " + t);
        assertEquals(0, t.attributed(), "no step ran: " + t);
        assertTrue(t.unattributed() >= 1, "samples older than the max pending age at a read are unattributed: " + t);
    }

    /**
     * {@code close()} returns only once the sampler's thread has fed its last event. The test holds the matcher's monitor: the sampler's
     * thread, {@code flottila-cpu-sampler}, blocks on it at its next read (a JSON thread dump, which lists virtual threads, shows it).
     * A close started then must not return while the monitor is held; after it returned, the thread ends.
     */
    @Test
    void closeReturnsOnlyOnceTheStreamsThreadEnded() throws Exception {
        StepCpu cpu = new StepCpu(StepCpu.Limits.DEFAULT);
        CpuSampler sampler = sampler(cpu);
        Thread closer;
        boolean returnedWhileHeld;
        List<JsonObject> aliveWhenItReturned;
        synchronized (cpu) {
            until(() -> samplerThreads().stream().anyMatch(t -> "BLOCKED".equals(t.getString("state"))
                    && String.valueOf(t.getString("blockedOn")).startsWith(StepCpu.class.getName() + "@")), "the sampler's thread blocks on the matcher's monitor");
            closer = Thread.ofVirtual().name("cpu-it-closer").start(sampler::close);
            // close() either returns, or settles: blocked on the held monitor itself, or parked past the recording's close (no JFR frame on its stack)
            until(() -> closer.getState() == Thread.State.TERMINATED || closer.getState() == Thread.State.BLOCKED
                    || (closer.getState() == Thread.State.WAITING && LockSupport.getBlocker(closer) != null
                    && Arrays.stream(closer.getStackTrace()).noneMatch(f -> f.getClassName().startsWith("jdk.jfr"))), "close() returns or waits");
            returnedWhileHeld = closer.getState() == Thread.State.TERMINATED;
            aliveWhenItReturned = returnedWhileHeld ? samplerThreads() : List.of();
        }
        if (!closer.join(BOUND)) fail("close() did not return within " + BOUND + " of the monitor's release, at " + Arrays.toString(closer.getStackTrace()));
        assertEquals(List.of(), aliveWhenItReturned, "close() returned while the sampler's thread was still feeding the matcher");
        // close() returns once the thread ran its last handler; the thread itself ends a moment later (bounded, never a sleep)
        until(() -> samplerThreads().isEmpty(), "after close() returned, the flottila-cpu-sampler thread ends");
    }

    /** Every thread named {@code flottila-cpu-sampler} now, virtual ones included: the JVM's JSON thread dump, each as it lists it. */
    List<JsonObject> samplerThreads() {
        Path dump = tmp.resolve("threads-" + dumps.incrementAndGet() + ".json");
        try {
            ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class).dumpThreads(dump.toString(), HotSpotDiagnosticMXBean.ThreadDumpFormat.JSON);
            JsonObject all = new JsonObject(Files.readString(dump, StandardCharsets.UTF_8));
            List<JsonObject> found = new ArrayList<>();
            for (Object container : all.getJsonObject("threadDump").getJsonArray("threadContainers")) {
                for (Object thread : ((JsonObject) container).getJsonArray("threads")) {
                    if ("flottila-cpu-sampler".equals(((JsonObject) thread).getString("name"))) found.add((JsonObject) thread);
                }
            }
            return found;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("fixture: the thread dump " + dump, e);
        }
    }

    /** Spins until {@code condition}, at most {@link #BOUND}. */
    static void until(java.util.function.BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + BOUND.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) fail("not within " + BOUND + ": " + what);
            Thread.onSpinWait();
        }
    }

    /** A twin of the step event: its name, its {@code step} a string — the sampler cannot read it as a step's int. */
    @Name("io.flottila.Step")
    @Label("CpuSamplerIT twin step")
    @StackTrace(false)
    static final class TwinStep extends Event {
        @Label("Flow") String flow;
        @Label("Step") String step;
        @Label("Name") String name;
    }

    /** What a JUL logger publishes from the capture's construction to its close. */
    static final class Captured implements AutoCloseable {
        final Logger logger;
        final List<LogRecord> records = new CopyOnWriteArrayList<>();
        final Handler handler = new Handler() {
            @Override public void publish(LogRecord r) { records.add(r); }
            @Override public void flush() { }
            @Override public void close() { }
        };

        Captured(String name) {
            logger = Logger.getLogger(name);
            logger.addHandler(handler);
        }

        List<LogRecord> warnings() {
            return records.stream().filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue()).toList();
        }

        String describe() {
            return records.stream().map(r -> r.getLevel() + " " + r.getMessage() + " " + r.getThrown()).toList().toString();
        }

        @Override
        public void close() {
            logger.removeHandler(handler);
        }
    }

    /**
     * An event the sampler cannot read — a twin of the step event whose {@code step} is a string — is logged at WARNING on the sampler's
     * logger with the exception, never only printed to stderr, and the read goes on: steps that run afterwards are counted, the sampler
     * has not failed (F-22: an unreadable event never aborts a cycle).
     */
    @Test
    void anEventItCannotReadIsLoggedAndTheReadGoesOn() throws Exception {
        long iterations = iterationsFor(Duration.ofMillis(2));
        StepCpu cpu = new StepCpu(StepCpu.Limits.DEFAULT);
        try (Captured log = new Captured(CpuSampler.class.getName());
             CpuSampler sampler = sampler(cpu);
             Recording twins = new Recording()) {
            twins.enable(TwinStep.class);
            twins.start();
            TwinStep twin = new TwinStep();
            assertTrue(twin.isEnabled(), "fixture: the twin is recorded");
            twin.flow = "cpu-twin";
            twin.step = "one";
            twin.name = "twin";
            twin.commit();
            until(() -> log.warnings().stream().anyMatch(r -> r.getThrown() != null), "a WARNING with the exception of the unreadable event is logged on "
                    + CpuSampler.class.getName());

            run(pipeline("cpu-after-twin", List.of(busy("a", iterations))), "cpu-after-twin", 3);
            drain(sampler);
            assertEquals(3, row(cpu.rows("cpu-after-twin"), 1).executions(), "the read went on: the steps after the twin are counted");
            assertFalse(sampler.failed(), "an unreadable event is no failure of the sampler: " + log.describe());
        }
    }

    /**
     * The sampler's recording stopped from outside while the sampler is open: at its next read {@code failed()} turns true and stays so,
     * a WARNING with the cause is logged, the recording is closed, a drain asked afterwards fails, and the aggregate keeps what was fed.
     * A sampler its owner closed has not failed, and neither its close nor a running sampler logs a warning (review r1, blocking 2;
     * the node then answers {@code sampling:"failed"}).
     */
    @Test
    void aRecordingStoppedFromOutsideFailsTheSampler() throws Exception {
        assertEquals(0, cpuStreams(), "fixture: no CPU-time recording runs before the case");
        long iterations = iterationsFor(Duration.ofMillis(2));
        StepCpu cpu = new StepCpu(StepCpu.Limits.DEFAULT);
        try (Captured log = new Captured(CpuSampler.class.getName())) {
            CpuSampler closed = sampler(new StepCpu(StepCpu.Limits.DEFAULT));
            closed.close();
            assertFalse(closed.failed(), "a sampler its owner closed has not failed");

            CpuSampler sampler = sampler(cpu);
            try {
                run(pipeline("cpu-ending", List.of(busy("a", iterations))), "cpu-ending", 10);
                drain(sampler);
                assertFalse(sampler.failed(), "witness: a running sampler has not failed");
                assertEquals(List.of(), log.warnings(), "a close and a running sampler log no warning: " + log.describe());
                Recording recording = FlightRecorder.getFlightRecorder().getRecordings().stream()
                        .filter(r -> r.getState() == RecordingState.RUNNING && "true".equals(r.getSettings().get("jdk.CPUTimeSample#enabled")))
                        .reduce((a, b) -> { throw new AssertionError("fixture: one CPU-time recording runs"); }).orElseThrow();
                assertTrue(recording.stop(), "fixture: the sampler's recording stops");

                until(sampler::failed, "the sampler reports its recording no longer runs");
                until(() -> log.warnings().stream().anyMatch(r -> r.getThrown() != null), "a WARNING with the cause is logged on " + CpuSampler.class.getName());
                List<String> causes = causes(log.warnings().stream().filter(r -> r.getThrown() != null).findFirst().orElseThrow().getThrown());
                assertTrue(causes.stream().anyMatch(m -> m.contains(RecordingState.STOPPED.name())),
                        "the WARNING's cause names the recording's state, " + RecordingState.STOPPED + " — found at the cycle that saw it, not a cycle later as a missing cut: " + causes);
                until(() -> recording.getState() == RecordingState.CLOSED, "the failed sampler closes its recording");
                assertThrows(IllegalStateException.class, () -> sampler.drained().await(BOUND.toMillis(), TimeUnit.MILLISECONDS),
                        "a drain asked of a failed sampler fails");
                assertEquals(10, row(cpu.rows("cpu-ending"), 1).executions(), "the aggregate keeps what was fed");
            } finally {
                sampler.close();
            }
            assertTrue(sampler.failed(), "a close after the end leaves it failed");
        }
    }

    /** The messages of {@code thrown} and of each of its causes, outermost first. */
    static List<String> causes(Throwable thrown) {
        List<String> messages = new ArrayList<>();
        for (Throwable t = thrown; t != null; t = t.getCause()) messages.add(t.getClass().getName() + ": " + t.getMessage());
        return messages;
    }

    /**
     * A drain still waiting when the sampler is closed fails: asked before a cycle, it is taken by that cycle and waits for the next
     * one (its window is read a cycle later); a close before that next cycle answers it with an {@link IllegalStateException}, it never
     * waits for a read that cannot come. The cycles run on a {@link FakeTimer} the case fires itself: one cycle, then the close.
     */
    @Test
    void aDrainWaitingForItsReadWhenTheSamplerIsClosedFails() throws Exception {
        FakeClock clock = FakeClock.at(CLOCK.instant());
        FakeTimer manual = new FakeTimer(clock);
        CpuSampler sampler = CpuSampler.start(PERIOD, new StepCpu(StepCpu.Limits.DEFAULT), clock, manual, READ_EVERY);
        Future<Void> drained;
        try {
            until(() -> manual.pending() == 1, "the sampler's thread schedules its first cycle");
            drained = sampler.drained();
            clock.advance(manual.nextDelay());   // cycle 1: takes the drain, commits cut 1
            until(() -> manual.pending() == 1, "cycle 1 ran and the sampler's thread schedules the next");
            assertFalse(drained.isComplete(), "witness: after one cycle the drain still waits for the read of the window its cut closed");
        } finally {
            sampler.close();
        }
        assertFalse(sampler.failed(), "fixture: the sampler was closed, it did not fail");
        IllegalStateException ended = assertThrows(IllegalStateException.class, () -> drained.await(BOUND.toMillis(), TimeUnit.MILLISECONDS),
                "a drain waiting when the sampler is closed fails, it never waits for the read that cannot come");
        assertTrue(drained.failed(), "the drain failed: " + ended);
    }

    /**
     * A twin of JFR's own {@code jdk.CPUTimeSamplesLost}: its name, its {@code lostSamples} an int as the JDK's (JFR accepts a Java
     * event of that name, probed on JDK 25.0.5). Its recording is started before the sampler's, which enables the name.
     */
    @Name("jdk.CPUTimeSamplesLost")
    @Label("CpuSamplerIT twin lost samples")
    @StackTrace(false)
    static final class TwinLost extends Event {
        @Label("Lost Samples") int lostSamples;
    }

    /** Lost samples a twin reports: far above any the JVM itself reports in a case. */
    static final int TWIN_LOST = 1_000_000;

    /** A recording of {@link TwinLost} alone, started — before the sampler's (FIX-108 r6 spec). */
    static Recording twins() {
        Recording twins = new Recording();
        twins.enable(TwinLost.class);
        twins.start();
        return twins;
    }

    /** The one JFR recording running now that takes CPU-time samples: the sampler's. */
    static Recording samplersRecording() {
        return FlightRecorder.getFlightRecorder().getRecordings().stream()
                .filter(r -> r.getState() == RecordingState.RUNNING && "true".equals(r.getSettings().get("jdk.CPUTimeSample#enabled")))
                .reduce((a, b) -> { throw new AssertionError("fixture: one CPU-time recording runs"); }).orElseThrow(() -> new AssertionError("fixture: no CPU-time recording runs"));
    }

    /**
     * Read {@code n}, by hand: the clock advanced to the sampler's next cycle (the {@link FakeTimer} fires it on this thread), then a
     * wait until the sampler's thread has run it and scheduled the next — or has ended, failed.
     */
    static void fire(FakeClock clock, FakeTimer manual, CpuSampler sampler, int n) {
        clock.advance(manual.nextDelay());
        until(() -> manual.pending() == 1 || sampler.failed(), "read " + n + " ran and the sampler's thread scheduled the next (or failed)");
    }

    /** The executions of {@code flow}'s steps the matcher holds. */
    static long executions(StepCpu cpu, String flow) {
        return cpu.rows(flow).stream().mapToLong(StepCpu.Row::executions).sum();
    }

    /**
     * A lost-samples event in the window reaches the totals: a {@link TwinLost} of {@value #TWIN_LOST} committed while the sampler
     * runs is counted once its window is drained (review r2: the lost feed was only bounded above).
     */
    @Test
    void aLostSamplesEventReachesTheTotals() throws Exception {
        StepCpu cpu = new StepCpu(StepCpu.Limits.DEFAULT);
        try (Recording twins = twins(); CpuSampler sampler = sampler(cpu)) {
            TwinLost lost = new TwinLost();
            assertTrue(lost.isEnabled(), "fixture: the twin is recorded");
            lost.lostSamples = TWIN_LOST;
            lost.commit();
            drain(sampler);
            assertTrue(cpu.totals().lost() >= TWIN_LOST, "the lost samples the event reports reach the totals: " + cpu.totals());
            assertFalse(sampler.failed(), "fixture: the sampler ran: " + cpu.totals());
        }
    }

    /**
     * A dump that no longer holds the cut opening its window (the recording dropped that chunk, as past 64 MiB) is logged at WARNING as
     * retention exceeded, the sampler goes on, and what it still holds of an earlier window is not fed again: the window opens where
     * the last read ended. Reads by hand on a {@link FakeTimer}. A {@link TwinLost} of {@value #TWIN_LOST} begun before read 1 (its
     * key, the start time, lies before cut 1) is committed after the case has rotated the chunk holding cut 1 (a running dump rotates
     * it), so it sits in the next chunk; read 2 feeds it (window cut 0 to cut 1). Then the case drops the recording's oldest chunk
     * ({@code setMaxSize(size − 1)} removes exactly the oldest finished one, the newest is always kept — JDK 25 PlatformRecording
     * .trimToSize, probed), the one holding cut 1: read 3's dump lacks cut 1 but still holds the twin and cut 2.
     */
    @Test
    void aDumpLackingItsWindowsOpeningCutWarnsAndFeedsNothingTwice() throws Exception {
        FakeClock clock = FakeClock.at(CLOCK.instant());
        FakeTimer manual = new FakeTimer(clock);
        StepCpu cpu = new StepCpu(StepCpu.Limits.DEFAULT);
        try (Recording twins = twins(); Captured log = new Captured(CpuSampler.class.getName())) {
            CpuSampler sampler = CpuSampler.start(PERIOD, cpu, clock, manual, READ_EVERY);
            try {
                until(() -> manual.pending() == 1, "the sampler's thread schedules its first read");
                TwinLost lost = new TwinLost();
                assertTrue(lost.isEnabled(), "fixture: the twin is recorded");
                lost.begin();
                lost.lostSamples = TWIN_LOST;
                fire(clock, manual, sampler, 1);   // commits cut 1, after the twin's start
                Recording recording = samplersRecording();
                recording.dump(tmp.resolve("rotate.jfr"));   // the chunk holding cut 1 ends here
                lost.commit();   // in the next chunk, its start before cut 1

                fire(clock, manual, sampler, 2);   // window (cut 0, cut 1]: the twin
                long afterTwo = cpu.totals().lost();
                assertTrue(afterTwo >= TWIN_LOST, "witness: read 2 fed the twin of its window: " + cpu.totals());
                assertFalse(sampler.failed(), "fixture: read 2 ran: " + log.describe());
                assertEquals(List.of(), log.warnings(), "fixture: no warning before the chunk is dropped: " + log.describe());

                long size = recording.getSize();
                recording.setMaxSize(size - 1);   // the oldest chunk, cut 1's, goes; the twin's is the newest finished one, kept
                long dropped = size - recording.getSize();
                recording.setMaxSize(CpuSampler.MAX_SIZE);
                assertTrue(dropped > 0 && recording.getSize() > 0, "fixture: a chunk was dropped and one kept: " + size + " bytes, " + dropped + " dropped");

                fire(clock, manual, sampler, 3);   // window (cut 1, cut 2], cut 1 gone
                long refed = cpu.totals().lost() - afterTwo;
                assertFalse(sampler.failed(), "a dump lacking the cut opening its window is no failure: " + log.describe());
                assertTrue(log.warnings().stream().anyMatch(r -> String.valueOf(r.getMessage()).contains("retention exceeded")),
                        "read 3 logs a WARNING that retention was exceeded: " + log.describe());
                assertTrue(refed < TWIN_LOST, "read 3 does not feed the twin again, fed by read 2 (the window opens where read 2 ended, never at the dump's "
                        + "first event): " + refed + " lost samples fed by read 3, totals " + cpu.totals());
            } finally {
                sampler.close();
            }
        }
    }

    /**
     * A dump lacking both cuts of its window (the chunks holding cut 1 and cut 2 dropped) skips it: a WARNING, the sampler goes on,
     * nothing is fed and a drain waits; the next read, whose dump lacks only its opening cut, feeds from where the last fed window
     * ended — the steps that ran meanwhile — and answers the drains. Reads by hand on a {@link FakeTimer}: after read 2 the case
     * rotates the chunk holding cut 2 and another, drops every finished chunk but the newest ({@code setMaxSize(1)}), then runs three
     * steps (in the chunk read 3 rotates, which its trim keeps as the newest). A drain asked before read 2 waits for the window read 3
     * skips; one asked before read 3 is taken by it; read 4 answers both.
     */
    @Test
    void aDumpLackingBothCutsSkipsItsWindowAndTheNextReadFeedsIt() throws Exception {
        long iterations = iterationsFor(Duration.ofMillis(2));
        Pipeline skipped = pipeline("cpu-skip", List.of(busy("a", iterations)));
        FakeClock clock = FakeClock.at(CLOCK.instant());
        FakeTimer manual = new FakeTimer(clock);
        StepCpu cpu = new StepCpu(StepCpu.Limits.DEFAULT);
        try (Captured log = new Captured(CpuSampler.class.getName())) {
            CpuSampler sampler = CpuSampler.start(PERIOD, cpu, clock, manual, READ_EVERY);
            try {
                until(() -> manual.pending() == 1, "the sampler's thread schedules its first read");
                fire(clock, manual, sampler, 1);
                Future<Void> beforeTwo = sampler.drained();
                fire(clock, manual, sampler, 2);   // takes beforeTwo: it waits for window (cut 1, cut 2], read 3's
                assertFalse(sampler.failed(), "fixture: read 2 ran: " + log.describe());
                assertEquals(List.of(), log.warnings(), "fixture: no warning before the chunks are dropped: " + log.describe());

                Recording recording = samplersRecording();
                recording.dump(tmp.resolve("rotate-1.jfr"));   // the chunk holding cut 2 ends
                recording.dump(tmp.resolve("rotate-2.jfr"));   // and a newer one, so it is not the newest
                long size = recording.getSize();
                recording.setMaxSize(1);   // every finished chunk but the newest goes: cut 1's and cut 2's
                long left = recording.getSize();
                recording.setMaxSize(CpuSampler.MAX_SIZE);
                assertTrue(left > 0 && left < size, "fixture: chunks were dropped and the newest kept: " + size + " → " + left + " bytes");

                run(skipped, "cpu-skip", 3);   // after cut 2
                Future<Void> beforeThree = sampler.drained();
                fire(clock, manual, sampler, 3);   // window (cut 1, cut 2], both cuts gone
                assertFalse(sampler.failed(), "a dump lacking both cuts of its window is no failure, the window is skipped: " + log.describe());
                assertTrue(log.warnings().stream().anyMatch(r -> String.valueOf(r.getMessage()).contains("retention exceeded")),
                        "read 3 logs a WARNING that retention was exceeded: " + log.describe());
                assertEquals(0, executions(cpu, "cpu-skip"), "read 3 feeds nothing: " + cpu.rows("cpu-skip"));
                assertFalse(beforeTwo.isComplete(), "the drain waiting for the skipped window still waits after read 3");
                assertFalse(beforeThree.isComplete(), "the drain read 3 took waits for the next read");

                fire(clock, manual, sampler, 4);   // window (cut 2, cut 3], cut 2 gone: opens where read 2 ended
                assertFalse(sampler.failed(), "read 4 ran: " + log.describe());
                assertEquals(3, executions(cpu, "cpu-skip"), "read 4 feeds the steps that ran after cut 2, read 3 skipped: " + cpu.rows("cpu-skip"));
                assertTrue(beforeTwo.succeeded(), "read 4 answers the drain waiting since read 2: " + beforeTwo);
                assertTrue(beforeThree.succeeded(), "read 4 answers the drain read 3 took: " + beforeThree);
            } finally {
                sampler.close();
            }
        }
    }

    /**
     * A cancel of the next read that throws as the sampler ends still closes its recording and deletes its dump file: the clean-up
     * goes on past the timer's failure. Reads by hand on a {@link FakeTimer} whose cancels throw; one read makes the dump file.
     */
    @Test
    void aCancelThatThrowsStillClosesTheRecordingAndDeletesItsDumpFile() throws Exception {
        assertEquals(0, cpuStreams(), "fixture: no CPU-time recording runs before the case");
        FakeClock clock = FakeClock.at(CLOCK.instant());
        FakeTimer manual = new FakeTimer(clock);
        AtomicInteger cancels = new AtomicInteger();
        Timer throwing = (delay, task) -> {
            manual.schedule(delay, task);
            return () -> {
                cancels.incrementAndGet();
                throw new IllegalStateException("cpu-it: this timer's cancel throws");
            };
        };
        Set<Path> before = dumpFiles();
        Set<Path> mine = new HashSet<>();
        CpuSampler sampler = CpuSampler.start(PERIOD, new StepCpu(StepCpu.Limits.DEFAULT), clock, throwing, READ_EVERY);
        try {
            until(() -> manual.pending() == 1, "the sampler's thread schedules its first read");
            fire(clock, manual, sampler, 1);
            mine.addAll(dumpFiles());
            mine.removeAll(before);
            assertEquals(1, mine.size(), "fixture: read 1 made the sampler's dump file: " + mine);
            assertEquals(1, cpuStreams(), "fixture: the sampler's recording runs");

            sampler.close();
            assertTrue(sampler.failed(), "a clean-up that throws at the owner's close — the timer's cancel — is a failure of the sampler, logged, not hidden");
            assertTrue(cancels.get() >= 1, "fixture: the sampler cancelled its next read, and the cancel threw");
            assertEquals(0, cpuStreams(), "the recording is closed though the cancel threw");
            for (Path dump : mine) assertFalse(Files.exists(dump), "the dump file is deleted though the cancel threw: " + dump);
        } finally {
            sampler.close();
            FlightRecorder.getFlightRecorder().getRecordings().stream()
                    .filter(r -> r.getState() == RecordingState.RUNNING && "true".equals(r.getSettings().get("jdk.CPUTimeSample#enabled")))
                    .forEach(Recording::close);
            for (Path dump : mine) Files.deleteIfExists(dump);
        }
    }

    /**
     * The recording's disk stays bounded however many cycles run: every read drops the chunks older than its dump (no maxAge, F-22
     * amended). A child JVM with a JFR repository of its own ({@code -XX:FlightRecorderOptions:repository}) runs a sampler read every
     * 50 ms with a busy virtual thread for 60 timer firings; from the 10th to the 60th its repository holds at most 1 MiB of chunk
     * files (two of ≈130 KiB here) — kept without the drop, it grows by one chunk a cycle, ≈7.5 MiB by the 60th. Witness: the
     * recorder wrote a new chunk about every cycle (at least 40 distinct ones listed), so a bound that held is the drop's.
     */
    @Test
    void theRecordingsDiskStaysBoundedOverManyReads() throws Exception {
        Path repository = Files.createDirectories(tmp.resolve("repository"));
        Path childTmp = Files.createDirectories(tmp.resolve("child-tmp"));
        Child run = child(List.of("-XX:FlightRecorderOptions:repository=" + repository, "-Djava.io.tmpdir=" + childTmp),
                List.of("disk", "50", "10", "60", repository.toString()));
        assertEquals(0, run.exit(), "fixture: the child ran its cycles: " + run.output());
        List<long[]> disk = run.output().lines().filter(l -> l.startsWith("disk ")).map(l -> {
            assertFalse(l.endsWith("failed"), "fixture: the child's sampler did not fail: " + l);
            return Arrays.stream(l.substring("disk ".length()).split(" ")).mapToLong(Long::parseLong).toArray();
        }).toList();
        assertEquals(51, disk.size(), "fixture: one line per firing from the 10th to the 60th: " + run.output());
        long most = disk.stream().mapToLong(d -> d[1]).max().orElseThrow();
        long[] last = disk.get(disk.size() - 1);
        String seen = "max " + most + " bytes; firing, bytes, chunk files, distinct chunks so far: "
                + disk.stream().map(Arrays::toString).toList();
        assertTrue(disk.stream().allMatch(d -> d[2] >= 1), "witness: the repository is the one the recorder writes: " + seen);
        assertTrue(last[3] >= 40, "witness: the recorder wrote a new chunk about every cycle: " + seen);
        assertTrue(most <= 1024 * 1024, "the repository stays within 1 MiB from the 10th cycle to the 60th, each read dropping the chunks before its dump: " + seen);
    }

    /**
     * Without a flight recorder ({@code -XX:-FlightRecorder}, a child JVM) the start is refused with an
     * {@link IllegalStateException} naming F-21 or FIX-108; the same child with the recorder starts (the refusal is the flag's).
     * The other refusal, an OS other than Linux, cannot be shown on Linux without a fake.
     */
    @Test
    void startRefusesWithoutFlightRecorder() throws Exception {
        Child refused = child(List.of("-XX:-FlightRecorder"));
        assertEquals(3, refused.exit(), "refused with an IllegalStateException: " + refused.output());
        assertTrue(refused.output().contains("refused: java.lang.IllegalStateException"), refused.output());
        assertTrue(refused.output().contains("F-21") || refused.output().contains("FIX-108"), "the refusal names F-21 or FIX-108: " + refused.output());

        Child started = child(List.of());
        assertEquals(0, started.exit(), "witness: with the recorder the same child starts: " + started.output());
        assertTrue(started.output().contains("started, period PT0.004S"), started.output());
    }

    record Child(int exit, String output) {}

    /** {@link CpuSamplerChild} in a JVM of its own with {@code options}, on this test's class path, its environment empty. */
    Child child(List<String> options) throws Exception {
        return child(options, List.of());
    }

    /** {@link CpuSamplerChild} with {@code args} in a JVM of its own with {@code options}, on this test's class path, its environment empty. */
    Child child(List<String> options, List<String> args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(options);
        command.addAll(List.of("-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), CpuSamplerChild.class.getName()));
        command.addAll(args);
        Path output = Files.createTempFile(tmp, "child", ".txt");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().clear();
        Process process = builder.start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("the child did not exit within 60 s: " + Files.readString(output, StandardCharsets.UTF_8));
        }
        return new Child(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
    }
}
