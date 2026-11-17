package dev.rail8.jq.query.compile;

import dev.rail8.jq.query.run.RunState;

/**
 * The stack units of the interpreter (ADR-0046 and its #111 amendment): the release constants {@code b} and the
 * reserve, and the unit limit {@code U_f} a run derives from its declared stack. A stack unit is one Java frame of the
 * push form, lambda and proxy frames included. #113's calibration matrix measured the constants in {@code -Xint} and C1
 * only and proves them in the warm default JIT (ADR-0046 #113, "Rulings on the matrix"); the matrix's record and
 * its measurement are cited in {@code bench/RESULTS.md}.
 */
public final class StackUnits {
  /**
   * {@code b}, the bytes per unit: the largest 512 KiB to 1 MiB slope of the matrix's {@code -Xint} and C1 cells, 189 B
   * (C1, the wide body W, CONSERVATIVE), rounded up to 8 B: 192 (ADR-0046 #113, K2).
   */
  static final long BYTES_PER_UNIT = 192;

  /**
   * The reserve: the largest linked intercept {@code S - b_cell·(U* + D_f)}, 220 832 B ({@code -Xint}, a virtual thread,
   * the nested drivers, OPTIMISED), plus the largest cold-linkage term, 29 988 B ({@code -Xint}, the Collect-heavy
   * descent), rounded up to 4 KiB, plus 32 KiB for the natives: 280 KiB (ADR-0046 #113, K3). It is above the guard pages
   * plus 32 KiB.
   */
  static final long RESERVE = 280 * 1024;

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
