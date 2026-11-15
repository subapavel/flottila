package io.flottila.mapping.mapping;

import java.util.Map;
import java.util.Optional;

/**
 * A mapping model (ADR-0010): the stored, declarative form of a graphical mapping. The schema
 * {@code contract/mapping-model.schema.json} is the source of the format (ADR-0011); these records
 * are written by hand to match it.
 *
 * @param formatVersion the version of the format, 1
 * @param description the mapping's free text, which the engine ignores (ADR-0029); an empty one is
 *     none, so it is not written
 * @param source the source structure's schema bundle, root and fingerprint, or empty until it is
 *     chosen (ADR-0029)
 * @param target the target structure's schema bundle, root and fingerprint, or empty until it is
 *     chosen (ADR-0029)
 * @param targetFieldMappings keyed by target field path; an unmapped field has no entry
 * @param udfs the UDF declarations, keyed by UDF name
 */
public record MappingModel(
    int formatVersion,
    Optional<String> description,
    Optional<StructureRef> source,
    Optional<StructureRef> target,
    Map<String, TargetFieldMapping> targetFieldMappings,
    Map<String, UdfDeclaration> udfs) {

  public MappingModel {
    description = description.filter(text -> !text.isEmpty());
    targetFieldMappings = Map.copyOf(targetFieldMappings);
    udfs = Map.copyOf(udfs);
  }
}
