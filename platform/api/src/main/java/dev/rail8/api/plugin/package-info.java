/**
 * The plugin contract: what an external developer implements ({@link dev.rail8.api.plugin.Plugin}) to add components,
 * steps and expression languages to a node, as the node's own modules do, what a node builds their components from
 * ({@link dev.rail8.api.plugin.ComponentContext}) and what a plugin reads its settings from
 * ({@link dev.rail8.api.plugin.PluginSettings}). This is the one api package that may name {@code io.vertx.core.Vertx},
 * because a component runs on the node's Vert.x; no other api package and no core class may name this one. An architecture
 * test enforces both.
 */
package dev.rail8.api.plugin;
