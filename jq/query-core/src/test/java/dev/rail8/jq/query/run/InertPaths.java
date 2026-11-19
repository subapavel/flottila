package dev.rail8.jq.query.run;

/** A path format for tests of passes that never run one: each method throws. */
public final class InertPaths implements PathFormat {
  /** A new format, an identity of its own. */
  public InertPaths() {}

  @Override
  public Object value(RunState s, Path p) {
    throw new AssertionError("a pass ran the format");
  }

  @Override
  public FilterException invalid(RunState s, Object result) {
    throw new AssertionError("a pass ran the format");
  }

  @Override
  public Edit modify(RunState s, Object root) {
    throw new AssertionError("a pass ran the format");
  }

  @Override
  public Edit assign(RunState s, Object root) {
    throw new AssertionError("a pass ran the format");
  }
}
