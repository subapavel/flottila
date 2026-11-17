package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.TestDocuments.read;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.LongStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #63's three properties, one case per seed (docs/agents/environment.md: seeded loops, no jqwik), over arrays and strings
 * drawn as values-v1 draws them (ADR-0025: scalars and nested containers, strings of ASCII, 2-, 3- and 4-byte characters,
 * written raw or as escapes). The oracle of each is the host's accessor or Java's code points, never the operation under
 * test: {@code .[]} emits {@code JqArray.get(i)} for each i in order; an in-range {@code .[a:b]} is the run a..b-1 of
 * {@code .[]}; a string's {@code .[a:b]} counts code points (jq-ref 1.8.2: slice/string-* on "é𝄞é𝄞é", man.test
 * {@code .[2:4]} on "abcdefghi" gives "cd"). Bounds are non-negative literals, so no filter needs {@code -} (#65).
 */
class NavigationPropertiesTest {

  static LongStream seeds() {
    return LongStream.range(0, 200);
  }

  /** The characters a string draws from: ASCII, é (2 bytes), € (3), U+1D11E (4). */
  private static final String[] CHARS = {"a", "Z", "0", " ", "é", "€", "𝄞"};

  /** A random string of up to 12 characters, as Java text. */
  private static String text(SplittableRandom r) {
    StringBuilder s = new StringBuilder();
    for (int i = r.nextInt(13); i > 0; i--) s.append(CHARS[r.nextInt(CHARS.length)]);
    return s.toString();
  }

  /** The JSON text of {@code s}: each character raw or, by the seed, as a backslash-u escape (a pair for U+1D11E). */
  private static String json(SplittableRandom r, String s) {
    StringBuilder out = new StringBuilder("\"");
    s.codePoints()
        .forEach(
            cp -> {
              String c = Character.toString(cp);
              if (r.nextInt(4) == 0) {
                for (int i = 0; i < c.length(); i++) out.append(String.format("\\u%04x", (int) c.charAt(i)));
              } else {
                out.append(c);
              }
            });
    return out.append('"').toString();
  }

  /** A random JSON value of at most {@code depth} levels. */
  private static String value(SplittableRandom r, int depth) {
    return switch (r.nextInt(depth == 0 ? 5 : 7)) {
      case 0 -> "null";
      case 1 -> r.nextBoolean() ? "true" : "false";
      case 2 -> Integer.toString(r.nextInt(-50, 1000));
      case 3 -> "1.5e" + r.nextInt(-3, 4);
      case 4 -> json(r, text(r));
      case 5 -> array(r, depth - 1);
      default -> "{\"k\":" + value(r, depth - 1) + "}";
    };
  }

  /** A random array of up to 8 elements. */
  private static String array(SplittableRandom r, int depth) {
    List<String> items = new ArrayList<>();
    for (int i = r.nextInt(9); i > 0; i--) items.add(value(r, depth));
    return "[" + String.join(",", items) + "]";
  }

  /** The results of {@code filter} on {@code in}, read into {@code workspace}. */
  private static List<JqValue> results(Workspace workspace, String filter, JqValue in) {
    List<JqValue> results = new ArrayList<>();
    JqEngine.builder()
        .build()
        .compile(filter)
        .run(
            in,
            Bindings.builder().build(),
            v -> {
              results.add(v);
              return true;
            },
            workspace);
    return results;
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void iterateEmitsEachIndexInOrder(long seed) {
    SplittableRandom r = new SplittableRandom(seed);
    String json = array(r, 3);
    Workspace workspace = JqEngine.builder().build().newWorkspace();
    JqArray root = (JqArray) read(workspace, json).root();
    List<String> expected = new ArrayList<>();
    for (int i = 0; i < root.length(); i++) expected.add(root.get(i).toJson());
    List<String> actual = results(workspace, ".[]", root).stream().map(JqValue::toJson).toList();
    assertThat(actual).as(".[] on %s", json).isEqualTo(expected);
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void inRangeSliceIsARunOfIterate(long seed) {
    SplittableRandom r = new SplittableRandom(seed);
    String json = array(r, 2);
    Workspace workspace = JqEngine.builder().build().newWorkspace();
    JqArray root = (JqArray) read(workspace, json).root();
    int length = root.length();
    int a = r.nextInt(length + 1);
    int b = r.nextInt(a, length + 1);
    String filter = ".[" + a + ":" + b + "]";
    List<JqValue> sliced = results(workspace, filter, root);
    assertThat(sliced).as("results of %s on %s", filter, json).hasSize(1);
    assertThat(sliced.getFirst()).as("the result of %s on %s", filter, json).isInstanceOf(JqArray.class);
    JqArray slice = (JqArray) sliced.getFirst();
    List<String> iterated = results(workspace, ".[]", root).stream().map(JqValue::toJson).toList();
    List<String> run = new ArrayList<>();
    for (int i = 0; i < slice.length(); i++) run.add(slice.get(i).toJson());
    assertThat(run).as("%s on %s against .[]'s results %d to %d", filter, json, a, b).isEqualTo(iterated.subList(a, b));
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void stringSliceCountsCodePoints(long seed) {
    SplittableRandom r = new SplittableRandom(seed);
    String s = text(r);
    String json = json(r, s);
    int points = s.codePointCount(0, s.length());
    int a = r.nextInt(points + 1);
    int b = r.nextInt(a, points + 1);
    String expected = s.substring(s.offsetByCodePoints(0, a), s.offsetByCodePoints(0, b));
    Workspace workspace = JqEngine.builder().build().newWorkspace();
    JqValue root = read(workspace, json).root();
    String filter = ".[" + a + ":" + b + "]";
    List<JqValue> sliced = results(workspace, filter, root);
    assertThat(sliced).as("results of %s on %s", filter, json).hasSize(1);
    assertThat(sliced.getFirst()).as("the result of %s on %s", filter, json).isInstanceOf(JqString.class);
    assertThat(((JqString) sliced.getFirst()).value()).as("%s on %s (%d code points)", filter, json, points).isEqualTo(expected);
  }
}
