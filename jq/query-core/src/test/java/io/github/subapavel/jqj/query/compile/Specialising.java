package io.github.subapavel.jqj.query.compile;

import io.github.subapavel.jqj.query.compile.RandomParts.Outcome;
import io.github.subapavel.jqj.query.ir.Call;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.If;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.ir.ParamCall;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.run.CallForm;
import io.github.subapavel.jqj.query.run.Collector;
import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.Fn1;
import io.github.subapavel.jqj.query.run.Fn2;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Termination;
import io.github.subapavel.jqj.query.run.Truth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The fixtures of #117's query-core tests (ADR-0045 "Amendment (ticket #117)"): a toy format (founding §14) whose
 * numbers are {@link N}, operations on them, nodes of a known count, a countdown function of one closure parameter, and
 * a run of an executable that keeps its results, its error or its termination and its steps (RandomParts' {@link
 * Outcome}).
 *
 * <p>Seams the tests assume (named in the #117 red's hand-back): {@code Compiler.compile(Program, int parameters,
 * CompileMode, int cap)}, package-private, the compile with a test-only specialisation cap, {@code compile(Program,
 * int, CompileMode)} being it with {@code Specialiser.SPECIALISE_CAP}; and {@code Executable.frozen()}, a {@code Frozen}
 * record with {@code program()}, the specialised program, {@code origin()}, an {@code int[]} by function place whose
 * entry for a copy is the place of the original it copies, {@code specialised()}, the nodes the built copies hold, and
 * {@code refused()}, the keys refused.
 */
final class Specialising {
  private Specialising() {}

  /** A toy number: equal by value, and each {@code new} one an identity of its own, as two literals of the source language are (S3). */
  record N(long v) {}

  /** A number is true when above zero, so a countdown's If stops at 0; any other value as the source language's truth. */
  static final Truth TRUTH = v -> v instanceof N n ? n.v() > 0 : v != null && v != Boolean.FALSE;

  /** {@code a + b}. */
  static final Operation PLUS = new Operation("plus", (Fn2) (state, in, a, b) -> new N(((N) a).v() + ((N) b).v()));

  /** {@code a < b}, a Boolean. */
  static final Operation LESS = new Operation("less", (Fn2) (state, in, a, b) -> ((N) a).v() < ((N) b).v());

  /** {@code . - 1}. */
  static final Operation DEC = new Operation("dec", (Fn0) (state, in) -> new N(((N) in).v() - 1));

  /** Its argument, unchanged: two nodes with an Identity argument. */
  static final Operation TAG = new Operation("tag", (Fn1) (state, in, a) -> a);

  /** No result. */
  static final Operation EMPTY = new Operation("empty", (Fn0) (state, in) -> CallForm.NONE);

  /** An array as a list. */
  static final Collector LIST = (elements, length) -> List.of(Arrays.copyOf(elements, length));

  /** The one-byte span at {@code start}. */
  static Source at(int start) {
    return new Source(start, start + 1);
  }

  /**
   * A tree of exactly {@code n} nodes at {@code s} that gives its input: an Identity, a TAG of one, or a Pipe of one of
   * those and a smaller tree.
   */
  static Node nodes(int n, Source s) {
    if (n == 1) return new Identity(s);
    if (n == 2) return new Intrinsic(s, TAG, List.of(new Identity(s)));
    if (n % 2 == 1) return new Pipe(s, new Identity(s), nodes(n - 2, s));
    return new Pipe(s, new Intrinsic(s, TAG, List.of(new Identity(s))), nodes(n - 3, s));
  }

  /**
   * {@code if . > 0 then f, (dec | callee(f)) else . end}, f closure parameter 0 passed on bare: 9 nodes, so its copy
   * for an argument of k nodes holds 7 + k (the ParamCall becomes the argument, the bare one leaves the Call).
   */
  static Node countdown(int callee, Source s) {
    Node again = new Pipe(s, new Intrinsic(s, DEC, List.of()), new Call(s, callee, List.of(new ParamCall(s, 0)), List.of()));
    return new If(s, new Identity(s), new Comma(s, new ParamCall(s, 0), again), new Identity(s), TRUTH);
  }

  /** {@link #run(Executable, Object, long, long)} with no budget. */
  static Outcome run(Executable executable, Object in) {
    return run(executable, in, Long.MAX_VALUE, Long.MAX_VALUE);
  }

  /**
   * Runs {@code executable} on {@code in} with the step budget {@code steps} and the recursion-depth budget {@code
   * depth}, on the Java stack, the sink never stopping: its results, its answer, its error ({@code ["error", value]})
   * or termination ({@code ["termination", budget]}), and its steps.
   */
  static Outcome run(Executable executable, Object in, long steps, long depth) {
    RunState state = new RunState();
    state.begin(steps, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, depth);
    List<Object> results = new ArrayList<>();
    Boolean answer = null;
    Object thrown = null;
    try {
      answer =
          executable.run(
              state,
              in,
              v -> {
                results.add(v);
                return true;
              });
    } catch (FilterException e) {
      thrown = List.of("error", e.value());
    } catch (Termination t) {
      thrown = List.of("termination", String.valueOf(t.budget()));
    } finally {
      state.end();
    }
    return new Outcome(List.copyOf(results), answer, thrown, state.steps(), state.built());
  }
}
