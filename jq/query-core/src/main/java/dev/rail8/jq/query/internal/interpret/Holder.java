package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.Sink;

/**
 * The sink a tail emitter runs into (ADR-0043 as amended by F-85): it holds the one result until {@link #take} and
 * answers it with a stop. So the tail run answers false exactly when its result came, and true when none did; the If
 * or Binds that owns the holder takes that result and does not pass the false on (ADR-0039).
 */
final class Holder implements Sink {
  private Object value;

  @Override
  public boolean emit(Object v) {
    value = v;
    return false;
  }

  /** The result that came since the last take; the holder is cleared again. */
  Object take() {
    Object v = value;
    value = null;
    return v;
  }
}
