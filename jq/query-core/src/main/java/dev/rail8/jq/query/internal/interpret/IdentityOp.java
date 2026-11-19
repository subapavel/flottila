package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.Path;
import dev.rail8.jq.query.run.PathSink;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;

/** The identity: its one result is its input, at the input's own path, and the sink's answer is the run's. */
public final class IdentityOp extends Op {
  /** The identity in the run form {@code valueForm}. */
  public IdentityOp(boolean valueForm) {
    super(valueForm);
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    return out.emit(in);
  }

  @Override
  public Object eval(RunState state, Object[] slots, Object in) {
    return in;
  }

  @Override
  void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    t.emit(out, under, in);
  }

  @Override
  public boolean runPath(RunState state, Object[] slots, Object in, Path at, PathSink out) {
    return out.emit(in, at);
  }

  @Override
  void startPath(Trampoline t, HeapForm under, Object[] slots, Object in, Path at, HeapPathSink out) {
    t.emitPath(out, under, in, at);
  }
}
