package io.github.subapavel.jqj.query.analysis;

import io.github.subapavel.jqj.query.ir.Alt;
import io.github.subapavel.jqj.query.ir.AltBind;
import io.github.subapavel.jqj.query.ir.Alternative;
import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Binds;
import io.github.subapavel.jqj.query.ir.Break;
import io.github.subapavel.jqj.query.ir.Call;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Foreach;
import io.github.subapavel.jqj.query.ir.If;
import io.github.subapavel.jqj.query.ir.Label;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.ParamCall;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Reduce;
import io.github.subapavel.jqj.query.ir.Var;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The kind of every Call of a program (GLOSSARY "Call kind", "Tail call", ADR-0006's four conditions, ADR-0047
 * "Amendment (ticket #118)"), by body and freeze id: one pass per body in ascending freeze id, so a node's tail position
 * is known before its parts are met, with no recursion.
 *
 * <p>Only a function body has tail positions. Its root is one, and from a tail position they pass to the right of a Pipe
 * whose left side is at most one, the right of a Comma or an Alt, the branches of an If whose condition is at most one,
 * and the body of a Bind or Binds whose every value is at most one; to no other part, so never into a closure argument's
 * body. A Call in a tail position into the caller's recursive group is a tail call when each of its closure arguments is
 * a bare ParamCall passed on or holds no free slot, no free Break and no ParamCall (condition 4): TAIL_SELF when it calls
 * the caller, TAIL_GROUP otherwise. Any other Call into the caller's group is RECURSIVE, and every other Call, each of
 * main's among them, is PLAIN. The conservative compile stores no tail kind (ADR-0018).
 */
public final class CallKinds {
  /** The kind of each Call by body, the main tree's first and then each function's by place, and by freeze id; null for any other node. */
  private final CallKind[][] kinds;

  /** By function place: whether a Call of the function's recursive group is TAIL_GROUP. */
  private final boolean[] groupJumps;

  private CallKinds(CallKind[][] kinds, boolean[] groupJumps) {
    this.kinds = kinds;
    this.groupJumps = groupJumps;
  }

  /**
   * The kinds of the program {@code numbering} numbers, whose recursive groups {@code graph} gives, with the tail kinds
   * the cardinality of {@code facts} proves.
   */
  public static CallKinds optimised(Numbering numbering, CallGraph graph, Facts facts) {
    return of(numbering, graph, facts);
  }

  /** The kinds of the program {@code numbering} numbers with no tail kind: each Call PLAIN or RECURSIVE (ADR-0018). */
  public static CallKinds conservative(Numbering numbering, CallGraph graph) {
    return of(numbering, graph, null);
  }

  /** The kinds, with tail kinds only when {@code facts} is not null. */
  private static CallKinds of(Numbering numbering, CallGraph graph, Facts facts) {
    int functions = numbering.functions();
    CallKind[][] kinds = new CallKind[functions + 1][];
    boolean[] jumping = new boolean[graph.groups()];
    for (int body = -1; body < functions; body++) {
      List<Node> nodes = numbering.nodes(body);
      CallKind[] kind = new CallKind[nodes.size()];
      boolean[] tail = new boolean[nodes.size()];
      tail[0] = body >= 0 && facts != null;
      for (int id = 0; id < kind.length; id++) {
        Node node = nodes.get(id);
        if (tail[id]) {
          pass(node, body, numbering, facts, tail);
        }
        if (node instanceof Call c) {
          kind[id] = kind(c, body, tail[id], graph);
          if (kind[id] == CallKind.TAIL_GROUP) {
            jumping[graph.group(body)] = true;
          }
        }
      }
      kinds[body + 1] = kind;
    }
    boolean[] groupJumps = new boolean[functions];
    for (int place = 0; place < functions; place++) {
      groupJumps[place] = jumping[graph.group(place)];
    }
    return new CallKinds(kinds, groupJumps);
  }

