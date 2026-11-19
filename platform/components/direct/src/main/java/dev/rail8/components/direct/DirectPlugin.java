package dev.rail8.components.direct;

import dev.rail8.api.Component;
import dev.rail8.api.plugin.ComponentContext;
import dev.rail8.api.plugin.Plugin;

import java.util.List;

/**
 * The direct module: the direct component, between flows of one node, scheme {@code direct}.
 *
 * @since 0.1
 */
public final class DirectPlugin implements Plugin {

    @Override public String name() { return "direct"; }

    @Override public String api() { return API; }

    /** The direct component, built over the node's {@code context}. */
    @Override
    public List<Component> components(ComponentContext context) {
        return List.of(new DirectComponent(context.blobs(), context.ids(), context.timer()));
    }
}
