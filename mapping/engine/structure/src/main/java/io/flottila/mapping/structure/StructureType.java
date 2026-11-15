package io.flottila.mapping.structure;

import java.util.List;

/**
 * A node of the structure graph: the child fields of every structure field of this type, attributes
 * first, then elements in output order (ADR-0008).
 *
 * @param id the type's id inside its structure
 * @param fields the child fields
 */
public record StructureType(String id, List<Field> fields) {

  public StructureType {
    fields = List.copyOf(fields);
  }
}
