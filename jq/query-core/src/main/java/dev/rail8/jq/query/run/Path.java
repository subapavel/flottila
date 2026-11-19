package dev.rail8.jq.query.run;

/**
 * Where a result was found in a path variant's input: the keys from the input to the result, as a chain from the last
 * key back to {@link #ROOT}. {@code parent} is the path without its last key, {@code key} that key, as the step that
 * added it built it, and {@code length} the number of keys. A path is immutable and may be shared by any number of
 * longer ones; making one charges nothing.
 */
public record Path(Path parent, Object key, int length) {
  /** The path of the input itself: no key, no parent, length 0. */
  public static final Path ROOT = new Path(null, null, 0);

  /** This path with {@code key} added at its end. */
  public Path child(Object key) {
    return new Path(this, key, length + 1);
  }
}
