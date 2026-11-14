package io.github.subapavel.jqj.internal.op;

import io.github.subapavel.jqj.internal.value.Numbers;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;

/**
 * jq 1.8.2's {@code -t} on each result, its {@code _negate} (jq-ref, recorded: diff/arith-numbers.rec): a number's
 * negation ({@link Numbers#negate}), and jq's error for any other kind.
 */
final class Negate implements Fn0 {
  private static final String ERROR = "cannot be negated";

  @Override
  public Object apply(RunState state, Object in) {
    JqValue v = (JqValue) in;
    return switch (v) {
      case JqNumber n -> Numbers.negate(n);
      case JqNull _ -> throw Arithmetic.cannot(state, v, ERROR);
      case JqBool _ -> throw Arithmetic.cannot(state, v, ERROR);
      case JqString _ -> throw Arithmetic.cannot(state, v, ERROR);
      case JqArray _ -> throw Arithmetic.cannot(state, v, ERROR);
      case JqObject _ -> throw Arithmetic.cannot(state, v, ERROR);
    };
  }
}
