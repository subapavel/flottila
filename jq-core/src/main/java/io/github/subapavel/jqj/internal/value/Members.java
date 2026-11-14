package io.github.subapavel.jqj.internal.value;

import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqValue;

/**
 * A container whose members {@code .[]} gives (ADR-0034): an array's elements in order, an object's values with each
 * key at its first position and its last value (ADR-0002); a document's object only through its {@link ObjectWalk}
 * (ADR-0044). The cursor is an index the container chooses, -1 after the last member. A step after a member checks the
 * document again, since the sink that took the member may have read another one onto the tape (ADR-0026).
 */
public sealed interface Members permits ArrayView, BuiltArray, ObjectWalk, BuiltObject {
  /** The cursor of the first member, or -1 when there is none. */
  int first();

  /** The cursor of the member after the one at {@code cursor}, or -1 after the last; it charges the scans it makes. */
  int next(RunState state, int cursor);

  /** The member at {@code cursor}, which is not -1; it charges the scans it makes. */
  JqValue member(RunState state, int cursor);
}
