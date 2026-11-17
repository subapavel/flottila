package io.github.subapavel.jqj.internal.op;

import io.github.subapavel.jqj.internal.json.ErrorDump;
import io.github.subapavel.jqj.internal.value.BuiltString;
import io.github.subapavel.jqj.internal.value.Keyed;
import io.github.subapavel.jqj.query.run.CallForm;
import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;

/**
 * jq's {@code .name} and {@code ."string"} (#34 design section 6, #61): on an object the last value of the key
 * (ADR-0002), or null when there is none, charging one tick per key scanned (ADR-0034); on null, null; on any other
 * kind a {@link FilterException} with jq's text, or no result for the quiet variant {@code .name?} (ADR-0034).
 */
public final class Field implements Fn0 {
  private final String name;

  /** Whether this is {@code .name?}, which drops its own error. */
  private final boolean quiet;

  /** The key as jq's error text shows it: its JSON text, cut when long (jq-ref 1.8.2, recorded: field/error-name-N). */
  private final String shown;

  public Field(String name) {
    this(name, false);
  }

  public Field(String name, boolean quiet) {
    this.name = name;
    this.quiet = quiet;
    // Outside a run, at compile time: a built string has no key proof, so the idle state is never charged.
    this.shown = ErrorDump.of(new RunState(), BuiltString.of(name));
  }

  @Override
  public Object apply(RunState state, Object in) {
    JqValue v = (JqValue) in;
    return switch (v) {
      case Keyed object -> {
        JqValue value = object.get(state, name);
        yield value == null ? JqNull.NULL : value;
      }
      case JqNull nul -> nul;
      // One pattern per case: javac checks a case of several patterns again, a branch that is never taken.
      case JqBool _ -> cannotIndex(v);
      case JqNumber _ -> cannotIndex(v);
      case JqString _ -> cannotIndex(v);
      case JqArray _ -> cannotIndex(v);
    };
  }

  /** jq's error for {@code v}, which has no keys, or no result for {@code .name?}. */
  private Object cannotIndex(JqValue v) {
    if (quiet) {
      return CallForm.NONE;
    }
    throw NavigationErrors.cannotIndex(v, "string", shown);
  }
}
