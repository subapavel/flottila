package io.flottila.runtime.journal;

import io.flottila.api.Timer;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared pieces of the journal tests (CORE-4a): the one place that builds a {@link JournalConfig} (so the record's
 * component order is written down once), test keys, deterministic bodies, bounded waits without sleeps, crash images of
 * an open journal, a timer that logs what was scheduled and a JUL capture of the journal package's log.
 */
final class JournalFixture {

    /** Every wait in these tests is a spin or a latch with this bound; it is a failure detector, never a timing. */
    static final Duration BOUND = Duration.ofSeconds(10);

    /** The fake clocks start at .850 so that a duration measured in whole seconds or milliseconds crosses a boundary. */
    static final Instant START = Instant.parse("2026-01-01T00:00:00.850Z");

    /** The tenant key id every record uses unless a case says otherwise. */
    static final int KEY = 1;

    private JournalFixture() { }

    /** Mutable settings over the design's defaults (CORE-3 items 1, 3, 4); {@link #config()} builds the record. */
    static final class Settings {
        Path dir;
        long segmentBytes = 64L << 20;
        int segmentRecords = 65_536;
        Duration segmentAge = Duration.ofSeconds(1);
        long pendingBytes = 16L << 20;
        Duration appendWait = Duration.ofSeconds(5);
        long recordMax = 256L << 10;
        String node = "node-1";

        Settings(Path dir) { this.dir = dir; }

        Settings segmentBytes(long v) { segmentBytes = v; return this; }
        Settings segmentRecords(int v) { segmentRecords = v; return this; }
        Settings segmentAge(Duration v) { segmentAge = v; return this; }
        Settings pendingBytes(long v) { pendingBytes = v; return this; }
        Settings appendWait(Duration v) { appendWait = v; return this; }
        Settings recordMax(long v) { recordMax = v; return this; }
        Settings at(Path v) { dir = v; return this; }

        JournalConfig config() {
            return new JournalConfig(dir, segmentBytes, segmentRecords, segmentAge, pendingBytes, appendWait, recordMax, node);
        }
    }

    static Settings settings(Path dir) { return new Settings(dir); }

    /** A test AES-256 key derived from its id (never a secret: a fixture). */
    static SecretKey key(int id) {
        byte[] k = new byte[32];
        new SplittableRandom(0x5EED0000L + id).nextBytes(k);
        return new SecretKeySpec(k, "AES");
    }

    /** The tenant key lookup over exactly these ids (null for any other id). */
    static IntFunction<SecretKey> keys(int... ids) {
        Map<Integer, SecretKey> m = new HashMap<>();
        for (int id : ids) m.put(id, key(id));
        return m::get;
    }

    /** {@code size} deterministic bytes derived from {@code id}: the same id always gives the same body. */
    static byte[] body(String id, int size) {
        byte[] b = new byte[size];
        new SplittableRandom(id.hashCode() * 31L + size).nextBytes(b);
        return b;
    }

    static JournalRecord record(String id, int size) {
        return new JournalRecord(id, 0, "in", KEY, body(id, size));
    }

    /** An abandoned-signal that never fires: the save is never given up. */
    static Future<?> live() {
        return Promise.<Void>promise().future();
    }

    /** Waits (bounded) for {@code f}; a failure is rethrown as is (Vert.x {@code await}). */
    static <T> T await(Future<T> f) throws TimeoutException {
        return f.await(BOUND.toSeconds(), TimeUnit.SECONDS);
    }

    /** Waits (bounded) until {@code f} completes, asserts that it failed and returns its cause. */
    static Throwable failureOf(Future<?> f) {
        until(f::isComplete, () -> "the future completes");
        assertTrue(f.failed(), "expected a failed future, it succeeded with " + f.result());
        return f.cause();
    }

    /**
     * The failure of an append whichever way it is reported: a refused call (nothing reserved, no ref) or an
     * {@link Journal.Appended} whose {@code forced} future fails.
     */
    static Throwable appendFailure(Journal journal, JournalRecord record) {
        Journal.Appended appended;
        try {
            appended = journal.append(record, live());
        } catch (Exception refused) {
            return refused;
        }
        return failureOf(appended.forced());
    }

