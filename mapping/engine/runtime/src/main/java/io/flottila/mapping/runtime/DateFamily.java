package io.flottila.mapping.runtime;

import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.xdm.DatePattern;
import io.flottila.mapping.xdm.FormatDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * The function family {@code date} (ADR-0025 "Families", F-15) of #80, over the formatting core
 * (ADR-0003). All are single-value functions whose date patterns are SimpleDateFormat patterns, box
 * params that lowering has checked (ADR-0026) and gives as {@code Literal}s, each compiled to an
 * XPath picture and analysed once, when its call binds (#194); a pattern node that is no literal is
 * compiled per value, and a literal that compiles to no picture, which lowering refuses, is refused
 * when the call binds. A date is read strictly in its pattern ({@link FormatDate#parse}), in the
 * run's offset when the pattern has none; a date the pattern does not read is {@code err:FORG0001}
 * at the function box. A date is written in the run's offset, as a SimpleDateFormat writes in its
 * zone, and dates are compared as instants. {@code currentDate} writes the run's time, fixed once
 * per mapping run. The binders are methods, not lambdas of the maps, so that PIT mutates them.
 */
final class DateFamily {

  /** The family's queue functions: it has none. */
  static final Map<String, QueueLibrary.Binder> QUEUE = Map.of();

  /** The family's single-value functions. */
  static final Map<String, SingleValueLibrary.Binder> VALUE =
      Map.ofEntries(
          Map.entry("compareDates", DateFamily::compareDates),
          Map.entry("currentDate", DateFamily::currentDate),
          Map.entry("dateAfter", DateFamily::dateAfter),
          Map.entry("dateBefore", DateFamily::dateBefore),
          Map.entry("dateTrans", DateFamily::dateTrans));

  private DateFamily() {}

  /**
   * {@code currentDate(; targetFormat)}: the run's time, {@link Frame#now}, written in {@code
   * targetFormat} in its own offset, the run's. It has no input, so it is a generating function
   * (ADR-0006 since #83, F-21), and its value is the same wherever the run reads it.
   */
  static Node.OfString currentDate(AuthoringId box, List<Node.OfString> args) {
    Node.OfString target = args.getFirst();
    Function<String, FormatDate.Picture> written = picture(target);
    return frame -> written.apply(target.eval(frame)).format(frame.now);
  }

  /**
   * {@code dateTrans(date, sourceFormat, targetFormat)}: the date read in {@code sourceFormat},
   * written in {@code targetFormat} in the run's offset.
   */
  static Node.OfString dateTrans(AuthoringId box, List<Node.OfString> args) {
    Node.OfString date = args.get(0);
    Node.OfString source = args.get(1);
    Node.OfString target = args.get(2);
    Function<String, FormatDate.Picture> read = picture(source);
    Function<String, FormatDate.Picture> written = picture(target);
    return frame -> {
      ZoneOffset zone = frame.now.getOffset();
      OffsetDateTime value =
          date("dateTrans", box, date.eval(frame), source.eval(frame), read, zone);
      return written.apply(target.eval(frame)).format(value.withOffsetSameInstant(zone));
    };
  }

  /** {@code dateBefore(date1, date2, format1, format2)}: whether date1 is before date2. */
  static Node.OfString dateBefore(AuthoringId box, List<Node.OfString> args) {
    return comparison("dateBefore", box, args, order -> Boolean.toString(order < 0));
  }

  /** {@code dateAfter(date1, date2, format1, format2)}: whether date1 is after date2. */
  static Node.OfString dateAfter(AuthoringId box, List<Node.OfString> args) {
    return comparison("dateAfter", box, args, order -> Boolean.toString(order > 0));
  }

  /**
   * {@code compareDates(date1, date2, format1, format2)}: -1, 0 or 1 as date1 is before, at or
   * after.
   */
  static Node.OfString compareDates(AuthoringId box, List<Node.OfString> args) {
    return comparison("compareDates", box, args, order -> Integer.toString(Integer.signum(order)));
  }

  /**
   * The node of {@code function} over {@code date1, date2, format1, format2}: {@code result} of the
   * order of the two instants, each date read in its own format.
   */
  private static Node.OfString comparison(
      String function, AuthoringId box, List<Node.OfString> args, IntFunction<String> result) {
    Node.OfString first = args.get(0);
    Node.OfString second = args.get(1);
    Node.OfString firstFormat = args.get(2);
    Node.OfString secondFormat = args.get(3);
    Function<String, FormatDate.Picture> firstPicture = picture(firstFormat);
    Function<String, FormatDate.Picture> secondPicture = picture(secondFormat);
    return frame -> {
      ZoneOffset zone = frame.now.getOffset();
      OffsetDateTime a =
          date(function, box, first.eval(frame), firstFormat.eval(frame), firstPicture, zone);
      OffsetDateTime b =
          date(function, box, second.eval(frame), secondFormat.eval(frame), secondPicture, zone);
      return result.apply(a.toInstant().compareTo(b.toInstant()));
    };
  }

  /**
   * The analysed picture of a date pattern param's value: for a {@code Literal}, the one picture of
   * its pattern, compiled once, here; for any other node, the picture of each value it gives.
   *
   * @throws IllegalArgumentException when {@code pattern} is a literal that compiles to no picture,
   *     which lowering refuses: a compiler bug
   */
  private static Function<String, FormatDate.Picture> picture(Node.OfString pattern) {
    if (pattern instanceof Node.Literal(String literal)) {
      FormatDate.Picture picture = FormatDate.picture(DatePattern.picture(literal));
      return value -> picture;
    }
    return value -> FormatDate.picture(DatePattern.picture(value));
  }

  /**
   * The date {@code text} writes in {@code pattern}, whose analysed picture {@code picture} gives,
   * in {@code zone} when the pattern has no offset, or {@code err:FORG0001} at {@code box} when it
   * writes none.
   */
  private static OffsetDateTime date(
      String function,
      AuthoringId box,
      String text,
      String pattern,
      Function<String, FormatDate.Picture> picture,
      ZoneOffset zone) {
    return picture
        .apply(pattern)
        .parse(text, zone)
        .orElseThrow(
            () ->
                MappingRunException.dataError(
                    RuntimeErrors.FORG0001,
                    box,
                    function + ": '" + text + "' is no date in the pattern " + pattern,
                    null));
  }
}
