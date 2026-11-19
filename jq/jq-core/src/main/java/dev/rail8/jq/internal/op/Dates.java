/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): tm2jv, jv2tm, set_tm_wday, set_tm_yday, f_strptime,
 * f_mktime, f_gmtime, f_localtime, f_strftime, f_strflocaltime and f_now in src/builtin.c.
 *
 * jq is copyright (C) 2012 Stephen Dolan
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package dev.rail8.jq.internal.op;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.nio.charset.StandardCharsets.UTF_8;

import dev.rail8.jq.internal.value.BuiltArray;
import dev.rail8.jq.internal.value.BuiltString;
import dev.rail8.jq.internal.value.DoubleNumber;
import dev.rail8.jq.internal.value.Members;
import dev.rail8.jq.internal.value.Numbers;
import dev.rail8.jq.query.ir.Operation;
import dev.rail8.jq.query.run.Fn0;
import dev.rail8.jq.query.run.Fn1;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.value.JqArray;
import dev.rail8.jq.value.JqNumber;
import dev.rail8.jq.value.JqString;
import dev.rail8.jq.value.JqValue;
import java.lang.foreign.MemorySegment;

/**
 * jq 1.8.2's date builtins written in C: {@code now}, {@code mktime}, {@code gmtime}, {@code localtime}, {@code
 * strftime}, {@code strflocaltime} and {@code strptime}. A broken-down time is jq's array of eight numbers: the year,
 * the month from 0, the day of the month, the hour, the minute, the second with its fraction, the day of the week
 * from Sunday as 0 and the day of the year from 0. {@code now} reads the host's clock; {@code localtime}, {@code
 * strflocaltime}, and {@code strftime} and {@code strptime} for {@code %s}, the host's zone; the others are UTC.
 *
 * <p>A number of seconds converts to a whole second as C converts a double to a 64-bit integer on x86-64: toward 0,
 * and NaN or a number out of the range to the least integer, whose year no conversion represents. Every field of an
 * array converts to an int, clamped to the int range and toward 0. {@code strftime} and {@code strptime} tick once
 * per started KiB of the longer of their input and their output, the other natives not at all. What they build is
 * charged before it is allocated.
 */
public final class Dates {
  /** {@code now}. */
  public static final Operation NOW = new Operation("now", (Fn0) (state, in) -> new DoubleNumber(Kept.of(state).time().now()));

  /** {@code mktime}. */
  public static final Operation MKTIME = new Operation("mktime", (Fn0) (state, in) -> mktime(state, (JqValue) in));

  /** {@code gmtime}. */
  public static final Operation GMTIME = new Operation("gmtime", (Fn0) (state, in) -> gmtime(state, (JqValue) in, false));

  /** {@code localtime}. */
  public static final Operation LOCALTIME =
      new Operation("localtime", (Fn0) (state, in) -> gmtime(state, (JqValue) in, true));

  /** {@code strftime(fmt)}. */
  public static final Operation STRFTIME =
      new Operation("strftime", (Fn1) (state, in, f) -> strftime(state, (JqValue) in, (JqValue) f, false));

  /** {@code strflocaltime(fmt)}. */
  public static final Operation STRFLOCALTIME =
      new Operation("strflocaltime", (Fn1) (state, in, f) -> strftime(state, (JqValue) in, (JqValue) f, true));

  /** {@code strptime(fmt)}. */
  public static final Operation STRPTIME =
      new Operation("strptime", (Fn1) (state, in, f) -> strptime(state, (JqValue) in, (JqValue) f));

  /** The fields of a broken-down time. */
  private static final int FIELDS = 8;

  /** What strptime's day of the week holds until a conversion or the day of the month sets it. */
  private static final int NO_WDAY = 8;

  /** What strptime's day of the year holds until a conversion or the day of the month sets it. */
  private static final int NO_YDAY = 367;

  /** The days before each month of a year that is not a leap year. */
  private static final int[] DAYS_BEFORE = {0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334};

  private Dates() {}

  /**
   * {@code mktime}: the seconds since the epoch of a broken-down time read as UTC, its fields carried as C's {@code
   * timegm} carries them; jq's errors for an input that is no array, a field that is no number or NaN, and a time
   * {@code timegm} cannot represent or whose result is -1 or -2.
   */
  private static DoubleNumber mktime(RunState state, JqValue a) {
    if (!(a instanceof JqArray array)) {
      throw Arithmetic.error("mktime requires array inputs");
    }
    BrokenDownTime tm = new BrokenDownTime();
    if (!fields(state, array, tm)) {
      throw Arithmetic.error("mktime requires parsed datetime inputs");
    }
    long t = tm.timegm();
    if (t == BrokenDownTime.FAILED || t == -1) {
      throw Arithmetic.error("invalid gmtime representation");
    }
    if (t == -2) {
      throw Arithmetic.error("mktime not supported on this platform");
    }
    return new DoubleNumber(t);
  }

