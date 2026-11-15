package io.github.subapavel.jqj.query.compile;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.query.ir.Call;
import io.github.subapavel.jqj.query.ir.Function;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.ir.ParamCall;
import io.github.subapavel.jqj.query.ir.Program;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Try;
import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.RunState;
import java.util.ArrayList;
import java.util.List;
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
    // The run entry's two frames and the Call's own under it, T and no rise over it, and f's intrinsic, one frame.
    assertThat(e.nesting().emitDepth(0)).as("f's leaf-emit depth").isEqualTo(1);
    assertThat(w).as("main's call of f").isEqualTo(Nesting.ENTRY + 1 + t + 1);
    assertThat(ran(e, w + 1)).as("U_f w + 1: the call stays in push form").containsExactly(w, "after 0");
    assertThat(ran(e, w)).as("U_f w: the call is a starter").containsExactly(w + t, "after 0");
    assertThat(ran(e, 1)).as("U_f 1: the call is a starter").containsExactly(w + t, "after 0");
    assertThat(ran(e, 0)).as("U_f 0: the run entry is the starter").containsExactly(0L, "after 0");
    assertThat(ran(e, -1)).as("U_f -1: the run entry is the starter").containsExactly(0L, "after 0");
  }

  /** A ParamCall holds its site's units and the leaf-emit depth its closure carries. */
  @Test
  void aParamCallHoldsItsClosuresEmitDepth() {
    // main: f(try units), f's body: its closure parameter.
    Node closure = new Try(AT, units(), units());
    Call call = new Call(AT, 0, List.of(closure), List.of());
    ParamCall site = new ParamCall(AT, 0);
    Executable e = Compiler.compile(new Program(call, List.of(new Function(AT, 1, 0, site))), 0, CompileMode.CONSERVATIVE);
    Nesting n = e.nesting();
    long held = n.units(call) + n.units(site) + n.emitDepth(closure);
    assertThat(n.emitDepth(closure)).as("the closure's leaf-emit depth under a Try").isGreaterThan(1);
    assertThat(ran(e, held + 1)).as("U_f one over both calls' units").containsExactly(held, "after 0");
    assertThat(ran(e, held)).as("U_f at both calls' units: the ParamCall is a starter").containsExactly(held + n.starterUnits(), "after 0");
  }

  /** Units are given back however a call ends: an error leaving a pushed call and a starter both leave none. */
  @Test
  void anErrorGivesTheUnitsBack() {
    Executable e = callOf(raise());
    assertThat(ran(e, 1_000)).as("an error in push form").containsExactly("error e", "after 0");
    assertThat(ran(e, 1)).as("an error out of a starter").containsExactly("error e", "after 0");
  }
}
