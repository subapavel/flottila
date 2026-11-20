/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): jv_set, jv_dels, jv_setpath, jv_getpath, delpaths_sorted
 * and jv_delpaths in src/jv_aux.c, and jv_array_set in src/jv.c.
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

import dev.rail8.jq.internal.value.BuiltArray;
import dev.rail8.jq.internal.value.BuiltObject;
import dev.rail8.jq.internal.value.BuiltString;
import dev.rail8.jq.internal.value.Equality;
import dev.rail8.jq.internal.value.KeyProof;
import dev.rail8.jq.internal.value.Keyed;
import dev.rail8.jq.internal.value.Members;
import dev.rail8.jq.internal.value.MergeSort;
import dev.rail8.jq.internal.value.Numbers;
import dev.rail8.jq.internal.value.Order;
import dev.rail8.jq.query.ir.Operation;
import dev.rail8.jq.query.ir.PathRule;
import dev.rail8.jq.query.run.Charges;
import dev.rail8.jq.query.run.FilterException;
import dev.rail8.jq.query.run.Fn1;
import dev.rail8.jq.query.run.Fn2;
import dev.rail8.jq.query.run.Path;
import dev.rail8.jq.query.run.PathStep;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.value.JqArray;
import dev.rail8.jq.value.JqNull;
import dev.rail8.jq.value.JqNumber;
import dev.rail8.jq.value.JqObject;
import dev.rail8.jq.value.JqString;
import dev.rail8.jq.value.JqValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * jq 1.8.2's path natives in value mode: {@code getpath/1}, {@code setpath/2} and {@code delpaths/1}, its {@code
 * jv_getpath}, {@code jv_setpath} and {@code jv_delpaths}. A path is an array of keys: each key is read as {@code .[k]}
 * reads it ({@link Index}) and written as jq's {@code jv_set} writes it, and a path of more than {@link #MAX_DEPTH} keys
 * is jq's "Path too deep". Each native walks its paths in a loop, never by recursion, so a deep path takes no Java
 * stack.
 *
 * <p>Ticks: getpath and setpath tick once per key of the path, and setpath also once per slot of each container it
 * builds. delpaths ticks once per path, the ticks of each compare of its path sort, once per key of each path, once
 * per slot of each container it copies to write into, and once per element or entry of each container it deletes
 * from, kept or dropped. Every container built is charged before it is allocated: a container plus an element per
 * slot of an array or an entry per key of an object.
 */
public final class Paths {
  /** The most keys a path holds, jq's {@code MAX_PATH_DEPTH}. */
  static final int MAX_DEPTH = 10_000;

  /** {@code getpath/1}. */
  public static final Operation GETPATH = new Operation("getpath", new GetPath(), Declared.step(PathRule.KEY_LIST));

  /** {@code setpath/2}. */
  public static final Operation SETPATH =
      new Operation(
          "setpath", (Fn2) (state, in, path, value) -> setpath(state, (JqValue) in, (JqValue) path, (JqValue) value), Declared.KEEPS);

  /** {@code delpaths/1}. */
  public static final Operation DELPATHS =
      new Operation("delpaths", (Fn1) (state, in, paths) -> delpaths(state, (JqValue) in, (JqValue) paths), Declared.KEEPS);

  /** The first capacity of an object built entry by entry. */
  private static final int ROOM = 4;

  private Paths() {}

  /**
   * {@code getpath/1}: in a path variant its result's path is its input's with each key of its argument added in turn,
   * as jq's {@code _jq_path_append} concatenates them; on an untracked input its result is untracked, with no error.
   */
  private static final class GetPath implements Fn1, PathStep {
    @Override
    public Object apply(RunState state, Object in, Object path) {
      return getpath(state, (JqValue) in, (JqValue) path);
    }

    /** Never asked: the result of getpath on an untracked input is untracked, with no error. */
    @Override
    public FilterException untracked(RunState state, Object in, Object key, Object end) {
      throw new IllegalStateException("getpath raises no error on an input that is not found in a path variant's input");
    }

    /** {@code at} with each key of {@code keys}, the array {@link #apply} read along, added in order. */
    @Override
    public Path extend(RunState state, Path at, Object in, Object keys, Object end) {
      Members list = (Members) keys;
      Path p = at;
      for (int c = list.first(); c != -1; c = list.next(state, c)) {
        p = p.child(list.member(state, c));
      }
      return p;
    }
  }

