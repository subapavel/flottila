package io.github.subapavel.jqj.query.compile;

import io.github.subapavel.jqj.query.run.RunState;

/**
 * SEAM STUB of #111 (test-author): the stack units of a compile and a run (ADR-0046 #111 amendment), only so that
 * UnitWeightsPropertyTest compiles. Every method throws; the implementer owns this class, its names and its bodies.
 */
final class StackUnits {
  private StackUnits() {}

  private static UnsupportedOperationException stub(String what) {
    return new UnsupportedOperationException("#111 seam stub, not built: StackUnits." + what);
  }

  /** {@code D_f}: the most frames main or any body nests, each call counted as a starter nests it. */
  static long callFreeDepth(Executable executable) {
    throw stub("callFreeDepth");
  }

  /** {@code T}: the units a starter adds for its own frames. */
  static long starterUnits(Executable executable) {
    throw stub("starterUnits");
  }

  /** {@code b}: the bytes per unit, a release constant. */
  static long bytesPerUnit() {
    throw stub("bytesPerUnit");
  }

  /** The reserve in bytes, a release constant. */
  static long reserve() {
    throw stub("reserve");
  }

  /** {@code U_f = floorDiv(stackBytes - reserve, b) - D_f} of a run of {@code executable} declaring {@code stackBytes}. */
  static long unitLimit(Executable executable, long stackBytes) {
    throw stub("unitLimit");
  }

  /** The units {@code state}'s run holds now. */
  static long units(RunState state) {
    throw stub("units");
  }
}
