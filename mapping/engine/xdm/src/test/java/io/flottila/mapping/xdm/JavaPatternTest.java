package io.flottila.mapping.xdm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.xdm.JavaPattern.Run;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The runs of a Java/SAP pattern (ADR-0003): quoted text as DecimalFormat and SimpleDateFormat
 * quote it, {@code ''} being one quote inside quotes and outside, and the unquoted pattern
 * characters between.
 */
class JavaPatternTest {

  @Test
  void quotedTextAndPatternCharactersAreRunsInPatternOrder() {
    assertThat(JavaPattern.runs("'EUR '#,##0 'net'"))
        .containsExactly(new Run("EUR ", true), new Run("#,##0 ", false), new Run("net", true));
  }

  @Test
  void twoQuotesAreOneQuoteOutsideQuotesAndInside() {
    assertThat(JavaPattern.runs("0''")).containsExactly(new Run("0", false), new Run("'", true));
    assertThat(JavaPattern.runs("'o''clock'")).containsExactly(new Run("o'clock", true));
    assertThat(JavaPattern.runs("''''")).containsExactly(new Run("'", true), new Run("'", true));
  }

  @Test
  void anEmptyPatternHasNoRuns() {
    assertThat(JavaPattern.runs("")).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"'abc", "a'b'c'"})
  void anUnterminatedQuoteIsRefused(String pattern) {
    assertThatThrownBy(() -> JavaPattern.runs(pattern))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("the pattern \"" + pattern + "\" has a quote that is never closed");
  }
}
