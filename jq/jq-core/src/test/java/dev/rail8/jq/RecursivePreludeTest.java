package dev.rail8.jq;

import static dev.rail8.jq.CallRuns.STACK;
import static dev.rail8.jq.CallRuns.onStack;
import static dev.rail8.jq.CallRuns.texts;
import static dev.rail8.jq.RunBudgets.assertBudget;
import static dev.rail8.jq.RunBudgets.recursionDepth;
import static org.assertj.core.api.Assertions.assertThat;

import dev.rail8.jq.JqTermination.Budget;
import dev.rail8.jq.query.compile.CompileMode;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code ..} and recurse at the public API (ADR-0042 amendment for #110, ADR-0045, ADR-0046): {@code ..} is recurse/0,
 * which runs recurse/1's lifted r with the closure {@code .[]?}, so its results are those of the same recursion written
 * by the user, which #107 runs (diff/calls.rec); and each level holds two call activations, r's Call and the call
 * activation of its closure. Each run is on a thread of {@link CallRuns#STACK}.
 */
class RecursivePreludeTest {

  /** The user-written recursion every other form is compared with: #107's Calls, no prelude. */
  private static final String USER = "[def r: ., (.[]?|r); r]";

  /** The three prelude forms of the same recursion: {@code ..}, recurse/0, and recurse/2 with a condition always true. */
  private static final List<String> PRELUDE = List.of("[..]", "[recurse]", "[recurse(.[]?;true)]");

  /**
   * Fixed inputs that cross the boundaries (scalars, empty containers, a single element, a repeated key, escape
   * sequences, a number no double holds, five levels), then 64 seeded documents, under both compile modes.
   */
  static Stream<Arguments> documents() {
    List<String> inputs =
        new ArrayList<>(
            List.of(
                "null", "1", "\"a\\\"b\\u00e9\"", "[]", "{}", "[1]", "{\"a\":1,\"b\":2,\"a\":{\"c\":3}}", "100000000000000000000000000001",
                "[[[[[]]]]]", "[1,[[2]],{ \"a\":[1]}]"));
    for (int seed = 0; seed < 64; seed++) inputs.add(document(seed));
    List<Arguments> out = new ArrayList<>();
    for (CompileMode mode : CompileMode.values()) {
      for (String input : inputs) out.add(Arguments.of(mode, input));
    }
    return out.stream();
  }

  /**
   * The design's property: {@code [..]} = {@code [recurse]} = {@code [recurse(.[]?;true)]} = {@code [def r: ., (.[]?|r);
   * r]}, on each document and in each compile mode. jq.test's case at line 187 anchors the user-written side to jq-ref:
   * {@code [..]} on {@code [1,[[2]],{ "a":[1]}]} gives {@code [[1,[[2]],{"a":[1]}],1,[[2]],[2],2,{"a":[1]},[1],1]}
   * (jq-ref 1.8.2, observed 2026-11-15, .logs/110-jqref-observe.log).
   */
  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("documents")
  void dotDotEqualsUserRecursion(CompileMode mode, String json) {
    onStack(
        STACK,
        () -> {
          JqEngine engine = JqEngine.builder().compileMode(mode).build();
          Workspace workspace = engine.newWorkspace();
          List<String> expected = texts(engine.compile(USER), workspace, json);
          assertThat(expected).as("results of %s on %s", USER, json).hasSize(1);
          if (json.equals("[1,[[2]],{ \"a\":[1]}]")) {
            assertThat(expected).as("results of %s on jq.test's input", USER).containsExactly("[[1,[[2]],{\"a\":[1]}],1,[[2]],[2],2,{\"a\":[1]},[1],1]");
          }
          for (String filter : PRELUDE) {
            assertThat(texts(engine.compile(filter), workspace, json)).as("results of %s on %s, %s", filter, json, mode).isEqualTo(expected);
          }
        });
  }

  /**
   * h levels of nested arrays, the innermost empty, with {@code [..]}'s results there as jq-ref 1.8.2 gives them
   * (observed 2026-11-15, .logs/110-jqref-observe.log, and diff/rec-prelude.rec's dot-dot-100-deep for 100 levels under
   * {@code [..]|length}); a scalar is one level too.
   */
  static Stream<Arguments> levels() {
    return Stream.of(
        Arguments.of(1, "[..]", "[]", "[[]]"),
        Arguments.of(1, "[..]", "1", "[1]"),
        Arguments.of(5, "[..]", "[[[[[]]]]]", "[[[[[[]]]]],[[[[]]]],[[[]]],[[]],[]]"),
        Arguments.of(100, "[..]|length", "[".repeat(100) + "]".repeat(100), "100"));
  }

  /**
   * CONSERVATIVE only (specialisation will change OPTIMISED, design ruling): at its deepest, a level holds r's Call and
   * the call activation of its closure {@code .[]?}, so h levels hold 2h call activations: a depth budget of 2h runs,
   * 2h − 1 ends the run with RECURSION_DEPTH before anything is emitted.
   */
  @ParameterizedTest(name = "{0} levels: {1}")
  @MethodSource("levels")
  void dotDotHoldsTwoActivationsPerLevel(int h, String filter, String json, String result) {
    onStack(
        STACK,
        () -> {
          JqEngine fits = recursionDepth(2 * h, CompileMode.CONSERVATIVE);
          assertThat(texts(fits.compile(filter), fits.newWorkspace(), json)).as("results of %s on %d levels with a depth of %d", filter, h, 2 * h).containsExactly(result);
          JqEngine under = recursionDepth(2 * h - 1, CompileMode.CONSERVATIVE);
          JqFilter f = under.compile(filter);
          Workspace workspace = under.newWorkspace();
          assertBudget(() -> texts(f, workspace, json), Budget.RECURSION_DEPTH, filter + " on " + h + " levels with a depth of " + (2 * h - 1));
        });
  }

  /**
   * One seeded document: up to five levels of arrays and objects (keys from a, b and c, so a key may repeat), with
   * nulls, booleans, literal numbers (one no double holds, one with a trailing zero) and strings with escape sequences.
   */
  private static String document(int seed) {
    StringBuilder out = new StringBuilder();
    value(new SplittableRandom(seed), 0, out);
    return out.toString();
  }

  private static final String[] SCALARS = {
    "null", "true", "false", "0", "-1", "1.10", "1e2", "100000000000000000000000000001", "\"\"", "\"x y\"", "\"a\\\"b\"", "\"\\u00e9\\n\""
  };

  private static void value(SplittableRandom random, int depth, StringBuilder out) {
    int kind = depth >= 5 ? 0 : random.nextInt(3);
    switch (kind) {
      case 0 -> out.append(SCALARS[random.nextInt(SCALARS.length)]);
      case 1 -> {
        out.append('[');
        int n = random.nextInt(4);
        for (int i = 0; i < n; i++) {
          if (i > 0) out.append(',');
          value(random, depth + 1, out);
        }
        out.append(']');
      }
      default -> {
        out.append('{');
        int n = random.nextInt(4);
        for (int i = 0; i < n; i++) {
          if (i > 0) out.append(',');
          out.append('"').append((char) ('a' + random.nextInt(3))).append("\":");
          value(random, depth + 1, out);
        }
        out.append('}');
      }
    }
  }
}