  /** {@code gmtime}, or {@code localtime} when {@code local}: the broken-down time of a number of seconds. */
  private static BuiltArray gmtime(RunState state, JqValue a, boolean local) {
    if (!(a instanceof JqNumber n)) {
      throw Arithmetic.error(local ? "localtime() requires numeric inputs" : "gmtime() requires numeric inputs");
    }
    double fsecs = Numbers.doubleValue(state, n);
    BrokenDownTime tm = new BrokenDownTime();
    convert(state, fsecs, tm, local);
    return array(state, tm, fsecs, null);
  }

  /**
   * Sets {@code tm} to the broken-down time of {@code fsecs} seconds, in the host zone when {@code local}, else UTC;
   * jq's error when its year since 1900 is not an int.
   */
  private static void convert(RunState state, double fsecs, BrokenDownTime tm, boolean local) {
    long secs = Double.isNaN(fsecs) || fsecs >= 0x1p63 || fsecs < -0x1p63 ? Long.MIN_VALUE : (long) fsecs;
    boolean done;
    if (local) {
      done = Kept.of(state).time().localtime(secs, tm);
    } else {
      done = tm.set(secs);
    }
    if (!done) {
      throw Arithmetic.error("error converting number of seconds since epoch to datetime");
    }
  }

  /**
   * {@code strftime(fmt)}, or {@code strflocaltime(fmt)} when {@code local}: a number converts as {@code gmtime} or
   * {@code localtime} does; an array is normalised as UTC by C's {@code timegm}, or as a local time of the host zone
   * by C's {@code mktime}, and keeps its fields when that fails. The format is read up to its first NUL.
   */
  private static BuiltString strftime(RunState state, JqValue a, JqValue f, boolean local) {
    String name = local ? "strflocaltime/1" : "strftime/1";
    BrokenDownTime tm = new BrokenDownTime();
    if (a instanceof JqNumber n) {
      double fsecs = Numbers.doubleValue(state, n);
      convert(state, fsecs, tm, local);
      // jq reads back the array it made: a year that wrapped its int stays wrapped, the rest is as it was.
      tm.year = clamp((double) (tm.year + 1900) - 1900);
      tm.isdst = 0;
      tm.gmtoff = 0;
      tm.zone = null;
    } else if (!(a instanceof JqArray)) {
      throw Arithmetic.error(name + " requires parsed datetime inputs");
    }
    if (!(f instanceof JqString format)) {
      throw Arithmetic.error(name + " requires a string format");
    }
    if (a instanceof JqArray array && !fields(state, array, tm)) {
      throw Arithmetic.error(name + " requires parsed datetime inputs");
    }
    HostTime zone = Kept.of(state).time();
    if (local) {
      tm.isdst = -1;
      zone.mktime(tm);
    } else {
      tm.timegm();
    }
    MemorySegment bytes = format.utf8();
    state.tick(Built.kibibytes(bytes.byteSize()));
    byte[] text = Strftime.format(untilNul(bytes), tm, zone);
    if (text == null) {
      throw Arithmetic.error(name + ": unknown system failure");
    }
    state.tick(Math.max(0, Built.kibibytes(text.length) - Built.kibibytes(bytes.byteSize())));
    byte[] out = Built.string(state, text.length);
    System.arraycopy(text, 0, out, 0, text.length);
    return BuiltString.of(out);
  }

  /**
   * {@code strptime(fmt)}: the broken-down time that C's {@code strptime} reads from the input with the format, each
   * read up to its first NUL, with the day of the week and of the year jq computes when neither the format nor the
   * day of the month gave them; the input left after the format, when it starts with a space, as a ninth element.
   */
  private static BuiltArray strptime(RunState state, JqValue a, JqValue f) {
    if (!(a instanceof JqString input) || !(f instanceof JqString format)) {
      throw Arithmetic.error("strptime/1 requires string inputs and arguments");
    }
    MemorySegment in = input.utf8();
    MemorySegment fm = format.utf8();
    state.tick(Built.kibibytes(Math.max(in.byteSize(), fm.byteSize())));
    byte[] text = untilNul(in);
    byte[] fmt = untilNul(fm);
    BrokenDownTime tm = new BrokenDownTime();
    tm.wday = NO_WDAY;
    tm.yday = NO_YDAY;
    int end = Strptime.parse(text, fmt, tm, Kept.of(state).time());
    if (end < 0 || (end < text.length && !space(text[end]))) {
      throw Arithmetic.error(
          "date \"" + new String(text, UTF_8) + "\" does not match format \"" + new String(fmt, UTF_8) + "\"");
    }
    if (tm.wday == NO_WDAY && tm.mday != 0 && tm.mon >= 0 && tm.mon <= 11) {
      setWday(tm);
    }
    if (tm.yday == NO_YDAY && tm.mday != 0 && tm.mon >= 0 && tm.mon <= 11) {
      setYday(tm);
    }
    BuiltString rest = null;
    if (end < text.length) {
      byte[] r = Built.string(state, text.length - end);
      System.arraycopy(text, end, r, 0, r.length);
      rest = BuiltString.of(r);
    }
    return array(state, tm, 0, rest);
  }

