package io.github.subapavel.jqj.query.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.query.ir.AltBind;
import io.github.subapavel.jqj.query.ir.Alternative;
import io.github.subapavel.jqj.query.ir.Advance;
import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Break;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.Foreach;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Label;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Reduce;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.run.CallForm;
import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.Fn1;
import io.github.subapavel.jqj.query.run.Many;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;
import io.github.subapavel.jqj.query.run.Termination;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Reduce, Foreach and Advance at the IR seam (ADR-0041), over a toy format whose values are plain objects (founding
 * §14). For each result r of init the state slot is set to r and {@code each} runs on the input; Reduce then emits the
 * slot's value and nulls the slot, Foreach emits each of {@code each}'s results. Advance takes the slot's value v and
 * leaves null, runs its update on v, and stores and emits each result, so every result starts from v, the last one
 * wins and no result leaves null. A retried ?// alternative rolls nothing back. A false passes at once, and Reduce
 * emits nothing after one (ADR-0039). None of the three ticks: the source's producer does.
 *
 * <p>The toy {@code pair($x)} is the filter {@code [., $x]} and {@code swap($x)} is {@code [$x, .]}; each expected value
 * is the reference binary's recording of the filter named beside it (oracle/src/test/resources/diff/reduce.rec,
 * 2026-11-13).
 *
 * <p>Shapes the test-author fixed from ADR-0041's lowering (no draft text reached the red): {@code Reduce(Source, Node
 * init, int slot, Node each)}, {@code Foreach(Source, Node init, int slot, Node each)} and {@code Advance(Source, int
 * slot, Node update)} in {@code query.ir}. Compile and run take #67's parameters (ADR-0038); Label and Break are #68's
 * (ADR-0039), AltBind #67's.
 */
class ReduceOpTest {
  private static final Source AT = new Source(0, 1);

  /** The state slot of every loop here. */
  private static final int STATE = 0;

  /** The slot of the pattern {@code $x}. */
  private static final int X = 1;

  /** The label slot. */
  private static final int LABEL = 2;

  private static Executable compile(Node root, CompileMode mode) {
    return Compiler.compile(root, 0, mode);
  }

  private static Const c(Object v) {
    return new Const(AT, v);
  }

  private static Comma comma(Node left, Node right) {
    return new Comma(AT, left, right);
  }

  private static Intrinsic call(String name, CallForm form, Node... args) {
    return new Intrinsic(AT, new Operation(name, form), List.of(args));
  }

  /** The filter {@code [., $x]}: a list that may hold null. */
  private static Intrinsic pair() {
    return call("pair", (Fn1) (state, in, a) -> Arrays.asList(in, a), new Var(AT, X));
  }

  /** The filter {@code [$x, .]}. */
  private static Intrinsic swap() {
    return call("swap", (Fn1) (state, in, a) -> Arrays.asList(a, in), new Var(AT, X));
  }

  /** The filter {@code .[1]} on a list. */
  private static Intrinsic second() {
    return call("second", (Fn0) (state, in) -> ((List<?>) in).get(1));
  }

  private static Intrinsic none() {
    return call("none", (Fn0) (state, in) -> CallForm.NONE);
  }

  /** {@code reduce SOURCE as $x (INIT; UPDATE)}, lowered as ADR-0041 states. */
  private static Reduce reduce(Node source, Node init, Node update) {
    return new Reduce(AT, init, STATE, new Bind(AT, source, X, new Advance(AT, STATE, update)));
  }

  /** {@code foreach SOURCE as $x (INIT; UPDATE)}. */
  private static Foreach foreach(Node source, Node init, Node update) {
    return new Foreach(AT, init, STATE, new Bind(AT, source, X, new Advance(AT, STATE, update)));
  }

  /** {@code foreach SOURCE as $x (INIT; UPDATE; EXTRACT)}. */
  private static Foreach foreach(Node source, Node init, Node update, Node extract) {
    return new Foreach(AT, init, STATE, new Bind(AT, source, X, new Pipe(AT, new Advance(AT, STATE, update), extract)));
  }

