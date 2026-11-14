/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): found_string in src/jv_parse.c, jvp_utf8_next and
 * jvp_utf8_encode in src/jv_unicode.c, jv_string_sized and jvp_string_copy_replace_bad in src/jv.c.
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
package io.github.subapavel.jqj.internal.json;

import io.github.subapavel.jqj.internal.value.Tape;
import java.util.Arrays;

/**
 * jq's decoding of a JSON string (ADR-0015 and its #35 amendment, F-26), in jq's two steps: the escape-sequence pass
 * of {@code found_string} ({@link #unescape}), then {@code jv_string_sized}'s replacement of each sequence that
 * {@code jvp_utf8_next} ({@link #step}) calls invalid by one U+FFFD, over the escape-sequence pass's output and the raw
 * bytes together ({@link #decode(byte[], int, int)}). The input is a string the reader accepted, so its escape
 * sequences are well formed and every high surrogate escape sequence is followed by a low one, or a filter's escape
 * run that {@code found_string}'s checks ({@link #escapeError}) passed.
 */
public final class JsonStrings {
  /** U+FFFD REPLACEMENT CHARACTER in UTF-8. */
  static final byte[] REPLACEMENT = {(byte) 0xEF, (byte) 0xBF, (byte) 0xBD};

  /** jq's {@code utf8_first_codepoint}: the smallest code point a sequence of each length may encode. */
  private static final int[] FIRST_CODE_POINT = {0, 0, 0x80, 0x800, 0x10000};

  private JsonStrings() {}

  /**
   * {@code found_string}'s loop over {@code b[from, to)}, a string's text between its quotes: writes each escape
   * sequence's byte, or the UTF-8 of its code point (a surrogate pair as one code point, a lone low surrogate as its
   * three bytes), and copies every other byte, into {@code out} from 0; returns the count, never more than {@code to -
   * from}.
   */
  static int unescape(byte[] b, int from, int to, byte[] out) {
    int o = 0;
    int in = from;
    while (in < to) {
      byte c = b[in++];
      if (c != '\\') {
        out[o++] = c;
        continue;
      }
      c = b[in++];
      switch (c) {
        case 'b' -> out[o++] = '\b';
        case 'f' -> out[o++] = '\f';
        case 't' -> out[o++] = '\t';
        case 'n' -> out[o++] = '\n';
        case 'r' -> out[o++] = '\r';
        case 'u' -> {
          int codePoint = hex4(b, in);
          in += 4;
          if (codePoint >= 0xD800 && codePoint <= 0xDBFF) {
            // The reader checked that a low surrogate escape sequence follows. In these ranges jq's "- 0xD800" and
            // "- 0xDC00" keep the low ten bits, and its "codepoint > 0x10FFFF" cannot hold.
            codePoint = 0x10000 + ((codePoint & 0x3FF) << 10 | (hex4(b, in + 2) & 0x3FF));
            in += 6;
          }
          o += encode(codePoint, out, o);
        }
        // '"', '\\' and '/': the reader accepts no other escape letter.
        default -> out[o++] = c;
      }
    }
    return o;
  }

  /**
   * The value of the four hex digits at {@code b[p]}, or a negative number when one of them is no hex digit, as jq's
   * {@code unhex4} gives -1: that digit's -1 from {@code Character.digit} sets every bit, and no shift after it clears
   * the sign. A byte from 0x80 is negative here, so no hex digit.
   */
  private static int hex4(byte[] b, int p) {
    int v = 0;
    for (int i = p; i < p + 4; i++) {
      v = v << 4 | Character.digit(b[i], 16);
    }
    return v;
  }

  /** {@code jvp_utf8_encode}: writes the UTF-8 of {@code codePoint} at {@code out[o]}; returns its length. */
  private static int encode(int codePoint, byte[] out, int o) {
    if (codePoint < 0x80) {
      out[o] = (byte) codePoint;
      return 1;
    }
    if (codePoint < 0x800) {
      out[o] = (byte) (0xC0 | codePoint >> 6);
      out[o + 1] = (byte) (0x80 | codePoint & 0x3F);
      return 2;
    }
    if (codePoint < 0x10000) {
      out[o] = (byte) (0xE0 | codePoint >> 12);
      out[o + 1] = (byte) (0x80 | codePoint >> 6 & 0x3F);
      out[o + 2] = (byte) (0x80 | codePoint & 0x3F);
      return 3;
    }
    out[o] = (byte) (0xF0 | codePoint >> 18);
    out[o + 1] = (byte) (0x80 | codePoint >> 12 & 0x3F);
    out[o + 2] = (byte) (0x80 | codePoint >> 6 & 0x3F);
    out[o + 3] = (byte) (0x80 | codePoint & 0x3F);
    return 4;
  }

