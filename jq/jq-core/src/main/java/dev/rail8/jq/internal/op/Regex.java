/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): f_match and f_match_name_iter in src/builtin.c.
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

import dev.rail8.jq.internal.value.BuiltObject;
import dev.rail8.jq.internal.value.BuiltString;
import dev.rail8.jq.internal.value.DoubleNumber;
import dev.rail8.jq.query.ir.Operation;
import dev.rail8.jq.query.run.Charges;
import dev.rail8.jq.query.run.FnN;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.value.JqBool;
import dev.rail8.jq.value.JqNull;
import dev.rail8.jq.value.JqString;
import dev.rail8.jq.value.JqValue;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.joni.Matcher;
import org.joni.Option;
import org.joni.Region;
import org.joni.exception.TimeoutException;

/**
 * jq 1.8.2's {@code _match_impl(re; mode; testmode)}, the native under {@code test}, {@code match}, {@code capture},
 * {@code scan}, {@code splits}, {@code sub} and their kin, on joni. The input and {@code re} must be strings, then
 * {@code mode} a string of the modifiers {@code g i x m s p l n} or null; then {@code re} compiles, through the
 * Workspace's {@link Patterns}. In test mode the result is whether {@code re} matches; otherwise an array of one match
 * object per match, all of them under {@code g}: {@code offset}, {@code length} and {@code string} of the match, in code
 * points, and {@code captures}, one object per group with its {@code name} or null. After an empty match the next
 * search starts one byte further, as jq's loop does, so a search may start inside a code point.
 *
 * <p>jq counts an offset by stepping from byte 0 by the length each lead byte gives; over well-formed UTF-8 its steps
 * are the lead bytes, so the count before a position is the lead bytes before it, also inside a code point. Here it
 * is a running count that moves from the last position counted, either way, so a global scan counts its input about
 * once instead of once per match.
 *
 * <p>Each search call may take the smaller of the run's regex match time and the time left before its deadline. One
 * that runs out of the match time is jq's catchable "Regex failure: retry-limit-in-match over"; one that runs into
 * the deadline ends the run with the deadline's termination; an interrupt cancels the run, with the thread's flag set
 * again.
 *
 * <p>Ticks: one per search call, and one per started KiB from the search start to the match end, or to the input's end
 * when nothing matches; the lookup ticks once per started KiB of the pattern. A string the run read is copied to an
 * array for joni, which is not charged; what the result builds is charged before it is allocated.
 */
public final class Regex {
  /** {@code _match_impl(re; mode; testmode)}. */
  public static final Operation MATCH =
      new Operation("_match_impl", (FnN) (state, in, args) -> match(state, (JqValue) in, (JqValue) args[0], (JqValue) args[1], (JqValue) args[2]));

  /** jq's error text for a search that ran out of its time, Oniguruma's for its retry limit. */
  static final String RETRY_LIMIT = "Regex failure: retry-limit-in-match over";

  /** The keys of a match object, in jq's order. */
  private static final JqString[] MATCH_KEYS = keys("offset", "length", "string", "captures");

  /** The keys of a capture that matched a non-empty string, in jq's order. */
  private static final JqString[] CAPTURE_KEYS = keys("offset", "length", "string", "name");

  /** The keys of a capture that matched nothing or an empty string, in jq's order. */
  private static final JqString[] EMPTY_CAPTURE_KEYS = keys("offset", "string", "length", "name");

  /** The value of {@code offset} for a capture that did not match. */
  private static final DoubleNumber NO_OFFSET = new DoubleNumber(-1);

  /** The value of {@code length} for an empty match or capture. */
  private static final DoubleNumber NO_LENGTH = new DoubleNumber(0);

  private Regex() {}

