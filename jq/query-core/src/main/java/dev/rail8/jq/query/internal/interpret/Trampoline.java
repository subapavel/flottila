package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.FilterException;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;

/**
 * The trampoline: a driver that runs the ops' heap forms one action at a time, so its Java depth does not grow with
 * the filter. An action is a start of an op under a form, an emit of a
 * result into a sink, an answer to a form, or an error raised at a form. Each action sets exactly one next action, so
 * no sink calls a sink and no form calls a form. A consumer starts above the form that produced its input, so an error
 * raised downstream passes back down through the forms that produced the result, as it passes Java frames in push form.
 * A bounce from one action to the next charges no step.
 *
 * <p>The starter, the push-form frame that runs main on the driver, does so with {@link #run}. Main starts under
 * {@link #BASE}, whose answer ends the run, and its results go to {@link #HAND_BACK}, which stops the driver at each
 * result and hands it back. The starter emits that result from its own frame, with no driver live, and resumes the
 * driver at the form that produced it with the emit's answer or with the error the emit raised. A Termination ends
 * the run wherever it is thrown.
 */
public final class Trampoline {
  private static final int START = 0;
  private static final int EMIT = 1;
  private static final int ANSWER = 2;
  private static final int RAISE = 3;
  private static final int HANDED_BACK = 4;
  private static final int ENDED = 5;
  private static final int FAILED = 6;

  /** The form main starts under: its answer ends the run, and an error raised at it leaves the run. */
  private static final HeapForm BASE = new Base();

  /** Main's sink: it hands each result back to the starter with the form that produced it. */
  private static final HeapSink HAND_BACK = (t, under, v) -> t.handBack(under, v);

  /** The run this driver belongs to: the heap forms tick, charge and count on it. */
  final RunState state;

  /** The next action, or {@link #HANDED_BACK}, {@link #ENDED} or {@link #FAILED} when the driver stopped. */
  private int action;

  /** The op a start starts. */
  private Op op;

  /** The form of the next action: started under, produced the result, answered, or raised at. */
  private HeapForm form;

  /** The frame's cells a start runs with. */
  private Object[] slots;

  /** A start's input, or the result an emit or a hand-back carries. */
  private Object value;

  /** The sink a start sends results to, or the sink an emit sends its result into. */
  private HeapSink sink;

  /** An answer's value, or the run's answer once it ended. */
  private boolean more;

  /** A raise's error, or the run's error once it failed. */
  private FilterException error;

  private Trampoline(RunState state) {
    this.state = state;
  }

  /**
   * Runs {@code body} on a new driver, in the run {@code state} belongs to, for {@code in} with the cells
   * {@code frame}: each result is emitted into {@code out} from this frame, with no driver live, and the emit's
   * answer or error goes back to the form that produced it. True when the results ran out, false when out stopped.
   *
   * @throws FilterException the error that left the body
   */
  public static boolean run(RunState state, Op body, Object[] frame, Object in, Sink out) {
    Trampoline t = new Trampoline(state);
    t.start(body, BASE, frame, in, HAND_BACK);
    while (t.drive()) {
      HeapForm producer = t.form;
      try {
        t.answer(producer, out.emit(t.value));
      } catch (FilterException e) {
        t.raise(producer, e);
      }
    }
    if (t.action == FAILED) {
      throw t.error;
    }
    return t.more;
  }

  /**
   * Runs actions until the run ends or a result is handed back: true at a hand-back. A FilterException out of a start,
   * an emit or a resume is raised at that action's form, where the push form's exception goes: the form the op started
   * under, the form that produced the result, or the resumed form, whose own code threw it and which passes it on (no
   * form that catches throws from its resume). A fail throws no FilterException: it passes the error on, or catches it.
   */
  private boolean drive() {
    while (action < HANDED_BACK) {
      if (action == RAISE) {
        form.fail(this, error);
        continue;
      }
      HeapForm at = form;
      try {
        switch (action) {
          case START -> op.start(this, at, slots, value, sink);
          case EMIT -> sink.accept(this, at, value);
          default -> at.resume(this, more);
        }
      } catch (FilterException e) {
        raise(at, e);
      }
    }
    return action == HANDED_BACK;
  }

  /** The next action: {@code op} starts under {@code under}, for {@code in} with {@code slots}, into {@code out}. */
  void start(Op op, HeapForm under, Object[] slots, Object in, HeapSink out) {
    action = START;
    this.op = op;
    form = under;
    this.slots = slots;
    value = in;
    sink = out;
  }

  /** The next action: {@code out} takes the result {@code v} of the form {@code under}. */
  void emit(HeapSink out, HeapForm under, Object v) {
    action = EMIT;
    sink = out;
    form = under;
    value = v;
  }

  /** The next action: {@code form} resumes with {@code more}, what it waits for answered. */
  void answer(HeapForm form, boolean more) {
    action = ANSWER;
    this.form = form;
    this.more = more;
  }

  /** The next action: the error {@code e} is raised at {@code form}. */
  void raise(HeapForm form, FilterException e) {
    action = RAISE;
    this.form = form;
    error = e;
  }

  /** The driver stops: the starter emits the result {@code v} of the form {@code under}. */
  private void handBack(HeapForm under, Object v) {
    action = HANDED_BACK;
    form = under;
    value = v;
  }

  /** The form under main: main's answer is the run's, and an error at it leaves the run. */
  private static final class Base extends HeapForm {
    Base() {
      super(null);
    }

    @Override
    void resume(Trampoline t, boolean more) {
      t.action = ENDED;
      t.more = more;
    }

    @Override
    void fail(Trampoline t, FilterException e) {
      t.action = FAILED;
      t.error = e;
    }
  }
}
