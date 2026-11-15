package io.flottila.mapping.xdm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A {@code SimpleDateFormat} pattern compiled to the XPath picture that writes every date as
 * SimpleDateFormat does (ADR-0003). The pairs were observed on JDK 25's SimpleDateFormat and on
 * Saxon-HE 12.5's format-dateTime (#80). A pattern whose output no picture gives, or that a strict
 * parse cannot read back, is refused, naming the element that cannot be translated.
 */
class DatePatternTest {

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '"',
      textBlock =
          """
          yyyy-MM-dd                    | [Y0001]-[M01]-[D01]
          yyyyMMdd                      | [Y0001][M01][D01]
          d.M.yyyy                      | [D1].[M1].[Y0001]
          HH:mm:ss                      | [H01]:[m01]:[s01]
          H:m:s                         | [H1]:[m1]:[s1]
          yyyy-MM-dd'T'HH:mm:ss.SSSXXX  | [Y0001]-[M01]-[D01]T[H01]:[m01]:[s01].[f001][Z01:01t]
          yyyyMMddHHmmssZ               | [Y0001][M01][D01][H01][m01][s01][Z0000]
          HHmmZ                         | [H01][m01][Z0000]
          H:mXXX                        | [H1]:[m1][Z01:01t]
          'at' HH 'o''clock'            | at [H01] o'clock
          yyyy''MM                      | [Y0001]'[M01]
          [yyyy]                        | [[[Y0001]]]
          '[x]' dd                      | [[x]] [D01]
          -/.,:; #0                     | -/.,:; #0
          `{HH}~@_                      | `{[H01]}~@_
          """)
  void aPatternBecomesThePictureThatWritesAsItDoes(String pattern, String picture) {
    assertThat(DatePattern.picture(pattern)).isEqualTo(picture);
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '"',
      textBlock =
          """
          ""                 | it is empty
          yy-MM-dd           | the field yy of that length
          y                  | the field y of that length
          MMM yyyy           | the field MMM of that length
          ddd                | the field ddd of that length
          HH:mm:ss.S         | the field S of that length
          ZZ                 | the field ZZ of that length
          HHX                | the field X of that length
          hh:mm a            | the pattern letter h
          EEE                | the pattern letter E
          HH a               | the pattern letter a
          HH z               | the pattern letter z
          A                  | the pattern letter A
          'T'Tyyyy           | the pattern letter T
          dd.MM.yyyy dd      | the field d stands twice
          HHZXXX             | the field X stands twice
          yyyyMd             | the field M of one letter is followed directly by the number d
          Hmm                | the field H of one letter is followed directly by the number m
          'x                 | a quote that is never closed
          """)
  void aPatternWithoutAPictureIsRefusedNamingItsElement(String pattern, String element) {
    assertThatThrownBy(() -> DatePattern.picture(pattern))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(element);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({"HXXX", "d'.'M", "MZ"})
  void aFieldOfOneLetterMayStandBeforeAZoneOrLiteralText(String pattern) {
    assertThat(DatePattern.picture(pattern)).isNotEmpty();
  }

  @Test
  void theMessageNamesThePattern() {
    assertThatThrownBy(() -> DatePattern.picture("yy"))
        .hasMessage("the date pattern \"yy\" has no XPath picture: the field yy of that length");
  }
}
