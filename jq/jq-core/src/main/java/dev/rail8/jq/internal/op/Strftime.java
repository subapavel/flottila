package dev.rail8.jq.internal.op;

import java.nio.charset.StandardCharsets;

/**
 * C's {@code strftime} as jq 1.8.2's reference build runs it on glibc, written from its observed behaviour, with the
 * C locale's English names. A conversion is {@code %}, flags ({@code _} pads with spaces, {@code -} does not pad to the
 * conversion's digits, {@code 0} pads with zeros, the last of these three counting; {@code ^} upper-cases, {@code #}
 * swaps the case of names), a decimal width, an {@code E} or {@code O} modifier and the conversion's letter. A width
 * pads the conversion's text on the left to that many bytes. A conversion it does not know, a modifier the conversion
 * does not take and a conversion the format ends in are copied as they stand, padded to their width. {@code %s} is the
 * time read as a local time of the run's zone, -1 when it does not convert.
 *
 * <p>The text of a format of {@code n} bytes holds at most {@code n + 99} bytes; a longer one, or an empty one of a
 * format that is not empty, fails, as glibc's does in jq's buffer.
 */
final class Strftime {
  private static final String[] DAYS = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};

  private static final String[] MONTHS = {
    "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November",
    "December"
  };

  /** The conversions that do not take the modifier E. */
  private static final String NO_E = "ABDFGHIMSUVWabdeghjklmw";

  /** The conversions that do not take the modifier O. */
  private static final String NO_O = "ADFXYacx";

  private final BrokenDownTime tm;
  private final HostTime zone;

  /** The text so far. */
  private byte[] out = new byte[64];

  private int length;

  /** The longest text the format may give. */
  private final long limit;

  /** Whether the text has gone past {@link #limit}. */
  private boolean over;

  private Strftime(BrokenDownTime tm, HostTime zone, long limit) {
    this.tm = tm;
    this.zone = zone;
    this.limit = limit;
  }

  /**
   * The text of the format {@code fmt} for {@code tm}, or null where glibc's {@code strftime} fails in jq's buffer: a
   * text longer than the format plus 99 bytes, or an empty text of a format that is not empty.
   */
  static byte[] format(byte[] fmt, BrokenDownTime tm, HostTime zone) {
    Strftime s = new Strftime(tm, zone, fmt.length + 99L);
    if (!s.run(fmt, false) || s.over || (s.length == 0 && fmt.length > 0)) {
      return null;
    }
    byte[] text = new byte[s.length];
    System.arraycopy(s.out, 0, text, 0, s.length);
    return text;
  }

  /** Appends the text of {@code fmt}, upper-cased when {@code upper}; false when a {@code %s} fails. */
  private boolean run(byte[] fmt, boolean upper) {
    int i = 0;
    while (i < fmt.length && !over) {
      byte b = fmt[i];
      if (b != '%') {
        add(b);
        i++;
        continue;
      }
      int start = i++;
      char pad = 0;
      boolean up = upper;
      boolean swap = false;
      for (; i < fmt.length; i++) {
        byte f = fmt[i];
        if (f == '_' || f == '-' || f == '0') {
          pad = (char) f;
        } else if (f == '^') {
          up = true;
        } else if (f == '#') {
          swap = true;
        } else {
          break;
        }
      }
      int width = 0;
      for (; i < fmt.length && fmt[i] >= '0' && fmt[i] <= '9'; i++) {
        width = width > (Integer.MAX_VALUE - 9) / 10 ? Integer.MAX_VALUE : width * 10 + fmt[i] - '0';
      }
      char modifier = 0;
      if (i < fmt.length && (fmt[i] == 'E' || fmt[i] == 'O')) {
        modifier = (char) fmt[i++];
      }
      if (i == fmt.length) {
        copy(fmt, start, i, pad, width, up);
        break;
      }
      char c = (char) (fmt[i++] & 0xFF);
      if ((modifier == 'E' && NO_E.indexOf(c) >= 0) || (modifier == 'O' && NO_O.indexOf(c) >= 0)) {
        copy(fmt, start, i, pad, width, up);
      } else if (!conversion(c, pad, width, up, swap)) {
        if (c == 's') {
          return false;
        }
        copy(fmt, start, i, pad, width, up);
      }
    }
    return true;
  }

  /**
   * Appends conversion {@code c}; false when {@code c} is no conversion, or for {@code s} when its local time does
   * not convert.
   */
  private boolean conversion(char c, char pad, int width, boolean up, boolean swap) {
    int y = tm.year + 1900;
    switch (c) {
      case 'a' -> name(name(DAYS, tm.wday, 3), pad, width, up || swap);
      case 'A' -> name(name(DAYS, tm.wday, Integer.MAX_VALUE), pad, width, up || swap);
      case 'b', 'h' -> name(name(MONTHS, tm.mon, 3), pad, width, up || swap);
      case 'B' -> name(name(MONTHS, tm.mon, Integer.MAX_VALUE), pad, width, up || swap);
      case 'p' -> name(swap ? (tm.hour > 11 ? "pm" : "am") : tm.hour > 11 ? "PM" : "AM", pad, width, false);
      case 'P' -> name(tm.hour > 11 ? "pm" : "am", pad, width, false);
      case 'Z' -> {
        // A time no conversion named: none when its daylight saving state is unknown, else the zone's standard name.
        String z = tm.zone != null ? tm.zone : tm.isdst < 0 ? "" : zone.standardName();
        name(swap ? lower(z) : z, pad, width, up && !swap);
      }
      case 'c' -> composite("%a %b %e %H:%M:%S %Y", pad, width, up);
      case 'D', 'x' -> composite("%m/%d/%y", pad, width, up);
      case 'F' -> composite("%Y-%m-%d", pad, width, up);
      case 'r' -> composite("%I:%M:%S %p", pad, width, up);
      case 'R' -> composite("%H:%M", pad, width, up);
      case 'T', 'X' -> composite("%H:%M:%S", pad, width, up);
      case 'n' -> name("\n", pad, width, false);
      case 't' -> name("\t", pad, width, false);
      case '%' -> name("%", pad, width, false);
      case 'C' -> number(y / 100 - (y % 100 < 0 ? 1 : 0), 1, '0', pad, width);
      case 'd' -> number(tm.mday, 2, '0', pad, width);
      case 'e' -> number(tm.mday, 2, '_', pad, width);
      case 'G' -> number(isoYear(y), 1, '0', pad, width);
      case 'g' -> number((isoYear(y) % 100 + 100) % 100, 2, '0', pad, width);
      case 'V' -> number(isoWeek(y), 2, '0', pad, width);
      case 'H' -> number(tm.hour, 2, '0', pad, width);
      case 'k' -> number(tm.hour, 2, '_', pad, width);
      case 'I' -> number(hour12(), 2, '0', pad, width);
      case 'l' -> number(hour12(), 2, '_', pad, width);
      case 'j' -> number(tm.yday + 1L, 3, '0', pad, width);
      case 'm' -> number(tm.mon + 1L, 2, '0', pad, width);
      case 'M' -> number(tm.min, 2, '0', pad, width);
      case 'S' -> number(tm.sec, 2, '0', pad, width);
      case 'u' -> number((tm.wday - 1 + 7) % 7 + 1, 1, '0', pad, width);
      case 'w' -> number(tm.wday, 1, '0', pad, width);
      case 'U' -> number((tm.yday - tm.wday + 7) / 7, 2, '0', pad, width);
      case 'W' -> number((tm.yday - (tm.wday - 1 + 7) % 7 + 7) / 7, 2, '0', pad, width);
      case 'Y' -> number(y, 1, '0', pad, width);
      case 'y' -> number((tm.year % 100 + 100) % 100, 2, '0', pad, width);
      case 'z' -> offset(pad, width);
      case 's' -> {
        long t = zone.mktime(tm.copy());
        // -1 when the local time does not convert; padded as a name is: the zeros of the flag 0 go before the sign.
        name(Long.toString(t == BrokenDownTime.FAILED ? -1 : t), pad, width, false);
      }
      default -> {
        return false;
      }
    }
    return true;
  }

  /** The first {@code n} letters of {@code names[i]}, or {@code ?} for an index out of range. */
  private static String name(String[] names, int i, int n) {
    if (i < 0 || i >= names.length) {
      return "?";
    }
    String name = names[i];
    return name.length() > n ? name.substring(0, n) : name;
  }

  /** The hour on a 12-hour clock, 12 in place of 0. */
  private int hour12() {
    int h = tm.hour % 12;
    return h == 0 ? 12 : h;
  }

  /** The ISO 8601 week-based year of the time, its calendar year being {@code y}. */
  private int isoYear(int y) {
    int days = isoDays(tm.yday, tm.wday);
    if (days < 0) {
      return y - 1;
    }
    return isoDays(tm.yday - yearDays(y), tm.wday) >= 0 ? y + 1 : y;
  }

  /** The ISO 8601 week of the time, from 1, its calendar year being {@code y}. */
  private int isoWeek(int y) {
    int days = isoDays(tm.yday, tm.wday);
    if (days < 0) {
      days = isoDays(tm.yday + yearDays(y - 1), tm.wday);
    } else {
      int next = isoDays(tm.yday - yearDays(y), tm.wday);
      if (next >= 0) {
        days = next;
      }
    }
    return days / 7 + 1;
  }

  /**
   * The days from the Monday that starts week 1 of a year, the week that holds its January 4, to the day {@code
   * yday} of that year that falls on {@code wday}.
   */
  private static int isoDays(int yday, int wday) {
    return yday - (yday - wday + 382) % 7 + 3;
  }

  private static int yearDays(int y) {
    return BrokenDownTime.leap(y) ? 366 : 365;
  }

  /**
   * {@code %z}: the sign padded to the width, then the hours and minutes east of UTC, at least four digits; nothing
   * when the daylight saving state is unknown.
   */
  private void offset(char pad, int width) {
    if (tm.isdst < 0) {
      return;
    }
    long off = tm.gmtoff;
    long a = Math.abs(off);
    padLeft(width - 1, pad == '0' ? (byte) '0' : (byte) ' ');
    add((byte) (off < 0 ? '-' : '+'));
    number(a / 3_600 * 100 + a / 60 % 60, 4, '0', pad, width);
  }

  /**
   * Appends {@code v} in decimal: padded to {@code digits} with {@code fill}'s, or with the pad flag's, zeros or
   * spaces, the sign counted, and to {@code width} with the same; the flag {@code -} pads to the width only, with
   * spaces.
   */
  private void number(long v, int digits, char fill, char pad, int width) {
    String d = Long.toString(v);
    char p = pad == 0 ? fill : pad;
    if (p == '0') {
      int zeros = Math.max(digits, width) - d.length();
      if (v < 0) {
        add((byte) '-');
        d = d.substring(1);
      }
      padLeft(zeros, (byte) '0');
      add(d);
    } else {
      padLeft((p == '-' ? width : Math.max(digits, width)) - d.length(), (byte) ' ');
      add(d);
    }
  }

  /** Appends {@code s}, upper-cased when {@code up}, padded to {@code width} with zeros for the flag 0, else spaces. */
  private void name(String s, char pad, int width, boolean up) {
    byte[] bytes = (up ? upper(s) : s).getBytes(StandardCharsets.UTF_8);
    padLeft(width - bytes.length, pad == '0' ? (byte) '0' : (byte) ' ');
    add(bytes, 0, bytes.length);
  }

  /** Appends the text of {@code fmt}, upper-cased when {@code up}, padded as {@link #name}. */
  private void composite(String fmt, char pad, int width, boolean up) {
    Strftime inner = new Strftime(tm, zone, limit);
    inner.run(fmt.getBytes(StandardCharsets.US_ASCII), up);
    if (inner.over) {
      over = true;
      return;
    }
    padLeft(width - inner.length, pad == '0' ? (byte) '0' : (byte) ' ');
    add(inner.out, 0, inner.length);
  }

  /** Appends {@code fmt[from, to)} as it stands, upper-cased when {@code up}, padded as {@link #name}. */
  private void copy(byte[] fmt, int from, int to, char pad, int width, boolean up) {
    padLeft(width - (to - from), pad == '0' ? (byte) '0' : (byte) ' ');
    for (int i = from; i < to; i++) {
      byte b = fmt[i];
      add(up && b >= 'a' && b <= 'z' ? (byte) (b - 32) : b);
    }
  }

  private void padLeft(long n, byte fill) {
    if (n <= 0) {
      return;
    }
    if (length + n > limit) {
      over = true;
      return;
    }
    for (long i = 0; i < n; i++) {
      add(fill);
    }
  }

  private void add(String s) {
    byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
    add(bytes, 0, bytes.length);
  }

  private void add(byte[] bytes, int from, int n) {
    for (int i = from; i < from + n; i++) {
      add(bytes[i]);
    }
  }

  private void add(byte b) {
    if (length >= limit) {
      over = true;
      return;
    }
    if (length == out.length) {
      byte[] grown = new byte[(int) Math.min(limit, 2L * out.length)];
      System.arraycopy(out, 0, grown, 0, length);
      out = grown;
    }
    out[length++] = b;
  }

  private static String upper(String s) {
    StringBuilder b = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      b.append(c >= 'a' && c <= 'z' ? (char) (c - 32) : c);
    }
    return b.toString();
  }

  private static String lower(String s) {
    StringBuilder b = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      b.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
    }
    return b.toString();
  }
}
