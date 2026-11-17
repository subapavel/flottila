package io.github.subapavel.jqj.query.compile;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.query.ir.Binds;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.If;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.run.CallForm;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.FnN;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Truth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A Binds is a tail emitter only when its values and its body all are (ADR-0043), over plain strings (founding §14): a
 * Binds with a Comma for its body or for a value gives two results, so as a Binds value or an If condition it runs the
 * rest once per result, as the nested chain does, in both compile modes. And a tail value with no result ends the
 * Binds, even after a tail value with one.
 *
 * <p>F-85 (#194): a holder answers its one result with a stop, so a tail run's false means a result came and its true
 * means none (ADR-0043 as amended); the If or Binds that owns the holder takes the result and does not pass that false
 * on (ADR-0039 as amended). The result's value, a Java null included, tells nothing about whether one came.
 */
class TailEmitterTest {
  private static final Source AT = new Source(0, 1);

  private static final Truth ALL = v -> true;

  /** The truth that holds only for a Java null: the branch it picks shows the condition's result was that null. */
  private static final Truth IS_NULL = v -> v == null;

  /** The results of one run into a sink that never stops, and the run's answer. */
  private record Ran(List<Object> results, boolean ranOut) {}

  /** A one-result call with no result. */
  private static Intrinsic nothing() {
    return new Intrinsic(AT, new Operation("nothing", (Fn0) (state, in) -> CallForm.NONE), List.of());
  }

  /** As {@link #list}, for arguments that may be a Java null. */
  private static Intrinsic nullableList(Node... args) {
    FnN all = (state, in, a) -> Arrays.asList(a.clone());
    return new Intrinsic(AT, new Operation("list", all), List.of(args));
  }

  private static Ran ran(Node root, CompileMode mode) {
    List<Object> emitted = new ArrayList<>();
    boolean ranOut =
        Compiler.compile(root, mode)
            .run(
                new RunState(),
                "in",
                v -> {
                  emitted.add(v);
                  return true;
                });
    return new Ran(emitted, ranOut);
  }

  /** {@code "a" as $1 | "b" as $2 | ($1, $2)}: tail values, a generator body. */
  private static Binds pair(int first, int second) {
    return new Binds(AT, List.of(new Const(AT, "a"), new Const(AT, "b")), List.of(first, second), new Comma(AT, new Var(AT, first), new Var(AT, second)));
  }

  private static Intrinsic list(Node... args) {
    FnN all = (state, in, a) -> List.of(a);
    return new Intrinsic(AT, new Operation("list", all), List.of(args));
  }

  private static List<Object> results(Node root, CompileMode mode) {
    List<Object> emitted = new ArrayList<>();
    Compiler.compile(root, mode)
        .run(
            new RunState(),
            "in",
            v -> {
              emitted.add(v);
              return true;
            });
    return emitted;
  }

  @Test
  void aBindsWhoseBodyIsAGeneratorRunsTheRestPerResult() {
    // (pair) as $0 | "c" as $3 | list($0; $3)
    for (CompileMode mode : CompileMode.values()) {
      Binds value = new Binds(AT, List.of(pair(1, 2), new Const(AT, "c")), List.of(0, 3), list(new Var(AT, 0), new Var(AT, 3)));
      assertThat(results(value, mode)).as("%s: results of a Binds whose first value is the pair", mode).containsExactly(List.of("a", "c"), List.of("b", "c"));
      // if (pair) then list(.) else "no" end
      If cond = new If(AT, pair(1, 2), list(new Const(AT, "then")), new Const(AT, "no"), ALL);
      assertThat(results(cond, mode)).as("%s: results of an If on the pair", mode).containsExactly(List.of("then"), List.of("then"));
    }
  }

  @Test
  void aBindsWhoseValueIsAGeneratorRunsTheRestPerResult() {
    for (CompileMode mode : CompileMode.values()) {
      // ("a", "b") as $1 | "c" as $2 | list($1; $2): a generator value, a tail body.
      Binds generator =
          new Binds(AT, List.of(new Comma(AT, new Const(AT, "a"), new Const(AT, "b")), new Const(AT, "c")), List.of(1, 2), list(new Var(AT, 1), new Var(AT, 2)));
      Binds value = new Binds(AT, List.of(generator, new Const(AT, "d")), List.of(0, 3), list(new Var(AT, 0), new Var(AT, 3)));
      assertThat(results(value, mode))
          .as("%s: results of a Binds whose first value is the Binds of a generator value", mode)
          .containsExactly(List.of(List.of("a", "c"), "d"), List.of(List.of("b", "c"), "d"));
      Binds again =
          new Binds(AT, List.of(new Comma(AT, new Const(AT, "a"), new Const(AT, "b")), new Const(AT, "c")), List.of(1, 2), list(new Var(AT, 1), new Var(AT, 2)));
      If cond = new If(AT, again, list(new Const(AT, "then")), new Const(AT, "no"), ALL);
      assertThat(results(cond, mode)).as("%s: results of an If on the Binds of a generator value", mode).containsExactly(List.of("then"), List.of("then"));
    }
  }

  @Test
  void aTailValueWithNoResultAfterOneWithAResultEndsTheBinds() {
    for (CompileMode mode : CompileMode.values()) {
      // "a" as $1 | nothing as $2 | "c" as $3 | list($1; $2; $3): the second value gives no result.
      Intrinsic nothing = new Intrinsic(AT, new Operation("nothing", (Fn0) (state, in) -> CallForm.NONE), List.of());
      Binds binds = new Binds(AT, List.of(new Const(AT, "a"), nothing, new Const(AT, "c")), List.of(1, 2, 3), list(new Var(AT, 1), new Var(AT, 2), new Var(AT, 3)));
      assertThat(results(binds, mode)).as("%s: results of a Binds whose second tail value gives none", mode).isEmpty();
    }
  }

  @Test
  void aJavaNullIsAResultOfATailCondition() {
    for (CompileMode mode : CompileMode.values()) {
      // if null then "then" else "else" end, on a truth that holds only for null.
      If cond = new If(AT, new Const(AT, null), new Const(AT, "then"), new Const(AT, "else"), IS_NULL);
      Ran ran = ran(cond, mode);
      assertThat(ran.results()).as("%s: results of an If on a tail condition giving a Java null", mode).containsExactly("then");
      assertThat(ran.ranOut()).as("%s: answer of that If", mode).isTrue();
    }
  }

  @Test
  void aJavaNullIsAResultOfATailValue() {
    for (CompileMode mode : CompileMode.values()) {
      // null as $1 | "b" as $2 | list($1; $2)
      Binds binds = new Binds(AT, List.of(new Const(AT, null), new Const(AT, "b")), List.of(1, 2), nullableList(new Var(AT, 1), new Var(AT, 2)));
      Ran ran = ran(binds, mode);
      assertThat(ran.results()).as("%s: results of a Binds whose first tail value gives a Java null", mode).containsExactly(Arrays.asList(null, "b"));
      assertThat(ran.ranOut()).as("%s: answer of that Binds", mode).isTrue();
    }
  }

  @Test
  void aTailConditionWithNoResultGivesNoneAndDoesNotStop() {
    for (CompileMode mode : CompileMode.values()) {
      // if nothing then "then" else "else" end
      Ran ran = ran(new If(AT, nothing(), new Const(AT, "then"), new Const(AT, "else"), ALL), mode);
      assertThat(ran.results()).as("%s: results of an If whose tail condition gives none", mode).isEmpty();
      assertThat(ran.ranOut()).as("%s: answer of that If: it ran out", mode).isTrue();
    }
  }

  @Test
  void aBindsWithATailValueOfNoResultRunsOut() {
    for (CompileMode mode : CompileMode.values()) {
      // nothing as $1 | "b" as $2 | list($1; $2): the first value gives none.
      Ran first = ran(new Binds(AT, List.of(nothing(), new Const(AT, "b")), List.of(1, 2), list(new Var(AT, 1), new Var(AT, 2))), mode);
      assertThat(first.results()).as("%s: results of a Binds whose first tail value gives none", mode).isEmpty();
      assertThat(first.ranOut()).as("%s: answer of that Binds: it ran out", mode).isTrue();
      // "a" as $1 | nothing as $2 | list($1; $2): a later value gives none, after one with a result.
      Ran later = ran(new Binds(AT, List.of(new Const(AT, "a"), nothing()), List.of(1, 2), list(new Var(AT, 1), new Var(AT, 2))), mode);
      assertThat(later.results()).as("%s: results of a Binds whose second tail value gives none", mode).isEmpty();
      assertThat(later.ranOut()).as("%s: answer of that Binds: it ran out", mode).isTrue();
    }
  }

  /** The kill of a no-result tail run answered as a stop: the right side of the Comma would not run. */
  @Test
  void aTailIfOrBindsWithNoResultLetsTheRightSideOfACommaRun() {
    for (CompileMode mode : CompileMode.values()) {
      // (if nothing then "then" else "else" end), "right"
      If cond = new If(AT, nothing(), new Const(AT, "then"), new Const(AT, "else"), ALL);
      Ran afterIf = ran(new Comma(AT, cond, new Const(AT, "right")), mode);
      assertThat(afterIf.results()).as("%s: results of a Comma whose left side is an If on a tail condition of no result", mode).containsExactly("right");
      assertThat(afterIf.ranOut()).as("%s: answer of that Comma", mode).isTrue();
      // ("a" as $1 | nothing as $2 | list($1; $2)), "right"
      Binds binds = new Binds(AT, List.of(new Const(AT, "a"), nothing()), List.of(1, 2), list(new Var(AT, 1), new Var(AT, 2)));
      Ran afterBinds = ran(new Comma(AT, binds, new Const(AT, "right")), mode);
      assertThat(afterBinds.results()).as("%s: results of a Comma whose left side is a Binds with a tail value of no result", mode).containsExactly("right");
      assertThat(afterBinds.ranOut()).as("%s: answer of that Comma", mode).isTrue();
    }
  }

  /** The holder's stop ends at the If or Binds that owns it: a result taken lets the right side of a Comma run. */
  @Test
  void aHoldersStopEndsAtItsIfAndItsBinds() {
    for (CompileMode mode : CompileMode.values()) {
      // (if "x" then "then" else "else" end), "right"
      If cond = new If(AT, new Const(AT, "x"), new Const(AT, "then"), new Const(AT, "else"), ALL);
      Ran afterIf = ran(new Comma(AT, cond, new Const(AT, "right")), mode);
      assertThat(afterIf.results()).as("%s: results of a Comma whose left side is an If on a tail condition of one result", mode).containsExactly("then", "right");
      assertThat(afterIf.ranOut()).as("%s: answer of that Comma", mode).isTrue();
      // ("a" as $1 | "b" as $2 | list($1; $2)), "right"
      Binds binds = new Binds(AT, List.of(new Const(AT, "a"), new Const(AT, "b")), List.of(1, 2), list(new Var(AT, 1), new Var(AT, 2)));
      Ran afterBinds = ran(new Comma(AT, binds, new Const(AT, "right")), mode);
      assertThat(afterBinds.results()).as("%s: results of a Comma whose left side is a Binds of tail values", mode).containsExactly(List.of("a", "b"), "right");
      assertThat(afterBinds.ranOut()).as("%s: answer of that Comma", mode).isTrue();
    }
  }
}
