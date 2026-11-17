package io.github.subapavel.jqj.oracle.json;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.oracle.diff.DiffCorpus;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The json oracle's corpus: JSONTestSuite's 318 {@code test_parsing} files at 1ef36fa0, each a recorded case
 * {@code -c . <file>} (ADR-0015, ADR-0020), and jq-ref's verdicts on them as recorded.
 */
class JsonCorpusTest {

  private static final List<DiffCorpus.Entry> ENTRIES = DiffCorpus.load(JsonCorpus.casesDir());

  @Test
  void everyFileIsACaseRunAsCompactIdentityOnItself() {
    assertThat(ENTRIES).hasSize(318);
    for (DiffCorpus.Entry e : ENTRIES) {
      String file = e.c().id().substring("json/".length()) + ".json";
      assertThat(e.c().args()).as(e.c().id()).containsExactly("-c", ".", file);
      assertThat(e.c().files()).as(e.c().id()).containsOnlyKeys(file);
      assertThat(e.c().stdin()).as(e.c().id()).isEmpty();
    }
  }

  @Test
  void labelsAreThePrefixesAndTheirCountsAreJsonTestSuites() {
    Map<String, Integer> counts = new TreeMap<>();
    for (DiffCorpus.Entry e : ENTRIES) counts.merge(JsonCorpus.label(e.c().id()), 1, Integer::sum);

    assertThat(counts).containsExactly(Map.entry("i_", 35), Map.entry("n_", 188), Map.entry("y_", 95));
  }

  @Test
  void onlyTheNulCaseIsADeviationCase() {
    assertThat(ENTRIES.stream().filter(e -> e.c().deviation() != null).map(e -> e.c().id() + " " + e.c().deviation()))
        .containsExactly("json/n_multidigit_number_then_00 nul");
  }

  @Test
  void everyCaseIsRecordedAndNoRecordingIsStale() {
    for (DiffCorpus.Entry e : ENTRIES) {
      assertThat(e.recording()).as(e.c().id()).isNotNull();
      assertThat(e.recording().def()).as(e.c().id()).isEqualTo(e.c().def());
    }
  }

  @Test
  void jqRefAcceptsOneHundredFortyFiveAndRejectsOneHundredSeventyThree() {
    Map<String, Integer> accepted = new TreeMap<>();
    Map<String, Integer> rejected = new TreeMap<>();
    for (DiffCorpus.Entry e : ENTRIES) {
      int rc = e.recording().outcome().rc();
      assertThat(rc).as(e.c().id()).isIn(0, 5);
      (rc == 0 ? accepted : rejected).merge(JsonCorpus.label(e.c().id()), 1, Integer::sum);
    }

    assertThat(accepted).containsExactly(Map.entry("i_", 25), Map.entry("n_", 25), Map.entry("y_", 95));
    assertThat(rejected).containsExactly(Map.entry("i_", 10), Map.entry("n_", 163));
  }

  @Test
  void theNulCaseIsOneJqRefAcceptsAndJqjRejects() {
    DiffCorpus.Entry e = ENTRIES.stream().filter(x -> x.c().id().equals("json/n_multidigit_number_then_00")).findFirst().orElseThrow();

    assertThat(e.c().files().get("n_multidigit_number_then_00.json")).isEqualTo(new byte[] {'1', '2', '3', 0});
    assertThat(e.recording().outcome().rc()).isZero();
    // The NUL row's jqj column, "rejected": rc 5 and no value, as jq-ref's rejected files give (ADR-0015). Its text,
    // with line and column, is the reader's in spec 5; until then the Stub rule fails the case first.
    assertThat(e.c().jqj().rc()).isEqualTo(5);
    assertThat(e.c().jqj().stdout()).isEmpty();
    assertThat(e.c().jqj().matches(e.recording().outcome())).as("deviation gone").isFalse();
  }
}
