package io.flottila.runtime.violation;

/** Deliberate violation (F-35): a runtime class outside the peer link opening a raw TCP client. */
public final class RuntimeOpensARawSocket {
    public static io.vertx.core.Future<io.vertx.core.net.NetSocket> open(io.vertx.core.Vertx vertx) {
        io.vertx.core.net.NetClient client = vertx.createNetClient(new io.vertx.core.net.NetClientOptions());
        return client.connect(8083, "127.0.0.1");
    }
}
