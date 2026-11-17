package dev.rail8.jq.query.run;

/** Receives pushed results. */
public interface Sink {
  /** Takes one result; false is a stop: the producer emits nothing more and returns false at once. */
  boolean emit(Object v);
}
