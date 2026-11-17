package io.github.subapavel.jqj.oracle.gen;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.oracle.diff.CaseFormat;
import io.github.subapavel.jqj.oracle.diff.DiffCase;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** The values-v1 Generator over its whole seed range (ADR-0025, #28's parameter table). */
class ValuesV1Test {

  /** Every test draws the range itself: PIT credits a static initializer to no test, so its mutants would live. */
  private final List<ValuesV1.Generated> generated = all();

  private static List<ValuesV1.Generated> all() {
    List<ValuesV1.Generated> out = new ArrayList<>();
    for (long seed = ValuesV1.FROM; seed < ValuesV1.TO; seed++) out.add(ValuesV1.generate(seed));
    return out;
  }

  private static final Set<String> LENIENT =
      Set.of("+1", ".5", "1.", "007", "2.e3", "-.5", "+1.E+2", "NaN", "nan", "-NaN", "sNaN", "NaN0", "NaN00", "Infinity", "inf", "-Infinity", "-inf");

  @Test
  void theSeedRangeIsTwoThousandOneHundred() {
    assertThat(ValuesV1.FROM).isZero();
    assertThat(ValuesV1.TO).isEqualTo(2100);
    assertThat(generated).hasSize(2100);
  }

  @Test
  void sameSeedSameBytes() {
    for (long seed = ValuesV1.TO - 1; seed >= ValuesV1.FROM; seed--) {
      ValuesV1.Generated again = ValuesV1.generate(seed);
      ValuesV1.Generated first = generated.get((int) seed);
      assertThat(again.block()).as("seed %d", seed).isEqualTo(first.block());
      assertThat(again.stdin()).as("seed %d", seed).isEqualTo(first.stdin());
    }
  }

  @Test
  void formIsSeedMod7() {
    List<List<String>> forms =
        List.of(
            List.of("-c", "."),
            List.of("."),
            List.of("-c", "-S", "."),
            List.of("-c", "-a", "."),
            List.of("-c", "tojson"),
            List.of("-c", "[paths]"),
            List.of("-c", "tostream"));
    for (ValuesV1.Generated g : generated) {
      DiffCase c = DiffCase.of("values-v1.cases", CaseFormat.parse("values-v1.cases", "gen/values-v1", g.block().getBytes(UTF_8)).getFirst());
      assertThat(c.id()).isEqualTo("gen/values-v1/" + g.seed());
      assertThat(c.args()).as("seed %d", g.seed()).isEqualTo(forms.get((int) (g.seed() % 7)));
      assertThat(c.stdin()).as("seed %d", g.seed()).isEqualTo(g.stdin());
      assertThat(c.files()).isEmpty();
      assertThat(c.env()).isEmpty();
      assertThat(c.deviation()).isNull();
      String filter = c.args().getLast();
      assertThat(g.block()).contains("\nfilter " + filter + "\n").doesNotContain("\narg " + filter + "\n");
    }
  }

  @Test
  void sizeCapHolds() {
    Map<ValuesV1.Feature, Integer> budgets =
        Map.of(ValuesV1.Feature.BUDGET_128, 128, ValuesV1.Feature.BUDGET_512, 512, ValuesV1.Feature.BUDGET_4096, 4096);
    int largest = 0;
    for (ValuesV1.Generated g : generated) {
      assertThat(g.stdin().length).as("seed %d", g.seed()).isBetween(1, 4096);
      List<ValuesV1.Feature> classes = budgets.keySet().stream().filter(g.features()::contains).toList();
      assertThat(classes).as("seed %d has one budget class", g.seed()).hasSize(1);
      assertThat(g.stdin().length).as("seed %d within its class", g.seed()).isLessThanOrEqualTo(budgets.get(classes.getFirst()));
      largest = Math.max(largest, g.stdin().length);
    }
    assertThat(largest).as("the largest document uses most of the cap").isGreaterThan(3500);
  }

  @Test
  void depthCapHolds() {
    int deepest = 0;
    int spines = 0;
    for (ValuesV1.Generated g : generated) {
      int depth = Scan.of(g.stdin()).maxDepth;
      if (g.features().contains(ValuesV1.Feature.SPINE)) {
        spines++;
        assertThat(depth).as("spine seed %d", g.seed()).isBetween(9, 200);
        deepest = Math.max(deepest, depth);
      } else {
        assertThat(depth).as("tree seed %d", g.seed()).isLessThanOrEqualTo(8);
      }
    }
    assertThat(spines).as("2 % of 2100").isBetween(25, 60);
    assertThat(deepest).isGreaterThan(150);
  }

  @Test
  void lenientFlagMatchesTokens() {
    int lenientDocuments = 0;
    for (ValuesV1.Generated g : generated) {
      List<String> lenient = Scan.of(g.stdin()).tokens.stream().filter(Scan::lenient).toList();
      assertThat(LENIENT).as("seed %d", g.seed()).containsAll(lenient);
      if (g.lenient()) {
        lenientDocuments++;
        assertThat(lenient).as("lenient seed %d", g.seed()).isNotEmpty();
      } else {
        assertThat(lenient).as("strict seed %d", g.seed()).isEmpty();
      }
    }
    assertThat(lenientDocuments).isBetween(17 * 21, 23 * 21);
  }

  @Test
  void noDeviationShape() {
    for (ValuesV1.Generated g : generated) {
      try {
        Scan.of(g.stdin());
      } catch (AssertionError e) {
        throw new AssertionError("seed " + g.seed() + ": " + e.getMessage() + " in " + CaseFormat.escape(g.stdin()), e);
      }
    }
  }

  @Test
  void everyClassOccurs() {
    Map<ValuesV1.Feature, Integer> documents = new EnumMap<>(ValuesV1.Feature.class);
    for (ValuesV1.Feature f : ValuesV1.Feature.values()) documents.put(f, 0);
    for (ValuesV1.Generated g : generated) for (ValuesV1.Feature f : g.features()) documents.merge(f, 1, Integer::sum);

    Map<ValuesV1.Feature, Integer> rare = new TreeMap<>();
    documents.forEach((f, n) -> {
      if (n < 10) rare.put(f, n);
    });
    assertThat(rare).as("classes in fewer than 10 documents").isEmpty();
  }

  /**
   * The escaped keys are in the bytes: an escaped pool key is spelled exactly {@code \}{@code u00e9}, and a respelled
   * duplicate is a later key of the same object that stands for the same characters with more {@code \}{@code u}
   * escapes.
   */
  @Test
  void escapedKeysAreInTheBytes() {
    for (ValuesV1.Generated g : generated) {
      Scan s = Scan.of(g.stdin());
      if (g.features().contains(ValuesV1.Feature.KEY_POOL_ESCAPED)) {
        assertThat(s.objectKeys.stream().flatMap(List::stream)).as("seed %d", g.seed()).contains("\\u00e9");
      }
      if (g.features().contains(ValuesV1.Feature.RESPELLED_KEY)) {
        assertThat(s.objectKeys.stream().anyMatch(ValuesV1Test::hasEscapedDuplicate)).as("seed %d", g.seed()).isTrue();
      }
    }
  }

  private static boolean hasEscapedDuplicate(List<String> keys) {
    for (int j = 1; j < keys.size(); j++) {
      for (int i = 0; i < j; i++) {
        String a = keys.get(i);
        String b = keys.get(j);
        if (!a.equals(b) && Scan.decode(a).equals(Scan.decode(b)) && escapes(b) > escapes(a)) return true;
      }
    }
    return false;
  }

  private static int escapes(String spelling) {
    return spelling.split("\\\\u", -1).length - 1;
  }

  /** The bytes behind three features, read from the documents rather than from the generator's word. */
  @Test
  void featuresAreInTheBytes() {
    for (ValuesV1.Generated g : generated) {
      byte[] d = g.stdin();
      boolean bom = d.length >= 3 && (d[0] & 0xff) == 0xef;
      assertThat(bom).as("seed %d", g.seed()).isEqualTo(g.features().contains(ValuesV1.Feature.BOM));
      if (g.features().contains(ValuesV1.Feature.TRAILING_LF)) assertThat(d[d.length - 1]).as("seed %d", g.seed()).isEqualTo((byte) '\n');
      String outsideStrings = new String(d, UTF_8).replaceAll("\"([^\"\\\\]|\\\\.)*\"", "\"\"");
      if (g.features().contains(ValuesV1.Feature.LAYOUT_NONE)) {
        assertThat(outsideStrings.stripTrailing()).as("seed %d", g.seed()).doesNotContain(" ", "\t", "\r", "\n");
      }
      if (g.features().contains(ValuesV1.Feature.LAYOUT_INDENT_LF)) assertThat(outsideStrings).as("seed %d", g.seed()).doesNotContain("\r", "\t");
      if (g.features().contains(ValuesV1.Feature.LAYOUT_INDENT_CRLF)) {
        assertThat(outsideStrings.replace("\r\n", "")).as("seed %d", g.seed()).doesNotContain("\r", "\t");
      }
    }
  }
}
