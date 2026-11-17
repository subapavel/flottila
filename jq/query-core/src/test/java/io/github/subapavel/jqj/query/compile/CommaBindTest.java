package io.github.subapavel.jqj.query.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.run.CallForm;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.Fn1;
import io.github.subapavel.jqj.query.run.Fn2;
import io.github.subapavel.jqj.query.run.Many;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Termination;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ADR-0034 at the IR seam, over a toy format whose values are plain objects (this module never looks inside a value,
 * founding §14): {@code Comma(left, right)} runs left, charges one step on entering right and runs right, and a stop on
 * the left returns without that step; {@code Bind(value, slot, body)} sets the slot to each result of value and runs
 * body on the Bind's own input; {@code Var(slot)} reads it; {@code Intrinsic(op, args)} takes atomic arguments
 * (Identity, Var, Const) as many as its form's arity, {@code CallForm.NONE} is no result, and a {@code Many} loop charges
 * one step per element before its emit, {@code Many.END} (-1) ending it. The verifier rejects every tree that breaks
 * those rules with an IllegalStateException, as it rejects a node used twice (CompilerTest).
 *
 * <p>Shapes the test-author fixed from ADR-0034 and ADR-0009 (no draft text reached the red): {@code Comma(Source, Node,
 * Node)}, {@code Bind(Source, Node value, int slot, Node body)}, {@code Var(Source, int slot)}, {@code Intrinsic(Source,
 * Operation, List<Node> args)}, {@code Operation(String name, CallForm form)}; {@code CallForm}, {@code Fn1}, {@code Fn2}
 * and {@code Many} beside {@code Fn0} in {@code query.run}; {@code Many.first(state, in, args)}, {@code next(state, in,
 * args, cursor)} and {@code value(state, in, cursor)} with {@code Object[]} arguments.
 */
class CommaBindTest {
  private static final Source AT = new Source(0, 1);

  private static Executable compile(Node root) {
    return Compiler.compile(root, CompileMode.OPTIMISED);
  }

  /** A run state in a run whose step budget is {@code steps}: its next tick past them ends the run (ADR-0030). */
  private static RunState running(long steps) {
    RunState state = new RunState();
    state.begin(steps, Long.MAX_VALUE, Long.MAX_VALUE);
    return state;
  }

  /** The results of {@code root} on {@code in} under {@code state}; the sink never stops. */
  private static List<Object> results(Node root, RunState state, Object in) {
    List<Object> emitted = new ArrayList<>();
    boolean ranOut =
        compile(root)
            .run(
                state,
                in,
                v -> {
                  emitted.add(v);
                  return true;
                });
    assertThat(ranOut).as("run's answer when the sink never stops").isTrue();
    return emitted;
  }

  private static Intrinsic call(String name, CallForm form, Node... args) {
    return new Intrinsic(AT, new Operation(name, form), List.of(args));
  }

  /** An operation with no result, {@code CallForm.NONE} in this toy format. */
  private static Intrinsic none() {
    return call("none", (Fn0) (state, in) -> CallForm.NONE);
  }

  /** An operation that fails the test if it runs. */
  private static Intrinsic never() {
    return call(
        "never",
        (Fn0)
            (state, in) -> {
              throw new AssertionError("the right branch ran after a stop on the left");
            });
  }

  /** A Many over {@code values}: its cursor is the index, END after the last. */
  private static Many over(Object... values) {
    return new Many() {
      @Override
      public long first(RunState state, Object in, Object[] args) {
        return values.length == 0 ? Many.END : 0;
      }

      @Override
      public long next(RunState state, Object in, Object[] args, long cursor) {
        return cursor + 1 < values.length ? cursor + 1 : Many.END;
      }

      @Override
      public Object value(RunState state, Object in, Object[] args, long cursor) {
        return values[(int) cursor];
      }
    };
  }

  private static void assertSteps(Runnable run, String what) {
    Termination t = assertThatExceptionOfType(Termination.class).as("%s", what).isThrownBy(run::run).actual();
    assertThat(t.cause()).as("cause of %s", what).isEqualTo(Termination.Cause.BUDGET);
    assertThat(t.budget()).as("budget of %s", what).isEqualTo(Termination.Budget.STEPS);
  }

