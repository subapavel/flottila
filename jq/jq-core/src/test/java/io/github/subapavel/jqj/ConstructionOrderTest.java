package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.value.JqNull;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The results of an object construction are the lexicographic product of its pairs' results, the first pair the
 * outermost loop, and each pair's results its keys' results outside its values' (ADR-0034 amendment, GLOSSARY "Key
 * list"); an array construction collects its body's results in order. Each seed builds an object of 0 to 3 pairs whose
 * keys and values have 0 to 3 results each, a key spelled as a name or computed, and the same object inside
 * {@code [...]}. The expected text is the product written out; it was checked against jq-ref 1.8.2 for every seed here
 * (2026-11-12, {@code jq-ref -c <filter>} on null: the same lines, rc 0), and the worked examples below are jq-ref's
 * recordings in diff/construct.rec.
 */
class ConstructionOrderTest {

  @Test
  void theFirstPairIsTheOutermostLoopAndEachKeyIsOutsideItsValue() {
    // construct/object-pairs-product, construct/object-key-outside-value, construct/array-of-commas.
    assertThat(printed("{a:(1,2),b:(3,4)}")).as("{a:(1,2),b:(3,4)}").isEqualTo("{\"a\":1,\"b\":3}\n{\"a\":1,\"b\":4}\n{\"a\":2,\"b\":3}\n{\"a\":2,\"b\":4}\n");
    assertThat(printed("{(\"a\",\"b\"):(1,2)}")).as("{(\"a\",\"b\"):(1,2)}").isEqualTo("{\"a\":1}\n{\"a\":2}\n{\"b\":1}\n{\"b\":2}\n");
    assertThat(printed("[(1,2),(3,4)]")).as("[(1,2),(3,4)]").isEqualTo("[1,2,3,4]\n");
  }

  @Test
  void aRepeatedKeyKeepsItsFirstPlaceAndItsLastValue() {
    // construct/object-repeated-key, construct/object-repeated-key-keeps-first-place, construct/object-insertion-order.
    assertThat(printed("{a:(1,2),a:3}")).as("{a:(1,2),a:3}").isEqualTo("{\"a\":3}\n{\"a\":3}\n");
    assertThat(printed("{a:1,b:2,a:3}")).as("{a:1,b:2,a:3}").isEqualTo("{\"a\":3,\"b\":2}\n");
    assertThat(printed("{b:1,a:2}")).as("{b:1,a:2}").isEqualTo("{\"b\":1,\"a\":2}\n");
  }

  @Test
  void aPairWithoutResultsGivesNoObjectAndAnEmptyBodyAnEmptyArray() {
    // construct/object-empty-value, construct/array-empty-body; jq.test line 31 ({} on null) and 35 ([] on null).
    assertThat(printed("{a:1,b:empty}")).as("{a:1,b:empty}").isEmpty();
    assertThat(printed("[empty]")).as("[empty]").isEqualTo("[]\n");
    assertThat(printed("{}")).as("{}").isEqualTo("{}\n");
    assertThat(printed("[]")).as("[]").isEqualTo("[]\n");
  }

  /** CODING_STANDARDS: one case per seed, the seed in the case name. */
  static LongStream seeds() {
    return LongStream.range(0, 64);
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void objectResultsAreTheProductOfThePairsResults(long seed) {
    Generated g = generate(seed);
    assertThat(printed(g.filter())).as("seed %d: %s", seed, g.filter()).isEqualTo(lines(g.objects()));
    String collected = "[" + g.filter() + "]";
    assertThat(printed(collected)).as("seed %d: %s", seed, collected).isEqualTo("[" + String.join(",", g.objects()) + "]\n");
  }

  /** A generated object construction and the compact text of each of its results, in order. */
  record Generated(String filter, List<String> objects) {}

  /**
   * Pair i has keys {@code "k<i><j>"} and values {@code 10 i + j}, j from 0 to its cardinality - 1, so no two pairs
   * share a key. A key with one result is written as a name or computed; none is {@code (empty)}.
   */
  static Generated generate(long seed) {
    RandomGenerator random = new SplittableRandom(seed);
    int pairs = random.nextInt(0, 4);
    List<String> parts = new ArrayList<>();
    List<List<String[]>> pairResults = new ArrayList<>();
    for (int i = 0; i < pairs; i++) {
      int keys = random.nextInt(0, 4);
      int values = random.nextInt(0, 4);
      List<String> keyTexts = new ArrayList<>();
      for (int j = 0; j < keys; j++) keyTexts.add("k" + i + j);
      List<String> valueTexts = new ArrayList<>();
      for (int j = 0; j < values; j++) valueTexts.add(Integer.toString(10 * i + j));
      String key =
          keys == 0
              ? "(empty)"
              : keys == 1 && random.nextBoolean() ? keyTexts.getFirst() : keyTexts.stream().map(k -> "\"" + k + "\"").collect(Collectors.joining(",", "(", ")"));
      String value = values == 0 ? "empty" : "(" + String.join(",", valueTexts) + ")";
      parts.add(key + ":" + value);
      List<String[]> results = new ArrayList<>();
      for (String k : keyTexts) {
        for (String v : valueTexts) results.add(new String[] {k, v});
      }
      pairResults.add(results);
    }
    List<String> objects = new ArrayList<>();
    product(pairResults, 0, new ArrayList<>(), objects);
    return new Generated("{" + String.join(", ", parts) + "}", objects);
  }

  /** The objects of every choice of one result per pair, pair 0 varying slowest. */
  private static void product(List<List<String[]>> pairs, int i, List<String[]> chosen, List<String> out) {
    if (i == pairs.size()) {
      out.add(chosen.stream().map(p -> "\"" + p[0] + "\":" + p[1]).collect(Collectors.joining(",", "{", "}")));
      return;
    }
    for (String[] r : pairs.get(i)) {
      chosen.add(r);
      product(pairs, i + 1, chosen, out);
      chosen.removeLast();
    }
  }

  private static String lines(List<String> values) {
    StringBuilder text = new StringBuilder();
    for (String v : values) text.append(v).append('\n');
    return text.toString();
  }

  /** What the compact writer prints for every result of {@code filter} on null. */
  private static String printed(String filter) {
    JqEngine engine = JqEngine.builder().build();
    Workspace workspace = engine.newWorkspace();
    RecordingTarget target = new RecordingTarget();
    assertThat(engine.compile(filter).run(JqNull.NULL, NONE, JsonWriter.sink(workspace, target, OutputOptions.compact()), workspace))
        .as("run's answer for %s", filter)
        .isTrue();
    return target.text();
  }
}
