package dev.rail8.jq.internal.op;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.Locale;

/**
 * The running run's clock and zone, from its host, and what its local conversions found so far. Offsets come from
 * the JDK's tzdb; a zone's name is the JDK's English short name, with a {@code GMT±hh:mm} name written {@code ±hh} or
 * {@code ±hhmm}, as tzdata names a zone without an abbreviation.
 *
 * <p>A local time that a zone's change of offset skips or repeats converts as jq 1.8.2 does on glibc: a skipped time
 * moves on by the length of the gap, into daylight saving time when one side has it; a repeated time takes the offset
 * that the run's previous local conversion found, the first one of a run the side reached from UTC. A conversion asked
 * for standard or daylight saving time that the instant is not in takes the offset of the nearest instant that is,
 * within about 17 years, or else moves by an hour. From the year 5 881 581 on, a zone keeps the offset it has at the
 * end of the year before; before its first change of offset, its name is LMT.
 */
final class HostTime {
  /** The zone of a run whose host sets none. */
  private static final ZoneId UTC = ZoneId.of("UTC");

  /** How far a conversion looks for an instant in the asked daylight saving state: 536 454 000 s, about 17 years. */
  private static final long DST_SEARCH = 536_454_000L;

  /** The probes a local conversion makes before it gives up, as glibc's does. */
  private static final int PROBES = 6;

  /** The seconds since the epoch the JDK's zone rules convert: within year ±999 999 999 with a day to spare. */
  private static final long INSTANT_LIMIT = 31_556_889_832_780_799L - 2 * 86_400L;

  /**
   * The start of the year 5 881 581, UTC. From then on glibc applies no change of offset, as the count of its days
   * since the epoch no longer fits an {@code int}: a zone keeps the offset and the name it has just before.
   */
  private static final long FROZEN = 185_542_602_134_400L;

  /** The JDK's English short zone name. */
  private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("zzz", Locale.ENGLISH);

  private InstantSource clock = InstantSource.system();
  private ZoneId zone = UTC;
  private ZoneRules rules = UTC.getRules();

  /** The offset the run's latest local conversion used, 0 before the first. */
  private long latest;

  /** A run begins with its host's clock and zone; no local conversion of it has run yet. */
  void begin(InstantSource clock, ZoneId zone) {
    this.clock = clock;
    if (!zone.equals(this.zone)) {
      this.zone = zone;
      this.rules = zone.getRules();
    }
    latest = 0;
  }

  /** The clock's seconds since the epoch, in whole microseconds, as jq reads {@code gettimeofday}. */
  double now() {
    Instant now = clock.instant();
    return now.getEpochSecond() + now.getNano() / 1_000 / 1e6;
  }

  /**
   * Sets {@code tm} to the instant {@code t} in the zone: C's {@code localtime}. False, and nothing changed, when its
   * year since 1900 is not an int.
   */
  boolean localtime(long t, BrokenDownTime tm) {
    long offset = offset(t);
    boolean overflows = offset > 0 ? t > Long.MAX_VALUE - offset : t < Long.MIN_VALUE - offset;
    if (overflows || !tm.set(t + offset)) {
      return false;
    }
    tm.isdst = dst(t) ? 1 : 0;
    tm.gmtoff = offset;
    tm.zone = name(t);
    return true;
  }

  /**
   * The seconds since the epoch of {@code tm}'s fields read as a local time of the zone, and {@code tm} normalised
   * to that instant: C's {@code mktime}, with {@code tm.isdst} the daylight saving state asked for, none when
   * negative. {@link BrokenDownTime#FAILED}, and nothing changed, when the instant has no year since 1900 that is an
   * int, when a skipped time has no side to prefer, or when no instant is found in {@link #PROBES} probes.
   */
  long mktime(BrokenDownTime tm) {
    long local = tm.seconds();
    int asked = tm.isdst;
    long t;
    if (rules.isFixedOffset()) {
      t = local - offset(0);
    } else {
      t = local - latest;
      long before = t;
      int probes = 0;
      while (true) {
        if (probes == PROBES) {
          return BrokenDownTime.FAILED;
        }
        long next = local - offset(t);
        if (next == t) {
          t = adjusted(local, t, asked);
          break;
        }
        if (next == before && probes > 0) {
          // A skipped time: the conversion goes back and forth between the instants each side's offset gives.
          t = skipped(t, next, asked);
          if (t == BrokenDownTime.FAILED) {
            return t;
          }
          break;
        }
        before = t;
        t = next;
        probes++;
      }
    }
    BrokenDownTime out = tm.copy();
    if (!localtime(t, out)) {
      return BrokenDownTime.FAILED;
    }
    tm.year = out.year;
    tm.mon = out.mon;
    tm.mday = out.mday;
    tm.hour = out.hour;
    tm.min = out.min;
    tm.sec = out.sec;
    tm.wday = out.wday;
    tm.yday = out.yday;
    tm.isdst = out.isdst;
    tm.gmtoff = out.gmtoff;
    tm.zone = out.zone;
    latest = local - t;
    return t;
  }

