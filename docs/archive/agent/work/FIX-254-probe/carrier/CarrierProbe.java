import jdk.management.VirtualThreadSchedulerMXBean;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/** FIX-254: does the state of the carrier threads show a virtual thread that the scheduler's mounted count hides? */
public class CarrierProbe {
    static final VirtualThreadSchedulerMXBean S = ManagementFactory.getPlatformMXBean(VirtualThreadSchedulerMXBean.class);
    static final ThreadGroup ROOT = root();
    static Thread[] buf = new Thread[64];
    static int carriers;

    static ThreadGroup root() {
        ThreadGroup g = Thread.currentThread().getThreadGroup();
        while (g.getParent() != null) g = g.getParent();
        return g;
    }

    static int runningCarriers() {
        int n = ROOT.enumerate(buf, true);
        while (n == buf.length) { buf = new Thread[buf.length * 2]; n = ROOT.enumerate(buf, true); }
        int running = 0, seen = 0;
        for (int i = 0; i < n; i++) {
            Thread t = buf[i];
            if (t.getClass().getName().equals("jdk.internal.misc.CarrierThread")) {
                seen++;
                if (t.getState() == Thread.State.RUNNABLE) running++;
            }
            buf[i] = null;
        }
        carriers = seen;
        return running;
    }

    static void until(java.util.function.BooleanSupplier c, String what) {
        long end = System.nanoTime() + 5_000_000_000L;
        while (!c.getAsBoolean()) {
            if (System.nanoTime() > end) { System.out.println("  NOT REACHED: " + what); return; }
            Thread.onSpinWait();
        }
    }

    static void sample(String label, Thread subject, int n) {
        int byMx = 0, byCar = 0, both = 0;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            boolean mx = S.getMountedVirtualThreadCount() == 0 && S.getQueuedVirtualThreadCount() == 0;
            boolean car = runningCarriers() == 0;
            if (mx) byMx++;
            if (car) byCar++;
            if (mx && car) both++;
        }
        long ns = (System.nanoTime() - t0) / n;
        System.out.printf("%-34s samples=%d idle-by-scheduler-count=%d idle-by-carrier-states=%d idle-by-both=%d carriers=%d subject=%s %dns/sample%n",
                label, n, byMx, byCar, both, carriers, subject.getState(), ns);
    }

    public static void main(String[] a) throws Exception {
        int n = 200_000;
        Thread parked = Thread.ofVirtual().name("parked").start(LockSupport::park);
        until(() -> parked.getState() == Thread.State.WAITING, "parked");
        sample("parked (one carrier so far)", parked, n);

        AtomicBoolean stop = new AtomicBoolean();
        Thread spinner = Thread.ofVirtual().name("spinner").start(() -> { while (!stop.get()) Thread.onSpinWait(); });
        until(() -> S.getMountedVirtualThreadCount() > 0, "spinner mounted");
        sample("spinning", spinner, n);
        stop.set(true); spinner.join();

        Process cat = new ProcessBuilder("cat").start();
        AtomicBoolean entering = new AtomicBoolean();
        Thread reader = Thread.ofVirtual().name("reader").start(() -> {
            entering.set(true);
            try { cat.getInputStream().read(); } catch (Exception e) { throw new RuntimeException(e); }
        });
        until(() -> entering.get() && S.getMountedVirtualThreadCount() == 0 && S.getQueuedVirtualThreadCount() == 0, "reader hidden from the count");
        sample("blocked in a Process pipe read", reader, n);
        cat.getOutputStream().close(); reader.join();

        byte[] line = new byte[4337];
        java.util.Arrays.fill(line, (byte) 'x'); line[line.length - 1] = '\n';
        AtomicBoolean stopW = new AtomicBoolean();
        Thread writer = Thread.ofVirtual().name("writer").start(() -> { while (!stopW.get()) { System.err.write(line, 0, line.length); System.err.flush(); } });
        until(() -> writer.getState() == Thread.State.RUNNABLE, "writer running");
        sample("writing 4337 bytes to System.err", writer, n);
        stopW.set(true); writer.join();

        until(() -> runningCarriers() == 0, "carriers parked again");
        sample("parked again (all carriers idle)", parked, n);

        long t0 = System.nanoTime();
        int x = 0;
        for (int i = 0; i < 1_000_000; i++) x += runningCarriers();
        System.out.printf("runningCarriers alone: %d ns/call (carriers=%d)%n", (System.nanoTime() - t0) / 1_000_000, carriers);
        LockSupport.unpark(parked); parked.join();
        System.exit(0);
    }
}
