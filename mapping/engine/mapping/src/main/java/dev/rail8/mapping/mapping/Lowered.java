package dev.rail8.mapping.mapping;

import dev.rail8.mapping.ir.TargetDocument;

/**
 * A mapping model that passed validation stage 3 and lowered to IR.
 *
 * @param document the IR of the whole mapping
 */
public record Lowered(TargetDocument document) implements LowerResult {}
