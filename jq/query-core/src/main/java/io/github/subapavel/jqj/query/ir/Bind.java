package io.github.subapavel.jqj.query.ir;

/**
 * {@code value as $slot | body} (ADR-0034, GLOSSARY "Slot"): each result of {@code value} sets the slot, and {@code body}
 * runs on the Bind's own input. A slot is bound by one Bind or one {@link AltBind} of the tree, or is a parameter
 * (ADR-0038), and a {@link Var} of a Bind's slot occurs only inside {@code body}.
 */
public record Bind(Source source, Node value, int slot, Node body) implements Node {}
