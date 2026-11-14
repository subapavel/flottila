package io.github.subapavel.jqj.internal.parse;

import io.github.subapavel.jqj.JqCompileException;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqValue;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ADR-0040's Binder, pass 1 of the resolution: it binds every reference of a filter once per lexical occurrence, a call
 * by its name and arity, a {@code $name} and a {@code break $name} by name, and collects jq's binding errors in jq's
 * compile levels, which it throws before any lowering (GLOSSARY "Compile level", "Live definition"). The {@link
 * Resolver} lowers from what it bound.
 *
 * <p>A call binds innermost first (ADR-0042 "Lookup order"): a parse constant ({@code true}, {@code false} and {@code
 * null} with no arguments), then the definitions and parameters in scope, the innermost first, a definition's body
 * seeing its nested definitions, then its parameters, a later one of a name first, then the definition itself, then the
 * outer scopes; then the {@link NativeTable}, a {@link Native} when it is built and an {@link Unbuilt} otherwise; then
 * the {@link Prelude}. {@code ..} is a call of {@code recurse/0} (jq's parser.y). A prelude body sees no user
 * definition, variable or label: only its own scopes, the native table and the prelude definitions before it, which
 * are the whole prelude's it calls, since builtin.jq calls no definition before it is written (PreludeBindTest). The
 * argument of a C native stays in the caller's compile level; every other argument of a call that binds is a closure
 * of a jq-defined function, a definition's or a bytecoded native's, so it is an inner compile level (ADR-0040 "Errors").
 *
 * <p>A prelude definition is bound only when the main filter reaches it, once per compile: after the main filter's
 * walk, and only if it reported no error, the bodies of the live prelude definitions are walked until no new one is
 * live (GLOSSARY "Live definition"). A prelude body has no binding error (PreludeBindTest), so its level reports
 * nothing.
 *
 * <p>The walk meets the references in the order the Resolver lowers them, which is jq's (ADR-0036, ADR-0038,
 * ADR-0039, ADR-0041), and a level reports them in that order. Its inner levels follow the same order, except that the
 * operands of a C function, an arithmetic or comparison operator or a string's interpolations, which jq places right
 * first, put theirs right first too (jq-ref 1.8.2, recorded: defs/operator-operands-inner-levels-right-first,
 * defs/interpolation-inner-levels-last-first). A level reports its inner levels only when it has no error itself, and
 * the level of a definition the main filter never reaches reports nothing.
 */
final class Binder {
  /** The IDENTs that are constants (jq's parser), when called without arguments. */
  private static final Map<String, JqValue> CONSTANTS = Map.of("true", JqBool.TRUE, "false", JqBool.FALSE, "null", JqNull.NULL);

  /**
   * The variables jq defines without an {@code as} (jq-ref 1.8.2, observed 2026-11-13: {@code $ENV}, {@code $ARGS} and
   * {@code $JQ_BUILD_CONFIGURATION} run, rc 0), none built yet (spec 7): unbound, each binds to a {@link Predefined},
   * which the Resolver stubs, not to jq's compile error.
   */
  private static final Set<String> PREDEFINED = Set.of("ENV", "ARGS", "JQ_BUILD_CONFIGURATION");

  /** What a variable of a {@code ?//} chain binds to in an alternative that has not stored it yet: a Const null. */
  static final Object UNSTORED = new Object();

  /** The id of the main filter in the call graph; a definition's id is its place in {@link #defs}, from 1. */
  private static final int MAIN = 0;

  /** What a call binds to. */
  sealed interface Callee permits Constant, Defined, Parameter, Native, Unbuilt, Library {}

  /** A parse constant. */
  record Constant(JqValue value) implements Callee {}

  /** A definition of the filter, or one nested in a prelude body. */
  record Defined(Ast.FuncDef def) implements Callee {}

  /** A parameter of a definition, a filter one or the filter of a value one ({@code a} of {@code $a}). */
  record Parameter(Ast.Param param) implements Callee {}

  /** An entry of the native table that no ticket has built yet: the Resolver stubs it (GLOSSARY "Unbuilt native"). */
  record Unbuilt(String signature) implements Callee {}

  /** A top-level definition of the prelude (ADR-0042). */
  record Library(Ast.FuncDef def) implements Callee {}