  @Test
  void commaEmitsLeftThenRight() {
    Object a = new Object();
    Object b = new Object();
    Object c = new Object();
    assertThat(results(new Comma(AT, new Const(AT, a), new Const(AT, b)), new RunState(), new Object())).as("results of a, b").containsExactly(a, b);
    Comma left = new Comma(AT, new Comma(AT, new Const(AT, a), new Const(AT, b)), new Const(AT, c));
    Comma right = new Comma(AT, new Const(AT, a), new Comma(AT, new Const(AT, b), new Const(AT, c)));
    assertThat(results(left, new RunState(), new Object())).as("results of (a, b), c").containsExactly(a, b, c);
    assertThat(results(right, new RunState(), new Object())).as("results of a, (b, c)").containsExactly(a, b, c);
    // Both branches run on the Comma's input.
    Object in = new Object();
    assertThat(results(new Comma(AT, new Identity(AT), new Identity(AT)), new RunState(), in)).as("results of ., .").containsExactly(in, in);
  }

  @Test
  void commaTicksEvenWhenLeftIsEmpty() {
    // F-32: the step is charged on entering the right branch, whatever the left gave, so a chain of empty lefts ticks.
    Object value = new Object();
    assertSteps(() -> results(new Comma(AT, none(), new Const(AT, value)), running(0), new Object()), "none, c with 0 steps");
    RunState one = running(1);
    assertThat(results(new Comma(AT, none(), new Const(AT, value)), one, new Object())).as("results of none, c with 1 step").containsExactly(value);
    assertThat(one.steps()).as("steps of none, c").isEqualTo(1L);
    one.end();
    // Two commas, two steps: 1 is not enough.
    Comma two = new Comma(AT, new Comma(AT, none(), none()), new Const(AT, value));
    assertSteps(() -> results(two, running(1), new Object()), "(none, none), c with 1 step");
  }

  @Test
  void commaStopOnLeftSkipsRight() {
    // ADR-0034: a stop on the left returns without the tick, so a budget of 0 is enough, and the right never runs.
    Object a = new Object();
    RunState state = running(0);
    List<Object> emitted = new ArrayList<>();
    boolean ranOut =
        compile(new Comma(AT, new Const(AT, a), never()))
            .run(
                state,
                new Object(),
                v -> {
                  emitted.add(v);
                  return false;
                });
    assertThat(ranOut).as("run's answer after the sink stopped the left").isFalse();
    assertThat(emitted).as("results before the stop").containsExactly(a);
    assertThat(state.steps()).as("steps after a stop on the left").isZero();
    state.end();
    // Witness: a stop on the right comes back as false too, after the right's step.
    RunState right = running(1);
    List<Object> second = new ArrayList<>();
    Object b = new Object();
    boolean stopped =
        compile(new Comma(AT, new Const(AT, a), new Const(AT, b)))
            .run(
                right,
                new Object(),
                v -> {
                  second.add(v);
                  return v != b;
                });
    assertThat(stopped).as("run's answer after the sink stopped the right").isFalse();
    assertThat(second).as("results before the stop on the right").containsExactly(a, b);
    assertThat(right.steps()).as("steps after a stop on the right").isEqualTo(1L);
  }

