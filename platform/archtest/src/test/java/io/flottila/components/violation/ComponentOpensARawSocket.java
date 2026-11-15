package io.flottila.components.violation;

/** Deliberate violation (F-35): a component opening a raw TCP server outside the Component SPI's own listeners. */
public final class ComponentOpensARawSocket {
    public static io.vertx.core.net.NetServer listen(io.vertx.core.Vertx vertx) {
        return vertx.createNetServer();
    }
}
