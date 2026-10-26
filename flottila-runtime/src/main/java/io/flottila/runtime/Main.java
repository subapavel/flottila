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
        if (code != 0) System.exit(code);
    }

    /**
     * Starts a node from {@code env} and returns 0 once it serves; a shutdown hook closes the node, then Vert.x. A refused
     * configuration is one line on {@code err} and 2, before anything connects; a node that does not start is one line and 1.
     */
    static int run(Map<String, String> env, PrintStream err) {
        NodeConfig config;
        try {
            config = NodeConfig.from(env);
        } catch (IllegalArgumentException refused) {
            err.println("flottila: configuration refused: " + oneLine(refused));
            return 2;
        }
        Vertx vertx = Vertx.vertx(new VertxOptions().setUseDaemonThread(false));
        SaxonXml xml = new SaxonXml();
        List<StepFactory> steps = Stream.concat(xml.steps().stream(), new XsdValidate().steps().stream()).toList();
        try (ExecutorService starter = Executors.newVirtualThreadPerTaskExecutor()) {
            Node node = starter.submit(() -> Node.start(config, vertx, ctx -> List.of(new HttpComponent(ctx.vertx(), ctx.blobs(), ctx.ids())),
                    steps, xml.languages())).get();
            java.lang.Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    node.close();
                } finally {
                    vertx.close().await();
                }
            }, "flottila-shutdown"));
            return 0;
        } catch (ExecutionException | InterruptedException failed) {
            err.println("flottila: the node did not start: " + oneLine(failed));
            vertx.close().await();
            return 1;
        }
    }

    /** {@code failure}'s message on one line: a message with line breaks (Flyway's, say) still makes one stderr line. */
    private static String oneLine(Throwable failure) {
        return String.valueOf(failure.getMessage()).replaceAll("\\s*\\R\\s*", " ");
    }
}
