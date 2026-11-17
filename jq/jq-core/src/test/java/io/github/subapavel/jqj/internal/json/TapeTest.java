package io.github.subapavel.jqj.internal.json;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.subapavel.jqj.JqSyntaxException;
import io.github.subapavel.jqj.internal.value.Tape;
import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Below the API, the one exception #22's Testing Decisions allow: the tape entry (ADR-0002) and the tape size
 * limits (ADR-0002, ADR-0015, F-21), which the API seam cannot reach at their real sizes. The names are the
 * #32 designer draft's: {@code internal.value.Tape} (static only), {@code internal.json.TapeLimits(int document,
 * int string, int number, int container)} with {@code ADR_0002}, and {@code internal.json.JsonReader} with
 * {@code read(MemorySegment)}.
 */
class TapeTest {

  /** ADR-0002: the span is 28 bits. */
  private static final int SPAN_MAX = 0x0FFF_FFFF;

  /** CODING_STANDARDS: one case per seed, the seed in the case name. */
  static LongStream seeds() {
    return LongStream.range(0, 16);
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void tapeEntryRoundTrips(long seed) {
    RandomGenerator random = new SplittableRandom(seed);
    // ADR-0002: a 4-bit tag, a 28-bit span and a 32-bit offset, each at 0, at its field maximum and at a random
    // value; the offset's all-ones field is the int -1.
    int[] spans = {0, SPAN_MAX, random.nextInt(0, SPAN_MAX + 1)};
    int[] offsets = {0, Integer.MAX_VALUE, -1, random.nextInt()};
    for (int tag = 0; tag <= 15; tag++) {
      for (int span : spans) {
        for (int off : offsets) {
          assertRoundTrip(tag, span, off);
        }
      }
    }
    for (int i = 0; i < 1_000; i++) {
      assertRoundTrip(random.nextInt(0, 16), random.nextInt(0, SPAN_MAX + 1), random.nextInt());
    }
  }

  private static void assertRoundTrip(int tag, int span, int off) {
    long e = Tape.entry(tag, span, off);
    assertThat(Tape.tag(e)).as("tag of entry(%d, %d, %d)", tag, span, off).isEqualTo(tag);
    assertThat(Tape.span(e)).as("span of entry(%d, %d, %d)", tag, span, off).isEqualTo(span);
    assertThat(Tape.off(e)).as("off of entry(%d, %d, %d)", tag, span, off).isEqualTo(off);
    // ADR-0002: "A tag >= 8 has the sign bit set".
    assertThat(e < 0).as("sign of entry(%d, %d, %d)", tag, span, off).isEqualTo(tag >= 8);
  }

  @Test
  void tapeLimitsAreAdr0002s() {
    // ADR-0002 "Limits" as amended by F-12 and F-21, the largest legal values.
    assertThat(TapeLimits.ADR_0002.document()).as("bytes per document, 2^31-3").isEqualTo(2_147_483_645);
    assertThat(TapeLimits.ADR_0002.string()).as("bytes per string with its quotes, 2^28-1").isEqualTo(268_435_455);
    assertThat(TapeLimits.ADR_0002.number()).as("bytes per number literal, 2^24-1").isEqualTo(16_777_215);
    assertThat(TapeLimits.ADR_0002.container()).as("entries per container, 2^28-1").isEqualTo(268_435_455);
  }

  @Test
  void eachSizeLimitHoldsAtItsEdge() {
    // Each small limit differs from the others, so a limit checked against the wrong kind shows.
    JsonReader small = new JsonReader(new TapeLimits(1_000, 6, 4, 3));

    // A string's span includes its quotes; a key is a string too.
    assertThat(kind(JqString.class, small.read(segment("\"abcd\"")).root()).value()).as("6-byte string").isEqualTo("abcd");
    assertOverLimit(small, "\"abcde\"", "string");
    assertThat(kind(JqObject.class, small.read(segment("{\"abcd\":1}")).root()).size()).as("6-byte key").isEqualTo(1);
    assertOverLimit(small, "{\"abcde\":1}", "string");

    // A number's length is its token's bytes, sign and fraction included.
    assertThat(kind(JqNumber.class, small.read(segment("1234")).root()).longValue()).as("4-byte number").isEqualTo(1234L);
    assertThat(kind(JqNumber.class, small.read(segment("-123")).root()).longValue()).as("4-byte negative").isEqualTo(-123L);
    assertThat(kind(JqNumber.class, small.read(segment("1.25")).root()).fitsLong()).as("4-byte fraction").isFalse();
    assertOverLimit(small, "12345", "number");
    assertOverLimit(small, "-1234", "number");
    assertOverLimit(small, "1.255", "number");

    // A container's span counts every entry inside it, nested ones and keys included (ADR-0002).
    assertThat(kind(JqArray.class, small.read(segment("[1,2,3]")).root()).length()).as("3 entries").isEqualTo(3);
    assertOverLimit(small, "[1,2,3,4]", "container");
    assertThat(kind(JqArray.class, small.read(segment("[[1,2]]")).root()).length()).as("[[1,2]]: 3 entries").isEqualTo(1);
    assertOverLimit(small, "[[1,2],3]", "container");
    assertThat(kind(JqObject.class, small.read(segment("{\"a\":[1]}")).root()).size()).as("{\"a\":[1]}: 3 entries").isEqualTo(1);
    assertOverLimit(small, "{\"a\":1,\"b\":2}", "container");

    // The document limit counts every byte of the segment, whitespace included.
    JsonReader tenBytes = new JsonReader(new TapeLimits(10, 268_435_455, 16_777_215, 268_435_455));
    assertThat(kind(JqString.class, tenBytes.read(segment("\"12345678\"")).root()).value()).as("10-byte document").isEqualTo("12345678");
    assertOverLimit(tenBytes, "\"123456789\"", "document");
    assertOverLimit(tenBytes, "\"12345678\" ", "document");

    // ADR_0002's number limit at its real size: 2^24-1 digits read, 2^24 do not.
    JsonReader real = new JsonReader(TapeLimits.ADR_0002);
    JqNumber longest = kind(JqNumber.class, real.read(MemorySegment.ofArray(digits(16_777_215))).root());
    assertThat(longest.fitsLong()).as("a 2^24-1-digit number fits no long").isFalse();
    assertOverLimit(real, MemorySegment.ofArray(digits(16_777_216)), "number");
  }

  /** A 1 followed by zeros, {@code length} digits in all. */
  private static byte[] digits(int length) {
    byte[] digits = new byte[length];
    Arrays.fill(digits, (byte) '0');
    digits[0] = '1';
    return digits;
  }

  private static MemorySegment segment(String json) {
    return MemorySegment.ofArray(json.getBytes(UTF_8));
  }

  private static <T extends JqValue> T kind(Class<T> type, JqValue value) {
    assertThat(value).isInstanceOf(type);
    return type.cast(value);
  }

  private static void assertOverLimit(JsonReader reader, String json, String limit) {
    assertOverLimit(reader, segment(json), limit);
  }

  /** ADR-0015 "Limits": "Exceeds {document|string|number|container} size limit for parsing at line L, column C". */
  private static void assertOverLimit(JsonReader reader, MemorySegment json, String limit) {
    assertThatThrownBy(() -> reader.read(json))
        .as("a %d-byte document over the %s limit", json.byteSize(), limit)
        .isInstanceOf(JqSyntaxException.class)
        .hasMessageStartingWith("Exceeds " + limit + " size limit for parsing at line ");
  }
}
