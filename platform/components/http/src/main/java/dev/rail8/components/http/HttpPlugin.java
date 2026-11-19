package dev.rail8.components.http;

import dev.rail8.api.Component;
import dev.rail8.api.plugin.ComponentContext;
import dev.rail8.api.plugin.Plugin;

import java.util.List;

/**
 * The http module: the http component, scheme {@code http}.
 *
 * @since 0.1
 */
public final class HttpPlugin implements Plugin {

    @Override public String name() { return "http"; }

    @Override public String api() { return API; }

    /** The http component, built over the node's {@code context}: its consumers mount on the node's HTTP port. */
    @Override
    public List<Component> components(ComponentContext context) {
        return List.of(new HttpComponent(context.vertx(), context.blobs(), context.ids(), context.timer(), context.http()));
    }
}
