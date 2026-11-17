package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.DeepRuns.assertJqError;
import static io.github.subapavel.jqj.DeepRuns.assertResult;
import static io.github.subapavel.jqj.DeepRuns.onSmallStack;
import static io.github.subapavel.jqj.DeepRuns.value;
import static io.github.subapavel.jqj.DeepRuns.wrap;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqValue;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * jq's object merge depth (ADR-0029, GLOSSARY "Object merge", design approval K3): {@code *} merges again where both
 * values of a key are objects, and past 10 000 levels of that recursion raises the catchable "Object merge too deep".
 * The chains are {@code {"a": …}} built by repeated runs (DeepRuns) around {} or 1; each merge runs on a 256 KiB stack,
 * so jq's error, never a StackOverflowError, shows the explicit stack. The expected results are jq-ref 1.8.2's
 * recordings in diff/arith-depth.rec, the reduce-merge-* cases, which build the same chains with reduce (required by
 * #69): around {} 10 000 levels merge and 10 001 raise, around 1 10 001 merge and 10 002 raise.
 */
class ObjectMergeDepthTest {
  private static final String MERGE = "Object merge too deep";

  @Test
  void aChainAroundAnEmptyObjectMergesUpTo10000Levels() {
    JqValue at10000 = wrap(value("{}"), "{a:", "}", 10_000);
    assertResult(onSmallStack("(. * .) | .b", at10000), "null", "(. * .) | .b on 10 000 levels around {}");
    Object merged = onSmallStack(". * .", at10000);
    assertThat(merged).as("outcome of . * . on 10 000 levels").isInstanceOf(List.class);
    // A value merged with itself is itself: 10 000 objects with the one key a, then {}.
    JqValue v = (JqValue) ((List<?>) merged).getFirst();
    for (int level = 0; level < 10_000; level++) {
      assertThat(v).as("level %d of the merge", level).isInstanceOf(JqObject.class);
      assertThat(((JqObject) v).size()).as("keys at level %d", level).isEqualTo(1);
      v = ((JqObject) v).get("a");
    }
    assertThat(v.toJson()).as("the innermost value of the merge").isEqualTo("{}");

    JqValue at10001 = wrap(at10000, "{a:", "}", 1);
    assertJqError(onSmallStack("(. * .) | .b", at10001), MERGE, "(. * .) | .b on 10 001 levels around {}");
  }

  @Test
  void aChainAroundANumberMergesUpTo10001Levels() {
    // The innermost pair is not two objects, so it needs no merge level of its own.
    JqValue at10001 = wrap(value("1"), "{a:", "}", 10_001);
    assertResult(onSmallStack("(. * .) | .b", at10001), "null", "(. * .) | .b on 10 001 levels around 1");
    JqValue at10002 = wrap(at10001, "{a:", "}", 1);
    assertJqError(onSmallStack("(. * .) | .b", at10002), MERGE, "(. * .) | .b on 10 002 levels around 1");
  }

  @Test
  void aMillionLevelsGiveJqsErrorOnASmallStack() {
    // reduce-merge-million.
    JqValue million = wrap(value("{}"), "{a:", "}", 1_000_000);
    assertJqError(onSmallStack("(. * .) | .b", million), MERGE, "(. * .) | .b on 10^6 levels");
  }

  @Test
  void onlyTheMergeRecursionCountsNotTheDepthOfTheValues() {
    // reduce-merge-shallow-right-10001 gives 1: the right side's a is no object, so nothing merges below it;
    // reduce-merge-empty-left-10002 gives null: {} has no a, so the deep value enters unmerged.
    JqValue at10001 = wrap(value("{}"), "{a:", "}", 10_001);
    assertResult(onSmallStack("(. * {a:1}) | .a", at10001), "1", "(. * {a:1}) | .a on 10 001 levels");
    JqValue at10002 = wrap(at10001, "{a:", "}", 1);
    assertResult(onSmallStack("({} * .) | .b", at10002), "null", "({} * .) | .b on 10 002 levels");
  }
}
