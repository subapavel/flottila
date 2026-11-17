package dev.rail8.jq.query.ir;

import java.util.List;

/**
 * One alternative of an {@link AltBind}: {@code match} is a chain of Binds that takes the chain's value apart, whose
 * innermost body is its leaf; {@code from} gives, for each group slot of the chain in its order, the alternative's own
 * slot that sets it, in scope at the leaf, or -1 for null.
 */
public record Alternative(Node match, List<Integer> from) {
  /** The from entry of a group slot the alternative has no slot for: the slot is set to null. */
  public static final int NULL = -1;

  public Alternative {
    from = List.copyOf(from);
  }
}
