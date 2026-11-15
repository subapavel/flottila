package io.github.subapavel.jqj.query.analysis;

import java.util.Arrays;

/**
 * The facts of a compiled program (ADR-0007, ADR-0045 #112): an immutable side table, each fact an array by freeze id
 * per body. A body is {@code -1} for the main tree and a function's place otherwise; a function's own value is its body
 * root's, id 0. The optimised compile analyses the program; the conservative compile keeps every fact at its safe value
 * (ADR-0018).
 */
public final class Facts {
  /** The cardinality of each node by body, the main tree's first and then each function's by place, and by freeze id. */
  private final Cardinality[][] cardinality;

  private Facts(Cardinality[][] cardinality) {
    this.cardinality = cardinality;
  }

  /** The facts the analyses prove for the program {@code numbering} numbers, whose recursive groups {@code graph} gives. */
  public static Facts optimised(Numbering numbering, CallGraph graph) {
    return new Facts(CardinalityAnalysis.of(numbering, graph));
  }

  /** The safe value of every fact for the program {@code numbering} numbers: every node MANY. */
  public static Facts conservative(Numbering numbering) {
    Cardinality[][] cardinality = new Cardinality[numbering.functions() + 1][];
    for (int body = 0; body < cardinality.length; body++) {
      cardinality[body] = new Cardinality[numbering.nodes(body - 1).size()];
      Arrays.fill(cardinality[body], Cardinality.MANY);
    }
    return new Facts(cardinality);
  }

  /**
   * The cardinality of node {@code id} of the body {@code function}, -1 for the main tree; never NEVER, which a fact
   * keeps as NONE (ADR-0007 #112).
   */
  public Cardinality cardinality(int function, int id) {
    return cardinality[function + 1][id];
  }
}
