package dev.rail8.jq.internal.op;

import dev.rail8.jq.internal.json.ErrorDump;
import dev.rail8.jq.internal.value.BuiltString;
import dev.rail8.jq.query.ir.Operation;
import dev.rail8.jq.query.run.FilterException;
import dev.rail8.jq.query.run.Fn1;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.value.JqString;
import dev.rail8.jq.value.JqValue;

/**
 * The check of a computed object key, jq's {@code INSERT} (jq-ref 1.8.2, recorded: construct/key-*-at-run-time): its
 * input when the key is a string, otherwise jq's error. It runs once the key's pair has its value and before the next
 * pair runs, as jq inserts each pair in turn (ADR-0034 amendment), so it takes the input and gives it back.
 */
public final class ObjectKey implements Fn1 {
  /** The check of one computed key, its argument. */
  public static final Operation CHECK = new Operation("object key", new ObjectKey());

  private ObjectKey() {}

  @Override
  public Object apply(RunState state, Object in, Object key) {
    if (key instanceof JqString) {
      return in;
    }
    throw new FilterException(BuiltString.of(message(state, (JqValue) key)));
  }

  /**
   * jq's text for {@code key}, which is no string, as an object key: at run time, and at parse time for a constant key
   * (ADR-0036), with the key shown as {@link ErrorDump} shows it, its proofs charged to {@code state}.
   */
  public static String message(RunState state, JqValue key) {
    return "Cannot use " + NavigationErrors.kind(key) + " (" + ErrorDump.of(state, key) + ") as object key";
  }
}
