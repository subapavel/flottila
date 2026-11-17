package io.github.subapavel.jqj.oracle.record;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.subapavel.jqj.oracle.diff.CaseFormat;
import io.github.subapavel.jqj.oracle.diff.DiffCase;
import io.github.subapavel.jqj.oracle.diff.Outcome;
import io.github.subapavel.jqj.oracle.diff.Recording;
import io.github.subapavel.jqj.oracle.gen.CoreV1;
import io.github.subapavel.jqj.oracle.gen.ValuesV1;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code --generate} of the record command (ADR-0020, ADR-0025), against a reference standing in for jq-ref: the JUnit
 * run never starts jq-ref.
 */
class RecorderTest {

  private static final Outcome OK = new Outcome(0, "1\n".getBytes(UTF_8), new byte[0]);

  /** A reference that answers each case with {@code outcome}. */
  private record Fake(Function<DiffCase, Outcome> outcome) implements Recorder.Reference {
    @Override
    public String header() {
      return "# jq-fake sha256 00";
    }

    @Override
    public Outcome run(DiffCase c) {
      return outcome.apply(c);
    }
  }

  private static long seed(DiffCase c) {
    return Long.parseLong(c.id().substring(ValuesV1.FAMILY.length() + 1));
  }

  @Test
  void aSeedWithRc3OrATimeoutIsDroppedAndEveryOtherIsRecorded() throws Exception {
    List<Long> runs = new ArrayList<>();
    Recorder.Reference ref =
        new Fake(
            c -> {
              long seed = seed(c);
              runs.add(seed);
              if (seed == 1) return new Outcome(3, new byte[0], "jq: error\n".getBytes(UTF_8));
              if (seed == 2 && runs.stream().filter(s -> s == 2).count() == 2) return null;
              if (seed == 3) return null;
              return OK;
            });

    Recorder.Family f = Recorder.generate(ValuesV1.NAME, 0, 5, ref);

    assertThat(runs).containsExactly(0L, 0L, 1L, 1L, 2L, 2L, 3L, 4L, 4L);
    assertThat(f.cases()).isEqualTo(ValuesV1.file(0, 5, new TreeMap<>(Map.of(1L, "rc3", 2L, "timeout", 3L, "timeout"))));
    assertThat(f.cases()).contains("\n# dropped 1 rc3\n# dropped 2 timeout\n# dropped 3 timeout\n");
    assertThat(f.recordings()).extracting(Recording::id).containsExactly("gen/values-v1/0", "gen/values-v1/4");
    List<CaseFormat.Block> blocks = CaseFormat.parse("values-v1.cases", ValuesV1.FAMILY, f.cases().getBytes(UTF_8));
    assertThat(f.recordings()).extracting(Recording::def).containsExactly(blocks.get(0).def(), blocks.get(1).def());
    assertThat(f.recordings().getFirst().outcome().sameAs(OK)).isTrue();
  }

  @Test
  void twoRunsThatDifferRejectTheRange() {
    int[] n = {0};
    Recorder.Reference ref = new Fake(c -> new Outcome(n[0]++, new byte[0], new byte[0]));

    assertThatThrownBy(() -> Recorder.generate(ValuesV1.NAME, 7, 8, ref)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("gen/values-v1/7").hasMessageContaining("differ");
  }

  @Test
  void generateWritesTheFamilyBelowGenWithTheReferenceHeader(@TempDir Path tmp) throws Exception {
    Path diff = tmp.resolve("diff");
    PrintStream out = System.out;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setOut(new PrintStream(captured, true, UTF_8));
    int rc;
    try {
      rc = Recorder.generate(List.of("values-v1", "0", "2", diff.toString()), new Fake(c -> c.id().endsWith("/1") ? null : OK));
    } finally {
      System.setOut(out);
    }

    assertThat(rc).isZero();
    assertThat(captured.toString(UTF_8)).isEqualTo("recorded 1 seeds of values-v1, dropped 1\n");
    assertThat(Files.readString(diff.resolve("gen/values-v1.cases"))).isEqualTo(ValuesV1.file(0, 2, Map.of(1L, "timeout")));
    assertThat(Files.readString(diff.resolve("gen/values-v1.rec"))).startsWith("# jq-fake sha256 00\n\ncase gen/values-v1/0\n");
    assertThat(Recording.read(diff.resolve("gen/values-v1.rec"))).containsOnlyKeys("gen/values-v1/0");
  }

  /** {@code --generate core-v1} records core-v1's own blocks into its own family, beside values-v1's. */
  @Test
  void generateCoreV1WritesItsOwnFamily(@TempDir Path tmp) throws Exception {
    Path diff = tmp.resolve("diff");
    List<String> ids = new ArrayList<>();
    PrintStream out = System.out;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setOut(new PrintStream(captured, true, UTF_8));
    int rc;
    try {
      rc =
          Recorder.generate(
              List.of(CoreV1.NAME, "5", "8", diff.toString()),
              new Fake(
                  c -> {
                    ids.add(c.id());
                    return c.id().endsWith("/6") ? new Outcome(3, new byte[0], new byte[0]) : OK;
                  }));
    } finally {
      System.setOut(out);
    }

    assertThat(rc).isZero();
    assertThat(captured.toString(UTF_8)).isEqualTo("recorded 2 seeds of core-v1, dropped 1\n");
    assertThat(ids).containsExactly("gen/core-v1/5", "gen/core-v1/5", "gen/core-v1/6", "gen/core-v1/6", "gen/core-v1/7", "gen/core-v1/7");
    assertThat(Files.readString(diff.resolve("gen/core-v1.cases"))).isEqualTo(CoreV1.file(5, 8, Map.of(6L, "rc3")));
    List<CaseFormat.Block> blocks = CaseFormat.read(diff.resolve("gen/core-v1.cases"));
    assertThat(Recording.read(diff.resolve("gen/core-v1.rec"))).containsOnlyKeys("gen/core-v1/5", "gen/core-v1/7");
    assertThat(Recording.read(diff.resolve("gen/core-v1.rec")).get("gen/core-v1/7").def()).isEqualTo(blocks.get(1).def());
    assertThat(Files.exists(diff.resolve("gen/values-v1.cases"))).isFalse();
  }

  @Test
  void aMalformedGenerateIsAUsageError(@TempDir Path diff) throws Exception {
    String dir = diff.toString();
    PrintStream err = System.err;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true, UTF_8));
    try {
      assertThat(Recorder.run(List.of("--generate", "values-v2", "0", "1", dir))).isEqualTo(2);
      assertThat(Recorder.run(List.of("--generate", "values-v1", "0", "x", dir))).isEqualTo(2);
      assertThat(Recorder.run(List.of("--generate", "values-v1", "1", "1", dir))).isEqualTo(2);
      assertThat(Recorder.run(List.of("--generate", "values-v1", "-1", "1", dir))).isEqualTo(2);
      assertThat(Recorder.run(List.of("--generate", "values-v1", "0", "1"))).isEqualTo(2);
      assertThat(Recorder.run(List.of("--generate", "core-v2", "0", "1", dir))).isEqualTo(2);
    } finally {
      System.setErr(err);
    }
    assertThat(captured.toString(UTF_8).lines()).hasSize(6).allMatch(l -> l.contains("--generate values-v1|core-v1 <from> <to>"));
    try (var left = Files.list(diff)) {
      assertThat(left).isEmpty();
    }
  }
}
