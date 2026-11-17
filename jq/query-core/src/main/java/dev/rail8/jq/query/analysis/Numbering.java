package dev.rail8.jq.query.analysis;

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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The freeze of a program (ADR-0045 and its #112 amendment, GLOSSARY "Freeze id"): each body, the main tree's and each
 * function's, numbers its nodes from 0 in pre-order, a node before its parts in their order, the order of its record's
 * components and each list in its order. So every part of a node has a larger id than the node, and a walk from the
 * largest id down meets each part before its node. The map is by identity, since records compare by value; it is sound
 * because no node object is used twice in a verified program (ADR-0005). A body is {@code -1} for the main tree and a
 * function's place otherwise.
 */
public final class Numbering {
  /** The freeze id of every node of the program in its body. */
  private final Map<Node, Integer> ids;

  /** The nodes of each body by freeze id: the main tree's first, then each function's by its place. */
  private final List<List<Node>> bodies;

  private Numbering(Map<Node, Integer> ids, List<List<Node>> bodies) {
    this.ids = ids;
    this.bodies = bodies;
  }

  /** Numbers the bodies of {@code program}, which the IR verifier accepted. */
  public static Numbering of(Program program) {
    Map<Node, Integer> ids = new IdentityHashMap<>();
    List<List<Node>> bodies = new ArrayList<>();
    bodies.add(number(program.main(), ids));
    for (Function f : program.functions()) {
      bodies.add(number(f.body(), ids));
    }
    return new Numbering(ids, List.copyOf(bodies));
  }

  /**
   * The freeze id of {@code node} in its body.
   *
   * @throws IllegalArgumentException when {@code node} is no node of the numbered program
   */
  public int id(Node node) {
    Integer id = ids.get(node);
    if (id == null) {
      throw new IllegalArgumentException("the node at bytes " + node.source() + " is no node of the numbered program");
    }
    return id;
  }

  /** The nodes of the body {@code body}, -1 for the main tree, by freeze id. */
  public List<Node> nodes(int body) {
    return bodies.get(body + 1);
  }

  /** The number of functions of the numbered program. */
  int functions() {
    return bodies.size() - 1;
  }

  /** The nodes of the body {@code root} in pre-order, each put in {@code ids}; from a stack of its own, so a deep tree takes no deep recursion. */
  private static List<Node> number(Node root, Map<Node, Integer> ids) {
    List<Node> order = new ArrayList<>();
    Deque<Node> pending = new ArrayDeque<>();
    pending.push(root);
    while (!pending.isEmpty()) {
      Node node = pending.pop();
      ids.put(node, order.size());
      order.add(node);
      List<Node> parts = parts(node);
      for (int k = parts.size() - 1; k >= 0; k--) {
        pending.push(parts.get(k));
      }
    }
    return List.copyOf(order);
  }

  /** The parts of {@code node} in their order: the order of its record's components, each list in its order. */
  static List<Node> parts(Node node) {
    // One arm per kind: javac compiles a case of two patterns to a second dispatch no node can take.
    return switch (node) {
      case Identity _ -> List.of();
      case Const _ -> List.of();
      case Var _ -> List.of();
      case Break _ -> List.of();
      case ParamCall _ -> List.of();
      case Pipe p -> List.of(p.left(), p.right());
      case Comma c -> List.of(c.left(), c.right());
      case Bind b -> List.of(b.value(), b.body());
      case Binds b -> {
        List<Node> parts = new ArrayList<>(b.values());
        parts.add(b.body());
        yield parts;
      }
      case Intrinsic i -> i.args();
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
      case Call c -> {
        List<Node> parts = new ArrayList<>(c.closures());
        parts.addAll(c.values());
        yield parts;
      }
    };
  }
}
