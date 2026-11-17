package io.github.subapavel.jqj.query.run;

/** Receives pushed results (ADR-0008). */
public interface Sink {
  /** Takes one result; false is a stop: the producer emits nothing more and returns false at once. */
  boolean emit(Object v);
}
