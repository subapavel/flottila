package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.read;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * range/2 at the public API (ADR-0041): the start itself first, so a literal keeps its form, then each value the double
 * before it plus 1, while that double is below the end's; the start is the outer loop over its bound's results; a bound
 * that is no number raises "Range bounds must be numeric" before the call's first result. Each expected text is jq-ref
 * 1.8.2's with {@code -c}, observed 2026-11-13 (recorded too in diff/reduce.rec, range2-*), on null unless an input is
 * named.
 */
class RangeTest {
  private static final JqEngine ENGINE = JqEngine.builder().build();

  /** The result texts of {@code filter} on {@code json}; asserts that the run completes. */
  private static List<String> results(String filter, String json) {
    Workspace workspace = ENGINE.newWorkspace();
    JqValue in = read(workspace, json.getBytes(UTF_8)).root();
    Results all = new Results();
    assertThat(ENGINE.compile(filter).run(in, NONE, all, workspace)).as("run's answer for %s on %s", filter, json).isTrue();
    return all.values.stream().map(JqValue::toJson).toList();
  }

  /** The error text {@code filter} raises on null, and the result texts before it into {@code before}. */
  private static String error(String filter, Results before) {
    JqFilterException e =
        assertThatExceptionOfType(JqFilterException.class).as("run of %s", filter).isThrownBy(() -> ENGINE.compile(filter).run(JqNull.NULL, NONE, before, ENGINE.newWorkspace())).actual();
    assertThat(e.value()).as("error value of %s", filter).isInstanceOf(JqString.class);
    return ((JqString) e.value()).value();
  }

  @Test
  void theFirstValueIsTheStartItselfAndTheLaterOnesComputedNumbers() {
    assertThat(results("[range(1.10;3)]", "null")).as("[range(1.10;3)]").containsExactly("[1.10,2.1]");
    assertThat(results("[range(1.0;3)]", "null")).as("[range(1.0;3)]").containsExactly("[1.0,2]");
    // A document's number keeps its form too (range2-literal-from-input-direct).
    assertThat(results("[range(.a;3)]", "{\"a\":1.10}")).as("[range(.a;3)] on {\"a\":1.10}").containsExactly("[1.10,2.1]");
  }

  @Test
  void itStopsWhenTheValueReachesTheEndAsDoubles() {
    // Both bounds are the double 1e20, though the literals differ (range2-past-double).
    assertThat(results("[range(100000000000000000001;100000000000000000003)]", "null")).as("[range(1e20 + 1; 1e20 + 3)]").containsExactly("[]");
    assertThat(results("[range(0;2.5)]", "null")).as("[range(0;2.5)]").containsExactly("[0,1,2]");
    assertThat(results("[range(-2.5;0)]", "null")).as("[range(-2.5;0)]").containsExactly("[-2.5,-1.5,-0.5]");
  }

  @Test
  void theEndIsReadOnceWhenTheRangeStarts() {
    // A document's number computes its double from its token at each read (ADR-0012), so an end read at each step
    // costs its token's length per tick. 100000 and a point and 2^20 zeros: 10^5 steps over a 1 MiB token, minutes when
    // read at each step; jq-ref gives 100000, rc 0, observed 2026-11-13.
    String end = "100000." + "0".repeat(1 << 20);
    List<String> counted =
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> results("reduce range(0; .) as $i (0; . + 1)", end), "reduce range(0; .) on a 1 MiB end");
    assertThat(counted).as("reduce range(0; .) as $i (0; . + 1) on 100000.0...0").containsExactly("100000");
  }

  @Test
  void theStartIsTheOuterLoop() {
    assertThat(results("[range(0,1;3,4)]", "null")).as("[range(0,1;3,4)]").containsExactly("[0,1,2,0,1,2,3,1,2,1,2,3]");
  }

  @Test
  void aBoundThatIsNoNumberRaisesBeforeAnyResult() {
    Results none = new Results();
    assertThat(error("range(5;\"a\")", none)).as("error of range(5;\"a\")").isEqualTo("Range bounds must be numeric");
    assertThat(error("range(null;1)", none)).as("error of range(null;1)").isEqualTo("Range bounds must be numeric");
    assertThat(none.values).as("results before the errors").isEmpty();
    // The earlier call's results stay; the failing call gives none (range2-error-before-results).
    Results before = new Results();
    assertThat(error("[range(0;3)], range(0;\"x\")", before)).as("error of [range(0;3)], range(0;\"x\")").isEqualTo("Range bounds must be numeric");
    assertThat(before.values.stream().map(JqValue::toJson).toList()).as("results before range(0;\"x\")").containsExactly("[0,1,2]");
  }
}
