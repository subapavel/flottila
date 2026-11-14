package io.github.subapavel.jqj.query.compile;

import io.github.subapavel.jqj.query.ir.Advance;
import io.github.subapavel.jqj.query.ir.Alt;
import io.github.subapavel.jqj.query.ir.AltBind;
import io.github.subapavel.jqj.query.ir.Alternative;
import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Binds;
import io.github.subapavel.jqj.query.ir.Break;
import io.github.subapavel.jqj.query.ir.Collect;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.Foreach;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.If;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Label;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Reduce;
import io.github.subapavel.jqj.query.ir.Try;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.Fn1;
import io.github.subapavel.jqj.query.run.Fn2;
import io.github.subapavel.jqj.query.run.FnN;
import io.github.subapavel.jqj.query.run.Many;
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
 */
final class IrVerifier {
  /** The nodes seen so far, compared by identity, not by equality. */
  private final Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());

  /** The slots the parameters, a Bind or an AltBind of the tree took so far. */
  private final Set<Integer> bound = new HashSet<>();

  /** The slots in scope where the walk is: the parameters, and those of each Bind and AltBind around it. */
  private final Set<Integer> scope = new HashSet<>();

  /** The slots of the Labels around where the walk is: a Break may name them, a Var may not. */
  private final Set<Integer> labels = new HashSet<>();

  /** The state slots of the loops whose each the walk is in and whose Advance it has not met yet. */
  private final Set<Integer> loops = new HashSet<>();

  /** The leaf of each match whose alternative the walk has not left, by identity, with its alternative's from-slots. */
  private final Map<Node, List<Integer>> leaves = new IdentityHashMap<>();

  /** One more than the largest slot so far. */
  private int slots;

  private IrVerifier(int parameters) {
    for (int p = 0; p < parameters; p++) {
      bound.add(p);
      scope.add(p);
    }
  }

  /**
   * Checks {@code root}, whose slots {@code 0 .. parameters - 1} are its parameter slots; returns the number of slots a
   * run of it needs, one more than its largest slot, the parameter slots included.
   */
  static int verify(Node root, int parameters) {
    IrVerifier verifier = new IrVerifier(parameters);
    verifier.visit(root);
    return Math.max(verifier.slots, parameters);
  }

  private void visit(Node node) {
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
        if (!labels.contains(b.slot())) {
          throw new IllegalStateException("the Break of slot " + b.slot() + " at bytes " + b.source() + " is outside the body of a Label of its slot");
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
