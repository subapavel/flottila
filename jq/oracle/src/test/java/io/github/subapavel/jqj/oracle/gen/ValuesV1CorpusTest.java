package io.github.subapavel.jqj.oracle.gen;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.oracle.diff.CaseFormat;
import io.github.subapavel.jqj.oracle.diff.DiffCorpus;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The committed {@code diff/gen/values-v1.cases|.rec} (ADR-0025): written by {@code scripts/oracle-record.sh
 * --generate values-v1 0 2100} and frozen, so the Generator must still produce every byte of it.
 */
class ValuesV1CorpusTest {

  private static final Pattern DROPPED = Pattern.compile("# dropped (\\d+) (rc3|timeout)");

  private static Path diff() {
    try {
      return Path.of(ValuesV1CorpusTest.class.getResource("/diff").toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String committed() throws Exception {
    return Files.readString(diff().resolve("gen/values-v1.cases"));
  }

  /** The Dropped seeds of a case file's header, with their reasons. */
  private static Map<Long, String> dropped(String cases) {
    Map<Long, String> out = new TreeMap<>();
    for (String line : cases.split("\n")) {
      Matcher m = DROPPED.matcher(line);
      if (m.matches()) out.put(Long.parseLong(m.group(1)), m.group(2));
    }
    return out;
  }

  /** The case file without the lines a deviation seed gains (answer (d) on #28): {@code deviation} and {@code jqj-*}. */
  static String withoutDeviationLines(String cases) {
    StringBuilder b = new StringBuilder();
    for (String line : cases.split("\n", -1)) {
      if (line.startsWith("deviation ") || line.startsWith("jqj-")) continue;
      b.append(line).append('\n');
    }
    return b.substring(0, b.length() - 1);
  }

  @Test
  void generatedEqualsCommitted() throws Exception {
    String cases = committed();

    assertThat(withoutDeviationLines(cases)).isEqualTo(ValuesV1.file(ValuesV1.FROM, ValuesV1.TO, dropped(cases)));
  }

  @Test
  void theFreezeCheckIgnoresOnlyTheDeviationLines() {
    String block = ValuesV1.generate(4).block();
    String marked = block.replace("\nend\n", "\ndeviation nul\njqj-rc 5\njqj-stdout\njqj-stderr x\nend\n");

    assertThat(withoutDeviationLines("# h\n\n" + marked)).isEqualTo("# h\n\n" + block);
    assertThat(withoutDeviationLines(block.replace("filter", "filterx"))).isNotEqualTo(block);
  }

  @Test
  void dropsAndBlocksPartitionRange() throws Exception {
    String cases = committed();
    Map<Long, String> dropped = dropped(cases);
    List<Long> seeds = new ArrayList<>(dropped.keySet());
    for (CaseFormat.Block b : CaseFormat.parse("values-v1.cases", ValuesV1.FAMILY, cases.getBytes(UTF_8))) {
      seeds.add(Long.parseLong(b.id().substring(ValuesV1.FAMILY.length() + 1)));
    }
    seeds.sort(null);

    List<Long> range = new ArrayList<>();
    for (long s = ValuesV1.FROM; s < ValuesV1.TO; s++) range.add(s);
    assertThat(seeds).isEqualTo(range);
    assertThat(cases.lines().filter(l -> l.startsWith("# dropped"))).hasSize(dropped.size());
  }

  @Test
  void recordingsExitZero() {
    List<DiffCorpus.Entry> entries = DiffCorpus.load(diff()).stream().filter(e -> e.c().id().startsWith(ValuesV1.FAMILY + "/")).toList();

    assertThat(entries.size()).as("ADR-0023's minimum after drops").isGreaterThanOrEqualTo(1000);
    for (DiffCorpus.Entry e : entries) {
      assertThat(e.recording()).as(e.c().id()).isNotNull();
      assertThat(e.recording().def()).as(e.c().id()).isEqualTo(e.c().def());
      assertThat(e.recording().outcome().rc()).as(e.c().id()).isZero();
    }
  }
}