  /** What a variable jq defines itself binds to: it is not built yet, so the Resolver stubs it. */
  record Predefined(String name) {}

  /**
   * The built entries of the {@link NativeTable}: ADR-0040's, ADR-0041's {@code range/2}, #72's language natives and
   * #73's sort and its kin, one constant a line. Each declares {@code keepsArgs} (ADR-0005 amendment, ADR-0022), which spec 3's {@code
   * keepsArgsIsSound} checks: whether its result may share a container or string with an argument or its input.
   */
  enum Native implements Callee {
    EMPTY("empty", 0, false),
    ERROR("error", 0, false),
    NOT("not", 0, false),
    RANGE("range", 2, false),
    LENGTH("length", 0, false),
    TYPE("type", 0, false),
    TOSTRING("tostring", 0, true),
    TOJSON("tojson", 0, false),
    TONUMBER("tonumber", 0, false),
    LAST("last", 1, true),
    HAS("has", 1, false),
    KEYS("keys", 0, true),
    KEYS_UNSORTED("keys_unsorted", 0, true),
    TO_ENTRIES("to_entries", 0, true),
    FLATTEN("flatten", 0, true),
    FLATTEN_DEPTH("flatten", 1, true),
    NAN("nan", 0, false),
    INFINITE("infinite", 0, false),
    ISNAN("isnan", 0, false),
    ISINFINITE("isinfinite", 0, false),
    ISNORMAL("isnormal", 0, false),
    HAVE_DECNUM("have_decnum", 0, false),
    HAVE_LITERAL_NUMBERS("have_literal_numbers", 0, false),
    SORT("sort", 0, true),
    SORT_BY("_sort_by_impl", 1, true),
    GROUP_BY("_group_by_impl", 1, true),
    UNIQUE("unique", 0, true),
    UNIQUE_BY("_unique_by_impl", 1, true),
    MIN("min", 0, true),
    MAX("max", 0, true),
    MIN_BY("_min_by_impl", 1, true),
    MAX_BY("_max_by_impl", 1, true),
    ;

    private final String signature;

    /** Whether the result may share a container or string with an argument or the input, at any depth. */
    final boolean keepsArgs;

    Native(String name, int arity, boolean keepsArgs) {
      this.signature = name + "/" + arity;
      this.keepsArgs = keepsArgs;
    }
  }

  private static final Map<String, Native> NATIVES = new HashMap<>();

  static {
    for (Native n : Native.values()) {
      NATIVES.put(n.signature, n);
    }
  }

  /** A name in scope, what it binds to, and the names outside it. */
  private record Scope(String name, Object binding, Scope outer) {}

  /** A compile error's block, and the line and column of its start. */
  private record Located(String block, int line, int column) {}

  /**
   * A compile level: the binding errors of its own text in the order the walk meets them, and its inner levels; {@code
   * def} is the id of the definition whose body it is, or {@link #MAIN} for the main filter and a closure argument.
   */
  private static final class Level {
    final int def;
    final List<Located> errors = new ArrayList<>();
    final List<Level> inner = new ArrayList<>();

    Level(int def) {
      this.def = def;
    }
  }

  private final FilterText text;

  /** What each reference binds to: an {@link Ast.Call} its {@link Callee}, a variable or label reference its declaration. */
  private final Map<Object, Object> bindings = new IdentityHashMap<>();

  /** The names of each {@code ?//} chain, in source order, each with its group declaration. */
  private final Map<List<Ast.Pattern>, Map<String, Object>> groups = new IdentityHashMap<>();

  /** The declarations of the host variables, in their slots' order. */
  private final List<Object> hosts = new ArrayList<>();

  /** The definitions met, by id; index 0 stands for the main filter. */
  private final List<Ast.FuncDef> defs = new ArrayList<>();

  private final Map<Ast.FuncDef, Integer> ids = new IdentityHashMap<>();

  /** The call graph: for each id, the ids of the definitions its own text calls, its closure arguments included. */
  private final List<List<Integer>> edges = new ArrayList<>();

  /** The definitions and parameters in scope, by {@code name/arity}, the innermost last. */
  private Map<String, ArrayDeque<Callee>> functions = new HashMap<>();

  private Scope variables;

  private Scope labels;

