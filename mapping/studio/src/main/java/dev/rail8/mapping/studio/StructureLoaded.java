package dev.rail8.mapping.studio;

import dev.rail8.mapping.structure.Structure;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A structure as a graph: the root field and each type's fields. The editor expands the tree
 * itself.
 *
 * @param kind {@value #KIND}
 * @param fingerprint {@code sha256:<hex>} of the structure
 * @param root the root field
 * @param types the child fields of each structure type, by type id
 */
record StructureLoaded(
    String kind, String fingerprint, FieldDto root, SortedMap<String, List<FieldDto>> types)
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
