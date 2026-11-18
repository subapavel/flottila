package dev.rail8.examples.webhook;

import dev.rail8.api.Component;
import dev.rail8.api.plugin.ComponentContext;
import dev.rail8.api.plugin.Plugin;

import java.util.List;

/**
 * The sample plugin: it adds one component, {@link WebhookComponent} (scheme {@code webhook}), built over the node's Vert.x,
 * blob store, ids and timer. The jar names this class in {@code META-INF/services/dev.rail8.api.plugin.Plugin}.
 */
public final class WebhookPlugin implements Plugin {

    @Override public String name() { return "webhook"; }

    @Override public String api() { return API; }

    @Override
    public List<Component> components(ComponentContext context) {
        return List.of(new WebhookComponent(context.vertx(), context.blobs(), context.ids(), context.timer()));
    }
}
