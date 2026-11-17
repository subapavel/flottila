package io.github.subapavel.jqj.query.ir;

/**
 * Ends the body of the {@link Label} whose slot is {@code slot} (ADR-0008, ADR-0039): it marks that Label's token in
 * the run state and gives false, which every node passes on at once. It sits inside that Label's body, and charges no
 * step.
 */
public record Break(Source source, int slot) implements Node {}