  private Level level = new Level(MAIN);

  /** The id of the definition whose own text the walk is in, or {@link #MAIN}. */
  private int owner = MAIN;

  /** Whether each id is in a cyclic strongly connected component of the call graph (ADR-0040 "Recursion"). */
  private boolean[] recursive;

  /** The signature of the prelude definition whose body the walk is in, or null in the filter's own text. */
  private String prelude;

  /** The prelude definitions a reference has bound to and whose bodies are not walked yet, each with an id. */
  private final List<Ast.FuncDef> unwalked = new ArrayList<>();

  private Binder(FilterText text) {
    this.text = text;
    defs.add(null);
    edges.add(new ArrayList<>());
  }

  /**
   * Binds {@code main} with the host variables {@code parameters} in scope.
   *
   * @throws JqCompileException jq's binding errors, in the levels that report them
   */
  static Binder bind(Ast main, List<String> parameters, FilterText text) {
    Binder binder = new Binder(text);
    for (String name : parameters) {
      Object host = new Object();
      binder.hosts.add(host);
      binder.variables = new Scope(name, host, binder.variables);
    }
    binder.walk(main);
    boolean[] live = binder.live();
    List<Located> errors = new ArrayList<>();
    report(binder.level, live, errors);
    if (!errors.isEmpty()) {
      List<String> blocks = new ArrayList<>(errors.size());
      for (Located e : errors) {
        blocks.add(e.block());
      }
      throw new JqCompileException(blocks, errors.getFirst().line(), errors.getFirst().column());
    }
    while (binder.walkLive(live)) {
      live = binder.live();
    }
    binder.recursive = binder.cycles();
    return binder;
  }

  /**
   * Walks the body of each prelude definition in {@link #unwalked} that {@code live} marks; whether it walked one. A
   * body walked here can bind new prelude definitions, with ids past {@code live}, which the next round marks.
   */
  private boolean walkLive(boolean[] live) {
    boolean walked = false;
    for (int i = 0; i < unwalked.size(); i++) {
      Ast.FuncDef def = unwalked.get(i);
      int id = ids.get(def);
      if (id < live.length && live[id]) {
        unwalked.remove(i--);
        walkPrelude(id, def);
        walked = true;
      }
    }
    return walked;
  }

  /**
   * The body of the prelude definition {@code def}, of id {@code id}, walked as {@link #define} walks a definition's,
   * in a scope of its own: no definition, parameter, variable or label of the filter (ADR-0042 "Lookup order"). Its
   * level is its own; a binding error in it is not jq's error but the {@link #notDefined} IllegalStateException.
   */
  private void walkPrelude(int id, Ast.FuncDef def) {
    // After the main filter's walk no label is in scope, and the walk is in no definition: only the host variables and
    // the definitions' emptied entries are left, which the body does not see.
    Map<String, ArrayDeque<Callee>> outerFunctions = functions;
    Scope outerVariables = variables;
    Level outerLevel = level;
    functions = new HashMap<>();
    variables = null;
    level = new Level(id);
    owner = id;
    prelude = def.name() + "/" + def.params().size();
    // The definition itself, which its body may call: a recursive group, which the Resolver stubs.
    push(prelude, new Library(def));
    for (Ast.Param p : def.params()) {
      push(p.name() + "/0", new Parameter(p));
      if (p.value()) {
        variables = new Scope(p.name(), p, variables);
      }
    }
    walk(def.body());
    functions = outerFunctions;
    variables = outerVariables;
    level = outerLevel;
    owner = MAIN;
    prelude = null;
  }

  /** What {@code reference}, an {@link Ast.Call}, variable reference or {@link Ast.Break} of the filter, binds to. */
  Object binding(Object reference) {
    return bindings.get(reference);
  }

  /** The names of the {@code ?//} chain of {@code patterns}, in source order, each with its group declaration. */
  Map<String, Object> groups(List<Ast.Pattern> patterns) {
    return groups.get(patterns);
  }

  /** The declarations of the host variables, in their slots' order. */
  List<Object> hosts() {
    return hosts;
  }

  /** Whether {@code def} is in a recursive group: a cyclic strongly connected component of the call graph. */
  boolean recursive(Ast.FuncDef def) {
    return recursive[ids.get(def)];
  }

