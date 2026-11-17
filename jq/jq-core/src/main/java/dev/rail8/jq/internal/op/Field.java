package dev.rail8.jq.internal.op;

import dev.rail8.jq.internal.json.ErrorDump;
import dev.rail8.jq.internal.value.BuiltString;
import dev.rail8.jq.internal.value.Keyed;
import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.FilterException;
import dev.rail8.jq.query.run.Fn0;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.value.JqArray;
import dev.rail8.jq.value.JqBool;
import dev.rail8.jq.value.JqNull;
import dev.rail8.jq.value.JqNumber;
import dev.rail8.jq.value.JqString;
import dev.rail8.jq.value.JqValue;

/**
 * jq's {@code .name} and {@code ."string"}: on an object the last value of the key, or null when there is none,
 * charging one tick per key scanned; on null, null; on any other kind a {@link FilterException} with jq's text, or no
 * result for the quiet variant {@code .name?}.
 */
public final class Field implements Fn0 {
  private final String name;

  /** Whether this is {@code .name?}, which drops its own error. */
  private final boolean quiet;

  /** The key as jq's error text shows it: its JSON text, cut when long, as jq 1.8.2 cuts it. */
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
