package io.github.subapavel.jqj.query.internal.interpret;

/**
 * A function of a program as the interpreter runs it (GLOSSARY "Lifted function", ADR-0045 #107): its frame is its
 * slots followed by its closure cells, under one count. The compiler makes every function's shell first, so a call
 * links to it before its body is built, then defines each body once, before the executable is published.
 */
public final class LiftedFunction {
  /** The body's slots: its value parameters, then the slots its nodes bind. */
  private final int slots;

  /** The frame size: {@link #slots}, then one cell per closure parameter. */
  private final int cells;

  private Op body;

  public LiftedFunction(int slots, int closures) {
    this.slots = slots;
    this.cells = slots + closures;
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