  @Test
  void bindBodySeesTheBindsInputAndTheSlot() {
    Object in = new Object();
    Object x = new Object();
    Object y = new Object();
    Fn1 pair = (state, input, a) -> List.of(input, a);
    // Each result of the value sets the slot; the body runs on the Bind's input, not on the value's result.
    Bind bind = new Bind(AT, new Comma(AT, new Const(AT, x), new Const(AT, y)), 0, call("pair", pair, new Var(AT, 0)));
    assertThat(results(bind, new RunState(), in)).as("results of (x, y) as $0 | pair($0)").containsExactly(List.of(in, x), List.of(in, y));

    // Two slots: the outer Bind is the outer loop, and Fn2 gets its arguments in order.
    Object p = new Object();
    Object q = new Object();
    Fn2 triple = (state, input, a, b) -> List.of(input, a, b);
    Bind outer =
        new Bind(
            AT,
            new Comma(AT, new Const(AT, x), new Const(AT, y)),
            0,
            new Bind(AT, new Comma(AT, new Const(AT, p), new Const(AT, q)), 1, call("triple", triple, new Var(AT, 0), new Var(AT, 1))));
    assertThat(results(outer, new RunState(), in))
        .as("results of (x, y) as $0 | (p, q) as $1 | triple($0; $1)")
        .containsExactly(List.of(in, x, p), List.of(in, x, q), List.of(in, y, p), List.of(in, y, q));

    // The value runs on the Bind's input too, and a value with no result runs no body.
    Bind identity = new Bind(AT, new Identity(AT), 0, call("pair", pair, new Var(AT, 0)));
    assertThat(results(identity, new RunState(), in)).as("results of . as $0 | pair($0)").containsExactly(List.of(in, in));
    assertThat(results(new Bind(AT, none(), 0, never()), new RunState(), in)).as("results of none as $0 | never").isEmpty();
  }

  @Test
  void atomicArgumentsReadTheInputAConstantOrASlot() {
    Object in = new Object();
    Object c = new Object();
    Fn2 both = (state, input, a, b) -> List.of(a, b);
    assertThat(results(call("both", both, new Identity(AT), new Const(AT, c)), new RunState(), in)).as("results of both(.; c)").containsExactly(List.of(in, c));
    // Through a Pipe the Identity argument is the right side's input.
    Object mid = new Object();
    Pipe piped = new Pipe(AT, new Const(AT, mid), call("both", both, new Const(AT, c), new Identity(AT)));
    assertThat(results(piped, new RunState(), in)).as("results of mid | both(c; .)").containsExactly(List.of(c, mid));
  }

  @Test
  void noneEmitsNothing() {
    // ADR-0034: CallForm.NONE means no result, for every one-result form; the run still ends with true.
    Fn1 noneOf1 = (state, in, a) -> CallForm.NONE;
    Fn2 noneOf2 = (state, in, a, b) -> CallForm.NONE;
    assertThat(results(none(), new RunState(), new Object())).as("results of none").isEmpty();
    assertThat(results(call("none1", noneOf1, new Identity(AT)), new RunState(), new Object())).as("results of none1(.)").isEmpty();
    assertThat(results(call("none2", noneOf2, new Identity(AT), new Identity(AT)), new RunState(), new Object())).as("results of none2(.; .)").isEmpty();
    // Witness: the same form with a result emits it.
    Object out = new Object();
    assertThat(results(call("one", (Fn0) (state, in) -> out), new RunState(), new Object())).as("results of one").containsExactly(out);
  }

  @Test
  void manyLoopTicksBeforeEachEmitAndStops() {
    Object a = new Object();
    Object b = new Object();
    Object c = new Object();
    Intrinsic three = call("three", over(a, b, c));
    // Three elements, three steps.
    RunState exact = running(3);
    assertThat(results(three, exact, new Object())).as("results of three with 3 steps").containsExactly(a, b, c);
    assertThat(exact.steps()).as("steps of three").isEqualTo(3L);
    exact.end();
    // With 2 steps the third element's tick comes before its emit.
    List<Object> emitted = new ArrayList<>();
    assertSteps(
        () ->
            compile(call("three", over(a, b, c)))
                .run(
                    running(2),
                    new Object(),
                    v -> {
                      emitted.add(v);
                      return true;
                    }),
        "three with 2 steps");
    assertThat(emitted).as("results of three with 2 steps").containsExactly(a, b);
    // A stop ends the loop at once: no further element, no further tick.
    RunState stop = running(3);
    List<Object> first = new ArrayList<>();
    boolean ranOut =
        compile(call("three", over(a, b, c)))
            .run(
                stop,
                new Object(),
                v -> {
                  first.add(v);
                  return false;
                });
    assertThat(ranOut).as("run's answer after the sink stopped three").isFalse();
    assertThat(first).as("results before the stop").containsExactly(a);
    assertThat(stop.steps()).as("steps before the stop").isEqualTo(1L);
    // An empty Many emits nothing and charges nothing.
    RunState empty = running(0);
    assertThat(results(call("empty", over()), empty, new Object())).as("results of an empty Many").isEmpty();
  }

