package dev.rail8.jq.query.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.rail8.jq.query.ir.Bind;
import dev.rail8.jq.query.ir.Binds;
import dev.rail8.jq.query.ir.Break;
import dev.rail8.jq.query.ir.Comma;
import dev.rail8.jq.query.ir.Const;
import dev.rail8.jq.query.ir.Identity;
import dev.rail8.jq.query.ir.If;
import dev.rail8.jq.query.ir.Intrinsic;
import dev.rail8.jq.query.ir.Label;
import dev.rail8.jq.query.ir.Node;
import dev.rail8.jq.query.ir.Operation;
import dev.rail8.jq.query.ir.Pipe;
import dev.rail8.jq.query.ir.Program;
import dev.rail8.jq.query.ir.Source;
import dev.rail8.jq.query.ir.Var;
import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.Fn0;
import dev.rail8.jq.query.run.Fn1;
import dev.rail8.jq.query.run.Fn2;
import dev.rail8.jq.query.run.FnN;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Truth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
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
 * which links every call site before the small thread runs. An overflow ends the run with a Termination: the
 * executable's run turns the StackOverflowError into one of cause STACK (ADR-0045 #107).
 *
 * <p>The Label control holds in CONSERVATIVE only (ADR-0039, ADR-0047 #116 Tail sites): in OPTIMISED a Label no outer
 * Break names runs by its value form, so the same run fits. The control of both modes fences each condition with a
 * Label whose body may break to a Label around the whole run.
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
          .isEqualTo("threw dev.rail8.jq.query.run.Termination");
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
      assertThat(ran(and(0, WARM, term, UnaryOperator.identity()), mode)).as("%s, %s: %d and-terms on the test thread", term, mode, WARM).isEqualTo("[yes] in 0 steps");
      assertThat(onSmallThread(() -> ran(and(0, PARTS, term, UnaryOperator.identity()), mode))).as("%s, %s: %d and-terms on a 512 KiB thread", term, mode, PARTS).isEqualTo("[yes] in 0 steps");
      assertThat(ran(or(0, WARM, term), mode)).as("%s, %s: %d or-terms on the test thread", term, mode, WARM).isEqualTo("[no] in 0 steps");
      assertThat(onSmallThread(() -> ran(or(0, PARTS, term), mode))).as("%s, %s: %d or-terms on a 512 KiB thread", term, mode, PARTS).isEqualTo("[no] in 0 steps");
    }
  }

  /**
   * The control, CONSERVATIVE only (ADR-0047 #116 Tail sites): the same and-run with each If's condition inside a Label
   * no Break names. A Label is no tail emitter and ticks nothing (ADR-0039), and CONSERVATIVE never runs the value form,
   * so each branch runs inside its condition's emit.
   */
  @Test
  void theSameBalancedRunWithItsConditionsInLabelsOverflowsA512KiBThread() throws InterruptedException {
    CompileMode mode = CompileMode.CONSERVATIVE;
    assertThat(ran(and(0, WARM, Term.CONST, labels(2)), mode)).as("%s: %d and-terms in Labels on the test thread", mode, WARM).isEqualTo("[yes] in 0 steps");
    assertThat(onSmallThread(() -> ran(and(0, PARTS, Term.CONST, labels(2)), mode)))
        .as("%s: %d and-terms in Labels on a 512 KiB thread", mode, PARTS)
        .isEqualTo("threw dev.rail8.jq.query.run.Termination");
  }

  /**
   * The same IR as the control above, OPTIMISED (ADR-0047 #116 Tail sites, beside ADR-0039): a Label no outer Break
   * names is at most one and cannot break out, so it runs by its value form, which returns its condition's value before
   * the branch runs. The run fits the thread as the run without Labels does.
   */
  @Test
  void theSameBalancedRunWithItsConditionsInLabelsFitsA512KiBThreadWhenOptimised() throws InterruptedException {
    CompileMode mode = CompileMode.OPTIMISED;
    assertThat(ran(and(0, WARM, Term.CONST, labels(2)), mode)).as("%s: %d and-terms in Labels on the test thread", mode, WARM).isEqualTo("[yes] in 0 steps");
    assertThat(onSmallThread(() -> ran(and(0, PARTS, Term.CONST, labels(2)), mode))).as("%s: %d and-terms in Labels on a 512 KiB thread", mode, PARTS).isEqualTo("[yes] in 0 steps");
  }

  /** The Label of the break-out control's whole run: the first slot past the root's; its fences take the slots after it. */
  private static final int OUTER = 2;

  /**
   * The control of both modes (ADR-0047 #116 Tail sites, beside ADR-0039): the same and-run inside {@code label $outer},
   * each If's condition in a fence {@code label $k | if $1 then break $outer else cond end}. Slot 1 is no, so no Break
   * runs, but each fence and each If around one may break out, so none runs by its value form and each branch runs
   * inside its condition's emit in both modes.
   */
  @Test
  void theSameBalancedRunWithItsConditionsInFencesThatBreakOutOverflowsA512KiBThread() throws InterruptedException {
    assertThatCode(() -> IrVerifier.verify(new Program(rooted(new Label(AT, OUTER, and(0, PARTS, Term.CONST, breakingOut(OUTER, OUTER + 1)))), List.of()), 0))
        .as("the verifier on %d and-terms in fences that break out", PARTS)
        .doesNotThrowAnyException();
    for (CompileMode mode : CompileMode.values()) {
      assertThat(ran(new Label(AT, OUTER, and(0, WARM, Term.CONST, breakingOut(OUTER, OUTER + 1))), mode))
          .as("%s: %d and-terms in fences that break out on the test thread", mode, WARM)
          .isEqualTo("[yes] in 0 steps");
      assertThat(onSmallThread(() -> ran(new Label(AT, OUTER, and(0, PARTS, Term.CONST, breakingOut(OUTER, OUTER + 1))), mode)))
          .as("%s: %d and-terms in fences that break out on a 512 KiB thread", mode, PARTS)
          .isEqualTo("threw dev.rail8.jq.query.run.Termination");
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

  /** {@code yes as $0 | no as $1 | run}. */
  private static Node rooted(Node run) {
    return new Bind(AT, new Const(AT, YES), 0, new Bind(AT, new Const(AT, NO), 1, run));
  }

  /** The results of {@code run}, compiled in {@code mode}, inside {@code yes as $0 | no as $1 | …}, and its steps. */
  private static String ran(Node run, CompileMode mode) {
    Node root = rooted(run);
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

  /** The terms {@code [from, to)} of {@code term} joined by {@code and}, balanced, all true; each If's condition is {@code fence} of it, the left one first. */
  private static Node and(int from, int to, Term term, UnaryOperator<Node> fence) {
    if (to - from == 1) {
      return term(term, YES);
    }
    int middle = (from + to) >>> 1;
    Node left = and(from, middle, term, fence);
    Node right = and(middle, to, term, fence);
    return new If(AT, fence.apply(left), new If(AT, fence.apply(right), new Const(AT, YES), new Const(AT, NO), TRUTH), new Const(AT, NO), TRUTH);
  }

  /** The terms {@code [from, to)} of {@code term} joined by {@code or}, balanced, all false. */
  private static Node or(int from, int to, Term term) {
    if (to - from == 1) {
      return term(term, NO);
    }
    int middle = (from + to) >>> 1;
    return new If(AT, or(from, middle, term), new Const(AT, YES), new If(AT, or(middle, to, term), new Const(AT, YES), new Const(AT, NO), TRUTH), TRUTH);
  }

  /** The fence of the Label control: {@code cond} inside a Label no Break names, of the next slot from {@code first}. */
  private static UnaryOperator<Node> labels(int first) {
    int[] next = {first};
    return cond -> new Label(AT, next[0]++, cond);
  }

  /**
   * The fence of the break-out control: {@code label $k | if $1 then break $outer else cond end}, k the next slot from
   * {@code first}; slot 1 is no, so the Break never runs, but it names the Label around the whole run, not the fence's.
   */
  private static UnaryOperator<Node> breakingOut(int outer, int first) {
    int[] next = {first};
    return cond -> new Label(AT, next[0]++, new If(AT, new Var(AT, 1), new Break(AT, outer), cond, TRUTH));
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
