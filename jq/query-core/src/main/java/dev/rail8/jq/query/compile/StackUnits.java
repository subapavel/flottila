package dev.rail8.jq.query.compile;

import dev.rail8.jq.query.run.RunState;

/**
 * The stack units of the interpreter: the release constants {@code b} and the reserve, and the unit limit {@code U_f} a
 * run derives from its declared stack. A stack unit is one Java frame of the push form, lambda and proxy frames
 * included. The calibration matrix measures the constants in {@code -Xint} and C1 only, whose stack edges
 * are stable, and checks that they hold in the warm default JIT, where the JIT still compiling during a search makes
 * the edges unreliable.
 */
public final class StackUnits {
  /**
   * {@code b}, the bytes per unit: the largest slope of the matrix's {@code -Xint} and C1 cells between two adjacent
   * thread sizes, 189 B (C1, the wide body W, CONSERVATIVE, and the plain descent), rounded up to 8 B: 192.
   */
  static final long BYTES_PER_UNIT = 192;

  /**
   * The reserve: the largest linked intercept {@code S - s_low·(U*linked + D_f)}, where {@code s_low} is the shape's
   * slope between its two lowest platform threads, taken on those two threads and the virtual one, 118 064 B (C1, a
   * virtual thread, the wide body W, CONSERVATIVE), plus the largest cold-linkage term, 30 996 B ({@code -Xint},
   * 256 KiB, the Collect-heavy descent, CONSERVATIVE), rounded up to 4 KiB, plus 32 KiB for the natives: 180 KiB. It
   * is above the guard pages plus 32 KiB.
   */
  static final long RESERVE = 180 * 1024;

  /**
   * {@code T}, the starter units: the most frames the driver holds above a starter's own frames, Trampoline.run, its
   * loop, an action and the sink proxy, sink body and helper under which host code runs.
   */
  static final long STARTER = 5;

  private StackUnits() {}

  /** {@code b}, the bytes per unit, a release constant. */
  public static long bytesPerUnit() {
    return BYTES_PER_UNIT;
  }

  /** The reserve in bytes, a release constant. */
  public static long reserve() {
    return RESERVE;
  }

  /** {@code D_f} of {@code executable}: the most frames main or any body nests, each call counted as a starter nests it. */
  static long callFreeDepth(Executable executable) {
    return executable.nesting().callFreeDepth();
  }

  /** {@code T} of {@code executable}: the units a starter adds for its own frames. */
  static long starterUnits(Executable executable) {
    return executable.nesting().starterUnits();
  }

  /** {@code U_f} of a run of {@code executable} declaring {@code stackBytes}. */
  static long unitLimit(Executable executable, long stackBytes) {
    return unitLimit(stackBytes, executable.nesting().callFreeDepth());
  }

  /**
   * {@code U_f = floorDiv(stackBytes - reserve, b) - D_f} of a run declaring {@code stackBytes}, a non-negative count,
   * whose filter nests {@code callFreeDepth} frames: at 0 or below, the run entry starts the trampoline.
   */
  static long unitLimit(long stackBytes, long callFreeDepth) {
    return Math.floorDiv(stackBytes - RESERVE, BYTES_PER_UNIT) - callFreeDepth;
  }

  /** The units {@code state}'s run holds now. */
  static long units(RunState state) {
    return state.units();
  }
}
