package io.flottila.mapping.structure;

import java.util.Optional;

/**
 * A field of a structure type (ADR-0008). A leaf field has a value type. A structure field names
 * the type that holds its child fields. A refused field loads and shows, but a mapping may not use
 * it or a field below it (ADR-0008 "Refused when a mapping uses the field", #92). An array property
 * is a JSON property whose items are its occurrences, whatever its {@code maxItems} (#118).
 *
 * @param step the field's kind and name, the last step of its field path
 * @param occurrence the field's effective occurrence
 * @param valueType the value type of a leaf field
 * @param nillable whether the field may carry {@code xsi:nil}
 * @param type the id of the {@link StructureType} with the child fields of a structure field
 * @param refusal why a mapping may not use the field, for a refused field
 * @param array whether the field is an array property; only a JSON property may be one
 */
public record Field(
    FieldPath.Step step,
    Occurrence occurrence,
    Optional<ValueType> valueType,
    boolean nillable,
    Optional<String> type,
    Optional<Refusal> refusal,
    boolean array) {

  /**
   * @throws IllegalArgumentException when {@code array} is set on an element or an attribute
   */
  public Field {
    if (array && step.kind() != FieldKind.JSON_PROPERTY) {
      throw new IllegalArgumentException("only a JSON property is an array property: " + step);
    }
  }

  /** This field with {@code refusal}, which replaces any it had; every other component is kept. */
  public Field withRefusal(Refusal refusal) {
    return new Field(step, occurrence, valueType, nillable, type, Optional.of(refusal), array);
  }

  /** A field that is not an array property. */
  public Field(
      FieldPath.Step step,
      Occurrence occurrence,
      Optional<ValueType> valueType,
      boolean nillable,
      Optional<String> type,
      Optional<Refusal> refusal) {
    this(step, occurrence, valueType, nillable, type, refusal, false);
  }

  /** A field that a mapping may use and that is not an array property: no refusal. */
  public Field(
      FieldPath.Step step,
      Occurrence occurrence,
      Optional<ValueType> valueType,
      boolean nillable,
      Optional<String> type) {
    this(step, occurrence, valueType, nillable, type, Optional.empty(), false);
  }
}
