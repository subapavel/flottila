package io.flottila.runtime.node;

import io.flottila.api.Timer;
import io.flottila.core.record.StepCpu;
import io.flottila.core.record.StepEvent;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import jdk.jfr.Category;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import jdk.jfr.StackTrace;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/**
 * The node's CPU sampler (F-21, D-094, F-22): one on-disk JFR {@link Recording} of {@code jdk.CPUTimeSample} (throttle the period,
 * no stack trace), {@code jdk.CPUTimeSamplesLost}, the {@link StepEvent}s and its own cut marker, read in completed form and fed into
 * a {@link StepCpu}: each sample with its thread, whether that is virtual, its time and its own {@code samplingPeriod}; each step
 * event with its thread, start, end, flow, step and name; each lost count; and after each window the window's end
 * ({@link StepCpu#advance}).
 *
 * <p>Why completed recordings (F-22): a live stream hands some virtual-thread events without their thread, while the same chunk
 * read whole resolves every one. So a read cycle runs every {@code readEvery} (5 s on a node), on the injected {@link Timer}, whose
 * task only signals: the read itself runs on the sampler's virtual thread, {@code flottila-cpu-sampler}, one cycle at a time. Cut 0
 * is committed at the start; cycle k dumps the recording to the sampler's one dump file (overwritten each cycle), iterates it with
 * {@link RecordingFile}, drops the chunks the last cycle kept (written before its dump, so before cut k-1), feeds only the window
 * (c<sub>k-2</sub>, c<sub>k-1</sub>] — a step by its end time, a sample or a lost count by its start time, both cuts' times taken
 * from the same dump as the events (a dump whose first chunk differs dates every event up to ±53 µs apart from the last one, the
 * order kept), no clock read — in time order, advances the matcher to c<sub>k-1</sub>, then commits cut k. The windows are disjoint
 * and each is read one cycle after it closed, so every event is fed exactly once and one written up to a cycle late still is; the
 * aggregate trails the present by one to two cycles. A cut follows its cycle's dump, so the next window lies in the chunks written
 * after that dump, which the recording keeps up to 64 MiB however late the next cycle runs. An event the recorder still hands
 * without its thread counts its execution only (a step) or is unattributed (a sample) ({@link #onStep}, {@link #onSample}).
 *
 * <p>More than 64 MiB written in one cycle drops chunks before a read: logged at WARNING as retention exceeded, the sampler goes on
 * and what they held is not fed. A dump without the cut opening its window opens it where the last fed window ended, a time an
 * earlier dump dated (±53 µs: an event that close to it may be fed twice or not at all); a dump without the cut closing its window
 * skips it — nothing fed, the matcher not advanced, the drains waiting for it wait on — and the next read feeds from where the last
 * fed window ended. The loss can reach past the chunks the recorder dropped: the trim drops the oldest chunks up to the size the
 * last cycle kept, so when the recorder had already dropped some of those it counts the newer chunks short, drops some of them too
 * and can drop the one holding cut k-1, where the next window opens (the next read warns, as above); and after a skipped window,
 * an opening cut written late enough to lie in the next dump opens the window there, not where the last fed one ended, so what
 * lies between is not fed (no probe has seen such a late cut). One sampler runs per node; each recording in the JVM shares the
 * recorder's chunks, so a second sampler (a test's) reads the same steps and samples, and each looks up only its own cuts (its
 * recording's id above the cut in the marker).
 *
 * <p>An event the sampler cannot read is logged at WARNING and the cycle goes on. A cycle that fails, or a
 * recording no longer running (stopped from outside), is logged at WARNING: the sampler has {@link #failed()} for good, its
 * recording is closed and its aggregate stays as last fed. {@link #start} returns once the recording runs; {@link #close} returns
 * once the sampler's thread has finished its cycle in progress, closed the recording and deleted the dump file. A JVM without the
 * flight recorder, or an OS other than Linux (where {@code jdk.CPUTimeSample} exists), is refused.
 *
 * @since 0.1
 */
public final class CpuSampler implements AutoCloseable {

    static final String CPU_TIME_SAMPLE = "jdk.CPUTimeSample";
    static final String SAMPLES_LOST = "jdk.CPUTimeSamplesLost";
    static final String DRAIN = "io.flottila.CpuSamplerDrain";
    /** A node's read cycle (F-22). */
    static final Duration READ_EVERY = Duration.ofSeconds(5);
    /** The most the recording keeps on disk between two reads. */
    static final long MAX_SIZE = 64L * 1024 * 1024;

    private static final System.Logger LOG = System.getLogger(CpuSampler.class.getName());

