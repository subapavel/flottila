package dev.rail8.jq.query.internal.interpret;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rail8.jq.query.compile.CompileMode;
import dev.rail8.jq.query.compile.Compiler;
import dev.rail8.jq.query.compile.Executable;
import dev.rail8.jq.query.ir.AltBind;
import dev.rail8.jq.query.ir.Alternative;
import dev.rail8.jq.query.ir.Bind;
import dev.rail8.jq.query.ir.Call;
import dev.rail8.jq.query.ir.Comma;
import dev.rail8.jq.query.ir.Const;
import dev.rail8.jq.query.ir.Function;
import dev.rail8.jq.query.ir.Identity;
import dev.rail8.jq.query.ir.If;
import dev.rail8.jq.query.ir.Intrinsic;
import dev.rail8.jq.query.ir.Label;
import dev.rail8.jq.query.ir.Node;
import dev.rail8.jq.query.ir.Operation;
import dev.rail8.jq.query.ir.Pipe;
import dev.rail8.jq.query.ir.Program;
import dev.rail8.jq.query.ir.Source;
import dev.rail8.jq.query.ir.Try;
import dev.rail8.jq.query.ir.Var;
import dev.rail8.jq.query.run.FilterException;
import dev.rail8.jq.query.run.Fn0;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Termination;
import dev.rail8.jq.query.run.Truth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The run entry as the trampoline's starter (ADR-0046 M4 and its #109 amendment, GLOSSARY "Starter", "Hand-back"),
 * over a toy format of integers and strings (founding §14): with {@code stackBytes} 0 main and every body run on the
 * driver, and the run's sink is what runs downstream of each handed-back result. Its error goes back into the driver at
 * the heap form that produced the result, so a heap AltBind still catches it and a heap Try lets it pass; its stop or
 * break runs every heap form out, as push form does.
 *
 * <p>Each program is a recursion of {@value #LEVELS} levels, f on {@value #LEVELS} down to 0, whose base holds the
 * kind under test. The push form is the oracle: the same executable, declared {@value #DECLARED} bytes of stack (so
 * S2b.5's {@code U_f} keeps it on the Java stack) on a {@value #BIG}-byte thread. The run with {@code stackBytes} 0 is
 * on a {@value #SMALL}-byte thread, which the push form of {@value #LEVELS} levels exhausts (at least 4 frames of 32
 * bytes a level, TailDepthTest's margin): it must end as the oracle does, with the same results, end and steps. Each
 * test also pins the oracle's end, the push form's semantics (ADR-0008, ADR-0038, ADR-0039), so neither run can agree
 * with a wrong other. After each run the whole recursion-depth budget, {@value #LEVELS} + 1 Calls, must be free again,
 * and the label token the test took before the run (an outer Label, as a starter's caller has) marked only after a
 * break to it.
 *
 * <p>The seam this test assumes beyond #107: {@code RunState.begin(long steps, long deadlineNanos, long bytesBuilt,
 * long bytesWritten, long recursionDepth, long stackBytes)} (ADR-0030 #109 amendment).
 */
class TrampolineTest {
  private static final Source AT = new Source(0, 1);

  /** The recursion's levels below main's Call. */
  static final int LEVELS = 10_000;

  /** Main's Call and one per level: the live Calls at the base, the run's whole recursion-depth budget. */
  static final long ACTIVATIONS = LEVELS + 1;

  /** The thread of the run with {@code stackBytes} 0. */
  static final long SMALL = 256 * 1024;

  /** The thread of the push-form oracle: four times {@link #DECLARED} (ADR-0046 "Where the stack is tested"). */
  static final long BIG = 512L << 20;

  /** The push-form oracle's declared stack. */
  static final long DECLARED = 128L << 20;

  private static final long UNLIMITED = Long.MAX_VALUE;

  private static final Object YES = "yes";
  private static final Object NO = "no";
  private static final Truth TRUTH = YES::equals;

  private static final Object FIRST = "first";
  private static final Object SECOND = "second";
  private static final Object LATER = "later";
  private static final Object AFTER = "after";
  private static final Object CAUGHT = "caught";
  private static final Object X = "x";

  /** What runs downstream of the starter: the run's sink, given the run state and the outer token. */
  @FunctionalInterface
  interface Downstream {
    boolean take(RunState state, long outer, Object v);
  }

  /** The sink of the error tests: raises x at {@link #FIRST}, takes anything else. */
  private static final Downstream RAISES_AT_FIRST =
      (state, outer, v) -> {
        if (v == FIRST) {
          throw new FilterException(X);
        }
        return true;
      };

  private static Intrinsic call(String name, Fn0 fn) {
    return new Intrinsic(AT, new Operation(name, fn), List.of());
  }

  /** {@code if . == 0 then base else otherwise end} on an integer input. */
  private static Node ifZero(Node base, Node otherwise) {
    return new If(AT, call("zero", (state, in) -> (Integer) in == 0 ? YES : NO), base, otherwise, TRUTH);
  }

  /** {@code (. - 1 | f) | .}: one level deeper, and not in tail position. */
  private static Node deeper() {
    return new Pipe(AT, new Pipe(AT, call("dec", (state, in) -> (Integer) in - 1), new Call(AT, 0, List.of(), List.of())), new Identity(AT));
  }

  private static Const c(Object v) {
    return new Const(AT, v);
  }

  /** {@code LEVELS | f} with f's body {@code body}, in the conservative compile. */
  private static Executable recursion(Node body) {
    return recursion(body, main -> main);
  }

  /** As {@link #recursion(Node)}, with main {@code around(LEVELS | f)}. */
  private static Executable recursion(Node body, UnaryOperator<Node> around) {
    Node main = around.apply(new Pipe(AT, c(LEVELS), new Call(AT, 0, List.of(), List.of())));
    return Compiler.compile(new Program(main, List.of(new Function(AT, 0, 0, body))), 0, CompileMode.CONSERVATIVE);
  }

  /**
   * How {@code executable} ran into {@code downstream} on a new thread of {@code threadBytes}, declaring
   * {@code stackBytes}: what the sink saw, the answer or the error and its wrap depth or the termination, the steps,
   * the activations the budget then still takes, and whether the outer token is marked.
   */
  private static String ran(Executable executable, long stackBytes, long threadBytes, Downstream downstream) {
    AtomicReference<String> outcome = new AtomicReference<>();
    Thread thread =
        new Thread(
            null,
            () -> {
              try {
                RunState state = new RunState();
                state.begin(UNLIMITED, UNLIMITED, UNLIMITED, UNLIMITED, ACTIVATIONS, stackBytes);
                long outer = state.label();
                List<Object> seen = new ArrayList<>();
                String end;
                try {
                  boolean answer =
                      executable.run(
                          state,
                          0,
                          v -> {
                            seen.add(v);
                            return downstream.take(state, outer, v);
                          });
                  end = "answer " + answer;
                } catch (FilterException e) {
                  end = "error " + e.value() + " of depth " + e.depth();
                } catch (Termination t) {
                  end = "termination " + t.cause() + (t.budget() == null ? "" : " " + t.budget());
                }
                outcome.set("results " + seen + ", " + end + ", " + state.steps() + " steps, " + free(state) + " free activations, outer marked " + state.caught(outer));
              } catch (Throwable t) {
                outcome.set("threw " + t);
              }
            },
            "trampoline-" + threadBytes,
            threadBytes);
    thread.start();
    try {
      thread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    return outcome.get();
  }

  /** The activations {@code state}'s budget still takes, at most one past {@link #ACTIVATIONS}. */
  private static long free(RunState state) {
    long n = 0;
    try {
      while (n <= ACTIVATIONS) {
        state.enter();
        n++;
      }
    } catch (Termination _) {
      // The budget is full.
    }
    return n;
  }

  /**
   * The oracle's run, whose end without its steps is {@code expected}, then the run with {@code stackBytes} 0, which
   * must end as the oracle does.
   */
  private static void assertSameAsPushForm(Executable executable, Downstream downstream, String expected, String what) {
    String pushed = ran(executable, DECLARED, BIG, downstream);
    assertThat(pushed.replaceFirst(", [0-9]+ steps", "")).as("push form of %s", what).isEqualTo(expected);
    assertThat(ran(executable, 0, SMALL, downstream)).as("%s with stackBytes 0, against push form", what).isEqualTo(pushed);
  }

  /**
   * f's base is {@code . as $v ?// $w | (first as $a | $a) ?// (second as $b | $b)}, written as an AltBind of two
   * alternatives over its group slot. Its first result raises x downstream: the error re-enters at the AltBind, which
   * starts its second alternative (ADR-0038, ADR-0046 M4's first case).
   */
  @Test
  void aDownstreamErrorReentersAtTheProducingForm() {
    Node base =
        new AltBind(
            AT,
            new Identity(AT),
            1,
            List.of(new Alternative(new Bind(AT, c(FIRST), 2, new Identity(AT)), List.of(2)), new Alternative(new Bind(AT, c(SECOND), 3, new Identity(AT)), List.of(3))),
            List.of(4),
            new Var(AT, 4));
    assertSameAsPushForm(
        recursion(ifZero(base, deeper())),
        RAISES_AT_FIRST,
        "results [first, second], answer true, 10001 free activations, outer marked false",
        "an AltBind whose first result raises downstream");
  }

  /**
   * Every level is {@code try (…) catch "caught"} and the base {@code try (first, second) catch "caught"}: the error x
   * raised downstream of first passes all 10 002 Trys wrapped, so none runs its handler, and it leaves the run unwrapped
   * (ADR-0039, GLOSSARY "Wrap depth").
   */
  @Test
  void aDownstreamErrorPassesAHeapTryWrapped() {
    Node base = new Try(AT, new Comma(AT, c(FIRST), c(SECOND)), c(CAUGHT));
    assertSameAsPushForm(
        recursion(new Try(AT, ifZero(base, deeper()), c(CAUGHT))),
        RAISES_AT_FIRST,
        "results [first], error x of depth 0, 10001 free activations, outer marked false",
        "Trys around an error raised downstream");
  }

  /**
   * Every level is {@code label $l | try (if . == 0 then (first, second) else ((. - 1 | f) | .), later end) catch
   * "caught"}. The sink stops at first: second, every later and every handler stay unrun, no Label takes the stop for
   * its break, and every Call leaves its activation (ADR-0008, ADR-0046 #109: a stop runs every heap form out).
   */
  @Test
  void aStopFromTheStarterRunsTheFormsOut() {
    Node body = new Label(AT, 0, new Try(AT, ifZero(new Comma(AT, c(FIRST), c(SECOND)), new Comma(AT, deeper(), c(LATER))), c(CAUGHT)));
    assertSameAsPushForm(
        recursion(body),
        (state, outer, v) -> false,
        "results [first], answer false, 10001 free activations, outer marked false",
        "a stop at the first result");
  }

  /**
   * Every level is {@code label $l | (if . == 0 then (first, second) else ((. - 1 | f) | .) end), later}, and main
   * {@code (label $m | LEVELS | f), after}. The sink breaks to the token taken before the run, so the break passes every
   * heap Label, main's included: nothing after first runs, and the token stays marked for its own Label (ADR-0039).
   */
  @Test
  void aBreakFromTheStarterPassesAHeapLabel() {
    Node body = new Label(AT, 0, new Comma(AT, ifZero(new Comma(AT, c(FIRST), c(SECOND)), deeper()), c(LATER)));
    assertSameAsPushForm(
        recursion(body, main -> new Comma(AT, new Label(AT, 0, main), c(AFTER))),
        (state, outer, v) -> {
          state.breakTo(outer);
          return false;
        },
        "results [first], answer false, 10001 free activations, outer marked true",
        "a break to an outer Label at the first result");
  }
}
