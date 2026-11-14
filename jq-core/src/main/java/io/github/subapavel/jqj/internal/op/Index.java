/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): jv_get in src/jv_aux.c, and jv_array_indexes in src/jv.c.
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
package io.github.subapavel.jqj.internal.op;

import io.github.subapavel.jqj.internal.value.ArrayView;
import io.github.subapavel.jqj.internal.value.BuiltArray;
import io.github.subapavel.jqj.internal.value.DoubleNumber;
import io.github.subapavel.jqj.internal.value.Equality;
import io.github.subapavel.jqj.internal.value.Keyed;
import io.github.subapavel.jqj.internal.value.Members;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.run.CallForm;
import io.github.subapavel.jqj.query.run.Fn1;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;

/**
 * jq 1.8.2's {@code .[k]}, its {@code jv_get} (jq-ref, recorded: diff/index.rec, slice.rec): an object and a string key
 * give the key's last value or null; an array and a number give the element, or null for NaN or past either end, a
 * fraction cut toward zero and a negative index counted from the end; an array or a string and an object key give the
 * slice of the key's {@code "start"} and {@code "end"} ({@link Slice}); null and a string, number or object key give
 * null; an array and an array key give the key's {@link #indexes} (#72). Any other pair is jq's error, which the quiet
 * variant {@code .[k]?} drops (ADR-0034).
 */
public final class Index implements Fn1 {
  /** {@code .[k]}. */
  public static final Operation PLAIN = new Operation(".[k]", new Index(false));

  /** {@code .[k]?}. */
  public static final Operation QUIET = new Operation(".[k]?", new Index(true));

  /** Whether this is {@code .[k]?}, which drops its own error. */
  private final boolean quiet;

  private Index(boolean quiet) {
    this.quiet = quiet;
  }

  @Override
  public Object apply(RunState state, Object in, Object key) {
    JqValue t = (JqValue) in;
    JqValue k = (JqValue) key;
    // jv_get's pairs; Java null is a pair jq has no value for, its "Cannot index" error. One pattern per case: javac
    // checks a case of several patterns again, a branch that is never taken.
    Object value =
        switch (t) {
          case Keyed object ->
              switch (k) {
                case JqString name -> orNull(object.get(state, name.value()));
                case JqNull _ -> null;
                case JqBool _ -> null;
                case JqNumber _ -> null;
                case JqArray _ -> null;
                case JqObject _ -> null;
              };
          case JqArray array ->
              switch (k) {
                case JqNumber n -> element(state, array, n.doubleValue());
                // parse_slice reads both keys; a missing one is jq's invalid, which is no number.
                case Keyed bounds ->
                    Slice.sliced(Slice.of(state, array, bounds.get(state, "start"), bounds.get(state, "end")), quiet);
                case JqArray keys -> indexes(state, (Members) array, (Members) keys);
                case JqNull _ -> null;
                case JqBool _ -> null;
                case JqString _ -> null;
              };
          case JqString s ->
              switch (k) {
                case Keyed bounds ->
                    Slice.sliced(Slice.of(state, s, bounds.get(state, "start"), bounds.get(state, "end")), quiet);
                case JqNull _ -> null;
                case JqBool _ -> null;
                case JqNumber _ -> null;
                case JqString _ -> null;
                case JqArray _ -> null;
              };
          case JqNull nul ->
              switch (k) {
                case JqString _ -> nul;
                case JqNumber _ -> nul;
                case JqObject _ -> nul;
                case JqNull _ -> null;
                case JqBool _ -> null;
                case JqArray _ -> null;
              };
          case JqBool _ -> null;
          case JqNumber _ -> null;
        };
    if (value != null) {
      return value;
    }
    if (quiet) {
      return CallForm.NONE;
    }
    throw NavigationErrors.cannotIndex(state, t, k);
  }

  /**
   * jq's {@code jv_array_indexes} of the array {@code a} and the array key {@code b}: in order, each index of {@code a}
   * at which {@code b}'s elements, not none, equal {@code a}'s from there on, overlapping runs included (jq-ref 1.8.2,
   * recorded: index/array-key-on-array-*, slice/key-array-*-on-array). As jq does, every pair inside {@code a} is
   * compared, after a pair that differs too, so a too-deep pair raises Equality's error wherever it is; a place past
   * {@code a}'s end is jq's invalid, equal to nothing. Each pair ticks once (ADR-0008), on top of Equality's own; the
   * result is a built array of computed numbers, charged as it grows (ADR-0035).
   */
  private static BuiltArray indexes(RunState state, Members a, Members b) {
    Built.Elements found = new Built.Elements(state);
    int index = 0;
    for (int start = a.first(); start != -1; start = a.next(state, start)) {
      // jq's idx stays the start only if the first pair is equal and no later pair differs.
      boolean equal = true;
      boolean any = false;
      int at = start;
      for (int k = b.first(); k != -1; k = b.next(state, k)) {
        state.tick();
        any = true;
        if (at == -1) {
          equal = false;
        } else {
          equal &= Equality.equal(state, a.member(state, at), b.member(state, k));
          at = a.next(state, at);
        }
      }
      if (any && equal) {
        found.add(new DoubleNumber(index));
      }
      index++;
    }
    return found.array();
  }

  /** {@code value}, or null for Java {@code null}, a missing key or an element past the end. */
  private static JqValue orNull(JqValue value) {
    return value == null ? JqNull.NULL : value;
  }

  /** The element at {@code i} of {@code array}, or null: a view charges the entries it steps (ADR-0034). */
  private static JqValue element(RunState state, JqArray array, double i) {
    if (Double.isNaN(i)) {
      return JqNull.NULL;
    }
    // The cast cuts toward zero and saturates, so an index past the int range is past either end.
    long at = (int) i;
    if (at < 0) {
      at += Slice.length(state, array);
      if (at < 0) {
        return JqNull.NULL;
      }
    }
    return orNull(
        switch (array) {
          case ArrayView view -> view.get(state, (int) at);
          case BuiltArray built -> at < built.length() ? built.get((int) at) : null;
        });
  }
}
