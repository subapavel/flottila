package io.github.subapavel.jqj.bench;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import io.github.subapavel.jqj.bench.Inputs.Size;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The inputs of ADR-0019 for the bench ids of ADR-0024: the `orders` size per id, the shape and the determinism of
 * `orders`, `recs` (G3) and `ints` (G4).
 */
class InputsTest {

  /** Every ADR-0024 bench id with the input size it runs on. */
  static Stream<Arguments> benchIds() {
    return Stream.of(
        argumentSet("G1.1k", Size.K1, 1_024),
        argumentSet("G1.100k", Size.K100, 102_400),
        argumentSet("G1.10m", Size.M10, 10_485_760),
        argumentSet("G2.1k", Size.K1, 1_024),
        argumentSet("G2.100k", Size.K100, 102_400),
        argumentSet("G2.10m", Size.M10, 10_485_760),
        argumentSet("G6.compact.10m", Size.M10, 10_485_760),
        argumentSet("G6.pretty.10m", Size.M10, 10_485_760),
        argumentSet("G7.engine.1k", Size.K1, 1_024),
        argumentSet("G7.process.1k", Size.K1, 1_024));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("benchIds")
  void theInputOfEachBenchIdHasExactlyItsSize(Size size, int bytes) {
    assertThat(size.bytes()).isEqualTo(bytes);
    assertThat(Inputs.orders(size)).hasSize(bytes);
  }

  /**
   * The digests freeze the generator: jqj's JMH times and jq-ref's times are comparable across commits only while the
   * bytes stay the same. A changed generator is a new input, recorded as such in bench/RESULTS.md.
   */
  static Stream<Arguments> digests() {
    return Stream.of(
        argumentSet("1k", Size.K1, "6f9455ed0d9c05f80cb263cac7bb421833e63f26c72898602fb5728998844cfc"),
        argumentSet("100k", Size.K100, "cdd6d4b62eff0b6d58e17445d9e53bad573562cf79bd4955496641501ea86b91"),
        argumentSet("10m", Size.M10, "2c8135089c4516ad77990e3ca01e2a36f4cf61b3843c269acaeec227dab1a547"));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("digests")
  void theBytesOfEachSizeAreFixed(Size size, String sha256) {
    assertThat(sha256(Inputs.orders(size))).isEqualTo(sha256);
  }

  @ParameterizedTest
  @EnumSource(Size.class)
  void twoCallsGiveTheSameBytes(Size size) {
    assertThat(Inputs.orders(size)).isEqualTo(Inputs.orders(size));
  }

  /** ADR-0019's shape, with `.a.b.c` after both arrays so G1 passes over the whole document. */
  @ParameterizedTest
  @EnumSource(Size.class)
  void theDocumentIsOrdersThenItemsThenA(Size size) {
    String text = new String(Inputs.orders(size), US_ASCII);
    assertThat(text).startsWith("{\"orders\":[{\"id\":0,\"total\":");
    assertThat(text).contains("],\"items\":[{\"sku\":\"s0\",\"price\":");
    assertThat(text.stripTrailing()).endsWith("],\"a\":{\"b\":{\"c\":\"x\"}}}");
    assertThat(text).endsWith("\n");
  }

  @Test
  void theFirstRecordsAreAsDocumented() {
    String text = new String(Inputs.orders(Size.K1), US_ASCII);
    assertThat(text)
        .startsWith(
            "{\"orders\":[{\"id\":0,\"total\":0.13,\"k\":\"k0\"},{\"id\":1,\"total\":79.32,\"k\":\"k1\"},");
    assertThat(text).contains("],\"items\":[{\"sku\":\"s0\",\"price\":1},{\"sku\":\"s1\",\"price\":4731},");
  }

  @Test
  void mainWritesOneFilePerSizeAndTheIntsOfG4(@TempDir Path dir) throws IOException {
    Inputs.main(new String[] {dir.toString()});
    for (Size size : Size.values()) {
      assertThat(Files.readAllBytes(dir.resolve("orders-" + size.label() + ".json"))).isEqualTo(Inputs.orders(size));
      assertThat(Files.readAllBytes(dir.resolve("recs-" + size.label() + ".json"))).isEqualTo(Inputs.recs(size));
    }
    assertThat(Files.readAllBytes(dir.resolve("ints-1e4.json"))).isEqualTo(Inputs.ints(10_000));
  }

  /** The `recs` digests freeze its generator and its seed, as the `orders` digests do. */
  static Stream<Arguments> recsDigests() {
    return Stream.of(
        argumentSet("1k", Size.K1, "866530aea93f12345b517a6a9fe31d83d24f13aeaa7ea3b7742855dfe297934f"),
        argumentSet("100k", Size.K100, "f737263174752da1eedff25a9076a165674b9537067d9fa31ed1c9d871c23efd"),
        argumentSet("10m", Size.M10, "d8fdb99b862c0937528b63a811c8eb8c364235ac4f14b9435d30ead708e1f454"));
  }

  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("recsDigests")
  void theRecsBytesOfEachSizeAreFixed(Size size, String sha256) {
    assertThat(Inputs.recs(size)).hasSize(size.bytes());
    assertThat(sha256(Inputs.recs(size))).isEqualTo(sha256);
  }

  /** ADR-0019's `recs` shape: one array of `{"k":"k0".."k63","v":n}`, then padding and the LF. */
  @ParameterizedTest
  @EnumSource(Size.class)
  void theRecsDocumentIsOneArrayOfKeyedRecords(Size size) {
    String text = new String(Inputs.recs(size), US_ASCII);
    assertThat(text).endsWith("\n");
    String array = text.stripTrailing();
    assertThat(array).startsWith("[{").endsWith("}]");
    for (String rec : array.substring(2, array.length() - 2).split("\\},\\{", -1)) {
      assertThat(rec).matches("\"k\":\"k([0-9]|[1-5][0-9]|6[0-3])\",\"v\":(0|[1-9][0-9]{0,5})");
    }
  }

  /** A smaller document holds the first records of a larger one: the draws come in the same order. */
  @Test
  void theRecsOf1kArePrefixOfTheRecsOf100k() {
    String small = new String(Inputs.recs(Size.K1), US_ASCII).stripTrailing();
    String large = new String(Inputs.recs(Size.K100), US_ASCII);
    assertThat(large).startsWith(small.substring(0, small.length() - 1) + ",");
  }

  @ParameterizedTest
  @EnumSource(Size.class)
  void theIntsOfEachSizeAreZeroUpwardsPaddedToTheSize(Size size) {
    byte[] bytes = Inputs.ints(size);
    assertThat(bytes).hasSize(size.bytes());
    String array = new String(bytes, US_ASCII).stripTrailing();
    String[] ints = array.substring(1, array.length() - 1).split(",", -1);
    for (int i = 0; i < ints.length; i++) {
      assertThat(ints[i]).isEqualTo(Integer.toString(i));
    }
    // Packed: the next integer would not have fit.
    assertThat(array.length() + 1 + Integer.toString(ints.length).length() + 1).isGreaterThan(size.bytes());
  }

  @Test
  void theIntsOf1kAreZeroTo282() {
    assertThat(sha256(Inputs.ints(Size.K1))).isEqualTo("21da99301cb91cde1cc1c6905940ddcace13246e44b20a65f08fce40c07305cd");
    assertThat(new String(Inputs.ints(Size.K1), US_ASCII)).startsWith("[0,1,2,").endsWith(",281,282]\n");
  }

  @Test
  void theIntsOfG4AreZeroTo9999WithNoPadding() {
    assertThat(Inputs.INTS_1E4).isEqualTo(10_000);
    assertThat(sha256(Inputs.ints(Inputs.INTS_1E4)))
        .isEqualTo("185223f3feacfcffb2e6eef5e787cbdaa7984fa82127c4ffd16602a793abf172");
    assertThat(new String(Inputs.ints(3), US_ASCII)).isEqualTo("[0,1,2]\n");
    assertThat(new String(Inputs.ints(0), US_ASCII)).isEqualTo("[]\n");
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }
}