  /** f_match: the result of {@code input | _match_impl(regex; modifiers; testmode)}. */
  private static JqValue match(RunState state, JqValue input, JqValue regex, JqValue modifiers, JqValue testmode) {
    boolean test = testmode == JqBool.TRUE;
    if (!(input instanceof JqString text)) {
      throw Arithmetic.cannot(state, input, "cannot be matched, as it is not a string");
    }
    if (!(regex instanceof JqString pattern)) {
      throw Arithmetic.cannot(state, regex, "is not a string");
    }
    int options = Option.CAPTURE_GROUP;
    boolean global = false;
    if (modifiers instanceof JqString mods) {
      MemorySegment m = mods.utf8();
      for (long i = 0; i < m.byteSize(); i++) {
        switch (m.get(ValueLayout.JAVA_BYTE, i)) {
          case 'g' -> global = true;
          case 'i' -> options |= Option.IGNORECASE;
          case 'x' -> options |= Option.EXTEND;
          case 'm' -> options |= Option.MULTILINE;
          case 's' -> options |= Option.SINGLELINE;
          case 'p' -> options |= Option.MULTILINE | Option.SINGLELINE;
          case 'l' -> options |= Option.FIND_LONGEST;
          case 'n' -> options |= Option.FIND_NOT_EMPTY;
          default -> throw Arithmetic.error(mods.value() + " is not a valid modifier string");
        }
      }
    } else if (!(modifiers instanceof JqNull)) {
      throw Arithmetic.cannot(state, modifiers, "is not a string");
    }
    Patterns patterns = Patterns.of(state);
    Patterns.Compiled compiled = patterns.lookup(state, pattern, options);
    byte[] bytes;
    int end;
    if (text instanceof BuiltString built) {
      bytes = built.bytes();
      end = built.byteLength();
    } else {
      bytes = text.utf8().toArray(ValueLayout.JAVA_BYTE);
      end = bytes.length;
    }
    Built.Elements matches = test ? null : new Built.Elements(state);
    CodePoints points = new CodePoints(bytes);
    int start = 0;
    do {
      state.tick();
      Matcher matcher = compiled.regex.matcher(bytes, 0, end);
      if (search(state, patterns, matcher, start, end) < 0) {
        state.tick(Built.kibibytes(end - start));
        break;
      }
      Region region = matcher.getEagerRegion();
      int matchStart = region.getBeg(0);
      int matchEnd = region.getEnd(0);
      state.tick(Built.kibibytes(matchEnd - start));
      if (test) {
        return JqBool.TRUE;
      }
      if (matchEnd == matchStart) {
        matches.add(emptyMatch(state, compiled, region, points));
        // ensure '"qux" | match("(?=u)"; "g")' matches just once
        start = matchEnd + 1;
      } else {
        matches.add(match(state, compiled, region, bytes, points));
        start = matchEnd;
      }
    } while (global && start <= end);
    return test ? JqBool.FALSE : matches.array();
  }

  /**
   * One search call of {@code matcher} from {@code start} to {@code end}: where the match starts, or a negative for
   * none.
   */
  private static int search(RunState state, Patterns patterns, Matcher matcher, int start, int end) {
    long matchTime = patterns.matchTime();
    long left = state.deadlineLeft();
    matcher.setTimeout(Math.max(0, Math.min(matchTime, left)));
    try {
      return matcher.searchInterruptible(start, end, Option.NONE);
    } catch (TimeoutException e) {
      if (left <= matchTime) {
        throw state.overDeadline();
      }
      throw Arithmetic.error(RETRY_LIMIT);
    } catch (InterruptedException e) {
      // joni cleared the flag when it saw it; the code after the cancelled run must still see it.
      Thread.currentThread().interrupt();
      throw state.cancelled();
    }
  }

  /**
   * The match object of an empty match: its offset, length 0, the empty string, and each capture at the match's own
   * offset with the empty string, or at -1 with null when it did not match.
   */
  private static BuiltObject emptyMatch(RunState state, Patterns.Compiled compiled, Region region, CodePoints points) {
    DoubleNumber offset = new DoubleNumber(points.count(region.getBeg(0)));
    Built.Elements captures = new Built.Elements(state);
    for (int i = 1; i < region.getNumRegs(); i++) {
      boolean unmatched = region.getBeg(i) == -1;
      captures.add(
          object(
              state,
              EMPTY_CAPTURE_KEYS,
              unmatched ? NO_OFFSET : offset,
              unmatched ? JqNull.NULL : empty(state),
              NO_LENGTH,
              name(state, compiled, i)));
    }
    return object(state, MATCH_KEYS, offset, NO_LENGTH, empty(state), captures.array());
  }

