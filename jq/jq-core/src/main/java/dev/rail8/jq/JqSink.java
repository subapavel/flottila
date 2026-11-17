package dev.rail8.jq;

import dev.rail8.jq.value.JqValue;

/** Receives a run's results. A result is valid during its call. */
public interface JqSink {
  /** Takes one result; false stops the run. */
  boolean emit(JqValue v);
}
