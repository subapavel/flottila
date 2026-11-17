package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.TestDocuments.read;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.stream.LongStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #34's two properties, one case per seed (docs/agents/environment.md: seeded loops, no jqwik). Documents are random
 * JSON of plain ASCII keys (some duplicated, some jq keywords) and scalars, arrays and objects. A Stub is never caught,
 * so a filter #34 does not build fails the case instead of agreeing with another Stub.
 */
class FieldPropertiesTest {
  private static final String[] KEYS = {"a", "b", "and", "if", "_1"};

  static LongStream seeds() {
    return LongStream.range(0, 200);
  }

  /** A random JSON text of at most {@code depth} levels. */
  private static String value(SplittableRandom r, int depth) {
    int kind = r.nextInt(depth == 0 ? 5 : 7);
    return switch (kind) {
      case 0 -> "null";
      case 1 -> "true";
      case 2 -> "false";
      case 3 -> Integer.toString(r.nextInt(100));
      case 4 -> "\"s" + r.nextInt(10) + "\"";
      case 5 -> {
        List<String> items = new ArrayList<>();
        for (int i = r.nextInt(3); i > 0; i--) items.add(value(r, depth - 1));
        yield "[" + String.join(",", items) + "]";
      }
      default -> object(r, depth - 1);
    };
  }

  /** A random object: up to six members whose keys come from {@link #KEYS}, so a key may occur more than once. */
  private static String object(SplittableRandom r, int depth) {
    List<String> members = new ArrayList<>();
    for (int i = r.nextInt(7); i > 0; i--) members.add("\"" + KEYS[r.nextInt(KEYS.length)] + "\":" + value(r, depth));
    return "{" + String.join(",", members) + "}";
  }

  /** The outcome of one run: the results' compact text, or the text of the jq error that ended it. */
  private static String outcome(JqEngine engine, String filter, String json) {
    Workspace workspace = engine.newWorkspace();
    JqValue root = read(workspace, json).root();
    List<String> results = new ArrayList<>();
    try {
      engine
          .compile(filter)
          .run(
              root,
              Bindings.builder().build(),
              v -> {
                results.add(v.toJson());
                return true;
              },
              workspace);
      return String.join(" ", results);
    } catch (JqFilterException e) {
      return "error " + ((JqString) e.value()).value();
    }
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void chainPipeAndParensAgree(long seed) {
    SplittableRandom r = new SplittableRandom(seed);
    String json = object(r, 3);
    String k1 = KEYS[r.nextInt(KEYS.length)];
    String k2 = KEYS[r.nextInt(KEYS.length)];
    String k3 = KEYS[r.nextInt(KEYS.length)];
    JqEngine engine = JqEngine.builder().build();

    // Every spelling of "k1, then k2, then k3" that #34 builds is the same filter (jq-ref 1.8.2, recorded in
    // diff/field.rec: chain-3, pipe-3, parens-postfix, parens-pipe, parens-nested, space-steps and pipe-identity agree).
    String chain = "." + k1 + "." + k2 + "." + k3;
    List<String> forms =
        List.of(
            "." + k1 + " | ." + k2 + " | ." + k3,
            "(." + k1 + " | ." + k2 + ") | ." + k3,
            "((." + k1 + ")." + k2 + ")." + k3,
            "(." + k1 + " | ." + k2 + ")." + k3,
            ". | ." + k1 + " ." + k2 + " | (." + k3 + ")",
            "((." + k1 + "." + k2 + "." + k3 + "))");
    String expected = outcome(engine, chain, json);
    for (String form : forms) {
      assertThat(outcome(engine, form, json)).as("%s against %s on %s", form, chain, json).isEqualTo(expected);
    }
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void fieldMatchesGet(long seed) {
    // .k on an object is JqObject.get(k), the last value of the key (ADR-0002, ADR-0026), and null for a missing key.
    SplittableRandom r = new SplittableRandom(seed);
    String json = object(r, 2);
    JqEngine engine = JqEngine.builder().build();
    Map<String, String> expected = new LinkedHashMap<>();
    Map<String, String> actual = new LinkedHashMap<>();
    Workspace workspace = engine.newWorkspace();
    JqObject root = (JqObject) read(workspace, json).root();
    for (String k : List.of("a", "b", "and", "if", "_1", "missing")) {
      JqValue got = root.get(k);
      expected.put(k, got == null ? "null" : got.toJson());
      List<JqValue> results = new ArrayList<>();
      engine
          .compile("." + k)
          .run(
              root,
              Bindings.builder().build(),
              v -> {
                results.add(v);
                return true;
              },
              workspace);
      assertThat(results).as(".%s on %s gives one result", k, json).hasSize(1);
      JqValue result = results.getFirst();
      if (got == null) {
        assertThat(result).as(".%s on %s, a missing key", k, json).isSameAs(JqNull.NULL);
      } else {
        assertThat(result).as(".%s on %s, the view get gives", k, json).isEqualTo(got);
      }
      actual.put(k, result.toJson());
    }
    assertThat(actual).as("each key's result on %s", json).isEqualTo(expected);
  }
}
