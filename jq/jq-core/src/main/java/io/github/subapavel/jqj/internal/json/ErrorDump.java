/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): jv_dump_string_trunc in src/jv_print.c.
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

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;

/**
 * jq 1.8.2's {@code jv_dump_string_trunc} into its 30-byte buffer (ADR-0015): what an error text shows of a value. A
 * compact text of at most 29 bytes is shown whole. A longer one is cut to 29 bytes: a string keeps 25 bytes and ends
 * in {@code ..."}, an array in {@code ...]} and an object in {@code ...}}; a number keeps 26 bytes and ends in
 * {@code ...}. The cut steps back to the start of a character, as jq's {@code jvp_utf8_backtrack} does (jq-ref 1.8.2,
 * recorded: index/array-key-*, iterate/cut-*; observed 2026-11-12: an array key on a string shows
 * {@code [1111111111,2222222222,33...]}, an object key on an object {@code {"aaaaaaaaaaaaaaaaaaaaa":...}}).
 */
public final class ErrorDump {
  /** The longest text shown whole: jq's buffer less its NUL. */
  private static final int WHOLE = 29;

  private ErrorDump() {}

  /** What an error text shows of {@code v}, the key proofs of its read objects charged to {@code state} (ADR-0044). */
  public static String of(RunState state, JqValue v) {
    int closing =
        switch (v) {
          case JqString _ -> '"';
          case JqArray _ -> ']';
          case JqObject _ -> '}';
          case JqNumber _ -> 0;
          // Null and the booleans are never longer than five bytes, so they are never cut.
          case JqNull _ -> 0;
          case JqBool _ -> 0;
        };
    return of(Printer.head(state, v, WHOLE + 1), closing);
  }

  /**
   * What an error text shows of a compact text whose first bytes are {@code dump}, all of them when it has at most
   * {@link #WHOLE}, otherwise at least one more; {@code closing} is the bracket or quote a cut ends with, 0 for none.
   */
  public static String of(byte[] dump, int closing) {
    if (dump.length <= WHOLE) {
      return new String(dump, UTF_8);
    }
    String tail = closing == 0 ? "..." : "..." + (char) closing;
    int kept = WHOLE - tail.length();
    // The first byte not kept starts a character.
    while ((dump[kept] & 0xC0) == 0x80) {
      kept--;
    }
    return new String(dump, 0, kept, UTF_8) + tail;
  }
}
