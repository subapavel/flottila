package dev.rail8.testkit;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * A pool of TCP ports for tests and the bench harness that never lie in the range the kernel chooses from. Probing "a free
 * port" by binding port 0, reading the number and closing is racy: port 0 and every outbound connection draw from the same
 * kernel range, so anything that binds 0 or connects between the probe and the real bind can be given the probed number.
 * {@link #free()} hands out ports from 20000-32767 minus the kernel's range (on Linux the two numbers of
 * {@code /proc/sys/net/ipv4/ip_local_port_range}, elsewhere 49152-65535), so only an explicit bind to that very number can
 * take one.
 *
 * <p>A candidate is handed out only if a wildcard bind succeeds at that moment, and the cursor walks upward and wraps, so one
 * JVM does not hand the same port out twice before it has wrapped. The process-wide cursor starts at an offset derived from
 * the process id so that two JVMs on one host start apart.
 *
 * <p>What it does not guarantee: another process that binds the same number explicitly can still take it; and on a host whose
 * kernel chooses from everywhere (its range covers the whole pool) there is no usable port, the helper falls back to binding
 * port 0 and handing that number on, and the guarantee is gone. After one whole pass without a bindable port it throws
 * {@link IllegalStateException}.
 *
 * <p>For this repository's tests and the bench harness only.
 *
 * @since 0.1
 */
public final class Ports {

    static final int POOL_FROM = 20000;
    static final int POOL_TO = 32767;
    private static final Path KERNEL_RANGE_FILE = Path.of("/proc/sys/net/ipv4/ip_local_port_range");
    private static final int[] IANA_DEFAULT = {49152, 65535};

    private final int poolFrom;
    private final int poolTo;
    private final int kernelFrom;
    private final int kernelTo;
    private int cursor;

    Ports(int poolFrom, int poolTo, int kernelFrom, int kernelTo, int start) {
        this.poolFrom = poolFrom;
        this.poolTo = poolTo;
        this.kernelFrom = kernelFrom;
        this.kernelTo = kernelTo;
        this.cursor = start >= poolFrom && start <= poolTo ? start : poolFrom;
    }

    private static final class Holder {
        static final Ports INSTANCE = create();

        private static Ports create() {
            int[] kernel = kernelRange(KERNEL_RANGE_FILE);
            int size = POOL_TO - POOL_FROM + 1;
            int start = POOL_FROM + (int) (ProcessHandle.current().pid() % size);
            return new Ports(POOL_FROM, POOL_TO, kernel[0], kernel[1], start);
        }
    }

    /**
     * The process-wide pool's next port.
     *
     * @throws IllegalStateException when no port of the pool can be bound. A malformed kernel range file is read in the holder's static
     *     initializer: the first use fails with {@link ExceptionInInitializerError} caused by an {@code IllegalStateException} that names
     *     the file, and every later use fails with {@link NoClassDefFoundError}
     */
    public static int free() {
        return Holder.INSTANCE.next();
    }

    private boolean usable(int port) {
        return port < kernelFrom || port > kernelTo;
    }

    private static boolean bindable(int port) {
        try (ServerSocket s = new ServerSocket(port)) {
            return true;
        } catch (IOException refused) {
            return false;
        }
    }

    synchronized int next() {
        int size = poolTo - poolFrom + 1;
        boolean any = false;
        for (int i = 0; i < size; i++) {
            int port = cursor;
            cursor = port == poolTo ? poolFrom : port + 1;
            if (!usable(port)) {
                continue;
            }
            any = true;
            if (bindable(port)) {
                return port;
            }
        }
        if (!any) {
            return kernelChosen();
        }
        throw new IllegalStateException("no bindable port in the pool " + poolFrom + ".." + poolTo
                + " (kernel range " + kernelFrom + ".." + kernelTo + " excluded)");
    }

    private static int kernelChosen() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("cannot bind port 0", e);
        }
    }

    static int[] kernelRange(Path file) {
        String content;
        try {
            // read as lines: Files.readString/readAllBytes size a /proc file by its reported length 0 and can return a truncated read
            content = String.join("\n", Files.readAllLines(file));
        } catch (NoSuchFileException missing) {
            return IANA_DEFAULT.clone();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
        String[] parts = content.trim().split("\\s+");
        if (parts.length == 2) {
            try {
                int from = Integer.parseInt(parts[0]);
                int to = Integer.parseInt(parts[1]);
                if (from >= 1 && to <= 65535 && from <= to) {
                    return new int[] {from, to};
                }
            } catch (NumberFormatException notNumbers) {
                // falls through to the exception naming the file
            }
        }
        throw new IllegalStateException(file + " does not hold two port numbers: '" + content.trim() + "'");
    }
}
