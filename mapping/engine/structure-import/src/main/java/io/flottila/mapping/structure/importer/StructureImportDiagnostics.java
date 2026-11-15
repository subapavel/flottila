package io.flottila.mapping.structure.importer;

/**
 * The codes of structure load errors: this module's part of the diagnostic catalogue (ADR-0018 as
 * amended by F-9). {@link StructureLoadException} names where each points.
 */
public final class StructureImportDiagnostics {

  /** A schema file the XML or JSON parser cannot read to its end, or that is not UTF-8 JSON. */
  public static final String SCHEMA_NOT_WELL_FORMED = "schema-not-well-formed";

  /**
   * A construct outside the XSD the reader reads, an empty bundle, or a JSON Schema construct
   * ADR-0008 refuses at load.
   */
  public static final String SCHEMA_CONSTRUCT_UNSUPPORTED = "schema-construct-unsupported";

  /**
   * A value the XSD does not allow, such as {@code minOccurs} above {@code maxOccurs}, a name no
   * bundle file declares, or a JSON Schema that is not valid, such as a {@code $ref} to nothing.
   */
  public static final String SCHEMA_INVALID = "schema-invalid";

  /**
   * An {@code xs:import} or {@code xs:include}, or a JSON Schema {@code $ref}, that names no file
   * of the schema bundle (ADR-0008: the bundle is a closed set).
   */
  public static final String SCHEMA_REFERENCE_OUTSIDE_BUNDLE = "schema-reference-outside-bundle";

  /** A root selector that names no global element of the schema. */
  public static final String SCHEMA_ROOT_NOT_FOUND = "schema-root-not-found";

  /** A schema file that cannot be read at all. */
  public static final String SCHEMA_UNREADABLE = "schema-unreadable";

  /** A package reference ({@code name@version:/path}) that the host's resolver does not read. */
  public static final String PACKAGE_REFERENCE_UNSUPPORTED = "package-reference-unsupported";

  /**
   * A root selector of a kind the reader does not read. Since #96 and #97 every kind is read and no
   * load reports it; it stays in the catalogue until the catalogue drops it.
   */
  public static final String ROOT_SELECTOR_UNSUPPORTED = "root-selector-unsupported";

  private StructureImportDiagnostics() {}
}
