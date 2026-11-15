package io.flottila.mapping.ir;

/**
 * The format of a mapping's input message (ADR-0008 "JSON as a source at run time"): it follows the
 * source structure's root selector, so a JSON root reads JSON and every other root reads XML. No
 * back end sniffs the input for it.
 */
public enum InputFormat {
  XML,
  JSON
}