  /** Adds to {@code out} the errors {@code l} reports: none for a dead definition, its own, or else its inner levels'. */
  private static void report(Level l, boolean[] live, List<Located> out) {
    if (!live[l.def]) {
      return;
    }
    if (!l.errors.isEmpty()) {
      out.addAll(l.errors);
      return;
    }
    for (Level i : l.inner) {
      report(i, live, out);
    }
  }

  private void walk(Ast ast) {
    switch (ast) {
      case Ast.Pipe p -> {
        walk(p.left());
        walk(p.right());
      }
      case Ast.Comma c -> comma(c);
      case Ast.Def d -> definitions(d);
      case Ast.Bind b -> {
        walk(b.value());
        bound(b.patterns(), () -> walk(b.body()));
      }
      case Ast.Label l -> label(l);
      case Ast.Binary b -> binary(b);
      case Ast.Neg n -> walk(n.operand());
      case Ast.Try t -> {
        walk(t.body());
        optional(t.handler());
      }
      case Ast.Reduce r -> {
        walk(r.init());
        walk(r.input());
        bound(r.patterns(), () -> walk(r.update()));
      }
      case Ast.Foreach f -> {
        walk(f.init());
        walk(f.input());
        bound(
            f.patterns(),
            () -> {
              walk(f.update());
              optional(f.extract());
            });
      }
      case Ast.If i -> {
        walk(i.cond());
        walk(i.then());
        optional(i.otherwise());
      }
      case Ast.Dot _ -> {}
      case Ast.Recurse r -> call(r, "recurse", List.of(), r.source());
      case Ast.Field f -> chain(f);
      case Ast.StringField f -> chain(f);
      case Ast.Index i -> chain(i);
      case Ast.Each e -> chain(e);
      case Ast.Slice s -> chain(s);
      case Ast.Optional o -> chain(o);
      case Ast.Num _ -> {}
      case Ast.Str s -> string(s);
      case Ast.Format _ -> {}
      case Ast.Paren p -> walk(p.body());
      case Ast.ArrayCons a -> optional(a.body());
      case Ast.ObjectCons o -> object(o);
      case Ast.Var v -> variable(v, v.name(), v.source());
      // builtin.jq's own $$$$name, which the Resolver stubs: it binds nothing.
      case Ast.LastRead _ -> {}
      case Ast.Loc _ -> {}
      case Ast.Call c -> call(c, c.name(), c.args(), c.source());
      case Ast.Break b -> breakTo(b);
    }
  }

  /** Walks {@code ast} unless it is null (a missing handler, extract, else or array body). */
  private void optional(Ast ast) {
    if (ast != null) {
      walk(ast);
    }
  }

  /** A comma chain: its terms flattened by a loop, left to right, as the Resolver lowers them. */
  private void comma(Ast.Comma chain) {
    List<Ast> terms = new ArrayList<>();
    Ast left = chain;
    while (left instanceof Ast.Comma c) {
      terms.add(c.right());
      left = c.left();
    }
    walk(left);
    for (int i = terms.size() - 1; i >= 0; i--) {
      walk(terms.get(i));
    }
  }

  /**
   * A chain of postfix steps, by a loop (F-42): each step's keys and bounds from the outermost step in, then the term
   * the chain starts from, as the Resolver lowers them.
   */
  private void chain(Ast ast) {
    Ast node = ast;
    while (node != null) {
      node =
          switch (node) {
            case Ast.Field f -> f.target();
            case Ast.StringField f -> {
              string(f.name());
              yield f.target();
            }
            case Ast.Index i -> {
              walk(i.index());
              yield i.target();
            }
            case Ast.Each e -> e.target();
            case Ast.Slice s -> {
              optional(s.from());
              optional(s.to());
              yield s.target();
            }
            case Ast.Optional o -> o.target();
            // The term the chain starts from, which is no step.
            default -> {
              walk(node);
              yield null;
            }
          };
    }
  }

