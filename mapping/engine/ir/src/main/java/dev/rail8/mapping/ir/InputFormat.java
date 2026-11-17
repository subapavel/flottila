package dev.rail8.mapping.ir;

/**
 * The format of a mapping's input message: it follows the source structure's root selector, so a
 * JSON root reads JSON and every other root reads XML. No back end sniffs the input for it.
 */
public enum InputFormat {
  XML,
  JSON
}