  /** The match object of a non-empty match, its offsets and lengths in code points, and its captures. */
  private static BuiltObject match(RunState state, Patterns.Compiled compiled, Region region, byte[] bytes, CodePoints points) {
    int matchStart = region.getBeg(0);
    int matchEnd = region.getEnd(0);
    long[] span = points.span(matchStart, matchEnd);
    BuiltString string = slice(state, bytes, matchStart, matchEnd);
    Built.Elements captures = new Built.Elements(state);
    for (int i = 1; i < region.getNumRegs(); i++) {
      int from = region.getBeg(i);
      int to = region.getEnd(i);
      if (from == to) {
        boolean unmatched = from == -1;
        captures.add(
            object(
                state,
                EMPTY_CAPTURE_KEYS,
                unmatched ? NO_OFFSET : new DoubleNumber(points.count(from)),
                unmatched ? JqNull.NULL : empty(state),
                NO_LENGTH,
                name(state, compiled, i)));
        continue;
      }
      long[] capture = points.span(from, to);
      captures.add(
          object(
              state,
              CAPTURE_KEYS,
              new DoubleNumber(capture[0]),
              new DoubleNumber(capture[1]),
              slice(state, bytes, from, to),
              name(state, compiled, i)));
    }
    return object(state, MATCH_KEYS, new DoubleNumber(span[0]), new DoubleNumber(span[1]), string, captures.array());
  }

  /** f_match_name_iter's work for one group: its name, or null. */
  private static JqValue name(RunState state, Patterns.Compiled compiled, int group) {
    BuiltString name = compiled.name(state, group);
    return name == null ? JqNull.NULL : name;
  }

  /** A new object of the four {@code keys} and their values, charged as object construction charges it. */
  private static BuiltObject object(RunState state, JqString[] keys, Object a, Object b, Object c, Object d) {
    state.chargeBuilt(Charges.CONTAINER + Charges.ENTRY * keys.length);
    return new BuiltObject(keys, new Object[] {a, b, c, d});
  }

  /** A new empty string. */
  private static BuiltString empty(RunState state) {
    return BuiltString.of(Built.string(state, 0));
  }

  /** A new string of {@code bytes} from {@code from} to {@code to}. */
  private static BuiltString slice(RunState state, byte[] bytes, int from, int to) {
    byte[] out = Built.string(state, to - from);
    System.arraycopy(bytes, from, out, 0, out.length);
    return BuiltString.of(out);
  }

  private static JqString[] keys(String... names) {
    JqString[] keys = new JqString[names.length];
    for (int i = 0; i < names.length; i++) {
      keys[i] = BuiltString.of(names[i]);
    }
    return keys;
  }

  /**
   * A running count of the lead bytes of a string's bytes before a position: jq's code point index there. It moves
   * from the last position counted to the next, forward or back.
   */
  static final class CodePoints {
    private final byte[] bytes;

    /** The last position counted. */
    private int at;

    /** The lead bytes before {@link #at}. */
    private long count;

    CodePoints(byte[] bytes) {
      this.bytes = bytes;
    }

    /** The lead bytes before {@code position}. */
    long count(int position) {
      while (at < position) {
        if (lead(bytes[at++])) {
          count++;
        }
      }
      while (at > position) {
        if (lead(bytes[--at])) {
          count--;
        }
      }
      return count;
    }

    /**
     * jq's offset and length of the non-empty span from {@code from} to {@code to}: the code points before {@code from}
     * and those from it to {@code to}; or, when {@code from} lies inside a code point, where jq's step never lands, 0
     * and the code points before {@code to}.
     */
    long[] span(int from, int to) {
      long offset = count(from);
      long upTo = count(to);
      return lead(bytes[from]) ? new long[] {offset, upTo - offset} : new long[] {0, upTo};
    }

    /** Whether {@code b} starts a UTF-8 sequence: any byte but a continuation byte. */
    private static boolean lead(byte b) {
      return (b & 0xC0) != 0x80;
    }
  }
}