  /**
   * {@code l op r}. An arithmetic or comparison operator is jq's call of a C function: its operands are in this level,
   * left first, but jq places their code right first, so their inner levels come right first. {@code and}, {@code or}
   * and {@code //} are jq's own code, in source order. {@code =} and {@code |=} are calls of builtin.jq's {@code
   * _assign} and {@code _modify}, whose operands are closures, inner levels; the other update operators run their
   * value in this level, then pass the paths to {@code _modify} (jq's {@code gen_update}; jq-ref 1.8.2, recorded:
   * defs/update-operands-are-inner-levels, defs/arithmetic-update-value-is-in-the-level).
   */
  private void binary(Ast.Binary b) {
    BinOp op = b.op();
    if (inLevel(op)) {
      operands(b);
    } else if (op == BinOp.ASSIGN || op == BinOp.UPDATE) {
      inner(b.left());
      inner(b.right());
    } else {
      walk(b.right());
      inner(b.left());
    }
  }

  /**
   * Whether both operands of {@code op} are in this level, left first: an arithmetic or comparison operator, {@code
   * and}, {@code or} or {@code //}.
   */
  private static boolean inLevel(BinOp op) {
    return rightFirst(op) || op == BinOp.AND || op == BinOp.OR || op == BinOp.ALTERNATIVE;
  }

  /** Whether {@code op} is jq's call of a C function, whose operands' inner levels come right first. */
  private static boolean rightFirst(BinOp op) {
    return ConstantKey.arithmetic(op) != null || ConstantKey.comparison(op) != null;
  }

  /**
   * The operands of {@code chain}, an operator of {@link #inLevel}, by a loop down the left operands that are such
   * operators too (#86): the parser builds a left-associative chain in a loop, as long as the filter. The term the chain
   * starts from is walked first, then each operator's right operand from the innermost operator out. All of them start
   * at the same place among the level's inner levels, so before a {@link #rightFirst} operator's right operand the
   * inner levels of its left one are cut and put back after it, as walking each operator on its own would. A {@code //}
   * nests to the right and is always its chain's outermost operator, its left operand never being one unparenthesised,
   * so its right operand is walked last: when that is an operator too, this loop walks it as the next chain, as walking
   * it would (#94).
   */
  private void operands(Ast.Binary chain) {
    for (Ast.Binary next = chain; next != null; ) {
      List<Ast.Binary> spine = new ArrayList<>();
      Ast left = next;
      next = null;
      while (left instanceof Ast.Binary b && inLevel(b.op())) {
        spine.add(b);
        left = b.left();
      }
      int from = level.inner.size();
      walk(left);
      for (int i = spine.size() - 1; i >= 0; i--) {
        Ast.Binary b = spine.get(i);
        if (rightFirst(b.op())) {
          List<Level> before = cut(from);
          walk(b.right());
          level.inner.addAll(before);
        } else if (b.op() == BinOp.ALTERNATIVE && b.right() instanceof Ast.Binary right) {
          next = right;
        } else {
          walk(b.right());
        }
      }
    }
  }

  /** Takes out of the current level the inner levels from {@code from} on, and gives them. */
  private List<Level> cut(int from) {
    List<Level> tail = level.inner.subList(from, level.inner.size());
    List<Level> cut = new ArrayList<>(tail);
    tail.clear();
    return cut;
  }

  /**
   * A string literal: its interpolations in source order, which jq joins with {@code +} calls, so their inner levels
   * come last part first.
   */
  private void string(Ast.Str s) {
    List<List<Level>> parts = new ArrayList<>();
    for (Ast.Part part : s.parts()) {
      if (part instanceof Ast.Interp p) {
        int from = level.inner.size();
        walk(p.query());
        parts.add(cut(from));
      }
    }
    for (int i = parts.size() - 1; i >= 0; i--) {
      level.inner.addAll(parts.get(i));
    }
  }

  /** {@code {k: v, ...}}: each pair's key, then its value, as the Resolver lowers them. */
  private void object(Ast.ObjectCons o) {
    for (Ast.Pair pair : o.pairs()) {
      if (pair.key() instanceof Ast.VarKey k && pair.value() != null) {
        // jq's error for an unbound $x of {$x: v} spans the whole pair (jq-ref 1.8.2, observed 2026-11-13).
        variable(k, k.name(), pair.source());
      } else {
        key(pair.key());
      }
      if (pair.value() != null) {
        walk(pair.value());
      } else if (pair.key() instanceof Ast.VarKey k) {
        variable(k, k.name(), k.source());
      }
    }
  }

