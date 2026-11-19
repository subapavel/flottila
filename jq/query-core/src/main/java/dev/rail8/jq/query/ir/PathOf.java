package dev.rail8.jq.query.ir;

import dev.rail8.jq.query.run.PathFormat;

/**
 * {@code path(body)}: {@code body} runs in path mode on the PathOf's input, from the input's own path, and for each of
 * its results the PathOf charges one step per key of the result's path and emits that path as {@code paths} makes it a
 * value. A result that was not found in the input raises {@code paths}' invalid-path error instead.
 */
public record PathOf(Source source, Node body, PathFormat paths) implements Node {}
