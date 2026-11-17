package io.github.subapavel.jqj;

import io.github.subapavel.jqj.value.JqValue;

/** Receives a run's results (ADR-0016). A result is valid during its call. */
public interface JqSink {
  /** Takes one result; false stops the run. */
  boolean emit(JqValue v);
}