  /** The expression of a key, if it has one: a string's interpolations or a {@code (query)}. */
  private void key(Ast.Key key) {
    if (key instanceof Ast.StringKey k) {
      string(k.string());
    } else if (key instanceof Ast.QueryKey k) {
      walk(k.query());
    }
  }

  /**
   * {@code $name} at {@code reference}: the innermost declaration of the name, or a {@link Predefined} for a variable
   * jq defines itself; any other name is jq's compile error over {@code error}.
   */
  private void variable(Object reference, String name, Source error) {
    for (Scope s = variables; s != null; s = s.outer()) {
      if (s.name().equals(name)) {
        bindings.put(reference, s.binding());
        return;
      }
    }
    if (PREDEFINED.contains(name)) {
      bindings.put(reference, new Predefined(name));
      return;
    }
    notDefined("$" + name, error);
  }

  /** {@code label $name | body}: the name in the labels' scope, apart from the variables', while the body is walked. */
  private void label(Ast.Label l) {
    Scope outer = labels;
    labels = new Scope(l.name(), l, outer);
    walk(l.body());
    labels = outer;
  }

  /**
   * {@code break $name}: the innermost label of the name. A name no label holds is jq's compile error {@code
   * $*label-name is not defined} over the whole term (jq-ref 1.8.2, recorded: control/break-unknown).
   */
  private void breakTo(Ast.Break b) {
    for (Scope s = labels; s != null; s = s.outer()) {
      if (s.name().equals(b.name())) {
        bindings.put(b, s.binding());
        return;
      }
    }
    notDefined("$*label-" + b.name(), b.source());
  }

  /**
   * {@code name(args)} at {@code source}, or {@code ..}, the call {@code reference} of jq's grammar: its callee, and its
   * arguments, in this level for a C native's (as {@link #binary} walks an operator's operands) and each an inner level
   * otherwise; a name and arity nothing binds is jq's compile error {@code name/arity is not defined} over the name, and
   * jq does not look into its arguments.
   */
  private void call(Ast reference, String name, List<Ast> args, Source source) {
    Callee callee = callee(name, args.size());
    if (callee == null) {
      int start = source.start();
      notDefined(name + "/" + args.size(), new Source(start, start + name.length()));
      return;
    }
    bindings.put(reference, callee);
    if (callee instanceof Defined d) {
      edges.get(owner).add(ids.get(d.def()));
    } else if (callee instanceof Library l) {
      edges.get(owner).add(ids.get(l.def()));
    }
    if (cFunction(callee)) {
      List<List<Level>> operands = new ArrayList<>();
      for (Ast arg : args) {
        int from = level.inner.size();
        walk(arg);
        operands.add(cut(from));
      }
      for (int i = operands.size() - 1; i >= 0; i--) {
        level.inner.addAll(operands.get(i));
      }
      return;
    }
    for (Ast arg : args) {
      inner(arg);
    }
  }

  /**
   * Whether {@code callee} is a C function of the native table, built or unbuilt, whose arguments stay in the caller's
   * compile level (the #71 hand-over: jq-ref 1.8.2, recorded: natives/has-argument-in-level); a bytecoded or replacing
   * native, a definition and a parameter are not.
   */
  private static boolean cFunction(Callee callee) {
    String signature = callee instanceof Native n ? n.signature : callee instanceof Unbuilt u ? u.signature() : null;
    return signature != null && NativeTable.kind(signature) == NativeTable.Kind.C;
  }

  /** What {@code name/arity} binds to here, in the order of the class comment, or null. */
  private Callee callee(String name, int arity) {
    if (arity == 0 && CONSTANTS.containsKey(name)) {
      return new Constant(CONSTANTS.get(name));
    }
    String signature = name + "/" + arity;
    ArrayDeque<Callee> defined = functions.get(signature);
    if (defined != null && !defined.isEmpty()) {
      return defined.peekLast();
    }
    if (NativeTable.kind(signature) != null) {
      return NATIVES.containsKey(signature) ? NATIVES.get(signature) : new Unbuilt(signature);
    }
    Ast.FuncDef def = Prelude.definition(signature);
    if (def == null) {
      return null;
    }
    if (!ids.containsKey(def)) {
      register(def);
      unwalked.add(def);
    }
    return new Library(def);
  }

