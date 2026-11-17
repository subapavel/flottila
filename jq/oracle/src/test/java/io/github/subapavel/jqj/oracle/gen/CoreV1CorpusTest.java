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
 * The committed {@code diff/gen/core-v1.cases|.rec} (ADR-0033): written by {@code scripts/oracle-record.sh --generate
 * core-v1 0 2100} and frozen, so the Generator must still produce every byte of it. The generator-only bounds of the
 * freeze ({@code label} in at least 250 seeds, {@code break} in at least 90, every production in at least 30, no
 * {@code ?//} with {@code first}, {@code limit} or {@code break}) are {@link CoreV1Test}'s.
 */
class CoreV1CorpusTest {

  private static final Pattern DROPPED = Pattern.compile("# dropped (\\d+) (rc3|timeout)");

  private static Path diff() {
    try {
      return Path.of(CoreV1CorpusTest.class.getResource("/diff").toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String committed() throws Exception {
    return Files.readString(diff().resolve("gen/core-v1.cases"));
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

  @Test
  void generatedEqualsCommitted() throws Exception {
    String cases = committed();

    assertThat(ValuesV1CorpusTest.withoutDeviationLines(cases)).isEqualTo(CoreV1.file(CoreV1.FROM, CoreV1.TO, dropped(cases)));
  }

  @Test
  void dropsAndBlocksPartitionRange() throws Exception {
    String cases = committed();
    Map<Long, String> dropped = dropped(cases);
    List<Long> seeds = new ArrayList<>(dropped.keySet());
    for (CaseFormat.Block b : CaseFormat.parse("core-v1.cases", CoreV1.FAMILY, cases.getBytes(UTF_8))) {
      seeds.add(Long.parseLong(b.id().substring(CoreV1.FAMILY.length() + 1)));
    }
    seeds.sort(null);

    List<Long> range = new ArrayList<>();
    for (long s = CoreV1.FROM; s < CoreV1.TO; s++) range.add(s);
    assertThat(seeds).isEqualTo(range);
    assertThat(cases.lines().filter(l -> l.startsWith("# dropped"))).hasSize(dropped.size());
  }

  @Test
  void recordingsRcZeroOrFive() {
    List<DiffCorpus.Entry> entries = DiffCorpus.load(diff()).stream().filter(e -> e.c().id().startsWith(CoreV1.FAMILY + "/")).toList();

    assertThat(entries.size()).as("ADR-0020's floor after drops").isGreaterThanOrEqualTo(1000);
    for (DiffCorpus.Entry e : entries) {
      assertThat(e.recording()).as(e.c().id()).isNotNull();
      assertThat(e.recording().def()).as(e.c().id()).isEqualTo(e.c().def());
      assertThat(e.recording().outcome().rc()).as(e.c().id()).isIn(0, 5);
    }
  }
}