  /** jq's {@code jv_getpath}: {@code root} read at each key of {@code path} in turn; the first error ends the walk. */
  static JqValue getpath(RunState state, JqValue root, JqValue path) {
    depth(state, path);
    Members keys = (Members) path;
    JqValue at = root;
    for (int c = keys.first(); c != -1; c = keys.next(state, c)) {
      state.tick();
      at = Index.get(state, at, keys.member(state, c));
    }
    return at;
  }

  /**
   * jq's {@code jv_setpath}: {@code root} with {@code value} at {@code path}, each level on the way written as {@code
   * jv_set} writes it. On the way down each key is read, and a key that is no slice is also checked as {@code jv_set}
   * would write it, so a level's error comes before a deeper one; on the way up a slice's own error comes after the
   * deeper ones, as in jq's recursion.
   */
  static JqValue setpath(RunState state, JqValue root, JqValue path, JqValue value) {
    int n = depth(state, path);
    if (n == 0) {
      return value;
    }
    JqValue[] containers = new JqValue[n];
    JqValue[] keys = new JqValue[n];
    Members list = (Members) path;
    JqValue at = root;
    int i = 0;
    for (int c = list.first(); c != -1; c = list.next(state, c), i++) {
      state.tick();
      JqValue key = list.member(state, c);
      containers[i] = at;
      keys[i] = key;
      JqValue below = Index.get(state, at, key);
      if (!(key instanceof JqObject)) {
        settable(state, at, key);
      }
      at = below;
    }
    JqValue result = value;
    for (i = n - 1; i >= 0; i--) {
      result = set(state, containers[i], keys[i], result);
    }
    return result;
  }

  /**
   * jq's {@code jv_delpaths}: {@code root} without the values at {@code paths}. The paths are sorted first, then each is
   * checked in sorted order; no path leaves {@code root} as it is, and an empty one deletes it all, giving null.
   * Otherwise {@code delpaths_sorted} works level by level: the paths of one key at a level form a group; a group whose
   * first path ends there deletes the key, and any other writes the key's value with its paths deleted, unless that
   * value is null. Each level's deletes run last, together, after its writes.
   */
  static JqValue delpaths(RunState state, JqValue root, JqValue paths) {
    if (!(paths instanceof JqArray list)) {
      throw error("Paths must be specified as an array");
    }
    Members members = (Members) list;
    JqValue[] sorted = new JqValue[Slice.length(state, list)];
    int n = 0;
    for (int c = members.first(); c != -1; c = members.next(state, c)) {
      state.tick();
      sorted[n++] = members.member(state, c);
    }
    sorted = MergeSort.sort(sorted, n, (a, b) -> Order.compare(state, a, b));
    JqValue[][] keys = new JqValue[n][];
    for (int i = 0; i < n; i++) {
      if (!(sorted[i] instanceof JqArray path)) {
        throw error("Path must be specified as array, not " + NavigationErrors.kind(sorted[i]));
      }
      keys[i] = new JqValue[depth(state, path)];
      Members of = (Members) path;
      int k = 0;
      for (int c = of.first(); c != -1; c = of.next(state, c)) {
        state.tick();
        keys[i][k++] = of.member(state, c);
      }
    }
    if (n == 0) {
      return root;
    }
    if (keys[0].length == 0) {
      return JqNull.NULL;
    }
    return deleteSorted(state, root, keys, n);
  }

  /**
   * jq's {@code delpaths_sorted} of the sorted, nonempty {@code paths} on {@code root}, its recursion an explicit stack
   * of levels: a level's group whose value is to be written opens the level below, and a finished level writes its
   * value into the one above.
   */
  private static JqValue deleteSorted(RunState state, JqValue root, JqValue[][] paths, int n) {
    Level level = new Level(null, root, 0, n);
    for (;;) {
      if (level.next < level.to) {
        int depth = level.depth;
        int i = level.next;
        JqValue key = paths[i][depth];
        int j = i + 1;
        while (j < level.to && Equality.equal(state, key, paths[j][depth])) {
          j++;
        }
        level.next = j;
        if (paths[i].length == depth + 1) {
          // The whole key goes, so the longer paths of the group are moot.
          level.deletes.add(key);
          continue;
        }
        JqValue below = level.get(state, key);
        if (!(below instanceof JqNull)) {
          level.key = key;
          level = new Level(level, below, i, j);
        }
        continue;
      }
      JqValue done = level.finish(state);
      level = level.outer;
      if (level == null) {
        return done;
      }
      level.set(state, level.key, done);
    }
  }