    /** Spins (no sleep) until {@code condition}; fails after {@link #BOUND} with the message built at that moment. */
    static void until(BooleanSupplier condition, Supplier<String> what) {
        long deadline = System.nanoTime() + BOUND.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) throw new AssertionError("not within " + BOUND + ": " + what.get());
            Thread.onSpinWait();
        }
    }

    /** True when {@code t} is parked (WAITING or TIMED_WAITING with a blocker), i.e. not merely between two steps. */
    static boolean parked(Thread t) {
        Thread.State s = t.getState();
        return (s == Thread.State.WAITING || s == Thread.State.TIMED_WAITING) && LockSupport.getBlocker(t) != null;
    }

    /** Waits until {@code t} is parked; the failure carries its stack. */
    static void untilParked(Thread t, String where) {
        until(() -> parked(t), () -> where + ": " + t + " is " + t.getState() + " at " + Arrays.toString(t.getStackTrace()));
    }

    /** A call running on a virtual thread of its own (an append that may wait for room blocks only that thread). */
    static final class Outcome<T> {
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private final Thread thread;

        Outcome(Callable<T> call) {
            thread = Thread.ofVirtual().name("journal-test-caller").start(() -> {
                try {
                    result.complete(call.call());
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                }
            });
        }

        boolean done() { return result.isDone(); }

        Thread thread() { return thread; }

        /** The value (bounded wait); a failure of the call fails the test with it as the cause. */
        T join() throws InterruptedException {
            try {
                return result.get(BOUND.toSeconds(), TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                throw new AssertionError("the call failed", e.getCause());
            } catch (TimeoutException e) {
                throw new AssertionError("the call did not return within " + BOUND + ": " + Arrays.toString(thread.getStackTrace()));
            }
        }

        /** The failure (bounded wait); a value fails the test. */
        Throwable failure() throws InterruptedException {
            try {
                T value = result.get(BOUND.toSeconds(), TimeUnit.SECONDS);
                throw new AssertionError("expected the call to fail, it returned " + value);
            } catch (ExecutionException e) {
                return e.getCause();
            } catch (TimeoutException e) {
                throw new AssertionError("the call did not end within " + BOUND + ": " + Arrays.toString(thread.getStackTrace()));
            }
        }
    }

    static <T> Outcome<T> onVirtual(Callable<T> call) { return new Outcome<>(call); }

    /**
     * What a kill -9 of this open journal would leave: a copy of {@code dir} into {@code image}, taken while every
     * append the case made is forced and the writer is idle. A file the seam opened but never forced is a pre-open
     * in flight (a crash before its header force): it is left out, as if the crash came before it existed.
     */
    static Path crashImage(Path dir, HeldFiles files, Path image) throws IOException {
        var skip = files.neverForced();
        List<Path> all;
        try (Stream<Path> walk = Files.walk(dir)) {
            all = walk.toList();
        }
        for (Path p : all) {
            Path to = image.resolve(dir.relativize(p).toString());
            if (Files.isDirectory(p)) {
                Files.createDirectories(to);
            } else if (!skip.contains(p.toAbsolutePath().normalize())) {
                Files.createDirectories(to.getParent());
                Files.copy(p, to);
            }
        }
        return image;
    }

    /** {@code file} as it would be found under {@code image}, when {@code file} lives under {@code dir}. */
    static Path inImage(Path dir, Path image, Path file) {
        return image.resolve(dir.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString());
    }

    /** The encoded bytes of a record in its segment file: {@code ref.position()} is its offset, {@code ref.length()} its size. */
    static byte[] recordBytes(Path segment, JournalRef ref) throws IOException {
        byte[] all = Files.readAllBytes(segment);
        assertTrue(ref.position() + ref.length() <= all.length, "the record " + ref + " lies inside " + segment + " (" + all.length + " bytes)");
        return Arrays.copyOfRange(all, (int) ref.position(), (int) (ref.position() + ref.length()));
    }

    /** True when any file under {@code dir} contains {@code needle}. */
    static boolean anyFileContains(Path dir, byte[] needle) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        for (Path f : files) if (HeldFiles.indexOf(Files.readAllBytes(f), needle, 0) >= 0) return true;
        return false;
    }

    /** The {@link FakeTimer} with a log of every schedule: its delay and whether it fired or was cancelled. */
    static final class LoggedTimer implements Timer {
        static final class Scheduled {
            final Duration delay;
            final AtomicBoolean fired = new AtomicBoolean();
            final AtomicBoolean cancelled = new AtomicBoolean();

            Scheduled(Duration delay) { this.delay = delay; }

            @Override public String toString() { return delay + (fired.get() ? " fired" : cancelled.get() ? " cancelled" : " pending"); }
        }

        private final FakeTimer inner;
        private final List<Scheduled> log = new CopyOnWriteArrayList<>();

        LoggedTimer(FakeTimer inner) { this.inner = inner; }

        @Override public Cancellable schedule(Duration delay, Runnable task) {
            Scheduled s = new Scheduled(delay);
            Cancellable c = inner.schedule(delay, () -> {
                s.fired.set(true);
                task.run();
            });
            log.add(s);
            return () -> {
                boolean was = c.cancel();
                if (was) s.cancelled.set(true);
                return was;
            };
        }

        List<Scheduled> log() { return List.copyOf(log); }

        List<Scheduled> withDelay(Duration delay) { return log.stream().filter(s -> s.delay.equals(delay)).toList(); }
    }

    /** Captures the JUL records of the journal package ({@code System.Logger} resolves to JUL in the runtime tests). */
    static final class LogCapture implements AutoCloseable {
        private final Logger logger;   // a strong reference: JUL holds loggers weakly
        private final List<LogRecord> records = new CopyOnWriteArrayList<>();
        private final Handler handler = new Handler() {
            @Override public void publish(LogRecord r) { records.add(r); }
            @Override public void flush() { }
            @Override public void close() { }
        };

        LogCapture() {
            logger = Logger.getLogger("io.flottila.runtime.journal");
            handler.setLevel(Level.ALL);
            logger.addHandler(handler);
        }

        long count(Level level) { return records.stream().filter(r -> r.getLevel().equals(level)).count(); }

        /** The records captured so far, in the order they were published (CORE-4c1: formatted with their parameters). */
        List<LogRecord> records() { return List.copyOf(records); }

        List<String> lines() {
            List<String> l = new ArrayList<>();
            for (LogRecord r : records) l.add(r.getLevel() + " " + r.getLoggerName() + ": " + r.getMessage());
            return l;
        }

        @Override public void close() { logger.removeHandler(handler); }
    }
}
