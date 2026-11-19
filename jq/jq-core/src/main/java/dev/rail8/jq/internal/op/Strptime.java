package dev.rail8.jq.internal.op;

import java.nio.charset.StandardCharsets;

/**
 * C's {@code strptime} as jq 1.8.2 calls it on glibc, in the C locale: reads a broken-down time from text by a format.
 *
 * <p>A white-space character of the format matches any run of white space in the text, none included; another
 * character other than {@code %} matches itself. A conversion is {@code %}, then any of the flags {@code -_0^#} and a
 * width, both read and ignored, then an optional {@code E} or {@code O} modifier, then its letter. A number comes
 * after any white space, takes at least one digit and at most its count, stops before a digit once ten times its value
 * is past its range, and fails out of its range; a name matches the English full or abbreviated name in any case,
 * without white space before it.
 *
 * <ul>
 *   <li>{@code %a %A} a day's name; {@code %b %B %h} a month's; {@code %p} AM or PM.
 *   <li>{@code %d %e} the day of the month, 1 to 31; {@code %m} the month, 1 to 12; {@code %j} the day of the year, 1
 *       to 366; {@code %Y} the year, 0 to 9999, four digits; {@code %y} the year of the century, 0 to 99, 1969 to
 *       2068, and {@code %Ey} a number of up to four digits, ignored, then that; {@code %C} the century, 0 to 99.
 *   <li>{@code %H %k} the hour, 0 to 23; {@code %I %l} the hour on the 12-hour clock, 1 to 12; {@code %M} the
 *       minute, 0 to 59; {@code %S} the second, 0 to 61.
 *   <li>{@code %u} the day of the week, 1 to 7 from Monday; {@code %w} 0 to 6 from Sunday; {@code %U} the week of the
 *       year from Sunday and {@code %W} from Monday, 0 to 53.
 *   <li>{@code %V} and {@code %g}, 0 to 53 and 0 to 99, and {@code %G}, any digits, are read and ignored.
 *   <li>{@code %s} the seconds since the epoch, digits only, converted in the run's zone.
 *   <li>{@code %z} {@code Z}, or a sign and {@code hh}, {@code hhmm} or {@code hh:mm}, read and ignored; {@code %Z}
 *       any word, read and ignored.
 *   <li>{@code %n %t} any white space; {@code %%} a {@code %}.
 *   <li>{@code %c %D %F %r %R %T %x %X} the formats {@code "%a %b %e %H:%M:%S %Y"}, {@code "%m/%d/%y"},
 *       {@code "%Y-%m-%d"}, {@code "%I:%M:%S %p"}, {@code "%H:%M"}, {@code "%H:%M:%S"}, {@code "%m/%d/%y"} and
 *       {@code "%H:%M:%S"}.
 * </ul>
 *
 * <p>{@code E} takes only {@code c C x X y Y}, and {@code O} only {@code b B d e h H I m M S U V w W y}; any other
 * conversion, and a format ending inside one, fails.
 *
 * <p>Once the text is read, the hour on the 12-hour clock moves on by 12 after PM; the century sets the year: with
 * {@code %y}'s year of the century, else alone. A year, a month or a day of the month read and no day of the week
 * then sets the day of the week, and the month and day from the day of the year when it was read and they were not;
 * then the day of the year, when it was not read. A week of the year with a day of the week sets the day of the year,
 * then the month and day that were not read; the week is the last one read, counted from Sunday once a {@code %U} was
 * read. These count leap days by C's truncating {@code int} division, so January
 * and February of the year 0 fall one day of the week late, and wrap in {@code int} as glibc's do.
 */
final class Strptime {
  /** The flags of a conversion, which the format may carry and this reads past. */
  private static final String FLAGS = "-_0^#";

  /** The conversions that take an {@code E} modifier. */
  private static final String WITH_E = "cCxXyY";

  /** The conversions that take an {@code O} modifier. */
  private static final String WITH_O = "bBdehHImMSUVwWy";

