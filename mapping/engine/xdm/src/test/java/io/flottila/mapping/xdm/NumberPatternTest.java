package io.flottila.mapping.xdm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A {@code DecimalFormat} pattern compiled to the XPath picture that formats every number as
 * DecimalFormat does with the symbols {@code .} and {@code ,} (ADR-0003). The pairs were observed
 * on JDK 25's DecimalFormat over a BigDecimal and on Saxon-HE 12.5's format-number (#74). A pattern
 * whose output no picture gives is refused, naming the element that cannot be translated. In the
 * tables a value is in double quotes when it starts with {@code #} or is empty.
 */
class NumberPatternTest {

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '"',
      textBlock =
          """
          0                 | 0
          000               | 000
          "#"               | "#"
          "##"              | "#"
          0.00              | 0.00
          0.##              | 0.##
          "#.##"            | 0.##
          "##0.#"           | 0.#
          .00               | "#.00"
          .##               | "#.##"
          "#.0"             | "#.0"
          "#,##0.00"        | "#,##0.00"
          "#,##,##0"        | "#,##0"
          0,000             | 0,000
          000,000           | 000,000
          0000,00           | 00,00,00
          "#,#"             | "#,#"
          0.#%              | 0.#%
          0‰                | 0‰
          "#,##0.00;(0)"    | "#,##0.00;(#,##0.00)"
          0%;(0%)           | 0%;(0%)
          0;0-              | 0;0-
          0;-0              | 0;-0
          'EUR '#,##0.00    | EUR #,##0.00
          0 'o''clock'      | 0 o'clock
          0''               | 0'
          0.0 m             | 0.0 m
          -0                | -0
          0 E               | 0 E
          E0                | E0
          0 each            | 0 each
          '¤'0              | ¤0
          """)
  void aPatternBecomesThePictureThatFormatsAsItDoes(String pattern, String picture) {
    assertThat(NumberPattern.picture(pattern)).isEqualTo(picture);
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '"',
      textBlock =
          """
          ""                | it is empty
          0.00E0            | the exponent E
          0E0               | the exponent E
          0 ¤               | the currency sign ¤
          ¤0                | the currency sign ¤
          0.                | the decimal separator has no digit after it
          "#."              | the decimal separator has no digit after it
          '#'0              | the quoted text # holds a character
          0'%'              | the quoted text % holds a character
          'x0'0             | the quoted text x0 holds a character
          5#                | the digit 5
          0 9               | the digit 9
          "#,##0;#"         | a negative subpattern with the positive one's prefix and suffix
          0;                | a subpattern has no digit
          0;(               | a subpattern has no digit
          abc               | a subpattern has no digit
          0;0;0             | more than two subpatterns
          0%;(0)            | a percent or per mille sign in one subpattern only
          0;(0%)            | a percent or per mille sign in one subpattern only
          0%%               | more than one percent or per mille sign
          0%‰               | more than one percent or per mille sign
          0e                | the letter e next to
          %e0               | the letter e next to
          e0                | the letter e next to
          0 0               | the digit or separator 0 after the suffix began
          0 .               | the digit or separator . after the suffix began
          0#                | the integer digits 0#
          ",##0"            | the integer digits ,##0
          "#,,##0"          | the integer digits #,,##0
          "#,.0"            | the integer digits #,
          .#0               | the fraction digits #0
          0.0.0             | the fraction digits 0.0
          'abc              | a quote that is never closed
          """)
  void aPatternNoPictureFormatsLikeIsRefusedWithItsElement(String pattern, String element) {
    assertThatThrownBy(() -> NumberPattern.picture(pattern))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("\"" + pattern + "\"")
        .hasMessageContaining(element);
  }
}
