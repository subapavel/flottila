package io.github.subapavel.jqj.query.compile;

import io.github.subapavel.jqj.query.analysis.CallGraph;
import io.github.subapavel.jqj.query.analysis.Numbering;
import io.github.subapavel.jqj.query.ir.Advance;
import io.github.subapavel.jqj.query.ir.Alt;
import io.github.subapavel.jqj.query.ir.AltBind;
import io.github.subapavel.jqj.query.ir.Alternative;
import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Binds;
import io.github.subapavel.jqj.query.ir.Break;
import io.github.subapavel.jqj.query.ir.Call;
import io.github.subapavel.jqj.query.ir.Collect;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.Foreach;
import io.github.subapavel.jqj.query.ir.Function;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.If;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Label;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.ParamCall;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Program;
import io.github.subapavel.jqj.query.ir.Reduce;
import io.github.subapavel.jqj.query.ir.Try;
import io.github.subapavel.jqj.query.ir.Var;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Specialisation (ADR-0006, ADR-0045 "Amendment (ticket #117)", GLOSSARY "Specialisation", "Outside call", "Invariant
 * parameter"): the query-core pass between verify and freeze, which the conservative compile skips (ADR-0018). It makes
 * a copy of a lifted function's recursive group, PLAIN ones included, for each distinct argument tuple an outside call
 * passes to the group's invariant parameters, and the outside call runs the copy, those closures gone.
 *
 * <ul>
 *   <li>An invariant parameter's class joins, by union, each closure parameter a Call inside the group passes a bare
 *       ParamCall of to the callee's parameter it fills. A class is invariant when no Call inside the group passes a
 *       member anything else and it has one member in each function of the group.
 *   <li>A tuple is the outside call's closure argument for each invariant class, the classes in the order of the
 *       group's first function's parameters. Two tuples are one key when they have one shape: Source ignored, the
 *       slots they bind renamed in the order they are bound, their free slots and free closure parameters numbered by
 *       first occurrence, and their constants, Operations, truths and collectors compared by identity (S3).
 *   <li>A copy keeps the closure parameters that are not invariant, renumbered from 0, then takes one trailing closure
 *       per free closure parameter of the tuple; it takes the original's value parameters, then one trailing value per
 *       free slot, a free Break's token among them (S4), its own slots moving up past them. Each ParamCall of an
 *       invariant parameter becomes a fresh copy of its argument, with fresh slots; a Call inside the group runs the
 *       sibling copy, passing its kept closures and its own trailing closures and values.
 *   <li>Outside calls are taken by Source, start then end, then by the Program's pre-order: main, the functions by
 *       place, then the copies by place, and the freeze id within a body (S2). A key is decided at its first call: it is
 *       built when {@code spent + nodes <= cap}, its nodes those its copies hold, counted before they are built, and
 *       refused otherwise, so every call of a refused key keeps its real closures. The calls in a new copy join the
 *       queue; a Call between copies is no outside call, and copies are not specialised again.
 * </ul>
 *
 * <p>The copies are appended after the originals in the order their keys are built, each group's in the order of its
 * members' places. Originals that no call reaches stay in the program.
 */
public final class Specialiser {
  /**
   * The specialisation cap (ADR-0045, GLOSSARY "Specialisation cap"): the nodes the copies of one compile may hold. The
   * #117 census measured the largest demand over the corpus and the prelude below it.
   */
  public static final int SPECIALISE_CAP = 16_384;

  private Specialiser() {}

  /**
   * What the pass made of a program: the specialised program and its layout, the original of each function by place
   * (its own place for an original), the nodes the built copies hold, and the keys refused.
   */
  record Result(Program program, IrVerifier.Layout layout, int[] origin, int specialised, int refused) {}

  /**
   * Specialises {@code program}, whose main tree has {@code parameters} parameter slots and which the IR verifier
   * accepted with {@code layout}, under the cap {@code cap}. A program in which some call runs a copy is rebuilt, and
   * the verifier checks the result and lays it out again (ADR-0045); one in which none does is returned as it is, with
   * its layout.
   *
   * @throws IllegalStateException when the rebuilt program breaks a rule of the IR
   */
  static Result specialise(Program program, int parameters, IrVerifier.Layout layout, int cap) {
    return new Pass(program, parameters, layout, cap).run();
  }

  /** What a compile that makes no copy has: {@code program} as it is, each function its own original, no count. */
  static Result none(Program program, IrVerifier.Layout layout) {
    return new Result(program, layout, identity(program.functions().size()), 0, 0);
  }

  /** Each of {@code functions} places its own original. */
  private static int[] identity(int functions) {
    int[] origin = new int[functions];
    for (int p = 0; p < functions; p++) {
      origin[p] = p;
    }
    return origin;
  }

  /** An outside call met in the body {@code body} (0 for main, 1 + place for a function) at freeze id {@code id}. */
  private record Site(Call call, int body, int id) {}

  /**
   * Where a decided outside call goes: the copy at {@code fn}, passing the caller's closure parameters {@code closures}
   * and slots {@code values}, its tuple's free references, after its kept closures and its values.
   */
  private record Target(int fn, int[] closures, int[] values) {}

  /** The order of the queue: Source start, Source end, then the Program's pre-order (S2). */
  private static final Comparator<Site> ORDER =
      Comparator.<Site>comparingInt(s -> s.call().source().start())
          .thenComparingInt(s -> s.call().source().end())
          .thenComparingInt(Site::body)
          .thenComparingInt(Site::id);

  /** One run of the pass over one program. */
  private static final class Pass {
    private final Program program;
    private final List<Function> functions;
    private final int originals;
    private final int parameters;
    private final IrVerifier.Layout layout;

    /** The layout's slots by body: the main tree's, then each original's. */
    private final int[] slots;

    private final int cap;

    /** The recursive group of each original, by place. */
    private final int[] group;

    /** The places of each group's functions, ascending; a function's index there is its member index. */
    private final List<int[]> members = new ArrayList<>();

    /** Each original's index in its group's members. */
    private final int[] member;

    /** By original place and closure parameter: the invariant class of the parameter in its group, or -1. */
    private final int[][] classOf;

    /** By original place and closure parameter: its place among the kept parameters, or -1 for an invariant one. */
    private final int[][] keptIndex;

    /** By original place: the closure parameters that are not invariant. */
    private final int[] kept;

    /** By group and invariant class: the class's parameter in each member, by member index. */
    private final List<int[][]> classes = new ArrayList<>();

    /** By group: the nodes of its bodies less every replaced ParamCall and every dropped argument. */
    private final long[] fixedNodes;

    /** By group and invariant class: the ParamCalls the class's argument replaces. */
    private final List<long[]> sites = new ArrayList<>();

    /** By group: its Calls inside the group, each of which passes the trailing closures and values on. */
    private final long[] internal;

    /** The copies as built, before the final rewrite retargets their outside calls. */
    private final List<Function> copies = new ArrayList<>();

    /** The original of each copy, in copy order. */
    private final List<Integer> copied = new ArrayList<>();

    /** The place of the first copy of each built key. */
    private final Map<List<Integer>, Integer> built = new HashMap<>();

    /** The keys refused. */
    private final Set<List<Integer>> refused = new HashSet<>();

    /** Each decided outside call, by identity. */
    private final Map<Call, Target> decided = new IdentityHashMap<>();

    /** The identity number of each constant, Operation, truth and collector a shape has met. */
    private final Map<Object, Integer> identities = new IdentityHashMap<>();

    private final PriorityQueue<Site> queue = new PriorityQueue<>(ORDER);

    private long spent;

    Pass(Program program, int parameters, IrVerifier.Layout layout, int cap) {
      this.program = program;
      this.functions = program.functions();
      this.originals = functions.size();
      this.parameters = parameters;
      this.layout = layout;
      this.slots = layout.slots();
      this.cap = cap;
      Numbering numbering = Numbering.of(program);
      CallGraph graph = CallGraph.of(program, numbering);
      group = new int[originals];
      member = new int[originals];
      int groups = 0;
      for (int p = 0; p < originals; p++) {
        group[p] = graph.group(p);
        groups = Math.max(groups, group[p] + 1);
      }
      List<List<Integer>> places = new ArrayList<>();
      for (int g = 0; g < groups; g++) {
        places.add(new ArrayList<>());
      }
      for (int p = 0; p < originals; p++) {
        member[p] = places.get(group[p]).size();
        places.get(group[p]).add(p);
      }
      for (List<Integer> g : places) {
        members.add(g.stream().mapToInt(Integer::intValue).toArray());
      }
      classOf = new int[originals][];
      keptIndex = new int[originals][];
      kept = new int[originals];
      invariantClasses(numbering);
      fixedNodes = new long[groups];
      internal = new long[groups];
      for (int g = 0; g < groups; g++) {
        sites.add(new long[classes.get(g).length]);
        costs(g, numbering);
      }
      enqueue(0, numbering.nodes(-1), -1);
      for (int p = 0; p < originals; p++) {
        enqueue(1 + p, numbering.nodes(p), group[p]);
      }
    }

    /**
     * The invariant classes of every group: a union over (function, closure parameter) pairs, each Call inside a group
     * joining the bare ParamCall it passes to the parameter it fills, and marking a parameter filled by anything else.
     */
    private void invariantClasses(Numbering numbering) {
      int[] offset = new int[originals + 1];
      for (int p = 0; p < originals; p++) {
        offset[p + 1] = offset[p] + functions.get(p).closures();
      }
      int[] parent = new int[offset[originals]];
      for (int k = 0; k < parent.length; k++) {
        parent[k] = k;
      }
      boolean[] bad = new boolean[parent.length];
      for (int p = 0; p < originals; p++) {
        for (Node node : numbering.nodes(p)) {
          if (node instanceof Call c && group[c.fn()] == group[p]) {
            for (int k = 0; k < c.closures().size(); k++) {
              if (c.closures().get(k) instanceof ParamCall pc) {
                parent[root(parent, offset[p] + pc.param())] = root(parent, offset[c.fn()] + k);
              } else {
                bad[offset[c.fn()] + k] = true;
              }
            }
          }
        }
      }
      for (int p = 0; p < originals; p++) {
        classOf[p] = new int[functions.get(p).closures()];
        Arrays.fill(classOf[p], -1);
      }
      for (int[] places : members) {
        // A class's parameter in each member, by its root; a member with two parameters in it, or with a bad one,
        // makes the class not invariant (-2).
        Map<Integer, int[]> byRoot = new HashMap<>();
        for (int i = 0; i < places.length; i++) {
          int p = places[i];
          for (int j = 0; j < functions.get(p).closures(); j++) {
            int[] params = byRoot.computeIfAbsent(root(parent, offset[p] + j), r -> filled(places.length));
            params[i] = params[i] == -1 && !bad[offset[p] + j] ? j : -2;
          }
        }
        List<int[]> found = new ArrayList<>();
        int first = places[0];
        for (int j = 0; j < functions.get(first).closures(); j++) {
          int[] params = byRoot.get(root(parent, offset[first] + j));
          if (Arrays.stream(params).allMatch(q -> q >= 0)) {
            for (int i = 0; i < places.length; i++) {
              classOf[places[i]][params[i]] = found.size();
            }
            found.add(params);
          }
        }
        int[][] byClass = found.toArray(new int[0][]);
        classes.add(byClass);
      }
      for (int p = 0; p < originals; p++) {
        keptIndex[p] = new int[classOf[p].length];
        for (int j = 0; j < classOf[p].length; j++) {
          keptIndex[p][j] = classOf[p][j] < 0 ? kept[p]++ : -1;
        }
      }
    }

    private static int[] filled(int length) {
      int[] params = new int[length];
      Arrays.fill(params, -1);
      return params;
    }

    private static int root(int[] parent, int k) {
      int r = k;
      while (parent[r] != r) {
        r = parent[r];
      }
      return r;
    }

    /**
     * The parts of a copy of group {@code g} that do not depend on the tuple: its bodies' nodes less each ParamCall an
     * argument replaces and each bare ParamCall a Call inside the group no longer passes, those ParamCalls by class,
     * and the Calls inside the group.
     */
    private void costs(int g, Numbering numbering) {
      long[] replaced = sites.get(g);
      for (int p : members.get(g)) {
        Set<Node> dropped = Collections.newSetFromMap(new IdentityHashMap<>());
        // A Call comes before its parts in pre-order, so its dropped arguments are known when the walk meets them.
        for (Node node : numbering.nodes(p)) {
          if (dropped.contains(node)) {
            continue;
          }
          if (node instanceof ParamCall pc && classOf[p][pc.param()] >= 0) {
            replaced[classOf[p][pc.param()]]++;
            continue;
          }
          fixedNodes[g]++;
          if (node instanceof Call c && group[c.fn()] == g) {
            internal[g]++;
            for (int k = 0; k < c.closures().size(); k++) {
              if (classOf[c.fn()][k] >= 0) {
                dropped.add(c.closures().get(k));
              }
            }
          }
        }
      }
    }

    /**
     * Queues the outside calls of a body numbered {@code nodes}, the body {@code body} of the Program's pre-order: each
     * Call into an original outside {@code own}, the caller's group (-1 for main or a copy, which is in no original's
     * group).
     */
    private void enqueue(int body, List<Node> nodes, int own) {
      for (int id = 0; id < nodes.size(); id++) {
        if (nodes.get(id) instanceof Call c && c.fn() < originals && group[c.fn()] != own) {
          queue.add(new Site(c, body, id));
        }
      }
    }

    Result run() {
      while (!queue.isEmpty()) {
        decide(queue.poll().call());
      }
      if (decided.isEmpty()) {
        // No call runs a copy, so no copy was built: the program stays as it is, with the layout already found.
        return new Result(program, layout, identity(originals), 0, refused.size());
      }
      Final rewrite = new Final();
      List<Function> all = new ArrayList<>();
      for (Function f : functions) {
        all.add(new Function(f.source(), f.closures(), f.values(), rewrite.node(f.body())));
      }
      for (Function f : copies) {
        all.add(new Function(f.source(), f.closures(), f.values(), rewrite.node(f.body())));
      }
      int[] origin = Arrays.copyOf(identity(originals), all.size());
      for (int q = 0; q < copied.size(); q++) {
        origin[originals + q] = copied.get(q);
      }
      Program specialised = new Program(rewrite.node(program.main()), all);
      return new Result(specialised, IrVerifier.verify(specialised, parameters), origin, (int) spent, refused.size());
    }

    /** Decides the outside call {@code c}: its key built, at its first call, or refused, or already built. */
    private void decide(Call c) {
      int g = group[c.fn()];
      if (classes.get(g).length == 0) {
        return;
      }
      Tuple tuple = new Tuple(c, g);
      Integer first = built.get(tuple.key);
      if (first == null) {
        // A key refused before is refused again: what is spent only grows.
        long nodes = cost(g, tuple);
        if (spent + nodes > cap) {
          refused.add(tuple.key);
          return;
        }
        spent += nodes;
        first = build(g, tuple);
      }
      decided.put(c, new Target(first + member[c.fn()], toArray(tuple.closures), toArray(tuple.values)));
    }

    private static int[] toArray(Map<Integer, Integer> free) {
      return free.keySet().stream().mapToInt(Integer::intValue).toArray();
    }

    /** The nodes the copies of group {@code g} for {@code tuple} will hold. */
    private long cost(int g, Tuple tuple) {
      long nodes = fixedNodes[g] + internal[g] * (tuple.closures.size() + tuple.values.size());
      long[] replaced = sites.get(g);
      for (int k = 0; k < replaced.length; k++) {
        nodes += replaced[k] * tuple.sizes[k];
      }
      return nodes;
    }

    /** Builds the copies of group {@code g} for {@code tuple}, queues their outside calls; the first copy's place. */
    private int build(int g, Tuple tuple) {
      int first = originals + copies.size();
      built.put(tuple.key, first);
      int[] places = members.get(g);
      for (int p : places) {
        Function f = functions.get(p);
        Copy copy = new Copy(p, first, tuple);
        Node body = copy.node(f.body());
        copies.add(new Function(f.source(), kept[p] + tuple.closures.size(), f.values() + tuple.values.size(), body));
        copied.add(p);
      }
      for (int i = 0; i < places.length; i++) {
        enqueue(1 + first + i, Numbering.of(new Program(copies.get(first - originals + i).body(), List.of())).nodes(-1), -1);
      }
      return first;
    }

    /**
     * The tuple of an outside call: its arguments for the invariant classes of the callee's group, their shape as the
     * key, their free closure parameters and free slots by first occurrence, and each argument's node count.
     */
    private final class Tuple {
      final List<Node> arguments = new ArrayList<>();
      final List<Integer> key = new ArrayList<>();
      final long[] sizes;

      /** The free closure parameters of the caller, each to its index among the trailing closures. */
      final Map<Integer, Integer> closures = new LinkedHashMap<>();

      /** The free slots of the caller, each to its index among the trailing values. */
      final Map<Integer, Integer> values = new LinkedHashMap<>();

      /** The slots the arguments bind, each to its number in binding order. */
      private final Map<Integer, Integer> own = new HashMap<>();

      private long nodes;

      Tuple(Call c, int g) {
        int[][] byClass = classes.get(g);
        sizes = new long[byClass.length];
        key.add(g);
        for (int k = 0; k < byClass.length; k++) {
          Node argument = c.closures().get(byClass[k][member[c.fn()]]);
          arguments.add(argument);
          nodes = 0;
          shape(argument);
          sizes[k] = nodes;
        }
      }

      private int identity(Object o) {
        return identities.computeIfAbsent(o, x -> identities.size());
      }

      /** A slot the walk meets bound by the arguments: its number in binding order. */
      private void bind(int slot) {
        own.put(slot, own.size());
      }

      /** A slot the walk reads: its number when the arguments bind it, else -1 less its index among the free slots. */
      private int ref(int slot) {
        Integer bound = own.get(slot);
        return bound != null ? bound : -1 - values.computeIfAbsent(slot, s -> values.size());
      }

      private void shape(Node node) {
        nodes++;
        // One arm per kind: javac compiles a case of two patterns to a second dispatch no node can take.
        switch (node) {
          case Identity _ -> key.add(0);
          case Const c -> {
            key.add(1);
            key.add(identity(c.value()));
          }
          case Var v -> {
            key.add(2);
            key.add(ref(v.slot()));
          }
          case Pipe p -> {
            key.add(3);
            shape(p.left());
            shape(p.right());
          }
          case Comma c -> {
            key.add(4);
            shape(c.left());
            shape(c.right());
          }
          case Bind b -> {
            key.add(5);
            shape(b.value());
            bind(b.slot());
            shape(b.body());
          }
          case Binds b -> {
            key.add(6);
            key.add(b.values().size());
            for (int k = 0; k < b.values().size(); k++) {
              shape(b.values().get(k));
              bind(b.slots().get(k));
            }
            shape(b.body());
          }
          case Intrinsic i -> {
            key.add(7);
            key.add(identity(i.op()));
            key.add(i.args().size());
            i.args().forEach(this::shape);
          }
          case Collect c -> {
            key.add(8);
            key.add(identity(c.collector()));
            shape(c.body());
          }
          case If i -> {
            key.add(9);
            key.add(identity(i.truth()));
            shape(i.cond());
            shape(i.then());
            shape(i.otherwise());
          }
          case Alt a -> {
            key.add(10);
            key.add(identity(a.truth()));
            shape(a.left());
            shape(a.right());
          }
          case AltBind a -> {
            key.add(11);
            key.add(a.alternatives().size());
            key.add(a.slots().size());
            bind(a.slot());
            a.slots().forEach(this::bind);
            shape(a.value());
            for (Alternative alternative : a.alternatives()) {
              shape(alternative.match());
              for (int from : alternative.from()) {
                key.add(from == Alternative.NULL ? Integer.MIN_VALUE : ref(from));
              }
            }
            shape(a.body());
          }
          case Try t -> {
            key.add(12);
            shape(t.body());
            shape(t.handler());
          }
          case Label l -> {
            key.add(13);
            bind(l.slot());
            shape(l.body());
          }
          case Break b -> {
            key.add(14);
            key.add(ref(b.slot()));
          }
          case Reduce r -> {
            key.add(15);
            shape(r.init());
            bind(r.slot());
            shape(r.each());
          }
          case Foreach f -> {
            key.add(16);
            shape(f.init());
            bind(f.slot());
            shape(f.each());
          }
          case Advance a -> {
            key.add(17);
            key.add(ref(a.slot()));
            shape(a.update());
          }
          case Call c -> {
            key.add(18);
            key.add(c.fn());
            key.add(c.closures().size());
            key.add(c.values().size());
            c.closures().forEach(this::shape);
            c.values().forEach(this::shape);
          }
          case ParamCall p -> {
            key.add(19);
            key.add(closures.computeIfAbsent(p.param(), q -> closures.size()));
          }
        }
      }
    }

    /**
     * A rebuild of a tree into fresh nodes: every kind is rebuilt part by part, its slots through {@link #bind} where a
     * node binds one and {@link #ref} where a node reads one, and its ParamCalls and Calls through {@link #paramCall}
     * and {@link #call}.
     */
    private abstract static class Rewrite {
      abstract int bind(int slot);

      abstract int ref(int slot);

      abstract Node paramCall(ParamCall p);

      /** A Call as it is, its parts rebuilt. */
      Node call(Call c) {
        return new Call(c.source(), c.fn(), nodes(c.closures()), nodes(c.values()));
      }

      final List<Node> nodes(List<Node> nodes) {
        List<Node> out = new ArrayList<>(nodes.size());
        for (Node n : nodes) {
          out.add(node(n));
        }
        return out;
      }

      final Node node(Node node) {
        // One arm per kind: javac compiles a case of two patterns to a second dispatch no node can take.
        return switch (node) {
          case Identity i -> new Identity(i.source());
          case Const c -> new Const(c.source(), c.value());
          case Var v -> new Var(v.source(), ref(v.slot()));
          case Pipe p -> new Pipe(p.source(), node(p.left()), node(p.right()));
          case Comma c -> new Comma(c.source(), node(c.left()), node(c.right()));
          case Bind b -> {
            Node value = node(b.value());
            int slot = bind(b.slot());
            yield new Bind(b.source(), value, slot, node(b.body()));
          }
          case Binds b -> {
            List<Node> values = new ArrayList<>();
            List<Integer> bound = new ArrayList<>();
            for (int k = 0; k < b.values().size(); k++) {
              values.add(node(b.values().get(k)));
              bound.add(bind(b.slots().get(k)));
            }
            yield new Binds(b.source(), values, bound, node(b.body()));
          }
          case Intrinsic i -> new Intrinsic(i.source(), i.op(), nodes(i.args()));
          case Collect c -> new Collect(c.source(), node(c.body()), c.collector());
          case If i -> new If(i.source(), node(i.cond()), node(i.then()), node(i.otherwise()), i.truth());
          case Alt a -> new Alt(a.source(), node(a.left()), node(a.right()), a.truth());
          case AltBind a -> altBind(a);
          case Try t -> new Try(t.source(), node(t.body()), node(t.handler()));
          case Label l -> {
            int slot = bind(l.slot());
            yield new Label(l.source(), slot, node(l.body()));
          }
          case Break b -> new Break(b.source(), ref(b.slot()));
          case Reduce r -> {
            Node init = node(r.init());
            int slot = bind(r.slot());
            yield new Reduce(r.source(), init, slot, node(r.each()));
          }
          case Foreach f -> {
            Node init = node(f.init());
            int slot = bind(f.slot());
            yield new Foreach(f.source(), init, slot, node(f.each()));
          }
          case Advance a -> new Advance(a.source(), ref(a.slot()), node(a.update()));
          case Call c -> call(c);
          case ParamCall p -> paramCall(p);
        };
      }

      /** An AltBind: its slots bound, then its value, each alternative's match before its from-slots, then its body. */
      private Node altBind(AltBind a) {
        int slot = bind(a.slot());
        List<Integer> group = new ArrayList<>();
        for (int g : a.slots()) {
          group.add(bind(g));
        }
        Node value = node(a.value());
        List<Alternative> alternatives = new ArrayList<>();
        for (Alternative alternative : a.alternatives()) {
          Node match = node(alternative.match());
          List<Integer> from = new ArrayList<>();
          for (int f : alternative.from()) {
            from.add(f == Alternative.NULL ? Alternative.NULL : ref(f));
          }
          alternatives.add(new Alternative(match, from));
        }
        return new AltBind(a.source(), value, slot, alternatives, group, node(a.body()));
      }
    }

    /**
     * The body of the copy of the original at {@code place} for a tuple: its own slots past the trailing values, each
     * ParamCall of an invariant parameter its argument inlined, each other one renumbered among the kept parameters,
     * and each Call inside the group the sibling copy's.
     */
    private final class Copy extends Rewrite {
      private final int place;
      private final int first;
      private final Tuple tuple;
      private final int values;

      /** The next fresh slot for an inlined argument's own: past the body's slots and the trailing values. */
      private int next;

      Copy(int place, int first, Tuple tuple) {
        this.place = place;
        this.first = first;
        this.tuple = tuple;
        this.values = functions.get(place).values();
        this.next = slots[place + 1] + tuple.values.size();
      }

      @Override
      int bind(int slot) {
        return ref(slot);
      }

      @Override
      int ref(int slot) {
        return slot < values ? slot : slot + tuple.values.size();
      }

      @Override
      Node paramCall(ParamCall p) {
        int k = classOf[place][p.param()];
        return k >= 0 ? new Inline(this).node(tuple.arguments.get(k)) : new ParamCall(p.source(), keptIndex[place][p.param()]);
      }

      @Override
      Node call(Call c) {
        if (group[c.fn()] != group[place]) {
          return super.call(c);
        }
        List<Node> closures = new ArrayList<>();
        for (int k = 0; k < c.closures().size(); k++) {
          if (classOf[c.fn()][k] < 0) {
            closures.add(node(c.closures().get(k)));
          }
        }
        for (int i = 0; i < tuple.closures.size(); i++) {
          closures.add(new ParamCall(c.source(), kept[place] + i));
        }
        List<Node> passed = nodes(c.values());
        for (int i = 0; i < tuple.values.size(); i++) {
          passed.add(new Var(c.source(), values + i));
        }
        return new Call(c.source(), first + member[c.fn()], closures, passed);
      }
    }

    /**
     * An argument inlined into a copy at one ParamCall: the slots it binds fresh slots of the copy, its free slots the
     * copy's trailing values and its free ParamCalls the copy's trailing closures.
     */
    private final class Inline extends Rewrite {
      private final Copy copy;
      private final Map<Integer, Integer> own = new HashMap<>();

      Inline(Copy copy) {
        this.copy = copy;
      }

      @Override
      int bind(int slot) {
        int fresh = copy.next++;
        own.put(slot, fresh);
        return fresh;
      }

      @Override
      int ref(int slot) {
        Integer bound = own.get(slot);
        return bound != null ? bound : copy.values + copy.tuple.values.get(slot);
      }

      @Override
      Node paramCall(ParamCall p) {
        return new ParamCall(p.source(), kept[copy.place] + copy.tuple.closures.get(p.param()));
      }
    }

    /**
     * The last rebuild of every body: each decided outside call runs its copy, its kept closures and its values
     * followed by its tuple's free closure parameters and free slots; everything else as it is.
     */
    private final class Final extends Rewrite {
      @Override
      int bind(int slot) {
        return slot;
      }

      @Override
      int ref(int slot) {
        return slot;
      }

      @Override
      Node paramCall(ParamCall p) {
        return new ParamCall(p.source(), p.param());
      }

      @Override
      Node call(Call c) {
        Target target = decided.get(c);
        if (target == null) {
          return super.call(c);
        }
        List<Node> closures = new ArrayList<>();
        for (int k = 0; k < c.closures().size(); k++) {
          if (classOf[c.fn()][k] < 0) {
            closures.add(node(c.closures().get(k)));
          }
        }
        for (int q : target.closures()) {
          closures.add(new ParamCall(c.source(), q));
        }
        List<Node> values = nodes(c.values());
        for (int s : target.values()) {
          values.add(new Var(c.source(), s));
        }
        return new Call(c.source(), target.fn(), closures, values);
      }
    }
  }
}
