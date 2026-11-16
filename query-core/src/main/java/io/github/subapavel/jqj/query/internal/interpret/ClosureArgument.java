package io.github.subapavel.jqj.query.internal.interpret;

/**
 * A closure argument of a call (ADR-0045 #107): one written at the call, whose closure each activation makes with the
 * running frame as its defining frame; one written at a jump, which reads no cell of the running frame and so is bound
 * once, with no defining frame (ADR-0047 #118); or a closure parameter of the caller passed on as it is, not wrapped.
 */
public final class ClosureArgument {
  /** The body of a written closure; null for a passed-on one. */
  private final Op body;

  /** A written closure's frame size, 0 for one that runs in its defining frame; a passed-on one's cell. */
  private final int cells;

  /** The links from the running frame to the frame holding a passed-on closure; 0 for a written one. */
  private final int level;

  /** A written closure's leaf-emit depth (ADR-0046 #111 amendment); a passed-on one's closure carries its own. */
  private final long emitDepth;

  /** The one closure of a closure written at a jump, with no defining frame; null for any other. */
  private final Closure closed;

  private ClosureArgument(Op body, int cells, int level, long emitDepth, boolean closed) {
    this.body = body;
    this.cells = cells;
    this.level = level;
    this.emitDepth = emitDepth;
    this.closed = closed ? new Closure(body, null, cells, emitDepth) : null;
  }

  /**
   * A closure written at the call: {@code body}, run in a frame of {@code cells} cells, or in its defining frame for 0,
   * whose frames from its root to an emit no call's result leads to are {@code emitDepth}.
   */
  public static ClosureArgument written(Op body, int cells, long emitDepth) {
    return new ClosureArgument(body, cells, 0, emitDepth, false);
  }

  /**
   * A closure written at a jump (ADR-0047 #118): as {@link #written}, but its body reads no cell of the frame it is
   * written in, no slot, Break token or closure parameter, so every jump binds the same closure, built here with no
   * defining frame, and a chain of jumps keeps no earlier frame alive.
   */
  public static ClosureArgument closed(Op body, int cells, long emitDepth) {
    return new ClosureArgument(body, cells, 0, emitDepth, true);
  }

  /** The closure in cell {@code cell} of the frame {@code level} links out from the running one, passed on. */
  public static ClosureArgument passed(int level, int cell) {
    return new ClosureArgument(null, cell, level, 0, false);
  }

  /** The closure this argument gives an activation that the running {@code frame} calls or jumps to. */
  Closure bind(Object[] frame) {
    if (closed != null) {
      return closed;
    }
    if (body == null) {
      return (Closure) Frames.out(frame, level)[cells];
    }
    return new Closure(body, frame, cells, emitDepth);
  }
}