  @Test
  void aVarAsANodeGivesItsSlotsValueWhateverItsInput() {
    // ADR-0034: a Var is a node as well as an argument; it gives its slot's one value.
    Object in = new Object();
    assertThat(results(new Bind(AT, new Identity(AT), 0, new Var(AT, 0)), new RunState(), in)).as("results of . as $0 | $0").containsExactly(in);
    // Witness that the Var reads its slot, not its input: a value that is not the input, and a Var after a Pipe.
    Object c = new Object();
    Object mid = new Object();
    assertThat(results(new Bind(AT, new Const(AT, c), 0, new Var(AT, 0)), new RunState(), in)).as("results of c as $0 | $0").containsExactly(c);
    Bind piped = new Bind(AT, new Const(AT, c), 0, new Pipe(AT, new Const(AT, mid), new Var(AT, 0)));
    assertThat(results(piped, new RunState(), in)).as("results of c as $0 | mid | $0").containsExactly(c);
    // A value with two results gives both, in order.
    Object a = new Object();
    Object b = new Object();
    Bind two = new Bind(AT, new Comma(AT, new Const(AT, a), new Const(AT, b)), 0, new Var(AT, 0));
    assertThat(results(two, new RunState(), in)).as("results of (a, b) as $0 | $0").containsExactly(a, b);
    // Two slots: each Var reads its own.
    Bind slots = new Bind(AT, new Const(AT, a), 0, new Bind(AT, new Const(AT, b), 1, new Comma(AT, new Var(AT, 1), new Var(AT, 0))));
    assertThat(results(slots, new RunState(), in)).as("results of a as $0 | b as $1 | $1, $0").containsExactly(b, a);
    // A stop at the Var ends the run: the value's second result is never bound.
    List<Object> emitted = new ArrayList<>();
    boolean ranOut =
        compile(two)
            .run(
                new RunState(),
                in,
                v -> {
                  emitted.add(v);
                  return false;
                });
    assertThat(ranOut).as("run's answer after the sink stopped at $0").isFalse();
    assertThat(emitted).as("results of (a, b) as $0 | $0 before the stop").containsExactly(a);
  }

  @Test
  void aManyGetsItsLoopSlotsAfterItsArgumentsOncePerLoop() {
    // ADR-0034 as amended by ADR-0044: a loop's array holds the arguments, then the Many's loop slots; first may set a
    // slot, the later calls of that loop read it, and the next loop starts from an empty slot.
    List<List<Object>> seen = new ArrayList<>();
    Many held =
        new Many() {
          @Override
          public int loopSlots() {
            return 1;
          }

          @Override
          public long first(RunState state, Object in, Object[] args) {
            seen.add(called("first", args));
            args[args.length - 1] = in;
            return 0;
          }

          @Override
          public long next(RunState state, Object in, Object[] args, long cursor) {
            seen.add(called("next", args));
            return Many.END;
          }

          @Override
          public Object value(RunState state, Object in, Object[] args, long cursor) {
            return args[args.length - 1];
          }
        };
    Object c = new Object();
    Object x = new Object();
    Object y = new Object();
    Pipe loops = new Pipe(AT, new Comma(AT, new Const(AT, x), new Const(AT, y)), call("held", held, new Const(AT, c)));
    assertThat(results(loops, new RunState(), new Object())).as("results of (x, y) | held(c)").containsExactly(x, y);
    assertThat(seen)
        .as("calls of (x, y) | held(c)")
        .containsExactly(Arrays.asList("first", c, null), List.of("next", c, x), Arrays.asList("first", c, null), List.of("next", c, y));
    // With no argument the loop slot is the whole array, never one shared by the loops.
    seen.clear();
    assertThat(results(new Pipe(AT, new Comma(AT, new Const(AT, x), new Const(AT, y)), call("held", held)), new RunState(), new Object()))
        .as("results of (x, y) | held")
        .containsExactly(x, y);
    assertThat(seen).as("calls of (x, y) | held").containsExactly(Arrays.asList("first", (Object) null), List.of("next", x), Arrays.asList("first", (Object) null), List.of("next", y));
  }