  /** The number of keys of {@code path}: jq's errors unless it is an array of at most {@link #MAX_DEPTH} keys. */
  private static int depth(RunState state, JqValue path) {
    if (!(path instanceof JqArray array)) {
      throw error("Path must be specified as an array");
    }
    int n = Slice.length(state, array);
    if (n > MAX_DEPTH) {
      throw tooDeep();
    }
    return n;
  }

  /** jq's error for a path of more keys than a native takes. */
  static FilterException tooDeep() {
    return error("Path too deep");
  }

  /** jq's {@code jv_set(t, k, null)} where {@code k} is no slice, for its error only: it builds nothing. */
  private static void settable(RunState state, JqValue t, JqValue k) {
    boolean nul = t instanceof JqNull;
    if (k instanceof JqString && (nul || t instanceof JqObject)) {
      return;
    }
    if (k instanceof JqNumber number && (nul || t instanceof JqArray)) {
      index(state, nul ? 0 : Slice.length(state, (JqArray) t), number);
      return;
    }
    throw cannotUpdate(t, k);
  }

  /**
   * jq's {@code jv_set}: {@code t} with {@code v} at {@code k}. A string key writes an object's key, in its place or
   * last when it is new; a number writes an array's element ({@link #index}), with null up to it; an object key writes
   * an array's slice with the array {@code v}. Null is an empty object or array to all three.
   */
  private static JqValue set(RunState state, JqValue t, JqValue k, JqValue v) {
    boolean nul = t instanceof JqNull;
    if (k instanceof JqString name && (nul || t instanceof JqObject)) {
      return withKey(state, nul ? null : (JqObject) t, name, v);
    }
    if (k instanceof JqNumber number && (nul || t instanceof JqArray)) {
      JqArray array = nul ? empty() : (JqArray) t;
      return withElement(state, array, index(state, Slice.length(state, array), number), v);
    }
    if (k instanceof JqObject bounds && (nul || t instanceof JqArray)) {
      return withSlice(state, nul ? empty() : (JqArray) t, (Keyed) bounds, v);
    }
    if (k instanceof JqObject && t instanceof JqString) {
      throw error("Cannot update string slices");
    }
    throw cannotUpdate(t, k);
  }

  /**
   * The index {@code jv_set} writes for the number {@code key} in an array of {@code length}: cut toward zero, counted
   * from the end when negative, and jq's errors for NaN, for a place before the start and past {@code INT_MAX >> 2}.
   */
  private static int index(RunState state, int length, JqNumber key) {
    double d = Numbers.doubleValue(state, key);
    if (Double.isNaN(d)) {
      throw error("Cannot set array element at NaN index");
    }
    // The cast saturates, as jq's clamp to the int range before its own cast does.
    long at = (int) d;
    if (at < 0) {
      at += length;
    }
    if (at < 0) {
      throw error("Out of bounds negative array index");
    }
    if (at > Integer.MAX_VALUE >> 2) {
      throw error("Array index too large");
    }
    return (int) at;
  }

  /** {@code object}, or a new object for null, with {@code v} at {@code name}: one tick per entry of the result. */
  private static BuiltObject withKey(RunState state, JqObject object, JqString name, JqValue v) {
    Entries out = new Entries(state);
    boolean found = false;
    if (object != null) {
      Members entries = KeyProof.members(state, object);
      Keyed keys = (Keyed) entries;
      String wanted = name.value();
      for (int c = entries.first(); c != -1; c = entries.next(state, c)) {
        state.tick();
        JqString key = keys.key(c);
        boolean same = !found && key.value().equals(wanted);
        found |= same;
        out.add(key, same ? v : entries.member(state, c));
      }
    }
    if (!found) {
      state.tick();
      out.add(name, v);
    }
    return out.object();
  }

  /** {@code array} with {@code v} at {@code at}, null between its end and {@code at}: one tick per slot. */
  private static BuiltArray withElement(RunState state, JqArray array, int at, JqValue v) {
    int length = Slice.length(state, array);
    Object[] out = Built.elements(state, Math.max(length, at + 1L));
    state.tick(out.length);
    copy(state, (Members) array, out);
    Arrays.fill(out, length, out.length, JqNull.NULL);
    out[at] = v;
    return new BuiltArray(out, out.length);
  }

