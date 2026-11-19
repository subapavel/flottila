package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.Path;
import dev.rail8.jq.query.run.PathSink;
import dev.rail8.jq.query.run.Sink;

/**
 * The sink a tail emitter, a run with one result at most that never breaks out, runs into: it holds the one result
 * until {@link #take} and answers it with a stop. So the tail run answers false exactly when its result came, and true
 * when none did; the If or Binds that owns the holder takes that result and does not pass the false on. As a path sink
 * it drops the result's path.
 */
final class Holder implements Sink, PathSink {
  private Object value;

  @Override
  public boolean emit(Object v) {
    value = v;
    return false;
  }

  @Override
  public boolean emit(Object v, Path p) {
    return emit(v);
  }

  /** The result that came since the last take; the holder is cleared again. */
  Object take() {
    Object v = value;
    value = null;
    return v;
  }
}