    /** The cut a read cycle commits after its dump (cut 0 at the start): {@link #marker} its recording and cycle; enabled by the sampler's recording only. */
    @Name(DRAIN)
    @Label("Flottila CPU Sampler Drain")
    @Category("Flottila")
    @Enabled(false)
    @StackTrace(false)
    static final class DrainMarker extends Event {
        @Label("Marker")
        long marker;
    }

    /** One event of a window, as the matcher takes it, by its time. */
    private record Read(long key, Consumer<StepCpu> into) {}

    private final Duration period;
    private final Instant since;
    private final StepCpu cpu;
    private final Timer timer;
    private final Duration readEvery;
    private final Recording recording;
    private final Thread reader = Thread.ofVirtual().name("flottila-cpu-sampler").unstarted(this::reading);
    /** Released by the timer when a cycle is due, and by {@link #close}. */
    private final Semaphore due = new Semaphore(0);
    /** Done once the sampler's thread has closed the recording and deleted the dump file. */
    private final CompletableFuture<Void> ended = new CompletableFuture<>();
    /** The {@link #drained()} calls the sampler's thread has not taken yet. */
    private final Queue<Promise<Void>> asked = new ConcurrentLinkedQueue<>();
    /** Set once {@link #close} was called. */
    private volatile boolean closing;
    /** Why a cycle failed; null while none did. */
    private volatile IllegalStateException failure;
    // read and written by the sampler's thread only
    /** The drains taken before the last cut: answered once the window that cut closes has been fed, at the next cycle. */
    private final List<Promise<Void>> waiting = new ArrayList<>();
    private Timer.Cancellable next = () -> false;
    /** The dump file, created at the first cycle. */
    private Path file;
    /** The size of the chunks the recording held after the last read: the ones the next read drops. */
    private long kept;
    /** The end of the last window fed, as the dump that fed it dated it; where a window whose opening cut was dropped opens. */
    private long fed = Long.MIN_VALUE;

    private CpuSampler(Duration period, Instant since, StepCpu cpu, Timer timer, Duration readEvery, Recording recording) {
        this.period = period;
        this.since = since;
        this.cpu = cpu;
        this.timer = timer;
        this.readEvery = readEvery;
        this.recording = recording;
    }

    /**
     * Starts a sampler of {@code period} into {@code into}, a read cycle every {@code readEvery} on {@code timer}; {@link #since()} is
     * {@code clock}'s instant now; the node passes its own timer (no timer of the sampler's own, F-22). Returns once its recording
     * runs: what is committed from then on is fed, one to two cycles later. A recording or a thread that does not start is closed
     * again and its failure thrown.
     *
     * @throws IllegalStateException the JVM has no flight recorder or the OS is not Linux
     */
    static CpuSampler start(Duration period, StepCpu into, Clock clock, Timer timer, Duration readEvery) {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(into, "into");
        Objects.requireNonNull(timer, "timer");
        Objects.requireNonNull(readEvery, "readEvery");
        String os = System.getProperty("os.name", "");
        if (!FlightRecorder.isAvailable() || !os.startsWith("Linux")) {
            throw new IllegalStateException("CPU sampling (F-21, FIX-108) needs the JDK's flight recorder on Linux, where jdk.CPUTimeSample exists: "
                    + "flight recorder available " + FlightRecorder.isAvailable() + ", os.name '" + os + "'");
        }
        FlightRecorder.register(StepEvent.class);
        FlightRecorder.register(DrainMarker.class);
        Recording recording = new Recording();
        recording.setName("flottila-cpu-sampler");
        recording.enable(CPU_TIME_SAMPLE).with("throttle", period.toMillis() + "ms").withoutStackTrace();
        recording.enable(SAMPLES_LOST);
        recording.enable(StepEvent.class).withoutStackTrace();
        recording.enable(DrainMarker.class).withoutStackTrace();
        recording.setToDisk(true);
        recording.setMaxSize(MAX_SIZE);   // no maxAge: a read drops what it no longer needs (cycle)
        CpuSampler sampler = new CpuSampler(period, clock.instant(), into, timer, readEvery, recording);
        try {
            recording.start();
            sampler.cut(0);   // the first window closes here: what was committed between the start and this cut
            sampler.reader.start();
        } catch (RuntimeException | Error notStarted) {   // nothing is left recording
            recording.close();
            throw notStarted;
        }
        return sampler;
    }

    /** The sampling period asked for (each sample carries the one JFR used). */
    public Duration period() {
        return period;
    }

    /** When the sampler started: the aggregate is cumulative since then. */
    public Instant since() {
        return since;
    }

