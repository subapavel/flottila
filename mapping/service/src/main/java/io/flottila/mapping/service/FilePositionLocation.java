package io.flottila.mapping.service;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A position in a schema file, as a structure load error reports it (ADR-0015).
 *
 * @param kind {@value #KIND}
 * @param file the file as the schema bundle refers to it
 * @param line the line, from 1
 * @param column the column, from 1
 */
public record FilePositionLocation(
    @Schema(required = true, constValue = FilePositionLocation.KIND) String kind,
    @Schema(required = true) String file,
    @Schema(required = true) int line,
    @Schema(required = true) int column)
    implements LocationDto {

  static final String KIND = "file-position";

  FilePositionLocation(String file, int line, int column) {
    this(KIND, file, line, column);
  }
}
