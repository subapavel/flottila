package dev.rail8.mapping.mapping;

import dev.rail8.mapping.structure.RootSelector;
import dev.rail8.mapping.structure.SchemaBundle;

/**
 * The structure a mapping reads from or writes to, by reference (ADR-0009, ADR-0010).
 *
 * @param bundle the schema files; the writer writes them sorted
 * @param root the root selector
 * @param fingerprint {@code sha256:<hex>} of the structure the mapping was written against
 */
public record StructureRef(SchemaBundle bundle, RootSelector root, String fingerprint) {}
