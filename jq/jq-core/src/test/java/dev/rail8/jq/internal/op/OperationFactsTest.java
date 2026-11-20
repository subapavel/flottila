package dev.rail8.jq.internal.op;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.rail8.jq.JqEngine;
import dev.rail8.jq.JqException;
import dev.rail8.jq.internal.parse.Parser;
import dev.rail8.jq.internal.parse.Resolver;
import dev.rail8.jq.internal.value.ArrayView;
import dev.rail8.jq.internal.value.BuiltArray;
import dev.rail8.jq.internal.value.BuiltObject;
import dev.rail8.jq.internal.value.BuiltString;
import dev.rail8.jq.internal.value.Detach;
import dev.rail8.jq.internal.value.KeyProof;
import dev.rail8.jq.internal.value.Keyed;
import dev.rail8.jq.internal.value.Members;
import dev.rail8.jq.internal.value.ObjectView;
import dev.rail8.jq.internal.value.StringView;
import dev.rail8.jq.query.ir.Alternative;
import dev.rail8.jq.query.ir.Function;
import dev.rail8.jq.query.ir.Intrinsic;
import dev.rail8.jq.query.ir.Node;
import dev.rail8.jq.query.ir.Operation;
import dev.rail8.jq.query.ir.PathRule;
import dev.rail8.jq.query.ir.Program;
import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.FilterException;
import dev.rail8.jq.query.run.Fn0;
import dev.rail8.jq.query.run.Fn1;
import dev.rail8.jq.query.run.Fn2;
import dev.rail8.jq.query.run.FnN;
import dev.rail8.jq.query.run.Many;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.value.JqArray;
import dev.rail8.jq.value.JqObject;
import dev.rail8.jq.value.JqString;
import dev.rail8.jq.value.JqValue;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Each operation's {@code keepsArgs} fact: whether a result may share a container or string with an argument or with
 * the input, at any depth. The operations are those the resolver puts into the IR of {@link #CORPUS}, which holds
 * every operation of this package that is a constant, so a new one fails until the corpus calls it. The ones that keep:
 * the navigation steps and the operations that give their input or a part of it ({@code tostring}, {@code @text} and
 * {@code format} on a string, {@code last}, the loop state, the object-key check, the trims), the constructions and
 * the arithmetic that may give an operand or its members ({@code +}, {@code -}, {@code *}), the sort family and
 * {@code flatten}, {@code keys}, {@code keys_unsorted} and {@code to_entries}, and {@code getpath}, {@code setpath} and
 * {@code delpaths}. The rest build their result afresh or give no container and no string, which
 * {@link #keepsArgsIsSound} checks on random inputs and arguments. The path rule of each operation is pinned too, by
 * the filters whose IR holds it.
 */
class OperationFactsTest {
  /** A run of field steps long enough to be one fused step. */
  private static final String STEP_RUN = ".a".repeat(600);

  /** A fused run whose first step is an index with a constant number. */
  private static final String INDEX_RUN = ".[0]" + STEP_RUN;

  /** A fused run whose first step is a slice with constant bounds. */
  private static final String SLICE_RUN = ".[1:2]" + STEP_RUN;

  /** Filters whose IR holds every operation: each native by its name, each operator, step and construction. */
  private static final List<String> CORPUS =
      List.of(
          "empty", "error", "range(.;.)", "length", "type", "tostring", "tojson", "tonumber", "last(.)", "has(.)",
          "keys", "keys_unsorted", "to_entries", "flatten", "flatten(.)", "nan", "infinite", "isnan", "isinfinite",
          "isnormal", "have_decnum", "have_literal_numbers", "sort", "_sort_by_impl(.)", "_group_by_impl(.)",
          "unique", "_unique_by_impl(.)", "min", "max", "_min_by_impl(.)", "_max_by_impl(.)", "startswith(.)",
          "endswith(.)", "utf8bytelength", "trim", "ltrim", "rtrim", "explode", "implode", "split(.)",
          "_strindices(.)", "contains(.)", "bsearch(.)", "builtins", "fromjson", "toboolean", "format(.)",
          "_match_impl(.;.;.)", "strptime(.)", "strftime(.)", "strflocaltime(.)", "mktime", "gmtime", "localtime",
          "now", "getpath(.)", "setpath(.;.)", "delpaths(.)",
          "acos", "acosh", "asin", "asinh", "atan", "atanh", "cbrt", "ceil", "cos", "cosh", "erf", "erfc", "exp",
          "exp10", "exp2", "expm1", "fabs", "floor", "frexp", "log", "log10", "log1p", "log2", "logb", "modf",
          "nearbyint", "rint", "round", "significand", "sin", "sinh", "sqrt", "tan", "tanh", "trunc", "atan2(.;.)",
          "copysign(.;.)", "drem(.;.)", "fdim(.;.)", "fmax(.;.)", "fmin(.;.)", "fmod(.;.)", "hypot(.;.)",
          "ldexp(.;.)", "nextafter(.;.)", "nexttoward(.;.)", "pow(.;.)", "remainder(.;.)", "scalb(.;.)",
          "scalbln(.;.)", "fma(.;.;.)",
          ".a", ".a?", ".\"a\"", ".[.]", ".[.]?", ".[0]", ".[\"a\"]", ".[null]", ".[0]?", ".[\"a\"]?", ".[1:2]",
          ".[.:.]", ".[1:2]?", ".[.:.]?", ".[]", ".[]?", STEP_RUN, INDEX_RUN, SLICE_RUN,
          ". + .", ". - .", ". * .", ". / .", ". % .", "-.", ". == .", ". != .", ". < .", ". > .", ". <= .",
          ". >= .", "{a: .}", "{(.): .}", "\"\\(.)\"", "@text", "@json", "@csv", "@tsv", "@html", "@uri", "@urid",
          "@sh", "@base64", "@base64d", "@foo", "reduce . as $x (.; .)", "foreach . as $x (.; .)");

  /** Each operation of {@link #CORPUS}'s IR, with the number of arguments of its first Intrinsic. */
  private static Map<Operation, Integer> corpus() {
    Map<Operation, Integer> found = new IdentityHashMap<>();
    for (String filter : CORPUS) {
      collect(filter, found);
    }
    return found;
  }

  /** Adds each operation of {@code filter}'s IR to {@code found}, with the number of arguments of its first Intrinsic. */
  private static void collect(String filter, Map<Operation, Integer> found) {
    Program program = Resolver.resolve(Parser.parse(filter));
    ArrayDeque<Object> todo = new ArrayDeque<>();
    todo.push(program.main());
    program.functions().forEach(todo::push);
    while (!todo.isEmpty()) {
      Object next = todo.pop();
      if (next instanceof Intrinsic i) {
        found.putIfAbsent(i.op(), i.args().size());
      }
      for (RecordComponent c : next.getClass().getRecordComponents()) {
        Object part = read(c, next);
        if (part instanceof Node || part instanceof Alternative || part instanceof Function) {
          todo.push(part);
        } else if (part instanceof List<?> list) {
          for (Object e : list) {
            if (e instanceof Node || e instanceof Alternative || e instanceof Function) {
              todo.push(e);
            }
          }
        }
      }
    }
  }

  private static Object read(RecordComponent c, Object record) {
    try {
      return c.getAccessor().invoke(record);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  /** The name a pin keys an operation by: a field step and a fused run are named by their text, so by their kind here. */
  private static String pinned(Operation op) {
    return switch (op.form()) {
      case dev.rail8.jq.internal.op.Field _ -> ".name";
      case StepRun _ -> "step run";
      default -> op.name();
    };
  }

  @Test
  void everyOperationDeclaresWhetherItKeepsItsArguments() {
    Map<String, Boolean> declared = new TreeMap<>();
    for (Operation op : corpus().keySet()) {
      Boolean before = declared.put(pinned(op), op.facts().keepsArgs());
      assertThat(before == null || before == op.facts().keepsArgs()).as("both operations named %s declare the same keepsArgs", pinned(op)).isTrue();
    }
    Map<String, Boolean> expected = new TreeMap<>();
    for (String keeps :
        new String[] {
          ".name", "step run", ".[k]", ".[k]?", ".[a:b]", ".[a:b]?", ".[]", ".[]?", "tostring", "@text", "format",
          "last", "_state", "object key", "trim", "ltrim", "rtrim", "{...}", "_plus", "_minus", "_multiply", "sort",
          "_sort_by_impl", "_group_by_impl", "unique", "_unique_by_impl", "min", "max", "_min_by_impl", "_max_by_impl",
          "flatten", "keys", "keys_unsorted", "to_entries", "getpath", "setpath", "delpaths"
        }) {
      expected.put(keeps, true);
    }
    for (String fresh :
        new String[] {
          "empty", "error", "range", "length", "type", "tojson", "tonumber", "has", "nan", "infinite", "isnan",
          "isinfinite", "isnormal", "have_decnum", "have_literal_numbers", "startswith", "endswith", "utf8bytelength",
          "explode", "implode", "split", "_strindices", "contains", "bsearch", "builtins", "fromjson", "toboolean",
          "_match_impl", "strptime", "strftime", "strflocaltime", "mktime", "gmtime", "localtime", "now", "_divide",
          "_mod", "_negate", "_equal", "_notequal", "_less", "_greater", "_lesseq", "_greatereq", "interpolation",
          "@json", "@csv", "@tsv", "@html", "@uri", "@urid", "@sh", "@base64", "@base64d", "@foo",
          "acos", "acosh", "asin", "asinh", "atan", "atan2", "atanh", "cbrt", "ceil", "copysign", "cos", "cosh",
          "drem", "erf", "erfc", "exp", "exp10", "exp2", "expm1", "fabs", "fdim", "floor", "fma", "fmax", "fmin",
          "fmod", "frexp", "hypot", "ldexp", "log", "log10", "log1p", "log2", "logb", "modf", "nearbyint",
          "nextafter", "nexttoward", "pow", "remainder", "rint", "round", "scalb", "scalbln", "significand", "sin",
          "sinh", "sqrt", "tan", "tanh", "trunc"
        }) {
      expected.put(fresh, false);
    }
    assertThat(declared).as("keepsArgs of each operation").isEqualTo(expected);
  }

  /**
   * The path rule each operation declares, read per filter of the corpus: the rules other than NONE of the operations
   * in that filter's IR. A field step is KEY; an index is KEY for a constant string key, INDEX for a constant number
   * and DYNAMIC for any other key; a slice is SLICE and an iteration ALL; a fused run declares its first step's rule;
   * {@code getpath} is KEY_LIST. Every other filter's operations are all NONE, so it is not in the map.
   */
  @Test
  void everyOperationDeclaresItsPathRule() {
    Map<String, Set<PathRule>> declared = new TreeMap<>();
    for (String filter : CORPUS) {
      Map<Operation, Integer> found = new IdentityHashMap<>();
      collect(filter, found);
      Set<PathRule> rules = EnumSet.noneOf(PathRule.class);
      found.keySet().forEach(op -> rules.add(op.facts().path()));
      rules.remove(PathRule.NONE);
      if (!rules.isEmpty()) {
        declared.put(filter, rules);
      }
    }
    Map<String, Set<PathRule>> expected = new TreeMap<>();
    for (String key : new String[] {".a", ".a?", ".\"a\"", ".[\"a\"]", ".[\"a\"]?", STEP_RUN}) {
      expected.put(key, Set.of(PathRule.KEY));
    }
    for (String index : new String[] {".[0]", ".[0]?", INDEX_RUN}) {
      expected.put(index, Set.of(PathRule.INDEX));
    }
    for (String dynamic : new String[] {".[.]", ".[.]?", ".[null]"}) {
      expected.put(dynamic, Set.of(PathRule.DYNAMIC));
    }
    for (String slice : new String[] {".[1:2]", ".[.:.]", ".[1:2]?", ".[.:.]?", SLICE_RUN}) {
      expected.put(slice, Set.of(PathRule.SLICE));
    }
    expected.put(".[]", Set.of(PathRule.ALL));
    expected.put(".[]?", Set.of(PathRule.ALL));
    expected.put("getpath(.)", Set.of(PathRule.KEY_LIST));
    assertThat(declared).as("the path rules other than NONE of the operations of each filter").isEqualTo(expected);
  }

  @Test
  void theCorpusHoldsEveryConstantOperation() throws Exception {
    Set<Operation> found = Collections.newSetFromMap(new IdentityHashMap<>());
    found.addAll(corpus().keySet());
    List<String> missing = new ArrayList<>();
    for (Operation op : constants()) {
      if (!found.contains(op)) {
        missing.add(op.name());
      }
    }
    assertThat(missing).as("the constant operations no filter of the corpus reaches").isEmpty();
  }

  /** Every operation a class of this package holds in a static field, alone or among a map's values. */
  private static List<Operation> constants() throws Exception {
    Path classes = Path.of(Iterate.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    Path dir = classes.resolve(Iterate.class.getPackageName().replace('.', '/'));
    List<Operation> ops = new ArrayList<>();
    List<Path> files;
    try (Stream<Path> listed = Files.list(dir)) {
      files = listed.filter(p -> p.toString().endsWith(".class")).sorted().toList();
    }
    for (Path file : files) {
      String simple = file.getFileName().toString().replace(".class", "");
      Class<?> type = Class.forName(Iterate.class.getPackageName() + "." + simple);
      for (Field field : type.getDeclaredFields()) {
        if (!Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
          continue;
        }
        field.setAccessible(true);
        Object value = field.get(null);
        if (value instanceof Operation op) {
          ops.add(op);
        } else if (value instanceof Map<?, ?> map) {
          map.values().stream().filter(Operation.class::isInstance).map(Operation.class::cast).forEach(ops::add);
        }
      }
    }
    return ops;
  }

  @Test
  void keepsArgsIsSound() {
    List<String> violations = new ArrayList<>();
    for (Map.Entry<Operation, Integer> e : corpus().entrySet()) {
      if (!e.getKey().facts().keepsArgs()) {
        violations.addAll(shared(e.getKey(), e.getValue()));
      }
    }
    assertThat(violations).as("results of operations that declare keepsArgs false, sharing storage with an argument or the input").isEmpty();
  }

  @Test
  void theSoundnessCheckFindsWhatAnOperationKeeps() {
    // Witnesses: tostring gives a string input back, + gives one operand when the other is null, and getpath a part of
    // its input.
    assertThat(shared(Basics.TOSTRING, 0)).as("what tostring keeps").isNotEmpty();
    assertThat(shared(Arithmetic.PLUS, 2)).as("what + keeps").isNotEmpty();
    assertThat(shared(Paths.GETPATH, 1)).as("what getpath keeps").isNotEmpty();
  }

  /**
   * The results of {@code op}, called with {@code argc} arguments on random inputs, that share storage with an argument
   * or the input, each described, at most 20.
   */
  private static List<String> shared(Operation op, int argc) {
    List<String> violations = new ArrayList<>();
    for (long seed = 0; seed < 2000 && violations.size() < 20; seed++) {
      SplittableRandom r = new SplittableRandom(seed * 31 + op.name().hashCode());
      JqValue in = value(r);
      Object[] args = new Object[argc];
      for (int i = 0; i < args.length; i++) {
        args[i] = argument(r, in);
      }
      RunState state = new RunState();
      Kept.of(state).begin(Long.MAX_VALUE, InstantSource.fixed(Instant.ofEpochSecond(1_700_000_000L)), ZoneOffset.UTC);
      List<Object> results;
      try {
        results = results(state, op.form(), in, args);
      } catch (FilterException | JqException noResult) {
        continue;
      }
      Storage held = new Storage();
      held.add(in);
      for (Object a : args) {
        held.add((JqValue) a);
      }
      for (Object result : results) {
        String shared = held.shared((JqValue) result);
        if (shared != null) {
          violations.add(op.name() + " on " + in.toJson() + " with " + List.of(args) + " gives " + ((JqValue) result).toJson() + ", which shares " + shared);
        }
      }
    }
    return violations;
  }

  /** The results of {@code form} on {@code in} and {@code args}, at most 100 of a Many. */
  private static List<Object> results(RunState state, CallForm form, JqValue in, Object[] args) {
    Object one =
        switch (form) {
          case Fn0 f -> f.apply(state, in);
          case Fn1 f -> f.apply(state, in, args[0]);
          case Fn2 f -> f.apply(state, in, args[0], args[1]);
          case FnN f -> f.apply(state, in, args);
          case Many m -> {
            Object[] slots = new Object[args.length + m.loopSlots()];
            System.arraycopy(args, 0, slots, 0, args.length);
            List<Object> all = new ArrayList<>();
            for (long c = m.first(state, in, slots); c != Many.END && all.size() < 100; c = m.next(state, in, slots, c)) {
              all.add(m.value(state, in, slots, c));
            }
            yield all;
          }
        };
    if (form instanceof Many) {
      @SuppressWarnings("unchecked")
      List<Object> all = (List<Object>) one;
      return all;
    }
    return one == CallForm.NONE ? List.of() : List.of(one);
  }

  /** Strings an operation reads as more than text: formats, separators, numbers, JSON, patterns. */
  private static final String[] STRINGS = {
    "", "a", "abc", " a ", "a,b", ",", "%Y-%m-%dT%H:%M:%SZ", "2015-03-05T23:51:47Z", "1", "1.5", "true", "[1,\\\"a\\\"]",
    "{\\\"a\\\":[\\\"b\\\"]}", "\\\"s\\\"", "a\\u00e9", "\\ud834\\udd1e", "x\\ny", "g", "x", "<&>"
  };

  /** A random value, read from a document of its own (views) or copied out of one (built values). */
  private static JqValue value(SplittableRandom r) {
    String text = json(r, 3);
    JqValue view;
    try {
      view = JqEngine.builder().build().newWorkspace().read(MemorySegment.ofArray(text.getBytes(UTF_8))).root();
    } catch (RuntimeException e) {
      throw new AssertionError(text, e);
    }
    return r.nextInt(3) == 0 ? Detach.of(view) : view;
  }

  /** An argument: the input, one of its members, or a value of its own. */
  private static Object argument(SplittableRandom r, JqValue in) {
    return switch (r.nextInt(4)) {
      case 0 -> in;
      case 1 -> {
        List<JqValue> members = new ArrayList<>();
        Storage.members(in, members);
        yield members.isEmpty() ? in : members.get(r.nextInt(members.size()));
      }
      default -> value(r);
    };
  }

  private static String json(SplittableRandom r, int depth) {
    int kind = r.nextInt(depth == 0 ? 5 : 7);
    return switch (kind) {
      case 0 -> "null";
      case 1 -> r.nextBoolean() ? "true" : "false";
      case 2 -> List.of("0", "1", "-2", "1.5", "100000000000000000001", "1e3", "1700000000").get(r.nextInt(7));
      case 3, 4 -> "\"" + STRINGS[r.nextInt(STRINGS.length)] + "\"";
      case 5 -> {
        StringBuilder a = new StringBuilder("[");
        int n = r.nextInt(4);
        for (int i = 0; i < n; i++) {
          a.append(i == 0 ? "" : ",").append(json(r, depth - 1));
        }
        yield a.append(']').toString();
      }
      default -> {
        StringBuilder o = new StringBuilder("{");
        int n = r.nextInt(4);
        for (int i = 0; i < n; i++) {
          o.append(i == 0 ? "" : ",").append('"').append(STRINGS[r.nextInt(4)]).append("\":").append(json(r, depth - 1));
        }
        yield o.append('}').toString();
      }
    };
  }

  /**
   * The storage of values: a document's container or string by its document and tape index (the view record), a built
   * one by its identity, and a string's bytes by the identity of the array that holds them.
   */
  private static final class Storage {
    private final Set<Object> views = new HashSet<>();
    private final Set<Object> built = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Object> bytes = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Adds the storage of {@code v} and of every value in it. */
    void add(JqValue v) {
      walk(v, part -> {
        if (part instanceof ArrayView || part instanceof ObjectView || part instanceof StringView) {
          views.add(part);
        } else if (part instanceof BuiltArray || part instanceof BuiltObject || part instanceof BuiltString) {
          built.add(part);
        }
        if (part instanceof JqString s) {
          s.utf8().heapBase().ifPresent(bytes::add);
        }
        return null;
      });
    }

    /** What of {@code v}, at any depth, is storage added here, or null. */
    String shared(JqValue v) {
      return walk(v, part -> {
        if (views.contains(part) || built.contains(part)) {
          return part.toJson();
        }
        if (part instanceof JqString s && s.utf8().heapBase().filter(bytes::contains).isPresent()) {
          return "the bytes of " + part.toJson();
        }
        return null;
      });
    }

    /** Applies {@code visit} to {@code v}, its members and an object's keys, depth first; its first non-null answer. */
    private static String walk(JqValue v, java.util.function.Function<JqValue, String> visit) {
      ArrayDeque<JqValue> todo = new ArrayDeque<>();
      todo.push(v);
      while (!todo.isEmpty()) {
        JqValue next = todo.pop();
        String answer = visit.apply(next);
        if (answer != null) {
          return answer;
        }
        List<JqValue> members = new ArrayList<>();
        members(next, members);
        members.forEach(todo::push);
      }
      return null;
    }

    /** The members of {@code v} and, for an object, its keys. */
    static void members(JqValue v, Collection<JqValue> out) {
      if (v instanceof JqArray || v instanceof JqObject) {
        RunState free = new RunState();
        Members members = v instanceof JqObject o ? KeyProof.members(free, o) : (Members) v;
        for (int c = members.first(); c != -1; c = members.next(free, c)) {
          out.add(members.member(free, c));
          if (members instanceof Keyed keyed) {
            out.add(keyed.key(c));
          }
        }
      }
    }
  }
}
