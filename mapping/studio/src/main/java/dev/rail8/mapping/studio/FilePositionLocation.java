package dev.rail8.mapping.studio;

/**
 * A position in a schema file, as a structure load error reports it.
 *
 * @param kind {@value #KIND}
 * @param file the file as the schema bundle refers to it
 * @param line the line, from 1
 * @param column the column, from 1
 */
record FilePositionLocation(String kind, String file, int line, int column) implements LocationDto {

  static final String KIND = "file-position";

  FilePositionLocation(String file, int line, int column) {
    this(KIND, file, line, column);
  }
}