    /**
     * Whether a read cycle failed (logged at WARNING): true for good, the aggregate as last fed. The clean-up counts as well: a cancel
     * of the next read that throws as the sampler ends, or a dump file it cannot delete, sets it, logged as "the CPU sampler's read
     * failed".
     */
    public boolean failed() {
        return failure != null;
    }

    /**
     * Completes once everything committed before this call has been fed: after the cycle whose cut follows this call and one more
     * (the window that cut closes is read a cycle later). Fails once the sampler has ended, closed or failed.
     */
    public Future<Void> drained() {
        Promise<Void> drained = Promise.promise();
        asked.add(drained);
        if (ended.isDone()) failAsked();   // ended already; one that ends after this check fails it on its own thread
        return drained.future();
    }

    /**
     * Stops the reads: returns once the sampler's thread has finished its cycle in progress, closed the recording and deleted the dump
     * file — nothing is fed afterwards (the last one to two cycles, not read yet, are not).
     */
    @Override
    public void close() {
        closing = true;
        due.release();
        ended.join();
    }

    /**
     * {@code step}'s fields onto {@code cpu}: {@code thread} the event's Java thread id, null when the recorder handed it without
     * its thread — then the step counts its execution and is not kept (review r1).
     */
    static void onStep(StepCpu cpu, Long thread, long startNs, long endNs, String flow, int step, String name) {
        if (thread == null) {
            cpu.stepWithoutThread(flow, step, name);
        } else {
            cpu.step(thread, startNs, endNs, flow, step, name);
        }
    }

    /**
     * A CPU-time sample's fields onto {@code cpu}: {@code thread} the sampled thread's Java id, null when the recorder handed it
     * without its thread — then it is unattributed, {@code virtual} unknown (review r1).
     */
    static void onSample(StepCpu cpu, Long thread, boolean virtual, long atNs, long periodNs) {
        if (thread == null) {
            cpu.sampleWithoutThread();
        } else {
            cpu.sample(thread, virtual, atNs, periodNs);
        }
    }

    /** The sampler's thread: a cycle each time the timer signals, until {@link #close} or a failure; then it cleans up and ends. */
    private void reading() {
        Throwable thrown = null;
        try (Closeable cleanup = this::cleanUp) {
            for (long cut = 1; ; cut++) {
                next = timer.schedule(readEvery, due::release);
                due.acquireUninterruptibly();
                if (closing) break;
                cycle(cut);
            }
        } catch (IOException | RuntimeException | Error e) {   // a failed cycle (the clean-up's own failure suppressed in it), or the clean-up's
            thrown = e;
        }
        ended(thrown);
    }

    /**
     * Cycle {@code cut}: the recording must still run; dump it, read it whole, drop the chunks the last cycle kept (written before its
     * dump, so before the cut opening the next window). Then feed the window between the last two cuts in time order (both cuts'
     * times from this dump, as the events'; the opening one, if dropped, where the last fed window ended), advance the matcher to its
     * end and answer the drains waiting for it; or, the closing cut dropped, skip the window and keep them waiting. Either way the
     * drains asked so far wait for this cycle's cut, committed last.
     */
    private void cycle(long cut) throws IOException {
        RecordingState state = recording.getState();
        if (state != RecordingState.RUNNING) {
            throw new IllegalStateException("the CPU sampler's recording is " + state + ", no longer running: stopped from outside (F-22, FIX-108)");
        }
        if (file == null) file = Files.createTempFile("flottila-cpu-", ".jfr");
        recording.dump(file);   // the file truncated, then the recording's chunks (JDK 25: WriteablePath, PlatformRecording.dump)
        Map<Long, Long> cuts = new HashMap<>();
        List<Read> events = new ArrayList<>();
        try (RecordingFile dump = new RecordingFile(file)) {
            while (dump.hasMoreEvents()) read(dump.readEvent(), cuts, events);
        }
        recording.setMaxSize(Math.max(1, recording.getSize() - kept));   // the chunks the last cycle kept go: written before its dump
        recording.setMaxSize(MAX_SIZE);
        kept = recording.getSize();
        Long opening = cuts.get(marker(cut - 2));
        Long closing = cuts.get(marker(cut - 1));
        if (closing == null) {
            LOG.log(System.Logger.Level.WARNING, "CPU sampler: retention exceeded, the dump of read " + cut + " lacks the cut closing its window (cut "
                    + (cut - 1) + "); the window is skipped, the next read feeds from where the last fed one ended (F-22, FIX-108)");
        } else {
            if (cut > 1 && opening == null) {   // read 1's window opens at the start: no cut opens it
                LOG.log(System.Logger.Level.WARNING, "CPU sampler: retention exceeded, the dump of read " + cut + " lacks the cut opening its window (cut "
                        + (cut - 2) + "); the window opens where the last fed one ended, ±53 µs (F-22, FIX-108)");
            }
            feed(events, opening != null ? opening : fed, closing);
            waiting.forEach(Promise::tryComplete);   // taken before cut - 1, which closed the window just fed
            waiting.clear();
        }
        for (Promise<Void> drain = asked.poll(); drain != null; drain = asked.poll()) waiting.add(drain);
        cut(cut);
    }

