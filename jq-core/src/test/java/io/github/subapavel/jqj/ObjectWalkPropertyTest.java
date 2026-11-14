package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.unlimited;
import static io.github.subapavel.jqj.RunBudgets.writer;
import static io.github.subapavel.jqj.TestDocuments.read;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.StringJoiner;
import java.util.stream.LongStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every walker of a read object sees each key once, in its first place, with its last value, as jq does (ADR-0002,
 * ADR-0044, #85), whatever the key proof finds: seeded random objects of 0 to 300 key entries, half of them at most 12
 * so the proof's pairwise compare of up to 8 keys is crossed too, with repeats anywhere, escaped twins of a key (a tag-6
 * key that spells one letter as a {@code \\u00XX} escape, equal to the plain key by its decoded bytes) and keys of {@code Aa}/{@code BB} blocks,
 * which share a {@code String.hashCode}. The model is a {@link LinkedHashMap}, whose {@code put} of a present key keeps
 * its place and replaces its value: jq-ref 1.8.2 records that rule in diff/distinct.rec (escaped-repeat-*,
 * nine-repeat-*, colliding16-repeat-*, to-entries-repeat). On 25ef36c the walks are correct and quadratic (F-40), so
 * this class guards the proof against a regression; it is not #85's red.
 */
class ObjectWalkPropertyTest {

  /** CODING_STANDARDS: one case per seed, the seed in the case name. */
  static LongStream seeds() {
    return LongStream.range(0, 64);
  }

  /** A read object's text, and the members jq gives for it in order: each key's first place and last value. */
  private record Sample(String json, Map<String, String> members) {}

  /** Letters and digits only, so a key prints as it is spelled. */
  private static final String ALPHABET = "abkxyz019";

  private static Sample sample(long seed) {
    SplittableRandom random = new SplittableRandom(seed);
    int entries = random.nextBoolean() ? random.nextInt(0, 13) : random.nextInt(0, 301);
    List<String> seen = new ArrayList<>();
    Map<String, String> members = new LinkedHashMap<>();
    StringJoiner json = new StringJoiner(",", "{", "}");
    for (int e = 0; e < entries; e++) {
      String key;
      int pick = random.nextInt(10);
      if (pick < 3 && !seen.isEmpty()) {
        key = seen.get(random.nextInt(seen.size()));
      } else if (pick < 6) {
        StringBuilder blocks = new StringBuilder();
        for (int b = random.nextInt(1, 5); b > 0; b--) blocks.append(random.nextBoolean() ? "Aa" : "BB");
        key = blocks.toString();
      } else {
        StringBuilder plain = new StringBuilder();
        for (int c = random.nextInt(1, 4); c > 0; c--) plain.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        key = plain.toString();
      }
      seen.add(key);
      String value = Integer.toString(random.nextInt(1000));
      members.put(key, value);
      json.add(spelling(key, random) + ":" + value);
    }
    return new Sample(json.toString(), members);
  }

  /** {@code key} in quotes, plain or, one time in three, with one of its letters as a {@code \\u00XX} escape. */
  private static String spelling(String key, SplittableRandom random) {
    if (random.nextInt(3) != 0) return "\"" + key + "\"";
    int at = random.nextInt(key.length());
    String escape = String.format(random.nextBoolean() ? "\\u%04x" : "\\u%04X", (int) key.charAt(at));
    return "\"" + key.substring(0, at) + escape + key.substring(at + 1) + "\"";
  }

  /** The texts of {@code filter}'s results over {@code json}, read on a fresh Workspace. */
  private static List<String> results(String filter, String json) {
    JqEngine engine = unlimited();
    Workspace workspace = engine.newWorkspace();
    JqValue root = read(workspace, json).root();
    Results all = new Results();
    engine.compile(filter).run(root, NONE, all, workspace);
    return all.values.stream().map(JqValue::toJson).toList();
  }

  private static String quoted(String key) {
    return "\"" + key + "\"";
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void iterateGivesEachLastValueInItsFirstPlace(long seed) {
    Sample s = sample(seed);
    assertThat(results(".[]", s.json())).as("seed %d: .[] over %s", seed, s.json()).containsExactlyElementsOf(s.members().values());
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void keysUnsortedGivesEachKeyOnceInItsFirstPlace(long seed) {
    Sample s = sample(seed);
    StringJoiner keys = new StringJoiner(",", "[", "]");
    s.members().keySet().forEach(k -> keys.add(quoted(k)));
    assertThat(results("keys_unsorted", s.json())).as("seed %d: keys_unsorted over %s", seed, s.json()).containsExactly(keys.toString());
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void toEntriesGivesEachKeyOnceWithItsLastValue(long seed) {
    Sample s = sample(seed);
    StringJoiner entries = new StringJoiner(",", "[", "]");
    s.members().forEach((k, v) -> entries.add("{\"key\":" + quoted(k) + ",\"value\":" + v + "}"));
    assertThat(results("to_entries", s.json())).as("seed %d: to_entries over %s", seed, s.json()).containsExactly(entries.toString());
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void lengthCountsEachKeyOnce(long seed) {
    Sample s = sample(seed);
    assertThat(results("length", s.json())).as("seed %d: length over %s", seed, s.json()).containsExactly(Integer.toString(s.members().size()));
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void printGivesEachKeyOnceWithItsLastValue(long seed) {
    Sample s = sample(seed);
    StringJoiner object = new StringJoiner(",", "{", "}\n");
    s.members().forEach((k, v) -> object.add(quoted(k) + ":" + v));
    JqEngine engine = unlimited();
    Workspace workspace = engine.newWorkspace();
    JqValue root = read(workspace, s.json()).root();
    RecordingTarget target = new RecordingTarget();
    engine.compile(".").run(root, NONE, writer(workspace, target), workspace);
    assertThat(target.text()).as("seed %d: -c . over %s", seed, s.json()).isEqualTo(object.toString());
  }
}
