package io.github.subapavel.jqj.internal.value;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The FITS_LONG flag of tonumber's literals (ADR-0012): an integer token in the long range, never -0. Only the value
 * order reads the flag, where -0 and 0 are equal either way, so no filter sees it; this pins the invariant, which a
 * document's -0 keeps too (its tape's flag is false).
 */
class LiteralNumberTextTest {
  @Test
  void minusZeroNeverFitsALong() {
    assertThat(((LiteralNumber) LiteralNumber.ofText("-0".getBytes(US_ASCII))).fitsLong()).as("FITS_LONG of tonumber's -0").isFalse();
    assertThat(((LiteralNumber) LiteralNumber.ofText("-007".getBytes(US_ASCII))).fitsLong()).as("FITS_LONG of tonumber's -7").isTrue();
    assertThat(((LiteralNumber) LiteralNumber.ofText("+0".getBytes(US_ASCII))).fitsLong()).as("FITS_LONG of tonumber's 0").isTrue();
  }
}
