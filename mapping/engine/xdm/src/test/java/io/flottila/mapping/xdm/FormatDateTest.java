package io.flottila.mapping.xdm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The date formatting core (ADR-0003): a date written with the picture a date pattern compiles to,
 * as Saxon-HE 12.5's format-dateTime writes it, and read back with the same picture by the strict
 * parse that mirrors it (#80). The written pairs were observed on Saxon-HE 12.5 and on JDK 25's
 * SimpleDateFormat with the pattern the picture came from.
 */
class FormatDateTest {

  private static final ZoneOffset KOLKATA = ZoneOffset.ofHoursMinutes(5, 30);

  @ParameterizedTest(name = "{0} {1} -> {2}")
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '"',
      textBlock =
          """
          2024-03-05T07:08:09.045+05:30 | [Y0001]-[M01]-[D01]T[H01]:[m01]:[s01].[f001][Z01:01t] | 2024-03-05T07:08:09.045+05:30
          2024-03-05T07:08:09.045Z      | [Y0001]-[M01]-[D01]T[H01]:[m01]:[s01].[f001][Z01:01t] | 2024-03-05T07:08:09.045Z
          2024-03-05T07:08:09-08:00     | [Z01:01t] [Z0000]                                     | -08:00 -0800
          2024-03-05T07:08:09Z          | [Z0000]                                               | +0000
          2024-03-05T07:08:09Z          | [D1].[M1].[Y0001] [H1]:[m1]:[s1]                      | 5.3.2024 7:8:9
          2024-11-25T17:48:59Z          | [D1].[M1].[Y0001] [H1]:[m1]:[s1]                      | 25.11.2024 17:48:59
          0099-01-01T00:00:00Z          | [Y0001]                                               | 0099
          2024-01-01T00:00:00.5Z        | [f001]                                                | 500
          2024-01-01T00:00:00Z          | [[[Y0001]]] at                                        | [2024] at
          """)
  void aDateIsWrittenAsFormatDateTimeWritesIt(String value, String picture, String written) {
    assertThat(FormatDate.format(OffsetDateTime.parse(value), picture)).isEqualTo(written);
  }

  @ParameterizedTest(name = "{0} {1} -> {2}")
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '"',
      textBlock =
          """
          2024-03-05                    | [Y0001]-[M01]-[D01]                                   | 2024-03-05T00:00+05:30
          5.3.2024                      | [D1].[M1].[Y0001]                                     | 2024-03-05T00:00+05:30
          25.11.2024                    | [D1].[M1].[Y0001]                                     | 2024-11-25T00:00+05:30
          07:08                         | [H01]:[m01]                                           | 1970-01-01T07:08+05:30
          2024-03-05T07:08:09.045Z      | [Y0001]-[M01]-[D01]T[H01]:[m01]:[s01].[f001][Z01:01t] | 2024-03-05T07:08:09.045Z
          2024-03-05T07:08:09.045-14:00 | [Y0001]-[M01]-[D01]T[H01]:[m01]:[s01].[f001][Z01:01t] | 2024-03-05T07:08:09.045-14:00
          202403050708+1400             | [Y0001][M01][D01][H01][m01][Z0000]                    | 2024-03-05T07:08+14:00
          202403050708-0000             | [Y0001][M01][D01][H01][m01][Z0000]                    | 2024-03-05T07:08Z
          07:08+05:59                   | [H01]:[m01][Z01:01t]                                  | 1970-01-01T07:08+05:59
          2024-02-29                   | [Y0001]-[M01]-[D01]                                   | 2024-02-29T00:00+05:30
          [2024]                        | [[[Y0001]]]                                           | 2024-01-01T00:00+05:30
          23:59:59                      | [H1]:[m1]:[s1]                                        | 1970-01-01T23:59:59+05:30
          """)
  void aTextIsReadWithThePictureAndTheImplicitOffset(String text, String picture, String date) {
    assertThat(FormatDate.parse(text, picture, KOLKATA)).contains(OffsetDateTime.parse(date));
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '"',
      textBlock =
          """
          2024-3-05                     | [Y0001]-[M01]-[D01]
          2024-03-05x                   | [Y0001]-[M01]-[D01]
          " 2024-03-05"                 | [Y0001]-[M01]-[D01]
          24-03-05                      | [Y0001]-[M01]-[D01]
          2024/03/05                    | [Y0001]-[M01]-[D01]
          123.3.2024                    | [D1].[M1].[Y0001]
          2023-02-29                    | [Y0001]-[M01]-[D01]
          2024-04-31                    | [Y0001]-[M01]-[D01]
          2024-13-01                    | [Y0001]-[M01]-[D01]
          2024-00-01                    | [Y0001]-[M01]-[D01]
          2024-01-00                    | [Y0001]-[M01]-[D01]
          0000-01-01                    | [Y0001]-[M01]-[D01]
          24:00                         | [H01]:[m01]
          23:60                         | [H01]:[m01]
          23:59:60                      | [H01]:[m01]:[s01]
          07:08+14:01                   | [H01]:[m01][Z01:01t]
          07:08+13:60                   | [H01]:[m01][Z01:01t]
          07:08z                        | [H01]:[m01][Z01:01t]
          0708+0530x                    | [H01][m01][Z0000]
          ""                            | [Y0001]
          """)
  void aTextThatWritesNoDateOfThePictureIsNone(String text, String picture) {
    assertThat(FormatDate.parse(text, picture, KOLKATA)).isEmpty();
  }

  @Test
  void aTextWithATrailingLineEndWritesNoDate() {
    assertThat(FormatDate.parse("2024\n", "[Y0001]", ZoneOffset.UTC)).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"[Y]", "[Y0001", "Y0001]", "[]", "[F]"})
  void aPictureNoDatePatternGivesIsRefused(String picture) {
    OffsetDateTime date = OffsetDateTime.parse("2024-03-05T07:08:09Z");
    assertThatThrownBy(() -> FormatDate.format(date, picture))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "the picture \"" + picture + "\" is none that a date pattern compiles to (ADR-0003)");
    assertThatThrownBy(() -> FormatDate.parse("2024", picture, ZoneOffset.UTC))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("is none that a date pattern compiles to");
    assertThatThrownBy(() -> FormatDate.picture(picture))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "the picture \"" + picture + "\" is none that a date pattern compiles to (ADR-0003)");
  }

  /**
   * A component is padded with zeros to its width, a sign counted in it, and a longer number is
   * written whole, as Java's {@code %0}<i>n</i>{@code d} pads it, with which the core wrote before
   * #194 (observed on that code): years beyond 9999 and before 1 come out of a date's offset shift.
   */
  @ParameterizedTest(name = "{0} {1} -> {2}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          +12345-06-07T08:09:01.002+01:02 | [Y0001] [M01] [D01] [H01] [m01] [s01] [f001] [Z0000] | 12345 06 07 08 09 01 002 +0102
          0000-01-01T00:00:00Z            | [Y0001]                                              | 0000
          -0005-12-31T23:59:59.999-14:00  | [Y0001] [M01] [D01] [H01] [m01] [s01] [f001] [Z0000] | -005 12 31 23 59 59 999 -1400
          2024-10-11T12:13:14.123-09:30   | [M01][D01][H01][m01][s01][f001][Z01:01t]             | 1011121314123-09:30
          """)
  void aComponentIsPaddedToItsWidth(String value, String picture, String written) {
    assertThat(FormatDate.format(OffsetDateTime.parse(value), picture)).isEqualTo(written);
  }

  /**
   * A picture is analysed once and then writes and reads every date as {@link FormatDate#format}
   * and {@link FormatDate#parse} do (#194), the same picture again and again.
   */
  @Test
  void anAnalysedPictureWritesAndReadsEveryDate() {
    FormatDate.Picture picture =
        FormatDate.picture("[Y0001]-[M01]-[D01]T[H01]:[m01]:[s01].[f001][Z01:01t]");
    FormatDate.Picture plain = FormatDate.picture("[D1].[M1].[Y0001] [H1]:[m1]:[s1]");

    for (int round = 0; round < 2; round++) {
      assertThat(picture.format(OffsetDateTime.parse("2024-03-05T07:08:09.045+05:30")))
          .isEqualTo("2024-03-05T07:08:09.045+05:30");
      assertThat(picture.format(OffsetDateTime.parse("2024-11-25T17:48:59Z")))
          .isEqualTo("2024-11-25T17:48:59.000Z");
      assertThat(picture.parse("2024-03-05T07:08:09.045-08:00", KOLKATA))
          .contains(OffsetDateTime.parse("2024-03-05T07:08:09.045-08:00"));
      assertThat(picture.parse("2024-03-05", KOLKATA)).isEmpty();
      assertThat(plain.format(OffsetDateTime.parse("2024-03-05T07:08:09Z")))
          .isEqualTo("5.3.2024 7:8:9");
      assertThat(plain.parse("25.11.2024 17:48:59", KOLKATA))
          .contains(OffsetDateTime.parse("2024-11-25T17:48:59+05:30"));
    }
  }
}
