package io.flottila.core.record;

import org.junit.jupiter.api.Test;

import java.lang.management.ThreadMXBean;
import java.lang.reflect.Proxy;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        long real = Meter.system().cpuNanos();
        assertTrue(real >= Snapshot.CPU_UNKNOWN, "the real platform answers a reading or unknown: " + real);
    }
}
