package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.Edit;
import dev.rail8.jq.query.run.FilterException;
import dev.rail8.jq.query.run.Path;
import dev.rail8.jq.query.run.PathFormat;
import dev.rail8.jq.query.run.PathSink;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;

/**
 * {@code lhs |= rhs} (a modify) or {@code lhs = value} (an assign): one {@link Edit} of {@code paths} on the input,
 * then the lhs's path variant runs on the input from the root path, and each of its results' paths is edited inside
 * that result's emit, in the lhs's order. A modify gets the value at the path, runs the rhs on it to its first result,
 * then sets the path to that result, or deletes it when there is none; an assign sets the path to its value, read on
 * the input once before the lhs runs. After the lhs ran out the op emits the edit's end, once. An untracked lhs result
 * raises {@code paths}' invalid-path error when it comes. A break passes on with no result; a break or an error that
 * leaves the op abandons the edit, which lives in a local or a field of the heap form, never in a frame slot. Its own
 * path variants are the defaults: what it emits is untracked.
 */
public final class UpdateOp extends Op {
  private final Op lhs;

  /** A modify's rhs, which runs in value mode on the value at each path; null for an assign. */
  private final Op rhs;

  /** An assign's value, read on the input; null for a modify. */
  private final Argument value;

  private final PathFormat paths;

  private UpdateOp(boolean valueForm, Op lhs, Op rhs, Argument value, PathFormat paths) {
    super(valueForm);
    this.lhs = lhs;
    this.rhs = rhs;
    this.value = value;
    this.paths = paths;
  }

  /** {@code lhs |= rhs}. */
  public static UpdateOp modify(boolean valueForm, Op lhs, Op rhs, PathFormat paths) {
    return new UpdateOp(valueForm, lhs, rhs, null, paths);
  }

  /** {@code lhs = value}, {@code value} atomic. */
  public static UpdateOp assign(boolean valueForm, Op lhs, Argument value, PathFormat paths) {
    return new UpdateOp(valueForm, lhs, null, value, paths);
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    Edits edits = edits(state, slots, in);
    if (!lhs.runPath(state, slots, in, Path.ROOT, edits)) {
      return false;
    }
    return out.emit(edits.edit.end(state));
  }

  /** The edit's end after the lhs ran out: a value-form Update may not break out, so the lhs's run ends with true. */
  @Override
  public Object eval(RunState state, Object[] slots, Object in) {
    Edits edits = edits(state, slots, in);
    lhs.runPath(state, slots, in, Path.ROOT, edits);
    return edits.edit.end(state);
  }

  /** The lhs's sink in push form, over a new edit of {@code in} and, for an assign, its value read on {@code in}. */
  private Edits edits(RunState state, Object[] slots, Object in) {
    if (rhs == null) {
      Object v = value.read(slots, in);
      return new Edits(state, slots, paths.assign(state, in), v);
    }
    return new Edits(state, slots, paths.modify(state, in), null);
  }

  /** The lhs starts by its path variant under an {@link Editing} form, which holds the edit and is the lhs's sink. */
  @Override
  void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    Editing editing;
    if (rhs == null) {
      Object v = value.read(slots, in);
      editing = new Editing(under, slots, paths.assign(t.state, in), v, out);
    } else {
      editing = new Editing(under, slots, paths.modify(t.state, in), null, out);
    }
    t.startPath(lhs, editing, slots, in, Path.ROOT, editing);
  }

  /** The error for the untracked lhs result {@code v}. */
  private FilterException invalid(RunState state, Object v) {
    return paths.invalid(state, v);
  }

  /**
   * The lhs's sink in push form: each result's path is edited before the emit returns, so the rhs runs inside the
   * lhs's emit. A push-form rhs runs into a first-result holder reused for every path; a false from its run with no
   * result is a break, which passes on as a stop.
   */
  private final class Edits implements PathSink, Sink {
    private final RunState state;
    private final Object[] slots;
    final Edit edit;

    /** An assign's value. */
    private final Object assigned;

    /** The rhs's first result for the path being edited, or {@link CallForm#NONE} while none came. */
    private Object first = CallForm.NONE;

    Edits(RunState state, Object[] slots, Edit edit, Object assigned) {
      this.state = state;
      this.slots = slots;
      this.edit = edit;
      this.assigned = assigned;
    }

    @Override
    public boolean emit(Object v, Path p) {
      if (p == null) {
        throw invalid(state, v);
      }
      if (rhs == null) {
        edit.set(state, p, assigned);
        return true;
      }
      Object old = edit.get(state, p);
      Object r;
      if (rhs.valueForm) {
        r = rhs.eval(state, slots, old);
      } else {
        first = CallForm.NONE;
        boolean more = rhs.run(state, slots, old, this);
        r = first;
        first = CallForm.NONE;
        if (!more && r == CallForm.NONE) {
          return false;
        }
      }
      if (r == CallForm.NONE) {
        edit.delete(state, p);
      } else {
        edit.set(state, p, r);
      }
      return true;
    }

    /** The rhs's first result: kept, and answered with a stop. */
    @Override
    public boolean emit(Object r) {
      first = r;
      return false;
    }
  }

  /**
   * The heap form while the lhs runs, and the lhs's sink: an assign sets each result's path and answers it to go on; a
   * modify starts the rhs on the value at the path under a {@link Replacing} form above the result's producer, as a
   * Pipe starts its right side. When the lhs ran out, the edit's end is emitted; after a break nothing is.
   */
  private final class Editing extends HeapForm implements HeapPathSink {
    private final Object[] slots;
    private final Edit edit;
    private final Object assigned;
    private final HeapSink out;

    Editing(HeapForm under, Object[] slots, Edit edit, Object assigned, HeapSink out) {
      super(under);
      this.slots = slots;
      this.edit = edit;
      this.assigned = assigned;
      this.out = out;
    }

    @Override
    public void accept(Trampoline t, HeapForm producer, Object v, Path p) {
      if (p == null) {
        throw invalid(t.state, v);
      }
      if (rhs == null) {
        edit.set(t.state, p, assigned);
        t.answer(producer, true);
        return;
      }
      Object old = edit.get(t.state, p);
      Replacing replacing = new Replacing(producer, edit, p);
      t.start(rhs, replacing, slots, old, replacing);
    }

    @Override
    void resume(Trampoline t, boolean more) {
      if (!more) {
        t.answer(under, false);
        return;
      }
      t.emit(out, under, edit.end(t.state));
    }
  }

  /**
   * The heap form while the rhs runs on the value at one path, and the rhs's sink: its first result is kept and
   * answered with a stop. When the rhs's run answers, the path is set to that result, or deleted when none came, and
   * the lhs result's producer is answered to go on; a false with no result is a break, which passes there as a stop.
   */
  private static final class Replacing extends HeapForm implements HeapSink {
    private final Edit edit;
    private final Path path;
    private Object first = CallForm.NONE;

    Replacing(HeapForm producer, Edit edit, Path path) {
      super(producer);
      this.edit = edit;
      this.path = path;
    }

    @Override
    public void accept(Trampoline t, HeapForm producer, Object r) {
      first = r;
      t.answer(producer, false);
    }

    @Override
    void resume(Trampoline t, boolean more) {
      if (first == CallForm.NONE) {
        if (!more) {
          t.answer(under, false);
          return;
        }
        edit.delete(t.state, path);
      } else {
        edit.set(t.state, path, first);
      }
      t.answer(under, true);
    }
  }
}
