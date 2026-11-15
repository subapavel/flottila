package io.flottila.mapping.ir;

import java.util.List;

/**
 * The single-value step over queues (ADR-0006 as refined by #51), with the export's {@code po:lift}
 * semantics: {@code body} once per slot, slot variable k holding input k's value at the slot.
 * Context i of the result pairs context i of every input; an empty or missing context gives an
 * empty one; the longest input sets the slot count and a shorter one reuses its last value; and
 * SUPPRESS in any input gives SUPPRESS without running the body.
 *
 * @param queues the inputs, each a queue; at least one
 * @param slotVars the {@code STRING} slot variable of each input, in input order
 * @param body a {@code STRING} that reads only the slot variables; for a function box, its atomic
 *     {@link FunCall}
 * @param origin the function box
 */
public record Lift(List<Expr> queues, List<String> slotVars, Expr body, AuthoringId origin)
    implements Expr {

  public Lift {
    queues = List.copyOf(queues);
    slotVars = List.copyOf(slotVars);
  }
}
