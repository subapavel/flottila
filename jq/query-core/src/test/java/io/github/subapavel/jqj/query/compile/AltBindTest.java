package io.github.subapavel.jqj.query.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.query.ir.AltBind;
import io.github.subapavel.jqj.query.ir.Alternative;
import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.run.CallForm;
import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.Fn1;
import io.github.subapavel.jqj.query.run.Many;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Termination;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * AltBind at the IR seam (ADR-0038, ADR-0008 and its F-32 amendment), over a toy format whose values are plain objects
 * (founding §14): the chain sets its value slot to each result of its value and runs the alternatives in order; at each
 * result of alternative k each group slot is set from that alternative's own slot and the body runs on the chain's
 * input. A FilterException from the match, the body or downstream starts alternative k + 1 while a later one remains;
 * the last one's error propagates; an error from the value propagates; a stop or a Termination returns at once.
 * Entering each alternative after the first charges one step, the first none. The toy {@code head} takes a list's first
 * element and raises on anything else, as the language's {@code .[0]} raises on a number.
 *
 * <p>Shapes as in IrVerifierTest: {@code AltBind(Source, Node value, int slot, List<Alternative> alternatives,
 * List<Integer> slots, Node body)}, {@code Alternative(Node match, List<Integer> from)}.
 */
class AltBindTest {
  private static final Source AT = new Source(0, 1);

  /** The value slot of every chain here. */
  private static final int VALUE = 0;

  /** The group slot of every one-name chain here. */
  private static final int GROUP = 9;

  /** What {@code head} raises on a value that is not a non-empty list. */
  private static final Object MISS = new Object();

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

  private static Var var(int slot) {
    return new Var(AT, slot);
  }

  /** The first element of a non-empty list; a FilterException of {@link #MISS} on anything else. */
  private static Intrinsic head(Node arg) {
    return call(
        "head",
        (Fn1)
            (state, in, a) -> {
              if (a instanceof List<?> list && !list.isEmpty()) return list.get(0);
              throw new FilterException(MISS);
            },
        arg);
  }

  private static Intrinsic id(Node arg) {
    return call("id", (Fn1) (state, in, a) -> a, arg);
  }

  /** Its argument, after noting it in {@code ran}. */
  private static Intrinsic mark(List<Object> ran, Node arg) {
    return call(
        "mark",
        (Fn1)
            (state, in, a) -> {
              ran.add(a);
              return a;
            },
        arg);
  }

  /** {@code value}, whatever the input. */
  private static Intrinsic constant(Object value) {
    return call("constant", (Fn0) (state, in) -> value);
  }

  /** A FilterException of its input. */
  private static Intrinsic raise() {
    return call(
        "raise",
        (Fn0)
            (state, in) -> {
              throw new FilterException(in);
            });
  }

  /** Its input, or a FilterException of it when it is {@code bad}. */
  private static Intrinsic not(Object bad) {
    return call(
        "not",
        (Fn0)
            (state, in) -> {
              if (in == bad) throw new FilterException(in);
              return in;
            });
  }