  /** A new id for {@code def}, with no call yet in the call graph. */
  private void register(Ast.FuncDef def) {
    ids.put(def, defs.size());
    defs.add(def);
    edges.add(new ArrayList<>());
  }

  /** {@code arg} walked as an inner compile level of the current one, the next of its inner levels (ADR-0040). */
  private void inner(Ast arg) {
    Level outer = level;
    level = new Level(MAIN);
    outer.inner.add(level);
    walk(arg);
    level = outer;
  }

  /**
   * A run of definitions, {@code def f: ...; def g: ...; body}, by a loop, since the parser nests a long top-level run
   * as deep as it is long: each definition's body is walked as its own level, then the definition is in scope for the
   * rest.
   */
  private void definitions(Ast.Def d) {
    List<String> defined = new ArrayList<>();
    Ast rest = d;
    while (rest instanceof Ast.Def def) {
      defined.add(define(def.def()));
      rest = def.body();
    }
    walk(rest);
    for (String signature : defined) {
      functions.get(signature).removeLast();
    }
  }

  /**
   * {@code def}: an id and a level of its own, at this place among the current level's inner levels; its body walked
   * with the definition itself, then its parameters in scope, a value parameter also as a variable; then the
   * definition put in scope. Its signature.
   */
  private String define(Ast.FuncDef def) {
    int id = defs.size();
    register(def);
    Level outer = level;
    level = new Level(id);
    outer.inner.add(level);
    int caller = owner;
    owner = id;
    Scope outerVariables = variables;
    String signature = def.name() + "/" + def.params().size();
    push(signature, new Defined(def));
    for (Ast.Param p : def.params()) {
      push(p.name() + "/0", new Parameter(p));
      if (p.value()) {
        variables = new Scope(p.name(), p, variables);
      }
    }
    walk(def.body());
    for (Ast.Param p : def.params()) {
      functions.get(p.name() + "/0").removeLast();
    }
    variables = outerVariables;
    owner = caller;
    level = outer;
    return signature;
  }

  private void push(String signature, Callee callee) {
    functions.computeIfAbsent(signature, s -> new ArrayDeque<>()).addLast(callee);
  }

  /**
   * {@code patterns}' names in scope for {@code body} (ADR-0038). One pattern: its key expressions see only the scope
   * outside it, and the body sees each name at its first store (jq-ref, recorded: bind/repeat-array,
   * bind/key-sees-outer-variable). A {@code ?//} chain: in each alternative every name of the chain is {@link
   * #UNSTORED} until the alternative stores it, each store in scope at once; the body sees each name at a group
   * declaration of its own.
   */
  private void bound(List<Ast.Pattern> patterns, Runnable body) {
    Scope outer = variables;
    if (patterns.size() == 1) {
      Map<String, Object> first = new LinkedHashMap<>();
      match(patterns.getFirst(), first);
      for (Map.Entry<String, Object> name : first.entrySet()) {
        variables = new Scope(name.getKey(), name.getValue(), variables);
      }
    } else {
      Set<String> names = new LinkedHashSet<>();
      for (Ast.Pattern pattern : patterns) {
        names(pattern, names);
      }
      for (Ast.Pattern pattern : patterns) {
        for (String name : names) {
          variables = new Scope(name, UNSTORED, variables);
        }
        match(pattern, null);
        variables = outer;
      }
      Map<String, Object> declarations = new LinkedHashMap<>();
      for (String name : names) {
        Object group = new Object();
        declarations.put(name, group);
        variables = new Scope(name, group, variables);
      }
      groups.put(patterns, declarations);
    }
    body.run();
    variables = outer;
  }

  /**
   * The stores and key expressions of {@code pattern} in the Resolver's matcher order: an array's elements last first,
   * an object's entries in source order, each its key, then its {@code $name}, then its pattern. Each store goes into
   * {@code first} when it is the name's first, or with a null {@code first} (an alternative) into scope at once.
   */
  private void match(Ast.Pattern pattern, Map<String, Object> first) {
    switch (pattern) {
      case Ast.VarPattern v -> store(v.name(), v, first);
      case Ast.ArrayPattern a -> {
        for (int i = a.elements().size() - 1; i >= 0; i--) {
          match(a.elements().get(i), first);
        }
      }
      case Ast.ObjectPattern o -> {
        for (Ast.ObjPat entry : o.entries()) {
          key(entry.key());
          if (entry.key() instanceof Ast.VarKey k) {
            store(k.name(), k, first);
          }
          if (entry.value() != null) {
            match(entry.value(), first);
          }
        }
      }
    }
  }

