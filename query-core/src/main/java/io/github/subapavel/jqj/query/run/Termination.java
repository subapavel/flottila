package io.github.subapavel.jqj.query.run;

/**
 * An end of the evaluation that no filter can catch (ADR-0008, ADR-0030): a budget ran out, or the thread was
 * interrupted. The engine translates it once, where the run began, into its own termination with exit code 5. The
 * causes halt, stack and stale view join with their first user.
 */
public final class Termination extends EvaluationException {
  private static final long serialVersionUID = 1L;

  /** Why the evaluation ended. */
  public enum Cause {
    /** A budget of the run state ran out; {@link #budget()} says which. */
    BUDGET,
    /** The thread was interrupted (ADR-0017); its interrupt flag is still set. */
    CANCELLED
  }

  /** The budget that ran out, and the message it carries: the texts are the engine's own (ADR-0030, ADR-0035). */
  public enum Budget {
    STEPS("Step budget exceeded"),
    DEADLINE("Deadline exceeded"),
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

  public Cause cause() {
    return cause;
  }

  /** The budget that ran out for {@link Cause#BUDGET}, null for {@link Cause#CANCELLED}. */
  public Budget budget() {
    return budget;
  }
}
