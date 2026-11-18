package dev.rail8.api.violation;

import dev.rail8.api.plugin.Plugin;

/** Deliberate violation: an api class outside the plugin contract names it. */
public final class ApiNamesThePluginContract {
    private ApiNamesThePluginContract() {}
    public static String name(Plugin plugin) { return plugin.name(); }
}