    /** Feeds the window ({@code opens}, {@code closes}] of {@code events} in time order and advances the matcher to its end. */
    private void feed(List<Read> events, long opens, long closes) {
        events.sort(Comparator.comparingLong(Read::key));   // stable: one thread's inner step, committed first, is fed before its outer one
        for (Read event : events) {
            if (event.key() > closes) break;   // the next window's, read again next cycle
            if (event.key() > opens) event.into().accept(cpu);
        }
        cpu.advance(closes);
        fed = closes;
    }

    /** Commits this sampler's cut {@code n}. */
    private void cut(long n) {
        DrainMarker marker = new DrainMarker();
        marker.marker = marker(n);
        marker.commit();
    }

    /**
     * One event of the dump: a cut's time noted (every sampler's, by marker); a step, sample or lost count kept; anything else in the
     * JVM's shared chunks passed over. One it cannot read is logged, never fed.
     */
    private void read(RecordedEvent event, Map<Long, Long> cuts, List<Read> events) {
        String type = event.getEventType().getName();
        try {
            switch (type) {
                case DRAIN -> cuts.put(event.getLong("marker"), nanos(event.getEndTime()));
                case StepEvent.NAME -> events.add(step(event));
                case CPU_TIME_SAMPLE -> events.add(sample(event));
                case SAMPLES_LOST -> {
                    int lost = event.getInt("lostSamples");
                    events.add(new Read(nanos(event.getStartTime()), into -> into.lost(lost)));
                }
                default -> { }
            }
        } catch (RuntimeException unreadable) {
            LOG.log(System.Logger.Level.WARNING, "CPU sampler: an event of " + type + " cannot be read; the read goes on (F-22, FIX-108)", unreadable);
        }
    }

    private static Read step(RecordedEvent step) {
        long end = nanos(step.getEndTime());
        Long thread = Optional.ofNullable(step.getThread()).map(RecordedThread::getJavaThreadId).orElse(null);
        long start = nanos(step.getStartTime());
        String flow = step.getString("flow");
        int number = step.getInt("step");
        String name = step.getString("name");
        return new Read(end, into -> onStep(into, thread, start, end, flow, number, name));
    }

    private static Read sample(RecordedEvent sample) {
        long at = nanos(sample.getStartTime());
        Optional<RecordedThread> thread = Optional.ofNullable(sample.getThread());
        Long id = thread.map(RecordedThread::getJavaThreadId).orElse(null);
        boolean virtual = thread.map(RecordedThread::isVirtual).orElse(false);
        long periodNs = sample.getDuration("samplingPeriod").toNanos();
        return new Read(at, into -> onSample(into, id, virtual, at, periodNs));
    }

    /** This sampler's marker of {@code cut}: its recording's id (unique in the JVM) above the cut. */
    private long marker(long cut) {
        return recording.getId() << 32 | cut;
    }

    /** The sampler's thread ends, whatever ended it: no next cycle, the recording closed, the dump file deleted, each whatever the one before threw. */
    private void cleanUp() throws IOException {
        try {
            next.cancel();
        } finally {
            try {
                recording.close();
            } finally {
                if (file != null) Files.deleteIfExists(file);
            }
        }
    }

    /** The sampler's thread has cleaned up: a failure is kept and logged; then every drain waiting is answered. */
    private void ended(Throwable thrown) {
        if (thrown != null) {
            failure = new IllegalStateException("the CPU sampler's read failed; it reads no more and its aggregate stays as last fed (F-22, FIX-108)", thrown);
            LOG.log(System.Logger.Level.WARNING, failure.getMessage(), failure);
        }
        ended.complete(null);
        waiting.forEach(drain -> drain.tryFail(new IllegalStateException("the CPU sampler ended (F-22, FIX-108)")));
        failAsked();
    }

    private void failAsked() {
        for (Promise<Void> drain = asked.poll(); drain != null; drain = asked.poll()) {
            drain.tryFail(new IllegalStateException("the CPU sampler ended (F-22, FIX-108)"));
        }
    }

    /** {@code t} in nanoseconds since the epoch. */
    private static long nanos(Instant t) {
        return t.getEpochSecond() * 1_000_000_000L + t.getNano();
    }
}
