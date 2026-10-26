package io.flottila.runtime;

import io.flottila.api.StepFactory;
import io.flottila.components.http.HttpComponent;
import io.flottila.runtime.node.Node;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.steps.validate.XsdValidate;
import io.flottila.steps.xslt.SaxonXml;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * The node's entry point and the one runtime class that names the shipped components and steps (F-4): the http component,
 * the xslt steps with the xpath language, the xsd step — explicitly, no ServiceLoader (components need {@code Vertx}, which
 * {@code api} may not name).
 *
 * @since 0.1
 */
public final class Main {

    private Main() {}

    /** Starts a node from the process environment; it runs on Vert.x's non-daemon threads until the JVM is told to stop. */
    public static void main(String[] args) {
        int code = run(System.getenv(), System.err);
        if (code != 0) {
            System.exit(code);
        }
    }

    /**
     * Starts a node from {@code env} and returns {@link #start}'s code; once the node serves, a shutdown hook runs {@link #stopper}
     * on a virtual thread ({@link Node#close} blocks on Vert.x futures) and waits for it without a bound of its own: the stop
     * deadline bounds the flows' stop inside the close, and the store's flush after it must not be cut; the orchestrator's kill
     * bounds the rest. A failure of the close reaches the hook thread, which reports it.
     */
    static int run(Map<String, String> env, PrintStream err) {
        Started started = start(env, err);
        if (started.code() == 0) {
            Runnable stopper = stopper(started.node().orElseThrow(), started.vertx().orElseThrow());
            java.lang.Runtime.getRuntime().addShutdownHook(new Thread(() -> CompletableFuture.runAsync(stopper,
                    task -> Thread.ofVirtual().name("flottila-stop").start(task)).join(), "flottila-shutdown"));
        }
        return started.code();
    }

    /**
     * What {@link #start} started: on 0 the serving node and its Vert.x, on 2 (configuration refused, nothing started) and on 1
     * (the node did not start, its Vert.x closed again) nothing.
     */
    record Started(int code, Optional<Node> node, Optional<Vertx> vertx) {}

    /**
     * Starts a node from {@code env} on a Vert.x of its own with non-daemon threads. A refused configuration is one line on
     * {@code err} and 2, before anything connects; a node that does not start is one line and 1, its Vert.x closed again.
     */
    static Started start(Map<String, String> env, PrintStream err) {
        NodeConfig config;
        try {
            config = NodeConfig.from(env);
        } catch (IllegalArgumentException refused) {
            err.println("flottila: configuration refused: " + oneLine(refused));
            return new Started(2, Optional.empty(), Optional.empty());
        }
        Vertx vertx = Vertx.vertx(new VertxOptions().setUseDaemonThread(false));
        SaxonXml xml = new SaxonXml();
        List<StepFactory> steps = Stream.concat(xml.steps().stream(), new XsdValidate().steps().stream()).toList();
        try (ExecutorService starter = Executors.newVirtualThreadPerTaskExecutor()) {
            Node node = starter.submit(() -> Node.start(config, vertx, ctx -> List.of(new HttpComponent(ctx.vertx(), ctx.blobs(), ctx.ids())),
                    steps, xml.languages())).get();
            return new Started(0, Optional.of(node), Optional.of(vertx));
        } catch (ExecutionException | InterruptedException failed) {
            err.println("flottila: the node did not start: " + oneLine(failed));
            vertx.close().await();
            return new Started(1, Optional.empty(), Optional.empty());
        }
    }

    /** Closes {@code node}, then {@code vertx} even when the node's close failed; the node's failure reaches the caller. Call it on a virtual thread. */
    static Runnable stopper(Node node, Vertx vertx) {
        return () -> {
            try {
                node.close();
            } finally {
                vertx.close().await();
            }
        };
    }

    /** {@code failure}'s message on one line: a message with line breaks (Flyway's, say) still makes one stderr line. */
    private static String oneLine(Throwable failure) {
        return String.valueOf(failure.getMessage()).replaceAll("\\s*\\R\\s*", " ");
    }
}
