package io.github.subapavel.jqj.query.ir;

import java.util.List;

/**
 * {@code value as p1 ?// p2 ... | body} (ADR-0038, ADR-0005, ADR-0008): each result of {@code value} sets {@code slot}
 * and runs the alternatives in order, each on the chain's input. At each result of an alternative's match, each group
 * slot of {@code slots} is set from that alternative's own slot, or null, and {@code body} runs on the chain's input. A
 * FilterException from the match, the body or downstream starts the next alternative while one remains; the last one's
 * error propagates, and so does one from {@code value}. A stop or a Termination returns at once. Entering each
 * alternative after the first charges one step (F-32). A group slot is read only in {@code body}.
 */
public record AltBind(Source source, Node value, int slot, List<Alternative> alternatives, List<Integer> slots, Node body) implements Node {
  public AltBind {
    alternatives = List.copyOf(alternatives);
    slots = List.copyOf(slots);
  }
}