  /**
   * {@code array} with its slice of {@code bounds}, read as {@code .[k]} reads a slice ({@link Slice#bounds}), replaced
   * by the elements of the array {@code v}: one tick per slot of the result.
   */
  private static BuiltArray withSlice(RunState state, JqArray array, Keyed bounds, JqValue v) {
    JqValue from = bounds.get(state, "start");
    JqValue to = bounds.get(state, "end");
    if (!Slice.isBound(from) || !Slice.isBound(to)) {
      throw NavigationErrors.sliceIndices();
    }
    int length = Slice.length(state, array);
    long range = Slice.bounds(state, length, from, to);
    if (!(v instanceof JqArray inserted)) {
      throw error("A slice of an array can only be assigned another array");
    }
    int start = (int) (range >>> 32);
    int end = (int) range;
    int added = Slice.length(state, inserted);
    Object[] out = Built.elements(state, (long) length - (end - start) + added);
    state.tick(out.length);
    Members elements = (Members) array;
    int i = 0;
    for (int c = elements.first(); c != -1; c = elements.next(state, c), i++) {
      if (i < start) {
        out[i] = elements.member(state, c);
      } else if (i >= end) {
        out[i - end + start + added] = elements.member(state, c);
      }
    }
    Members insert = (Members) inserted;
    int at = start;
    for (int c = insert.first(); c != -1; c = insert.next(state, c)) {
      out[at++] = insert.member(state, c);
    }
    return new BuiltArray(out, out.length);
  }

  /**
   * jq's {@code jv_dels} on an array: {@code array} without the elements at {@code keys}, sorted. A number deletes its
   * element, NaN none, a negative one counted from the end, each cut toward zero; an object deletes its slice; any
   * other key is jq's error. One tick per element, kept or dropped.
   */
  private static BuiltArray withoutElements(RunState state, JqArray array, List<JqValue> keys) {
    int length = Slice.length(state, array);
    // Each key adds one at the first place it deletes and takes it back after the last.
    int[] cover = new int[length + 1];
    for (JqValue key : keys) {
      switch (key) {
        case JqNumber number -> {
          double d = Numbers.doubleValue(state, number);
          long at = d < 0 ? length + (long) (int) d : (int) d;
          if (!Double.isNaN(d) && at >= 0 && at < length) {
            cover[(int) at]++;
            cover[(int) at + 1]--;
          }
        }
        case JqObject object -> {
          Keyed bounds = (Keyed) object;
          JqValue from = bounds.get(state, "start");
          JqValue to = bounds.get(state, "end");
          if (!Slice.isBound(from) || !Slice.isBound(to)) {
            throw NavigationErrors.sliceIndices();
          }
          long range = Slice.bounds(state, length, from, to);
          cover[(int) (range >>> 32)]++;
          cover[(int) range]--;
        }
        default -> throw error("Cannot delete " + NavigationErrors.kind(key) + " element of array");
      }
    }
    Built.Elements kept = new Built.Elements(state);
    Members elements = (Members) array;
    int i = 0;
    int covered = 0;
    for (int c = elements.first(); c != -1; c = elements.next(state, c), i++) {
      state.tick();
      covered += cover[i];
      if (covered == 0) {
        kept.add(elements.member(state, c));
      }
    }
    return kept.array();
  }

  /**
   * jq's {@code jv_dels} on an object: {@code object} without the keys {@code keys}, each a string or jq's error. One
   * tick per entry, kept or dropped.
   */
  private static BuiltObject withoutKeys(RunState state, JqObject object, List<JqValue> keys) {
    Set<String> names = new HashSet<>();
    for (JqValue key : keys) {
      if (!(key instanceof JqString name)) {
        throw error("Cannot delete " + NavigationErrors.kind(key) + " field of object");
      }
      names.add(name.value());
    }
    Members entries = KeyProof.members(state, object);
    Keyed of = (Keyed) entries;
    Entries kept = new Entries(state);
    for (int c = entries.first(); c != -1; c = entries.next(state, c)) {
      state.tick();
      JqString key = of.key(c);
      if (!names.contains(key.value())) {
        kept.add(key, entries.member(state, c));
      }
    }
    return kept.object();
  }

  /** The elements of {@code array} into {@code out} from its start. */
  private static void copy(RunState state, Members array, Object[] out) {
    int i = 0;
    for (int c = array.first(); c != -1; c = array.next(state, c)) {
      out[i++] = array.member(state, c);
    }
  }