  /** jq's {@code set_tm_wday}: the day of the week by Gauss's algorithm, wrong for January and February of 1900. */
  private static void setWday(BrokenDownTime tm) {
    int century = (1900 + tm.year) / 100;
    int year = (1900 + tm.year) % 100;
    if (tm.mon < 2) {
      year--;
    }
    // March is 1, April is 2, ..., January is 11 and February 12.
    int mon = tm.mon - 1;
    if (mon < 1) {
      mon += 12;
    }
    int wday =
        (tm.mday + (int) Math.floor(2.6 * mon - 0.2) + year + (int) Math.floor(year / 4.0) + (int) Math.floor(century / 4.0)
                - 2 * century)
            % 7;
    if (wday < 0) {
      wday += 7;
    }
    tm.wday = wday;
  }

  /** jq's {@code set_tm_yday}: the day of the year from the month and the day of the month. */
  private static void setYday(BrokenDownTime tm) {
    int year = 1900 + tm.year;
    int leapDay = tm.mon > 1 && BrokenDownTime.leap(year) ? 1 : 0;
    tm.yday = DAYS_BEFORE[tm.mon] + leapDay + tm.mday - 1;
  }

  /**
   * jq's {@code jv2tm}: sets {@code tm}'s fields from the array's first eight elements, the year less 1900, each
   * clamped to the int range and cut toward 0; a shorter array leaves the fields after it 0. False when one of them
   * is no number or NaN.
   */
  private static boolean fields(RunState state, JqArray array, BrokenDownTime tm) {
    tm.year = 0;
    tm.mon = 0;
    tm.mday = 0;
    tm.hour = 0;
    tm.min = 0;
    tm.sec = 0;
    tm.wday = 0;
    tm.yday = 0;
    tm.isdst = 0;
    tm.gmtoff = 0;
    tm.zone = null;
    Members members = (Members) array;
    int i = 0;
    for (int c = members.first(); c != -1 && i < FIELDS; c = members.next(state, c), i++) {
      if (!(members.member(state, c) instanceof JqNumber n)) {
        return false;
      }
      double d = Numbers.doubleValue(state, n);
      if (Double.isNaN(d)) {
        return false;
      }
      int v = clamp(i == 0 ? d - 1900 : d);
      switch (i) {
        case 0 -> tm.year = v;
        case 1 -> tm.mon = v;
        case 2 -> tm.mday = v;
        case 3 -> tm.hour = v;
        case 4 -> tm.min = v;
        case 5 -> tm.sec = v;
        case 6 -> tm.wday = v;
        default -> tm.yday = v;
      }
    }
    return true;
  }

  /** {@code d} clamped to the int range and cut toward 0. */
  private static int clamp(double d) {
    return d < Integer.MIN_VALUE ? Integer.MIN_VALUE : d > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) d;
  }

  /**
   * jq's {@code tm2jv}: the eight numbers of {@code tm}, the year being its int plus 1900, wrapped, and the second
   * {@code fsecs}'s fraction on top; then {@code rest} when it is not null.
   */
  private static BuiltArray array(RunState state, BrokenDownTime tm, double fsecs, BuiltString rest) {
    int n = rest == null ? FIELDS : FIELDS + 1;
    Object[] elements = Built.elements(state, n);
    elements[0] = new DoubleNumber(tm.year + 1900);
    elements[1] = new DoubleNumber(tm.mon);
    elements[2] = new DoubleNumber(tm.mday);
    elements[3] = new DoubleNumber(tm.hour);
    elements[4] = new DoubleNumber(tm.min);
    elements[5] = new DoubleNumber(tm.sec + (fsecs - Math.floor(fsecs)));
    elements[6] = new DoubleNumber(tm.wday);
    elements[7] = new DoubleNumber(tm.yday);
    if (rest != null) {
      elements[FIELDS] = rest;
    }
    return new BuiltArray(elements, n);
  }

  /** The bytes of {@code s} before its first NUL, the C string jq hands the C library. */
  private static byte[] untilNul(MemorySegment s) {
    long n = s.byteSize();
    for (long i = 0; i < n; i++) {
      if (s.get(JAVA_BYTE, i) == 0) {
        n = i;
        break;
      }
    }
    return s.asSlice(0, n).toArray(JAVA_BYTE);
  }

  /** C's {@code isspace} in the C locale. */
  static boolean space(byte b) {
    return b == ' ' || (b >= '\t' && b <= '\r');
  }
}
