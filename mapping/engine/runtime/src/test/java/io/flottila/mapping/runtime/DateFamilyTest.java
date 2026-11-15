package io.flottila.mapping.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.xdm.QueueBlock;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The date family (#80), called through {@link SingleValueLibrary} as a {@code Lift}'s body calls
 * it, in a run whose time is {@link #UTC} or {@link #PLUS_ONE}: a date is read strictly in its
 * pattern (ADR-0003), in the run's offset when the pattern has none, and written in the run's
 * offset; a date its pattern does not read is {@code err:FORG0001} at the function box. The corpus
 * cases {@code fn/currentDate} to {@code fn/compareDates} pin the same through the facade and
 * against Saxon-HE.
 */
class DateFamilyTest {

  private static final AuthoringId BOX = new AuthoringId("D1");

  /** A run's time in UTC. */
  private static final OffsetDateTime UTC = OffsetDateTime.parse("2026-11-04T12:00:00Z");

  /** A run's time one hour ahead of UTC. */
  private static final OffsetDateTime PLUS_ONE = OffsetDateTime.parse("2026-11-04T13:00:00+01:00");

  private static String call(OffsetDateTime now, String name, String... args) {
    List<Node.OfString> nodes = Arrays.stream(args).<Node.OfString>map(a -> f -> a).toList();
    Frame frame = new Frame(new QueueBlock[0], null, null, 0, 0, 0, now);
    return SingleValueLibrary.bind(name, BOX, nodes).eval(frame);
  }

  private static void assertDataError(String name, String message, String... args) {
    assertThatThrownBy(() -> call(UTC, name, args))
        .isInstanceOfSatisfying(
            MappingRunException.class,
            e -> {
              assertThat(e.code()).isEqualTo(RuntimeErrors.FORG0001);
              assertThat(e.authoringId()).isEqualTo(BOX);
              assertThat(e.getMessage()).isEqualTo("the function box D1: " + name + ": " + message);
            });
  }

  @Test
  void currentDateWritesTheRunsTimeInItsOwnOffset() {
    assertThat(call(UTC, "currentDate", "yyyy/MM/dd")).isEqualTo("2026/11/04");
    assertThat(call(PLUS_ONE, "currentDate", "yyyy-MM-dd HH:mm:ss.SSS XXX"))
        .isEqualTo("2026-11-04 13:00:00.000 +01:00");
  }

  @Test
  void dateTransWritesTheDateItReadsInTheTargetFormat() {
    assertThat(call(UTC, "dateTrans", "20261104", "yyyyMMdd", "dd.MM.yyyy"))
        .isEqualTo("04.11.2026");
    assertThat(call(UTC, "dateTrans", "4.1.2026 7:05", "d.M.yyyy H:mm", "yyyy-MM-dd'T'HH:mm:ss"))
        .isEqualTo("2026-01-04T07:05:00");
  }

  @Test
  void dateTransReadsAndWritesInTheRunsOffset() {
    // no offset in the date: it is read in the run's offset, and written in it again
    assertThat(call(PLUS_ONE, "dateTrans", "2026-11-04 10:00", "yyyy-MM-dd HH:mm", "HH:mm XXX"))
        .isEqualTo("10:00 +01:00");
    assertThat(call(UTC, "dateTrans", "2026-11-04 10:00", "yyyy-MM-dd HH:mm", "HH:mm XXX"))
        .isEqualTo("10:00 Z");
    // an offset in the date: the instant is kept and written in the run's offset
    assertThat(call(UTC, "dateTrans", "2026-11-04 10:00 +0200", "yyyy-MM-dd HH:mm Z", "HH:mm Z"))
        .isEqualTo("08:00 +0000");
    assertThat(
            call(PLUS_ONE, "dateTrans", "2026-11-04 10:00 +0200", "yyyy-MM-dd HH:mm Z", "HH:mm Z"))
        .isEqualTo("09:00 +0100");
  }

  @Test
  void aDateItsPatternDoesNotReadIsForg0001AtTheBox() {
    assertDataError(
        "dateTrans",
        "'2026-11-04' is no date in the pattern yyyyMMdd",
        "2026-11-04",
        "yyyyMMdd",
        "dd.MM.yyyy");
    assertDataError(
        "dateTrans",
        "'20260230' is no date in the pattern yyyyMMdd",
        "20260230",
        "yyyyMMdd",
        "dd.MM.yyyy");
    assertDataError(
        "dateBefore",
        "'' is no date in the pattern yyyyMMdd",
        "20261104",
        "",
        "yyyyMMdd",
        "yyyyMMdd");
    assertDataError("dateAfter", "'x' is no date in the pattern yyyy", "x", "2026", "yyyy", "yyyy");
    assertDataError(
        "compareDates",
        "'2026' is no date in the pattern yyyyMMdd",
        "2026",
        "2026",
        "yyyyMMdd",
        "yyyy");
  }

  @Test
  void dateBeforeAndDateAfterCompareTheInstants() {
    assertThat(call(UTC, "dateBefore", "20261103", "04.11.2026", "yyyyMMdd", "dd.MM.yyyy"))
        .isEqualTo("true");
    assertThat(call(UTC, "dateBefore", "20261104", "04.11.2026", "yyyyMMdd", "dd.MM.yyyy"))
        .isEqualTo("false");
    assertThat(call(UTC, "dateBefore", "20261105", "04.11.2026", "yyyyMMdd", "dd.MM.yyyy"))
        .isEqualTo("false");
    assertThat(call(UTC, "dateAfter", "20261105", "04.11.2026", "yyyyMMdd", "dd.MM.yyyy"))
        .isEqualTo("true");
    assertThat(call(UTC, "dateAfter", "20261104", "04.11.2026", "yyyyMMdd", "dd.MM.yyyy"))
        .isEqualTo("false");
    assertThat(call(UTC, "dateAfter", "20261103", "04.11.2026", "yyyyMMdd", "dd.MM.yyyy"))
        .isEqualTo("false");
  }

  @Test
  void compareDatesGivesTheSignOfTheirOrder() {
    assertThat(call(UTC, "compareDates", "20261103", "20261104", "yyyyMMdd", "yyyyMMdd"))
        .isEqualTo("-1");
    assertThat(call(UTC, "compareDates", "20261104", "20261104", "yyyyMMdd", "yyyyMMdd"))
        .isEqualTo("0");
    assertThat(call(UTC, "compareDates", "20261105", "20261104", "yyyyMMdd", "yyyyMMdd"))
        .isEqualTo("1");
    // within one second the instants differ by nanoseconds: still -1 and 1
    String millis = "HH:mm:ss.SSS";
    assertThat(call(UTC, "compareDates", "10:00:00.001", "10:00:00.900", millis, millis))
        .isEqualTo("-1");
    assertThat(call(UTC, "compareDates", "10:00:00.900", "10:00:00.001", millis, millis))
        .isEqualTo("1");
  }

  @Test
  void theSameInstantInTwoOffsetsIsOneDate() {
    String zoned = "yyyy-MM-dd HH:mmXXX";
    String plain = "yyyy-MM-dd HH:mm";
    assertThat(
            call(UTC, "compareDates", "2026-11-04 10:00+02:00", "2026-11-04 08:00Z", zoned, zoned))
        .isEqualTo("0");
    assertThat(call(UTC, "dateBefore", "2026-11-04 10:00+02:00", "2026-11-04 08:00Z", zoned, zoned))
        .isEqualTo("false");
    // a date without an offset is in the run's: 09:30 at +01:00 is before 09:00 in UTC
    assertThat(call(PLUS_ONE, "dateBefore", "2026-11-04 09:30", "2026-11-04 09:00Z", plain, zoned))
        .isEqualTo("true");
    assertThat(call(UTC, "dateBefore", "2026-11-04 09:30", "2026-11-04 09:00Z", plain, zoned))
        .isEqualTo("false");
  }

  /** A run's frame at {@code now}. */
  private static Frame frame(OffsetDateTime now) {
    return new Frame(new QueueBlock[0], null, null, 0, 0, 0, now);
  }

  /**
   * Literal patterns, as lowering gives every param, are compiled once, when the call binds (#194),
   * and read and write each date of the call as patterns compiled per value do.
   */
  @Test
  void literalPatternsReadAndWriteEveryDate() {
    String[] dates = new String[2];
    Node.OfString trans =
        SingleValueLibrary.bind(
            "dateTrans",
            BOX,
            List.of(f -> dates[0], new Node.Literal("yyyyMMdd"), new Node.Literal("dd.MM.yyyy")));
    Node.OfString compare =
        SingleValueLibrary.bind(
            "compareDates",
            BOX,
            List.of(
                f -> dates[0],
                f -> dates[1],
                new Node.Literal("yyyyMMdd"),
                new Node.Literal("yyyy-MM-dd HH:mmXXX")));
    Node.OfString current =
        SingleValueLibrary.bind("currentDate", BOX, List.of(new Node.Literal("HH:mm XXX")));

    for (String[] slot :
        List.of(
            new String[] {"20261104", "2026-11-04 01:00+02:00", "04.11.2026", "1"},
            new String[] {"20260229", "2026-01-01 00:00Z", "error", ""},
            new String[] {"19991231", "1999-12-31 00:00Z", "31.12.1999", "0"})) {
      dates[0] = slot[0];
      dates[1] = slot[1];
      if (slot[2].equals("error")) {
        assertThatThrownBy(() -> trans.eval(frame(UTC)))
            .isInstanceOfSatisfying(
                MappingRunException.class,
                e ->
                    assertThat(e.getMessage())
                        .isEqualTo(
                            "the function box D1: dateTrans: '20260229' is no date in the pattern"
                                + " yyyyMMdd"));
      } else {
        assertThat(trans.eval(frame(UTC))).isEqualTo(slot[2]);
        assertThat(compare.eval(frame(UTC))).isEqualTo(slot[3]);
      }
    }
    assertThat(current.eval(frame(PLUS_ONE))).isEqualTo("13:00 +01:00");
    assertThat(current.eval(frame(UTC))).isEqualTo("12:00 Z");
  }

  /**
   * A literal pattern that compiles to no picture, which lowering refuses (ADR-0003, ADR-0026,
   * corpus {@code fn/dateTrans/009}), is refused when its call binds: the call is never built.
   */
  @Test
  void aLiteralPatternWithoutAPictureIsRefusedWhenItsCallBinds() {
    // witness: the same pattern over a node that is no literal binds, and is refused per value
    Node.OfString perValue =
        SingleValueLibrary.bind("dateTrans", BOX, List.of(f -> "1", f -> "dd.MM.yy", f -> "yyyy"));
    assertThatThrownBy(() -> perValue.eval(frame(UTC)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("the date pattern \"dd.MM.yy\" has no XPath picture: ");

    assertThatThrownBy(
            () ->
                SingleValueLibrary.bind(
                    "dateTrans",
                    BOX,
                    List.of(f -> "1", new Node.Literal("dd.MM.yy"), new Node.Literal("yyyy"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("the date pattern \"dd.MM.yy\" has no XPath picture: ");
    assertThatThrownBy(
            () ->
                SingleValueLibrary.bind(
                    "dateTrans",
                    BOX,
                    List.of(f -> "1", new Node.Literal("yyyy"), new Node.Literal("dd.MM.yy"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("the date pattern \"dd.MM.yy\" has no XPath picture: ");
  }
}