  /** {@code name} followed by the arguments a Many got, as a list. */
  private static List<Object> called(String name, Object[] args) {
    List<Object> call = new ArrayList<>();
    call.add(name);
    call.addAll(Arrays.asList(args));
    return call;
  }

  @Test
  void aManyGetsTheValuesOfItsAtomicArguments() {
    // ADR-0034: each call reads a Many's arguments, in order, and hands the same values to first and to next.
    List<List<Object>> seen = new ArrayList<>();
    Many twice =
        new Many() {
          @Override
          public long first(RunState state, Object in, Object[] args) {
            seen.add(called("first", args));
            return 0;
          }

          @Override
          public long next(RunState state, Object in, Object[] args, long cursor) {
            seen.add(called("next", args));
            return cursor == 0 ? 1 : Many.END;
          }

          @Override
          public Object value(RunState state, Object in, Object[] args, long cursor) {
            return List.of(in, cursor);
          }
        };
    Object in = new Object();
    Object c = new Object();
    assertThat(results(call("twice", twice, new Const(AT, c)), new RunState(), in)).as("results of twice(c)").containsExactly(List.of(in, 0L), List.of(in, 1L));
    assertThat(seen).as("calls of twice(c)").containsExactly(List.of("first", c), List.of("next", c), List.of("next", c));

    // A Var argument: each result of the Bind's value arrives in its own call.
    seen.clear();
    Object x = new Object();
    Object y = new Object();
    Bind bound = new Bind(AT, new Comma(AT, new Const(AT, x), new Const(AT, y)), 0, call("twice", twice, new Var(AT, 0)));
    assertThat(results(bound, new RunState(), in)).as("results of (x, y) as $0 | twice($0)").hasSize(4);
    assertThat(seen)
        .as("calls of (x, y) as $0 | twice($0)")
        .containsExactly(List.of("first", x), List.of("next", x), List.of("next", x), List.of("first", y), List.of("next", y), List.of("next", y));

    // Three arguments keep their order, and an Identity among them reads the call's input.
    seen.clear();
    Bind mixed = new Bind(AT, new Const(AT, x), 0, call("twice", twice, new Const(AT, c), new Identity(AT), new Var(AT, 0)));
    assertThat(results(mixed, new RunState(), in)).as("results of x as $0 | twice(c; .; $0)").hasSize(2);
    assertThat(seen).as("calls of x as $0 | twice(c; .; $0)").containsExactly(List.of("first", c, in, x), List.of("next", c, in, x), List.of("next", c, in, x));
  }

  @Test
  void verifierRejectsUnboundVar() {
    Fn1 id = (state, in, a) -> a;
    assertThatIllegalStateException().as("compile of id($0) with no Bind").isThrownBy(() -> compile(call("id", id, new Var(AT, 0))));
    // A Var after its Bind, outside the body.
    Comma after = new Comma(AT, new Bind(AT, new Identity(AT), 0, new Identity(AT)), call("id", id, new Var(AT, 0)));
    assertThatIllegalStateException().as("compile of (. as $0 | .), id($0)").isThrownBy(() -> compile(after));
    // A Var of another slot.
    assertThatIllegalStateException()
        .as("compile of . as $0 | id($1)")
        .isThrownBy(() -> compile(new Bind(AT, new Identity(AT), 0, call("id", id, new Var(AT, 1)))));
    // Witness: inside its Bind's body the Var compiles.
    assertThat(compile(new Bind(AT, new Identity(AT), 0, call("id", id, new Var(AT, 0))))).as("compile of . as $0 | id($0)").isNotNull();
  }

