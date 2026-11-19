package dev.rail8.jq.query.compile;

import static dev.rail8.jq.query.compile.ListPaths.AT;
import static org.assertj.core.api.Assertions.assertThat;

import dev.rail8.jq.query.ir.Bind;
import dev.rail8.jq.query.ir.Call;
import dev.rail8.jq.query.ir.Const;
import dev.rail8.jq.query.ir.Function;
import dev.rail8.jq.query.ir.Identity;
import dev.rail8.jq.query.ir.If;
import dev.rail8.jq.query.ir.Intrinsic;
import dev.rail8.jq.query.ir.Node;
import dev.rail8.jq.query.ir.Operation;
import dev.rail8.jq.query.ir.PathOf;
import dev.rail8.jq.query.ir.Pipe;
import dev.rail8.jq.query.ir.Program;
import dev.rail8.jq.query.ir.Update;
import dev.rail8.jq.query.ir.Var;
import dev.rail8.jq.query.run.FilterException;
import dev.rail8.jq.query.run.Fn1;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Termination;
import dev.rail8.jq.query.run.Truth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Path variants past the Java stack: a recursion of {@value #LEVELS} levels whose base is {@code .[0]}, inside a PathOf
 * and as an Update's lhs, over the toy format of {@link ListPaths}. Its function f is
 * {@code def f($n): if $n == 0 then .[0] else (($n - 1) as $m | f($m)) | . end}, whose call is in no tail position, or
 * its tail form {@code def f($n): if $n == 0 then .[0] else ($n - 1) as $m | f($m) end}, a jump in the optimised
 * compile. The push form is the oracle: declared {@value #DECLARED} bytes of stack on a {@value #BIG}-byte thread.
 * Each program must end as it does on the trampoline (a declared stack of 0) on a {@value #SMALL}-byte thread, which
 * the push form of the recursion exhausts, and mixed, declared {@value #MIXED} bytes on a {@value #MIXED_THREAD}-byte
 * thread, where the calls past the unit limit start the trampoline: the same results, end and steps, and the whole
 * recursion-depth budget free again after the run.
 */
class PathRecursionTest {
  /** The recursion's levels below main's Call. */
  static final int LEVELS = 10_000;

  /** Main's Call and one per level: the run's whole recursion-depth budget. */
  static final long ACTIVATIONS = LEVELS + 1;

  /** The thread of the run on the trampoline. */
  static final long SMALL = 256 * 1024;

  /** The thread of the push-form oracle. */
  static final long BIG = 512L << 20;

  /** The push-form oracle's declared stack. */
  static final long DECLARED = 128L << 20;

  /** The declared stack of the mixed run. */
  static final long MIXED = 512 * 1024;

  /** The thread of the mixed run: room for the push form the declared stack lets run. */
  static final long MIXED_THREAD = 2L << 20;

  private static final long UNLIMITED = Long.MAX_VALUE;

  private static final Truth TRUTH = Boolean.TRUE::equals;

  private static final Object IN = List.of(5L, 6L);

  private final ListPaths paths = new ListPaths();

  private static Node zero(Node arg) {
    return new Intrinsic(AT, new Operation("zero", (Fn1) (state, in, a) -> (Long) a == 0), List.of(arg));
  }

  private static Node dec(Node arg) {
    return new Intrinsic(AT, new Operation("dec", (Fn1) (state, in, a) -> (Long) a - 1), List.of(arg));
  }

  /** f's body around its recursive call {@code ($n - 1) as $m | f($m)}, which {@code around} places. */
  private static Node body(UnaryOperator<Node> around) {
    Node recurse = new Bind(AT, dec(new Var(AT, 0)), 1, new Call(AT, 0, List.of(), List.of(new Var(AT, 1))));
    return new If(AT, zero(new Var(AT, 0)), ListPaths.index(new Const(AT, 0L)), around.apply(recurse), TRUTH);
  }

  /** f's body with its call in no tail position. */
  private static Node deep() {
    return body(recurse -> new Pipe(AT, recurse, new Identity(AT)));
  }

  /** f's body with its call in tail position. */
  private static Node tail() {
    return body(recurse -> recurse);
  }

  /** {@code main} around f({@value #LEVELS}), with f's body {@code f}. */
  private static Program program(UnaryOperator<Node> main, Node f) {
    return new Program(main.apply(new Call(AT, 0, List.of(), List.of(new Const(AT, (long) LEVELS)))), List.of(new Function(AT, 0, 1, f)));
  }

  private Program pathOf(Node f) {
    return program(call -> new PathOf(AT, call, paths), f);
  }

  private Program update(Node f) {
    return program(call -> new Update(AT, Update.Kind.MODIFY, call, new Const(AT, 9L), paths), f);
  }

  /**
   * How {@code program} ran on {@link #IN} on a new thread of {@code threadBytes}, declaring {@code stackBytes}: its
   * results, its answer, error or termination, its steps and the activations the budget then still takes.
   */
  private static String ran(Program program, CompileMode mode, long stackBytes, long threadBytes) {
    Executable executable = Compiler.compile(program, 0, mode);
    AtomicReference<String> outcome = new AtomicReference<>();
    Thread thread =
        new Thread(
            null,
            () -> {
              try {
                RunState state = new RunState();
                state.begin(UNLIMITED, UNLIMITED, UNLIMITED, UNLIMITED, ACTIVATIONS, stackBytes);
                List<Object> seen = new ArrayList<>();
                String end;
                try {
                  end =
                      "answer "
                          + executable.run(
                              state,
                              IN,
                              v -> {
                                seen.add(v);
                                return true;
                              });
                } catch (FilterException e) {
                  end = "error " + e.value();
                } catch (Termination t) {
                  end = "termination " + t.cause();
                }
                outcome.set("results " + seen + ", " + end + ", " + state.steps() + " steps, " + free(state) + " free activations");
              } catch (Throwable t) {
                outcome.set("threw " + t);
              }
            },
            "path-recursion-" + threadBytes,
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

  /** The oracle's end without its steps is {@code expected}; the trampoline's and the mixed run's are the oracle's. */
  private static void assertSameAsPushForm(Program program, String expected, String what) {
    for (CompileMode mode : CompileMode.values()) {
      String pushed = ran(program, mode, DECLARED, BIG);
      assertThat(pushed.replaceFirst(", [0-9]+ steps", "")).as("push form of %s, %s", what, mode).isEqualTo(expected);
      assertThat(ran(program, mode, 0, SMALL)).as("%s on the trampoline, %s, against push form", what, mode).isEqualTo(pushed);
      assertThat(ran(program, mode, MIXED, MIXED_THREAD)).as("%s declaring %d bytes, %s, against push form", what, MIXED, mode).isEqualTo(pushed);
    }
  }

  @Test
  void aPathOfThroughTheRecursionGivesItsBasesPath() {
    assertSameAsPushForm(pathOf(deep()), "results [[0]], answer true, 10001 free activations", "path(f(10000))");
    assertSameAsPushForm(pathOf(tail()), "results [[0]], answer true, 10001 free activations", "path(f(10000)) by tail calls");
  }

  @Test
  void anUpdateThroughTheRecursionEditsItsBasesPath() {
    assertSameAsPushForm(update(deep()), "results [[9, 6]], answer true, 10001 free activations", "f(10000) |= 9");
    assertSameAsPushForm(update(tail()), "results [[9, 6]], answer true, 10001 free activations", "f(10000) |= 9 by tail calls");
  }

  @Test
  void thePushFormOfTheRecursionExhaustsTheSmallThread() {
    // Witness that the trampoline runs are not vacuous: the push form alone runs out of the small thread's stack.
    for (Program program : List.of(pathOf(deep()), update(deep()))) {
      assertThat(ran(program, CompileMode.CONSERVATIVE, DECLARED, SMALL)).as("push form on the small thread").contains("termination STACK");
    }
  }
}
