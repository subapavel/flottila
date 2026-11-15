package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/**
 * A closure argument of one call activation (GLOSSARY "Closure", ADR-0045 #107): its body and the frame it was written
 * in, its defining frame. A body that binds slots runs in a fresh frame of {@code cells} cells whose cell 0 links the
 * defining frame; one that binds none, {@code cells} 0, runs in the defining frame itself.
 */
final class Closure {
  private final Op body;
  private final Object[] frame;
  private final int cells;

  Closure(Op body, Object[] frame, int cells) {
    this.body = body;
    this.frame = frame;
    this.cells = cells;
  }

  /** Pushes the body's results for {@code in} into {@code out}: true when they ran out, false when out stopped. */
  boolean run(RunState state, Object in, Sink out) {
    return body.run(state, frame(), in, out);
  }

  /** {@link #run}'s heap form: the body starts on the trampoline under {@code under}, in the same frame. */
  void start(Trampoline t, HeapForm under, Object in, HeapSink out) {
    t.start(body, under, frame(), in, out);
  }

  /** The frame the body runs in: the defining frame itself, or a fresh one whose cell 0 links it. */
  private Object[] frame() {
    if (cells == 0) {
      return frame;
    }
    Object[] own = new Object[cells];
    own[0] = frame;
    return own;
  }
}
