package dev.rail8.api.plugin.violation;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;

/** Deliberate violation: a class of the plugin contract names a Vert.x type beyond {@code Vertx} and the allowlist. */
public final class PluginUsesVertxBeyondItsSurface {
    private PluginUsesVertxBeyondItsSurface() {}
    public static HttpServer server(Vertx vertx) { return vertx.createHttpServer(); }
}
