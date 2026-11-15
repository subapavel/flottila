package io.flottila.mapping.structure.importer;

import java.io.Serial;
import java.util.List;

/**
 * A schema bundle that cannot be read into a structure. It carries a stable kebab-case code and the
 * position in the schema file that caused it (ADR-0008, ADR-0015). Lines and columns are 1-based:
 *
 * <ul>
 *   <li>{@code schema-not-well-formed}: where the XML parser stops;
 *   <li>a refused construct ({@code schema-construct-unsupported}, {@code schema-invalid}), a name
 *       no bundle file declares ({@code schema-invalid}) or a reference outside the bundle ({@code
 *       schema-reference-outside-bundle}): just after its start tag, or the end of refused text;
 *   <li>{@code schema-root-not-found}: just after the start tag of the first bundle file's document
 *       element, {@code xs:schema} or, in a WSDL bundle (#96), {@code wsdl:definitions} or {@code
 *       wsdl:description};
 *   <li>in a JSON Schema (#97): where the JSON parser stops; a refused or invalid keyword, the
 *       {@code $ref} of {@code schema-reference-outside-bundle} among them, at its key; a refused
 *       schema (the root, the second declaration of a property) at its first character;
 *   <li>an error about a whole file ({@code schema-unreadable}, {@code
 *       package-reference-unsupported}, an empty bundle, a JSON Schema file that is not UTF-8):
 *       line 1, column 1. {@code root-selector-unsupported} stays in the catalogue for stability
 *       and is no longer produced: since #96 and #97 every root selector kind is read.
 * </ul>
 *
 * <p>One load reports every load error (ADR-0008): {@link #errors} holds them, files in bundle
 * order and each file in document order, and the exception's own code and position are the first
 * one's.
 */
public final class StructureLoadException extends Exception {

  @Serial private static final long serialVersionUID = 1L;

  private final String code;
  private final String file;
  private final int line;
  private final int column;
  private final StructureLoadException[] errors;

  /** One load error, the only one of its load. */
  public StructureLoadException(String code, String file, int line, int column, String message) {
    super(message);
    this.code = code;
    this.file = file;
    this.line = line;
    this.column = column;
    this.errors = new StructureLoadException[] {this};
  }

  /**
   * The load errors of one load, in the order {@link #errors} keeps.
   *
   * @param errors at least one load error, each made by the five-argument constructor
   */
  public StructureLoadException(List<StructureLoadException> errors) {
    super(errors.getFirst().getMessage());
    StructureLoadException first = errors.getFirst();
    this.code = first.code;
    this.file = first.file;
    this.line = first.line;
    this.column = first.column;
    this.errors = errors.toArray(StructureLoadException[]::new);
  }

  /** Every load error of the load, the first being this exception's own code and position. */
  public List<StructureLoadException> errors() {
    return List.of(errors);
  }

  public String code() {
    return code;
  }

  /** The file as the schema bundle refers to it. */
  public String file() {
    return file;
  }

  public int line() {
    return line;
  }

  public int column() {
    return column;
  }
}