  @Test
  void verifierRejectsVarInItsOwnValue() {
    Fn1 id = (state, in, a) -> a;
    Bind own = new Bind(AT, call("id", id, new Var(AT, 0)), 0, new Identity(AT));
    assertThatIllegalStateException().as("compile of id($0) as $0 | .").isThrownBy(() -> compile(own));
    // Witness: an outer slot in an inner Bind's value compiles.
    Bind nested = new Bind(AT, new Identity(AT), 0, new Bind(AT, call("id", id, new Var(AT, 0)), 1, call("id", id, new Var(AT, 1))));
    assertThat(compile(nested)).as("compile of . as $0 | id($0) as $1 | id($1)").isNotNull();
  }

  @Test
  void verifierRejectsSlotBoundTwice() {
    Fn1 id = (state, in, a) -> a;
    Bind inner = new Bind(AT, new Identity(AT), 0, new Bind(AT, new Identity(AT), 0, call("id", id, new Var(AT, 0))));
    assertThatIllegalStateException().as("compile of . as $0 | . as $0 | id($0)").isThrownBy(() -> compile(inner));
    // ADR-0034: one Bind per slot in a tree, siblings included.
    Comma siblings = new Comma(AT, new Bind(AT, new Identity(AT), 0, call("id", id, new Var(AT, 0))), new Bind(AT, new Identity(AT), 0, call("id", id, new Var(AT, 0))));
    assertThatIllegalStateException().as("compile of (. as $0 | id($0)), (. as $0 | id($0))").isThrownBy(() -> compile(siblings));
    // Witness: two slots compile.
    Comma two = new Comma(AT, new Bind(AT, new Identity(AT), 0, call("id", id, new Var(AT, 0))), new Bind(AT, new Identity(AT), 1, call("id", id, new Var(AT, 1))));
    assertThat(compile(two)).as("compile of (. as $0 | id($0)), (. as $1 | id($1))").isNotNull();
  }

  @Test
  void verifierRejectsNonAtomicArgument() {
    Fn1 id = (state, in, a) -> a;
    for (Node argument : List.of(new Pipe(AT, new Identity(AT), new Identity(AT)), new Comma(AT, new Identity(AT), new Identity(AT)), call("id", id, new Identity(AT)))) {
      assertThatIllegalStateException().as("compile of id(%s)", argument).isThrownBy(() -> compile(call("id", id, argument)));
    }
    Bind bound = new Bind(AT, new Identity(AT), 0, call("id", id, new Bind(AT, new Identity(AT), 1, new Var(AT, 1))));
    assertThatIllegalStateException().as("compile of a Bind as an argument").isThrownBy(() -> compile(bound));
    // Witness: each atomic argument compiles.
    for (Node argument : List.of(new Identity(AT), new Const(AT, new Object()))) {
      assertThat(compile(call("id", id, argument))).as("compile of id(%s)", argument).isNotNull();
    }
  }

  @Test
  void verifierRejectsWrongArity() {
    Fn0 zero = (state, in) -> in;
    Fn1 one = (state, in, a) -> a;
    Fn2 two = (state, in, a, b) -> a;
    assertThatIllegalStateException().as("compile of zero(.)").isThrownBy(() -> compile(call("zero", zero, new Identity(AT))));
    assertThatIllegalStateException().as("compile of one").isThrownBy(() -> compile(call("one", one)));
    assertThatIllegalStateException().as("compile of one(.; .)").isThrownBy(() -> compile(call("one", one, new Identity(AT), new Identity(AT))));
    assertThatIllegalStateException().as("compile of two(.)").isThrownBy(() -> compile(call("two", two, new Identity(AT))));
    assertThatIllegalStateException()
        .as("compile of two(.; .; .)")
        .isThrownBy(() -> compile(call("two", two, new Identity(AT), new Identity(AT), new Identity(AT))));
    // Witness: each form with its arity compiles.
    assertThat(compile(call("zero", zero))).as("compile of zero").isNotNull();
    assertThat(compile(call("one", one, new Identity(AT)))).as("compile of one(.)").isNotNull();
    assertThat(compile(call("two", two, new Identity(AT), new Identity(AT)))).as("compile of two(.; .)").isNotNull();
  }
}
