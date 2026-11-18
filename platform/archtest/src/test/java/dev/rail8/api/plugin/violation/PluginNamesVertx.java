package dev.rail8.api.plugin.violation;

import dev.rail8.api.plugin.ComponentContext;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

/**
 * Counter-fixture: a class of the plugin contract that names {@code Vertx}, an allowlisted type and another class of the
 * contract. It passes the plugin contract's own rule, the api surface rule leaves it alone, and naming the contract from
 * inside it is allowed.
 */
public final class PluginNamesVertx {
    private PluginNamesVertx() {}
    public static Future<Void> close(ComponentContext context) {
        Vertx vertx = context.vertx();
        return vertx.close();
    }
}
