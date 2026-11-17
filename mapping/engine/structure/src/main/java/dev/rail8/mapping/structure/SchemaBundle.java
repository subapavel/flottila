package dev.rail8.mapping.structure;

import java.util.List;

/**
 * The schema files a structure is read from.
 *
 * @param files the file references as the mapping model spells them, relative to the base directory
 *     the reader is given
 */
public record SchemaBundle(List<String> files) {

  public SchemaBundle {
    files = List.copyOf(files);
  }
}
