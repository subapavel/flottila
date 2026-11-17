package io.github.subapavel.jqj;

import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqValue;
import java.util.Objects;

/**
 * An evaluation that ended and cannot be caught by jq (ADR-0016, ADR-0008, ADR-0017): its cause, the exit
 * code, the value it carries ({@link JqNull#NULL} when there is none, ADR-0024) and, for the
 * {@link Cause#BUDGET} cause only, the budget that ran out.
 */
public final class JqTermination extends JqException {
  private static final long serialVersionUID = 1L;

  /** Why the evaluation ended. */
  public enum Cause {
    HALT,
    BUDGET,
    STACK,
    STALE_VIEW,
    CANCELLED
  }

  /** The budget of {@link Limits} that ran out. */
  public enum Budget {
    STEPS,
    DEADLINE,
    RECURSION_DEPTH,
    BYTES_BUILT,
    BYTES_WRITTEN
  }

  private final Cause cause;
  private final int exitCode;

  // A JqValue is not Serializable (a view lives on its Workspace's tape): serializing the exception
  // fails instead of dropping the value, as a transient field would.
  @SuppressWarnings("serial")
  private final JqValue value;
  private final Budget budget;

  /**
   * @throws NullPointerException if {@code cause} or {@code value} is null
   * @throws IllegalArgumentException if a budget is given with a cause other than {@link Cause#BUDGET},
   *     or none with {@link Cause#BUDGET}
   */
  public JqTermination(Cause cause, int exitCode, JqValue value, Budget budget) {
    this(cause, exitCode, value, budget, Objects.requireNonNull(cause, "cause").name());
  }

  /**
   * As the public constructor, with {@code message} instead of the cause's name: the text a run's budget carries
   * (ADR-0030).
   */
  JqTermination(Cause cause, int exitCode, JqValue value, Budget budget, String message) {
    Objects.requireNonNull(value, "value");
    if ((cause == Cause.BUDGET) != (budget != null)) {
      throw new IllegalArgumentException("a budget is given exactly with the cause BUDGET: cause " + cause + ", budget " + budget);
    }
    super(message);
    this.cause = cause;
    this.exitCode = exitCode;
    this.value = value;
    this.budget = budget;
  }

  public Cause cause() {
    return cause;
  }

  public int exitCode() {
    return exitCode;
  }

  /** The value the termination carries, {@link JqNull#NULL} when there is none. */
  public JqValue value() {
    return value;
  }

  /** The budget that ran out for {@link Cause#BUDGET}, null for every other cause. */
  public Budget budget() {
    return budget;
  }
}
