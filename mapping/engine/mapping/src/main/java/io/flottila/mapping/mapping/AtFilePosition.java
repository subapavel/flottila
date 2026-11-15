package io.flottila.mapping.mapping;

/**
 * A position in a schema file, as a structure load error reports it (ADR-0015 location {@code
 * {file, line, column}}).
 *
 * @param file the file as the schema bundle refers to it
 * @param line the line, from 1
 * @param column the column, from 1
 */
public record AtFilePosition(String file, int line, int column) implements Location {}
