package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.unlimited;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqValue;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * 10⁵ nested built arrays print without a StackOverflowError, compact and pretty (#64 design (b): a non-recursive
 * printer walk), and with jq's print depth: a value inside the 10 001st level prints {@code <skipped: too deep>}
 * (ADR-0015). In spec 2a no filter builds that depth in one run (no reduce yet, and jq's grammar refuses so deep a
 * filter, #81), so each run wraps its input, a built value, in 100 more arrays, and 1 000 runs make 10⁵ levels around
 * null. The oracle is jq-ref 1.8.2, observed 2026-11-12 on {@code reduce range(100000) as $i (null; [.])}: under
 * {@code -nc} 20 022 bytes, 10 001 brackets, {@code <skipped: too deep>}, 10 001 brackets and an LF, sha256
 * 7abcf37e…; without {@code -c} 200 080 026 bytes, sha256 e5f24d8d…, the skip on line 10 002; {@code tojson | length}
 * 20 021.
 */
class DeepBuiltArrayTest {
  private static final int LEVELS_PER_RUN = 100;
  private static final int RUNS = 1_000;

  private static final String COMPACT_SHA256 = "7abcf37eb01d1e0b54a9c839054ae3e6c3f8708bb8690e63c32de30f376fd622";
  private static final String PRETTY_SHA256 = "e5f24d8dff8ce043cdb101a774c534341401674c31d2c33c2c9837cc7f2d8bce";

  @Test
  void hundredThousandNestedBuiltArraysPrintCompactAndPrettyAsJqDoes() {
    JqEngine engine = unlimited();
    JqFilter wrap = engine.compile("[".repeat(LEVELS_PER_RUN) + "." + "]".repeat(LEVELS_PER_RUN));
    Workspace workspace = engine.newWorkspace();
    JqValue value = JqNull.NULL;
    for (int run = 0; run < RUNS; run++) {
      Results results = new Results();
      assertThat(wrap.run(value, NONE, results, workspace)).as("run %d's answer", run).isTrue();
      assertThat(results.values).as("results of run %d", run).hasSize(1);
      value = results.values.getFirst();
    }

    DigestTarget compact = new DigestTarget();
    assertThat(JsonWriter.sink(workspace, compact, OutputOptions.compact()).emit(value)).as("emit's answer, compact").isTrue();
    assertThat(compact.count).as("bytes printed compact").isEqualTo(20_022L);
    assertThat(compact.head()).as("first bytes printed compact").isEqualTo("[".repeat(64));
    assertThat(compact.sha256()).as("sha256 of the compact output").isEqualTo(COMPACT_SHA256);

    DigestTarget pretty = new DigestTarget();
    assertThat(JsonWriter.sink(workspace, pretty, OutputOptions.pretty()).emit(value)).as("emit's answer, pretty").isTrue();
    assertThat(pretty.count).as("bytes printed pretty").isEqualTo(200_080_026L);
    assertThat(pretty.head()).as("first bytes printed pretty").startsWith("[\n  [\n    [\n");
    assertThat(pretty.sha256()).as("sha256 of the pretty output").isEqualTo(PRETTY_SHA256);

    // JqValue.toJson is the compact text without its LF.
    String json = value.toJson();
    assertThat(json.length()).as("length of toJson").isEqualTo(20_021);
    assertThat(json).as("toJson").isEqualTo("[".repeat(10_001) + "<skipped: too deep>" + "]".repeat(10_001));
  }

  /** A writer's target that keeps a digest, a count and the first 64 bytes of what it is given. */
  private static final class DigestTarget extends OutputStream {
    private final MessageDigest digest;
    private final byte[] head = new byte[64];
    long count;

    DigestTarget() {
      try {
        digest = MessageDigest.getInstance("SHA-256");
      } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    public void write(int b) {
      write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) {
      for (int i = 0; i < len && count + i < head.length; i++) {
        head[(int) count + i] = b[off + i];
      }
      digest.update(b, off, len);
      count += len;
    }

    String head() {
      return new String(Arrays.copyOf(head, (int) Math.min(count, head.length)), java.nio.charset.StandardCharsets.US_ASCII);
    }

    String sha256() {
      return HexFormat.of().formatHex(digest.digest());
    }
  }
}
