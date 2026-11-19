package dev.rail8.runtime;

import dev.rail8.api.Component;
import dev.rail8.api.Expressions;
import dev.rail8.api.StepFactory;
import dev.rail8.api.plugin.ComponentContext;
import dev.rail8.components.direct.DirectComponent;
import dev.rail8.components.http.HttpComponent;
import dev.rail8.components.sftp.SftpComponent;
import dev.rail8.runtime.node.Node;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.plugin.Plugins;
import dev.rail8.steps.jq.JqStepFactory;
import dev.rail8.steps.mapping.MappingStepFactory;
import dev.rail8.steps.validate.XsdValidate;
import dev.rail8.steps.xslt.SaxonXml;
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
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The node's entry point and the one runtime class that names the shipped components and steps: the http, sftp and direct
 * components, the xslt steps with the xpath language, the xsd step, the jq step, explicitly. Plugins add theirs after them
 * ({@link Plugins}). {@link #shipped(int, Plugins)} is the one wiring point, used by the node and the CLI.
 *
 * @since 0.1
 */
public final class Main {

    private static final System.Logger LOG = System.getLogger(Main.class.getName());

    private Main() {}

    /**
     * What a node runs on: its components, built per node from a {@link ComponentContext}, its step factories and its
     * expression languages; the shipped ones first, then each plugin's.
     *
     * @param components builds the shipped components (http, sftp, direct), then the plugins', over a node's context; never a mock
     * @param steps the shipped step factories (the xslt steps, the xsd step, the jq step, then the mapping step), then the plugins'
     * @param languages the shipped expression languages (xpath), then the plugins'
     */
    public record Shipped(Function<ComponentContext, List<Component>> components, List<StepFactory> steps, List<Expressions> languages) {
        public Shipped {
            steps = List.copyOf(steps);
            languages = List.copyOf(languages);
        }
    }

    /** {@link #shipped(int, Plugins)} without a plugin: what the node ships alone. */
    public static Shipped shipped(int jqMaxRecursionDepth) {
        return shipped(jqMaxRecursionDepth, Plugins.none());
    }

    /**
     * The shipped components, steps and languages, then what {@code plugins} add: the one place that names the shipped ones, for
     * the node ({@link #start}) and the CLI. The plugins' steps and languages are taken here; their components when the returned
     * function builds them, where a refusal is an {@link IllegalStateException}.
     *
     * @param jqMaxRecursionDepth the recursion depth of a jq step whose flow sets none: the node's
     *                            {@link NodeConfig#jqMaxRecursionDepth()}, the CLI's {@link NodeConfig#JQ_MAX_RECURSION_DEPTH_DEFAULT}
     * @param plugins             the loaded plugins whose components, steps and languages follow the shipped ones;
     *                            {@link Plugins#none()} for none
     * @throws IllegalArgumentException a plugin's step type or language is refused ({@link Plugins#steps}, {@link Plugins#languages})
     */
    public static Shipped shipped(int jqMaxRecursionDepth, Plugins plugins) {
        SaxonXml xml = new SaxonXml();
        List<StepFactory> steps = Stream.of(xml.steps(), new XsdValidate().steps(), List.<StepFactory>of(new JqStepFactory(jqMaxRecursionDepth), new MappingStepFactory()))
                .flatMap(List::stream).toList();
        return new Shipped(plugins.components(ctx -> List.of(new HttpComponent(ctx.vertx(), ctx.blobs(), ctx.ids(), ctx.timer()),
                new SftpComponent(ctx.blobs(), ctx.ids(), ctx.timer(), ctx.clock()), new DirectComponent(ctx.blobs(), ctx.ids(), ctx.timer()))),
                plugins.steps(steps), plugins.languages(xml.languages()));
    }

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
     * Starts a node from {@code env} on a Vert.x of its own with non-daemon threads, with the plugins of {@code RAIL8_PLUGINS}
     * ({@link Plugins#fromEnv}), each logged in one line. A refused configuration, plugins and their steps and languages
     * included, is one line on {@code err} and 2, before Vert.x starts and anything connects; a node that does not start, a
     * plugin's refused component included, is one line and 1, its Vert.x and its plugins closed again.
     */
    static Started start(Map<String, String> env, PrintStream err) {
        NodeConfig config;
        Plugins plugins = Plugins.none();
        Shipped shipped;
        try {
            config = NodeConfig.from(env);
            plugins = Plugins.fromEnv(env);
            shipped = shipped(config.jqMaxRecursionDepth(), plugins);
        } catch (IllegalArgumentException refused) {
            plugins.close();
            err.println("rail8: configuration refused: " + oneLine(refused));
            return new Started(2, Optional.empty(), Optional.empty(), Optional.empty());
        }
        plugins.describe().forEach(line -> LOG.log(System.Logger.Level.INFO, line));
        Vertx vertx = Vertx.vertx(new VertxOptions().setUseDaemonThread(false));
        try (ExecutorService starter = Executors.newVirtualThreadPerTaskExecutor()) {
            Node node = starter.submit(() -> Node.start(config, vertx, shipped.components(), shipped.steps(), shipped.languages())).get();
            return new Started(0, Optional.of(node), Optional.of(vertx), Optional.of(plugins));
        } catch (ExecutionException | InterruptedException failed) {
            err.println("rail8: the node did not start: " + oneLine(failed));
            vertx.close().await();
            plugins.close();
            return new Started(1, Optional.empty(), Optional.empty(), Optional.empty());
        }
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