  /** A Many over {@code values}: its cursor is the index, END after the last. */
  private static Intrinsic over(Object... values) {
    Many many =
        new Many() {
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
    return call("over", many);
  }

  /** A run state in a run whose step budget is {@code steps}: its next tick past them ends the run (ADR-0030). */
  private static RunState running(long steps) {
    RunState state = new RunState();
    state.begin(steps, Long.MAX_VALUE, Long.MAX_VALUE);
    return state;
  }

  /** The results of {@code root} on {@code in}; the sink never stops, and the run must report that they ran out. */
  private static List<Object> results(Node root, CompileMode mode, RunState state, Object in) {
    List<Object> emitted = new ArrayList<>();
    Sink all =
        v -> {
          emitted.add(v);
          return true;
        };
    assertThat(compile(root, mode).run(state, new Object[0], in, all)).as("run's answer when the sink never stops").isTrue();
    return emitted;
  }

  private static List<Object> results(Node root, CompileMode mode) {
    return results(root, mode, new RunState(), new Object());
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void reduceEmitsTheLastStateOnce(CompileMode mode) {
    // reduce range(3;6) as $x ([]; [., $x]) gives [[[[],3],4],5] (reduce/range2-pairs).
    Node root = reduce(over(3, 4, 5), c(List.of()), pair());
    assertThat(results(root, mode)).as("results of reduce (3,4,5) as $x ([]; pair($x))").containsExactly(Arrays.asList(Arrays.asList(Arrays.asList(List.of(), 3), 4), 5));
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void everyUpdateResultStartsFromTheSameStateAndTheLastWins(CompileMode mode) {
    // reduce (1,2) as $x (0; [., $x], [$x, .]) gives [2,[1,0]] (reduce/multi-update-same-start).
    Node root = reduce(comma(c(1), c(2)), c(0), comma(pair(), swap()));
    assertThat(results(root, mode)).as("results of reduce (1,2) as $x (0; pair($x), swap($x))").containsExactly(Arrays.asList(2, Arrays.asList(1, 0)));
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void anUpdateWithNoResultLeavesNull(CompileMode mode) {
    // reduce (1,2) as $x (5; empty) gives null (reduce/update-empty).
    assertThat(results(reduce(comma(c(1), c(2)), c(5), none()), mode)).as("results of reduce (1,2) as $x (5; none)").containsExactly((Object) null);
    // reduce ([], [3]) as $x (7; $x[] as $y | [., $y]) gives [null,3] (reduce/update-empty-then-next): the next update
    // starts from the null.
    Intrinsic someOrNone = call("someOrNone", (Fn1) (state, in, a) -> a.equals(1) ? CallForm.NONE : Arrays.asList(in, a), new Var(AT, X));
    assertThat(results(reduce(comma(c(1), c(3)), c(7), someOrNone), mode)).as("results of reduce (1,3) as $x (7; none on 1, else pair($x))").containsExactly(Arrays.asList(null, 3));
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void anEmptySourceEmitsTheInit(CompileMode mode) {
    // reduce empty as $x (5; .) gives 5 (reduce/empty-source); [foreach empty as $x (0; .)] gives [].
    Intrinsic never = call("never", (Fn0) (state, in) -> {
      throw new AssertionError("the update ran with no source result");
    });
    assertThat(results(reduce(over(), c(5), never), mode)).as("results of reduce over() as $x (5; never)").containsExactly(5);
    assertThat(results(foreach(over(), c(5), never), mode)).as("results of foreach over() as $x (5; never)").isEmpty();
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void eachInitResultRunsItsOwnLoop(CompileMode mode) {
    // [reduce (1,2) as $x (0, 10; [., $x])] gives [[[0,1],2],[[10,1],2]] (reduce/init-generator-pairs).
    Node root = reduce(comma(c(1), c(2)), comma(c(0), c(10)), pair());
    assertThat(results(root, mode))
        .as("results of reduce (1,2) as $x (0, 10; pair($x))")
        .containsExactly(Arrays.asList(Arrays.asList(0, 1), 2), Arrays.asList(Arrays.asList(10, 1), 2));
    // [reduce (1,2) as $x (empty; [., $x])] gives [] (reduce/init-empty).
    assertThat(results(reduce(comma(c(1), c(2)), none(), pair()), mode)).as("results of reduce (1,2) as $x (none; pair($x))").isEmpty();
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void theSourceAndTheInitRunOnTheInputAndTheUpdateOnTheState(CompileMode mode) {
    // reduce .[] as $x (.; [$x, .[0]]) on [1,2] gives [2,1] (reduce/input-is-the-sources-and-inits).
    Many elements =
        new Many() {
          @Override
          public long first(RunState state, Object in, Object[] args) {
            return ((List<?>) in).isEmpty() ? Many.END : 0;
          }

          @Override
          public long next(RunState state, Object in, Object[] args, long cursor) {
            return cursor + 1 < ((List<?>) in).size() ? cursor + 1 : Many.END;
          }

          @Override
          public Object value(RunState state, Object in, Object[] args, long cursor) {
            return ((List<?>) in).get((int) cursor);
          }
        };
    Intrinsic update = call("update", (Fn1) (state, in, a) -> Arrays.asList(a, ((List<?>) in).get(0)), new Var(AT, X));
    Node root = reduce(call("elements", elements), new Identity(AT), update);
    assertThat(results(root, mode, new RunState(), List.of(1, 2))).as("results of reduce .[] as $x (.; [$x, .[0]]) on [1,2]").containsExactly(Arrays.asList(2, 1));
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void foreachEmitsEachUpdateResult(CompileMode mode) {
    // [foreach (1,2) as $x (0; [., $x], $x)] gives [[0,1],1,[1,2],2] (reduce/foreach-multi-update-pairs).
    Node root = foreach(comma(c(1), c(2)), c(0), comma(pair(), new Var(AT, X)));
    assertThat(results(root, mode)).as("results of foreach (1,2) as $x (0; pair($x), $x)").containsExactly(Arrays.asList(0, 1), 1, Arrays.asList(1, 2), 2);
    // [foreach (1,2) as $x (0; empty)] gives [] (reduce/foreach-update-empty).
    assertThat(results(foreach(comma(c(1), c(2)), c(0), none()), mode)).as("results of foreach (1,2) as $x (0; none)").isEmpty();
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void foreachExtractsFromEachState(CompileMode mode) {
    // [foreach (1,2) as $x (0, 10; [., $x]; .[1])] gives [1,2,1,2] (reduce/foreach-init-generator).
    Node root = foreach(comma(c(1), c(2)), comma(c(0), c(10)), pair(), second());
    assertThat(results(root, mode)).as("results of foreach (1,2) as $x (0, 10; pair($x); .[1])").containsExactly(1, 2, 1, 2);
    // [foreach (1,2) as $x (0; [., $x]; empty)] gives [] (reduce/foreach-extract-empty).
    assertThat(results(foreach(comma(c(1), c(2)), c(0), pair(), none()), mode)).as("results of foreach (1,2) as $x (0; pair($x); none)").isEmpty();
  }

  /** Whether {@code ref} is cleared after at most 50 collections. */
  private static boolean collected(WeakReference<?> ref) {
    for (int i = 0; i < 50 && ref.get() != null; i++) {
      System.gc();
      LockSupport.parkNanos(10_000_000L);
    }
    return ref.get() == null;
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void theSlotIsNullAfterTheEmit(CompileMode mode) {
    // ADR-0041: Reduce emits the slot's value and nulls the slot, so once the emit returned the run holds the final
    // state no longer. The right branch of the comma runs after the reduce, in the same run and with the same slots.
    List<WeakReference<Object>> made = new ArrayList<>();
    Intrinsic fresh =
        call(
            "fresh",
            (Fn0)
                (state, in) -> {
                  Object state1 = new Object();
                  made.add(new WeakReference<>(state1));
                  return state1;
                });
    Intrinsic probe = call("probe", (Fn0) (state, in) -> collected(made.getLast()));
    Node root = comma(reduce(comma(c(1), c(2)), c(0), fresh), probe);
    // The sink keeps a marker for the state, never the state.
    List<Object> seen = new ArrayList<>();
    Sink marks =
        v -> {
          seen.add(v instanceof Boolean ? v : "state");
          return true;
        };
    assertThat(compile(root, mode).run(new RunState(), new Object[0], new Object(), marks)).as("run's answer").isTrue();
    assertThat(made).as("states the update made").hasSize(2);
    assertThat(seen).as("results of reduce (1,2) as $x (0; fresh), probe: the final state is collectable").containsExactly("state", true);

    // Witness: the probe sees a state that is still held. A sink that keeps the state makes the probe answer false.
    made.clear();
    List<Object> kept = new ArrayList<>();
    Sink keeps =
        v -> {
          kept.add(v);
          return true;
        };
    assertThat(compile(root, mode).run(new RunState(), new Object[0], new Object(), keeps)).as("run's answer with the keeping sink").isTrue();
    assertThat(kept).as("results with the keeping sink").hasSize(2);
    assertThat(kept.get(1)).as("the probe while the sink holds the state").isEqualTo(false);
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void reduceEmitsNothingAfterABreak(CompileMode mode) {
    // [label $f | reduce (1, 2, break $f, 3) as $x (0; [., $x])] gives [] (reduce/break-in-source).
    Node inSource = new Label(AT, LABEL, reduce(comma(c(1), comma(c(2), comma(new Break(AT, LABEL), c(3)))), c(0), pair()));
    assertThat(results(inSource, mode)).as("results of label | reduce (1, 2, break, 3) as $x (0; pair($x))").isEmpty();
    // A break in the update: [label $f | reduce (0,1,2,3,4) as $x (0; if $x == 3 then break $f else .+$x end)] gives []
    // (reduce/break-in-update); here every update breaks.
    Node inUpdate = new Label(AT, LABEL, reduce(comma(c(1), c(2)), c(0), new Break(AT, LABEL)));
    assertThat(results(inUpdate, mode)).as("results of label | reduce (1,2) as $x (0; break)").isEmpty();
    // A break in the init ends the loops after the earlier init's emit: [label $f | reduce (1,2) as $x ((1, break $f);
    // .+$x)] gives [4] (reduce/break-in-init).
    Node inInit = new Label(AT, LABEL, reduce(comma(c(1), c(2)), comma(c(1), new Break(AT, LABEL)), pair()));
    assertThat(results(inInit, mode)).as("results of label | reduce (1,2) as $x ((1, break); pair($x))").containsExactly(Arrays.asList(Arrays.asList(1, 1), 2));
    // Witness: without the break the same reduce emits its state.
    Node plain = new Label(AT, LABEL, reduce(comma(c(1), c(2)), c(0), pair()));
    assertThat(results(plain, mode)).as("results of label | reduce (1,2) as $x (0; pair($x))").containsExactly(Arrays.asList(Arrays.asList(0, 1), 2));
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void foreachKeepsWhatItEmittedBeforeABreak(CompileMode mode) {
    // [label $f | foreach (1,2,3) as $x (0; if $x == 3 then break $f else .+$x end)] gives [1,3] (reduce/break-in-foreach):
    // the results before the break stay. Here the source breaks after 1 and 2.
    Node root = new Label(AT, LABEL, foreach(comma(c(1), comma(c(2), new Break(AT, LABEL))), c(0), new Var(AT, X)));
    assertThat(results(root, mode)).as("results of label | foreach (1, 2, break) as $x (0; $x)").containsExactly(1, 2);
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void aHostStopEndsTheLoopsAtOnce(CompileMode mode) {
    List<Object> emitted = new ArrayList<>();
    Sink first =
        v -> {
          emitted.add(v);
          return false;
        };
    Node root = reduce(comma(c(1), c(2)), comma(c(0), c(10)), pair());
    assertThat(compile(root, mode).run(new RunState(), new Object[0], new Object(), first)).as("run's answer after the sink stopped the reduce").isFalse();
    assertThat(emitted).as("results before the stop").containsExactly(Arrays.asList(Arrays.asList(0, 1), 2));
    emitted.clear();
    Node each = foreach(comma(c(1), c(2)), c(0), new Var(AT, X));
    assertThat(compile(each, mode).run(new RunState(), new Object[0], new Object(), first)).as("run's answer after the sink stopped the foreach").isFalse();
    assertThat(emitted).as("results before the foreach's stop").containsExactly(1);
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void aRetriedAlternativeRunsTheUpdateOnWhatTheFailedOneLeft(CompileMode mode) {
    // reduce ([1],[2]) as [$a] ?// $a (0; if $a == 2 then error("x") else .+$a end) gives [2] (reduce/altbind-no-rollback):
    // the first alternative's Advance took the state and its update raised, so the second alternative's update runs on
    // null, not on the state before. Here the source has one value, the first update raises and the second records
    // the state it got.
    int value = 3;
    int own1 = 4;
    int own2 = 5;
    int group = 6;
    List<Object> inputs = new ArrayList<>();
    Intrinsic update =
        call(
            "update",
            (Fn0)
                (state, in) -> {
                  inputs.add(in);
                  if (inputs.size() == 1) {
                    throw new FilterException("x");
                  }
                  return "after";
                });
    Alternative first = new Alternative(new Bind(AT, new Var(AT, value), own1, new Identity(AT)), List.of(own1));
    Alternative second = new Alternative(new Bind(AT, new Var(AT, value), own2, new Identity(AT)), List.of(own2));
    AltBind chain = new AltBind(AT, c("v"), value, List.of(first, second), List.of(group), new Advance(AT, STATE, update));
    Node root = new Reduce(AT, c("before"), STATE, chain);
    assertThat(results(root, mode)).as("results of reduce v as a ?// a (before; update)").containsExactly("after");
    assertThat(inputs).as("the states each alternative's update got").containsExactly("before", null);
  }

  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void noneOfTheThreeTicks(CompileMode mode) {
    // ADR-0041: only the source's producer ticks, one step per element of a Many.
    Object a = new Object();
    Object b = new Object();
    Object d = new Object();
    Node reduce = reduce(over(a, b, d), c(0), new Var(AT, X));
    RunState three = running(3);
    assertThat(results(reduce, mode, three, new Object())).as("results of reduce over(a,b,d) with 3 steps").containsExactly(d);
    assertThat(three.steps()).as("steps of the reduce").isEqualTo(3L);
    three.end();
    Termination t = assertThatExceptionOfType(Termination.class).as("reduce over(a,b,d) with 2 steps").isThrownBy(() -> results(reduce, mode, running(2), new Object())).actual();
    assertThat(t.budget()).as("budget of the reduce with 2 steps").isEqualTo(Termination.Budget.STEPS);

    Node foreach = foreach(over(a, b, d), c(0), new Var(AT, X), new Identity(AT));
    RunState again = running(3);
    assertThat(results(foreach, mode, again, new Object())).as("results of foreach over(a,b,d) with 3 steps").containsExactly(a, b, d);
    again.end();
    List<Object> before = new ArrayList<>();
    assertThatExceptionOfType(Termination.class)
        .as("foreach over(a,b,d) with 2 steps")
        .isThrownBy(
            () ->
                compile(foreach, mode)
                    .run(
                        running(2),
                        new Object[0],
                        new Object(),
                        v -> {
                          before.add(v);
                          return true;
                        }));
    assertThat(before).as("results of the foreach before its third tick").containsExactly(a, b);
  }
}
