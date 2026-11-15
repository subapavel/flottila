package io.github.subapavel.jqj.query.compile;

import io.github.subapavel.jqj.query.run.RunState;

/**
 * The stack units of the interpreter (ADR-0046 and its #111 amendment): the release constants {@code b} and the
 * reserve, and the unit limit {@code U_f} a run derives from its declared stack. A stack unit is one Java frame of the
 * push form, lambda and proxy frames included. The constants are provisional until #113 calibrates them; their
 * measurement is in {@code bench/RESULTS.md}.
 */
public final class StackUnits {
  /**
   * {@code b}, the bytes per unit: the largest over {@code -Xint}, C1 only and the warm default JIT, measured on #111's
   * deep filters in fresh child JVMs, 94, rounded up (bench/RESULTS.md, #111).
   */
  static final long BYTES_PER_UNIT = 100;

  /** The reserve: 96 KiB of guard pages and 32 KiB for natives, a source constant (ADR-0046 #111 amendment). */
  static final long RESERVE = 128 * 1024;

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
