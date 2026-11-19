package dev.rail8.jq.query.ir;

import java.util.Collections;
import java.util.List;

/**
 * {@code values[0] as $slots[0] | … | values[n-1] as $slots[n-1] | body} as one node: it means
 * {@code Bind(values[0], trackings[0], slots[0], … Bind(values[n-1], trackings[n-1], slots[n-1], body))}, the first
 * outermost, and runs as that chain does. It has n ≥ 2 values, one tracking and one slot per value; slot k is in scope
 * in the later values and in {@code body} only. It ticks and charges nothing of its own.
 */
public record Binds(Source source, List<Node> values, List<Tracking> trackings, List<Integer> slots, Node body) implements Node {
  public Binds {
    values = List.copyOf(values);
    trackings = List.copyOf(trackings);
    slots = List.copyOf(slots);
  }

  /** A Binds whose values all run in value mode in a path variant too. */
  public Binds(Source source, List<Node> values, List<Integer> slots, Node body) {
    this(source, values, Collections.nCopies(values.size(), Tracking.VALUE), slots, body);
  }
}
