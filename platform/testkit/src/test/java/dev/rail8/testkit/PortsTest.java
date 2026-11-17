package dev.rail8.testkit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The port pool: a port handed out is one the kernel does not choose from, so only an explicit bind to that number can take it.
 * The small pools sit on a run of ports that this test bound and released in its set-up (a first bind check); a port of that
 * run that somebody else takes between the check and a case would make the case fail loudly (a bind refused), never pass falsely.
 */
class PortsTest {

    /** A kernel range far above every small pool of these cases (and above the real pool 20000-32767). */
    static final int FAR_FROM = 40000;
    static final int FAR_TO = 50000;

    @TempDir Path tmp;

    /** The first port of a run of six consecutive ports that can be bound right now. */
    int base;

    @BeforeEach
    void findARunOfBindablePorts() throws IOException {
        int from = 20000 + (int) (ProcessHandle.current().pid() % 2000);
        for (int candidate = from; candidate < 32000; candidate++) {
            if (runBindable(candidate, 6)) {
                base = candidate;
                return;
            }
        }
        throw new AssertionError("no run of six bindable ports between " + from + " and 32000 on this host");
    }

    static boolean runBindable(int first, int count) throws IOException {
        for (int p = first; p < first + count; p++) {
            try (ServerSocket s = new ServerSocket(p)) {
                assertEquals(p, s.getLocalPort());
            } catch (java.net.BindException taken) {
                return false;
            }
        }
        return true;
    }

    static ServerSocket hold(int port) throws IOException {
        return new ServerSocket(port);
    }

    static void closeAll(List<ServerSocket> held) throws IOException {
        for (ServerSocket s : held) {
            s.close();
        }
    }

    // --- the pool, the kernel's range, the order

    @Test
    void portsLieInThePoolOutsideTheKernelRangeInAscendingOrderWithBothBoundsInclusive() {
        // pool b..b+5, kernel b+2..b+3: usable b, b+1, b+4, b+5; both edges of the kernel range are excluded
        Ports ports = new Ports(base, base + 5, base + 2, base + 3, base);
        assertEquals(base, ports.next());
        assertEquals(base + 1, ports.next());
        assertEquals(base + 4, ports.next());
        assertEquals(base + 5, ports.next());
    }

    @Test
    void aPortHandedOutCanBeBoundByTheTestAtOnce() throws IOException {
        Ports ports = new Ports(base, base + 5, FAR_FROM, FAR_TO, base);
        for (int i = 0; i < 6; i++) {
            int port = ports.next();
            try (ServerSocket s = new ServerSocket(port)) {
                assertEquals(port, s.getLocalPort(), "the port is released again after the helper's own bind check");
            }
        }
    }

