package dev.rail8.jq.internal.op;

import dev.rail8.jq.internal.json.ErrorDump;
import dev.rail8.jq.internal.value.BuiltString;
import dev.rail8.jq.query.run.FilterException;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.value.JqArray;
import dev.rail8.jq.value.JqBool;
import dev.rail8.jq.value.JqNull;
import dev.rail8.jq.value.JqNumber;
import dev.rail8.jq.value.JqObject;
import dev.rail8.jq.value.JqString;
import dev.rail8.jq.value.JqValue;

/**
 * jq 1.8.2's error texts of navigation, the family of Field, Index, Slice and Iterate.
 */
final class NavigationErrors {
  private NavigationErrors() {}

  /** jq's name of {@code v}'s kind. One case per kind of the sealed {@link JqValue}. */
  static String kind(JqValue v) {
    return switch (v) {
      case JqNull _ -> "null";
      case JqBool _ -> "boolean";
      case JqNumber _ -> "number";
      case JqString _ -> "string";
      case JqArray _ -> "array";
      case JqObject _ -> "object";
    };
  }

  /** {@code t[k]}'s error, the key shown as {@link ErrorDump} shows it, its proofs charged to {@code state}. */
  static FilterException cannotIndex(RunState state, JqValue t, JqValue k) {
    return cannotIndex(t, kind(k), ErrorDump.of(state, k));
  }

  /** {@code t[k]}'s error for a key of the kind {@code keyKind} that an error shows as {@code shown}. */
  static FilterException cannotIndex(JqValue t, String keyKind, String shown) {
    return error("Cannot index " + kind(t) + " with " + keyKind + " (" + shown + ")");
  }

  /** {@code t[]}'s error, {@code t}'s proofs charged to {@code state}. */
  static FilterException cannotIterate(RunState state, JqValue t) {
    return error("Cannot iterate over " + kind(t) + " (" + ErrorDump.of(state, t) + ")");
  }

  /** A slice of an array or a string with a bound that is neither null nor a number. */
  static FilterException sliceIndices() {
    return error("Array/string slice indices must be integers");
  }

  private static FilterException error(String text) {
    return new FilterException(BuiltString.of(text));
  }
}