  /** An operation that fails the test if it runs. */
  private static Intrinsic never() {
    return call(
        "never",
        (Fn0)
            (state, in) -> {
              throw new AssertionError("an alternative ran after a stop or a Termination");
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

  /** An alternative whose match binds {@code own} to {@code match}'s results, with {@code own} its one from-slot. */
  private static Alternative alternative(Node match, int own) {
    return new Alternative(new Bind(AT, match, own, new Identity(AT)), List.of(own));
  }

  /** {@code value as alternatives… | body}, value slot {@link #VALUE}, one group slot {@link #GROUP}. */
  private static AltBind chain(Node value, Node body, Alternative... alternatives) {
    return new AltBind(AT, value, VALUE, List.of(alternatives), List.of(GROUP), body);
  }

  private static void assertSteps(Runnable run, String what) {
    Termination t = assertThatExceptionOfType(Termination.class).as("%s", what).isThrownBy(run::run).actual();
    assertThat(t.cause()).as("cause of %s", what).isEqualTo(Termination.Cause.BUDGET);
    assertThat(t.budget()).as("budget of %s", what).isEqualTo(Termination.Budget.STEPS);
  }

  @Test
  void theFirstAlternativeThatMatchesSetsTheGroupSlotAndChargesNothing() {
    Object x = new Object();
    AltBind root = chain(new Identity(AT), var(GROUP), alternative(head(var(VALUE)), 1), alternative(id(var(VALUE)), 2));
    // A budget of 0: the first alternative charges no step.
    assertThat(results(root, running(0), List.of(x))).as("results on [x]").singleElement().isSameAs(x);
  }

  @Test
  void aMatchErrorStartsTheNextAlternativeForOneStep() {
    Object y = new Object();
    assertThat(results(chain(new Identity(AT), var(GROUP), alternative(head(var(VALUE)), 1), alternative(id(var(VALUE)), 2)), running(1), y))
        .as("results on y with 1 step")
        .singleElement()
        .isSameAs(y);
    assertSteps(() -> results(chain(new Identity(AT), var(GROUP), alternative(head(var(VALUE)), 1), alternative(id(var(VALUE)), 2)), running(0), y), "two alternatives on y with 0 steps");

    // Three alternatives, the first two failing: one step for each later alternative entered.
    assertThat(
            results(
                chain(new Identity(AT), var(GROUP), alternative(head(var(VALUE)), 1), alternative(head(var(VALUE)), 2), alternative(id(var(VALUE)), 3)),
                running(2),
                y))
        .as("results on y of three alternatives with 2 steps")
        .singleElement()
        .isSameAs(y);
    assertSteps(
        () ->
            results(
                chain(new Identity(AT), var(GROUP), alternative(head(var(VALUE)), 1), alternative(head(var(VALUE)), 2), alternative(id(var(VALUE)), 3)),
                running(1),
                y),
        "three alternatives on y with 1 step");
  }

  @Test
  void aBodyErrorStartsTheNextAlternative() {
    Object x = new Object();
    List<Object> in = List.of(x);
    AltBind root = chain(new Identity(AT), new Pipe(AT, var(GROUP), not(x)), alternative(head(var(VALUE)), 1), alternative(id(var(VALUE)), 2));
    assertThat(results(root, new RunState(), in)).as("results on [x] whose body raises on x").singleElement().isSameAs(in);
  }

  @Test
  void aDownstreamErrorStartsTheNextAlternativeAndEarlierResultsStay() {
    // The reference binary: [[1]] | (.[] as [$a] ?// $a | $a) | .[0] gives 1 (bind/alt-downstream-error), and
    // . as [$a] ?// $a | $a, ($a | .[0]) on [5] gives 5, [5], 5 (bind/alt-results-before-error-kept).
    Object x = new Object();
    List<Object> in = List.of(x);
    Pipe downstream = new Pipe(AT, chain(new Identity(AT), var(GROUP), alternative(head(var(VALUE)), 1), alternative(id(var(VALUE)), 2)), not(x));
    assertThat(results(downstream, new RunState(), in)).as("results on [x] when downstream raises on x").singleElement().isSameAs(in);

    Pipe kept = new Pipe(AT, chain(new Identity(AT), var(GROUP), alternative(head(var(VALUE)), 1), alternative(id(var(VALUE)), 2)), new Comma(AT, new Identity(AT), not(x)));
    assertThat(results(kept, new RunState(), in)).as("results on [x] of (chain) | ., not(x)").containsExactly(x, in, in);
  }

  @Test
  void theLastAlternativesErrorPropagates() {
    // From the last match.
    Object last = new Object();
    AltBind matches = chain(new Identity(AT), var(GROUP), alternative(head(var(VALUE)), 1), alternative(new Pipe(AT, constant(last), raise()), 2));
    FilterException fromMatch =
        assertThatExceptionOfType(FilterException.class).as("run of two failing matches").isThrownBy(() -> results(matches, new RunState(), new Object())).actual();
    assertThat(fromMatch.value()).as("value of the error of two failing matches").isSameAs(last);

    // From the body in the last alternative: the second alternative's error, not the first one's.
    Object x = new Object();
    List<Object> in = List.of(x);
    AltBind bodies = chain(new Identity(AT), new Pipe(AT, var(GROUP), raise()), alternative(head(var(VALUE)), 1), alternative(id(var(VALUE)), 2));
    FilterException fromBody =
        assertThatExceptionOfType(FilterException.class).as("run of a body that always raises").isThrownBy(() -> results(bodies, new RunState(), in)).actual();
    assertThat(fromBody.value()).as("value of the body's last error").isSameAs(in);
  }

  @Test
  void anErrorFromTheValuePropagates() {
    // The reference binary: (1, ("a"|.[0])) as [$a] ?// $a | $a gives 1, then the value's error
    // (bind/alt-value-error-propagates).
    Object x = new Object();
    Object fault = new Object();
    List<Object> ran = new ArrayList<>();
    List<Object> emitted = new ArrayList<>();
    AltBind root =
        chain(
            new Comma(AT, constant(List.of(x)), new Pipe(AT, constant(fault), raise())),
            var(GROUP),
            alternative(head(var(VALUE)), 1),
            alternative(mark(ran, var(VALUE)), 2));
    FilterException e =
        assertThatExceptionOfType(FilterException.class)
            .as("run of a chain whose value raises after its first result")
            .isThrownBy(
                () ->
                    compile(root)
                        .run(
                            new RunState(),
                            new Object(),
                            v -> {
                              emitted.add(v);
                              return true;
                            }))
            .actual();
    assertThat(e.value()).as("value of the error").isSameAs(fault);
    assertThat(emitted).as("results before the value's error").singleElement().isSameAs(x);
    assertThat(ran).as("values the second alternative saw").isEmpty();
  }

  @Test
  void aStopEndsItAtOnce() {
    // A stop in the first alternative: no later alternative, no step (a budget of 0).
    Object x = new Object();
    List<Object> emitted = new ArrayList<>();
    AltBind first = chain(new Identity(AT), var(GROUP), alternative(head(var(VALUE)), 1), alternative(never(), 2));
    boolean ranOut =
        compile(first)
            .run(
                running(0),
                List.of(x),
                v -> {
                  emitted.add(v);
                  return false;
                });
    assertThat(ranOut).as("run's answer after a stop in the first alternative").isFalse();
    assertThat(emitted).as("results before the stop").singleElement().isSameAs(x);

    // A stop in the second alternative: the third is not entered, so 1 step is enough.
    Object y = new Object();
    List<Object> second = new ArrayList<>();
    AltBind middle = chain(new Identity(AT), var(GROUP), alternative(head(var(VALUE)), 1), alternative(id(var(VALUE)), 2), alternative(never(), 3));
    boolean ranOutAgain =
        compile(middle)
            .run(
                running(1),
                y,
                v -> {
                  second.add(v);
                  return false;
                });
    assertThat(ranOutAgain).as("run's answer after a stop in the second alternative").isFalse();
    assertThat(second).as("results before the stop in the second alternative").singleElement().isSameAs(y);
  }

  @Test
  void aTerminationPassesThrough() {
    // From the match, the body and downstream: the same Termination leaves the run and no later alternative runs.
    for (String where : List.of("match", "body", "downstream")) {
      Termination[] thrown = new Termination[1];
      Intrinsic terminate =
          call(
              "terminate",
              (Fn0)
                  (state, in) -> {
                    thrown[0] = state.overBuilt();
                    throw thrown[0];
                  });
      List<Object> ran = new ArrayList<>();
      Alternative later = alternative(mark(ran, var(VALUE)), 2);
      Node root =
          switch (where) {
            case "match" -> chain(new Identity(AT), var(GROUP), alternative(terminate, 1), later);
            case "body" -> chain(new Identity(AT), new Pipe(AT, var(GROUP), terminate), alternative(id(var(VALUE)), 1), later);
            default -> new Pipe(AT, chain(new Identity(AT), var(GROUP), alternative(id(var(VALUE)), 1), later), terminate);
          };
      Termination t = assertThatExceptionOfType(Termination.class).as("run of a Termination from the %s", where).isThrownBy(() -> results(root, new RunState(), new Object())).actual();
      assertThat(t).as("the Termination from the %s", where).isSameAs(thrown[0]);
      assertThat(ran).as("values the second alternative saw after a Termination from the %s", where).isEmpty();
    }
  }

  @Test
  void eachResultOfAMatchSetsTheGroupSlot() {
    // The reference binary: . as {("a","b"):$v} | $v gives 1, 2 (bind/key-generator).
    Object p = new Object();
    Object q = new Object();
    AltBind root = chain(new Identity(AT), var(GROUP), alternative(call("both", over(p, q)), 1), alternative(never(), 2));
    assertThat(results(root, new RunState(), new Object())).as("results of a match with two results").containsExactly(p, q);
  }

  @Test
  void eachGroupSlotTakesItsOwnAlternativesSlot() {
    // Two names, group slots 9 and 10; the second alternative binds them in the other order (own slot 4 for the first
    // name, 3 for the second), so from = [4, 3]. A wiring by binding order would give q, p.
    Object p = new Object();
    Object q = new Object();
    Alternative first = new Alternative(new Bind(AT, head(var(VALUE)), 1, new Bind(AT, head(var(VALUE)), 2, new Identity(AT))), List.of(1, 2));
    Alternative second = new Alternative(new Bind(AT, constant(q), 3, new Bind(AT, constant(p), 4, new Identity(AT))), List.of(4, 3));
    AltBind root = new AltBind(AT, new Identity(AT), VALUE, List.of(first, second), List.of(9, 10), new Comma(AT, var(9), var(10)));
    assertThat(results(root, new RunState(), new Object())).as("results of the two group slots").containsExactly(p, q);
  }
}
