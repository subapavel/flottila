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

  /** Whether each node may break out (ADR-0007 #115), by body as {@link #cardinality} and by freeze id. */
  private final boolean[][] breaks;

  private Facts(Cardinality[][] cardinality, boolean[][] breaks) {
    this.cardinality = cardinality;
    this.breaks = breaks;
  }

  /** The facts the analyses prove for the program {@code numbering} numbers, whose recursive groups {@code graph} gives. */
  public static Facts optimised(Numbering numbering, CallGraph graph) {
    return new Facts(CardinalityAnalysis.of(numbering, graph), BreakOutAnalysis.of(numbering, graph));
  }

  /** The safe value of every fact for the program {@code numbering} numbers: every node MANY and may break out. */
  public static Facts conservative(Numbering numbering) {
    Cardinality[][] cardinality = new Cardinality[numbering.functions() + 1][];
    boolean[][] breaks = new boolean[cardinality.length][];
    for (int body = 0; body < cardinality.length; body++) {
      cardinality[body] = new Cardinality[numbering.nodes(body - 1).size()];
      Arrays.fill(cardinality[body], Cardinality.MANY);
      breaks[body] = new boolean[cardinality[body].length];
      Arrays.fill(breaks[body], true);
    }
    return new Facts(cardinality, breaks);
  }

  /**
   * The cardinality of node {@code id} of the body {@code function}, -1 for the main tree; never NEVER, which a fact
   * keeps as NONE (ADR-0007 #112).
   */
  public Cardinality cardinality(int function, int id) {
    return cardinality[function + 1][id];
  }

  /**
   * Whether node {@code id} of the body {@code body}, -1 for the main tree, may break out: whether a run of it may end by
   * a Break to a Label activation the run did not start (ADR-0007 #115).
   */
  public boolean mayBreakOut(int body, int id) {
    return breaks[body + 1][id];
  }
}
