package dev.rail8.jq.query.compile;

import dev.rail8.jq.query.ir.Facts;
import dev.rail8.jq.query.ir.Intrinsic;
import dev.rail8.jq.query.ir.Node;
import dev.rail8.jq.query.ir.Operation;
import dev.rail8.jq.query.ir.PathRule;
import dev.rail8.jq.query.ir.Source;
import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.Edit;
import dev.rail8.jq.query.run.FilterException;
import dev.rail8.jq.query.run.Fn0;
import dev.rail8.jq.query.run.Fn1;
import dev.rail8.jq.query.run.Fn2;
import dev.rail8.jq.query.run.Many;
import dev.rail8.jq.query.run.Path;
import dev.rail8.jq.query.run.PathFormat;
import dev.rail8.jq.query.run.PathStep;
import dev.rail8.jq.query.run.RunState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * A toy path format over Java values: a list is an array whose keys are its {@code Long} indexes, a map an object whose
 * keys are its {@code String} names, and every other value a scalar. A path's value is the list of its keys. Each edit
 * really edits a copy of its root and writes what it is asked into {@link #log}, which the tests read with the lines
 * their own operations write there.
 *
 * <p>Its steps, each an operation of the rule its name says: {@link #field} (KEY, an Fn0 that adds its own name),
 * {@link #index} (INDEX), {@link #dynamic} (DYNAMIC), {@link #slice} (SLICE, whose key the step builds and charges),
 * {@link #getpath} (KEY_LIST) and {@link #all} (ALL). A step on an untracked input raises "untracked" with the input,
 * and a step on a value it cannot read raises "cannot index" or "cannot iterate".
 */
final class ListPaths implements PathFormat {
  static final Source AT = new Source(0, 1);

  /** The bytes {@link #slice} charges for each key it builds. */
  static final long SLICE_KEY = 24;

  /** What the edits and the tests' own operations did, in order. */
  final List<String> log = new ArrayList<>();

  @Override
  public Object value(RunState s, Path p) {
    return keys(p);
  }

  @Override
  public FilterException invalid(RunState s, Object result) {
    return new FilterException("invalid path of " + result);
  }

  @Override
  public Edit modify(RunState s, Object root) {
    return new Recording(root);
  }

  @Override
  public Edit assign(RunState s, Object root) {
    return new Recording(root);
  }

  /** The keys of {@code p} from the root, or null for an untracked result. */
  static List<Object> keys(Path p) {
    if (p == null) {
      return null;
    }
    List<Object> keys = new ArrayList<>();
    for (Path at = p; at.length() > 0; at = at.parent()) {
      keys.add(at.key());
    }
    Collections.reverse(keys);
    return keys;
  }

  /** The value at {@code key} in {@code v}: null when it holds none there, or when {@code v} is null. */
  static Object at(Object v, Object key) {
    if (v == null) {
      return null;
    }
    if (v instanceof List<?> list && key instanceof Long index) {
      return index >= 0 && index < list.size() ? list.get(index.intValue()) : null;
    }
    if (v instanceof Map<?, ?> map && key instanceof String name) {
      return map.get(name);
    }
    throw new FilterException("cannot index " + v + " with " + key);
  }

  /** {@code v} with {@code value} at {@code keys} from {@code k} on, made as copies; a missing list or map is made. */
  static Object with(Object v, List<Object> keys, int k, Object value) {
    if (k == keys.size()) {
      return value;
    }
    Object key = keys.get(k);
    if (key instanceof Long index) {
      List<Object> list = v == null ? new ArrayList<>() : new ArrayList<>((List<?>) v);
      while (list.size() <= index) {
        list.add(null);
      }
      list.set(index.intValue(), with(list.get(index.intValue()), keys, k + 1, value));
      return Collections.unmodifiableList(list);
    }
    Map<String, Object> map = v == null ? new LinkedHashMap<>() : new LinkedHashMap<>(castMap(v));
    map.put((String) key, with(map.get((String) key), keys, k + 1, value));
    return Collections.unmodifiableMap(map);
  }

  /** {@code v} without the value at {@code keys}, made as copies; nothing changes when it holds none there. */
  static Object without(Object v, List<Object> keys, int k) {
    Object key = keys.get(k);
    if (v instanceof List<?> l && key instanceof Long index && index < l.size()) {
      List<Object> list = new ArrayList<>(l);
      if (k == keys.size() - 1) {
        list.remove(index.intValue());
      } else {
        list.set(index.intValue(), without(list.get(index.intValue()), keys, k + 1));
      }
      return Collections.unmodifiableList(list);
    }
    if (v instanceof Map<?, ?> m && m.containsKey(key)) {
      Map<String, Object> map = new LinkedHashMap<>(castMap(m));
      if (k == keys.size() - 1) {
        map.remove((String) key);
      } else {
        map.put((String) key, without(map.get((String) key), keys, k + 1));
      }
      return Collections.unmodifiableMap(map);
    }
    return v;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castMap(Object v) {
    return (Map<String, Object>) v;
  }

  /** A map of the pairs {@code name, value, ...}, in their order. */
  static Map<String, Object> object(Object... pairs) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int k = 0; k < pairs.length; k += 2) {
      map.put((String) pairs[k], pairs[k + 1]);
    }
    return Collections.unmodifiableMap(map);
  }

  /** The edit of one update run: it edits copies of its root and logs each call. */
  private final class Recording implements Edit {
    private Object root;
    private final List<List<Object>> deletes = new ArrayList<>();

    Recording(Object root) {
      this.root = root;
    }

    @Override
    public Object get(RunState s, Path p) {
      log.add("get " + keys(p));
      Object v = root;
      for (Object key : keys(p)) {
        v = at(v, key);
      }
      return v;
    }

    @Override
    public void set(RunState s, Path p, Object v) {
      log.add("set " + keys(p) + " " + v);
      root = with(root, keys(p), 0, v);
    }

    @Override
    public void delete(RunState s, Path p) {
      log.add("delete " + keys(p));
      deletes.add(keys(p));
    }

    /** The root with each deleted path removed, the last in key order first, so no index moves before its turn. */
    @Override
    public Object end(RunState s) {
      log.add("end");
      List<List<Object>> sorted = new ArrayList<>(deletes);
      sorted.sort(KEY_ORDER.reversed());
      Object v = root;
      for (List<Object> keys : sorted) {
        if (!keys.isEmpty()) {
          v = without(v, keys, 0);
        }
      }
      return v;
    }
  }

  /** Paths in key order: key by key, a shorter path first, an index before a name. */
  private static final Comparator<List<Object>> KEY_ORDER =
      (a, b) -> {
        for (int k = 0; k < Math.min(a.size(), b.size()); k++) {
          int c = compareKeys(a.get(k), b.get(k));
          if (c != 0) {
            return c;
          }
        }
        return Integer.compare(a.size(), b.size());
      };

  private static int compareKeys(Object a, Object b) {
    if (a instanceof Long x && b instanceof Long y) {
      return Long.compare(x, y);
    }
    if (a instanceof String x && b instanceof String y) {
      return x.compareTo(y);
    }
    return a instanceof Long ? -1 : 1;
  }

  /** The error of a step on the untracked input {@code in}. */
  private static FilterException untracked(Object in) {
    return new FilterException("untracked " + in);
  }

  /** {@code .name}: a KEY step whose key is its own. */
  static Node field(String name) {
    return new Intrinsic(AT, new Operation("." + name, new Field(name), new Facts(PathRule.KEY, true)), List.of());
  }

  /** A field read: an Fn0 that adds its name to the input's path. */
  private record Field(String name) implements Fn0, PathStep {
    @Override
    public Object apply(RunState state, Object in) {
      return at(in, name);
    }

    @Override
    public FilterException untracked(RunState s, Object in, Object key, Object end) {
      return ListPaths.untracked(in);
    }

    @Override
    public Path extend(RunState s, Path at, Object in, Object key, Object end) {
      return at.child(name);
    }
  }

  /** {@code .[key]}, {@code key} an atomic node: an INDEX step. */
  static Node index(Node key) {
    return new Intrinsic(AT, new Operation(".[]", new Index(), new Facts(PathRule.INDEX, true)), List.of(key));
  }

  /** {@code .[key]} as a DYNAMIC step, a key of either kind. */
  static Node dynamic(Node key) {
    return new Intrinsic(AT, new Operation(".[?]", new Index(), new Facts(PathRule.DYNAMIC, true)), List.of(key));
  }

  /** An index or a name read: an Fn1 whose argument is the key. */
  private record Index() implements Fn1, PathStep {
    @Override
    public Object apply(RunState state, Object in, Object key) {
      return at(in, key);
    }

    @Override
    public FilterException untracked(RunState s, Object in, Object key, Object end) {
      return ListPaths.untracked(in);
    }
  }

  /** {@code .[from:to]} of a list: a SLICE step whose key, {@code [from, to]}, it builds. */
  static Node slice(Node from, Node to) {
    return new Intrinsic(AT, new Operation(".[:]", new Slice(), new Facts(PathRule.SLICE, true)), List.of(from, to));
  }

  /** A slice: an Fn2 of the two bounds, which charges each key it builds. */
  private record Slice() implements Fn2, PathStep {
    @Override
    public Object apply(RunState state, Object in, Object from, Object to) {
      if (!(in instanceof List<?> list)) {
        throw new FilterException("cannot slice " + in);
      }
      int f = (int) Math.min(list.size(), (Long) from);
      int t = (int) Math.min(list.size(), Math.max(f, (Long) to));
      return List.copyOf(list.subList(f, t));
    }

    @Override
    public FilterException untracked(RunState s, Object in, Object key, Object end) {
      return ListPaths.untracked(in);
    }

    @Override
    public Path extend(RunState s, Path at, Object in, Object key, Object end) {
      s.chargeBuilt(SLICE_KEY);
      return at.child(List.of(key, end));
    }
  }

  /** {@code getpath(keys)}: a KEY_LIST step, untracked on an untracked input. */
  static Node getpath(Node keys) {
    return new Intrinsic(AT, new Operation("getpath", new GetPath(), new Facts(PathRule.KEY_LIST, true)), List.of(keys));
  }

  /** A read along a list of keys: an Fn1 that adds each key. */
  private record GetPath() implements Fn1, PathStep {
    @Override
    public Object apply(RunState state, Object in, Object keys) {
      Object v = in;
      for (Object key : (List<?>) keys) {
        v = at(v, key);
      }
      return v;
    }

    @Override
    public FilterException untracked(RunState s, Object in, Object key, Object end) {
      throw new AssertionError("getpath is never asked for the untracked error");
    }

    @Override
    public Path extend(RunState s, Path at, Object in, Object key, Object end) {
      Path p = at;
      for (Object k : (List<?>) key) {
        p = p.child(k);
      }
      return p;
    }
  }

  /** {@code .[]} of a list: an ALL step whose cursor is the index. */
  static Node all() {
    return new Intrinsic(AT, new Operation(".[]", new All(), new Facts(PathRule.ALL, true)), List.of());
  }

  /** Each value of a list, a Many that is a step. */
  private record All() implements Many, PathStep {
    @Override
    public long first(RunState state, Object in, Object[] args) {
      if (!(in instanceof List<?> list)) {
        throw new FilterException("cannot iterate " + in);
      }
      return list.isEmpty() ? Many.END : 0;
    }

    @Override
    public long next(RunState state, Object in, Object[] args, long cursor) {
      return cursor + 1 < ((List<?>) in).size() ? cursor + 1 : Many.END;
    }

    @Override
    public Object value(RunState state, Object in, Object[] args, long cursor) {
      return ((List<?>) in).get((int) cursor);
    }

    @Override
    public Object key(RunState state, Object in, Object[] args, long cursor) {
      return cursor;
    }

    @Override
    public FilterException untracked(RunState s, Object in, Object key, Object end) {
      return ListPaths.untracked(in);
    }
  }

  /** An Fn0 of rule {@code rule} that gives {@code fn} of its input. */
  static Node call(String name, PathRule rule, UnaryOperator<Object> fn) {
    return new Intrinsic(AT, new Operation(name, (Fn0) (state, in) -> fn.apply(in), new Facts(rule, true)), List.of());
  }

  /** An Fn0 that writes {@code line} into {@link #log}, then gives its input at its input's path, as a debug does. */
  Node debug(String line) {
    return new Intrinsic(
        AT,
        new Operation(
            "debug",
            (Fn0)
                (state, in) -> {
                  log.add(line + " " + in);
                  return in;
                },
            new Facts(PathRule.SAME, true)),
        List.of());
  }

  /** An Fn0 with no result. */
  static Node nothing() {
    return new Intrinsic(AT, new Operation("empty", (Fn0) (state, in) -> CallForm.NONE), List.of());
  }

  /** An Fn0 that raises {@code value}. */
  static Node raise(Object value) {
    return new Intrinsic(
        AT,
        new Operation(
            "error",
            (Fn0)
                (state, in) -> {
                  throw new FilterException(value);
                }),
        List.of());
  }
}
