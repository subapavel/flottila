package io.flottila.mapping.mapping;

/**
 * The diagnostic codes of the mapping-model front end: this module's part of the diagnostic
 * catalogue (ADR-0018 as amended by F-9). Renaming a code is a contract change (ADR-0011).
 */
public final class MappingDiagnostics {

  // stage 1, the schema (ADR-0011, "Stage 1 and 2 codes")

  /** A {@code formatVersion} newer than this engine reads, at {@code /formatVersion}. */
  public static final String FORMAT_VERSION_UNSUPPORTED = "format-version-unsupported";

  /** A member the format does not have here ({@code additionalProperties: false}). */
  public static final String UNKNOWN_MEMBER = "unknown-member";

  /** A required member is absent. */
  public static final String MISSING_MEMBER = "missing-member";

  /** A value of the wrong JSON type. */
  public static final String WRONG_TYPE = "wrong-type";

  /** A string that does not match its pattern. */
  public static final String PATTERN_MISMATCH = "pattern-mismatch";

  /**
   * A value outside its {@code enum}, {@code const}, minimum, maximum, maximum length (ADR-0029) or
   * uniqueness.
   */
  public static final String INVALID_VALUE = "invalid-value";

  /** Any other schema keyword failed: stage 1 fails closed. */
  public static final String SCHEMA_VIOLATION = "schema-violation";

  /** A box whose {@code kind} names no box kind, at its {@code kind}. */
  public static final String UNKNOWN_BOX_KIND = "unknown-box-kind";

  // stage 2, the model check

  /** An authoring id used more than once; target field mappings and boxes share one id space. */
  public static final String DUPLICATE_AUTHORING_ID = "duplicate-authoring-id";

  /** A reference to no box of the target field mapping. */
  public static final String UNKNOWN_BOX_REFERENCE = "unknown-box-reference";

  /** A reference to a box of another target field mapping. */
  public static final String CROSS_TARGET_FIELD_REFERENCE = "cross-target-field-reference";

  /** Boxes that form a cycle, at the smallest id of the cycle. */
  public static final String BOX_CYCLE = "box-cycle";

  /** A source-field box whose context is no proper ancestor of its field. */
  public static final String CONTEXT_NOT_ANCESTOR = "context-not-ancestor";

  /** A UDF box whose UDF is not declared in {@code udfs}. */
  public static final String UNDECLARED_UDF = "undeclared-udf";

  // stage 3, compile (#26)

  /**
   * A model without its source or target structure, at {@code /source} or {@code /target}
   * (ADR-0029): a new mapping has none until they are chosen. Compile, test and export report it
   * before any bundle is read, and run no further stage.
   */
  public static final String MISSING_STRUCTURE = "missing-structure";

  /**
   * A box that {@code result} reaches and that this engine does not compile yet, at the box: a UDF
   * box, or a function box whose function the catalogue does not hold yet (ADR-0025).
   */
  public static final String UNSUPPORTED_BOX_KIND = "unsupported-box-kind";

  /** An unresolved function box that {@code result} reaches, at the box. */
  public static final String UNRESOLVED_FUNCTION = "unresolved-function";

  /**
   * A field that is no leaf element under the root, such as an attribute, at the source-field box
   * or the target field mapping.
   */
  public static final String UNSUPPORTED_FIELD = "unsupported-field";

  /**
   * A field path the structure does not have, at the source-field box or the target field mapping.
   */
  public static final String ORPHANED_REFERENCE = "orphaned-reference";

  /** A target field mapping whose {@code result} is {@code null} (F-2). */
  public static final String UNCONNECTED_TARGET_FIELD = "unconnected-target-field";

  /**
   * A target leaf whose effective occurrence has {@code minOccurs > 0}, that has no target field
   * mapping and stands under a parent that is created (ADR-0006, #65), at its absent member {@code
   * /targetFieldMappings/<field path>}.
   */
  public static final String UNMAPPED_MANDATORY_LEAF = "unmapped-mandatory-leaf";

  /**
   * A side whose stored fingerprint is not the one its re-read schema bundle gives (ADR-0009 as
   * amended on 2026-11-05, #91), at {@code /source/fingerprint} or {@code /target/fingerprint}.
   */
  public static final String FINGERPRINT_MISMATCH = "fingerprint-mismatch";

  /**
   * A field path through a field that two or more sibling declarations with one step give (ADR-0008
   * "Refused when a mapping uses the field", #92): at the source-field box or the target field
   * mapping that names it, or, for such a target field with {@code minOccurs > 0} that the walk
   * would create and no mapping writes, at its absent member {@code /targetFieldMappings/<field
   * path>}. The message names the schema file, line and column of the step's second declaration.
   */
  public static final String AMBIGUOUS_FIELD_PATH = "ambiguous-field-path";

  /**
   * A target field path through a field of a repeated group of two or more particles that is not a
   * choice of single elements, which streamed output cannot interleave (ADR-0008 "Refused when a
   * mapping uses the field", #94): at the target field mapping that names it, or, for such a field
   * with {@code minOccurs > 0} that the walk would create and no mapping writes, at its absent
   * member {@code /targetFieldMappings/<field path>}. A source may read the field. The message
   * names the schema file, line and column of the repeated group's declaration.
   */
  public static final String INTERLEAVED_TARGET_GROUP = "interleaved-target-group";

  /**
   * A target field mapping that writes an abstract element, an element of an abstract type, or a
   * field below one (ADR-0008 "Refused when a mapping uses the field", #95), at its authoring id;
   * or such an element with {@code minOccurs > 0} that the target walk would create and no mapping
   * writes, at its absent member {@code /targetFieldMappings/<field path>}. The message names the
   * schema file, line and column of the abstract declaration. A source-field box reads one.
   */
  public static final String ABSTRACT_TARGET_ELEMENT = "abstract-target-element";

  /**
   * A mapping whose target is a JSON root, at {@code /target} (ADR-0008 "JSON as a target", #97): a
   * JSON Schema loads and shows as a target, and JSON output comes after milestone 1.
   */
  public static final String JSON_TARGET_UNSUPPORTED = "json-target-unsupported";

  // stage 3, a reached standard function box against its catalogue entry (ADR-0025)

  /** A function box with more inputs than its catalogue entry has, at the box. */
  public static final String WRONG_ARITY = "wrong-arity";

  /**
   * A function box input slot of its catalogue entry that is {@code null} or absent, at the box.
   */
  public static final String UNCONNECTED_INPUT = "unconnected-input";

  /** A function box param whose key its catalogue entry lacks, at the box. */
  public static final String UNKNOWN_PARAMETER = "unknown-parameter";

  /** A function box param of another type than its catalogue entry's, at the box. */
  public static final String WRONG_PARAMETER_TYPE = "wrong-parameter-type";

  /** A function box param whose string is none of its catalogue entry's values, at the box. */
  public static final String INVALID_PARAMETER_VALUE = "invalid-parameter-value";

  /**
   * A single-value function box with an input that a generating block feeds, such as {@code concat}
   * over {@code copyValue}, at the consuming box (ADR-0006, F-46): the block is one value for every
   * parent occurrence, which no single-value input can take. SAP PO accepts that graph, so it is
   * the function's deviation.
   */
  public static final String UNSUPPORTED_GENERATING_INPUT = "unsupported-generating-input";

  // a warning, never an error (ADR-0011, ADR-0013, ADR-0027)

  /**
   * A box that its target field mapping's {@code result} does not reach through the boxes' inputs,
   * at the box: it is kept, and compile ignores it.
   */
  public static final String UNREACHABLE_BOX = "unreachable-box";

  private MappingDiagnostics() {}
}