    @Test
    void successiveCallsGiveDistinctPortsUntilTheCursorWraps() {
        Ports ports = new Ports(base, base + 5, FAR_FROM, FAR_TO, base);
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 6; i++) {
            assertTrue(seen.add(ports.next()), "a port twice before the cursor wrapped");
        }
        assertEquals(6, seen.size());
    }

    @Test
    void theCursorStartsAtTheGivenStartAndWrapsToTheBottomOfThePool() {
        Ports ports = new Ports(base, base + 2, FAR_FROM, FAR_TO, base + 1);
        assertEquals(base + 1, ports.next());
        assertEquals(base + 2, ports.next());
        assertEquals(base, ports.next(), "past the top of the pool the cursor wraps to the bottom");
        assertEquals(base + 1, ports.next(), "and walks on");
    }

    // --- a held candidate

    @Test
    void aHeldCandidateIsSkippedAndTheNextBindableOneIsHandedOut() throws IOException {
        Ports ports = new Ports(base, base + 3, FAR_FROM, FAR_TO, base);
        try (ServerSocket held = hold(base)) {
            assertEquals(base, held.getLocalPort());
            assertEquals(base + 1, ports.next(), "base is held: the one after it is handed out");
        }
    }

    @Test
    void aHeldCandidateAtTheTopOfThePoolIsSkippedAcrossTheWrap() throws IOException {
        Ports ports = new Ports(base, base + 2, FAR_FROM, FAR_TO, base + 2);
        try (ServerSocket held = hold(base + 2)) {
            assertEquals(base + 2, held.getLocalPort());
            assertEquals(base, ports.next(), "the top is held: the cursor wraps to the bottom");
        }
    }

    @Test
    void aPortThatWasHeldIsHandedOutAgainOnceItIsReleased() throws IOException {
        Ports ports = new Ports(base, base + 1, FAR_FROM, FAR_TO, base);
        try (ServerSocket held = hold(base)) {
            assertEquals(base, held.getLocalPort());
            assertEquals(base + 1, ports.next());
        }
        assertEquals(base, ports.next(), "released and under the cursor again: bindable, so handed out");
    }

    // --- exhaustion and the fallback

    @Test
    void aPoolWhoseEveryUsablePortIsHeldThrowsNamingThePoolAndIsUsableAgainWhenOneIsReleased() throws IOException {
        // pool b..b+3, kernel b+1: usable b, b+2, b+3, all three held; b+1 is kernel-chosen and free, yet never handed out
        Ports ports = new Ports(base, base + 3, base + 1, base + 1, base);
        List<ServerSocket> held = new ArrayList<>();
        try {
            held.add(hold(base));
            held.add(hold(base + 2));
            held.add(hold(base + 3));
            IllegalStateException e = assertThrows(IllegalStateException.class, ports::next);
            assertTrue(e.getMessage().contains(String.valueOf(base)) && e.getMessage().contains(String.valueOf(base + 3)),
                    "the message names the pool " + base + ".." + (base + 3) + ": " + e.getMessage());
        } finally {
            closeAll(held);
        }
        int again = ports.next();
        assertTrue(again == base || again == base + 2 || again == base + 3,
                "the failure was the held ports: with them released the pool serves again, never the kernel's " + (base + 1) + ": " + again);
    }

    @Test
    void aPoolWithNoUsablePortFallsBackToAKernelChosenPortThatCanBeBoundEvenWhenEveryPoolPortIsHeld() throws IOException {
        // the kernel's range covers the whole pool; every pool port is held, so only a bind-0 fallback can answer
        Ports ports = new Ports(base, base + 2, 1, 65535, base);
        List<ServerSocket> held = new ArrayList<>();
        try {
            for (int p = base; p <= base + 2; p++) {
                held.add(hold(p));
            }
            int port = ports.next();
            assertFalse(port >= base && port <= base + 2, "a held pool port " + port + " was handed out");
            assertTrue(port > 0, "a real port number: " + port);
            try (ServerSocket s = new ServerSocket(port)) {
                assertEquals(port, s.getLocalPort());
            }
        } finally {
            closeAll(held);
        }
    }

    // --- eight threads

    @Test
    void eightThreadsTakingFiftyPortsEachGetFourHundredDistinctPorts() throws InterruptedException {
        Ports ports = new Ports(20000, 32767, FAR_FROM, FAR_TO, 20000);
        ConcurrentLinkedQueue<Integer> got = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    go.await();
                    for (int i = 0; i < 50; i++) {
                        got.add(ports.next());
                    }
                } catch (Throwable e) {
                    failures.add(e);
                }
            }));
        }
        go.countDown();
        for (Thread t : threads) {
            t.join();
        }
        assertTrue(failures.isEmpty(), "a thread failed: " + failures);
        assertEquals(400, got.size());
        assertEquals(400, new HashSet<>(got).size(), "the same port was handed to two callers");
    }

    // --- the kernel's range file

    @Test
    void kernelRangeReadsTheTwoNumbersOfTheFile() throws IOException {
        Path file = tmp.resolve("ip_local_port_range");
        Files.writeString(file, "40001\t50002\n");
        assertArrayEquals(new int[] {40001, 50002}, Ports.kernelRange(file));
        Files.writeString(file, "1024 5000");
        assertArrayEquals(new int[] {1024, 5000}, Ports.kernelRange(file), "blank-separated, no newline");
    }

    @Test
    void kernelRangeOfAMissingFileIsTheIanaDefault() {
        assertArrayEquals(new int[] {49152, 65535}, Ports.kernelRange(tmp.resolve("no-such-file")));
    }

    @Test
    void kernelRangeOfAFileWithoutTwoPortNumbersIsAnIllegalStateExceptionNamingTheFileAndItsContent() throws IOException {
        String[] contents = {"banana", "40000", "70000 80000", "50000 40000", "0 100", ""};
        for (String content : contents) {
            Path file = tmp.resolve("range-" + Math.abs(content.hashCode()));
            Files.writeString(file, content + "\n");
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> Ports.kernelRange(file),
                    "content '" + content + "'");
            assertTrue(e.getMessage().contains(file.toString()), "names the file: " + e.getMessage());
            assertTrue(e.getMessage().endsWith(": '" + content + "'"),
                    "ends with the quoted content '" + content + "': " + e.getMessage());
        }
    }

    // --- the process-wide helper on this host

    @Test
    void freeGivesBindablePortsInThePoolOutsideTheKernelRangeOfThisHost() throws IOException {
        Path file = Path.of("/proc/sys/net/ipv4/ip_local_port_range");
        int[] kernel = Files.exists(file)
                ? Ports.kernelRange(file)
                : new int[] {49152, 65535};
        // the expected range is read here from the file's two numbers, the reader under test is not trusted for it
        if (Files.exists(file)) {
            // a /proc sysctl file answers only a read at offset 0; readString reads it in several reads and returns "3", readAllLines reads once
            String[] parts = Files.readAllLines(file).get(0).trim().split("\\s+");
            assertEquals(2, parts.length, "the file holds two numbers");
            assertArrayEquals(new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])}, kernel);
        }
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            int port = Ports.free();
            assertTrue(port >= 20000 && port <= 32767, "inside 20000-32767: " + port);
            assertFalse(port >= kernel[0] && port <= kernel[1], port + " lies in the kernel's range " + kernel[0] + "-" + kernel[1]);
            assertTrue(seen.add(port), "a port twice in twenty calls: " + port);
            try (ServerSocket s = new ServerSocket(port)) {
                assertEquals(port, s.getLocalPort());
            }
        }
    }

    @Test
    void eachForkDrawsFromASliceOfItsOwnAndTheSlicesDoNotOverlap() {
        List<int[]> slices = new ArrayList<>();
        for (int fork = 1; fork <= Ports.SLICES; fork++) slices.add(Ports.slice(String.valueOf(fork)));
        assertArrayEquals(new int[] {Ports.POOL_FROM, Ports.POOL_FROM + 797}, slices.get(0), "fork 1 starts the pool");
        for (int i = 0; i < slices.size(); i++) {
            int[] s = slices.get(i);
            assertTrue(s[0] >= Ports.POOL_FROM && s[1] <= Ports.POOL_TO && s[0] < s[1], "slice " + (i + 1) + " lies in the pool");
            if (i > 0) assertTrue(s[0] > slices.get(i - 1)[1], "slice " + (i + 1) + " begins after the one before it ends");
        }
        assertArrayEquals(slices.get(0), Ports.slice(String.valueOf(Ports.SLICES + 1)), "the forks past the last slice wrap");
    }

    @Test
    void aForkNumberThatIsNotAWholeNumberOfAtLeastOneIsRefused() {
        for (String bad : List.of("0", "-1", "x", "")) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> Ports.slice(bad), bad);
            assertTrue(e.getMessage().contains(Ports.FORK_PROPERTY), e.getMessage());
        }
    }
}
