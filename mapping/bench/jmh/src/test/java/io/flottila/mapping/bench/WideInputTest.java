package io.flottila.mapping.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wide.WideGenerator;

/**
 * The digest test of the {@code wide} generator (#181, #186): every run of a benchmark measures the
 * same bytes, and the small input is the differential case {@code bench/wide/001}. A change of the
 * generator or of {@code bench.json} changes the pin, and is a finding that resets the mapping's
 * baselines (#181, user story 21).
 */
class WideInputTest {

  /** The SHA-256 of the small input, hex. */
  private static final String SMALL_SHA256 =
      "d5d7042ecebfd818e157ea2cc486d8e4f3b2f7733fb3f7281401e62e1b7e6122";

  /** The SHA-256 of the large input, hex. */
  private static final String LARGE_SHA256 =
      "4e6aad7a3e3c900144e9bcc0ab0a9b2fae7e5a8b37ad6502fb3dbbbb9f93a545";

  @Test
  void theSmallInputIsPinned() throws Exception {
    byte[] small = WideBench.wide().small();

    // 8-10 kB under either reading of a kB (ADR-0021)
    assertThat(small.length).as("the small input's size").isBetween(8192, 10_000);
    assertThat(sha256(small)).as("the small input's SHA-256").isEqualTo(SMALL_SHA256);
  }

  @Test
  void theLargeInputIsPinned() throws Exception {
    byte[] large = WideBench.wide().large();

    // at least 10,485,760 B (10 MiB), so at least 10 MB under either reading of a MB (#181)
    assertThat(large.length)
        .as("the large input's size, the param inputBytes of the large benchmarks")
        .isEqualTo(Integer.parseInt(WideBench.LARGE_BYTES))
        .isGreaterThanOrEqualTo(10_485_760);
    assertThat(sha256(large)).as("the large input's SHA-256").isEqualTo(LARGE_SHA256);
  }

  @Test
  void theGeneratorsCommandWritesThePinnedInputs(@TempDir Path dir) throws Exception {
    Path small = dir.resolve("wide-small.xml");
    Path large = dir.resolve("wide-large.xml");

    WideGenerator.main(new String[] {"small", small.toString()});
    WideGenerator.main(new String[] {"large", large.toString()});

    assertThat(sha256(Files.readAllBytes(small))).as("small's SHA-256").isEqualTo(SMALL_SHA256);
    assertThat(sha256(Files.readAllBytes(large))).as("large's SHA-256").isEqualTo(LARGE_SHA256);
  }

  @Test
  void theGeneratorsCommandNamesItsUsage(@TempDir Path dir) {
    String file = dir.resolve("wide.xml").toString();

    for (String[] args :
        List.of(new String[] {"medium", file}, new String[] {"small"}, new String[] {})) {
      assertThatThrownBy(() -> WideGenerator.main(args))
          .as("%s", List.of(args))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("<small|large> <output file>");
    }
    assertThat(dir.resolve("wide.xml")).doesNotExist();
  }

  @Test
  void theSmallInputIsTheDifferentialCase() throws Exception {
    Path committed =
        Path.of(System.getProperty("flottila.corpus")).resolve("cases/bench/wide/001/input.xml");

    assertThat(WideBench.wide().small()).isEqualTo(Files.readAllBytes(committed));
  }

  private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
