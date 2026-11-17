package io.github.subapavel.jqj.query.compile;

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
import io.github.subapavel.jqj.query.analysis.CallGraph;
import io.github.subapavel.jqj.query.analysis.CallKinds;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The push form's Java frames of a program, fixed from its IR when the tree is built (ADR-0046 and its #111
 * amendment): a stack unit is one frame of the interpreter, lambda and proxy frames included. Each body (main, a
 * function's, a closure argument's) is walked twice from stacks of its own, so a deep tree takes no deep recursion, and
 * no call is followed into its callee.
 *
 * <ul>
 *   <li>Bottom-up, each node's emit height (the frames from its own up to the one that calls its sink), its leaf-emit
 *       height (the same over the emits no call's result leads to) and its depth (its deepest frame). A call counts as a
 *       starter nests it, 1 + T frames for a Call and 2 + T for a ParamCall (its closure's frame), and its results lead
 *       to no leaf emit.
 *   <li>Top-down, each node's base (the frames under its own, from its body's root) and its rise (the most frames over
 *       the frame that calls its sink, up to the one that calls its body's sink).
 * </ul>
 *
 * <p>A Call's units are {@code w = descent + rise + the callee's leaf-emit depth}: its descent the frames from its
 * body's root to its own, its rise {@code T} and the node's rise. A ParamCall's are its descent and rise; the closure it
 * runs adds its own leaf-emit depth at run time. {@code D_f} is the deepest frame of any body, main's over the run
 * entry's two frames.
 */
public final class Nesting {
  /** The frames under main's root: the two {@code Executable.run}s. */
  static final long ENTRY = 2;

  /** No leaf emit: far enough below 0 that a sum of a few stays below any height, and no sum overflows. */
  private static final long NONE = -(1L << 40);

  /** The frames a Call's own op holds, before T. */
  private static final long CALL = 1;

  /** The frames a ParamCall's own op and its closure hold, before T. */
  private static final long PARAM_CALL = 2;

  /** A sink's frames: its lambda's proxy and body. */
  private static final long SINK = 2;

  /** The units of each Call and ParamCall, by identity. */
  private final Map<Node, Long> units = new IdentityHashMap<>();

  /** The leaf-emit depth of each closure argument, by identity. */
  private final Map<Node, Long> closures = new IdentityHashMap<>();

  /** The leaf-emit depth of each function's body, by its place. */
  private final long[] functions;

  /** The leaf-emit depth a Call into each function is charged, by its place: its own, or its group's largest. */
  private final long[] charged;

  /** The bottom-up facts of each node, by identity, while the walk runs. */
  private final Map<Node, Facts> facts = new IdentityHashMap<>();

  private final long starter;

  private long callFreeDepth;

  /** A node's emit height, leaf-emit height and depth, each from its own frame, which is 1. */
  private record Facts(long emit, long leaf, long depth) {}

  /** A node to place: its base and its rise. */
  private record Place(Node node, long base, long rise) {}

  private Nesting(int functions, long starter) {
    this.functions = new long[functions];
    this.charged = new long[functions];
    this.starter = starter;
  }

  /**
   * The nesting of {@code program}, whose IR the verifier passed, with {@link StackUnits#STARTER} as {@code T}: its
   * recursive groups {@code graph} and its Call kinds {@code kinds}. A jump keeps the units of its activation, so a
   * Call into a group with a TAIL_GROUP call is charged the largest leaf-emit depth of the group's functions, whose bodies
   * may all run in that activation (ADR-0046 #111 amendment, ADR-0047 #118).
   */
  static Nesting of(Program program, CallGraph graph, CallKinds kinds) {
    List<Function> functions = program.functions();
    Nesting n = new Nesting(functions.size(), StackUnits.STARTER);
    List<Node> roots = new ArrayList<>();
    roots.add(program.main());
    for (Function f : functions) {
      roots.add(f.body());
    }
    // Closure arguments join the roots as the walk finds them.
    for (int r = 0; r < roots.size(); r++) {
      n.up(roots.get(r), roots);
    }
    Map<Integer, Long> deepest = new HashMap<>();
    for (int i = 0; i < functions.size(); i++) {
      n.functions[i] = n.leafDepth(functions.get(i).body());
      deepest.merge(graph.group(i), n.functions[i], Math::max);
    }
    for (int i = 0; i < functions.size(); i++) {
      n.charged[i] = kinds.groupJumps(i) ? deepest.get(graph.group(i)) : n.functions[i];
    }
    for (int r = 1 + functions.size(); r < roots.size(); r++) {
      n.closures.put(roots.get(r), n.leafDepth(roots.get(r)));
    }
    for (int r = 0; r < roots.size(); r++) {
      long base = r == 0 ? ENTRY : 0;
      n.down(roots.get(r), base);
      n.callFreeDepth = Math.max(n.callFreeDepth, base + n.facts.get(roots.get(r)).depth());
    }
    n.facts.clear();
    return n;
  }

  /**
   * The units of the Call or ParamCall {@code site}: a Call's whole {@code w}, a ParamCall's descent and rise. A bare
   * ParamCall passed on as a closure argument has them as the root of a body of its own.
   */
  public long units(Node site) {
    return units.get(site);
  }

  /** The leaf-emit depth of the body of the function at {@code place}. */
  public long emitDepth(int place) {
    return functions[place];
  }

  /** The leaf-emit depth of the closure argument {@code closure}, a Call's argument in this program. */
  public long emitDepth(Node closure) {
    return closures.get(closure);
  }

  /** {@code D_f}: the most frames main or any body nests, each call counted as a starter nests it. */
  public long callFreeDepth() {
    return callFreeDepth;
  }

  /** {@code T}: the units a starter adds for the driver's frames over its own. */
  public long starterUnits() {
    return starter;
  }

  /** The leaf-emit depth of the body {@code root}: 0 when every emit is a call's result. */
  private long leafDepth(Node root) {
    return Math.max(0, facts.get(root).leaf());
  }

  /** The bottom-up facts of every node of the body {@code root}, children first; each closure argument joins {@code roots}. */
  private void up(Node root, List<Node> roots) {
    Deque<Node> pending = new ArrayDeque<>();
    Deque<Node> order = new ArrayDeque<>();
    pending.push(root);
    while (!pending.isEmpty()) {
      Node node = pending.pop();
      order.push(node);
      if (node instanceof Call c) {
        roots.addAll(c.closures());
      }
      for (Node part : parts(node)) {
        pending.push(part);
      }
    }
    while (!order.isEmpty()) {
      Node node = order.pop();
      facts.put(node, measure(node));
    }
  }

  /** The parts of {@code node} that run in its body's frames; a Call's closure arguments are bodies of their own. */
  private static List<Node> parts(Node node) {
    return switch (node) {
      case Pipe p -> List.of(p.left(), p.right());
      case Comma c -> List.of(c.left(), c.right());
      case Bind b -> List.of(b.value(), b.body());
      case Binds b -> {
        List<Node> parts = new ArrayList<>(b.values());
        parts.add(b.body());
        yield parts;
      }
      case Collect c -> List.of(c.body());
      case If i -> List.of(i.cond(), i.then(), i.otherwise());
      case Alt a -> List.of(a.left(), a.right());
      case AltBind a -> {
        List<Node> parts = new ArrayList<>();
        parts.add(a.value());
        for (Alternative alternative : a.alternatives()) {
          parts.add(alternative.match());
        }
        parts.add(a.body());
        yield parts;
      }
      case Try t -> List.of(t.body(), t.handler());
      case Label l -> List.of(l.body());
      case Reduce r -> List.of(r.init(), r.each());
      case Foreach f -> List.of(f.init(), f.each());
      case Advance a -> List.of(a.update());
      // Leaves, and an Intrinsic's or a Call's atomic arguments, which hold no frame.
      default -> List.of();
    };
  }

  private Facts of(Node node) {
    return facts.get(node);
  }

  /** At least {@link #NONE}: a leaf-emit height with no leaf emit stays none however much is added. */
  private static long none(long leaf) {
    return Math.max(NONE, leaf);
  }

  /** {@code node}'s facts from its parts', each frame as the push form's {@code run} nests it. */
  private Facts measure(Node node) {
    return switch (node) {
      // run calls the sink: one frame. A Break emits nothing; one is an upper bound.
      case Identity _ -> LEAF;
      case Const _ -> LEAF;
      case Var _ -> LEAF;
      case Intrinsic _ -> LEAF;
      case Break _ -> LEAF;
      case Call _ -> new Facts(CALL + starter, NONE, CALL + starter);
      case ParamCall _ -> new Facts(PARAM_CALL + starter, NONE, PARAM_CALL + starter);
      // The right side runs in the left side's sink: its proxy and body, then its run.
      case Pipe p -> chain(of(p.left()), of(p.right()));
      case Bind b -> chain(of(b.value()), of(b.body()));
      case Foreach f -> chain(of(f.init()), of(f.each()));
      case Comma c -> {
        Facts l = of(c.left());
        Facts r = of(c.right());
        yield new Facts(1 + Math.max(l.emit(), r.emit()), none(1 + Math.max(l.leaf(), r.leaf())), 1 + Math.max(l.depth(), r.depth()));
      }
      case Binds b -> binds(b);
      // The body's results go into the Collect's own sink; the Collect emits from its run.
      case Collect c -> new Facts(1, 1, 1 + of(c.body()).depth());
      case If i -> branches(i);
      // The left side's results pass the Truthy sink's frame.
      case Alt a -> {
        Facts l = of(a.left());
        Facts r = of(a.right());
        yield new Facts(1 + Math.max(l.emit() + 1, r.emit()), none(1 + Math.max(l.leaf() + 1, r.leaf())), 1 + Math.max(l.depth(), r.depth()));
      }
      case AltBind a -> altBind(a);
      // The body's results pass the sink's proxy and body and downstream's frame.
      case Try t -> {
        Facts b = of(t.body());
        Facts h = of(t.handler());
        yield new Facts(1 + Math.max(b.emit() + 3, h.emit()), none(1 + Math.max(b.leaf() + 3, h.leaf())), 1 + Math.max(b.depth(), h.depth()));
      }
      case Label l -> {
        Facts b = of(l.body());
        yield new Facts(1 + b.emit(), none(1 + b.leaf()), 1 + b.depth());
      }
      // Each init result runs each in the sink, then the sink's body emits.
      case Reduce r -> {
        Facts i = of(r.init());
        yield new Facts(i.emit() + 1 + SINK, none(i.leaf() + 1 + SINK), Math.max(1 + i.depth(), i.emit() + 1 + SINK + of(r.each()).depth()));
      }
      // The update's results pass the sink's proxy and body, which emits.
      case Advance a -> {
        Facts u = of(a.update());
        yield new Facts(u.emit() + 1 + SINK, none(u.leaf() + 1 + SINK), 1 + u.depth());
      }
    };
  }

  /** The facts of a leaf, which emits from its own frame. */
  private static final Facts LEAF = new Facts(1, 1, 1);

  /** A Pipe, a Bind or a Foreach: {@code second} runs in {@code first}'s sink, and its results are the node's. */
  private static Facts chain(Facts first, Facts second) {
    long base = 1 + first.emit() + SINK;
    return new Facts(base + second.emit(), none(1 + first.leaf() + SINK + second.leaf()), Math.max(1 + first.depth(), base + second.depth()));
  }

  /**
   * A Binds: its run calls {@code from}, which runs each value; a tail value returns into the holder, any other runs the
   * next {@code from} in its sink, the body after the last.
   */
  private Facts binds(Binds b) {
    long from = 2;
    long fromLeaf = 2;
    long depth = 2;
    for (Node value : b.values()) {
      Facts v = of(value);
      depth = Math.max(depth, from + v.depth());
      if (!Compiler.tail(value)) {
        from += v.emit() + SINK + 1;
        fromLeaf = none(fromLeaf + v.leaf() + SINK + 1);
      }
    }
    Facts body = of(b.body());
    return new Facts(from + body.emit(), none(fromLeaf + body.leaf()), Math.max(depth, from + body.depth()));
  }

  /** An If: a tail condition returns into the holder and the branch runs from the If's run; any other runs it in its sink. */
  private Facts branches(If i) {
    Facts c = of(i.cond());
    Facts t = of(i.then());
    Facts e = of(i.otherwise());
    boolean tail = Compiler.tail(i.cond());
    long base = tail ? 1 : 1 + c.emit() + SINK;
    long baseLeaf = tail ? 1 : 1 + c.leaf() + SINK;
    return new Facts(base + Math.max(t.emit(), e.emit()), none(baseLeaf + Math.max(t.leaf(), e.leaf())), Math.max(1 + c.depth(), base + Math.max(t.depth(), e.depth())));
  }

  /**
   * An AltBind: the value's sink calls {@code alternatives}, which calls {@code alternative}, which runs the match; the
   * match's sink runs the body.
   */
  private Facts altBind(AltBind a) {
    Facts v = of(a.value());
    long emit = NONE;
    long leaf = NONE;
    long depth = NONE;
    for (Alternative alternative : a.alternatives()) {
      Facts m = of(alternative.match());
      emit = Math.max(emit, m.emit());
      leaf = Math.max(leaf, m.leaf());
      depth = Math.max(depth, m.depth());
    }
    Facts b = of(a.body());
    long match = 1 + v.emit() + SINK + 2;
    long body = match + emit + SINK;
    return new Facts(body + b.emit(), none(1 + v.leaf() + SINK + 2 + leaf + SINK + b.leaf()), Math.max(1 + v.depth(), Math.max(match + depth, body + b.depth())));
  }

  /** Places every node of the body {@code root}, whose root's base is {@code base}, and records each call's units. */
  private void down(Node root, long base) {
    Deque<Place> pending = new ArrayDeque<>();
    pending.push(new Place(root, base, 0));
    while (!pending.isEmpty()) {
      Place at = pending.pop();
      for (Place part : place(at)) {
        pending.push(part);
      }
    }
  }

  /** The places of the parts of {@code at}'s node, and the units of a call there. */
  private List<Place> place(Place at) {
    long base = at.base();
    long rise = at.rise();
    return switch (at.node()) {
      case Call c -> {
        record(c, base + CALL + starter + rise + charged[c.fn()]);
        yield List.of();
      }
      case ParamCall p -> {
        record(p, base + PARAM_CALL + starter + rise);
        yield List.of();
      }
      case Pipe p -> chain(base, rise, p.left(), p.right());
      case Bind b -> chain(base, rise, b.value(), b.body());
      case Foreach f -> chain(base, rise, f.init(), f.each());
      case Comma c -> List.of(new Place(c.left(), base + 1, rise), new Place(c.right(), base + 1, rise));
      case Binds b -> binds(b, base, rise);
      case Collect c -> List.of(new Place(c.body(), base + 1, 0));
      case If i -> {
        boolean tail = Compiler.tail(i.cond());
        long branches = Math.max(of(i.then()).emit(), of(i.otherwise()).emit());
        long then = tail ? base + 1 : base + 1 + of(i.cond()).emit() + SINK;
        yield List.of(new Place(i.cond(), base + 1, tail ? 0 : SINK + branches + rise), new Place(i.then(), then, rise), new Place(i.otherwise(), then, rise));
      }
      case Alt a -> List.of(new Place(a.left(), base + 1, 1 + rise), new Place(a.right(), base + 1, rise));
      case AltBind a -> altBind(a, base, rise);
      case Try t -> List.of(new Place(t.body(), base + 1, 3 + rise), new Place(t.handler(), base + 1, rise));
      case Label l -> List.of(new Place(l.body(), base + 1, rise));
      case Reduce r -> List.of(new Place(r.init(), base + 1, SINK + rise), new Place(r.each(), base + 1 + of(r.init()).emit() + SINK, 0));
      case Advance a -> List.of(new Place(a.update(), base + 1, SINK + rise));
      // Leaves: no part to place. An Intrinsic's arguments are atomic and hold no frame.
      case Identity _, Const _, Var _, Intrinsic _, Break _ -> List.of();
    };
  }

  /** The most units any occurrence of {@code site} holds, should the IR share it. */
  private void record(Node site, long w) {
    units.merge(site, w, Math::max);
  }

  /** A Pipe, a Bind or a Foreach at {@code base}: {@code second} in {@code first}'s sink. */
  private List<Place> chain(long base, long rise, Node first, Node second) {
    long emit = of(first).emit();
    return List.of(new Place(first, base + 1, SINK + of(second).emit() + rise), new Place(second, base + 1 + emit + SINK, rise));
  }

  /** A Binds at {@code base}: each value from its {@code from}'s frame, the body from the last one's. */
  private List<Place> binds(Binds b, long base, long rise) {
    List<Node> values = b.values();
    long[] from = new long[values.size() + 1];
    from[0] = base + 2;
    for (int k = 0; k < values.size(); k++) {
      from[k + 1] = Compiler.tail(values.get(k)) ? from[k] : from[k] + of(values.get(k)).emit() + SINK + 1;
    }
    long last = from[values.size()];
    long body = of(b.body()).emit();
    List<Place> places = new ArrayList<>();
    for (int k = 0; k < values.size(); k++) {
      Node value = values.get(k);
      // A tail value returns into the holder: nothing of the Binds' runs over its results.
      long over = Compiler.tail(value) ? 0 : last - from[k] - of(value).emit() + body + rise;
      places.add(new Place(value, from[k], over));
    }
    places.add(new Place(b.body(), last, rise));
    return places;
  }

  /** An AltBind at {@code base}: the value, each match in the value's sink, the body in a match's sink. */
  private List<Place> altBind(AltBind a, long base, long rise) {
    long emit = 0;
    for (Alternative alternative : a.alternatives()) {
      emit = Math.max(emit, of(alternative.match()).emit());
    }
    long body = of(a.body()).emit();
    long match = base + 1 + of(a.value()).emit() + SINK + 2;
    List<Place> places = new ArrayList<>();
    places.add(new Place(a.value(), base + 1, SINK + 2 + emit + SINK + body + rise));
    for (Alternative alternative : a.alternatives()) {
      places.add(new Place(alternative.match(), match, SINK + body + rise));
    }
    places.add(new Place(a.body(), match + emit + SINK, rise));
    return places;
  }
}
