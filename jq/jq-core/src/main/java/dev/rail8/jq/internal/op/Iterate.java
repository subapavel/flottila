package dev.rail8.jq.internal.op;

import dev.rail8.jq.internal.value.DoubleNumber;
import dev.rail8.jq.internal.value.KeyProof;
import dev.rail8.jq.internal.value.Keyed;
import dev.rail8.jq.internal.value.Members;
import dev.rail8.jq.internal.value.ObjectView;
import dev.rail8.jq.query.ir.Operation;
import dev.rail8.jq.query.ir.PathRule;
import dev.rail8.jq.query.run.FilterException;
import dev.rail8.jq.query.run.Many;
import dev.rail8.jq.query.run.PathStep;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.value.JqBool;
import dev.rail8.jq.value.JqNull;
import dev.rail8.jq.value.JqNumber;
import dev.rail8.jq.value.JqString;
import dev.rail8.jq.value.JqValue;

/**
 * jq 1.8.2's {@code .[]}: the {@link Members} of an array or an object, in order; on any other kind jq's error, which
 * the quiet variant {@code .[]?} drops. A document's object is walked through its {@link KeyProof}. The loop holds the
 * members in its one loop slot; a cursor holds the member's place among them in its high 32 bits and their cursor in
 * its low ones, so the key of each result, its object key or its array index, is known in a path variant.
 */
public final class Iterate implements Many, PathStep {
  /** {@code .[]}. */
  public static final Operation PLAIN = new Operation(".[]", new Iterate(false), Declared.step(PathRule.ALL));

  /** {@code .[]?}. */
  public static final Operation QUIET = new Operation(".[]?", new Iterate(true), Declared.step(PathRule.ALL));

  /** Whether this is {@code .[]?}, which drops its own error. */
  private final boolean quiet;

  private Iterate(boolean quiet) {
    this.quiet = quiet;
  }

  /** The loop slot that holds the members, after no argument. */
  private static final int MEMBERS = 0;

  @Override
  public int loopSlots() {
    return 1;
  }

  @Override
  public long first(RunState state, Object in, Object[] args) {
    // One pattern per case: javac checks a case of several patterns again, a branch that is never taken.
    JqValue v = (JqValue) in;
    return switch (v) {
      case ObjectView object -> start(args, KeyProof.walk(state, object, false));
      case Members members -> start(args, members);
      case JqNull _ -> none(state, v);
      case JqBool _ -> none(state, v);
      case JqNumber _ -> none(state, v);
      case JqString _ -> none(state, v);
    };
  }

  /** The cursor of the first of {@code members}, which the loop slot holds for the later calls. */
  private static long start(Object[] args, Members members) {
    args[MEMBERS] = members;
    return cursor(0, members.first());
  }

  /** The cursor of the member at {@code place} whose cursor among the members is {@code member}, or END for -1. */
  private static long cursor(long place, int member) {
    return member == -1 ? END : place << 32 | member;
  }

  /** No cursor for {@code v}, which has no members: jq's error, or none for {@code .[]?}. */
  private long none(RunState state, JqValue v) {
    if (quiet) {
      return END;
    }
    throw NavigationErrors.cannotIterate(state, v);
  }

  /** {@link #first} gives a cursor only after it set the loop slot. */
  @Override
  public long next(RunState state, Object in, Object[] args, long cursor) {
    return cursor((cursor >>> 32) + 1, ((Members) args[MEMBERS]).next(state, (int) cursor));
  }

  /** {@link #first} gives a cursor only after it set the loop slot. */
  @Override
  public Object value(RunState state, Object in, Object[] args, long cursor) {
    return ((Members) args[MEMBERS]).member(state, (int) cursor);
  }

  /** The member's object key, or its index in an array as a number; {@link #first} set the loop slot. */
  @Override
  public Object key(RunState state, Object in, Object[] args, long cursor) {
    return args[MEMBERS] instanceof Keyed keyed ? keyed.key((int) cursor) : new DoubleNumber(cursor >>> 32);
  }

  /** jq's error for iterating {@code in}, an input not found in a path variant's input. */
  @Override
  public FilterException untracked(RunState state, Object in, Object key, Object end) {
    return NavigationErrors.invalidIterate(state, (JqValue) in);
  }
}
