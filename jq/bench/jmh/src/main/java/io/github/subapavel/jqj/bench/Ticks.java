package io.github.subapavel.jqj.bench;

import io.github.subapavel.jqj.JqEngine;
import io.github.subapavel.jqj.JqFilter;
import io.github.subapavel.jqj.JqTermination;
import io.github.subapavel.jqj.Limits;
import io.github.subapavel.jqj.OutputOptions;
import io.github.subapavel.jqj.bench.Inputs.Size;
import java.lang.foreign.MemorySegment;
import java.util.List;

/**
 * The tick-loop diagnostic of ADR-0019 for the G2, G3 and G4 scenarios: the ticks one operation of {@link Gates}
 * charges (reader, run and writer, ADR-0030), found as the smallest step budget under which the operation completes.
 * The step budget is exact (refuel comes at the budget when that is nearer than 4096 ticks), so a bisection over
 * {@link Limits#steps()} gives the count through the public API alone. Divided into the scenario's JMH time per
 * operation it gives the mean time per tick and the steps per second.
 *
 * <p>{@code java -cp benchmarks.jar io.github.subapavel.jqj.bench.Ticks} prints one tab-separated line per bench id:
 * {@code id}, {@code ticks}.
 */
public final class Ticks {

  /** A scenario of {@link Gates}: its bench id, filter and document, run in compact output. */
  record Scenario(String id, String filter, byte[] document) {}

  private Ticks() {}

  /** The G2, G3 and G4 scenarios of {@link Gates}, in its order. */
  static List<Scenario> scenarios() {
    return List.of(
        new Scenario("G2.1k", Gates.G2, Inputs.orders(Size.K1)),
        new Scenario("G2.100k", Gates.G2, Inputs.orders(Size.K100)),
        new Scenario("G2.10m", Gates.G2, Inputs.orders(Size.M10)),
        new Scenario("G3.100k", Gates.G3, Inputs.recs(Size.K100)),
        new Scenario("G3.10m", Gates.G3, Inputs.recs(Size.M10)),
        new Scenario("G4.1e4", Gates.G4, Inputs.ints(Inputs.INTS_1E4)));
  }

  /**
   * The ticks of one operation of {@code s}: the smallest step budget, at most {@code base}'s, under which it
   * completes, every other limit {@code base}'s.
   *
   * @throws IllegalStateException if it does not complete under {@code base}'s step budget
   */
  static long ticks(Scenario s, Limits base) {
    long most = base.steps();
    if (!completes(s, most, base)) {
      throw new IllegalStateException(s.id() + " does not complete within " + most + " steps");
    }
    long fails = -1;
    while (most - fails > 1) {
      long mid = fails + (most - fails) / 2;
      if (completes(s, mid, base)) {
        most = mid;
      } else {
        fails = mid;
      }
    }
    return most;
  }

  /**
   * Whether one operation of {@code s} completes under a step budget of {@code steps}, every other limit
   * {@code base}'s; a fresh engine and Workspace each time, so no ended run's state carries over.
   *
   * @throws JqTermination if the operation ends on another budget than the steps
   */
  static boolean completes(Scenario s, long steps, Limits base) {
    Limits limits =
        new Limits(
            steps,
            base.deadline(),
            base.nestingDepth(),
            base.recursionDepth(),
            base.bytesBuilt(),
            base.bytesWritten(),
            base.regexMatchTime(),
            base.stackBytes());
    JqEngine engine = JqEngine.builder().limits(limits).build();
    JqFilter filter = engine.compile(s.filter());
    try {
      Gates.once(filter, engine.newWorkspace(), MemorySegment.ofArray(s.document()), OutputOptions.compact(), new Gates.Discard());
      return true;
    } catch (JqTermination e) {
      if (e.budget() != JqTermination.Budget.STEPS) {
        throw e;
      }
      return false;
    }
  }

  /** Prints {@code id}, then a tab, then the ticks of one operation under {@code Limits.embedded()}, per scenario. */
  public static void main(String[] args) {
    for (Scenario s : scenarios()) {
      System.out.println(s.id() + "\t" + ticks(s, Limits.embedded()));
    }
  }
}
