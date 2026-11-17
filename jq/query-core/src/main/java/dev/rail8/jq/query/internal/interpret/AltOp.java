package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;
import dev.rail8.jq.query.run.Truth;

/**
 * {@code left // right}: the left side's results for which the truth holds, as they come, and the right side's only
 * when there was none. A false on the left, a stop or a break, returns at once, without the right. It catches nothing
 * and charges no step of its own.
 */
public final class AltOp extends Op {
  private final Op left;
  private final Op right;
  private final Truth truth;

  public AltOp(boolean valueForm, Op left, Op right, Truth truth) {
    super(valueForm);
    this.left = left;
    this.right = right;
    this.truth = truth;
  }

  /**
   * Each side in its run form: a value-form left side's value is emitted here when the truth holds for it, and the
   * right side runs when it does not or there was none.
   */
  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    if (left.valueForm) {
      Object v = left.eval(state, slots, in);
      if (v != CallForm.NONE && truth.test(v)) {
        return out.emit(v);
      }
    } else {
      Truthy found = new Truthy(truth, out);
      if (!left.run(state, slots, in, found)) {
        return false;
      }
      if (found.any) {
        return true;
      }
    }
    if (!right.valueForm) {
      return right.run(state, slots, in, out);
    }
    Object w = right.eval(state, slots, in);
    return w == CallForm.NONE || out.emit(w);
  }

  /**
   * The left side's value when the truth holds for it, and the right side's otherwise. Both sides of a value-form Alt
   * are value form wherever they run: a push left side would be many, and a push right side after a left one that may
   * give a result would make the Alt many.
   */
  @Override
  public Object eval(RunState state, Object[] slots, Object in) {
    Object v = left.eval(state, slots, in);
    if (v != CallForm.NONE && truth.test(v)) {
      return v;
    }
    return right.eval(state, slots, in);
  }

  @Override
  void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    Left left = new Left(under, slots, in, out);
    t.start(this.left, left, slots, in, left);
  }

  /**
   * The heap form while the left side runs, and its sink: it passes on the results the truth holds for, above the form
   * that produced them, and answers the others to go on; when the left side ran out with none passed, the right side
   * runs in its place.
   */
  private final class Left extends HeapForm implements HeapSink {
    private final Object[] slots;
    private final Object in;
    private final HeapSink out;

    /** Whether a result passed. */
    private boolean any;

    Left(HeapForm under, Object[] slots, Object in, HeapSink out) {
      super(under);
      this.slots = slots;
      this.in = in;
      this.out = out;
    }

    @Override
    public void accept(Trampoline t, HeapForm producer, Object v) {
      if (!truth.test(v)) {
        t.answer(producer, true);
        return;
      }
      any = true;
      t.emit(out, producer, v);
    }

    @Override
    void resume(Trampoline t, boolean more) {
      if (!more || any) {
        t.answer(under, more);
        return;
      }
      t.start(right, under, slots, in, out);
    }
  }

  /** The left side's sink: it passes on the results the truth holds for and drops the others. */
  private static final class Truthy implements Sink {
    private final Truth truth;
    private final Sink out;

    /** Whether a result passed. */
    private boolean any;

    Truthy(Truth truth, Sink out) {
      this.truth = truth;
      this.out = out;
    }

    @Override
    public boolean emit(Object v) {
      if (!truth.test(v)) {
        return true;
      }
      any = true;
      return out.emit(v);
    }
  }
}
