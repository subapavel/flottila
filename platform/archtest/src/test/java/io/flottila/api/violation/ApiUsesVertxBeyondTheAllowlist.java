package io.flottila.api.violation;

/** Deliberate violation (P0-03 fixture, test scope only): api touches Vert.x beyond Future/Buffer/streams. */
public final class ApiUsesVertxBeyondTheAllowlist {
    private ApiUsesVertxBeyondTheAllowlist() {}
    public static io.vertx.core.Vertx vertx() { return io.vertx.core.Vertx.vertx(); }
}
