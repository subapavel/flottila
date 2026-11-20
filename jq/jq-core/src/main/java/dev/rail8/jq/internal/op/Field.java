package dev.rail8.jq.internal.op;

import dev.rail8.jq.internal.json.ErrorDump;
import dev.rail8.jq.internal.value.BuiltString;
import dev.rail8.jq.internal.value.Keyed;
import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.FilterException;
import dev.rail8.jq.query.run.Fn0;
import dev.rail8.jq.query.run.Path;
import dev.rail8.jq.query.run.PathStep;
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
 * result for the quiet variant {@code .name?}. In a path variant it adds its name to the path, the key it holds.
 */
public final class Field implements Fn0, PathStep {
  private final String name;

  /** The name as a path key: built once, at compile time, so a path step charges nothing for it. */
  private final JqString key;

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
    this.key = BuiltString.of(name);
    // Outside a run, at compile time: a built string has no key proof, so the idle state is never charged.
    this.shown = ErrorDump.of(new RunState(), key);
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

  /** jq's error for reading this name of {@code in}, an input not found in a path variant's input; {@code .name?} too. */
  @Override
  public FilterException untracked(RunState state, Object in, Object key, Object end) {
    return NavigationErrors.invalidAccess(state, shown, (JqValue) in);
  }

  /** {@code at} with this step's name added; the arguments, none, are not read. */
  @Override
  public Path extend(RunState state, Path at, Object in, Object key, Object end) {
    return at.child(this.key);
  }
}
