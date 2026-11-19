package dev.rail8.jq.query.ir;

/**
 * {@code value as $slot | body}: each result of {@code value} sets the slot, and {@code body}
 * runs on the Bind's own input; {@code tracking} is how the value runs in a path variant. A slot is bound by one Bind
 * or one {@link AltBind} of the tree, or is a parameter, and a {@link Var} of a Bind's slot occurs only inside
 * {@code body}.
 */
public record Bind(Source source, Node value, Tracking tracking, int slot, Node body) implements Node {
  /** A Bind whose value runs in value mode in a path variant too. */
  public Bind(Source source, Node value, int slot, Node body) {
    this(source, value, Tracking.VALUE, slot, body);
  }
}
