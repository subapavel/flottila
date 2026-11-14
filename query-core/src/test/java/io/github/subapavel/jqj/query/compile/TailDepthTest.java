package io.github.subapavel.jqj.query.compile;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Binds;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.If;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Label;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.run.CallForm;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.Fn1;
import io.github.subapavel.jqj.query.run.Fn2;
import io.github.subapavel.jqj.query.run.FnN;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Truth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Both sides of ADR-0043's stack claim, in this JVM, so a mutant of the tail-emitter classification fails here (#92
 * review, CODING_STANDARDS Tests): a tail value or a tail condition is taken before the rest runs, so a run of
 * {@value #PARTS} of them fits a {@value #STACK}-byte thread (ADR-0017's default stack); the same run in push form
 * nests once per part and overflows it. Over a toy format of integers and strings (founding §14).
 *
 * <p>The margin: in push form each part keeps at least one frame on the stack, and a JVM frame of a call is at least 32
 * bytes, so {@value #PARTS} parts need 640 000 bytes or more, past the thread's 524 288. The tail form keeps a few
 * frames whatever the number of parts. Each Binds chain has parts of one tail kind only, so a classification that loses
 * any one kind nests every part of its chain. Each run first runs a copy of {@value #WARM} parts on the test thread,
 * which links every call site before the small thread runs.
 */
class TailDepthTest {
  private static final Source AT = new Source(0, 1);

  /** The parts of each run on the small thread. */
  static final int PARTS = 20_000;

  /** The parts of the copy on the test thread. */
  static final int WARM = 100;

  /** ADR-0017's default stack for a run. */
  static final long STACK = 512 * 1024;

  private static final Object YES = "yes";

  private static final Object NO = "no";

  private static final Truth TRUTH = v -> !NO.equals(v);

  /**
   * The kinds of tail emitter ADR-0043 names, each the part kind of one chain, with what the chain's body gives for
   * {@value #WARM} and for {@value #PARTS} parts: the slots of part 0, the middle part and the last one. Part k gives
   * k from slot k - 1 (part 0 gives 0), but an Identity part gives the input and a Var part copies slot k - 1.
   */
  enum Kind {
    IDENTITY("[[in, in, in]]", "[[in, in, in]]"),
    CONST("[[0, 50, 99]]", "[[0, 10000, 19999]]"),
    VAR("[[0, 0, 0]]", "[[0, 0, 0]]"),
    INTRINSIC("[[0, 50, 99]]", "[[0, 10000, 19999]]"),
    PIPE("[[0, 50, 99]]", "[[0, 10000, 19999]]"),
    IF("[[0, 50, 99]]", "[[0, 10000, 19999]]"),
    BIND("[[0, 50, 99]]", "[[0, 10000, 19999]]"),
    BINDS("[[0, 50, 99]]", "[[0, 10000, 19999]]");

    final String warm;
    final String full;

    Kind(String warm, String full) {
      this.warm = warm;
      this.full = full;
    }
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Kind.class)
  void aBindsOfTwentyThousandTailValuesOfOneKindFitsA512KiBThread(Kind kind) throws InterruptedException {
    for (CompileMode mode : CompileMode.values()) {
      assertThat(ran(chain(WARM, k -> part(kind, k)), mode)).as("%s, %s: %d parts on the test thread", kind, mode, WARM).isEqualTo(kind.warm + " in 0 steps");
      assertThat(onSmallThread(() -> ran(chain(PARTS, k -> part(kind, k)), mode))).as("%s, %s: %d parts on a 512 KiB thread", kind, mode, PARTS).isEqualTo(kind.full + " in 0 steps");
    }
  }

  /**
   * The control: part k is {@code (slot k - 1 + 1, nothing)}, one result as a Fn1 gives, but a Comma, no tail emitter,
   * so the rest runs inside its emit. Each Comma ticks once, entering its right side after the rest returned.
   */
  @Test
  void theSameBindsOfOneResultCommasOverflowsA512KiBThread() throws InterruptedException {
    for (CompileMode mode : CompileMode.values()) {
      assertThat(ran(chain(WARM, TailDepthTest::comma), mode)).as("%s: %d Comma parts on the test thread", mode, WARM).isEqualTo("[[0, 50, 99]] in 99 steps");
      assertThat(onSmallThread(() -> ran(chain(PARTS, TailDepthTest::comma), mode)))
          .as("%s: %d Comma parts on a 512 KiB thread", mode, PARTS)
          .isEqualTo("threw java.lang.StackOverflowError");
    }
  }

  /** The kinds of term of a balanced run: a Const, a Var of slot 0 or 1 (yes or no), or an Fn0. */
  enum Term {
    CONST,
    VAR,
    INTRINSIC
  }

  /**
   * {@code a and b} is {@code if a then (if b then true else false) else false} and {@code a or b} is {@code if a then
   * true else (if b then true else false)} (ADR-0037), over balanced halves of terms of one kind: every term true gives
   * yes for {@code and}, every term false gives no for {@code or}; none of it ticks.
   */
  @ParameterizedTest(name = "{0}")
  @EnumSource(Term.class)
  void aBalancedRunOfTwentyThousandTailTermsOfOneKindFitsA512KiBThread(Term term) throws InterruptedException {
    for (CompileMode mode : CompileMode.values()) {
      assertThat(ran(and(0, WARM, term, null), mode)).as("%s, %s: %d and-terms on the test thread", term, mode, WARM).isEqualTo("[yes] in 0 steps");
      assertThat(onSmallThread(() -> ran(and(0, PARTS, term, null), mode))).as("%s, %s: %d and-terms on a 512 KiB thread", term, mode, PARTS).isEqualTo("[yes] in 0 steps");
      assertThat(ran(or(0, WARM, term), mode)).as("%s, %s: %d or-terms on the test thread", term, mode, WARM).isEqualTo("[no] in 0 steps");
      assertThat(onSmallThread(() -> ran(or(0, PARTS, term), mode))).as("%s, %s: %d or-terms on a 512 KiB thread", term, mode, PARTS).isEqualTo("[no] in 0 steps");
    }
  }

  /**
   * The control: the same and-run with each If's condition inside a Label no Break names. A Label is no tail emitter
   * and ticks nothing (ADR-0039), so each branch runs inside its condition's emit.
   */
  @Test
  void theSameBalancedRunWithItsConditionsInLabelsOverflowsA512KiBThread() throws InterruptedException {
    for (CompileMode mode : CompileMode.values()) {
      assertThat(ran(and(0, WARM, Term.CONST, new int[] {2}), mode)).as("%s: %d and-terms in Labels on the test thread", mode, WARM).isEqualTo("[yes] in 0 steps");
      assertThat(onSmallThread(() -> ran(and(0, PARTS, Term.CONST, new int[] {2}), mode)))
          .as("%s: %d and-terms in Labels on a 512 KiB thread", mode, PARTS)
          .isEqualTo("threw java.lang.StackOverflowError");
    }
  }

  /** What {@code run} gave on a fresh {@value #STACK}-byte thread, or {@code "threw "} and the class of what it threw. */
  private static String onSmallThread(Supplier<String> run) throws InterruptedException {
    AtomicReference<String> outcome = new AtomicReference<>();
    Thread small =
        new Thread(
            null,
            () -> {
              try {
                outcome.set(run.get());
              } catch (Throwable t) {
                outcome.set("threw " + t.getClass().getName());
              }
            },
            "512 KiB",
            STACK);
    small.start();
    small.join();
    return outcome.get();
  }

  /** The results of {@code run}, compiled in {@code mode}, inside {@code yes as $0 | no as $1 | …}, and its steps. */
  private static String ran(Node run, CompileMode mode) {
    Node root = new Bind(AT, new Const(AT, YES), 0, new Bind(AT, new Const(AT, NO), 1, run));
    RunState state = new RunState();
    state.begin(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    List<Object> results = new ArrayList<>();
    boolean ranOut =
        Compiler.compile(root, mode)
            .run(
                state,
                "in",
                v -> {
                  results.add(v);
                  return true;
                });
    return (ranOut ? "" : "stopped: ") + results + " in " + state.steps() + " steps";
  }

  /** The first slot of the chain's parts: slots 0 and 1 are the root's. */
  private static final int FIRST = 2;

  /** The Binds of {@code n} parts, part k from {@code part}, setting slot FIRST + k; its body gives parts 0, n / 2 and n - 1. */
  private static Binds chain(int n, IntFunction<Node> part) {
    List<Node> values = new ArrayList<>();
    List<Integer> slots = new ArrayList<>();
    for (int k = 0; k < n; k++) {
      values.add(part.apply(k));
      slots.add(FIRST + k);
    }
    return new Binds(AT, values, slots, list(slot(0), slot(n / 2), slot(n - 1)));
  }

  /** The Var of part {@code k}'s slot. */
  private static Var slot(int k) {
    return new Var(AT, FIRST + k);
  }

  /** A slot no part and no root Bind takes: past every part's, a separate range per part. */
  private static int own(int k, int offset) {
    return FIRST + PARTS + 2 * k + offset;
  }

  /** Part {@code k} of a chain of {@code kind}. */
  private static Node part(Kind kind, int k) {
    if (kind == Kind.IDENTITY) {
      return new Identity(AT);
    }
    if (kind == Kind.CONST) {
      return new Const(AT, k);
    }
    if (k == 0) {
      return new Const(AT, 0);
    }
    Var prev = slot(k - 1);
    return switch (kind) {
      case VAR -> prev;
      case INTRINSIC ->
          switch (k % 4) {
            case 0 -> call("k", (Fn0) (state, in) -> k);
            case 1 -> inc(prev);
            case 2 -> call("add", (Fn2) (state, in, a, b) -> (Integer) a + (Integer) b, prev, new Const(AT, 1));
            default -> call("sum", (FnN) (state, in, a) -> (Integer) a[0] + (Integer) a[1], prev, new Const(AT, 1));
          };
      case PIPE -> new Pipe(AT, prev, inc(new Identity(AT)));
      case IF -> new If(AT, prev, inc(slot(k - 1)), new Const(AT, NO), TRUTH);
      case BIND -> new Bind(AT, prev, own(k, 0), inc(new Var(AT, own(k, 0))));
      case BINDS -> new Binds(AT, List.of(prev, inc(new Var(AT, own(k, 0)))), List.of(own(k, 0), own(k, 1)), new Var(AT, own(k, 1)));
      default -> throw new IllegalArgumentException("no compound part of kind " + kind);
    };
  }

  /** Part {@code k} of the control: 0 for part 0, else {@code (slot k - 1 + 1, nothing)}. */
  private static Node comma(int k) {
    if (k == 0) {
      return new Const(AT, 0);
    }
    return new Comma(AT, inc(slot(k - 1)), call("nothing", (Fn0) (state, in) -> CallForm.NONE));
  }

  /** The terms {@code [from, to)} of {@code term} joined by {@code and}, balanced, all true; with {@code labels}, each If's condition is inside a Label of the next slot from {@code labels[0]}. */
  private static Node and(int from, int to, Term term, int[] labels) {
    if (to - from == 1) {
      return term(term, YES);
    }
    int middle = (from + to) >>> 1;
    Node left = and(from, middle, term, labels);
    Node right = and(middle, to, term, labels);
    return new If(AT, fenced(left, labels), new If(AT, fenced(right, labels), new Const(AT, YES), new Const(AT, NO), TRUTH), new Const(AT, NO), TRUTH);
  }

  /** The terms {@code [from, to)} of {@code term} joined by {@code or}, balanced, all false. */
  private static Node or(int from, int to, Term term) {
    if (to - from == 1) {
      return term(term, NO);
    }
    int middle = (from + to) >>> 1;
    return new If(AT, or(from, middle, term), new Const(AT, YES), new If(AT, or(middle, to, term), new Const(AT, YES), new Const(AT, NO), TRUTH), TRUTH);
  }

  /** {@code cond}, or with {@code labels} {@code cond} inside a Label of slot {@code labels[0]}, the next one taken. */
  private static Node fenced(Node cond, int[] labels) {
    return labels == null ? cond : new Label(AT, labels[0]++, cond);
  }

  /** A term of {@code term} giving {@code value}, yes or no. */
  private static Node term(Term term, Object value) {
    return switch (term) {
      case CONST -> new Const(AT, value);
      case VAR -> new Var(AT, YES.equals(value) ? 0 : 1);
      case INTRINSIC -> call("term", (Fn0) (state, in) -> value);
    };
  }

  private static Intrinsic call(String name, CallForm form, Node... args) {
    return new Intrinsic(AT, new Operation(name, form), List.of(args));
  }

  private static Intrinsic inc(Node arg) {
    return call("inc", (Fn1) (state, in, a) -> (Integer) a + 1, arg);
  }

  /** The list of its arguments. */
  private static Intrinsic list(Node... args) {
    return call("list", (FnN) (state, in, a) -> List.of(a), args);
  }
}