  /**
   * Of the two instants a skipped local time goes back and forth between, the one in daylight saving time when no
   * state was asked for and only one is; else the one not in the asked state; else none.
   */
  private long skipped(long a, long b, int asked) {
    boolean da = dst(a);
    boolean db = dst(b);
    if (asked < 0) {
      return da == db ? BrokenDownTime.FAILED : da ? a : b;
    }
    boolean want = asked != 0;
    if (da != want) {
      return a;
    }
    return db != want ? b : BrokenDownTime.FAILED;
  }

  /**
   * {@code t}, the instant of {@code local} in the zone, when no daylight saving state was asked for or {@code t} is
   * in it; else {@code local} at the offset of the nearest instant in the asked state, within {@link #DST_SEARCH};
   * else {@code t} moved by an hour toward the asked state's offset.
   */
  private long adjusted(long local, long t, int asked) {
    boolean want = asked > 0;
    if (asked < 0 || dst(t) == want) {
      return t;
    }
    Instant at = Instant.ofEpochSecond(clamp(t));
    long best = Long.MAX_VALUE;
    long offset = 0;
    for (ZoneOffsetTransition x = rules.previousTransition(at); x != null; x = rules.previousTransition(x.getInstant())) {
      long distance = t - x.toEpochSecond();
      if (distance > DST_SEARCH) {
        break;
      }
      if (dst(x.toEpochSecond() - 1) == want) {
        best = distance;
        offset = x.getOffsetBefore().getTotalSeconds();
        break;
      }
    }
    for (ZoneOffsetTransition x = rules.nextTransition(at); x != null; x = rules.nextTransition(x.getInstant())) {
      long distance = x.toEpochSecond() - t;
      if (distance > DST_SEARCH || distance >= best || x.toEpochSecond() >= FROZEN) {
        break;
      }
      if (dst(x.toEpochSecond()) == want) {
        best = distance;
        offset = x.getOffsetAfter().getTotalSeconds();
        break;
      }
    }
    if (best == Long.MAX_VALUE) {
      // None found: the instant an hour later for standard time, an hour earlier for daylight saving time.
      return want ? t - 3_600 : t + 3_600;
    }
    return local - offset;
  }

  /** The zone's offset east of UTC at the instant {@code t}, in seconds. */
  private long offset(long t) {
    return rules.getOffset(Instant.ofEpochSecond(clamp(t))).getTotalSeconds();
  }

  /** Whether the instant {@code t} is in daylight saving time in the zone. */
  private boolean dst(long t) {
    return rules.isDaylightSavings(Instant.ofEpochSecond(clamp(t)));
  }

  /** The zone's name at the instant {@code t}. */
  private String name(long t) {
    Instant at = Instant.ofEpochSecond(clamp(t));
    // Before a zone's first change of offset it keeps its local mean time, which tzdata names LMT.
    if (!rules.isFixedOffset() && rules.previousTransition(at.plusSeconds(1)) == null) {
      return "LMT";
    }
    String name;
    try {
      name = NAME.format(at.atZone(zone));
    } catch (DateTimeException e) {
      return "";
    }
    if (name.startsWith("GMT") && name.length() > 3) {
      // The JDK's GMT±hh:mm, a zone without an abbreviation, is tzdata's ±hh, or ±hhmm with minutes, of the offset
      // at that instant.
      long offset = offset(t);
      long a = Math.abs(offset) / 60;
      String hours = String.format(Locale.ROOT, "%c%02d", offset < 0 ? '-' : '+', a / 60);
      return a % 60 == 0 ? hours : hours + String.format(Locale.ROOT, "%02d", a % 60);
    }
    return name;
  }

  /** {@code t} within the range the JDK's zone rules convert, and before {@link #FROZEN}. */
  private static long clamp(long t) {
    return Math.max(-INSTANT_LIMIT, Math.min(FROZEN - 1, t));
  }
}
