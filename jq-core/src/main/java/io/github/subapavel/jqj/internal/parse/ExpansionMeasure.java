package io.github.subapavel.jqj.internal.parse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The expansion measure (GLOSSARY "Expansion measure", ADR-0040 "Amendment (ticket #114)"): for each live definition
 * body and each closure argument of a call of a definition, the nodes its lowering would spend of the inline cap and
 * the levels it would reach, closures and nested expansions included. Nodes are a linear form and levels a max-plus
 * form over the filter parameters in scope: {@code nodes + Σ uses(p)·nodes(p)} and {@code max(reach, max(above(p) +
 * reach(p)))}, where {@code p} is a parameter whose closure the lowering runs.
 *
 * <p>It is computed once per compile, callee-first, by a loop over {@link Binder#calleeFirst()}, and each text is
 * walked by a loop with a stack of its own, never by recursion. At each call site of a definition outside a recursive
 * group it takes the larger of the inlined form (the body, its parameters bound to the arguments' forms, and each value
 * parameter's argument lowered once for its Bind) and the lifted form (a Call, its Vars, each closure argument lowered once,
 * since {@code invoke} lowers a closure for every filter parameter, used or not): for nodes the sum of the two, which is
 * exact for a call with no closure argument and an upper bound otherwise; for levels the larger. A call into a
 * recursive group has the lifted form only. A fold counts as unfolded.
 *
 * <p>The nodes count what {@link Resolver}'s {@code spend()} counts, one per node it builds inside an expansion. The
 * levels bound the Resolver's count of levels and the depth of the IR it builds below the call, each construct counted
 * with room for the nodes its lowering adds (a comma chain's balanced tree, a chain's Pipes and Binds, an operator's
 * Binds): only a construct of calls, brackets, pipes and binary commas is measured exactly. Both saturate at {@link
 * #SATURATED}, far past the cap and the depth bound.
 */
final class ExpansionMeasure {

  /** Where the counts stop growing: past {@code Resolver.INLINE_CAP} and {@code Resolver.MAX_IR_DEPTH} alike. */
  static final long SATURATED = 1L << 40;

  /**
   * A measure: {@code nodes} plus {@code uses(p)} times each parameter's nodes, and the larger of {@code reach} and
   * {@code above(p)} plus each parameter's reach. A concrete measure has no parameter.
   */
  record Form(long nodes, Map<Object, Long> uses, long reach, Map<Object, Long> above) {}

  /** The measure of a ParamCall: no node spent, one level. */
  static final Form PARAM_CALL = new Form(0, Map.of(), 1, Map.of());

  /** What pass 1 bound each reference to. */
  private final Binder binder;

  /** The form of each live definition's body. */
  private final Map<Ast.FuncDef, Form> bodies = new IdentityHashMap<>();

  /** The form of each argument of a call of a definition, by identity. */
  private final Map<Ast, Form> arguments = new IdentityHashMap<>();

  private ExpansionMeasure(Binder binder) {
    this.binder = binder;
  }

  /** The measure of {@code main} and of every live definition {@code binder} found in it. */
  static ExpansionMeasure of(Ast main, Binder binder) {
    ExpansionMeasure measure = new ExpansionMeasure(binder);
    for (Ast.FuncDef def : binder.calleeFirst()) {
      measure.bodies.put(def, measure.walk(def.body()));
    }
    measure.walk(main);
    return measure;
  }

  /** The form of {@code arg}, an argument of a call of a definition in live text. */
  Form argument(Ast arg) {
    return arguments.get(arg);
  }

  /** The form of a closure parameter {@code p} run as it is. */
  private static Form param(Object p) {
    Map<Object, Long> uses = new IdentityHashMap<>();
    uses.put(p, 1L);
    Map<Object, Long> above = new IdentityHashMap<>();
    above.put(p, 0L);
    return new Form(0, uses, 0, above);
  }

  /**
   * The measure of a call of {@code def} whose arguments measure {@code args}: the inlined and the lifted form, nodes
   * summed and levels the larger, or the lifted form alone for a definition in a recursive group. {@code outer} gives
   * the measure of a parameter outside {@code def} that its body runs, {@code captured} that of a closure capture.
   */
  Form call(Ast.FuncDef def, List<Form> args, Function<Object, Form> outer, Function<Object, Form> captured) {
    List<Ast.Param> params = def.params();
    int binds = 0;
    for (Ast.Param p : params) {
      if (p.value()) {
        binds++;
      }
    }
    boolean vars = binds > 0;
    for (Binder.Capture c : binder.captures(def)) {
      vars |= c.value();
    }
    int around = binds > Resolver.BIND_RUN ? 1 : binds;
    Acc acc = new Acc();
    // The lifted form: the value parameters' Binds, the Call, a Var below it for each value parameter and value
    // capture (Resolver#invoke), and its closures inside it.
    acc.own(0, around + (vars ? 2L : 1L));
    int value = 0;
    for (int i = 0; i < params.size(); i++) {
      Ast.Param p = params.get(i);
      if (p.value()) {
        acc.child(args.get(i), binds > Resolver.BIND_RUN ? 1 : ++value);
      }
      if (!p.value() || binder.called(p)) {
        acc.child(args.get(i), around + 1L);
      }
    }
    for (Binder.Capture c : binder.captures(def)) {
      if (c.closure()) {
        acc.child(captured.apply(c.declaration()), around + 1L);
      }
    }
    if (binder.recursive(def)) {
      return acc.form();
    }
    // The inlined form: the value parameters' Binds, then the body with each parameter's closure.
    Map<Object, Form> own = new IdentityHashMap<>();
    for (int i = 0; i < params.size(); i++) {
      own.put(params.get(i), args.get(i));
    }
    Form body = bodies.get(def);
    acc.own(body.nodes(), around + body.reach());
    for (Map.Entry<Object, Long> use : body.uses().entrySet()) {
      Form f = own.containsKey(use.getKey()) ? own.get(use.getKey()) : outer.apply(use.getKey());
      acc.add(f, use.getValue());
    }
    for (Map.Entry<Object, Long> at : body.above().entrySet()) {
      Form f = own.containsKey(at.getKey()) ? own.get(at.getKey()) : outer.apply(at.getKey());
      acc.raise(f, around + at.getValue());
    }
    value = 0;
    for (int i = 0; i < params.size(); i++) {
      if (params.get(i).value()) {
        acc.child(args.get(i), binds > Resolver.BIND_RUN ? 1 : ++value);
      }
    }
    return acc.form();
  }

  /** {@code form} with each parameter it runs taken as the measure {@code value} gives it. */
  static Form evaluated(Form form, Function<Object, Form> value) {
    if (form.uses().isEmpty() && form.above().isEmpty()) {
      return form;
    }
    Acc acc = new Acc();
    acc.own(form.nodes(), form.reach());
    for (Map.Entry<Object, Long> use : form.uses().entrySet()) {
      acc.add(value.apply(use.getKey()), use.getValue());
    }
    for (Map.Entry<Object, Long> at : form.above().entrySet()) {
      acc.raise(value.apply(at.getKey()), at.getValue());
    }
    return acc.form();
  }

  /** A form being added up: nodes summed, levels the larger. */
  private static final class Acc {
    long nodes;
    long reach;
    final Map<Object, Long> uses = new IdentityHashMap<>();
    final Map<Object, Long> above = new IdentityHashMap<>();

    /** {@code n} nodes of its own, and a reach of at least {@code r}. */
    void own(long n, long r) {
      nodes = sum(nodes, n);
      reach = Math.max(reach, Math.min(SATURATED, r));
    }

    /** A part lowered once, {@code shift} levels below the construct's own top. */
    void child(Form f, long shift) {
      add(f, 1);
      raise(f, shift);
    }

    /** {@code times} lowerings of {@code f}'s nodes. */
    void add(Form f, long times) {
      nodes = sum(nodes, product(times, f.nodes()));
      for (Map.Entry<Object, Long> use : f.uses().entrySet()) {
        uses.merge(use.getKey(), product(times, use.getValue()), ExpansionMeasure::sum);
      }
    }

    /** {@code f}'s levels, {@code shift} levels down. */
    void raise(Form f, long shift) {
      reach = Math.max(reach, sum(shift, f.reach()));
      for (Map.Entry<Object, Long> at : f.above().entrySet()) {
        above.merge(at.getKey(), sum(shift, at.getValue()), Math::max);
      }
    }

    Form form() {
      return new Form(nodes, uses.isEmpty() ? Map.of() : uses, reach, above.isEmpty() ? Map.of() : above);
    }
  }

  private static long sum(long a, long b) {
    return Math.min(SATURATED, a + b);
  }

  private static long product(long a, long b) {
    if (a == 0 || b == 0) {
      return 0;
    }
    return a > SATURATED / b ? SATURATED : Math.min(SATURATED, a * b);
  }

  /** A text being walked: its parts, and how many of them are measured. */
  private static final class Frame {
    final Ast ast;
    final List<Ast> parts;
    int next;

    Frame(Ast ast, List<Ast> parts) {
      this.ast = ast;
      this.parts = parts;
    }
  }

  /** The form of {@code root}, its parts before it, by a loop with a stack of its own. */
  private Form walk(Ast root) {
    Map<Ast, Form> done = new IdentityHashMap<>();
    ArrayDeque<Frame> stack = new ArrayDeque<>();
    stack.push(new Frame(root, parts(root)));
    while (!stack.isEmpty()) {
      Frame top = stack.peek();
      if (top.next < top.parts.size()) {
        Ast part = top.parts.get(top.next++);
        stack.push(new Frame(part, parts(part)));
        continue;
      }
      stack.pop();
      done.put(top.ast, combine(top.ast, top.parts, done));
    }
    return done.get(root);
  }

  /** The parts of {@code ast} its lowering lowers, a comma chain's, a chain's and a run of definitions' flattened. */
  private List<Ast> parts(Ast ast) {
    List<Ast> parts = new ArrayList<>();
    switch (ast) {
      case Ast.Paren p -> parts.add(p.body());
      case Ast.Def d -> {
        Ast rest = d;
        while (rest instanceof Ast.Def def) {
          rest = def.body();
        }
        parts.add(rest);
      }
      case Ast.Pipe p -> {
        parts.add(p.left());
        parts.add(p.right());
      }
      case Ast.Comma c -> {
        Ast left = c;
        while (left instanceof Ast.Comma inner) {
          parts.add(inner.right());
          left = inner.left();
        }
        parts.add(left);
      }
      case Ast.Bind b -> {
        parts.add(b.value());
        patterns(b.patterns(), parts);
        parts.add(b.body());
      }
      case Ast.Label l -> parts.add(l.body());
      case Ast.Binary b -> {
        parts.add(b.left());
        parts.add(b.right());
      }
      case Ast.Neg n -> parts.add(n.operand());
      case Ast.Try t -> {
        parts.add(t.body());
        present(t.handler(), parts);
      }
      case Ast.Reduce r -> {
        parts.add(r.init());
        parts.add(r.input());
        patterns(r.patterns(), parts);
        parts.add(r.update());
      }
      case Ast.Foreach f -> {
        parts.add(f.init());
        parts.add(f.input());
        patterns(f.patterns(), parts);
        parts.add(f.update());
        present(f.extract(), parts);
      }
      case Ast.If i -> {
        parts.add(i.cond());
        parts.add(i.then());
        present(i.otherwise(), parts);
      }
      // One arm per kind: javac compiles a case of several patterns to a dispatch no Ast takes (Resolver#parts).
      case Ast.Field _ -> chain(ast, parts);
      case Ast.StringField _ -> chain(ast, parts);
      case Ast.Index _ -> chain(ast, parts);
      case Ast.Each _ -> chain(ast, parts);
      case Ast.Slice _ -> chain(ast, parts);
      case Ast.Optional _ -> chain(ast, parts);
      case Ast.Str s -> {
        for (Ast.Part part : s.parts()) {
          if (part instanceof Ast.Interp i) {
            parts.add(i.query());
          }
        }
      }
      case Ast.ArrayCons a -> present(a.body(), parts);
      case Ast.ObjectCons o -> {
        for (Ast.Pair pair : o.pairs()) {
          key(pair.key(), parts);
          present(pair.value(), parts);
        }
      }
      case Ast.Call c -> parts.addAll(c.args());
      // The leaves: no part. No default, so a new kind with parts cannot be walked as a leaf.
      case Ast.Dot _ -> {}
      case Ast.Recurse _ -> {}
      case Ast.Num _ -> {}
      case Ast.Format _ -> {}
      case Ast.Var _ -> {}
      case Ast.LastRead _ -> {}
      case Ast.Loc _ -> {}
      case Ast.Break _ -> {}
    }
    return parts;
  }

  private static void present(Ast ast, List<Ast> parts) {
    if (ast != null) {
      parts.add(ast);
    }
  }

  /** The expression of a key, if it has one. */
  private static void key(Ast.Key key, List<Ast> parts) {
    if (key instanceof Ast.StringKey k) {
      parts.add(k.string());
    } else if (key instanceof Ast.QueryKey k) {
      parts.add(k.query());
    }
  }

  /** The key expressions of {@code patterns}, by a loop; their pattern nodes. */
  private static int patterns(List<Ast.Pattern> patterns, List<Ast> parts) {
    int count = 0;
    ArrayDeque<Ast.Pattern> pending = new ArrayDeque<>(patterns);
    while (!pending.isEmpty()) {
      count++;
      switch (pending.pop()) {
        case Ast.VarPattern _ -> {}
        case Ast.ArrayPattern a -> pending.addAll(a.elements());
        case Ast.ObjectPattern o -> {
          for (Ast.ObjPat entry : o.entries()) {
            if (parts != null) {
              key(entry.key(), parts);
            }
            if (entry.value() != null) {
              pending.add(entry.value());
            }
          }
        }
      }
    }
    return count;
  }

  /** The keys, bounds and term of the chain of postfix steps at {@code outermost}; its steps. */
  private static int chain(Ast outermost, List<Ast> parts) {
    int steps = 0;
    Ast node = outermost;
    Ast innermost = outermost;
    while (node != null) {
      Ast target;
      switch (node) {
        case Ast.Field f -> target = f.target();
        case Ast.StringField f -> {
          if (!f.name().parts().stream().allMatch(Ast.Text.class::isInstance) && parts != null) {
            parts.add(f.name());
          }
          target = f.target();
        }
        case Ast.Index i -> {
          if (parts != null) {
            parts.add(i.index());
          }
          target = i.target();
        }
        case Ast.Each e -> target = e.target();
        case Ast.Slice s -> {
          if (parts != null) {
            present(s.from(), parts);
            present(s.to(), parts);
          }
          target = s.target();
        }
        case Ast.Optional o -> target = o.target();
        // The chain's term: any kind but the six steps above (Pipe, Comma, Def, Bind, Label, Binary, Neg, Try,
        // Reduce, Foreach, If, Dot, Recurse, Num, Str, Format, Paren, ArrayCons, ObjectCons, Var, LastRead, Loc, Call,
        // Break). It is added as a part, so parts() and combine() measure it by its own exhaustive arms, never as a
        // leaf here. A default, since an arm per term kind would add arms for kinds the parser never puts under a step
        // (Pipe, Comma and Binary come in a Paren).
        default -> {
          boolean own = node instanceof Ast.Dot && (innermost instanceof Ast.Index || innermost instanceof Ast.Each || innermost instanceof Ast.Slice);
          if (!own && parts != null) {
            parts.add(node);
          }
          return steps;
        }
      }
      steps++;
      innermost = node;
      node = target;
    }
    return steps;
  }

  /** The smallest k with 2^k at least {@code n}, for {@code n} of at least 2. */
  private static int log2(int n) {
    return 32 - Integer.numberOfLeadingZeros(n - 1);
  }

  /** The form of {@code ast} from those of its {@code parts}. */
  private Form combine(Ast ast, List<Ast> parts, Map<Ast, Form> done) {
    Acc acc = new Acc();
    switch (ast) {
      case Ast.Paren _ -> {
        return done.get(parts.getFirst());
      }
      case Ast.Def _ -> {
        return done.get(parts.getFirst());
      }
      case Ast.Call c -> {
        return call(ast, c.args(), done);
      }
      case Ast.Recurse _ -> {
        return call(ast, List.of(), done);
      }
      case Ast.Comma _ -> {
        acc.own(1, 1);
        children(acc, parts, done, log2(parts.size()));
      }
      case Ast.Pipe _ -> {
        acc.own(1, 1);
        children(acc, parts, done, 1);
      }
      case Ast.ArrayCons _ -> {
        acc.own(1, 1);
        children(acc, parts, done, 1);
      }
      case Ast.Binary _ -> {
        acc.own(1, 4);
        children(acc, parts, done, 3);
      }
      case Ast.Neg _ -> {
        acc.own(1, 3);
        children(acc, parts, done, 1);
      }
      case Ast.If _ -> scoped(acc, parts, done);
      case Ast.Try _ -> scoped(acc, parts, done);
      case Ast.Label _ -> scoped(acc, parts, done);
      case Ast.Bind b -> {
        int p = patterns(b.patterns(), null);
        long k = Math.min(2L * p + 2, Resolver.BIND_RUN);
        acc.own(1 + p, k + 5);
        children(acc, parts, done, k + 4);
      }
      case Ast.Reduce r -> {
        int p = patterns(r.patterns(), null);
        long k = Math.min(2L * p + 2, Resolver.BIND_RUN);
        acc.own(1 + p, k + 8);
        children(acc, parts, done, k + 7);
      }
      case Ast.Foreach f -> {
        int p = patterns(f.patterns(), null);
        long k = Math.min(2L * p + 2, Resolver.BIND_RUN);
        acc.own(1 + p, k + 8);
        children(acc, parts, done, k + 7);
      }
      case Ast.Field _ -> steps(acc, ast, parts, done);
      case Ast.StringField _ -> steps(acc, ast, parts, done);
      case Ast.Index _ -> steps(acc, ast, parts, done);
      case Ast.Each _ -> steps(acc, ast, parts, done);
      case Ast.Slice _ -> steps(acc, ast, parts, done);
      case Ast.Optional _ -> steps(acc, ast, parts, done);
      case Ast.Str s -> {
        long k = Math.min(s.parts().size(), Resolver.BIND_RUN) + 2L;
        acc.own(1, k + 1);
        children(acc, parts, done, k);
      }
      case Ast.ObjectCons o -> {
        int n = o.pairs().size();
        int shorthands = 0;
        for (Ast.Pair pair : o.pairs()) {
          if (pair.value() == null && (pair.key() instanceof Ast.NameKey || pair.key() instanceof Ast.StringKey)) {
            shorthands++;
          }
        }
        long k = 3L * Math.min(n, Resolver.BIND_RUN) + 2;
        acc.own(1L + n + shorthands, k + 2);
        children(acc, parts, done, k);
      }
      // The leaves, a node and a level each. No default, so a new kind cannot be measured as a leaf unseen.
      case Ast.Dot _ -> acc.own(1, 1);
      case Ast.Num _ -> acc.own(1, 1);
      case Ast.Format _ -> acc.own(1, 1);
      case Ast.Var _ -> acc.own(1, 1);
      case Ast.LastRead _ -> acc.own(1, 1);
      case Ast.Loc _ -> acc.own(1, 1);
      case Ast.Break _ -> acc.own(1, 1);
    }
    return acc.form();
  }

  /** An if, a try or a label: a node of its own, each part a level or two below it. */
  private static void scoped(Acc acc, List<Ast> parts, Map<Ast, Form> done) {
    acc.own(1, 3);
    children(acc, parts, done, 2);
  }

  /**
   * A chain of postfix steps: a node a step, and room for its Pipes, its computed keys' Binds and its Trys, three
   * levels a step.
   */
  private static void steps(Acc acc, Ast chain, List<Ast> parts, Map<Ast, Form> done) {
    long steps = chain(chain, null);
    acc.own(steps, 3 * steps + 3);
    children(acc, parts, done, 3 * steps + 1);
  }

  private static void children(Acc acc, List<Ast> parts, Map<Ast, Form> done, long shift) {
    for (Ast part : parts) {
      acc.child(done.get(part), shift);
    }
  }

  /** The form of the call {@code reference} with {@code args}, by what the Binder bound it to. */
  private Form call(Ast reference, List<Ast> args, Map<Ast, Form> done) {
    Acc acc = new Acc();
    switch ((Binder.Callee) binder.binding(reference)) {
      case Binder.Parameter p -> {
        return param(p.param());
      }
      case Binder.Defined d -> {
        return definition(d.def(), args, done);
      }
      case Binder.Library l -> {
        return definition(l.def(), args, done);
      }
      case Binder.Native _ -> {
        // A native's arguments are lowered once each, on the call's input, bound around its Intrinsic.
        acc.own(1, args.isEmpty() ? 2 : 5);
        for (Ast arg : args) {
          acc.child(done.get(arg), 3);
        }
      }
      case Binder.Constant _ -> acc.own(1, 1);
      case Binder.Unbuilt _ -> acc.own(1, 1);
    }
    return acc.form();
  }

  /** The form of a call of {@code def} with {@code args}, each argument's form kept for the Resolver. */
  private Form definition(Ast.FuncDef def, List<Ast> args, Map<Ast, Form> done) {
    List<Form> forms = new ArrayList<>(args.size());
    for (Ast arg : args) {
      Form f = done.get(arg);
      arguments.put(arg, f);
      forms.add(f);
    }
    return call(def, forms, ExpansionMeasure::param, ExpansionMeasure::param);
  }
}
