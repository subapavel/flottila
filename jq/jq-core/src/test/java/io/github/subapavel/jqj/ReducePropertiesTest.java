package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.read;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqValue;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Seeded property (docs/agents/environment.md): on a non-empty array of integers, the last state a foreach emits is the
 * state the same reduce emits, {@code [foreach .[] as $x (0; .+$x)] | .[-1]} equals {@code reduce .[] as $x (0;
 * .+$x)}, and both are the integers' sum. The arrays are not empty because jq-ref 1.8.2 differs there (observed
 * 2026-11-13 on []: the foreach side gives null, the reduce side 0), which the witness pins.
 */
class ReducePropertiesTest {

  private static final String FOREACH = "[foreach .[] as $x (0; .+$x)] | .[-1]";

  private static final String REDUCE = "reduce .[] as $x (0; .+$x)";

  static LongStream seeds() {
    return LongStream.range(0, 200);
  }

  /** The one result text of {@code filter} on {@code json}; asserts that the run completes with exactly one. */
  private static String result(String filter, String json) {
    JqEngine engine = JqEngine.builder().build();
    Workspace workspace = engine.newWorkspace();
    JqValue in = read(workspace, json.getBytes(UTF_8)).root();
    Results all = new Results();
    assertThat(engine.compile(filter).run(in, NONE, all, workspace)).as("run's answer for %s on %s", filter, json).isTrue();
    List<String> texts = all.values.stream().map(JqValue::toJson).toList();
    assertThat(texts).as("results of %s on %s", filter, json).hasSize(1);
    return texts.getFirst();
  }

  @ParameterizedTest
  @MethodSource("seeds")
  void theLastForeachStateIsTheReduceState(long seed) {
    SplittableRandom r = new SplittableRandom(seed);
    List<String> items = new ArrayList<>();
    long sum = 0;
    for (int i = 1 + r.nextInt(20); i > 0; i--) {
      int v = r.nextInt(-1000, 1001);
      items.add(Integer.toString(v));
      sum += v;
    }
    String json = "[" + String.join(",", items) + "]";
    String reduced = result(REDUCE, json);
    assertThat(result(FOREACH, json)).as("last foreach state on %s", json).isEqualTo(reduced);
    assertThat(reduced).as("reduce state on %s", json).isEqualTo(Long.toString(sum));
  }

  @Test
  void theEmptyArrayIsTheExceptionJqRefShows() {
    assertThat(result(FOREACH, "[]")).as("last foreach state on []").isEqualTo("null");
    assertThat(result(REDUCE, "[]")).as("reduce state on []").isEqualTo("0");
  }
}
