package io.github.subapavel.jqj.internal.op;

import io.github.subapavel.jqj.internal.value.KeyProof;
import io.github.subapavel.jqj.internal.value.Members;
import io.github.subapavel.jqj.internal.value.ObjectView;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.run.Many;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;

/**
 * jq's {@code .[]} (jq-ref 1.8.2, recorded: diff/iterate.rec): the {@link Members} of an array or an object, in order;
 * on any other kind jq's error, which the quiet variant {@code .[]?} drops (ADR-0034). A document's object is walked
 * through its {@link KeyProof} (ADR-0044). The loop holds the members in its one loop slot, and the cursor is theirs.
 */
public final class Iterate implements Many {
  /** {@code .[]}. */
  public static final Operation PLAIN = new Operation(".[]", new Iterate(false));

  /** {@code .[]?}. */
  public static final Operation QUIET = new Operation(".[]?", new Iterate(true));

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
    return members.first();
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
    return ((Members) args[MEMBERS]).next(state, (int) cursor);
  }

  /** {@link #first} gives a cursor only after it set the loop slot. */
  @Override
  public Object value(RunState state, Object in, Object[] args, long cursor) {
    return ((Members) args[MEMBERS]).member(state, (int) cursor);
  }
}
