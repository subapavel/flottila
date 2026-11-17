package dev.rail8.jq.query.compile;

import dev.rail8.jq.query.ir.Advance;
import dev.rail8.jq.query.ir.Alt;
import dev.rail8.jq.query.ir.AltBind;
import dev.rail8.jq.query.ir.Alternative;
import dev.rail8.jq.query.ir.Bind;
import dev.rail8.jq.query.ir.Binds;
import dev.rail8.jq.query.ir.Break;
import dev.rail8.jq.query.ir.Call;
import dev.rail8.jq.query.ir.Collect;
import dev.rail8.jq.query.ir.Comma;
import dev.rail8.jq.query.ir.Const;
import dev.rail8.jq.query.ir.Foreach;
import dev.rail8.jq.query.ir.Function;
import dev.rail8.jq.query.ir.Identity;
import dev.rail8.jq.query.ir.If;
import dev.rail8.jq.query.ir.Intrinsic;
import dev.rail8.jq.query.ir.Label;
import dev.rail8.jq.query.ir.Node;
import dev.rail8.jq.query.ir.ParamCall;
import dev.rail8.jq.query.ir.Pipe;
import dev.rail8.jq.query.ir.Program;
import dev.rail8.jq.query.ir.Reduce;
import dev.rail8.jq.query.ir.Try;
import dev.rail8.jq.query.ir.Var;
import dev.rail8.jq.query.run.Fn0;
import dev.rail8.jq.query.run.Fn1;
import dev.rail8.jq.query.run.Fn2;
import dev.rail8.jq.query.run.FnN;
import dev.rail8.jq.query.run.Many;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks the rules of the IR (ADR-0005, ADR-0034, ADR-0038, ADR-0039, ADR-0041, ADR-0043) before a backend sees a tree; a
 * broken rule is an IllegalStateException. The tree uses no node object twice. A slot is bound by one Bind, by one
 * Binds (one slot per value, two values at least), by one AltBind (its value slot and its group slots), by one Label,
 * by one Reduce or Foreach (its state slot), or is a parameter, which no node binds and every node may read; a Var
 * reads its slot only inside its Bind's body, a Binds slot only in the later values and the body, an AltBind's value
 * slot only in its alternatives and its body, and a group slot only in its AltBind's body. A Label's slot is read by no
 * Var, only by a Break inside the Label's body, and a Break names no other slot. A state slot is read by no Var and
 * named by one Advance at most, inside its loop's each, never in its init. An AltBind has an alternative at least, each with one from entry per group slot, and each from-slot,
 * unless it is -1 for null, is in scope at its match's leaf, the innermost body of the match's chain of Binds. An
 * Intrinsic's arguments are atomic (Identity, Var or Const), as many as its call form's arity: none for Fn0, one for
 * Fn1, two for Fn2, and any number for an FnN or a Many, which take them as an array.
 *
 * <p>A program (ADR-0045 and its #107 amendment) is its main tree and its functions, each body checked on its own
 * slots, with no node object used twice in the whole program. In a function, slots {@code 0 .. values - 1} are its value
 * parameters, which it sees as parameters and no slot of its caller; a Break may also name one of them, a Label's
 * token passed as a value. A ParamCall is only in a function and names one of its closure parameters. A Call names a
 * function of the program and passes as many closures and values as it declares; its closures are nodes of the
 * caller's scope, checked in place, though an Advance in one names a loop of that closure's own text; its values are
 * atomic, and a Var among them may name a Label's slot around the Call, to pass its token.
 */
final class IrVerifier {
  /** {@link #closures} of the main tree, which is no function. */
  private static final int MAIN = -1;

  /** The nodes seen so far in the whole program, compared by identity, not by equality. */
  private final Set<Node> seen;

  /** The program's functions, which a Call names by place. */
  private final List<Function> functions;

  /** The parameter slots of this body: the main tree's, or the function's value parameters. */
  private final int parameters;

  /** The closure parameters of the function whose body this is, or {@link #MAIN}. */
  private final int closures;

  /** The value parameters a Break may name, a token passed in: the function's, and none in the main tree. */
  private final Set<Integer> tokens = new HashSet<>();

  /** The slots the parameters, a Bind or an AltBind of the tree took so far. */
  private final Set<Integer> bound = new HashSet<>();

  /** The slots in scope where the walk is: the parameters, and those of each Bind and AltBind around it. */
  private final Set<Integer> scope = new HashSet<>();

  /** The slots of the Labels around where the walk is: a Break may name them, a Var may not. */
  private final Set<Integer> labels = new HashSet<>();

  /**
   * The state slots of the loops whose each the walk is in and whose Advance it has not met yet; none at the start of a
   * Call's closure.
   */
  private Set<Integer> loops = new HashSet<>();

  /** The leaf of each match whose alternative the walk has not left, by identity, with its alternative's from-slots. */
  private final Map<Node, List<Integer>> leaves = new IdentityHashMap<>();

  /** One more than the largest slot so far. */
  private int slots;

  /** The slots the text the walk is in binds: a Call's closure argument's own, without those of a closure inside it. */
  private List<Integer> own = new ArrayList<>();

  /** The slots each closure argument of the program binds in its own text, by identity. */
  private final Map<Node, List<Integer>> frames;

  /**
   * What a backend needs to lay out a program's frames: the slots a run of each body needs, the main tree's first and
   * then each function's, and the slots each Call's closure argument binds in its own text, by identity.
   */
  record Layout(int[] slots, Map<Node, List<Integer>> closures) {}

  private IrVerifier(Set<Node> seen, Map<Node, List<Integer>> frames, List<Function> functions, int parameters, int closures) {
    this.seen = seen;
    this.frames = frames;
    this.functions = functions;
    this.parameters = parameters;
    this.closures = closures;
    for (int p = 0; p < parameters; p++) {
      bound.add(p);
      scope.add(p);
      if (closures != MAIN) {
        tokens.add(p);
      }
    }
  }

  /**
   * Checks {@code program}, whose main tree has the parameter slots {@code 0 .. parameters - 1}; returns its layout: the
   * number of slots a run of each body needs, one more than its largest slot, its parameter slots included, the main
   * tree's first, then each function's in the program's order; and the slots of each closure argument.
   */
  static Layout verify(Program program, int parameters) {
    Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Map<Node, List<Integer>> frames = new IdentityHashMap<>();
    List<Function> functions = program.functions();
    int[] slots = new int[functions.size() + 1];
    slots[0] = new IrVerifier(seen, frames, functions, parameters, MAIN).body(program.main());
    for (int i = 0; i < functions.size(); i++) {
      Function f = functions.get(i);
      slots[i + 1] = new IrVerifier(seen, frames, functions, f.values(), f.closures()).body(f.body());
    }
    return new Layout(slots, frames);
  }

  /** Checks {@code root}, this verifier's body; the number of slots a run of it needs. */
  private int body(Node root) {
    visit(root);
    return Math.max(slots, parameters);
  }

  /** The checks every node gets first: it is used once in the program, and a match's leaf has its from-slots in scope. */
  private void see(Node node) {
    if (!seen.add(node)) {
      throw new IllegalStateException("the node at bytes " + node.source() + " is used twice: the IR must be a tree");
    }
    List<Integer> from = leaves.remove(node);
    if (from != null) {
      for (int f : from) {
        if (f != Alternative.NULL && !scope.contains(f)) {
          throw new IllegalStateException("the from-slot " + f + " is not in scope at the leaf of its match at bytes " + node.source());
        }
      }
    }
  }

  private void visit(Node node) {
    see(node);
    // One arm per kind: javac compiles a case of two patterns to a second dispatch for a node that matches neither,
    // a path no node can take.
    switch (node) {
      case Identity _ -> {
        // No operand node.
      }
      case Const _ -> {
        // A leaf: its value is no node.
      }
      case Var v -> {
        if (!scope.contains(v.slot())) {
          throw new IllegalStateException("the Var of slot " + v.slot() + " at bytes " + v.source() + " is outside the scope of its slot");
        }
      }
      case Intrinsic i -> arguments(i);
      case Pipe p -> {
        visit(p.left());
        visit(p.right());
      }
      case Comma c -> {
        visit(c.left());
        visit(c.right());
      }
      case Collect c -> visit(c.body());
      case If i -> {
        visit(i.cond());
        visit(i.then());
        visit(i.otherwise());
      }
      case Alt a -> {
        visit(a.left());
        visit(a.right());
      }
      case Bind b -> {
        take(b.slot(), node);
        // The value runs before the slot is set, so only the body may read it.
        visit(b.value());
        scope.add(b.slot());
        visit(b.body());
        scope.remove(b.slot());
      }
      case Binds b -> binds(b);
      case AltBind a -> altBind(a);
      case Try t -> {
        visit(t.body());
        visit(t.handler());
      }
      case Label l -> {
        take(l.slot(), node);
        labels.add(l.slot());
        visit(l.body());
        labels.remove(l.slot());
      }
      case Break b -> {
        if (!labels.contains(b.slot()) && !tokens.contains(b.slot())) {
          throw new IllegalStateException(
              "the Break of slot " + b.slot() + " at bytes " + b.source() + " is outside the body of a Label of its slot, and its slot is no value parameter of its function");
        }
      }
      case Call c -> call(c);
      case ParamCall p -> {
        if (closures == MAIN) {
          throw new IllegalStateException("the ParamCall at bytes " + p.source() + " is in the main tree, outside every function");
        }
        if (p.param() < 0 || p.param() >= closures) {
          throw new IllegalStateException("the ParamCall at bytes " + p.source() + " runs closure parameter " + p.param() + " of a function of " + closures);
        }
      }
      case Reduce r -> loop(r, r.init(), r.slot(), r.each());
      case Foreach f -> loop(f, f.init(), f.slot(), f.each());
      case Advance a -> {
        if (!loops.remove(a.slot())) {
          throw new IllegalStateException(
              "the Advance of slot " + a.slot() + " at bytes " + a.source() + " is outside the each of a Reduce or Foreach of its slot, or is its second Advance");
        }
        visit(a.update());
      }
    }
  }

  /**
   * The Reduce or Foreach {@code node}: its init runs before the state slot is set, so only its each may hold the
   * slot's one Advance, and no Var reads the slot anywhere.
   */
  private void loop(Node node, Node init, int slot, Node each) {
    take(slot, node);
    visit(init);
    loops.add(slot);
    visit(each);
    loops.remove(slot);
  }

  /**
   * The Binds {@code b} (ADR-0043), as the chain of nested Bind nodes it means: two values at least and one slot per value,
   * each slot taken before
   * its value is walked, and each value sees the slots of the values before it, the body all of them. The walk loops
   * over the values, so it does not go deeper with their number.
   */
  private void binds(Binds b) {
    List<Node> values = b.values();
    if (values.size() < 2) {
      throw new IllegalStateException("the Binds at bytes " + b.source() + " has " + values.size() + " values; it needs two at least");
    }
    List<Integer> slots = b.slots();
    if (slots.size() != values.size()) {
      throw new IllegalStateException("the Binds at bytes " + b.source() + " has " + values.size() + " values and " + slots.size() + " slots; it needs one slot per value");
    }
    for (int k = 0; k < values.size(); k++) {
      take(slots.get(k), b);
      visit(values.get(k));
      scope.add(slots.get(k));
    }
    visit(b.body());
    // One slot at a time: removeAll on a list as long as the set scans the list for each slot.
    for (int slot : slots) {
      scope.remove(slot);
    }
  }

  /**
   * The chain {@code a}: its value sees neither its value slot nor its group slots, its alternatives see the value
   * slot, and its body sees both.
   */
  private void altBind(AltBind a) {
    if (a.alternatives().isEmpty()) {
      throw new IllegalStateException("the AltBind at bytes " + a.source() + " has no alternative");
    }
    take(a.slot(), a);
    for (int g : a.slots()) {
      take(g, a);
    }
    visit(a.value());
    scope.add(a.slot());
    for (Alternative alternative : a.alternatives()) {
      if (alternative.from().size() != a.slots().size()) {
        throw new IllegalStateException(
            "an alternative of the AltBind at bytes " + a.source() + " has " + alternative.from().size() + " from entries for " + a.slots().size() + " group slots");
      }
      leaves.put(leaf(alternative.match()), alternative.from());
      visit(alternative.match());
    }
    scope.addAll(a.slots());
    visit(a.body());
    scope.removeAll(a.slots());
    scope.remove(a.slot());
  }

  /**
   * The leaf of the match {@code match}: the innermost body of its chain of Bind and Binds nodes (ADR-0043: a wide
   * match is {@code Binds(steps, Identity)}), where every slot of that chain is in scope; {@code match} itself when it
   * is neither.
   */
  private static Node leaf(Node match) {
    Node leaf = match;
    while (true) {
      if (leaf instanceof Bind b) {
        leaf = b.body();
      } else if (leaf instanceof Binds b) {
        leaf = b.body();
      } else {
        return leaf;
      }
    }
  }

  /** The slot {@code slot} that {@code node} binds: taken by no parameter and no other node. */
  private void take(int slot, Node node) {
    if (!bound.add(slot)) {
      throw new IllegalStateException("the slot " + slot + " of the node at bytes " + node.source() + " is a parameter or is bound twice in the tree");
    }
    slots = Math.max(slots, slot + 1);
    own.add(slot);
  }

  /**
   * The Call {@code c}: a function of the program, as many closures and values as it declares. Its closures are walked
   * here, in the caller's scope, with no loop open, so an Advance in one names a loop of its own text; its values are
   * atomic, and a Var that names a Label around the Call passes that Label's token.
   */
  private void call(Call c) {
    if (c.fn() < 0 || c.fn() >= functions.size()) {
      throw new IllegalStateException("the Call at bytes " + c.source() + " names function " + c.fn() + " of a program of " + functions.size());
    }
    Function f = functions.get(c.fn());
    if (c.closures().size() != f.closures() || c.values().size() != f.values()) {
      throw new IllegalStateException(
          "the Call at bytes " + c.source() + " passes " + c.closures().size() + " closures and " + c.values().size() + " values to function " + c.fn() + ", which takes "
              + f.closures() + " and " + f.values());
    }
    Set<Integer> open = loops;
    List<Integer> text = own;
    loops = new HashSet<>();
    for (Node closure : c.closures()) {
      own = new ArrayList<>();
      visit(closure);
      frames.put(closure, own);
    }
    own = text;
    loops = open;
    for (Node v : c.values()) {
      if (!(v instanceof Identity || v instanceof Var || v instanceof Const)) {
        throw new IllegalStateException("a value of the Call at bytes " + c.source() + " is not atomic: " + v);
      }
      if (v instanceof Var token && labels.contains(token.slot())) {
        see(v);
      } else {
        visit(v);
      }
    }
  }

  private void arguments(Intrinsic i) {
    int arity =
        switch (i.op().form()) {
          case Fn0 _ -> 0;
          case Fn1 _ -> 1;
          case Fn2 _ -> 2;
          case FnN _ -> i.args().size();
          case Many _ -> i.args().size();
        };
    if (i.args().size() != arity) {
      throw new IllegalStateException(
          "the Intrinsic " + i.op().name() + " at bytes " + i.source() + " has " + i.args().size() + " arguments; its form takes " + arity);
    }
    for (Node a : i.args()) {
      if (!(a instanceof Identity || a instanceof Var || a instanceof Const)) {
        throw new IllegalStateException("an argument of the Intrinsic " + i.op().name() + " at bytes " + i.source() + " is not atomic: " + a);
      }
      visit(a);
    }
  }
}
