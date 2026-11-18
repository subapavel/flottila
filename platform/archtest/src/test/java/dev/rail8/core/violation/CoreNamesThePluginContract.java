package dev.rail8.core.violation;

import dev.rail8.api.plugin.Plugin;

/** Deliberate violation: a core class names the plugin contract. */
public final class CoreNamesThePluginContract {
    private CoreNamesThePluginContract() {}
    public static String name(Plugin plugin) { return plugin.name(); }
}
