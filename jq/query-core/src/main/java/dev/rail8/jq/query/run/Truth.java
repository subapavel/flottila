package dev.rail8.jq.query.run;

/**
 * What an {@code If} or an {@code Alt} node asks of a result: the format's own test, passed to the node as data, like
 * a {@link Collector}, so the core never looks inside a value.
 */
@FunctionalInterface
public interface Truth {
  /** Whether {@code value} counts as true. */
  boolean test(Object value);
}
