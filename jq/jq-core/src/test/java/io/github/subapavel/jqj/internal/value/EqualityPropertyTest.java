package io.github.subapavel.jqj.internal.value;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.Bindings;
import io.github.subapavel.jqj.JqEngine;
import io.github.subapavel.jqj.Workspace;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqValue;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.stream.LongStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code ==} and {@code !=} over seeded random object pairs agree with a reference comparison that has no key index
 * (ADR-0044 as amended by #95): Java's own equality of the objects' models, a {@link LinkedHashMap} per object, whose
 * {@code put} of a present key keeps the last value, as jq does (ADR-0002). Each pair runs read against read, built
 * against read and read against built; a built side is the {@link Detach} copy of its read text. The right side is the
 * left one with its members reordered (nested objects too), each key repeated now and then with an earlier decoy value,
 * or that copy with one change: a value, a key dropped, a key added, the left side's last key renamed. Keys are short
 * plain keys, keys of Aa/BB blocks that share one hash (so a proof of many sorts) and either spelled with an escape now
 * and then. Objects have 0 to 8 key entries for the pairwise proof or up to 40 for the table and the sort.
 *
 * <p>The cost row: with plain keys, Equality takes at most three steps per node of the two sides (a value or a key
 * entry): a read object's proof one per key entry, each side once, one per member pair, an array's length one per
 * element, each side once, and a string compare one. Before #95 a read right side cost a field read per left member,
 * each scanning all its key entries, so a wide pair took n * m.
 */
class EqualityPropertyTest {

  /** CODING_STANDARDS: one case per seed, the seed in the case name. */
  static LongStream seeds() {
    return LongStream.range(0, 96);
  }

  /** A JSON text, its model, and its nodes: each value and each key entry it spells, repeats included. */
  private record Text(String json, Object model, int nodes) {}

  /** A pair's two sides. */
  private record Pair(Text left, Text right) {
    boolean equal() {
      return Objects.equals(left.model(), right.model());
    }
  }

  /** The texts of a random generation, with its seed and whether its keys may collide. */
  private static final class Gen {
    final SplittableRandom random;
    final boolean colliding;
    int nodes;

    Gen(long seed, boolean colliding) {
      this.random = new SplittableRandom(seed);
      this.colliding = colliding;
    }

    /** A random key: plain, or of Aa/BB blocks when the keys may collide. */
    String key() {
      if (colliding && random.nextBoolean()) {
        StringBuilder blocks = new StringBuilder();
        for (int b = random.nextInt(1, 5); b > 0; b--) blocks.append(random.nextBoolean() ? "Aa" : "BB");
        return blocks.toString();
      }
      return "k" + random.nextInt(48);
    }

    /** A random model of at most {@code depth} more levels of containers. */
    Object value(int depth) {
      int pick = random.nextInt(depth > 0 ? 10 : 6);
      if (pick < 3) return random.nextInt(5);
      if (pick < 6) return "s" + random.nextInt(4);
      if (pick < 8) {
        List<Object> array = new ArrayList<>();
        for (int i = random.nextInt(4); i > 0; i--) array.add(value(depth - 1));
        return array;
      }
      return object(depth - 1, random.nextInt(4) == 0 ? random.nextInt(9, 41) : random.nextInt(0, 9));
    }

    /** A random object model of up to {@code entries} distinct keys. */
    Map<String, Object> object(int depth, int entries) {
      Map<String, Object> object = new LinkedHashMap<>();
      for (int e = 0; e < entries; e++) object.put(key(), value(depth));
      return object;
    }

    /** {@code model}'s JSON text: each object's members shuffled, a decoy before a member now and then; counts nodes. */
    String write(Object model) {
      nodes++;
      return switch (model) {
        case Integer i -> i.toString();
        case String s -> "\"" + s + "\"";
        case List<?> list -> {
          List<String> elements = new ArrayList<>();
          for (Object element : list) elements.add(write(element));
          yield "[" + String.join(",", elements) + "]";
        }
        case Map<?, ?> map -> {
          List<Map.Entry<?, ?>> members = new ArrayList<>(map.entrySet());
          for (int i = members.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Map.Entry<?, ?> t = members.get(i);
            members.set(i, members.get(j));
            members.set(j, t);
          }
          List<String> entries = new ArrayList<>();
          for (Map.Entry<?, ?> member : members) {
            String key = (String) member.getKey();
            if (random.nextInt(6) == 0) {
              nodes++;
              entries.add(spelling(key) + ":" + write(value(0)));
            }
            nodes++;
            entries.add(spelling(key) + ":" + write(member.getValue()));
          }
          yield "{" + String.join(",", entries) + "}";
        }
        default -> throw new IllegalArgumentException(String.valueOf(model));
      };
    }

    /** {@code key} in quotes, plain or, one time in four, with one of its letters as a {@code \\u00XX} escape. */
    String spelling(String key) {
      if (random.nextInt(4) != 0) return "\"" + key + "\"";
      int at = random.nextInt(key.length());
      return "\"" + key.substring(0, at) + String.format("\\u%04x", (int) key.charAt(at)) + key.substring(at + 1) + "\"";
    }

    /** {@code model}'s text and nodes. */
    Text text(Object model) {
      nodes = 0;
      String json = write(model);
      return new Text(json, model, nodes);
    }
  }

  /** The pair of {@code seed}; plain keys only when {@code colliding} is false. */
  private static Pair pair(long seed, boolean colliding) {
    Gen gen = new Gen(seed, colliding);
    int entries = gen.random.nextBoolean() ? gen.random.nextInt(0, 9) : gen.random.nextInt(9, 41);
    Map<String, Object> left = gen.object(2, entries);
    Map<String, Object> right = new LinkedHashMap<>(left);
    List<String> keys = new ArrayList<>(left.keySet());
    switch (gen.random.nextInt(6)) {
      case 0, 1 -> {} // a reordered copy
      case 2 -> {
        if (!keys.isEmpty()) right.put(keys.get(gen.random.nextInt(keys.size())), gen.value(1));
      }
      case 3 -> {
        if (!keys.isEmpty()) right.remove(keys.get(gen.random.nextInt(keys.size())));
      }
      case 4 -> right.put("added", gen.value(1));
      default -> {
        if (!keys.isEmpty()) {
          Object last = right.remove(keys.getLast());
          right.put(keys.getLast() + "x", last);
        }
      }
    }
    return new Pair(gen.text(left), gen.text(right));
  }

  private static JqValue read(String json) {
    Workspace workspace = JqEngine.builder().build().newWorkspace();
    return workspace.read(MemorySegment.ofArray(json.getBytes(US_ASCII))).root();
  }

  /** The one result's text of {@code filter} over the read {@code [left,right]}, {@code $a} and {@code $b} built. */
  private static String result(String filter, Pair p) {
    JqEngine engine = JqEngine.builder().build();
    Workspace workspace = engine.newWorkspace();
    JqValue root = workspace.read(MemorySegment.ofArray(("[" + p.left().json() + "," + p.right().json() + "]").getBytes(US_ASCII))).root();
    Bindings bindings = Bindings.builder().put("a", Detach.of(read(p.left().json()))).put("b", Detach.of(read(p.right().json()))).build();
    List<String> results = new ArrayList<>();
    engine.compile(filter, Set.of("a", "b")).run(root, bindings, v -> results.add(v.toJson()), workspace);
    assertThat(results).as("results of %s", filter).hasSize(1);
    return results.getFirst();
  }

  private static void assertAgrees(long seed, boolean colliding) {
    Pair p = pair(seed, colliding);
    String equal = Boolean.toString(p.equal());
    String unequal = Boolean.toString(!p.equal());
    for (String[] filters : new String[][] {{".[0] == .[1]", ".[0] != .[1]"}, {"$a == .[1]", "$a != .[1]"}, {".[0] == $b", ".[0] != $b"}}) {
      assertThat(result(filters[0], p)).as("seed %d: %s over %s and %s", seed, filters[0], p.left().json(), p.right().json()).isEqualTo(equal);
      assertThat(result(filters[1], p)).as("seed %d: %s over %s and %s", seed, filters[1], p.left().json(), p.right().json()).isEqualTo(unequal);
    }
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void plainKeysAgreeWithTheReference(long seed) {
    assertAgrees(seed, false);
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void collidingKeysAgreeWithTheReference(long seed) {
    assertAgrees(seed, true);
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void aReadRightSideCostsAtMostThreeStepsPerNode(long seed) {
    Pair p = pair(seed, false);
    long bound = 3L * (p.left().nodes() + p.right().nodes());
    for (boolean builtLeft : new boolean[] {false, true}) {
      JqValue left = builtLeft ? Detach.of(read(p.left().json())) : read(p.left().json());
      RunState state = new RunState();
      boolean equal = Equality.equal(state, left, read(p.right().json()));
      assertThat(equal).as("seed %d: equality of %s and %s, left built %s", seed, p.left().json(), p.right().json(), builtLeft).isEqualTo(p.equal());
      assertThat(state.steps())
          .as("seed %d: steps of equality of %s and %s, left built %s, against 3 per node", seed, p.left().json(), p.right().json(), builtLeft)
          .isLessThanOrEqualTo(bound);
    }
  }
}
