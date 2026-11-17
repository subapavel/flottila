package dev.rail8.jq.internal.op;

import dev.rail8.jq.internal.value.Numbers;
import dev.rail8.jq.query.run.Fn0;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.value.JqArray;
import dev.rail8.jq.value.JqBool;
import dev.rail8.jq.value.JqNull;
import dev.rail8.jq.value.JqNumber;
import dev.rail8.jq.value.JqObject;
import dev.rail8.jq.value.JqString;
import dev.rail8.jq.value.JqValue;

/**
 * jq 1.8.2's {@code -t} on each result, its {@code _negate} (jq-ref, recorded: diff/arith-numbers.rec): a number's
 * negation ({@link Numbers#negate(RunState, JqNumber)}, which charges a non-short literal's token, #97), and jq's error
 * for any other kind.
 */
final class Negate implements Fn0 {
  private static final String ERROR = "cannot be negated";

  @Override
  public Object apply(RunState state, Object in) {
    JqValue v = (JqValue) in;
    return switch (v) {
      case JqNumber n -> Numbers.negate(state, n);
      case JqNull _ -> throw Arithmetic.cannot(state, v, ERROR);
      case JqBool _ -> throw Arithmetic.cannot(state, v, ERROR);
      case JqString _ -> throw Arithmetic.cannot(state, v, ERROR);
      case JqArray _ -> throw Arithmetic.cannot(state, v, ERROR);
      case JqObject _ -> throw Arithmetic.cannot(state, v, ERROR);
    };
  }
}
