package dev.rail8.mapping.structure;

/** What a field is in the message. */
public enum FieldKind {
  ELEMENT,
  ATTRIBUTE,
  /** A property of a JSON object, or the JSON root {@code $}. */
  JSON_PROPERTY
}
