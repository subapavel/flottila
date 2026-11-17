package io.github.subapavel.jqj.oracle.gen;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.oracle.diff.CaseFormat;
import io.github.subapavel.jqj.oracle.diff.DiffCase;
import io.github.subapavel.jqj.oracle.gen.CoreV1.Node;
import io.github.subapavel.jqj.oracle.gen.CoreV1.Op;
import io.github.subapavel.jqj.oracle.gen.CoreV1.Place;
import io.github.subapavel.jqj.oracle.gen.CoreV1.Scope;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The core-v1 Generator over its whole seed range (#58's approved design). The walkers here read the tree with jq's
 * own scoping rules, written from the design and not from the generator's {@code Scope}.
 */
class CoreV1Test {

  /** Every test draws the range itself: PIT credits a static initializer to no test, so its mutants would live. */
  private final List<CoreV1.Generated> generated = all();

  private static List<CoreV1.Generated> all() {
    List<CoreV1.Generated> out = new ArrayList<>();
    for (long seed = CoreV1.FROM; seed < CoreV1.TO; seed++) out.add(CoreV1.generate(seed));
    return out;
  }

  /** The generated pieces of a seed: E alone, or the def body and E (form 5), or B and F (form 6). */
  private static List<Node> pieces(CoreV1.Generated g) {
    Node t = g.tree();
    return t.op() == Op.DEF2 || t.op() == Op.RECURSE ? t.kids() : List.of(t);
  }

  @Test
  void theSeedRangeIsTwoThousandOneHundred() {
    assertThat(CoreV1.FROM).isZero();
    assertThat(CoreV1.TO).isEqualTo(2100);
    assertThat(CoreV1.FAMILY).isEqualTo("gen/core-v1");
    assertThat(generated).hasSize(2100);
  }

  @Test
  void sameSeedSameBytes() {
    for (long seed = CoreV1.TO - 1; seed >= CoreV1.FROM; seed--) {
      CoreV1.Generated again = CoreV1.generate(seed);
      CoreV1.Generated first = generated.get((int) seed);
      assertThat(again.block()).as("seed %d", seed).isEqualTo(first.block());
      assertThat(again.stdin()).as("seed %d", seed).isEqualTo(first.stdin());
      assertThat(again.tree()).as("seed %d", seed).isEqualTo(first.tree());
    }
  }

  private static final Pattern FORM6 =
      Pattern.compile("def r\\(f; \\$k\\): if \\$k < 1 then (.+) else f \\| r\\(f; \\$k - 1\\) end; r\\((.+); ([0-4])\\)");

  @Test
  void formIsSeedMod7() {
    for (CoreV1.Generated g : generated) {
      DiffCase c = DiffCase.of("core-v1.cases", CaseFormat.parse("core-v1.cases", "gen/core-v1", g.block().getBytes(UTF_8)).getFirst());
      int form = (int) (g.seed() % 7);
      String filter = CoreV1.print(g.tree());
      assertThat(c.id()).isEqualTo("gen/core-v1/" + g.seed());
      assertThat(c.args()).as("seed %d", g.seed()).isEqualTo(form == 4 ? List.of(filter) : List.of("-c", filter));
      assertThat(c.stdin()).as("seed %d", g.seed()).isEqualTo(g.stdin());
      assertThat(c.files()).isEmpty();
      assertThat(c.env()).isEmpty();
      assertThat(c.deviation()).isNull();
      Node t = g.tree();
      switch (form) {
        case 5 -> {
          assertThat(t.op()).isEqualTo(Op.DEF2);
          assertThat(t.names()).containsExactly("g", "f", "$p");
          assertThat(filter).startsWith("def g(f; $p): ");
          assertThat(callsTo(t.kids().get(1), "g")).as("seed %d: E calls g", g.seed()).isPositive();
        }
        case 6 -> {
          assertThat(t.op()).isEqualTo(Op.RECURSE);
          Matcher m = FORM6.matcher(filter);
          assertThat(m.matches()).as("seed %d: %s", g.seed(), filter).isTrue();
          assertThat(m.group(3)).isEqualTo(t.names().getFirst());
        }
        default -> assertThat(t.op()).as("seed %d", g.seed()).isNotIn(Op.DEF2, Op.RECURSE);
      }
    }
  }

  /** Every K from 0 to 4 occurs in form 6, and every form has 300 seeds. */
  @Test
  void formsAreEvenAndKCoversItsRange() {
    int[] forms = new int[7];
    Set<String> ks = new TreeSet<>();
    for (CoreV1.Generated g : generated) {
      forms[(int) (g.seed() % 7)]++;
      if (g.tree().op() == Op.RECURSE) ks.add(g.tree().names().getFirst());
    }
    assertThat(forms).containsOnly(300);
    assertThat(ks).containsExactly("0", "1", "2", "3", "4");
  }

  private static int depth(Node n) {
    int d = 0;
    for (Node k : n.kids()) d = Math.max(d, depth(k));
    return d + 1;
  }

  @Test
  void depthAtMost4() {
    int deepest = 0;
    for (CoreV1.Generated g : generated) {
      for (Node p : pieces(g)) {
        assertThat(depth(p)).as("seed %d", g.seed()).isBetween(1, 4);
        deepest = Math.max(deepest, depth(p));
      }
    }
    assertThat(deepest).isEqualTo(4);
  }

  /** Each place keeps or drops the breakable labels by #58's decision, and every place is classified here. */
  @Test
  void eachPlaceOpensOrClosesBreak() {
    Scope s = Scope.TOP.var("$x").label("$out");
    Set<Place> closed = EnumSet.of(Place.TRY_BODY, Place.OPTIONAL_OPERAND, Place.ALT_DESTRUCT_BINDING, Place.DEF_BODY, Place.CALL_ARGUMENT, Place.ERROR_ARGUMENT);
    Set<Place> opened = EnumSet.of(Place.TRY_HANDLER, Place.ALTERNATIVE_LEFT, Place.SELECT_ARGUMENT, Place.FIRST_ARGUMENT, Place.LIMIT_ARGUMENT, Place.MAP_ARGUMENT);

    assertThat(EnumSet.complementOf(EnumSet.copyOf(closed))).isEqualTo(opened);
    for (Place p : closed) {
      assertThat(s.in(p).breakable()).as("%s", p).isEmpty();
      assertThat(s.in(p).vars()).as("%s keeps the variables", p).containsExactly("$x");
    }
    for (Place p : opened) assertThat(s.in(p)).as("%s", p).isEqualTo(s);
  }

  @Test
  void scopeAddsANameOnce() {
    Scope s = Scope.TOP.var("$x").var("$y").var("$x").label("$out").label("$out");

    assertThat(s.vars()).containsExactly("$x", "$y");
    assertThat(s.breakable()).containsExactly("$out");
  }

  /**
   * A {@code break $l} finds its label without crossing a {@code try} body, a {@code ?} operand, a {@code ?//}
   * binding, a def body, an argument of a user def or closure parameter, or the argument of {@code error} (#58's
   * decision): a label crossed by one of them is unusable below it. The try handler, the left side of {@code //} and
   * the arguments of select, first, limit and map do not cross, and breaks occur below them.
   */
  @Test
  void breakNeverCrossesCatch() {
    Map<String, Integer> opened = new TreeMap<>();
    int breaks = 0;
    for (CoreV1.Generated g : generated) breaks += breaksChecked(g.tree(), new ArrayDeque<>(), null, opened, g.seed());
    assertThat(breaks).as("breaks drawn").isGreaterThan(90);
    assertThat(opened.keySet()).as("breaks below an opened place").isSubsetOf("handler", "alternative", "select", "first", "limit", "map");
    assertThat(opened.values().stream().mapToInt(Integer::intValue).sum()).as("breaks below an opened place").isGreaterThanOrEqualTo(5);
  }

  /** A label in scope and whether a catching construct lies between it and here. */
  private record Label(String name, boolean crossed) {}

  /** The kids of {@code n} below which a label in scope is crossed. */
  private static boolean crosses(Node n, int kid) {
    return switch (n.op()) {
      case TRY, TRY_CATCH, OPTIONAL, DEF0, DEF1, DEF1V, DEF2, ERROR_OF -> kid == 0;
      case ALT_DESTRUCT, CALL, RECURSE -> true;
      default -> false;
    };
  }

  /** The opened place kid {@code kid} of {@code n} is, or null. */
  private static String opened(Node n, int kid) {
    return switch (n.op()) {
      case TRY_CATCH -> kid == 1 ? "handler" : null;
      case ALTERNATIVE -> kid == 0 ? "alternative" : null;
      case SELECT -> "select";
      case FIRST -> "first";
      case LIMIT -> "limit";
      case MAP -> kid == 1 ? "map" : null;
      default -> null;
    };
  }

  private static int breaksChecked(Node n, Deque<Label> labels, String place, Map<String, Integer> opened, long seed) {
    if (n.op() == Op.BREAK) {
      Label found = labels.stream().filter(l -> l.name().equals(n.names().getFirst())).findFirst().orElse(null);
      assertThat(found).as("seed %d: %s is bound", seed, n.names().getFirst()).isNotNull();
      assertThat(found.crossed()).as("seed %d: break %s crosses a closed place", seed, found.name()).isFalse();
      if (place != null) opened.merge(place, 1, Integer::sum);
      return 1;
    }
    Deque<Label> here = labels;
    if (n.op() == Op.LABEL) {
      here = new ArrayDeque<>(here);
      here.addFirst(new Label(n.names().getFirst(), false));
    }
    int count = 0;
    for (int i = 0; i < n.kids().size(); i++) {
      Deque<Label> below = here;
      if (crosses(n, i)) {
        below = new ArrayDeque<>();
        for (Label l : here) below.addLast(new Label(l.name(), true));
      }
      String p = opened(n, i);
      count += breaksChecked(n.kids().get(i), below, p != null ? p : place, opened, seed);
    }
    return count;
  }

  /** The generator-only bounds of #58's decision: labels in at least 250 seeds, breaks in at least 90. */
  @Test
  void labelsAndBreaksAreCommon() {
    int labels = 0;
    int breaks = 0;
    for (CoreV1.Generated g : generated) {
      if (count(g.tree(), Op.LABEL) > 0) labels++;
      if (count(g.tree(), Op.BREAK) > 0) breaks++;
    }
    assertThat(labels).as("seeds with a label").isGreaterThanOrEqualTo(250);
    assertThat(breaks).as("seeds with a break").isGreaterThanOrEqualTo(90);
  }

  /** No seed holds a {@code ?//} together with {@code first}, {@code limit} or {@code break} (#58's decision). */
  @Test
  void noAltDestructuringWithFirstLimitOrBreak() {
    int alts = 0;
    for (CoreV1.Generated g : generated) {
      if (count(g.tree(), Op.ALT_DESTRUCT) == 0) continue;
      alts++;
      int cuts = count(g.tree(), Op.FIRST) + count(g.tree(), Op.LIMIT) + count(g.tree(), Op.BREAK);
      assertThat(cuts).as("seed %d: %s", g.seed(), CoreV1.print(g.tree())).isZero();
    }
    assertThat(alts).isGreaterThanOrEqualTo(30);
  }

  /** A label body drawn while a multi-result place is left is {@code K, R}. */
  @Test
  void labelBodiesAreOftenKCommaR() {
    int commaBodies = 0;
    for (CoreV1.Generated g : generated) commaBodies += commaLabels(g.tree());
    assertThat(commaBodies).isGreaterThan(150);
  }

  private static int commaLabels(Node n) {
    int c = n.op() == Op.LABEL && n.kids().getFirst().op() == Op.COMMA ? 1 : 0;
    for (Node k : n.kids()) c += commaLabels(k);
    return c;
  }

  private static final Pattern STRING = Pattern.compile("\"([^\"\\\\]|\\\\.)*\"");

  private static final Set<String> EXCLUDED =
      Set.of(
          "recurse", "while", "until", "repeat", "walk", "flatten", "tostream", "sort", "sort_by", "unique", "unique_by", "min", "max",
          "min_by", "max_by", "$__loc__", "input", "inputs", "debug", "stderr", "env", "$ENV", "now", "halt", "halt_error", "getpath",
          "paths", "path");

  private static final Set<String> ALLOWED =
      Set.of(
          "def", "if", "then", "elif", "else", "end", "as", "reduce", "foreach", "try", "catch", "label", "break", "and", "or", "not",
          "empty", "length", "type", "tostring", "error", "select", "first", "limit", "map", "range", "true", "false", "null", "f", "g",
          "h", "r", "a", "b", "c", "$x", "$y", "$z", "$p", "$k", "$l", "$m");

  private static final Pattern WORD = Pattern.compile("[0-9][0-9.]*(e[0-9]+)?|\\$?[A-Za-z_][A-Za-z0-9_]*");

  /** A token scan outside string literals: only the closed name list, no {@code ..}, {@code @}, assignment or range/3. */
  @Test
  void noExcludedName() {
    for (CoreV1.Generated g : generated) {
      String filter = CoreV1.print(g.tree());
      String code = STRING.matcher(filter).replaceAll("\"\"");
      assertThat(code).as("seed %d: %s", g.seed(), filter).doesNotContain("..", "@", "\"\"\"");
      assertThat(Pattern.compile("(?<![=!<>])=(?!=)").matcher(code).find()).as("seed %d: assignment in %s", g.seed(), filter).isFalse();
      Matcher range = Pattern.compile("range\\(([^)]*)\\)").matcher(code);
      while (range.find()) assertThat(range.group(1)).as("seed %d: range/2 only", g.seed()).matches("-?[0-9]; -?[0-9]");
      Matcher w = WORD.matcher(code);
      while (w.find()) {
        String word = w.group();
        if (Character.isDigit(word.charAt(0))) continue;
        assertThat(EXCLUDED).as("seed %d: %s", g.seed(), filter).doesNotContain(word);
        assertThat(ALLOWED).as("seed %d: %s in %s", g.seed(), word, filter).contains(word);
      }
    }
  }

  /** The number of calls to {@code name} anywhere below {@code n}. */
  private static int callsTo(Node n, String name) {
    int c = n.op() == Op.CALL && n.names().getFirst().equals(name) ? 1 : 0;
    for (Node k : n.kids()) c += callsTo(k, name);
    return c;
  }

  /**
   * Only form 6 recurses: with jq's rule (a def is in scope inside its own body, then in its rest; a parameter inside the
   * body), no call resolves to a def whose body encloses it, and only form 6 has the template.
   */
  @Test
  void onlyForm6Recurses() {
    for (CoreV1.Generated g : generated) {
      Node t = g.tree();
      assertThat(t.op() == Op.RECURSE).as("seed %d", g.seed()).isEqualTo(g.seed() % 7 == 6);
      for (Node p : pieces(g)) assertThat(count(p, Op.RECURSE) + count(p, Op.DEF2)).as("seed %d", g.seed()).isZero();
      Deque<Binding> top = new ArrayDeque<>();
      if (t.op() == Op.RECURSE) {
        top.push(new Binding("f", 0, false));
        top.push(new Binding("r", 2, true));
        assertThat(recursions(t.kids().getFirst(), top)).as("seed %d: B", g.seed()).isZero();
        assertThat(recursions(t.kids().get(1), new ArrayDeque<>(List.of(new Binding("r", 2, false))))).as("seed %d: F", g.seed()).isZero();
      } else {
        assertThat(recursions(t, top)).as("seed %d: %s", g.seed(), CoreV1.print(t)).isZero();
      }
    }
  }

  /** A def or parameter in scope; {@code open} while the walk is inside its own body. */
  private record Binding(String name, int arity, boolean open) {}

  private static int recursions(Node n, Deque<Binding> scope) {
    if (n.op() == Op.CALL) {
      String name = n.names().getFirst();
      int arity = n.kids().size();
      Binding b = scope.stream().filter(x -> x.name().equals(name) && x.arity() == arity).findFirst().orElse(null);
      int r = b != null && b.open() ? 1 : 0;
      for (Node k : n.kids()) r += recursions(k, scope);
      return r;
    }
    if (isDef(n.op())) {
      String name = n.names().getFirst();
      int arity = n.names().size() - 1;
      Deque<Binding> body = new ArrayDeque<>(scope);
      body.push(new Binding(name, arity, true));
      for (String p : n.names().subList(1, n.names().size())) if (!p.startsWith("$")) body.push(new Binding(p, 0, false));
      Deque<Binding> rest = new ArrayDeque<>(scope);
      rest.push(new Binding(name, arity, false));
      return recursions(n.kids().get(0), body) + recursions(n.kids().get(1), rest);
    }
    int r = 0;
    for (Node k : n.kids()) r += recursions(k, scope);
    return r;
  }

  private static boolean isDef(Op op) {
    return op == Op.DEF0 || op == Op.DEF1 || op == Op.DEF1V || op == Op.DEF2;
  }

  private static int count(Node n, Op op) {
    int c = n.op() == op ? 1 : 0;
    for (Node k : n.kids()) c += count(k, op);
    return c;
  }

  /** What a walk has in scope: variables, labels and defs with their arities (jq resolves a call by name and arity). */
  private record Env(Set<String> vars, Set<String> labels, Set<String> fns) {

    Env var(String... vs) {
      Set<String> v = new HashSet<>(vars);
      v.addAll(List.of(vs));
      return new Env(v, labels, fns);
    }

    Env label(String l) {
      Set<String> s = new HashSet<>(labels);
      s.add(l);
      return new Env(vars, s, fns);
    }

    Env fn(String name, int arity) {
      Set<String> s = new HashSet<>(fns);
      s.add(name + "/" + arity);
      return new Env(vars, labels, s);
    }

    /** A def body sees the variables and defs around it, its parameters and itself, but no label. */
    Env body(Node def) {
      Env e = new Env(vars, Set.of(), fns).fn(def.names().getFirst(), def.names().size() - 1);
      for (String p : def.names().subList(1, def.names().size())) e = p.startsWith("$") ? e.var(p) : e.fn(p, 0);
      return e;
    }
  }

  private static final Env EMPTY_ENV = new Env(Set.of(), Set.of(), Set.of());

  @Test
  void namesBoundAritiesRight() {
    for (CoreV1.Generated g : generated) {
      Node t = g.tree();
      if (t.op() == Op.RECURSE) {
        Env r = EMPTY_ENV.fn("r", 2);
        bound(t.kids().getFirst(), r.fn("f", 0).var("$k"), g.seed());
        bound(t.kids().get(1), r, g.seed());
      } else {
        bound(t, EMPTY_ENV, g.seed());
      }
    }
  }

  private static void bound(Node n, Env env, long seed) {
    String what = "seed " + seed + ": " + n.op() + " " + n.names();
    switch (n.op()) {
      case VAR -> assertThat(env.vars()).as(what).contains(n.names().getFirst());
      case BREAK -> assertThat(env.labels()).as(what).contains(n.names().getFirst());
      case CALL -> assertThat(env.fns()).as(what).contains(n.names().getFirst() + "/" + n.kids().size());
      default -> {}
    }
    List<Node> k = n.kids();
    switch (n.op()) {
      case AS, AS_ARRAY -> {
        bound(k.get(0), env, seed);
        bound(k.get(1), env.var(n.names().toArray(String[]::new)), seed);
      }
      case AS_OBJECT, ALT_DESTRUCT -> {
        bound(k.get(0), env, seed);
        bound(k.get(1), env.var(odd(n.names())), seed);
      }
      case REDUCE, FOREACH, FOREACH3 -> {
        bound(k.get(0), env, seed);
        bound(k.get(1), env, seed);
        for (Node x : k.subList(2, k.size())) bound(x, env.var(n.names().getFirst()), seed);
      }
      case LABEL -> bound(k.getFirst(), env.label(n.names().getFirst()), seed);
      case DEF0, DEF1, DEF1V, DEF2 -> {
        bound(k.get(0), env.body(n), seed);
        bound(k.get(1), env.fn(n.names().getFirst(), n.names().size() - 1), seed);
      }
      default -> {
        for (Node x : k) bound(x, env, seed);
      }
    }
    assertThat(arity(n)).as(what).isTrue();
  }

  /** The variables of a key/variable pair list. */
  private static String[] odd(List<String> pairs) {
    List<String> out = new ArrayList<>();
    for (int i = 1; i < pairs.size(); i += 2) out.add(pairs.get(i));
    return out.toArray(String[]::new);
  }

  /** The kid and name counts each production must have. */
  private static boolean arity(Node n) {
    int k = n.kids().size();
    int m = n.names().size();
    return switch (n.op()) {
      case DOT, EMPTY, LENGTH, TYPE, TOSTRING, NOT, ERROR -> k == 0 && m == 0;
      case PATH, VAR, LITERAL, BREAK -> k == 0 && m == 1;
      case RANGE -> k == 0 && m == 2;
      case CALL -> m == 1 && k <= 2;
      case PIPE, COMMA, ADD, SUB, MUL, DIV, MOD, EQ, NE, LT, LE, GT, GE, AND, OR, ALTERNATIVE, TRY_CATCH, MAP, OBJECT_COMPUTED -> k == 2 && m == 0;
      case TRY, OPTIONAL, SELECT, FIRST, ERROR_OF, LENGTH_OF, TOSTRING_OF, TYPE_OF, NOT_OF, COLLECT -> k == 1 && m == 0;
      case LIMIT, REPEAT, LABEL -> k == 1 && m == 1;
      case AS -> k == 2 && m == 1;
      case AS_ARRAY -> k == 2 && m >= 1 && m <= 2;
      case AS_OBJECT, ALT_DESTRUCT -> k == 2 && (m == 2 || m == 4);
      case IF -> k == 3 && m == 0;
      case IF_ELIF -> k == 5 && m == 0;
      case IF_NO_ELSE -> k == 2 && m == 0;
      case REDUCE, FOREACH -> k == 3 && m == 1;
      case FOREACH3 -> k == 4 && m == 1;
      case OBJECT -> k >= 1 && k <= 2 && m == k;
      case DEF0 -> k == 2 && m == 1;
      case DEF1, DEF1V -> k == 2 && m == 2;
      case DEF2, RECURSE -> k == 2 && m == (n.op() == Op.DEF2 ? 3 : 1);
    };
  }

  /** What jq folds at compile time: a literal, or an arithmetic or comparison operator over constants (jq-ref 1.8.2). */
  private static boolean folds(Node n) {
    Set<Op> folding = EnumSet.of(Op.ADD, Op.SUB, Op.MUL, Op.DIV, Op.MOD, Op.EQ, Op.NE, Op.LT, Op.LE, Op.GT, Op.GE, Op.REPEAT);
    return n.op() == Op.LITERAL || (folding.contains(n.op()) && n.kids().stream().allMatch(CoreV1Test::folds));
  }

  private static int computedKeys(Node n, long seed) {
    int c = 0;
    if (n.op() == Op.OBJECT_COMPUTED) {
      c++;
      Node key = n.kids().getFirst();
      if (folds(key)) assertThat(key.op() == Op.LITERAL && key.names().getFirst().startsWith("\"")).as("seed %d: constant key %s", seed, CoreV1.print(key)).isTrue();
    }
    for (Node k : n.kids()) c += computedKeys(k, seed);
    return c;
  }

  /** jq-ref refuses a constant object key that is no string at compile time (rc 3): a constant key is a string literal. */
  @Test
  void computedKeysNeverFoldToANonString() {
    int keys = 0;
    for (CoreV1.Generated g : generated) keys += computedKeys(g.tree(), g.seed());
    assertThat(keys).isGreaterThan(30);
  }

  private static int literalDivisions(Node n, long seed) {
    int c = 0;
    if ((n.op() == Op.DIV || n.op() == Op.MOD) && n.kids().get(0).op() == Op.LITERAL && n.kids().get(1).op() == Op.LITERAL) {
      c++;
      Set<String> zero = n.op() == Op.DIV ? Set.of("0", "-0") : Set.of("0", "-0", "0.5");
      assertThat(zero).as("seed %d: %s", seed, CoreV1.print(n)).doesNotContain(n.kids().get(1).names().getFirst());
    }
    for (Node k : n.kids()) c += literalDivisions(k, seed);
    return c;
  }

  /** No literal is divided (or taken modulo) by a literal zero; {@code %} truncates 0.5 to zero. */
  @Test
  void noLiteralDivisionByZero() {
    int divisions = 0;
    for (CoreV1.Generated g : generated) divisions += literalDivisions(g.tree(), g.seed());
    assertThat(divisions).isGreaterThan(10);
  }

  /** The places that can give several results: a comma, {@code range/2} and {@code .[]} (with or without {@code ?}). */
  private static int sites(Node n) {
    int s = n.op() == Op.COMMA || n.op() == Op.RANGE || (n.op() == Op.PATH && n.names().getFirst().contains("[]")) ? 1 : 0;
    for (Node k : n.kids()) s += sites(k);
    return s;
  }

  @Test
  void sitesAtMost3() {
    int most = 0;
    for (CoreV1.Generated g : generated) {
      int s = sites(g.tree());
      assertThat(s).as("seed %d: %s", g.seed(), CoreV1.print(g.tree())).isLessThanOrEqualTo(3);
      most = Math.max(most, s);
      if (g.tree().op() == Op.RECURSE) assertThat(sites(g.tree().kids().get(1))).as("seed %d: F", g.seed()).isZero();
    }
    assertThat(most).isEqualTo(3);
  }

  @Test
  void documentsStrictSmall() {
    int[] tops = new int[3];
    int lf = 0;
    for (CoreV1.Generated g : generated) {
      byte[] d = g.stdin();
      assertThat(d.length).as("seed %d", g.seed()).isBetween(1, 512);
      Scan s;
      try {
        s = Scan.of(d);
      } catch (AssertionError e) {
        throw new AssertionError("seed " + g.seed() + ": " + e.getMessage() + " in " + CaseFormat.escape(d), e);
      }
      assertThat(s.tokens).as("seed %d", g.seed()).noneMatch(Scan::lenient);
      assertThat(s.maxDepth).as("seed %d", g.seed()).isLessThanOrEqualTo(3);
      for (List<String> keys : s.objectKeys) {
        assertThat(keys).as("seed %d", g.seed()).hasSizeLessThanOrEqualTo(4).doesNotHaveDuplicates();
      }
      String text = new String(d, UTF_8);
      String body = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
      if (body.length() < text.length()) lf++;
      assertThat(STRING.matcher(body).replaceAll("\"\"")).as("seed %d: compact", g.seed()).doesNotContain(" ", "\n", "\t", "\r");
      tops[body.startsWith("{") ? 0 : body.startsWith("[") ? 1 : 2]++;
    }
    assertThat(tops[0]).as("objects, 50 %").isBetween(950, 1150);
    assertThat(tops[1]).as("arrays, 30 %").isBetween(530, 730);
    assertThat(tops[2]).as("scalars, 20 %").isBetween(320, 520);
    assertThat(lf).as("trailing LF, 70 %").isBetween(1370, 1570);
  }

  @Test
  void everyProductionOccurs() {
    Map<Op, Integer> seeds = new EnumMap<>(Op.class);
    for (Op op : Op.values()) seeds.put(op, 0);
    for (CoreV1.Generated g : generated) {
      Set<Op> ops = EnumSet.noneOf(Op.class);
      collect(g.tree(), ops);
      for (Op op : ops) seeds.merge(op, 1, Integer::sum);
    }
    Map<Op, Integer> rare = new TreeMap<>();
    seeds.forEach((op, n) -> {
      if (n < 30) rare.put(op, n);
    });
    assertThat(rare).as("productions in fewer than 30 seeds").isEmpty();
  }

  private static void collect(Node n, Set<Op> ops) {
    ops.add(n.op());
    for (Node k : n.kids()) collect(k, ops);
  }

  /** The printer parenthesises every compound operand: a sample of filters, each checked against its tree. */
  @Test
  void printerParenthesisesCompoundOperands() {
    Node one = new Node(Op.LITERAL, List.of("1"), List.of());
    Node minus = new Node(Op.LITERAL, List.of("-1"), List.of());
    Node dot = new Node(Op.DOT, List.of(), List.of());
    Node pipe = new Node(Op.PIPE, List.of(), List.of(dot, one));
    assertThat(CoreV1.print(new Node(Op.ADD, List.of(), List.of(pipe, minus)))).isEqualTo("(. | 1) + (-1)");
    assertThat(CoreV1.print(new Node(Op.AS, List.of("$x"), List.of(pipe, pipe)))).isEqualTo("(. | 1) as $x | (. | 1)");
    assertThat(CoreV1.print(new Node(Op.TRY_CATCH, List.of(), List.of(pipe, one)))).isEqualTo("try (. | 1) catch 1");
    assertThat(CoreV1.print(new Node(Op.REDUCE, List.of("$y"), List.of(pipe, one, pipe)))).isEqualTo("reduce (. | 1) as $y (1; . | 1)");
    assertThat(CoreV1.print(new Node(Op.OBJECT, List.of("\"x y\"", "a"), List.of(pipe, minus)))).isEqualTo("{\"x y\": (. | 1), a: (-1)}");
    assertThat(CoreV1.print(new Node(Op.CALL, List.of("g"), List.of(pipe, one)))).isEqualTo("g(. | 1; 1)");
    assertThat(CoreV1.print(new Node(Op.MAP, List.of(), List.of(pipe, pipe)))).isEqualTo("(. | 1) | map(. | 1)");
  }

  @Test
  void fileListsDroppedSeedsThenTheOtherBlocks() {
    String file = CoreV1.file(0, 3, new TreeMap<>(Map.of(1L, "rc3")));
    assertThat(file).startsWith("# Generated cases of core-v1").contains("\n# dropped 1 rc3\n");
    assertThat(file).endsWith("\n" + CoreV1.generate(0).block() + "\n" + CoreV1.generate(2).block());
    assertThat(CaseFormat.parse("core-v1.cases", CoreV1.FAMILY, file.getBytes(UTF_8))).extracting(CaseFormat.Block::id).containsExactly("gen/core-v1/0", "gen/core-v1/2");
  }

  /** A filter with backslashes and newlines survives the case format: the filter line is escaped. */
  @Test
  void theFilterLineIsEscaped() {
    CoreV1.Generated g = generated.stream().filter(x -> CoreV1.print(x.tree()).contains("\\")).findFirst().orElseThrow();
    assertThat(g.block()).contains("\\\\");
    DiffCase c = DiffCase.of("core-v1.cases", CaseFormat.parse("core-v1.cases", "gen/core-v1", g.block().getBytes(UTF_8)).getFirst());
    assertThat(c.args().getLast()).isEqualTo(CoreV1.print(g.tree()));
  }
}
