package dev.rail8.jq.internal.parse;

import dev.rail8.jq.JqCompileException;
import dev.rail8.jq.query.ir.Source;
import dev.rail8.jq.value.JqBool;
import dev.rail8.jq.value.JqNull;
import dev.rail8.jq.value.JqValue;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Binder, pass 1 of the resolution: it binds every reference of a filter once per lexical occurrence, a call by its
 * name and arity, a {@code $name} and a {@code break $name} by name, and collects jq's binding errors in jq's compile
 * levels, which it throws before any lowering. A compile level is the main filter, a live definition's body (one the
 * main filter reaches through calls, closure arguments included) or a closure argument of a jq-defined function. The
 * {@link Resolver} lowers from what it bound.
 *
 * <p>A call binds innermost first: a parse constant ({@code true}, {@code false} and {@code null} with no arguments),
 * then the definitions and parameters in scope, the innermost first, a definition's body seeing its nested definitions,
 * then its parameters, a later one of a name first, then the definition itself, then the outer scopes; then the
 * {@link NativeTable}, a {@link Native} when it is built and an {@link Unbuilt} otherwise; then the {@link Prelude}.
 * {@code ..} is a call of {@code recurse/0} (jq's parser.y). A prelude body sees no user definition, variable or label:
 * only its own scopes, the native table and the prelude definitions before it, which are the whole prelude's it calls,
 * since builtin.jq calls no definition before it is written. The argument of a C native stays in the caller's compile
 * level; every other argument of a call that binds is a closure of a jq-defined function, a definition's or a bytecoded
 * native's, so it is an inner compile level, as in jq.
 *
 * <p>A prelude definition is bound only when the main filter reaches it, once per compile: after the main filter's
 * walk, and only if it reported no error, the bodies of the live prelude definitions are walked until no new one is
 * live. A prelude body has no binding error, so its level reports nothing.
 *
 * <p>The walk meets the references in the order the Resolver lowers them, which is jq's, and a level reports them in
 * that order. Its inner levels follow the same order, except that the operands of a C function, an arithmetic or
 * comparison operator or a string's interpolations, which jq places right first, put theirs right first too, as in jq
 * 1.8.2. A level reports its inner levels only when it has no error itself, and the level of a definition the main
 * filter never reaches reports nothing.
 */
final class Binder {
  /** The IDENTs that are constants (jq's parser), when called without arguments. */
  private static final Map<String, JqValue> CONSTANTS = Map.of("true", JqBool.TRUE, "false", JqBool.FALSE, "null", JqNull.NULL);

  /**
   * The variables jq defines without an {@code as} (in jq 1.8.2 {@code $ENV}, {@code $ARGS} and
   * {@code $JQ_BUILD_CONFIGURATION} run, rc 0), none of them built here: unbound, each binds to a {@link Predefined},
   * which the Resolver stubs, not to jq's compile error.
   */
  private static final Set<String> PREDEFINED = Set.of("ENV", "ARGS", "JQ_BUILD_CONFIGURATION");

  /** The name of the variable an arithmetic update or {@code //=} binds its value to: no filter can spell it. */
  private static final String UPDATE_VALUE = "*value";

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

  /** An entry of the native table that is not built: the Resolver stubs it. */
  record Unbuilt(String signature) implements Callee {}

  /** A top-level definition of the prelude. */
  record Library(Ast.FuncDef def) implements Callee {}

  /** What a variable jq defines itself binds to: it is not built, so the Resolver stubs it. */
  record Predefined(String name) {}

  /**
   * A capture of a definition: a declaration outside it that its text or its callees reach, passed as a value (a
   * variable, a value parameter read as {@code $name}, a label's token), as a closure (a filter parameter, or a value
   * parameter called by its name), or as both.
   */
  record Capture(Object declaration, boolean value, boolean closure) {}

  /** A read of a declaration as a value, in {@link #reads} and the captures' kinds. */
  private static final int VALUE = 1;

  /** A read of a declaration as a closure, in {@link #reads} and the captures' kinds. */
  private static final int CLOSURE = 2;

  /** Declarations, by identity, each with its kinds ({@link #VALUE}, {@link #CLOSURE}), in the order first added. */
  private static final class Kinds {
    final List<Object> order = new ArrayList<>();
    final Map<Object, Integer> kinds = new IdentityHashMap<>();

    /** Adds {@code kind} to the kinds of {@code declaration}; whether they changed. */
    boolean add(Object declaration, int kind) {
      int before = kinds.getOrDefault(declaration, 0);
      if (before == 0) {
        order.add(declaration);
      }
      kinds.put(declaration, before | kind);
      return (before | kind) != before;
    }
  }

  /** The built entries of the {@link NativeTable}, one constant a line. */
  enum Native implements Callee {
    EMPTY("empty", 0),
    ERROR("error", 0),
    NOT("not", 0),
    RANGE("range", 2),
    LENGTH("length", 0),
    TYPE("type", 0),
    TOSTRING("tostring", 0),
    TOJSON("tojson", 0),
    TONUMBER("tonumber", 0),
    LAST("last", 1),
    HAS("has", 1),
    KEYS("keys", 0),
    KEYS_UNSORTED("keys_unsorted", 0),
    TO_ENTRIES("to_entries", 0),
    FLATTEN("flatten", 0),
    FLATTEN_DEPTH("flatten", 1),
    NAN("nan", 0),
    INFINITE("infinite", 0),
    ISNAN("isnan", 0),
    ISINFINITE("isinfinite", 0),
    ISNORMAL("isnormal", 0),
    HAVE_DECNUM("have_decnum", 0),
    HAVE_LITERAL_NUMBERS("have_literal_numbers", 0),
    SORT("sort", 0),
    SORT_BY("_sort_by_impl", 1),
    GROUP_BY("_group_by_impl", 1),
    UNIQUE("unique", 0),
    UNIQUE_BY("_unique_by_impl", 1),
    MIN("min", 0),
    MAX("max", 0),
    MIN_BY("_min_by_impl", 1),
    MAX_BY("_max_by_impl", 1),
    STARTSWITH("startswith", 1),
    ENDSWITH("endswith", 1),
    UTF8BYTELENGTH("utf8bytelength", 0),
    TRIM("trim", 0),
    LTRIM("ltrim", 0),
    RTRIM("rtrim", 0),
    EXPLODE("explode", 0),
    IMPLODE("implode", 0),
    SPLIT("split", 1),
    STRINDICES("_strindices", 1),
    CONTAINS("contains", 1),
    BSEARCH("bsearch", 1),
    BUILTINS("builtins", 0),
    FROMJSON("fromjson", 0),
    TOBOOLEAN("toboolean", 0),
    FORMAT("format", 1),
    MATCH("_match_impl", 3),
    STRPTIME("strptime", 1),
    STRFTIME("strftime", 1),
    STRFLOCALTIME("strflocaltime", 1),
    MKTIME("mktime", 0),
    GMTIME("gmtime", 0),
    LOCALTIME("localtime", 0),
    NOW("now", 0),
    GETPATH("getpath", 1),
    SETPATH("setpath", 2),
    DELPATHS("delpaths", 1),
    PATH("path", 1),
    MODIFY("_modify", 2),
    ASSIGN("_assign", 2),
    ;

    private final String signature;

    Native(String name, int arity) {
      this.signature = name + "/" + arity;
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

  /** The call each update operator stands for ({@link #asCall}), by identity: what the walk bound in its place. */
  private final Map<Ast.Binary, Ast> updates = new IdentityHashMap<>();

  /** The declarations of the host variables, in their slots' order. */
  private final List<Object> hosts = new ArrayList<>();

  /** The definitions met, by id; index 0 stands for the main filter. */
  private final List<Ast.FuncDef> defs = new ArrayList<>();

  private final Map<Ast.FuncDef, Integer> ids = new IdentityHashMap<>();

  /** The call graph: for each id, the ids of the definitions its own text calls, its closure arguments included. */
  private final List<List<Integer>> edges = new ArrayList<>();

  /** For each id, the id whose own text holds its definition; {@link #MAIN} for a top-level prelude definition's. */
  private final List<Integer> parents = new ArrayList<>();

  /** The id whose own text declares each declaration: a variable's, a group's, a label's or a parameter's. */
  private final Map<Object, Integer> declared = new IdentityHashMap<>();

  /** For each id, the declarations its own text reads, each with its kinds ({@link #VALUE}, {@link #CLOSURE}), in walk order. */
  private final List<Kinds> reads = new ArrayList<>();

  /** The parameters some call binds to by name: a filter parameter run, or a value parameter's filter. */
  private final Set<Ast.Param> called = Collections.newSetFromMap(new IdentityHashMap<>());

  /** For each id, its captures with their kinds, in Binder order; computed at the first {@link #captures} call. */
  private List<Kinds> captured;

  /** The definitions and parameters in scope, by {@code name/arity}, the innermost last. */
  private Map<String, ArrayDeque<Callee>> functions = new HashMap<>();

  private Scope variables;

  private Scope labels;

  private Level level = new Level(MAIN);

  /** The id of the definition whose own text the walk is in, or {@link #MAIN}. */
  private int owner = MAIN;

  /** Whether each id is in a cyclic strongly connected component of the call graph. */
  private boolean[] recursive;

  /** Whether the main filter reaches each id, once every live prelude body is walked. */
  private boolean[] live;

  /** The live definitions, each after every definition it calls outside its own recursive group ({@link #cycles}). */
  private final List<Ast.FuncDef> calleeFirst = new ArrayList<>();

  /** The signature of the prelude definition whose body the walk is in, or null in the filter's own text. */
  private String prelude;

  /** The prelude definitions a reference has bound to and whose bodies are not walked yet, each with an id. */
  private final List<Ast.FuncDef> unwalked = new ArrayList<>();

  private Binder(FilterText text) {
    this.text = text;
    defs.add(null);
    edges.add(new ArrayList<>());
    parents.add(MAIN);
    reads.add(new Kinds());
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
      binder.declared.put(host, MAIN);
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
    binder.live = live;
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
   * in a scope of its own: no definition, parameter, variable or label of the filter. Its level is its own; a binding
   * error in it is not jq's error but the {@link #notDefined} IllegalStateException.
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
    // The definition itself, which its body may call: a recursive group, which the Resolver lifts.
    push(prelude, new Library(def));
    for (Ast.Param p : def.params()) {
      push(p.name() + "/0", new Parameter(p));
      declared.put(p, id);
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

  /** The call the update operator {@code b} stands for, which the walk bound in its place; null for any other operator. */
  Ast update(Ast.Binary b) {
    return updates.get(b);
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

  /**
   * The live definitions, callees first: each comes after every definition its own text calls, closure arguments
   * included, unless both are in one recursive group (Tarjan's order of the strongly connected components). The
   * expansion measure is computed in this order.
   */
  List<Ast.FuncDef> calleeFirst() {
    return calleeFirst;
  }

  /** Whether a call binds to {@code param} by its name: a filter parameter run, or a value parameter's filter. */
  boolean called(Ast.Param param) {
    return called.contains(param);
  }

  /**
   * The captures of {@code def}, in Binder order: the declarations outside it that its own text reads, in the order the
   * walk met them, then those its callees capture that are outside it too, each callee in the order of its calls. A
   * fixpoint over the call graph, computed once for every definition at the first call, since a recursive group's
   * members reach each other's.
   */
  List<Capture> captures(Ast.FuncDef def) {
    if (captured == null) {
      captured = fixpoint();
    }
    Kinds kinds = captured.get(ids.get(def));
    List<Capture> captures = new ArrayList<>(kinds.order.size());
    for (Object declaration : kinds.order) {
      int kind = kinds.kinds.get(declaration);
      captures.add(new Capture(declaration, (kind & VALUE) != 0, (kind & CLOSURE) != 0));
    }
    return captures;
  }

  /**
   * Each definition's captures with their kinds: first its own reads of declarations outside it, then, until nothing
   * changes, its callees' captures outside it. A definition that calls itself merges its own captures, which changes
   * nothing. The main filter's stay empty: nothing calls it.
   */
  private List<Kinds> fixpoint() {
    List<Kinds> all = new ArrayList<>(defs.size());
    all.add(new Kinds());
    for (int id = 1; id < defs.size(); id++) {
      Kinds own = new Kinds();
      Kinds read = reads.get(id);
      for (Object declaration : read.order) {
        if (!inside(declaration, id)) {
          own.add(declaration, read.kinds.get(declaration));
        }
      }
      all.add(own);
    }
    boolean changed = true;
    while (changed) {
      changed = false;
      for (int id = 1; id < defs.size(); id++) {
        Kinds own = all.get(id);
        for (int callee : edges.get(id)) {
          Kinds theirs = all.get(callee);
          for (Object declaration : theirs.order) {
            if (!inside(declaration, id) && own.add(declaration, theirs.kinds.get(declaration))) {
              changed = true;
            }
          }
        }
      }
    }
    return all;
  }

  /** Whether {@code declaration} is declared in the text of definition {@code id}: its own or a nested definition's. */
  private boolean inside(Object declaration, int id) {
    for (int at = declared.get(declaration); at != MAIN; at = parents.get(at)) {
      if (at == id) {
        return true;
      }
    }
    return false;
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
   * A chain of postfix steps, by a loop, so that a long chain cannot overflow the stack: each step's keys and bounds
   * from the outermost step in, then the term the chain starts from, as the Resolver lowers them.
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
   * and {@code //} are jq's own code, in source order. An update operator is walked as the call it stands for
   * ({@link #asCall}), kept for the Resolver: {@code =} and {@code |=} call {@code _assign} and {@code _modify}, whose
   * operands are closures, inner levels; the other update operators bind their value in this level, then pass the
   * paths to {@code _modify}, as in jq 1.8.2.
   */
  private void binary(Ast.Binary b) {
    if (inLevel(b.op())) {
      operands(b);
    } else {
      Ast call = asCall(b);
      updates.put(b, call);
      walk(call);
    }
  }

  /**
   * The call the update operator {@code b} stands for, as jq 1.8.2's parser writes it ({@code gen_update} and {@code
   * gen_definedor_assign}): {@code l |= r} is {@code _modify(l; r)}, {@code l = r} is {@code _assign(l; r)}, and
   * {@code l op= r} is {@code r as $v | _modify(l; . op $v)}, {@code //=} with {@code //} for {@code op}, where no
   * filter can name {@code $v}. Every node of it has {@code b}'s Source.
   */
  private static Ast asCall(Ast.Binary b) {
    Source at = b.source();
    if (b.op() == BinOp.UPDATE || b.op() == BinOp.ASSIGN) {
      return new Ast.Call(at, b.op() == BinOp.UPDATE ? "_modify" : "_assign", List.of(b.left(), b.right()));
    }
    BinOp leaf =
        switch (b.op()) {
          case ADD_UPDATE -> BinOp.ADD;
          case SUBTRACT_UPDATE -> BinOp.SUBTRACT;
          case MULTIPLY_UPDATE -> BinOp.MULTIPLY;
          case DIVIDE_UPDATE -> BinOp.DIVIDE;
          case MODULO_UPDATE -> BinOp.MODULO;
          case ALTERNATIVE_UPDATE -> BinOp.ALTERNATIVE;
          default -> throw new IllegalArgumentException("no update operator: " + b.op());
        };
    Ast update = new Ast.Binary(at, leaf, new Ast.Dot(at), new Ast.Var(at, UPDATE_VALUE));
    Ast modify = new Ast.Call(at, "_modify", List.of(b.left(), update));
    return new Ast.Bind(at, b.right(), List.of(new Ast.VarPattern(at, UPDATE_VALUE)), modify);
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
   * operators too, not by recursion: the parser builds a left-associative chain in a loop, as long as the filter. The
   * term the chain starts from is walked first, then each operator's right operand from the innermost operator out. All
   * of them start at the same place among the level's inner levels, so before a {@link #rightFirst} operator's right
   * operand the inner levels of its left one are cut and put back after it, as walking each operator on its own would.
   * A {@code //} nests to the right and is always its chain's outermost operator, its left operand never being one
   * unparenthesised, so its right operand is walked last: when that is an operator too, this loop walks it as the next
   * chain, as walking it would.
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
        // jq's error for an unbound $x of {$x: v} spans the whole pair, as in jq 1.8.2.
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
        if (s.binding() != UNSTORED) {
          read(s.binding(), VALUE);
        }
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
    declared.put(l, owner);
    walk(l.body());
    labels = outer;
  }

  /**
   * {@code break $name}: the innermost label of the name. A name no label holds is jq's compile error
   * {@code $*label-name is not defined} over the whole term, as in jq 1.8.2.
   */
  private void breakTo(Ast.Break b) {
    for (Scope s = labels; s != null; s = s.outer()) {
      if (s.name().equals(b.name())) {
        bindings.put(b, s.binding());
        read(s.binding(), VALUE);
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
    } else if (callee instanceof Parameter p) {
      read(p.param(), CLOSURE);
      called.add(p.param());
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
   * compile level, as in jq 1.8.2; a bytecoded or replacing native, a definition and a parameter are not.
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
      register(def, MAIN);
      unwalked.add(def);
    }
    return new Library(def);
  }

  /** A new id for {@code def}, defined in the own text of {@code parent}, with no call or read yet. */
  private void register(Ast.FuncDef def, int parent) {
    ids.put(def, defs.size());
    defs.add(def);
    edges.add(new ArrayList<>());
    parents.add(parent);
    reads.add(new Kinds());
  }

  /** A read of {@code declaration} as {@code kind} in the own text the walk is in. */
  private void read(Object declaration, int kind) {
    reads.get(owner).add(declaration, kind);
  }

  /** {@code arg} walked as an inner compile level of the current one, the next of its inner levels. */
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
    register(def, owner);
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
      declared.put(p, id);
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
   * {@code patterns}' names in scope for {@code body}. One pattern: its key expressions see only the scope outside it,
   * and the body sees each name at its first store, as in jq. A {@code ?//} chain: in each alternative every name of
   * the chain is {@link #UNSTORED} until the alternative stores it, each store in scope at once; the body sees each
   * name at a group declaration of its own.
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
        declared.put(group, owner);
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
    declared.put(declaration, owner);
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

  /** Which ids the main filter reaches through calls, closure arguments included. */
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
   * run of definitions can chain thousands of calls. A definition that calls itself is one. Each component is complete
   * after every component it reaches, so the live definitions are added to {@link #calleeFirst} as their components
   * complete.
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
          completed(w);
          while (w != v) {
            cyclic[w] = true;
            w = stack.pop();
            onStack[w] = false;
            completed(w);
          }
          cyclic[v] |= group;
        }
      }
    }
    return cyclic;
  }

  /** Adds the definition of id {@code id} to {@link #calleeFirst} when it is live; the main filter is no definition. */
  private void completed(int id) {
    if (id != MAIN && live[id]) {
      calleeFirst.add(defs.get(id));
    }
  }
}
