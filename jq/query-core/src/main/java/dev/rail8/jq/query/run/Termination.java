package dev.rail8.jq.query.run;

/**
 * An end of the evaluation that no filter can catch: a budget ran out, the thread was interrupted, or the stack ran
 * out. The engine translates it once, where the run began, into its own termination with exit code 5.
 */
public final class Termination extends EvaluationException {
  private static final long serialVersionUID = 1L;

  /** Why the evaluation ended. */
  public enum Cause {
    /** A budget of the run state ran out; {@link #budget()} says which. */
    BUDGET,
    /** The thread was interrupted; its interrupt flag is still set. */
    CANCELLED,
    /** The thread's stack ran out: a backstop, which only an executable's run raises. */
    STACK
  }

  /** The budget that ran out, and the message it carries: the texts are the engine's own. */
  public enum Budget {
    STEPS("Step budget exceeded"),
    DEADLINE("Deadline exceeded"),
    RECURSION_DEPTH("Recursion-depth budget exceeded"),
    BYTES_BUILT("Bytes-built budget exceeded"),
    BYTES_WRITTEN("Bytes-written budget exceeded");

    private final String message;

    Budget(String message) {
      this.message = message;
    }
  }

  private final Cause cause;
  private final Budget budget;

  private Termination(Cause cause, Budget budget, String message) {
    super(message);
    this.cause = cause;
    this.budget = budget;
  }

  /** The run went over {@code budget}; the message is the budget's. */
  static Termination over(Budget budget) {
    return new Termination(Cause.BUDGET, budget, budget.message);
  }

  /** The run's thread was interrupted; there is no message. */
  static Termination cancelled() {
    return new Termination(Cause.CANCELLED, null, null);
  }

  /** The run's stack ran out; the message is "Stack exhausted". */
  public static Termination stack() {
    return new Termination(Cause.STACK, null, "Stack exhausted");
  }

  public Cause cause() {
    return cause;
  }

  /** The budget that ran out for {@link Cause#BUDGET}, null for every other cause. */
  public Budget budget() {
    return budget;
  }
}