  /**
   * {@code jvp_utf8_next} at {@code s[i]}, before {@code end}: the length of the sequence there, negated when jq's code
   * point is -1. A bad lead byte or a stray continuation takes one byte; a sequence cut by {@code end} takes every byte
   * left (F-26); a missing continuation takes the bytes before it; an overlong, surrogate or too large one takes all.
   */
  static int step(byte[] s, int i, int end) {
    int first = s[i] & 0xFF;
    if (first < 0x80) {
      return 1;
    }
    if (first < 0xC2 || first > 0xF4) {
      // A continuation byte, C0, C1 or F5 to FF: jq's utf8_coding_length is 0 or UTF8_CONTINUATION_BYTE.
      return -1;
    }
    int length = first < 0xE0 ? 2 : first < 0xF0 ? 3 : 4;
    if (length > end - i) {
      return i - end;
    }
    // jq's utf8_coding_bits: the lead byte's bits below its length prefix.
    int codePoint = first & 0x7F >> length;
    for (int k = 1; k < length; k++) {
      int next = s[i + k] & 0xFF;
      if ((next & 0xC0) != 0x80) {
        return -k;
      }
      codePoint = codePoint << 6 | next & 0x3F;
    }
    if (codePoint < FIRST_CODE_POINT[length] || (codePoint >= 0xD800 && codePoint <= 0xDFFF) || codePoint > 0x10FFFF) {
      return -length;
    }
    return length;
  }

  /**
   * A string entry's value, jq's string: for tag 5 its bytes between the quotes, for tag 6 {@link #decode(byte[], int,
   * int)} of them. The array is new.
   */
  public static byte[] decode(byte[] doc, long entry) {
    int from = Tape.off(entry) + 1;
    int to = Tape.off(entry) + Tape.span(entry) - 1;
    if (Tape.tag(entry) == Tape.STRING) {
      return Arrays.copyOfRange(doc, from, to);
    }
    return decode(doc, from, to);
  }

  /**
   * The value of the string text {@code b[from, to)}, whose escape sequences are well formed with every high surrogate
   * paired (a tag-6 string the reader accepted, or an escape run of a filter that {@link #escapeError} passed):
   * {@link #unescape}, then {@link #replaceBad}. The array is new.
   */
  public static byte[] decode(byte[] b, int from, int to) {
    byte[] s = new byte[to - from];
    int n = unescape(b, from, to, s);
    return replaceBad(s, 0, n);
  }

  /**
   * {@code jv_string_sized} over {@code s[from, to)}: each sequence {@link #step} calls invalid becomes one U+FFFD, and
   * every other byte stays. The array is new.
   */
  public static byte[] replaceBad(byte[] s, int from, int to) {
    // The bytes the result needs: a valid sequence keeps its own, a bad one becomes the three of U+FFFD.
    int size = 0;
    for (int i = from; i < to; ) {
      int length = step(s, i, to);
      size += length > 0 ? length : REPLACEMENT.length;
      i += Math.abs(length);
    }
    byte[] out = new byte[size];
    int o = 0;
    for (int i = from; i < to; ) {
      int length = step(s, i, to);
      if (length > 0) {
        System.arraycopy(s, i, out, o, length);
        o += length;
      } else {
        System.arraycopy(REPLACEMENT, 0, out, o, REPLACEMENT.length);
        o += REPLACEMENT.length;
      }
      i += Math.abs(length);
    }
    return out;
  }

  /**
   * {@code found_string}'s checks over {@code b[from, to)}, a string's text between its quotes, when every byte of it is
   * in an escape sequence, a backslash and its letter (or the U+FFFD of a bad byte), as in the filter lexer's escape run
   * with its bad UTF-8 replaced (GLOSSARY "Escape run"): jq's
   * message for the first bad sequence, or null when all are well formed. A {@code \\u} needs four bytes before
   * {@code to}, all hex digits, and a high surrogate a {@code \\u} low surrogate right after it.
   */
  public static String escapeError(byte[] b, int from, int to) {
    int in = from;
    while (in < to) {
      // b[in] is the backslash, b[in + 1] its letter.
      in += 2;
      switch (b[in - 1]) {
        case '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> {
          // A one-letter escape sequence.
        }
        case 'u' -> {
          if (in + 4 > to) {
            return "Invalid \\uXXXX escape";
          }
          int unit = hex4(b, in);
          if (unit < 0) {
            return "Invalid characters in \\uXXXX escape";
          }
          in += 4;
          if (unit >= 0xD800 && unit <= 0xDBFF) {
            // jq tells a missing, malformed or wrong second unit apart in no word: one message for all three. A
            // malformed one is negative, below every low surrogate. jq also checks that a \\u follows; here b[in] is
            // the next sequence's backslash, as every byte is in one, and a sequence that is no \\u has a backslash or a
            // byte of U+FFFD at in + 2, no hex digit, so it is a malformed unit too.
            if (in + 6 > to || !isLowSurrogate(hex4(b, in + 2))) {
              return "Invalid \\uXXXX\\uXXXX surrogate pair escape";
            }
            in += 6;
          }
        }
        default -> {
          return "Invalid escape";
        }
      }
    }
    return null;
  }

  private static boolean isLowSurrogate(int unit) {
    return unit >= 0xDC00 && unit <= 0xDFFF;
  }
}
