package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.Path;
import dev.rail8.jq.query.run.PathSink;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;

/**
 * A closure argument of one call activation: its body and the frame it was written in, its defining frame. A body
 * that binds slots runs in a fresh frame of {@code cells} cells whose cell 0 links the defining frame; one that binds
 * none, {@code cells} 0, runs in the defining frame itself. It carries its body's
 * leaf-emit depth, which a ParamCall that runs it holds as stack units.
 */
final class Closure {
  private final Op body;
  private final Object[] frame;
  private final int cells;
  private final long emitDepth;

  Closure(Op body, Object[] frame, int cells, long emitDepth) {
    this.body = body;
    this.frame = frame;
    this.cells = cells;
    this.emitDepth = emitDepth;
  }

  /**
   * Pushes the body's results for {@code in} into {@code out}: true when they ran out, false when out stopped. A
   * value-form body returns its value, emitted here.
   */
  boolean run(RunState state, Object in, Sink out) {
    if (!body.valueForm) {
      return body.run(state, frame(), in, out);
    }
    Object v = body.eval(state, frame(), in);
    return v == CallForm.NONE || out.emit(v);
  }

  /** {@link #run} with this frame as the starter: the body runs on a new trampoline. */
  boolean drive(RunState state, Object in, Sink out) {
    return Trampoline.run(state, body, frame(), in, out);
  }

  /** {@link #run}'s heap form: the body starts on the trampoline under {@code under}, in the same frame. */
  void start(Trampoline t, HeapForm under, Object in, HeapSink out) {
    t.start(body, under, frame(), in, out);
  }

  /** The path variant of {@link #run}: the body's path variant pushes the results for {@code in} at {@code at}. */
  boolean runPath(RunState state, Object in, Path at, PathSink out) {
    return body.runPath(state, frame(), in, at, out);
  }

  /** {@link #runPath} with this frame as the starter: the body's path variant runs on a new trampoline. */
  boolean drivePath(RunState state, Object in, Path at, PathSink out) {
    return Trampoline.runPath(state, body, frame(), in, at, out);
  }

  /** {@link #runPath}'s heap form: the body's path variant starts on the trampoline under {@code under}. */
  void startPath(Trampoline t, HeapForm under, Object in, Path at, HeapPathSink out) {
    t.startPath(body, under, frame(), in, at, out);
  }

  /** The frames the body nests from its root to an emit no call's result leads to. */
  long emitDepth() {
    return emitDepth;
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
