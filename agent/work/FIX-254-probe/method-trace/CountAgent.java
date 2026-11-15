import java.io.PrintWriter;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import jdk.management.VirtualThreadSchedulerMXBean;

/** A measuring instrument, not product code: records every change of the scheduler's two counters with its time. */
public class CountAgent {
    static final int CAP = 1 << 21;
    static final long[] at = new long[CAP];
    static final int[] mounted = new int[CAP];
    static final long[] queued = new long[CAP];
    static volatile int n;

    public static void premain(String out, Instrumentation inst) {
        Instant anchor = Instant.now();
        long anchorNanos = System.nanoTime();
        long anchorEpoch = anchor.getEpochSecond() * 1_000_000_000L + anchor.getNano();
        Thread t = new Thread(() -> {
            VirtualThreadSchedulerMXBean bean = ManagementFactory.getPlatformMXBean(VirtualThreadSchedulerMXBean.class);
            int lm = -1;
            long lq = -1;
            int k = 0;
            while (true) {
                int m = bean.getMountedVirtualThreadCount();
                long q = bean.getQueuedVirtualThreadCount();
                if ((m != lm || q != lq) && k < CAP) {
                    at[k] = System.nanoTime();
                    mounted[k] = m;
                    queued[k] = q;
                    n = ++k;
                    lm = m;
                    lq = q;
                }
                Thread.onSpinWait();
            }
        }, "count-agent");
        t.setDaemon(true);
        t.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try (PrintWriter w = new PrintWriter(out)) {
                int k = n;
                for (int i = 0; i < k; i++) w.println((anchorEpoch + (at[i] - anchorNanos)) + " " + mounted[i] + " " + queued[i]);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }));
    }
}