  /** The names {@code pattern} stores, added to {@code names} in source order. */
  private static void names(Ast.Pattern pattern, Set<String> names) {
    switch (pattern) {
      case Ast.VarPattern v -> names.add(v.name());
      case Ast.ArrayPattern a -> {
        for (Ast.Pattern element : a.elements()) {
          names(element, names);
        }
      }
      case Ast.ObjectPattern o -> {
        for (Ast.ObjPat entry : o.entries()) {
          if (entry.key() instanceof Ast.VarKey k) {
            names.add(k.name());
          }
          if (entry.value() != null) {
            names(entry.value(), names);
          }
        }
      }
    }
  }

  private void store(String name, Object declaration, Map<String, Object> first) {
    if (first == null) {
      variables = new Scope(name, declaration, variables);
    } else {
      first.putIfAbsent(name, declaration);
    }
  }

  /**
   * Collects jq's compile error {@code <shown> is not defined} over {@code error} in the current level.
   *
   * @throws IllegalStateException if the walk is in a prelude body, whose {@code error} is in builtin.jq, not the filter:
   *     a defect of the prelude or the binder, which no filter can cause
   */
  private void notDefined(String shown, Source error) {
    if (prelude != null) {
      throw new IllegalStateException(
          shown + " is not defined in the prelude's " + prelude + ", bytes " + error.start() + " to " + error.end()
              + " of builtin.jq");
    }
    level.errors.add(new Located(text.block(shown + " is not defined", error.start(), error.end()), text.line(error.start()), text.column(error.start())));
  }

  /** Which ids the main filter reaches through calls, closure arguments included (GLOSSARY "Live definition"). */
  private boolean[] live() {
    boolean[] live = new boolean[defs.size()];
    ArrayDeque<Integer> work = new ArrayDeque<>();
    live[MAIN] = true;
    work.add(MAIN);
    while (!work.isEmpty()) {
      for (int to : edges.get(work.poll())) {
        if (!live[to]) {
          live[to] = true;
          work.add(to);
        }
      }
    }
    return live;
  }

  /**
   * Which ids are in a cyclic strongly connected component of the call graph: Tarjan's algorithm, by a loop, since a
   * run of definitions can chain thousands of calls. A definition that calls itself is one.
   */
  private boolean[] cycles() {
    int n = defs.size();
    int[] index = new int[n];
    int[] low = new int[n];
    int[] next = new int[n];
    boolean[] onStack = new boolean[n];
    boolean[] cyclic = new boolean[n];
    Arrays.fill(index, -1);
    ArrayDeque<Integer> stack = new ArrayDeque<>();
    ArrayDeque<Integer> path = new ArrayDeque<>();
    int counter = 0;
    for (int root = 0; root < n; root++) {
      if (index[root] != -1) {
        continue;
      }
      path.push(root);
      index[root] = low[root] = counter++;
      stack.push(root);
      onStack[root] = true;
      while (!path.isEmpty()) {
        int v = path.peek();
        List<Integer> out = edges.get(v);
        if (next[v] < out.size()) {
          int w = out.get(next[v]++);
          if (w == v) {
            cyclic[v] = true;
          } else if (index[w] == -1) {
            index[w] = low[w] = counter++;
            stack.push(w);
            onStack[w] = true;
            path.push(w);
          } else if (onStack[w]) {
            low[v] = Math.min(low[v], index[w]);
          }
          continue;
        }
        path.pop();
        if (!path.isEmpty()) {
          int parent = path.peek();
          low[parent] = Math.min(low[parent], low[v]);
        }
        if (low[v] == index[v]) {
          int w = stack.pop();
          onStack[w] = false;
          boolean group = w != v;
          while (w != v) {
            cyclic[w] = true;
            w = stack.pop();
            onStack[w] = false;
          }
          cyclic[v] |= group;
        }
      }
    }
    return cyclic;
  }
}
