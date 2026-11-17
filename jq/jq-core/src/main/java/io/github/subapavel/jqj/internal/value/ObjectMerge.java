/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): jv_object_merge and jvp_object_merge_recursive in
 * src/jv.c.
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
package io.github.subapavel.jqj.internal.value;

import io.github.subapavel.jqj.query.run.Charges;
import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * jq 1.8.2's object {@code +} ({@code jv_object_merge}) and object merge {@code *} ({@code jvp_object_merge_recursive};
 * GLOSSARY "Object merge"): a growable object of the left object's entries, then each entry of the right one in its
 * key order, a key the left has keeping its place with the right value. Merge goes into the two values of a key when
 * both are objects, building their merge in place of the left value; past {@link #MAX_DEPTH} levels of that it raises
 * jq's catchable "Object merge too deep" (ADR-0029). Each level is a frame of its own stack, never a Java frame. Each
 * level charges a container, then an entry per distinct key as it enters (ADR-0035 amendment), and one tick per entry
 * of its right object and one per left entry it copies, for object {@code +} and merge {@code *} alike (ADR-0034
 * amendment). A view's own walks charge theirs, its key proof (ADR-0044).
 */
public final class ObjectMerge {
  /** jq's {@code MAX_OBJECT_MERGE_DEPTH}. */
  static final int MAX_DEPTH = 10_000;

  private static final String TOO_DEEP = "Object merge too deep";

  /** A level's first room for keys; it doubles as keys enter. */
  private static final int ROOM = 8;

  private ObjectMerge() {}

  /** {@code a + b} on two objects. */
  public static BuiltObject plus(RunState state, JqObject a, JqObject b) {
    return merge(state, a, b, false);
  }

  /**
   * {@code a * b} on two objects.
   *
   * @throws FilterException "Object merge too deep", jq's error
   */
  public static BuiltObject merge(RunState state, JqObject a, JqObject b) {
    return merge(state, a, b, true);
  }

  private static BuiltObject merge(RunState state, JqObject a, JqObject b, boolean deep) {
    Level level = new Level(state, a, b, 0, null);
    for (;;) {
      if (level.cursor == -1) {
        BuiltObject merged = level.build();
        level = level.outer;
        if (level == null) {
          return merged;
        }
        level.values[level.pending] = merged;
        continue;
      }
      JqString key = level.rightKeys.key(level.cursor);
      JqValue value = level.right.member(state, level.cursor);
      level.cursor = level.right.next(state, level.cursor);
      state.tick();
      String name = key.value();
      Integer at = level.index.get(name);
      if (deep && at != null && level.values[at] instanceof JqObject inner && value instanceof JqObject other) {
        level.pending = at;
        level = new Level(state, inner, other, level.depth + 1, level);
      } else if (at != null) {
        level.values[at] = value;
      } else {
        level.add(state, key, name, value);
      }
    }
  }

  /** One merge level: the object it builds, and the right object's entries still to come. */
  private static final class Level {
    final int depth;

    /** The level whose value at {@link #pending} this one merges, or null for the outermost. */
    final Level outer;

    final Members right;
    final Keyed rightKeys;

    /** The right object's next entry, or -1 after its last. */
    int cursor;

    /** The index of the key this level's inner level merges. */
    int pending;

    JqString[] keys = new JqString[ROOM];
    Object[] values = new Object[ROOM];
    int size;

    /** Each key's index, by its text: two keys are one when their UTF-8 bytes are, and so their texts. */
    final Map<String, Integer> index = new HashMap<>();

    /**
     * The level at {@code depth} of {@code left} and {@code right}: the depth checked, then each side's walk, a view's
     * after its proof, the right one's first, then the left entries entered, one tick each.
     */
    Level(RunState state, JqObject left, JqObject right, int depth, Level outer) {
      if (depth > MAX_DEPTH) {
        throw new FilterException(BuiltString.of(TOO_DEEP));
      }
      this.depth = depth;
      this.outer = outer;
      this.right = KeyProof.members(state, right);
      this.rightKeys = (Keyed) this.right;
      this.cursor = this.right.first();
      state.chargeBuilt(Charges.CONTAINER);
      Members entries = KeyProof.members(state, left);
      Keyed names = (Keyed) entries;
      for (int c = entries.first(); c != -1; c = entries.next(state, c)) {
        state.tick();
        JqString key = names.key(c);
        add(state, key, key.value(), entries.member(state, c));
      }
    }

    /** A key the object does not have yet, with its value: an entry charged before it enters. */
    void add(RunState state, JqString key, String name, JqValue value) {
      state.chargeBuilt(Charges.ENTRY);
      if (size == keys.length) {
        keys = Arrays.copyOf(keys, size << 1);
        values = Arrays.copyOf(values, size << 1);
      }
      index.put(name, size);
      keys[size] = key;
      values[size++] = value;
    }

    BuiltObject build() {
      return new BuiltObject(keys, values, size);
    }
  }
}