  private static final String[] DAYS = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};

  private static final String[] MONTHS = {
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December"
  };

  private static final byte[] DATE_TIME = ascii("%a %b %e %H:%M:%S %Y");
  private static final byte[] DATE = ascii("%m/%d/%y");
  private static final byte[] ISO_DATE = ascii("%Y-%m-%d");
  private static final byte[] TIME = ascii("%H:%M:%S");
  private static final byte[] TIME_12 = ascii("%I:%M:%S %p");
  private static final byte[] HOUR_MINUTE = ascii("%H:%M");

  /**
   * The days of a common year and of a leap year before each month, from January to the end of December, each
   * preceded by what glibc reads before January: 255 in a common year, 365 in a leap year.
   */
  private static final int[][] BEFORE = {
    {255, 0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334, 365},
    {365, 0, 31, 60, 91, 121, 152, 182, 213, 244, 274, 305, 335, 366}
  };

  private final byte[] in;
  private final BrokenDownTime tm;
  private final HostTime zone;
  private int at;
  private boolean twelveHour;
  private boolean pm;
  private int century = -1;
  private boolean withCentury;
  private boolean wantDays;
  private boolean haveWday;
  private boolean haveYday;
  private boolean haveMon;
  private boolean haveMday;
  private boolean sundayWeek;
  private boolean mondayWeek;
  private int week;

  private Strptime(byte[] in, BrokenDownTime tm, HostTime zone) {
    this.in = in;
    this.tm = tm;
    this.zone = zone;
  }

  /**
   * Reads {@code input} by {@code fmt} into {@code tm}, whose fields not read stay as they are: the count of the
   * input's bytes read, or -1 when it does not match. {@code %s} converts in {@code zone}.
   */
  static int parse(byte[] input, byte[] fmt, BrokenDownTime tm, HostTime zone) {
    Strptime p = new Strptime(input, tm, zone);
    if (!p.read(fmt)) {
      return -1;
    }
    p.finish();
    return p.at;
  }

  private boolean read(byte[] fmt) {
    int f = 0;
    while (f < fmt.length) {
      byte c = fmt[f++];
      if (Dates.space(c)) {
        skipSpace();
        continue;
      }
      if (c != '%') {
        if (at == in.length || in[at] != c) {
          return false;
        }
        at++;
        continue;
      }
      while (f < fmt.length && FLAGS.indexOf(fmt[f]) >= 0) {
        f++;
      }
      while (f < fmt.length && digit(fmt[f])) {
        f++;
      }
      byte modifier = 0;
      if (f < fmt.length && (fmt[f] == 'E' || fmt[f] == 'O')) {
        modifier = fmt[f++];
      }
      if (f == fmt.length) {
        return false;
      }
      char conversion = (char) (fmt[f++] & 0xff);
      if (modifier == 'E' && WITH_E.indexOf(conversion) < 0 || modifier == 'O' && WITH_O.indexOf(conversion) < 0) {
        return false;
      }
      if (!convert(conversion, modifier)) {
        return false;
      }
    }
    return true;
  }

  private boolean convert(char conversion, byte modifier) {
    int v;
    switch (conversion) {
      case '%' -> {
        if (at == in.length || in[at] != '%') {
          return false;
        }
        at++;
      }
      case 'n', 't' -> skipSpace();
      case 'a', 'A' -> {
        if ((v = name(DAYS)) < 0) {
          return false;
        }
        tm.wday = v;
        haveWday = true;
      }
      case 'b', 'B', 'h' -> {
        if ((v = name(MONTHS)) < 0) {
          return false;
        }
        tm.mon = v;
        haveMon = true;
        wantDays = true;
      }
      case 'c' -> {
        return read(DATE_TIME);
      }
      case 'D', 'x' -> {
        return read(DATE);
      }
      case 'F' -> {
        return read(ISO_DATE);
      }
      case 'T', 'X' -> {
        return read(TIME);
      }
      case 'r' -> {
        return read(TIME_12);
      }
      case 'R' -> {
        return read(HOUR_MINUTE);
      }
      case 'C' -> {
        if ((v = number(0, 99, 2)) < 0) {
          return false;
        }
        century = v;
        wantDays = true;
      }
      case 'd', 'e' -> {
        if ((v = number(1, 31, 2)) < 0) {
          return false;
        }
        tm.mday = v;
        haveMday = true;
        wantDays = true;
      }
      case 'H', 'k' -> {
        if ((v = number(0, 23, 2)) < 0) {
          return false;
        }
        tm.hour = v;
        twelveHour = false;
      }
      case 'I', 'l' -> {
        if ((v = number(1, 12, 2)) < 0) {
          return false;
        }
        tm.hour = v % 12;
        twelveHour = true;
      }
      case 'j' -> {
        if ((v = number(1, 366, 3)) < 0) {
          return false;
        }
        tm.yday = v - 1;
        haveYday = true;
      }
      case 'm' -> {
        if ((v = number(1, 12, 2)) < 0) {
          return false;
        }
        tm.mon = v - 1;
        haveMon = true;
        wantDays = true;
      }
      case 'M' -> {
        if ((v = number(0, 59, 2)) < 0) {
          return false;
        }
        tm.min = v;
      }
      case 'S' -> {
        if ((v = number(0, 61, 2)) < 0) {
          return false;
        }
        tm.sec = v;
      }
      case 'p' -> {
        if (word("AM")) {
          pm = false;
        } else if (word("PM")) {
          pm = true;
        } else {
          return false;
        }
      }
      case 's' -> {
        return seconds();
      }
      case 'u' -> {
        if ((v = number(1, 7, 1)) < 0) {
          return false;
        }
        tm.wday = v % 7;
        haveWday = true;
      }
      case 'w' -> {
        if ((v = number(0, 6, 1)) < 0) {
          return false;
        }
        tm.wday = v;
        haveWday = true;
      }
      case 'U', 'W' -> {
        if ((v = number(0, 53, 2)) < 0) {
          return false;
        }
        week = v;
        sundayWeek |= conversion == 'U';
        mondayWeek |= conversion == 'W';
      }
      case 'V' -> {
        return number(0, 53, 2) >= 0;
      }
      case 'g' -> {
        return number(0, 99, 2) >= 0;
      }
      case 'G' -> {
        if (at == in.length || !digit(in[at])) {
          return false;
        }
        while (at < in.length && digit(in[at])) {
          at++;
        }
      }
      case 'y' -> {
        // With E, a number of up to four digits comes first, read and ignored.
        if (modifier == 'E' && number(0, 9_999, 4) < 0 || (v = number(0, 99, 2)) < 0) {
          return false;
        }
        tm.year = v >= 69 ? v : v + 100;
        withCentury = true;
        wantDays = true;
      }
      case 'Y' -> {
        if ((v = number(0, 9_999, 4)) < 0) {
          return false;
        }
        tm.year = v - 1900;
        withCentury = false;
        wantDays = true;
      }
      case 'Z' -> {
        skipSpace();
        while (at < in.length && !Dates.space(in[at])) {
          at++;
        }
      }
      case 'z' -> {
        return offset();
      }
      default -> {
        return false;
      }
    }
    return true;
  }

  /** Sets what the fields read imply, once the whole text is read. */
  private void finish() {
    if (twelveHour && pm) {
      tm.hour += 12;
    }
    if (century != -1) {
      tm.year = withCentury ? tm.year % 100 + (century - 19) * 100 : (century - 19) * 100;
    }
    if (wantDays && !haveWday) {
      if (!(haveMon && haveMday) && haveYday) {
        monthAndDay();
        haveMon = true;
        haveMday = true;
      }
      tm.wday = weekday(tm.year, tm.mon, tm.mday);
    }
    if (wantDays && !haveYday) {
      tm.yday = before(tm.year, tm.mon + 1) + tm.mday - 1;
    }
    if ((sundayWeek || mondayWeek) && haveWday) {
      int first = sundayWeek ? 0 : 1;
      int january = weekday(tm.year, 0, 1);
      if (!haveYday) {
        tm.yday = (7 - (january - first)) % 7 + (week - 1) * 7 + (tm.wday - first + 7) % 7;
      }
      if (!haveMday || !haveMon) {
        monthAndDay();
      }
    }
  }

  /** Sets the month and the day of the month not read from the day of the year, the month at most December. */
  private void monthAndDay() {
    int m = 0;
    while (m < 12 && before(tm.year, m + 1) <= tm.yday) {
      m++;
    }
    if (!haveMon) {
      tm.mon = m - 1;
    }
    if (!haveMday) {
      tm.mday = tm.yday - before(tm.year, m) + 1;
    }
  }

  /** The days of the year since 1900 {@code year} before month {@code m}, 1 for January; 0 is before January. */
  private static int before(int year, int m) {
    return BEFORE[BrokenDownTime.leap(1900 + year) ? 1 : 0][m];
  }

  /** The day of the week of the date, in glibc's {@code int} arithmetic. */
  private static int weekday(int year, int mon, int mday) {
    int corrected = 1900 + year - (mon < 2 ? 1 : 0);
    int quads = corrected / 4;
    int days = -473 + 365 * (year - 70) + quads - quads / 25 + (quads % 25 < 0 ? 1 : 0) + quads / 25 / 4
        + BEFORE[0][mon + 1] + mday - 1;
    return (days % 7 + 7) % 7;
  }

  /** {@code %s}: digits, converted in the zone; false when they overflow or the zone cannot convert them. */
  private boolean seconds() {
    if (at == in.length || !digit(in[at])) {
      return false;
    }
    long t = 0;
    while (at < in.length && digit(in[at])) {
      int d = in[at++] - '0';
      if (t > (Long.MAX_VALUE - d) / 10) {
        return false;
      }
      t = t * 10 + d;
    }
    return zone.localtime(t, tm);
  }

  /** {@code %z}: {@code Z}, or a sign and {@code hh}, {@code hhmm} or {@code hh:mm} with minutes below 60. */
  private boolean offset() {
    skipSpace();
    if (at < in.length && in[at] == 'Z') {
      at++;
      return true;
    }
    if (at == in.length || in[at] != '+' && in[at] != '-') {
      return false;
    }
    at++;
    int n = 0;
    int v = 0;
    while (n < 4 && at < in.length) {
      if (digit(in[at])) {
        v = v * 10 + in[at++] - '0';
        n++;
      } else if (n == 2 && in[at] == ':' && at + 1 < in.length && digit(in[at + 1])) {
        at++;
      } else {
        break;
      }
    }
    if (n == 2) {
      v *= 100;
    } else if (n != 4) {
      return false;
    }
    return v % 100 < 60;
  }

  /**
   * A number after any white space, from {@code from} to {@code to}; else -1. It takes at most {@code digits} digits,
   * and no further digit once ten times its value so far is past {@code to}.
   */
  private int number(int from, int to, int digits) {
    skipSpace();
    if (at == in.length || !digit(in[at])) {
      return -1;
    }
    int v = in[at++] - '0';
    for (int n = 1; n < digits && at < in.length && digit(in[at]) && v * 10 <= to; n++) {
      v = v * 10 + in[at++] - '0';
    }
    return v < from || v > to ? -1 : v;
  }

  /** The index of the name, full or its first three letters, that the text starts with in any case; else -1. */
  private int name(String[] names) {
    for (int i = 0; i < names.length; i++) {
      if (word(names[i]) || word(names[i].substring(0, 3))) {
        return i;
      }
    }
    return -1;
  }

  /** Whether the text starts with {@code w} in any case; reads it when it does. */
  private boolean word(String w) {
    if (in.length - at < w.length()) {
      return false;
    }
    for (int i = 0; i < w.length(); i++) {
      int c = in[at + i];
      if (c >= 'a' && c <= 'z') {
        c -= 'a' - 'A';
      }
      if (c != Character.toUpperCase(w.charAt(i))) {
        return false;
      }
    }
    at += w.length();
    return true;
  }

  private void skipSpace() {
    while (at < in.length && Dates.space(in[at])) {
      at++;
    }
  }

  private static boolean digit(int b) {
    return b >= '0' && b <= '9';
  }

  private static byte[] ascii(String s) {
    return s.getBytes(StandardCharsets.US_ASCII);
  }
}
