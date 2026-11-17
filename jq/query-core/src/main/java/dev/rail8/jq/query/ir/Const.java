package dev.rail8.jq.query.ir;

/**
 * A constant: one result, its value, whatever the input. The value is opaque to the core. That it is a built
 * value and never a view is held by construction, not checked here: only a format's parser makes a Const.
 */
public record Const(Source source, Object value) implements Node {}
