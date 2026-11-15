package io.flottila.mapping.mapping;

import io.flottila.mapping.ir.TargetDocument;

/**
 * A mapping model that passed validation stage 3 and lowered to IR (ADR-0006, ADR-0011).
 *
 * @param document the IR of the whole mapping
 */
public record Lowered(TargetDocument document) implements LowerResult {}