  /** The tail positions {@code node}, itself one, passes to its parts in {@code tail}, by freeze id. */
  private static void pass(Node node, int body, Numbering numbering, Facts facts, boolean[] tail) {
    switch (node) {
      case Pipe p -> {
        if (atMostOne(facts, body, numbering, p.left())) {
          tail[numbering.id(p.right())] = true;
        }
      }
      case Comma c -> tail[numbering.id(c.right())] = true;
      case Alt a -> tail[numbering.id(a.right())] = true;
      case If i -> {
        if (atMostOne(facts, body, numbering, i.cond())) {
          tail[numbering.id(i.then())] = true;
          tail[numbering.id(i.otherwise())] = true;
        }
      }
      case Bind b -> {
        if (atMostOne(facts, body, numbering, b.value())) {
          tail[numbering.id(b.body())] = true;
        }
      }
      case Binds b -> {
        if (b.values().stream().allMatch(v -> atMostOne(facts, body, numbering, v))) {
          tail[numbering.id(b.body())] = true;
        }
      }
      // A tail position passes to no part of any other kind: Try, Label, Reduce, Foreach, Collect, AltBind, Advance and
      // a Call's closure arguments run their parts with something left to do after them, or in a frame of their own.
      default -> {
        // No part is a tail position.
      }
    }
  }

  /** Whether {@code part} of the body {@code body} is at most one: one result at most, and nothing runs after it (F-56). */
  private static boolean atMostOne(Facts facts, int body, Numbering numbering, Node part) {
    return facts.cardinality(body, numbering.id(part)) != Cardinality.MANY;
  }

  /** The kind of the Call {@code c} of the body {@code body}, in a tail position when {@code tail}. */
  private static CallKind kind(Call c, int body, boolean tail, CallGraph graph) {
    if (body < 0 || graph.group(c.fn()) != graph.group(body)) {
      return CallKind.PLAIN;
    }
    if (!tail || !c.closures().stream().allMatch(CallKinds::passes)) {
      return CallKind.RECURSIVE;
    }
    return c.fn() == body ? CallKind.TAIL_SELF : CallKind.TAIL_GROUP;
  }

  /**
   * Condition 4 for the closure argument {@code closure}: a bare ParamCall passed on, or a body whose slot reads (a Var,
   * a Break's token, an AltBind's from-slots) are all of slots it binds itself, and which holds no ParamCall.
   */
  private static boolean passes(Node closure) {
    if (closure instanceof ParamCall) {
      return true;
    }
    Set<Integer> bound = new HashSet<>();
    List<Integer> read = new ArrayList<>();
    Deque<Node> pending = new ArrayDeque<>();
    pending.push(closure);
    while (!pending.isEmpty()) {
      Node node = pending.pop();
      switch (node) {
        case ParamCall _ -> {
          return false;
        }
        case Var v -> read.add(v.slot());
        case Break b -> read.add(b.slot());
        case Bind b -> bound.add(b.slot());
        case Binds b -> bound.addAll(b.slots());
        case Label l -> bound.add(l.slot());
        case Reduce r -> bound.add(r.slot());
        case Foreach f -> bound.add(f.slot());
        case AltBind a -> {
          bound.add(a.slot());
          bound.addAll(a.slots());
          for (Alternative alternative : a.alternatives()) {
            for (int from : alternative.from()) {
              if (from != Alternative.NULL) {
                read.add(from);
              }
            }
          }
        }
        // No slot read or bound: an Advance names a loop of the closure's own text (IrVerifier).
        default -> {
          // Its parts are walked below.
        }
      }
      for (Node part : Numbering.parts(node)) {
        pending.push(part);
      }
    }
    // A program binds each slot of a body once, so a slot read and bound in the closure is its own.
    return bound.containsAll(read);
  }

  /** The kind of the Call {@code id}, a freeze id, of the body {@code function}, -1 for the main tree; null for a node that is no Call. */
  public CallKind kind(int function, int id) {
    return kinds[function + 1][id];
  }

  /**
   * Whether a Call of the recursive group of the function at {@code place} is TAIL_GROUP: then a call into the group
   * runs the body of any function of the group in its activation (ADR-0046 #111 amendment).
   */
  public boolean groupJumps(int place) {
    return groupJumps[place];
  }
}
