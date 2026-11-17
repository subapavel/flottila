package io.github.subapavel.jqj.query.ir;

/**
 * The results of {@code body}, on the Label's input, until a {@link Break} of this activation (ADR-0008, ADR-0039):
 * each run takes a fresh token from the run state into {@code slot}, and a Break that hands that token back ends the
 * body, which is no error and no stop. Only a Break inside {@code body} names the slot; no {@link Var} reads it. It
 * charges no step of its own.
 */
public record Label(Source source, int slot, Node body) implements Node {}
