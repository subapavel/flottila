package io.github.subapavel.jqj.query.compile;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.run.Many;
import io.github.subapavel.jqj.query.run.RunState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The Many args contract (ADR-0041 as amended by F-43, ADR-0044): the call site gives each loop a new args array, of
 * its arguments and then its loop slots, except a call with no argument and no loop slot, whose loops share one empty
 * array that has nothing to replace.
 */
class ManyArgsArrayTest {
  private static final Source AT = new Source(0, 1);

  /** A Many of one result that notes the args array of each loop's first call in {@code seen}. */
  private static Many noting(List<Object[]> seen, int loopSlots) {
    return new Many() {
      @Override
      public int loopSlots() {
        return loopSlots;
      }

      @Override
      public long first(RunState state, Object in, Object[] args) {
        seen.add(args);
        return 0;
      }

      @Override
      public long next(RunState state, Object in, Object[] args, long cursor) {
        return Many.END;
      }

      @Override
      public Object value(RunState state, Object in, Object[] args, long cursor) {
        return "v";
      }
    };
  }

  /** A call of {@code many} with Const arguments of {@code constants}. */
  private static Intrinsic call(Many many, Object... constants) {
    List<Node> args = new ArrayList<>();
    for (Object constant : constants) {
      args.add(new Const(AT, constant));
    }
    return new Intrinsic(AT, new Operation("noting", many), args);
  }

  /** Two loops of {@code many}, two calls of it with Const arguments of {@code constants}, one after the other in a run. */
  private static void twoLoops(Many many, CompileMode mode, Object... constants) {
    List<Object> emitted = new ArrayList<>();
    Compiler.compile(new Comma(AT, call(many, constants), call(many, constants)), mode)
        .run(
            new RunState(),
            new Object(),
            v -> {
              emitted.add(v);
              return true;
            });
    assertThat(emitted).as("results of the two loops").containsExactly("v", "v");
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void loopsWithNoArgumentAndNoLoopSlotShareOneEmptyArray(CompileMode mode) {
    List<Object[]> seen = new ArrayList<>();
    twoLoops(noting(seen, 0), mode);
    assertThat(seen).as("args arrays of the two loops").hasSize(2);
    assertThat(seen.get(0)).as("args of the first loop").isEmpty();
    assertThat(seen.get(1)).as("args of the second loop, the first loop's array").isSameAs(seen.get(0));
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void eachLoopWithAnArgumentOrALoopSlotGetsANewArray(CompileMode mode) {
    // Witness: an argument, or a loop slot alone, gives each loop its own array.
    List<Object[]> withArgument = new ArrayList<>();
    twoLoops(noting(withArgument, 0), mode, 7);
    assertThat(withArgument.get(0)).as("args of the first loop with one argument").containsExactly(7);
    assertThat(withArgument.get(1)).as("args of the second loop with one argument").containsExactly(7).isNotSameAs(withArgument.get(0));

    List<Object[]> withSlot = new ArrayList<>();
    twoLoops(noting(withSlot, 1), mode);
    assertThat(withSlot.get(0)).as("args of the first loop with one loop slot").hasSize(1);
    assertThat(withSlot.get(1)).as("args of the second loop with one loop slot").hasSize(1).isNotSameAs(withSlot.get(0));
  }
}
