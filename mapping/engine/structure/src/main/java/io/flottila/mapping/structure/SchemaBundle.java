package io.flottila.mapping.structure;

import java.util.List;

/**
 * The schema files a structure is read from (ADR-0009, ADR-0010).
 *
 * @param files the file references as the mapping model spells them, relative to the base directory
 *     the reader is given
 */
public record SchemaBundle(List<String> files) {

  public SchemaBundle {
    files = List.copyOf(files);
  }
}
