package io.github.subapavel.jqj.query.compile;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.query.ir.Call;
import io.github.subapavel.jqj.query.ir.Function;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.ir.ParamCall;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Program;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Try;
import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.RunState;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The switch at {@code U_f} seen from inside a run (ADR-0046 #111 amendment): an intrinsic reads the units the live
 * push-form calls hold. A call adds its units, then compares, so at {@code units == U_f} it is a starter and holds
 * {@code T} more; at {@code U_f} 0 or below the run entry is the starter and no call holds a unit. Every run declares
 * the {@code stackBytes} of the wanted {@code U_f} and runs on a thread of four times that (ADR-0046 "Where the stack
 * is tested").
 */
class UnitLimitTest {
  private static final Source AT = new Source(0, 1);

  /** An intrinsic whose one result is the units the run holds when it runs. */
  private static Node units() {
    return new Intrinsic(AT, new Operation("units", (Fn0) (state, in) -> state.units()), List.of());
  }

  /** An intrinsic that raises. */
  private static Node raise() {
    return new Intrinsic(AT, new Operation("raise", (Fn0) (state, in) -> {
      throw new FilterException("e");
    }), List.of());
  }

  /** What {@code e} gave at {@code U_f} {@code limit}, and the units after the run, on a thread of 4x its stack. */
  private static List<Object> ran(Executable e, long limit) {
    long stackBytes = StackUnits.reserve() + StackUnits.bytesPerUnit() * (limit + StackUnits.callFreeDepth(e));
    assertThat(StackUnits.unitLimit(e, stackBytes)).as("U_f of %d stack bytes", stackBytes).isEqualTo(limit);
    AtomicReference<List<Object>> outcome = new AtomicReference<>();
    Thread thread =
        new Thread(
            null,
            () -> {
              RunState state = new RunState();
              state.begin(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, stackBytes);
              List<Object> seen = new ArrayList<>();
              try {
                e.run(state, null, seen::add);
              } catch (FilterException x) {
                seen.add("error " + x.value());
              }
              seen.add("after " + state.units());
              outcome.set(seen);
            },
            "unit-limit",
            Math.max(4 * stackBytes, 256 * 1024));
    thread.start();
    try {
      thread.join();
    } catch (InterruptedException x) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(x);
    }
    return outcome.get();
  }

  /** main is a Call of f, whose body reads the units; f's body is {@code body}. */
  private static Executable callOf(Node body) {
    return Compiler.compile(new Program(new Call(AT, 0, List.of(), List.of()), List.of(new Function(AT, 0, 0, body))), 0, CompileMode.CONSERVATIVE);
  }

  @Test
  void aCallAtTheLimitStartsTheTrampolineAndOneUnderStaysOnTheStack() {
    Program program = new Program(new Call(AT, 0, List.of(), List.of()), List.of(new Function(AT, 0, 0, units())));
    Executable e = Compiler.compile(program, 0, CompileMode.CONSERVATIVE);
    long w = e.nesting().units(program.main());
    long t = StackUnits.starterUnits(e);
    // T: the most frames the driver holds above a starter's own frames (StackUnits.STARTER).
    assertThat(t).as("T, the starter units").isEqualTo(5);
    // The run entry's two frames and the Call's own under it, T and no rise over it, and f's intrinsic, one frame.
    assertThat(e.nesting().emitDepth(0)).as("f's leaf-emit depth").isEqualTo(1);
    assertThat(w).as("main's call of f").isEqualTo(2 + 1 + 5 + 1);
    assertThat(ran(e, w + 1)).as("U_f w + 1: the call stays in push form").containsExactly(w, "after 0");
    assertThat(ran(e, w)).as("U_f w: the call is a starter").containsExactly(w + t, "after 0");
    assertThat(ran(e, 1)).as("U_f 1: the call is a starter").containsExactly(w + t, "after 0");
    assertThat(ran(e, 0)).as("U_f 0: the run entry is the starter").containsExactly(0L, "after 0");
    assertThat(ran(e, -1)).as("U_f -1: the run entry is the starter").containsExactly(0L, "after 0");
  }

  /**
   * A ParamCall holds its site's descent and rise and the leaf-emit depth its closure carries. In f's body
   * {@code g | units}, the ParamCall's frames sit over the Pipe's, one; its own two (the op and the closure's) and
   * {@code T} follow, and its rise is the sink's proxy and body and the units intrinsic, three. The closure
   * {@code try units catch units} emits from the Try's sink, its proxy and body and downstream's frame over the Try and
   * the intrinsic: five. f's body is the deepest: the Pipe, the ParamCall and T, the sink and the intrinsic.
   */
  @Test
  void aParamCallHoldsItsRiseAndItsClosuresEmitDepth() {
    Node closure = new Try(AT, units(), units());
    Call call = new Call(AT, 0, List.of(closure), List.of());
    ParamCall site = new ParamCall(AT, 0);
    Executable e = Compiler.compile(new Program(call, List.of(new Function(AT, 1, 0, new Pipe(AT, site, units())))), 0, CompileMode.CONSERVATIVE);
    Nesting n = e.nesting();
    long t = n.starterUnits();
    assertThat(n.units(site)).as("the ParamCall's descent and rise").isEqualTo(1 + 2 + t + 3);
    assertThat(n.emitDepth(closure)).as("the closure's leaf-emit depth under a Try").isEqualTo(5);
    assertThat(n.callFreeDepth()).as("D_f, f's body").isEqualTo(1 + 2 + t + 2 + 1);
    long held = n.units(call) + n.units(site) + n.emitDepth(closure);
    assertThat(ran(e, held + 1)).as("U_f one over both calls' units").containsExactly(held, "after 0");
    assertThat(ran(e, held)).as("U_f at both calls' units: the ParamCall is a starter").containsExactly(held + t, "after 0");
  }

  /**
   * Starters in a rise (ADR-0046 #111 invariant): at {@code U_f} 1 each of ten Calls in {@code f | f | ... | f | frames}
   * is a starter, and each stays live, its trampoline's frame under the next call, while its result runs the rest. A
   * call counted as 1 + T keeps those frames within {@code U_f - 1 + D_f} (ADR-0046, the invariant: no {@code T} term).
   */
  @Test
  void startersInARiseStayUnderTheLimit() {
    List<Integer> seen = new ArrayList<>();
    Node rest = new Intrinsic(AT, new Operation("frames", (Fn0) (state, in) -> {
      seen.add(frames());
      return in;
    }), List.of());
    for (int k = 0; k < 10; k++) {
      rest = new Pipe(AT, new Call(AT, 0, List.of(), List.of()), rest);
    }
    Executable e = Compiler.compile(new Program(rest, List.of(new Function(AT, 0, 0, new Identity(AT)))), 0, CompileMode.CONSERVATIVE);
    ran(e, 1);
    long bound = 1 - 1 + StackUnits.callFreeDepth(e);
    assertThat(seen).as("the frames at the end of the chain against %d", bound).hasSize(1).allSatisfy(f -> assertThat((long) f).isLessThanOrEqualTo(bound));
  }

  /** The interpreter's frames on this thread, lambda and proxy frames included, this test's own left out. */
  private static int frames() {
    return StackWalker.getInstance(Set.of(StackWalker.Option.SHOW_HIDDEN_FRAMES))
        .walk(s -> (int) s.map(StackWalker.StackFrame::getClassName).filter(c -> c.startsWith("io.github.subapavel.jqj.query.") && !c.startsWith(UnitLimitTest.class.getName())).count());
  }

  /** {@code U_f = floorDiv(stackBytes - reserve, b) - D_f}: a declared stack one byte under the reserve rounds down. */
  @Test
  void aStackUnderTheReserveRoundsDown() {
    Executable e = callOf(units());
    assertThat(StackUnits.unitLimit(e, StackUnits.reserve() - 1)).as("U_f one byte under the reserve").isEqualTo(-1 - StackUnits.callFreeDepth(e));
    // -131 072 / 96 is -1 365.33: floorDiv gives -1 366, plain division -1 365.
    assertThat(StackUnits.unitLimit(e, 0)).as("U_f of 0 bytes").isEqualTo(-1366 - StackUnits.callFreeDepth(e));
  }

  /** Units are given back however a call ends: an error leaving a pushed call and a starter both leave none. */
  @Test
  void anErrorGivesTheUnitsBack() {
    Executable e = callOf(raise());
    assertThat(ran(e, 1_000)).as("an error in push form").containsExactly("error e", "after 0");
    assertThat(ran(e, 1)).as("an error out of a starter").containsExactly("error e", "after 0");
  }
}
