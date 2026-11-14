package io.github.subapavel.jqj.query.ir;

/**
 * A constant (ADR-0005): one result, its value, whatever the input. The value is opaque to the core. That it is a built
 * value and never a view is held by construction, not checked here (ADR-0031): only a format's parser makes a Const.
 */
public record Const(Source source, Object value) implements Node {}
