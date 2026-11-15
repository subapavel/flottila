package io.flottila.core.record;

import org.junit.jupiter.api.Test;

import java.lang.management.ThreadMXBean;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The system meter over ThreadMXBean: a reading when the platform has one for this thread, unknown otherwise. */
class MeterTest {

    static ThreadMXBean threads(boolean supported, LongSupplier reading) {
        return (ThreadMXBean) Proxy.newProxyInstance(ThreadMXBean.class.getClassLoader(), new Class<?>[] {ThreadMXBean.class}, (proxy, method, args) -> switch (method.getName()) {
            case "isCurrentThreadCpuTimeSupported" -> supported;
            case "getCurrentThreadCpuTime" -> reading.getAsLong();
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    @Test
    void readsTheThreadsCpuTimeOrAnswersUnknown() {
        assertEquals(1_234L, Meter.of(threads(true, () -> 1_234L)).cpuNanos());
        assertEquals(0L, Meter.of(threads(true, () -> 0L)).cpuNanos(), "zero is a reading");
        assertEquals(Snapshot.CPU_UNKNOWN, Meter.of(threads(true, () -> -1L)).cpuNanos(), "the JDK answers -1 for a thread it cannot measure");
        assertEquals(Snapshot.CPU_UNKNOWN, Meter.of(threads(true, () -> -7L)).cpuNanos());
        assertEquals(Snapshot.CPU_UNKNOWN, Meter.of(threads(false, () -> { throw new AssertionError("never asked"); })).cpuNanos());
    }

    /** What {@code read} answered on a thread started by {@code kind}, and whether that thread was virtual. */
    record Reading(boolean virtual, long cpuNanos) {}

    /** Runs {@code read} on a new thread of {@code kind} and joins it; a throw there fails the case. */
    static Reading on(Thread.Builder kind, LongSupplier read) throws InterruptedException {
        AtomicReference<Reading> got = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread t = kind.start(() -> {
            try {
                got.set(new Reading(Thread.currentThread().isVirtual(), read.getAsLong()));
            } catch (Throwable x) {
                thrown.set(x);
            }
        });
        assertTrue(t.join(Duration.ofSeconds(10)), "the reading thread did not end within 10 s, at " + Arrays.toString(t.getStackTrace()));
        if (thrown.get() != null) throw new AssertionError("the reading threw", thrown.get());
        return got.get();
    }

    /**
     * The F-21 tripwire: on JDK 25 ThreadMXBean answers -1 on a virtual thread, so the system meter is unknown on the message's
     * virtual thread — built on a platform thread as a node builds it, or on the virtual thread itself — and reads on a platform
     * thread. Red the day the JDK measures a virtual thread: per-step CPU can then be recorded again (F-21, FIX-106, FIX-108).
     */
    @Test
    void systemMeterIsUnknownOnAVirtualThreadAndReadsOnAPlatformThread() throws InterruptedException {
        assertFalse(Thread.currentThread().isVirtual(), "fixture: the case starts on a platform thread");
        Meter builtOnAPlatformThread = Meter.system();

        Reading virtualShared = on(Thread.ofVirtual(), builtOnAPlatformThread::cpuNanos);
        assertTrue(virtualShared.virtual(), "fixture: read on a virtual thread");
        assertEquals(Snapshot.CPU_UNKNOWN, virtualShared.cpuNanos(),
                "F-21: the JDK does not measure a virtual thread's CPU time; it now does, so step CPU can be recorded (FIX-106)");
        Reading virtualOwn = on(Thread.ofVirtual(), () -> Meter.system().cpuNanos());
        assertTrue(virtualOwn.virtual(), "fixture: built and read on a virtual thread");
        assertEquals(Snapshot.CPU_UNKNOWN, virtualOwn.cpuNanos(), "F-21: a meter built on the virtual thread is unknown there too");

        Reading platformShared = on(Thread.ofPlatform(), builtOnAPlatformThread::cpuNanos);
        assertFalse(platformShared.virtual(), "fixture: read on a platform thread");
        assertTrue(platformShared.cpuNanos() >= 0, "a platform thread's CPU time is a reading: " + platformShared.cpuNanos());
        Reading platformOwn = on(Thread.ofPlatform(), () -> Meter.system().cpuNanos());
        assertFalse(platformOwn.virtual(), "fixture: built and read on a platform thread");
        assertTrue(platformOwn.cpuNanos() >= 0, "a reading: " + platformOwn.cpuNanos());
    }
}