  private static BuiltArray empty() {
    return new BuiltArray(new Object[0], 0);
  }

  private static FilterException cannotUpdate(JqValue t, JqValue k) {
    return error("Cannot update field at " + NavigationErrors.kind(k) + " index of " + NavigationErrors.kind(t));
  }

  private static FilterException error(String text) {
    return new FilterException(BuiltString.of(text));
  }

  /**
   * One level of {@code delpaths_sorted}: its container, the paths {@code [next, to)} still to group, the keys it
   * deletes, in sorted order, and the key of the group the level below works on. The first write into an array element
   * or an object key copies the container into a scratch, which later writes change in place, so a level that writes
   * many keys copies its container once; any other write, a slice's or an error, is {@code jv_set}'s.
   */
  private static final class Level {
    final Level outer;

    /** The place of this level's keys in each path. */
    final int depth;

    final int to;

    int next;

    JqValue container;

    /** The elements of the array container once a write copied them, or null. */
    Object[] elements;

    /** The entries of the object container once a write copied them, or null, with the place of each key. */
    Entries entries;

    Map<String, Integer> places;

    final List<JqValue> deletes = new ArrayList<>();

    JqValue key;

    Level(Level outer, JqValue container, int from, int to) {
      this.outer = outer;
      this.depth = outer == null ? 0 : outer.depth + 1;
      this.container = container;
      this.next = from;
      this.to = to;
    }

    /** jq's {@code jv_get} of the container as written so far. */
    JqValue get(RunState state, JqValue k) {
      if (entries != null && k instanceof JqString name) {
        Integer at = places.get(name.value());
        return at == null ? JqNull.NULL : (JqValue) entries.values[at];
      }
      return Index.get(state, elements != null ? new BuiltArray(elements, elements.length) : container, k);
    }

    /** jq's {@code jv_set} of {@code v} at {@code k}, a key {@link #get} found a value for. */
    void set(RunState state, JqValue k, JqValue v) {
      if (k instanceof JqNumber number && container instanceof JqArray array) {
        if (elements == null) {
          elements = Built.elements(state, Slice.length(state, array));
          state.tick(elements.length);
          copy(state, (Members) array, elements);
        }
        elements[index(state, elements.length, number)] = v;
        return;
      }
      if (k instanceof JqString name && container instanceof JqObject object) {
        if (entries == null) {
          Members members = KeyProof.members(state, object);
          Keyed keys = (Keyed) members;
          entries = new Entries(state);
          places = new HashMap<>();
          for (int c = members.first(); c != -1; c = members.next(state, c)) {
            state.tick();
            JqString each = keys.key(c);
            places.put(each.value(), entries.size);
            entries.add(each, members.member(state, c));
          }
        }
        entries.values[places.get(name.value())] = v;
        return;
      }
      container = Paths.set(state, value(), k, v);
      elements = null;
      entries = null;
      places = null;
    }

    /** The container as written so far; the scratch is never written after. */
    JqValue value() {
      if (elements != null) {
        return new BuiltArray(elements, elements.length);
      }
      return entries != null ? entries.object() : container;
    }

    /** jq's {@code jv_dels} of this level's deletes from its container. */
    JqValue finish(RunState state) {
      JqValue t = value();
      if (deletes.isEmpty() || t instanceof JqNull) {
        return t;
      }
      return switch (t) {
        case JqArray array -> withoutElements(state, array, deletes);
        case JqObject object -> withoutKeys(state, object, deletes);
        default -> throw error("Cannot delete fields from " + NavigationErrors.kind(t));
      };
    }
  }

  /** An object's keys and values while it is built, charged as a container and then an entry per key. */
  private static final class Entries {
    private final RunState state;

    JqString[] keys = new JqString[ROOM];

    Object[] values = new Object[ROOM];

    int size;

    Entries(RunState state) {
      this.state = state;
      state.chargeBuilt(Charges.CONTAINER);
    }

    void add(JqString key, Object value) {
      state.chargeBuilt(Charges.ENTRY);
      if (size == keys.length) {
        int grown = Built.grown(state, size);
        keys = Arrays.copyOf(keys, grown);
        values = Arrays.copyOf(values, grown);
      }
      keys[size] = key;
      values[size++] = value;
    }

    /** The object of the entries so far, its keys distinct. */
    BuiltObject object() {
      return new BuiltObject(Arrays.copyOf(keys, size), Arrays.copyOf(values, size));
    }
  }
}
