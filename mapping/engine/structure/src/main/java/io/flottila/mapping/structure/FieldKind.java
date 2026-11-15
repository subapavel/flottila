package io.flottila.mapping.structure;

/** What a field is in the message (ADR-0008). */
public enum FieldKind {
  ELEMENT,
  ATTRIBUTE,
  /** A property of a JSON object, or the JSON root {@code $} (ADR-0007). */
  JSON_PROPERTY
}
