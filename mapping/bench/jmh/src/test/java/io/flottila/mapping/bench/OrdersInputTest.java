package io.flottila.mapping.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import orders.OrdersGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The digest test of the {@code orders} generator (#181, #183): every run of a benchmark measures
 * the same bytes, and the small input is the differential case {@code bench/orders/001}. A change
 * of the generator or of {@code bench.json} changes the pin, and is a finding that resets the
 * mapping's baselines (#181, user story 21).
 */
class OrdersInputTest {

  /** The SHA-256 of the small input, hex. */
  private static final String SMALL_SHA256 =
      "67ed8560c684e9078f7c52a2ebe88416c2795946c9f829526dd2f7f53ef3a8ed";

  /** The SHA-256 of the large input, hex. */
  private static final String LARGE_SHA256 =
      "467cb060fa1ac81b46b459f4b6e82d970ec18f33bdc00785df5be1a58032d593";

  @Test
  void theSmallInputIsPinned() throws Exception {
    byte[] small = BenchMapping.orders().small();

    // 8-10 kB under either reading of a kB (ADR-0021)
    assertThat(small.length).as("the small input's size").isBetween(8192, 10_000);
    assertThat(sha256(small)).as("the small input's SHA-256").isEqualTo(SMALL_SHA256);
  }

  @Test
  void theLargeInputIsPinned() throws Exception {
    byte[] large = BenchMapping.orders().large();

    // at least 10,485,760 B (10 MiB), so at least 10 MB under either reading of a MB (#181)
    assertThat(large.length)
        .as("the large input's size, the param inputBytes of the large benchmarks")
        .isEqualTo(Integer.parseInt(OrdersBench.LARGE_BYTES))
        .isGreaterThanOrEqualTo(10_485_760);
    assertThat(sha256(large)).as("the large input's SHA-256").isEqualTo(LARGE_SHA256);
  }

  @Test
  void theGeneratorsCommandWritesThePinnedInputs(@TempDir Path dir) throws Exception {
    Path small = dir.resolve("orders-small.xml");
    Path large = dir.resolve("orders-large.xml");

    OrdersGenerator.main(new String[] {"small", small.toString()});
    OrdersGenerator.main(new String[] {"large", large.toString()});

    assertThat(sha256(Files.readAllBytes(small))).as("small's SHA-256").isEqualTo(SMALL_SHA256);
    assertThat(sha256(Files.readAllBytes(large))).as("large's SHA-256").isEqualTo(LARGE_SHA256);
  }

  @Test
  void theGeneratorsCommandNamesItsUsage(@TempDir Path dir) {
    String file = dir.resolve("orders.xml").toString();

    for (String[] args :
        List.of(new String[] {"medium", file}, new String[] {"small"}, new String[] {})) {
      assertThatThrownBy(() -> OrdersGenerator.main(args))
          .as("%s", List.of(args))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("<small|large> <output file>");
    }
    assertThat(dir.resolve("orders.xml")).doesNotExist();
  }

  @Test
  void theSmallInputIsTheDifferentialCase() throws Exception {
    Path committed =
        Path.of(System.getProperty("flottila.corpus")).resolve("cases/bench/orders/001/input.xml");

    assertThat(BenchMapping.orders().small()).isEqualTo(Files.readAllBytes(committed));
  }

  private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
