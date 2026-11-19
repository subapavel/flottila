package dev.rail8.jq.query.internal.interpret;

/**
 * A function of a program as the interpreter runs it: its frame is its slots followed by its closure cells and its
 * hidden cells, under one count. The compiler makes every function's shell first, so a call
 * links to it before its body is built, then defines each body once, before the executable is published.
 */
public final class LiftedFunction {
  /** The body's slots: its value parameters, then the slots its nodes bind. */
  private final int slots;

  /** The frame size: {@link #slots}, then one cell per closure parameter, then the hidden cells. */
  private final int cells;

  /** The hidden cell that holds the input's path in a path variant, the first after the closure cells; -1 for none. */
  private final int pathCell;

  private Op body;

  /** A function with no hidden cell. */
  public LiftedFunction(int slots, int closures) {
    this(slots, closures, 0);
  }

  /**
   * A function with {@code hidden} cells after its closure cells: none, or first the input path's cell, then the cells
   * its body's items keep their paths in.
   */
  public LiftedFunction(int slots, int closures, int hidden) {
    this.slots = slots;
    this.cells = slots + closures + hidden;
    this.pathCell = hidden == 0 ? -1 : slots + closures;
  }

  /** The cell of the frame that holds the input's path in a path variant, or -1 when the frame has none. */
  public int pathCell() {
    return pathCell;
  }

  /** Sets the body, once, before any run. */
  public void define(Op body) {
    this.body = body;
  }

  Op body() {
    return body;
  }

  /**
   * A fresh frame for an activation that a Call or a jump enters from the running {@code slots}: its value parameters
   * read on {@code in}, then its closures, each bound in {@code slots}.
   */
  Object[] frame(Argument[] values, ClosureArgument[] closures, Object[] slots, Object in) {
    Object[] frame = new Object[cells];
    for (int k = 0; k < values.length; k++) {
      frame[k] = values[k].read(slots, in);
    }
    for (int j = 0; j < closures.length; j++) {
      frame[this.slots + j] = closures[j].bind(slots);
    }
    return frame;
  }
}
