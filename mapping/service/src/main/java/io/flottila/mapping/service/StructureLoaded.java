package io.flottila.mapping.service;

import io.flottila.mapping.structure.Structure;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A structure as a graph (ADR-0008, ADR-0015): the root field and each type's fields. The editor
 * expands the tree itself.
 *
 * @param kind {@value #KIND}
 * @param fingerprint {@code sha256:<hex>} of the structure (ADR-0009)
 * @param root the root field
 * @param types the child fields of each structure type, by type id
 */
public record StructureLoaded(
    @Schema(required = true, constValue = StructureLoaded.KIND) String kind,
    @Schema(required = true) String fingerprint,
    @Schema(required = true) FieldDto root,
    @Schema(required = true) SortedMap<String, List<FieldDto>> types)
    implements StructureResponse {

  static final String KIND = "loaded";

  static StructureLoaded of(Structure structure) {
    SortedMap<String, List<FieldDto>> types = new TreeMap<>();
    structure
        .types()
        .forEach((id, type) -> types.put(id, type.fields().stream().map(FieldDto::of).toList()));
    return new StructureLoaded(KIND, structure.fingerprint(), FieldDto.of(structure.root()), types);
  }
}
