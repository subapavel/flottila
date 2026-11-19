package dev.rail8.runtime;

import dev.rail8.api.plugin.AdminService;
import dev.rail8.runtime.node.Node;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.plugin.Plugins;
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

/**
 * The node's entry point. It names no component and no step: what the node runs on is its modules and plugins
 * ({@link Plugins}), the shipped components and steps among them, found on its class path.
 *
 * @since 0.1
 */
public final class Main {

    private static final System.Logger LOG = System.getLogger(Main.class.getName());

    private Main() {}

    /**
     * The system property that names the class Vert.x logs through, and the class the node names: Vert.x's own one for
     * {@code java.util.logging}.
     */
    static final String VERTX_LOGGING = "vertx.logger-delegate-factory-class-name";
    static final String VERTX_JUL = "io.vertx.core.logging.JULLogDelegateFactory";

    /**
     * Has Vert.x log straight to {@code java.util.logging} rather than through SLF4J, so that a Vert.x line in the node's log
     * names the Vert.x class that logged it, not SLF4J's adapter. A class the operator named in {@link #VERTX_LOGGING} stays.
     * It takes effect only before Vert.x logs its first line.
     */
    static void vertxLogsToJul() {
        if (System.getProperty(VERTX_LOGGING) == null) {
            System.setProperty(VERTX_LOGGING, VERTX_JUL);
        }
    }

    /**
     * Starts a node from the process environment, Vert.x logging to {@code java.util.logging} ({@link #vertxLogsToJul}); it runs
     * on Vert.x's non-daemon threads until the JVM is told to stop.
     */
    public static void main(String[] args) {
        vertxLogsToJul();
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
            Runnable stopper = stopper(started.node().orElseThrow(), started.vertx().orElseThrow(), started.plugins().orElseThrow());
            java.lang.Runtime.getRuntime().addShutdownHook(new Thread(() -> CompletableFuture.runAsync(stopper,
                    task -> Thread.ofVirtual().name("rail8-stop").start(task)).join(), "rail8-shutdown"));
        }
        return started.code();
    }

    /**
     * What {@link #start} started: on 0 the serving node, its Vert.x and its plugins, on 2 (configuration refused, nothing
     * started) and on 1 (the node did not start, its Vert.x and its plugins closed again) nothing.
     */
    record Started(int code, Optional<Node> node, Optional<Vertx> vertx, Optional<Plugins> plugins) {}

    /**
     * Starts a node from {@code env} on a Vert.x of its own with non-daemon threads, with its modules and the plugins of
     * {@code RAIL8_PLUGINS} ({@link Plugins#fromEnv}), each logged in one line, and the admin services of its modules
     * ({@link Plugins#services}). A refused configuration, the modules and plugins, their settings, steps, languages and admin
     * services included, is one line on {@code err} and 2, before Vert.x starts and anything connects, the plugins closed again;
     * a node that does not start, a refused component included, is one line and 1, its Vert.x and its plugins closed again.
     */
    static Started start(Map<String, String> env, PrintStream err) {
        NodeConfig config;
        Plugins plugins;
        try {
            config = NodeConfig.from(env);
            plugins = Plugins.fromEnv(env);
        } catch (IllegalArgumentException refused) {
            return refused(refused, err);
        }
        Map<String, List<AdminService>> services;
        try {
            services = plugins.services(env);
        } catch (IllegalArgumentException refused) {
            plugins.close();
            return refused(refused, err);
        }
        plugins.describe().forEach(line -> LOG.log(System.Logger.Level.INFO, line));
        Vertx vertx = Vertx.vertx(new VertxOptions().setUseDaemonThread(false));
        try (ExecutorService starter = Executors.newVirtualThreadPerTaskExecutor()) {
            Node node = starter.submit(() -> Node.start(config, vertx, plugins::components, plugins.steps(), plugins.languages(), services)).get();
            return new Started(0, Optional.of(node), Optional.of(vertx), Optional.of(plugins));
        } catch (ExecutionException | InterruptedException failed) {
            err.println("rail8: the node did not start: " + oneLine(failed));
            vertx.close().await();
            plugins.close();
            return new Started(1, Optional.empty(), Optional.empty(), Optional.empty());
        }
    }

    private static Started refused(IllegalArgumentException refused, PrintStream err) {
        err.println("rail8: configuration refused: " + oneLine(refused));
        return new Started(2, Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * Closes {@code node}, then {@code vertx} even when the node's close failed, then {@code plugins}' class loaders, once nothing
     * of theirs runs; the node's failure reaches the caller. Call it on a virtual thread.
     */
    static Runnable stopper(Node node, Vertx vertx, Plugins plugins) {
        return () -> {
            try {
                node.close();
            } finally {
                try {
                    vertx.close().await();
                } finally {
                    plugins.close();
                }
            }
        };
    }

    /** {@code failure}'s message on one line: a message with line breaks (Flyway's, say) still makes one stderr line. */
    private static String oneLine(Throwable failure) {
        return String.valueOf(failure.getMessage()).replaceAll("\\s*\\R\\s*", " ");
    }
}
