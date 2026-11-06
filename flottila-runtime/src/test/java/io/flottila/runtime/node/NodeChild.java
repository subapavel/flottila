package io.flottila.runtime.node;

import io.vertx.core.Vertx;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The child JVM of {@link NodeFailStopIT}: starts a {@link Node} on its own process environment (no component, no step), prints
 * {@code started}, and runs until its standard input ends — then closes the node and exits 0. A node that halts on its own
 * (fail-stop, D-101 (2)) exits with its own status before that.
 */
public final class NodeChild {

    private NodeChild() { }

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        Vertx vertx = Vertx.vertx();
        NodeConfig config = NodeConfig.from(System.getenv());
        Node node = OnVirtualThread.call(() -> Node.start(config, vertx, ctx -> List.of(), List.of(), List.of()));
        out.println("started");
        while (System.in.read() >= 0) {
            // runs until the parent closes our standard input
        }
        OnVirtualThread.call(() -> {
            node.close();
            return null;
        });
        vertx.close().toCompletionStage().toCompletableFuture().get();
        out.println("closed");
    }
}
