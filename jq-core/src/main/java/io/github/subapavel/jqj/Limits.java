package io.github.subapavel.jqj;

import java.time.Duration;
import java.util.Objects;

/**
 * The budgets of an evaluation (ADR-0017). A budget with no limit holds its largest value,
 * {@link #UNLIMITED} or {@link #UNLIMITED_TIME}, never 0 or a negative (ADR-0024). The engine carries
 * them; enforcing them is spec 2's.
 *
 * @param steps the steps an evaluation may take
 * @param deadline the wall-clock time an evaluation may take
 * @param nestingDepth the deepest nesting a document may have (a jq parse error, not a termination)
 * @param recursionDepth the live activations an evaluation may hold
 * @param bytesBuilt the string bytes and container slots an evaluation may build
 * @param bytesWritten the bytes a writer may write
 * @param regexMatchTime the time one regex match may take
 * @param stackBytes the stack the evaluation declares, from which each run derives how many stack units of its calls
 *     stay on the Java stack before a call starts the heap-form trampoline (ADR-0011, ADR-0046): {@code U_f =
 *     floorDiv(stackBytes - reserve, b) - D_f}, with the reserve 128 KiB (96 KiB of guard pages and 32 KiB for
 *     natives), {@code b} the bytes per unit, provisionally 96 until #113 calibrates it ({@code bench/RESULTS.md},
 *     #111), and {@code D_f} the filter's call-free depth; {@code explain()} shows all three. It is not a budget: 0 means zero bytes, never unlimited, and at or below {@code reserve + b * D_f}, 0
 *     included, main and every body run on the trampoline; the engine still needs the reserve's stack for the
 *     trampoline and natives. The thread must have the declared bytes.
 */
public record Limits(
    long steps,
    Duration deadline,
    int nestingDepth,
    int recursionDepth,
    long bytesBuilt,
    long bytesWritten,
    Duration regexMatchTime,
    long stackBytes) {

  /** A count budget that never runs out (GLOSSARY "Unlimited"). */
  public static final long UNLIMITED = Long.MAX_VALUE;

  /** A time budget that never runs out (GLOSSARY "Unlimited"). */
  public static final Duration UNLIMITED_TIME = Duration.ofNanos(Long.MAX_VALUE);

  /** 1 GiB. */
  private static final long GIB = 1L << 30;

  /**
   * @throws IllegalArgumentException if a count is negative, or a time is negative or longer than
   *     {@link #UNLIMITED_TIME}
   * @throws NullPointerException if a time is null
   */
  public Limits {
    requireNonNegative(steps, "steps");
    requireTime(deadline, "deadline");
    requireNonNegative(nestingDepth, "nestingDepth");
    requireNonNegative(recursionDepth, "recursionDepth");
    requireNonNegative(bytesBuilt, "bytesBuilt");
    requireNonNegative(bytesWritten, "bytesWritten");
    requireTime(regexMatchTime, "regexMatchTime");
    requireNonNegative(stackBytes, "stackBytes");
  }

  /** The engine's default (ADR-0017). */
  public static Limits embedded() {
    return new Limits(1_000_000_000L, Duration.ofSeconds(10), 10_000, 4_194_304, GIB, GIB, Duration.ofSeconds(1), 512 * 1024L);
  }

  /**
   * The command line's (ADR-0017): {@link #embedded()}'s parse depth, recursion and regex limits and stack
   * bytes; the steps, the deadline and both byte budgets are unlimited.
   */
  public static Limits cli() {
    Limits embedded = embedded();
    return new Limits(
        UNLIMITED,
        UNLIMITED_TIME,
        embedded.nestingDepth(),
        embedded.recursionDepth(),
        UNLIMITED,
        UNLIMITED,
        embedded.regexMatchTime(),
        embedded.stackBytes());
  }

  private static void requireNonNegative(long value, String name) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " is negative: " + value);
    }
  }

  private static void requireTime(Duration value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isNegative() || value.compareTo(UNLIMITED_TIME) > 0) {
      throw new IllegalArgumentException(name + " is not between 0 and Limits.UNLIMITED_TIME: " + value);
    }
  }
}
