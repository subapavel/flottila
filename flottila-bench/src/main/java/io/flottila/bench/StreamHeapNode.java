package io.flottila.bench;

import io.flottila.components.http.HttpComponent;
import io.flottila.runtime.node.Node;
import io.flottila.runtime.node.NodeConfig;
import io.vertx.core.Vertx;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;

/**
 * The stream-heap run's node (P1-10c): {@link StreamHeapMain} starts it as a child JVM at the run's {@code -Xmx}. It starts a
 * {@link Node} from its environment with the http component and the bench's {@link StreamCopy} as its one step — named here
 * explicitly, as {@code io.flottila.runtime.Main} names the shipped steps (no ServiceLoader) — and then answers on stdout:
 * for every line read on stdin, one line {@code <used> <max>}, the heap's used and max bytes ({@link MemoryMXBean}) right
 * after a forced GC ({@link #sample}). Stdout carries nothing else: the node logs to stderr, and the run starts the JVM with
 * {@code -XX:+DisplayVMOutputToStderr}. At the end of stdin (the run closed it, or the run's JVM died) it closes the node —
 * its recording flushed — and its Vert.x, and the JVM exits.
 *
 * @since 0.1
 */
public final class StreamHeapNode {

    /** The heap's used and max bytes at one instant. */
    record Heap(long used, long max) {}

    private StreamHeapNode() {}

    public static void main(String[] args) throws Exception {
        run(System.getenv(), System.in, System.out);
    }

    /**
     * {@link #serve} on a virtual thread of its own ({@link Node#start} blocks on Vert.x futures); what it threw comes back as
     * the cause of an {@link java.util.concurrent.ExecutionException} (the JVM then exits 1 with its stack trace).
     */
    static void run(Map<String, String> env, InputStream asks, PrintStream answers) throws Exception {
        FutureTask<Void> serve = new FutureTask<>(() -> {
            serve(env, asks, answers);
            return null;
        });
        Thread.ofVirtual().name("stream-heap-node").start(serve).join();
        serve.get();
    }

    /** Starts the node from {@code env}, {@link #answer}s until {@code asks} ends, then closes the node and its Vert.x. */
    static void serve(Map<String, String> env, InputStream asks, PrintStream answers) throws IOException {
        Vertx vertx = Vertx.vertx();
        try {
            Node node = Node.start(NodeConfig.from(env), vertx, ctx -> List.of(new HttpComponent(ctx.vertx(), ctx.blobs(), ctx.ids())),
                    List.of(new StreamCopy()), List.of());
            try {
                answer(new BufferedReader(new InputStreamReader(asks, StandardCharsets.UTF_8)), answers);
            } finally {
                node.close();
            }
        } finally {
            vertx.close().await();
        }
    }

    /** One line {@code <used> <max>} of a {@link #sample} on {@code answers} for every line of {@code asks}, until it ends. */
    static void answer(BufferedReader asks, PrintStream answers) throws IOException {
        for (String ask = asks.readLine(); ask != null; ask = asks.readLine()) {
            Heap heap = sample();
            answers.println(heap.used() + " " + heap.max());
            answers.flush();
        }
    }

    /** The heap right after a forced GC ({@link MemoryMXBean#gc}): what is still reachable, and the heap's max (the {@code -Xmx}). */
    static Heap sample() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        memory.gc();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        return new Heap(heap.getUsed(), heap.getMax());
    }
}
