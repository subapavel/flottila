package dev.rail8.jq.query.compile;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rail8.jq.query.ir.Comma;
import dev.rail8.jq.query.ir.Const;
import dev.rail8.jq.query.ir.Intrinsic;
import dev.rail8.jq.query.ir.Node;
import dev.rail8.jq.query.ir.Operation;
import dev.rail8.jq.query.ir.Pipe;
import dev.rail8.jq.query.ir.Source;
import dev.rail8.jq.query.run.Many;
import dev.rail8.jq.query.run.RunState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The Many args contract (ADR-0041 as amended by F-43, ADR-0044): the call site gives each loop a new args array, of
 * its arguments and then its loop slots, and {@code first} may replace an element of it; a Const is never written. So
 * what one loop's {@code first} writes into its array, over an argument or into a loop slot, never reaches the next
 * loop, which starts from the argument's value and an empty loop slot. Both loops are of one call site, run once per
 * input, since the array is per loop, not per call site.
 */
class ManyArgsArrayTest {
  private static final Source AT = new Source(0, 1);

  /**
   * A Many of one result whose {@code first} notes the element at {@code place} of its args array in {@code seen}, then
   * replaces it.
   */
  private static Many replacing(List<Object> seen, int loopSlots, int place) {
    return new Many() {
      @Override
      public int loopSlots() {
        return loopSlots;
      }

      @Override
      public long first(RunState state, Object in, Object[] args) {
        seen.add(args[place]);
        args[place] = "replaced";
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
    return new Intrinsic(AT, new Operation("replacing", many), args);
  }

  /**
   * Two loops of one call site of {@code many} with Const arguments of {@code constants}, one per input of {@code (1, 2) |
   * call}, one after the other in a run: what one loop wrote is still in the op if the op kept it.
   */
  private static void twoLoops(Many many, CompileMode mode, Object... constants) {
    List<Object> emitted = new ArrayList<>();
    Compiler.compile(new Pipe(AT, new Comma(AT, new Const(AT, 1), new Const(AT, 2)), call(many, constants)), mode)
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
  void aReplacedArgumentNeverReachesTheNextLoop(CompileMode mode) {
    List<Object> seen = new ArrayList<>();
    twoLoops(replacing(seen, 0, 0), mode, 7);
    assertThat(seen).as("the argument each loop's first reads, after the first loop replaced its own").containsExactly(7, 7);
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void aLoopSlotStartsEmptyInEachLoop(CompileMode mode) {
    List<Object> seen = new ArrayList<>();
    twoLoops(replacing(seen, 1, 1), mode, 7);
    assertThat(seen).as("the loop slot after the argument each loop's first reads, after the first loop wrote its own").containsExactly(null, null);
  }
}
