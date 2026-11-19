package dev.rail8.jq.internal.op;

/**
 * A broken-down time with the fields of C's {@code struct tm} that jq's date builtins read and write, and the
 * proleptic Gregorian calendar they use, in {@code long} arithmetic at any year whose count since 1900 is an
 * {@code int}. {@code year} counts years since 1900, {@code mon} months from January as 0, {@code wday} days from
 * Sunday as 0, {@code yday} days from January 1 as 0; {@code isdst} is positive in daylight saving time, 0 outside it
 * and negative when unknown; {@code gmtoff} is the zone's offset east of UTC in seconds, and {@code zone} its name, or
 * null when it has none.
 */
final class BrokenDownTime {
  /** What a conversion that cannot represent its result returns in place of a time. */
  static final long FAILED = Long.MIN_VALUE;

  /** The days from 0000-03-01 to 1970-01-01. */
  private static final long EPOCH_DAYS = 719_468;

  int year;
  int mon;
  int mday;
  int hour;
  int min;
  int sec;
  int wday;
  int yday;
  int isdst;
  long gmtoff;
  String zone;

  BrokenDownTime copy() {
    BrokenDownTime c = new BrokenDownTime();
    c.year = year;
    c.mon = mon;
    c.mday = mday;
    c.hour = hour;
    c.min = min;
    c.sec = sec;
    c.wday = wday;
    c.yday = yday;
    c.isdst = isdst;
    c.gmtoff = gmtoff;
    c.zone = zone;
    return c;
  }

  /**
   * The seconds since the epoch of the fields read as UTC, each field out of its range carried into the next larger
   * one: month 12 is January of the next year, day 0 the last day of the month before, second 60 the next minute.
   * {@code wday} and {@code yday} are not read. Every int of every field fits: the result stays within ±2^57.
   */
  long seconds() {
    long y = year + 1900L + Math.floorDiv(mon, 12);
    int m = Math.floorMod(mon, 12);
    long days = daysFromCivil(y, m + 1) + mday - 1L;
    return days * 86_400 + hour * 3_600L + min * 60L + sec;
  }

  /**
   * Sets the calendar fields, {@code wday} and {@code yday} to those of {@code local} seconds since the epoch; leaves
   * {@code isdst}, {@code gmtoff} and {@code zone}. False, and nothing changed, when the year since 1900 is not an
   * int.
   */
  boolean set(long local) {
    long days = Math.floorDiv(local, 86_400);
    int rem = Math.floorMod(local, 86_400);
    // Days from 0000-03-01, in eras of 400 years that start on March 1.
    long z = days + EPOCH_DAYS;
    long era = Math.floorDiv(z, 146_097);
    long doe = z - era * 146_097;
    long yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    long mp = (5 * doy + 2) / 153;
    int d = (int) (doy - (153 * mp + 2) / 5 + 1);
    int m = (int) (mp < 10 ? mp + 2 : mp - 10);
    long y = yoe + era * 400 + (m <= 1 ? 1 : 0);
    long sinceBase = y - 1900;
    if (sinceBase != (int) sinceBase) {
      return false;
    }
    year = (int) sinceBase;
    mon = m;
    mday = d;
    hour = rem / 3_600;
    min = rem / 60 % 60;
    sec = rem % 60;
    wday = Math.floorMod(days + 4, 7);
    yday = (int) (days - daysFromCivil(y, 1));
    return true;
  }

  /**
   * Normalises the fields as UTC and gives their seconds since the epoch: C's {@code timegm}. On success every field
   * is in its range, {@code isdst} is 0, {@code gmtoff} 0 and {@code zone} {@code "GMT"}; when the normalised year
   * since 1900 is not an int it gives {@link #FAILED} and changes nothing.
   */
  long timegm() {
    long t = seconds();
    if (!set(t)) {
      return FAILED;
    }
    isdst = 0;
    gmtoff = 0;
    zone = "GMT";
    return t;
  }

  /** Whether {@code y} is a leap year of the proleptic Gregorian calendar. */
  static boolean leap(long y) {
    return y % 4 == 0 && (y % 100 != 0 || y % 400 == 0);
  }

  /** The days from 1970-01-01 to the first day of month {@code m} (1 to 12) of year {@code y}. */
  private static long daysFromCivil(long y, int m) {
    long yy = m <= 2 ? y - 1 : y;
    long era = Math.floorDiv(yy, 400);
    long yoe = yy - era * 400;
    long doy = (153L * (m > 2 ? m - 3 : m + 9) + 2) / 5;
    long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    return era * 146_097 + doe - EPOCH_DAYS;
  }
}
