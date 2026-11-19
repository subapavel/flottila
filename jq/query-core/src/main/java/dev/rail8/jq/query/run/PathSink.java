package dev.rail8.jq.query.run;

/** Receives pushed results with their paths, in a path variant. */
public interface PathSink {
  /**
   * Takes one result and its path in the path variant's input, null when the result was not found there; false is a
   * stop, as for {@link Sink#emit}.
   */
  boolean emit(Object v, Path p);
}
